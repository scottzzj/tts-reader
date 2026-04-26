package com.example.ttsreader.service;

import com.example.ttsreader.dto.TextSegment;

import java.nio.file.Path;
import java.util.List;

public record TtsSynthesisResult(
        Path audioPath,
        String fileName,
        long durationMs,
        String voiceId,
        double rate,
        List<TextSegment> segments
) {
}
