package com.example.ttsreader.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TtsRequest(
        @NotBlank(message = "text must not be blank")
        @Size(max = 50000, message = "text must be 50000 characters or less")
        String text,
        String voiceId,
        Double rate
) {
}
