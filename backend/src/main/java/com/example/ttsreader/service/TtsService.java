package com.example.ttsreader.service;

import com.example.ttsreader.NotFoundException;
import com.example.ttsreader.config.TtsProperties;
import com.example.ttsreader.dto.TextSegment;
import com.example.ttsreader.dto.TtsAudioChunk;
import com.example.ttsreader.dto.TtsRequest;
import com.example.ttsreader.dto.TtsResponse;
import com.example.ttsreader.dto.TtsTaskResponse;
import com.example.ttsreader.dto.TtsVoice;
import jakarta.annotation.PreDestroy;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.IOException;
import java.io.SequenceInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

@Service
public class TtsService {

    private static final int TARGET_CHUNK_LENGTH = 1200;
    private static final int MIN_CHUNK_LENGTH = 600;
    // Use Unicode escapes so punctuation boundaries stay correct regardless of source/file encoding.
    private static final String HARD_BOUNDARIES = "\u3002\uff01\uff1f\uff1b.!?;\n";
    private static final String SOFT_BOUNDARIES = "\uff0c\u3001\uff1a,:";
    private static final Duration TASK_TTL = Duration.ofHours(2);
    private static final Duration CLEANUP_INTERVAL = Duration.ofMinutes(5);

    private final TtsClient ttsClient;
    private final Path outputDirectory;
    private final String publicPathPrefix;
    private final int chunkConcurrency;
    private final ConcurrentMap<String, StreamTask> streamTasks = new ConcurrentHashMap<>();
    private final ExecutorService streamExecutor;
    private final ExecutorService synthesisExecutor;
    private final AtomicLong lastCleanupAtMillis = new AtomicLong(0L);

    public TtsService(TtsClient ttsClient, TtsProperties properties) throws Exception {
        this.ttsClient = ttsClient;
        this.outputDirectory = Paths.get(properties.outputDir()).toAbsolutePath().normalize();
        this.publicPathPrefix = properties.publicPathPrefix();
        this.chunkConcurrency = Math.max(1, properties.chunkConcurrency());
        this.streamExecutor = Executors.newFixedThreadPool(Math.max(2, this.chunkConcurrency * 2));
        this.synthesisExecutor = Executors.newFixedThreadPool(this.chunkConcurrency);
        Files.createDirectories(outputDirectory);
    }

    @PreDestroy
    void shutdown() {
        streamExecutor.shutdownNow();
        synthesisExecutor.shutdownNow();
    }

    public TtsResponse synthesize(TtsRequest request) {
        maybeCleanupExpiredArtifacts();
        String text = request.text();
        List<TextChunk> chunks = splitText(text);
        TtsSynthesisResult result;
        if (chunks.size() == 1) {
            result = ttsClient.synthesize(request);
        } else {
            List<TtsSynthesisResult> parts = synthesizeChunks(chunks, request);
            result = mergeResults(parts, chunks, request.voiceId(), request.rate(), true);
        }
        return new TtsResponse(
                text,
                publicPathPrefix + "/" + result.fileName(),
                result.fileName(),
                result.durationMs(),
                result.rate(),
                result.voiceId(),
                result.segments()
        );
    }

    public TtsTaskResponse createTask(TtsRequest request) {
        maybeCleanupExpiredArtifacts();
        String text = request.text();
        List<TextChunk> chunks = splitText(text);
        String taskId = UUID.randomUUID().toString().replace("-", "");
        String voiceId = StringUtils.hasText(request.voiceId()) ? request.voiceId() : "";
        double rate = request.rate() == null ? 1.0 : request.rate();
        StreamTask task = new StreamTask(taskId, text, voiceId, rate, chunks);
        streamTasks.put(taskId, task);
        streamExecutor.submit(() -> processTask(task, request, chunks));
        return task.snapshot(publicPathPrefix);
    }

    public TtsTaskResponse getTask(String taskId) {
        maybeCleanupExpiredArtifacts();
        StreamTask task = streamTasks.get(taskId);
        if (task == null) {
            throw new NotFoundException("TTS task not found");
        }
        task.touch();
        return task.snapshot(publicPathPrefix);
    }

    public List<TtsVoice> getVoices() {
        return ttsClient.getVoices();
    }

    public Resource loadAudio(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            throw new IllegalArgumentException("fileName must not be blank");
        }
        Path path = outputDirectory.resolve(fileName).normalize();
        if (!path.startsWith(outputDirectory) || !Files.exists(path)) {
            throw new NotFoundException("audio file not found");
        }
        return new FileSystemResource(path);
    }

    private void processTask(StreamTask task, TtsRequest request, List<TextChunk> chunks) {
        try {
            List<TtsSynthesisResult> results = synthesizeChunks(chunks, request);
            for (int index = 0; index < chunks.size(); index++) {
                task.applySynthesisResult(index, chunks.get(index), results.get(index));
            }
            task.complete();
        } catch (Exception exception) {
            task.fail(exception.getMessage());
        } finally {
            task.touch();
        }
    }

    private List<TextChunk> splitText(String text) {
        validateText(text);
        if (text.length() <= TARGET_CHUNK_LENGTH) {
            return List.of(new TextChunk(text, 0, text.length()));
        }
        return splitBalancedText(text, 0, TARGET_CHUNK_LENGTH, MIN_CHUNK_LENGTH);
    }

    private List<TextChunk> splitBalancedText(String text, int startIndex, int targetChunkLength, int minChunkLength) {
        List<TextChunk> chunks = new ArrayList<>();
        int start = startIndex;
        while (start < text.length()) {
            int remaining = text.length() - start;
            int remainingChunkCount = (int) Math.ceil((double) remaining / targetChunkLength);
            int plannedLength = (int) Math.ceil((double) remaining / remainingChunkCount);
            int chunkLength = Math.max(minChunkLength, Math.min(targetChunkLength, plannedLength));
            int end = resolveChunkEnd(text, start, chunkLength, minChunkLength);
            chunks.add(new TextChunk(text.substring(start, end), start, end));
            start = end;
        }
        return chunks;
    }

    private int resolveChunkEnd(String text, int start, int desiredLength, int minChunkLength) {
        int end = Math.min(text.length(), start + desiredLength);
        if (end >= text.length()) {
            return text.length();
        }

        int boundary = findBoundary(text, start, end, HARD_BOUNDARIES, minChunkLength);
        if (boundary <= start) {
            boundary = findBoundary(text, start, end, SOFT_BOUNDARIES, minChunkLength);
        }
        return boundary > start ? boundary : end;
    }

    private int findBoundary(String text, int start, int end, String boundaryChars, int minChunkLength) {
        int searchStart = Math.min(text.length() - 1, start + minChunkLength);
        for (int index = end - 1; index >= searchStart; index--) {
            if (boundaryChars.indexOf(text.charAt(index)) >= 0) {
                return index + 1;
            }
        }
        return -1;
    }

    private void validateText(String text) {
        if (!StringUtils.hasText(text)) {
            throw new IllegalArgumentException("text must not be blank");
        }
    }

    private List<TtsSynthesisResult> synthesizeChunks(List<TextChunk> chunks, TtsRequest request) {
        int concurrency = Math.min(chunkConcurrency, chunks.size());
        if (concurrency <= 1) {
            List<TtsSynthesisResult> parts = new ArrayList<>(chunks.size());
            for (TextChunk chunk : chunks) {
                parts.add(ttsClient.synthesize(new TtsRequest(chunk.text(), request.voiceId(), request.rate())));
            }
            return parts;
        }

        List<Future<IndexedSynthesisResult>> futures = new ArrayList<>(chunks.size());
        try {
            for (int index = 0; index < chunks.size(); index++) {
                TextChunk chunk = chunks.get(index);
                int chunkIndex = index;
                futures.add(synthesisExecutor.submit(() -> new IndexedSynthesisResult(
                        chunkIndex,
                        ttsClient.synthesize(new TtsRequest(chunk.text(), request.voiceId(), request.rate()))
                )));
            }

            List<TtsSynthesisResult> orderedResults = new ArrayList<>(Collections.nCopies(chunks.size(), null));
            for (Future<IndexedSynthesisResult> future : futures) {
                IndexedSynthesisResult indexedResult = future.get();
                orderedResults.set(indexedResult.index(), indexedResult.result());
            }
            return orderedResults;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("chunk synthesis interrupted", exception);
        } catch (ExecutionException exception) {
            for (Future<IndexedSynthesisResult> future : futures) {
                future.cancel(true);
            }
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("failed to synthesize one of the text chunks", cause);
        }
    }

    private void maybeCleanupExpiredArtifacts() {
        long now = System.currentTimeMillis();
        long lastCleanupAt = lastCleanupAtMillis.get();
        if ((now - lastCleanupAt) < CLEANUP_INTERVAL.toMillis()) {
            return;
        }
        if (!lastCleanupAtMillis.compareAndSet(lastCleanupAt, now)) {
            return;
        }
        cleanupExpiredArtifacts();
    }

    private TtsSynthesisResult mergeResults(
            List<TtsSynthesisResult> parts,
            List<TextChunk> chunks,
            String requestedVoiceId,
            Double requestedRate,
            boolean cleanupParts
    ) {
        String fileName = "tts-merged-" + UUID.randomUUID().toString().substring(0, 8) + ".wav";
        Path outputFile = outputDirectory.resolve(fileName);
        List<TextSegment> mergedSegments = new ArrayList<>();
        long durationMs = 0L;
        int charOffset = 0;

        for (int index = 0; index < parts.size(); index++) {
            TtsSynthesisResult part = parts.get(index);
            for (TextSegment segment : part.segments()) {
                mergedSegments.add(new TextSegment(
                        mergedSegments.size(),
                        segment.text(),
                        segment.startMs() + durationMs,
                        segment.endMs() + durationMs,
                        segment.charStart() + charOffset,
                        segment.charEnd() + charOffset
                ));
            }
            durationMs += part.durationMs();
            charOffset += chunks.get(index).text().length();
        }

        AudioFormat baseFormat = null;
        long totalFrameLength = 0L;
        List<AudioInputStream> audioStreams = new ArrayList<>();
        try {
            for (TtsSynthesisResult part : parts) {
                AudioInputStream stream = AudioSystem.getAudioInputStream(part.audioPath().toFile());
                if (baseFormat == null) {
                    baseFormat = stream.getFormat();
                } else if (!sameFormat(baseFormat, stream.getFormat())) {
                    throw new IllegalStateException("audio chunks use different wave formats");
                }
                totalFrameLength += stream.getFrameLength();
                audioStreams.add(stream);
            }

            if (baseFormat == null) {
                throw new IllegalStateException("no audio chunks were generated");
            }

            SequenceInputStream sequence = new SequenceInputStream(Collections.enumeration(audioStreams));
            try (AudioInputStream mergedStream = new AudioInputStream(sequence, baseFormat, totalFrameLength)) {
                AudioSystem.write(mergedStream, AudioFileFormat.Type.WAVE, outputFile.toFile());
            }
        } catch (Exception exception) {
            throw new IllegalStateException("failed to merge chunk audio", exception);
        } finally {
            closeStreams(audioStreams);
            if (cleanupParts) {
                cleanupParts(parts);
            }
        }

        String voiceId = StringUtils.hasText(requestedVoiceId)
                ? requestedVoiceId
                : parts.get(0).voiceId();
        double rate = requestedRate == null ? parts.get(0).rate() : requestedRate;
        return new TtsSynthesisResult(outputFile, fileName, durationMs, voiceId, rate, mergedSegments);
    }

    private boolean sameFormat(AudioFormat left, AudioFormat right) {
        return left.getEncoding().equals(right.getEncoding())
                && left.getSampleRate() == right.getSampleRate()
                && left.getSampleSizeInBits() == right.getSampleSizeInBits()
                && left.getChannels() == right.getChannels()
                && left.getFrameSize() == right.getFrameSize()
                && left.getFrameRate() == right.getFrameRate()
                && left.isBigEndian() == right.isBigEndian();
    }

    private void closeStreams(List<AudioInputStream> audioStreams) {
        for (AudioInputStream audioStream : audioStreams) {
            try {
                audioStream.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void cleanupParts(List<TtsSynthesisResult> parts) {
        for (TtsSynthesisResult part : parts) {
            try {
                Files.deleteIfExists(part.audioPath());
            } catch (IOException ignored) {
            }
        }
    }

    private void cleanupExpiredArtifacts() {
        Instant cutoff = Instant.now().minus(TASK_TTL);
        streamTasks.entrySet().removeIf(entry -> {
            if (!entry.getValue().isExpired(cutoff)) {
                return false;
            }
            entry.getValue().deleteOutputFiles(outputDirectory);
            return true;
        });
        cleanupExpiredOutputFiles(cutoff);
    }

    private void cleanupExpiredOutputFiles(Instant cutoff) {
        try (Stream<Path> files = Files.list(outputDirectory)) {
            files.filter(Files::isRegularFile)
                    .forEach(path -> deleteIfExpired(path, cutoff));
        } catch (IOException ignored) {
        }
    }

    private void deleteIfExpired(Path path, Instant cutoff) {
        try {
            if (Files.getLastModifiedTime(path).toInstant().isBefore(cutoff)) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
        }
    }

    private static List<TextSegment> offsetSegments(List<TextSegment> segments, long durationOffset, int charOffset) {
        List<TextSegment> adjusted = new ArrayList<>(segments.size());
        for (TextSegment segment : segments) {
            adjusted.add(new TextSegment(
                    adjusted.size(),
                    segment.text(),
                    segment.startMs() + durationOffset,
                    segment.endMs() + durationOffset,
                    segment.charStart() + charOffset,
                    segment.charEnd() + charOffset
            ));
        }
        return adjusted;
    }

    private record TextChunk(String text, int start, int end) {
    }

    private record IndexedSynthesisResult(int index, TtsSynthesisResult result) {
    }

    private static final class StreamTask {
        private final String taskId;
        private final String text;
        private final String voiceId;
        private final double rate;
        private final List<StreamChunkState> chunks;
        private Instant updatedAt;
        private String status;
        private String errorMessage;

        private StreamTask(String taskId, String text, String voiceId, double rate, List<TextChunk> textChunks) {
            this.taskId = taskId;
            this.text = text;
            this.voiceId = voiceId;
            this.rate = rate;
            this.updatedAt = Instant.now();
            this.status = "PROCESSING";
            this.errorMessage = "";
            this.chunks = new ArrayList<>(textChunks.size());
            for (int index = 0; index < textChunks.size(); index++) {
                TextChunk chunk = textChunks.get(index);
                this.chunks.add(new StreamChunkState(index, chunk.text(), chunk.start(), chunk.end()));
            }
        }

        private synchronized void applySynthesisResult(int index, TextChunk chunk, TtsSynthesisResult result) {
            StreamChunkState state = chunks.get(index);
            ChunkAlignmentSupport.AlignmentResult alignment = ChunkAlignmentSupport.align(
                    chunk.text(),
                    result.durationMs(),
                    result.segments()
            );
            state.status = "READY";
            state.alignmentStatus = alignment.status();
            state.fileName = result.fileName();
            state.audioUrl = "";
            state.durationMs = result.durationMs();
            state.roughSegments = alignment.roughSegments();
            state.segments = alignment.alignedSegments();
            updatedAt = Instant.now();
        }

        private synchronized void complete() {
            this.status = "COMPLETED";
            this.updatedAt = Instant.now();
        }

        private synchronized void fail(String message) {
            this.status = "FAILED";
            this.errorMessage = StringUtils.hasText(message) ? message : "TTS task failed";
            this.updatedAt = Instant.now();
        }

        private synchronized void touch() {
            this.updatedAt = Instant.now();
        }

        private synchronized boolean isExpired(Instant cutoff) {
            return updatedAt.isBefore(cutoff);
        }

        private synchronized void deleteOutputFiles(Path outputDirectory) {
            for (StreamChunkState chunk : chunks) {
                if (!StringUtils.hasText(chunk.fileName)) {
                    continue;
                }
                Path path = outputDirectory.resolve(chunk.fileName).normalize();
                if (!path.startsWith(outputDirectory)) {
                    continue;
                }
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            }
        }

        private synchronized TtsTaskResponse snapshot(String publicPathPrefix) {
            List<TtsAudioChunk> snapshotChunks = new ArrayList<>(chunks.size());
            List<TextSegment> readyRoughSegments = new ArrayList<>();
            List<TextSegment> readySegments = new ArrayList<>();
            int readyChunkCount = 0;
            long playableDurationMs = 0L;
            boolean prefixContinuous = true;
            String taskAlignmentStatus = "PENDING";

            for (StreamChunkState chunk : chunks) {
                boolean chunkReady = "READY".equals(chunk.status);
                if (chunkReady) {
                    readyChunkCount += 1;
                }

                String snapshotStatus = chunk.status;
                long startMs = -1L;
                long endMs = -1L;
                if (chunkReady && prefixContinuous) {
                    startMs = playableDurationMs;
                    endMs = startMs + chunk.durationMs;
                    playableDurationMs = endMs;
                    readyRoughSegments.addAll(TtsService.offsetSegments(chunk.roughSegments, startMs, chunk.charStart));
                    readySegments.addAll(TtsService.offsetSegments(chunk.segments, startMs, chunk.charStart));
                } else if (chunkReady) {
                    snapshotStatus = "BUFFERED";
                } else {
                    prefixContinuous = false;
                }

                taskAlignmentStatus = mergeAlignmentStatus(taskAlignmentStatus, chunk.alignmentStatus);

                snapshotChunks.add(new TtsAudioChunk(
                        chunk.index,
                        snapshotStatus,
                        chunk.alignmentStatus,
                        chunk.text,
                        chunk.charStart,
                        chunk.charEnd,
                        chunk.durationMs,
                        startMs,
                        endMs,
                        resolveChunkAudioUrl(chunk, publicPathPrefix),
                        chunk.fileName,
                        chunk.roughSegments,
                        chunk.segments
                ));
            }

            return new TtsTaskResponse(
                    taskId,
                    status,
                    taskAlignmentStatus,
                    text,
                    voiceId,
                    rate,
                    chunks.size(),
                    readyChunkCount,
                    playableDurationMs,
                    errorMessage,
                    snapshotChunks,
                    readyRoughSegments,
                    readySegments
            );
        }

        private String resolveChunkAudioUrl(StreamChunkState chunk, String publicPathPrefix) {
            if (StringUtils.hasText(chunk.audioUrl)) {
                return chunk.audioUrl;
            }
            return StringUtils.hasText(chunk.fileName) ? publicPathPrefix + "/" + chunk.fileName : null;
        }
    }

    private static final class StreamChunkState {
        private final int index;
        private final String text;
        private final int charStart;
        private final int charEnd;
        private String status;
        private String alignmentStatus;
        private String fileName;
        private String audioUrl;
        private long durationMs;
        private List<TextSegment> roughSegments;
        private List<TextSegment> segments;

        private StreamChunkState(int index, String text, int charStart, int charEnd) {
            this.index = index;
            this.text = text;
            this.charStart = charStart;
            this.charEnd = charEnd;
            this.status = "PENDING";
            this.alignmentStatus = "PENDING";
            this.fileName = "";
            this.audioUrl = "";
            this.durationMs = 0L;
            this.roughSegments = List.of();
            this.segments = List.of();
        }
    }

    private static String mergeAlignmentStatus(String currentStatus, String nextStatus) {
        if ("FAILED".equals(currentStatus) || "FAILED".equals(nextStatus)) {
            return "FAILED";
        }
        if ("ROUGH".equals(currentStatus) || "ROUGH".equals(nextStatus)) {
            return "ROUGH";
        }
        if ("ALIGNED".equals(currentStatus) || "ALIGNED".equals(nextStatus)) {
            return "ALIGNED";
        }
        return nextStatus == null ? currentStatus : nextStatus;
    }
}
