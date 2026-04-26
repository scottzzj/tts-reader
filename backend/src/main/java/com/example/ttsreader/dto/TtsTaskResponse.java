package com.example.ttsreader.dto;

import java.util.List;

public record TtsTaskResponse(
        String taskId,
        String status,
        String alignmentStatus,
        String text,
        String voiceId,
        double rate,
        int totalChunks,
        int readyChunkCount,
        long generatedDurationMs,
        String errorMessage,
        List<TtsAudioChunk> chunks,
        List<TextSegment> roughSegments,
        List<TextSegment> segments
) {
}
