package io.github.llm4j.getviral.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Opt-in AI video clips from Google Veo through the Gemini API (a paid feature — enable with
 * GETVIRAL_VEO=true). Generation is a long-running operation:
 * {@code POST models/{model}:predictLongRunning} → poll the operation until {@code done} → download
 * {@code response.generateVideoResponse.generatedSamples[0].video.uri}.
 */
public class VeoClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BASE = "https://generativelanguage.googleapis.com/v1beta/";

    private final String apiKey;
    private final String model;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    public VeoClient(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model;
    }

    public String model() {
        return model;
    }

    public Path generate(String prompt, String aspectRatio, Path output) throws IOException, InterruptedException {
        Map<String, Object> body = Map.of(
                "instances", List.of(Map.of("prompt", prompt)),
                "parameters", Map.of("aspectRatio", aspectRatio));
        JsonNode operation = send(HttpRequest.newBuilder(URI.create(BASE + "models/" + model + ":predictLongRunning"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body))));
        String name = operation.path("name").asText();
        if (name.isBlank()) throw new IOException("Veo did not return an operation: " + operation);

        for (int i = 0; i < 60; i++) {           // up to ~10 minutes
            Thread.sleep(10_000);
            JsonNode status = send(HttpRequest.newBuilder(URI.create(BASE + name)).GET());
            if (status.path("done").asBoolean()) {
                if (status.has("error")) throw new IOException("Veo failed: " + status.path("error").path("message").asText());
                String uri = status.path("response").path("generateVideoResponse").path("generatedSamples")
                        .path(0).path("video").path("uri").asText();
                if (uri.isBlank()) throw new IOException("Veo finished without a video: " + status);
                HttpResponse<Path> download = http.send(HttpRequest.newBuilder(URI.create(uri))
                        .header("x-goog-api-key", apiKey).GET().build(), HttpResponse.BodyHandlers.ofFile(output));
                if (download.statusCode() / 100 != 2) throw new IOException("Veo download failed: HTTP " + download.statusCode());
                return output;
            }
        }
        throw new IOException("Veo timed out waiting for " + name);
    }

    private JsonNode send(HttpRequest.Builder builder) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(builder.header("x-goog-api-key", apiKey)
                .timeout(Duration.ofSeconds(60)).build(), HttpResponse.BodyHandlers.ofString());
        JsonNode json = JSON.readTree(response.body().isBlank() ? "{}" : response.body());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Veo API error (HTTP " + response.statusCode() + "): "
                    + json.path("error").path("message").asText(response.body()));
        }
        return json;
    }
}
