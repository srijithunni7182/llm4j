package io.github.llm4j.getviral.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.getviral.config.GetViralConfig;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Minimal client for the Instagram Platform Content Publishing API (Graph API).
 *
 * <p>Publishing is a three-step flow for a professional (Business/Creator) account:
 * <ol>
 *   <li>{@code POST /{ig-user-id}/media} creates a media container
 *       ({@code image_url} or {@code video_url}, {@code media_type} = IMAGE | REELS | STORIES,
 *       {@code caption}, optional {@code cover_url}, {@code share_to_feed}).</li>
 *   <li>{@code GET /{container-id}?fields=status_code} until it is {@code FINISHED}
 *       (video processing can take a while; ERROR / EXPIRED are terminal).</li>
 *   <li>{@code POST /{ig-user-id}/media_publish?creation_id={container-id}} publishes it.</li>
 * </ol>
 * {@code GET /{ig-user-id}/content_publishing_limit} reports the rolling 24h quota. Media must be
 * hosted at a public URL; the token needs the content-publish permission for the account.
 * Host and API version are configurable (IG_GRAPH_HOST / IG_GRAPH_VERSION).
 */
public class InstagramGraphClient {

    public static final int MAX_CAPTION_CHARS = 2200;
    public static final int MAX_HASHTAGS = 30;
    public static final int MAX_MENTIONS = 20;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final GetViralConfig config;
    private final HttpClient http;

    public InstagramGraphClient(GetViralConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public boolean configured() {
        return config.instagramConfigured();
    }

    public String baseUrl() {
        return config.igGraphHost().replaceAll("/+$", "") + "/" + config.igGraphVersion();
    }

    public String userId() {
        return config.igUserId();
    }

    public JsonNode publishingLimit() throws IOException, InterruptedException {
        return get("/" + userId() + "/content_publishing_limit", Map.of("fields", "quota_usage,config"));
    }

    public JsonNode createContainer(Map<String, String> params) throws IOException, InterruptedException {
        return post("/" + userId() + "/media", params);
    }

    public JsonNode containerStatus(String containerId) throws IOException, InterruptedException {
        return get("/" + containerId, Map.of("fields", "status_code,status"));
    }

    public JsonNode publish(String containerId) throws IOException, InterruptedException {
        return post("/" + userId() + "/media_publish", Map.of("creation_id", containerId));
    }

    public JsonNode permalink(String mediaId) throws IOException, InterruptedException {
        return get("/" + mediaId, Map.of("fields", "permalink"));
    }

    private JsonNode get(String path, Map<String, String> params) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path + "?" + form(withToken(params))))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        return send(request);
    }

    private JsonNode post(String path, Map<String, String> params) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(withToken(params))))
                .build();
        return send(request);
    }

    private JsonNode send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode body = JSON.readTree(response.body().isBlank() ? "{}" : response.body());
        if (response.statusCode() / 100 != 2 || body.has("error")) {
            String message = body.path("error").path("message").asText("HTTP " + response.statusCode());
            throw new IOException("Instagram Graph API error: " + message);
        }
        return body;
    }

    private Map<String, String> withToken(Map<String, String> params) {
        Map<String, String> all = new LinkedHashMap<>(params);
        all.put("access_token", config.igAccessToken());
        return all;
    }

    private static String form(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }
}
