package com.example.ttsreader.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tts")
public record TtsProperties(
        String mode,
        String outputDir,
        String publicPathPrefix,
        int chunkConcurrency,
        Http http,
        Mock mock
) {
    public record Http(
            String baseUrl,
            int connectTimeoutSeconds,
            int readTimeoutSeconds
    ) {
    }

    public record Mock(
            int sampleRate,
            int defaultCharDurationMs,
            int punctuationDurationMs,
            int whitespaceDurationMs,
            int pauseGapMs
    ) {
    }
}
