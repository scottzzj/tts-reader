package com.example.ttsreader.dto;

import java.util.List;

public record TtsResponse(
        String text,
        String audioUrl,
        String fileName,
        long durationMs,
        double rate,
        String voiceId,
        List<TextSegment> segments
) {
}
