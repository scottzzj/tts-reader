package com.example.ttsreader.dto;

import java.util.List;

public record TtsAudioChunk(
        int index,
        String status,
        String alignmentStatus,
        String text,
        int charStart,
        int charEnd,
        long durationMs,
        long startMs,
        long endMs,
        String audioUrl,
        String fileName,
        List<TextSegment> roughSegments,
        List<TextSegment> segments
) {
}
