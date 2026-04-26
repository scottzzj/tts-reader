package com.example.ttsreader.service.mock;

import com.example.ttsreader.dto.TextSegment;

import java.util.List;

public record MockWaveResult(
        long durationMs,
        List<TextSegment> segments
) {
}
