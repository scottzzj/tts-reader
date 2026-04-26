package com.example.ttsreader.dto;

public record TextSegment(
        int index,
        String text,
        long startMs,
        long endMs,
        int charStart,
        int charEnd
) {
}
