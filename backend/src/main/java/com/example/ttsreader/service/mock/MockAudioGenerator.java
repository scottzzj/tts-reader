package com.example.ttsreader.service.mock;

import com.example.ttsreader.config.TtsProperties;
import com.example.ttsreader.dto.TextSegment;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class MockAudioGenerator {

    private final TtsProperties.Mock properties;

    public MockAudioGenerator(TtsProperties.Mock properties) {
        this.properties = properties;
    }

    public MockWaveResult generate(Path outputPath, String text) throws IOException {
        List<TextSegment> segments = new ArrayList<>();
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();

        int sampleRate = properties.sampleRate();
        long currentMs = 0L;
        int charCursor = 0;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            int durationMs = resolveDuration(current);
            long startMs = currentMs;
            long endMs = currentMs + durationMs;
            segments.add(new TextSegment(index, String.valueOf(current), startMs, endMs, charCursor, charCursor + 1));
            appendFrame(pcm, current, durationMs, sampleRate);
            if (!Character.isWhitespace(current)) {
                appendSilence(pcm, properties.pauseGapMs(), sampleRate);
                endMs += properties.pauseGapMs();
            }
            currentMs = endMs;
            charCursor++;
        }

        Files.createDirectories(outputPath.getParent());
        Files.write(outputPath, toWaveBytes(pcm.toByteArray(), sampleRate));
        return new MockWaveResult(currentMs, segments);
    }

    private int resolveDuration(char current) {
        if (Character.isWhitespace(current)) {
            return properties.whitespaceDurationMs();
        }
        if ("，。！？；：,.!?;:".indexOf(current) >= 0) {
            return properties.punctuationDurationMs();
        }
        return properties.defaultCharDurationMs() + Math.abs(current % 4) * 18;
    }

    private void appendFrame(ByteArrayOutputStream output, char current, int durationMs, int sampleRate) throws IOException {
        int sampleCount = Math.max(1, durationMs * sampleRate / 1000);
        boolean silent = Character.isWhitespace(current) || "，。！？；：,.!?;:".indexOf(current) >= 0;
        double frequency = 220 + (current % 7) * 32;
        for (int i = 0; i < sampleCount; i++) {
            short sample = 0;
            if (!silent) {
                double fade = Math.min(1.0, i / (double) Math.max(1, sampleCount / 8));
                double release = Math.min(1.0, (sampleCount - i) / (double) Math.max(1, sampleCount / 7));
                double amplitude = 0.16 * Math.min(fade, release);
                sample = (short) (Math.sin(2 * Math.PI * frequency * i / sampleRate) * amplitude * Short.MAX_VALUE);
            }
            writeLittleEndianShort(output, sample);
        }
    }

    private void appendSilence(ByteArrayOutputStream output, int durationMs, int sampleRate) throws IOException {
        int sampleCount = Math.max(1, durationMs * sampleRate / 1000);
        for (int i = 0; i < sampleCount; i++) {
            writeLittleEndianShort(output, (short) 0);
        }
    }

    private byte[] toWaveBytes(byte[] pcmData, int sampleRate) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream data = new DataOutputStream(baos)) {
            int channels = 1;
            int bitsPerSample = 16;
            int byteRate = sampleRate * channels * bitsPerSample / 8;
            int blockAlign = channels * bitsPerSample / 8;
            int dataSize = pcmData.length;

            data.writeBytes("RIFF");
            writeLittleEndianInt(data, 36 + dataSize);
            data.writeBytes("WAVE");
            data.writeBytes("fmt ");
            writeLittleEndianInt(data, 16);
            writeLittleEndianShort(data, (short) 1);
            writeLittleEndianShort(data, (short) channels);
            writeLittleEndianInt(data, sampleRate);
            writeLittleEndianInt(data, byteRate);
            writeLittleEndianShort(data, (short) blockAlign);
            writeLittleEndianShort(data, (short) bitsPerSample);
            data.writeBytes("data");
            writeLittleEndianInt(data, dataSize);
            data.write(pcmData);
            data.flush();
            return baos.toByteArray();
        }
    }

    private void writeLittleEndianShort(ByteArrayOutputStream output, short value) {
        output.write(value & 0xff);
        output.write((value >> 8) & 0xff);
    }

    private void writeLittleEndianShort(DataOutputStream output, short value) throws IOException {
        output.writeByte(value & 0xff);
        output.writeByte((value >> 8) & 0xff);
    }

    private void writeLittleEndianInt(DataOutputStream output, int value) throws IOException {
        output.writeByte(value & 0xff);
        output.writeByte((value >> 8) & 0xff);
        output.writeByte((value >> 16) & 0xff);
        output.writeByte((value >> 24) & 0xff);
    }
}
