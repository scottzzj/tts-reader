package com.example.ttsreader.service;

import com.example.ttsreader.dto.TextSegment;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

final class ChunkAlignmentSupport {

    private static final int TARGET_MEANINGFUL_CHARS_PER_SEGMENT = 4;
    private static final long MIN_SEGMENT_DURATION_MS = 90L;
    private static final String PUNCTUATION_CHARS =
            "\uFF0C\u3002\uFF01\uFF1F\uFF1B\uFF1A\u3001,.!?;:()\uFF08\uFF09\u201C\u201D\"'\u300A\u300B\u3010\u3011\u2014-";

    private ChunkAlignmentSupport() {
    }

    static AlignmentResult align(String text, long durationMs, List<TextSegment> roughSegments) {
        List<TextSegment> fallbackSegments = copySegments(roughSegments);
        if (!StringUtils.hasText(text) || durationMs <= 0L) {
            return new AlignmentResult(
                    fallbackSegments.isEmpty() ? "NONE" : "ROUGH",
                    fallbackSegments,
                    fallbackSegments
            );
        }

        List<Unit> units = splitIntoUnits(text);
        if (units.isEmpty()) {
            return new AlignmentResult(
                    fallbackSegments.isEmpty() ? "NONE" : "ROUGH",
                    fallbackSegments,
                    fallbackSegments
            );
        }

        double totalWeight = units.stream().mapToDouble(Unit::weight).sum();
        if (totalWeight <= 0D) {
            return new AlignmentResult(
                    fallbackSegments.isEmpty() ? "NONE" : "ROUGH",
                    fallbackSegments,
                    fallbackSegments
            );
        }

        List<TextSegment> alignedSegments = new ArrayList<>(units.size());
        long cursor = 0L;
        for (int index = 0; index < units.size(); index++) {
            Unit unit = units.get(index);
            long unitDuration;
            if (index == units.size() - 1) {
                unitDuration = Math.max(0L, durationMs - cursor);
            } else {
                double ratio = unit.weight() / totalWeight;
                unitDuration = Math.max(MIN_SEGMENT_DURATION_MS, Math.round(durationMs * ratio));
                long remaining = durationMs - cursor;
                long reservedMin = (long) (units.size() - index - 1) * MIN_SEGMENT_DURATION_MS;
                unitDuration = Math.min(unitDuration, Math.max(MIN_SEGMENT_DURATION_MS, remaining - reservedMin));
            }
            long endMs = index == units.size() - 1 ? durationMs : Math.min(durationMs, cursor + unitDuration);
            alignedSegments.add(new TextSegment(
                    index,
                    unit.text(),
                    cursor,
                    endMs,
                    unit.charStart(),
                    unit.charEnd()
            ));
            cursor = endMs;
        }

        return new AlignmentResult("ALIGNED", fallbackSegments, alignedSegments);
    }

    private static List<TextSegment> copySegments(List<TextSegment> segments) {
        return segments == null ? List.of() : List.copyOf(segments);
    }

    private static List<Unit> splitIntoUnits(String text) {
        List<Unit> units = new ArrayList<>();
        int index = 0;
        while (index < text.length()) {
            char current = text.charAt(index);

            if (Character.isWhitespace(current)) {
                int start = index;
                while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                    index++;
                }
                appendOrMergeWhitespace(units, text.substring(start, index), start, index);
                continue;
            }

            if (isAsciiWordChar(current)) {
                int start = index;
                while (index < text.length() && isAsciiWordChar(text.charAt(index))) {
                    index++;
                }
                units.add(new Unit(text.substring(start, index), start, index, Math.max(1.0D, (index - start) * 0.5D)));
                continue;
            }

            if (isPunctuation(current)) {
                int start = index;
                index++;
                units.add(new Unit(text.substring(start, index), start, index, punctuationWeight(current)));
                continue;
            }

            int start = index;
            int meaningfulCount = 0;
            while (index < text.length()) {
                char value = text.charAt(index);
                if (Character.isWhitespace(value) || isAsciiWordChar(value) || isPunctuation(value)) {
                    break;
                }
                index++;
                meaningfulCount++;
                if (meaningfulCount >= TARGET_MEANINGFUL_CHARS_PER_SEGMENT) {
                    break;
                }
            }
            units.add(new Unit(text.substring(start, index), start, index, meaningfulCount));
        }
        return units;
    }

    private static void appendOrMergeWhitespace(List<Unit> units, String value, int start, int end) {
        if (units.isEmpty()) {
            units.add(new Unit(value, start, end, 0.25D));
            return;
        }

        Unit last = units.get(units.size() - 1);
        units.set(units.size() - 1, new Unit(
                last.text() + value,
                last.charStart(),
                end,
                last.weight() + 0.25D
        ));
    }

    private static boolean isAsciiWordChar(char value) {
        return (value >= 'a' && value <= 'z')
                || (value >= 'A' && value <= 'Z')
                || (value >= '0' && value <= '9');
    }

    private static boolean isPunctuation(char value) {
        return PUNCTUATION_CHARS.indexOf(value) >= 0;
    }

    private static double punctuationWeight(char value) {
        return switch (value) {
            case '\u3002', '\uFF01', '\uFF1F', '\uFF1B', '.', '!', '?', ';' -> 1.3D;
            case '\uFF0C', '\u3001', '\uFF1A', ',', ':', '\u2014', '-' -> 0.9D;
            default -> 0.7D;
        };
    }

    record AlignmentResult(String status, List<TextSegment> roughSegments, List<TextSegment> alignedSegments) {
    }

    private record Unit(String text, int charStart, int charEnd, double weight) {
    }
}
