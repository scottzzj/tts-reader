package com.example.ttsreader.service.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.ttsreader.config.TtsProperties;
import com.example.ttsreader.dto.TextSegment;
import com.example.ttsreader.dto.TtsRequest;
import com.example.ttsreader.dto.TtsVoice;
import com.example.ttsreader.service.TtsClient;
import com.example.ttsreader.service.TtsSynthesisResult;
import com.example.ttsreader.service.TtsStreamPiece;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public class HttpTtsClient implements TtsClient {

    private final RestClient restClient;
    private final TtsProperties properties;
    private final HttpClient streamHttpClient;
    private final ObjectMapper objectMapper;
    private final URI baseUri;

    public HttpTtsClient(RestClient restClient, TtsProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
        this.streamHttpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.http().connectTimeoutSeconds()))
                .build();
        this.objectMapper = new ObjectMapper();
        this.baseUri = URI.create(properties.http().baseUrl().endsWith("/")
                ? properties.http().baseUrl()
                : properties.http().baseUrl() + "/");
    }

    @Override
    public TtsSynthesisResult synthesize(TtsRequest request) {
        HttpSynthesisResponse response;
        try {
            response = restClient.post()
                    .uri("/synthesize")
                    .body(request)
                    .retrieve()
                    .body(HttpSynthesisResponse.class);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().is4xxClientError()) {
                throw new IllegalArgumentException(extractDetailMessage(exception));
            }
            throw exception;
        }

        if (response == null) {
            throw new IllegalStateException("Empty response from TTS service");
        }

        try {
            Path outputDirectory = Paths.get(properties.outputDir()).toAbsolutePath().normalize();
            Files.createDirectories(outputDirectory);
            String fileName = sanitizeFileName(response.fileName());
            Path outputFile = outputDirectory.resolve(fileName).normalize();
            if (!outputFile.startsWith(outputDirectory)) {
                throw new IllegalStateException("Resolved audio path is outside the configured output directory");
            }
            Files.createDirectories(outputFile.getParent());
            byte[] bytes;
            if (StringUtils.hasText(response.audioBase64())) {
                bytes = Base64.getDecoder().decode(response.audioBase64());
            } else if (StringUtils.hasText(response.audioUrl())) {
                bytes = restClient.get().uri(response.audioUrl()).retrieve().body(byte[].class);
            } else {
                throw new IllegalStateException("No audio returned by TTS service");
            }
            Files.write(outputFile, bytes);
            return new TtsSynthesisResult(
                    outputFile,
                    fileName,
                    response.durationMs(),
                    StringUtils.hasText(response.voiceId()) ? response.voiceId() : request.voiceId(),
                    request.rate() == null ? 1.0 : request.rate(),
                    response.segments() == null ? List.of() : response.segments()
            );
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist HTTP TTS response", e);
        }
    }

    @Override
    public void streamSynthesize(TtsRequest request, Consumer<TtsStreamPiece> pieceConsumer) {
        try {
            String requestBody = objectMapper.writeValueAsString(request);
            HttpRequest httpRequest = HttpRequest.newBuilder(baseUri.resolve("stream"))
                    .timeout(Duration.ofSeconds(properties.http().readTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<java.io.InputStream> response = streamHttpClient.send(
                    httpRequest,
                    HttpResponse.BodyHandlers.ofInputStream()
            );

            if (response.statusCode() >= 400) {
                String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                throw new IllegalArgumentException(extractDetailMessage(errorBody));
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    JsonNode event = objectMapper.readTree(line);
                    String type = event.path("type").asText("");
                    if ("start".equals(type) || "done".equals(type)) {
                        continue;
                    }
                    if ("error".equals(type)) {
                        throw new IllegalStateException(event.path("message").asText("TTS stream failed"));
                    }
                    if (!"chunk".equals(type)) {
                        continue;
                    }

                    List<TextSegment> segments = parseSegments(event.path("segments"));
                    String audioUrl = resolveExternalAudioUrl(event.path("audioUrl").asText(""));
                    String fileName = sanitizeFileName(event.path("fileName").asText(""));
                    pieceConsumer.accept(new TtsStreamPiece(
                            event.path("index").asInt(),
                            event.path("text").asText(""),
                            event.path("charStart").asInt(),
                            event.path("charEnd").asInt(),
                            null,
                            fileName,
                            audioUrl,
                            event.path("durationMs").asLong(),
                            StringUtils.hasText(request.voiceId()) ? request.voiceId() : "",
                            request.rate() == null ? 1.0 : request.rate(),
                            segments
                    ));
                }
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to consume HTTP TTS stream", exception);
        }
    }

    @Override
    public List<TtsVoice> getVoices() {
        HttpVoiceResponse[] voices = restClient.get()
                .uri("/voices")
                .retrieve()
                .body(HttpVoiceResponse[].class);
        return voices == null ? List.of() : Arrays.stream(voices)
                .map(voice -> new TtsVoice(voice.id(), voice.name(), voice.accentColor(), voice.initials()))
                .toList();
    }

    private String sanitizeFileName(String upstreamFileName) {
        if (!StringUtils.hasText(upstreamFileName)) {
            return "tts-" + UUID.randomUUID() + ".wav";
        }
        String sanitized = Paths.get(upstreamFileName).getFileName().toString();
        return StringUtils.hasText(sanitized) ? sanitized : "tts-" + UUID.randomUUID() + ".wav";
    }

    private List<TextSegment> parseSegments(JsonNode segmentsNode) {
        if (segmentsNode == null || !segmentsNode.isArray()) {
            return List.of();
        }
        return Arrays.stream(objectMapper.convertValue(segmentsNode, TextSegment[].class))
                .toList();
    }

    private String resolveExternalAudioUrl(String audioUrl) {
        if (!StringUtils.hasText(audioUrl)) {
            return "";
        }
        if (audioUrl.startsWith("http://") || audioUrl.startsWith("https://")) {
            return audioUrl;
        }
        return baseUri.resolve(audioUrl.startsWith("/") ? audioUrl.substring(1) : audioUrl).toString();
    }

    private String extractDetailMessage(RestClientResponseException exception) {
        return extractDetailMessage(exception.getResponseBodyAsString());
    }

    private String extractDetailMessage(String body) {
        if (!StringUtils.hasText(body)) {
            return "TTS service rejected the request";
        }
        String marker = "\"detail\":\"";
        int start = body.indexOf(marker);
        if (start < 0) {
            return body;
        }
        start += marker.length();
        int end = body.indexOf('"', start);
        if (end < 0) {
            return body;
        }
        return body.substring(start, end)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }
}
