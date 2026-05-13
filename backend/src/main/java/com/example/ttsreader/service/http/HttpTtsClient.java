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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public class HttpTtsClient implements TtsClient {

    private static final Logger log = LoggerFactory.getLogger(HttpTtsClient.class);

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
            String fileName = buildUniqueFileName(response.fileName());
            Path outputFile = outputDirectory.resolve(fileName).normalize();
            if (!outputFile.startsWith(outputDirectory)) {
                throw new IllegalStateException("Resolved audio path is outside the configured output directory");
            }
            persistAudio(response, outputFile);
            String normalizedFileName = normalizeForWaveMerge(outputFile);
            Path normalizedOutputFile = outputDirectory.resolve(normalizedFileName).normalize();
            if (StringUtils.hasText(response.audioBase64())) {
                Files.deleteIfExists(outputFile);
            }
            return new TtsSynthesisResult(
                    normalizedOutputFile,
                    normalizedFileName,
                    response.durationMs(),
                    StringUtils.hasText(response.voiceId()) ? response.voiceId() : request.voiceId(),
                    request.rate() == null ? 1.0 : request.rate(),
                    response.segments() == null ? List.of() : response.segments()
            );
        } catch (Exception e) {
            log.error(
                    "Failed to persist HTTP TTS response. upstreamFileName={}, audioUrl={}, outputDir={}",
                    response.fileName(),
                    response.audioUrl(),
                    properties.outputDir(),
                    e
            );
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

    private String buildUniqueFileName(String upstreamFileName) {
        String sanitized = sanitizeFileName(upstreamFileName);
        int extensionIndex = sanitized.lastIndexOf('.');
        String extension = extensionIndex >= 0 ? sanitized.substring(extensionIndex) : ".wav";
        return "tts-" + UUID.randomUUID() + extension.toLowerCase();
    }

    private void persistAudio(HttpSynthesisResponse response, Path outputFile) throws Exception {
        Files.createDirectories(outputFile.getParent());
        Path tempFile = Files.createTempFile(outputFile.getParent(), "tts-http-", ".tmp");
        try {
            if (StringUtils.hasText(response.audioBase64())) {
                Files.write(tempFile, Base64.getDecoder().decode(response.audioBase64()));
            } else if (StringUtils.hasText(response.audioUrl())) {
                byte[] audioBytes = restClient.get()
                        .uri(response.audioUrl())
                        .retrieve()
                        .body(byte[].class);
                if (audioBytes == null || audioBytes.length == 0) {
                    throw new IllegalStateException("No audio bytes returned by TTS service");
                }
                Files.write(tempFile, audioBytes);
            } else {
                throw new IllegalStateException("No audio returned by TTS service");
            }
            moveTempFile(tempFile, outputFile);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private void moveTempFile(Path tempFile, Path outputFile) throws Exception {
        try {
            Files.move(tempFile, outputFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            log.warn("Atomic move is not supported for {}, falling back to replace-existing move", outputFile);
            Files.move(tempFile, outputFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String normalizeForWaveMerge(Path sourceFile) throws Exception {
        String fileName = sourceFile.getFileName().toString();
        if (fileName.toLowerCase().endsWith(".wav")) {
            return fileName;
        }

        Path normalizedFile = sourceFile.resolveSibling(fileName + ".wav");
        try (javax.sound.sampled.AudioInputStream audioInputStream =
                     javax.sound.sampled.AudioSystem.getAudioInputStream(sourceFile.toFile())) {
            javax.sound.sampled.AudioSystem.write(
                    audioInputStream,
                    javax.sound.sampled.AudioFileFormat.Type.WAVE,
                    normalizedFile.toFile()
            );
        } catch (Exception exception) {
            Files.deleteIfExists(sourceFile);
            throw new IllegalStateException("Chunked synthesis currently requires WAV-compatible audio from the upstream service", exception);
        }
        return normalizedFile.getFileName().toString();
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
