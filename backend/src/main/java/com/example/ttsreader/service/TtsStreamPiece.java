package com.example.ttsreader.service;

import com.example.ttsreader.dto.TextSegment;

import java.nio.file.Path;
import java.util.List;

public record TtsStreamPiece(
        int index,
        String text,
        int charStart,
        int charEnd,
        Path audioPath,
        String fileName,
        String audioUrl,
        long durationMs,
        String voiceId,
        double rate,
        List<TextSegment> segments
) {
}
