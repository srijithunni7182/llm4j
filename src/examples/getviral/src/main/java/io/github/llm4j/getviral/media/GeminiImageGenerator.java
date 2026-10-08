package io.github.llm4j.getviral.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.imageio.ImageIO;

/**
 * AI images from the Gemini API ({@code models/{model}:generateContent} with image output), using the
 * same GEMINI_API_KEY as the agents. The image arrives as base64 {@code inlineData} in the response.
 */
public class GeminiImageGenerator implements ImageGenerator {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BASE = "https://generativelanguage.googleapis.com/v1beta/models/";

    private final String apiKey;
    private final String model;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public GeminiImageGenerator(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public String name() {
        return "Gemini · " + model;
    }

    @Override
    public boolean ai() {
        return true;
    }

    @Override
    public Optional<BufferedImage> generate(ImageRequest request) {
        if (apiKey == null || apiKey.isBlank()) return Optional.empty();
        try {
            Map<String, Object> body = Map.of(
                    "contents", List.of(Map.of("parts", List.of(Map.of("text", request.prompt())))),
                    "generationConfig", Map.of(
                            "responseModalities", List.of("TEXT", "IMAGE"),
                            "imageConfig", Map.of("aspectRatio", request.aspectRatio())));
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(BASE + model + ":generateContent"))
                    .timeout(Duration.ofSeconds(90))
                    .header("Content-Type", "application/json")
                    .header("x-goog-api-key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)))
                    .build();
            HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) return Optional.empty();
            for (JsonNode part : JSON.readTree(response.body()).path("candidates").path(0).path("content").path("parts")) {
                JsonNode data = part.path("inlineData").path("data");
                if (data.isTextual()) {
                    byte[] bytes = Base64.getDecoder().decode(data.asText());
                    return Optional.ofNullable(ImageIO.read(new ByteArrayInputStream(bytes)));
                }
            }
            return Optional.empty();
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
