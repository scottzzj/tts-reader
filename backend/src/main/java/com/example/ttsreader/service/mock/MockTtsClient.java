package com.example.ttsreader.service.mock;

import com.example.ttsreader.config.TtsProperties;
import com.example.ttsreader.dto.TextSegment;
import com.example.ttsreader.dto.TtsRequest;
import com.example.ttsreader.dto.TtsVoice;
import com.example.ttsreader.service.TtsClient;
import com.example.ttsreader.service.TtsSynthesisResult;
import com.example.ttsreader.service.TtsStreamPiece;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

public class MockTtsClient implements TtsClient {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);
    private static final List<TtsVoice> VOICES = List.of(
            new TtsVoice("aria", "Aria", "#2563eb", "A"),
            new TtsVoice("luna", "Luna", "#0891b2", "L"),
            new TtsVoice("jun", "Jun", "#0f766e", "J")
    );

    private final TtsProperties properties;
    private final MockAudioGenerator audioGenerator;

    public MockTtsClient(TtsProperties properties, MockAudioGenerator audioGenerator) {
        this.properties = properties;
        this.audioGenerator = audioGenerator;
    }

    @Override
    public TtsSynthesisResult synthesize(TtsRequest request) {
        try {
            Path outputDirectory = Paths.get(properties.outputDir()).toAbsolutePath().normalize();
            Files.createDirectories(outputDirectory);
            String fileName = "tts-" + LocalDateTime.now().format(FORMATTER) + "-"
                    + UUID.randomUUID().toString().substring(0, 8) + ".wav";
            Path outputFile = outputDirectory.resolve(fileName);
            MockWaveResult result = audioGenerator.generate(outputFile, request.text());
            String voiceId = request.voiceId() == null || request.voiceId().isBlank() ? VOICES.getFirst().id() : request.voiceId();
            double rate = request.rate() == null ? 1.0 : request.rate();
            return new TtsSynthesisResult(outputFile, fileName, result.durationMs(), voiceId, rate, result.segments());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate mock TTS audio", e);
        }
    }

    @Override
    public void streamSynthesize(TtsRequest request, Consumer<TtsStreamPiece> pieceConsumer) {
        TtsSynthesisResult result = synthesize(request);
        pieceConsumer.accept(new TtsStreamPiece(
                0,
                request.text(),
                0,
                request.text().length(),
                result.audioPath(),
                result.fileName(),
                null,
                result.durationMs(),
                result.voiceId(),
                result.rate(),
                result.segments()
        ));
    }

    @Override
    public List<TtsVoice> getVoices() {
        return VOICES;
    }
}
