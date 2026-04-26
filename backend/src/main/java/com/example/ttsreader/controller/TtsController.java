package com.example.ttsreader.controller;

import com.example.ttsreader.dto.TtsRequest;
import com.example.ttsreader.dto.TtsResponse;
import com.example.ttsreader.dto.TtsTaskResponse;
import com.example.ttsreader.dto.TtsVoice;
import com.example.ttsreader.service.TtsService;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@RestController
@RequestMapping("/api/tts")
public class TtsController {

    private final TtsService ttsService;

    public TtsController(TtsService ttsService) {
        this.ttsService = ttsService;
    }

    @PostMapping
    public TtsResponse synthesize(@Valid @RequestBody TtsRequest request) {
        return ttsService.synthesize(request);
    }

    @PostMapping("/tasks")
    public TtsTaskResponse createTask(@Valid @RequestBody TtsRequest request) {
        return ttsService.createTask(request);
    }

    @GetMapping("/tasks/{taskId}")
    public TtsTaskResponse getTask(@PathVariable String taskId) {
        return ttsService.getTask(taskId);
    }

    @GetMapping("/voices")
    public List<TtsVoice> voices() {
        return ttsService.getVoices();
    }

    @GetMapping("/files/{fileName}")
    public ResponseEntity<Resource> audio(@PathVariable String fileName) {
        Resource resource = ttsService.loadAudio(fileName);
        MediaType mediaType = resolveMediaType(resource);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + resource.getFilename() + "\"")
                .contentType(mediaType)
                .body(resource);
    }

    private MediaType resolveMediaType(Resource resource) {
        try {
            Path path = resource.getFile().toPath();
            String contentType = Files.probeContentType(path);
            if (contentType != null && !contentType.isBlank()) {
                return MediaType.parseMediaType(contentType);
            }
        } catch (Exception ignored) {
        }

        String name = resource.getFilename() == null ? "" : resource.getFilename().toLowerCase();
        if (name.endsWith(".mp3")) {
            return MediaType.parseMediaType("audio/mpeg");
        }
        if (name.endsWith(".ogg")) {
            return MediaType.parseMediaType("audio/ogg");
        }
        return MediaType.parseMediaType("audio/wav");
    }
}
