package com.example.ttsreader.service.http;

import com.example.ttsreader.dto.TextSegment;

import java.util.List;

public record HttpSynthesisResponse(
        String fileName,
        String audioUrl,
        String audioBase64,
        long durationMs,
        String voiceId,
        List<TextSegment> segments
) {
}
