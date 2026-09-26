package io.github.llm4j.getviral.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.getviral.studio.StudioEvents;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Base class for tools backed by free, keyless public REST APIs.
 *
 * <p>Every call goes to the real endpoint first. If the host is unreachable (offline laptop,
 * locked-down CI, a rate limit) the tool falls back to a recorded sample response shipped in
 * {@code getviral/fixtures/} and says so in its observation — the agent (and the UI) always know
 * whether they are looking at live data or a sample.
 */
public abstract class PublicApiTool implements Tool {

    public static final String USER_AGENT =
            "GetViral/5.0 (llm4j showcase; https://github.com/srijithunni7182/llm4j)";

    protected static final ObjectMapper JSON = new ObjectMapper();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Responses are cached per JVM so parallel agents asking the same question share one call. */
    private static final Map<String, JsonNode> CACHE = new ConcurrentHashMap<>();

    private final boolean offline;
    private final StudioEvents events;

    protected PublicApiTool(boolean offline, StudioEvents events) {
        this.offline = offline;
        this.events = events != null ? events : StudioEvents.NONE;
    }

    /** A fetched payload plus where it came from. */
    public record Fetched(JsonNode json, boolean live, String host) {
        public String sourceLine() {
            return live
                    ? "[source: live " + host + "]"
                    : "[source: offline sample of " + host + " — live API unreachable, treat as illustrative]";
        }
    }

    /**
     * GETs {@code url}, falling back to {@code fixtures/<fixture>.json}.
     */
    protected Fetched fetch(String url, String fixture) {
        String host = URI.create(url).getHost();
        long start = System.nanoTime();
        JsonNode cached = CACHE.get(url);
        if (cached != null) {
            report(host, url, true, start, "cache");
            return new Fetched(cached, true, host);
        }
        if (!offline) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "application/json")
                        .GET()
                        .build();
                HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 == 2) {
                    JsonNode json = JSON.readTree(response.body());
                    CACHE.put(url, json);
                    report(host, url, true, start, "HTTP " + response.statusCode());
                    return new Fetched(json, true, host);
                }
                report(host, url, false, start, "HTTP " + response.statusCode());
            } catch (IOException e) {
                report(host, url, false, start, e.getClass().getSimpleName());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                report(host, url, false, start, "interrupted");
            }
        } else {
            report(host, url, false, start, "offline mode");
        }
        return new Fetched(loadFixture(fixture), false, host);
    }

    protected void report(String host, String url, boolean live, long startNanos, String status) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tool", getName());
        data.put("host", host);
        data.put("url", url);
        data.put("live", live);
        data.put("status", status);
        data.put("ms", (System.nanoTime() - startNanos) / 1_000_000);
        events.emit("api", data);
    }

    private static JsonNode loadFixture(String fixture) {
        String path = "/getviral/fixtures/" + fixture + ".json";
        try (InputStream in = PublicApiTool.class.getResourceAsStream(path)) {
            if (in == null) {
                return JSON.createObjectNode();
            }
            return JSON.readTree(in);
        } catch (IOException e) {
            return JSON.createObjectNode();
        }
    }

    // ── small helpers shared by the concrete tools ──────────────────────────────────────────

    /** Reads the first present argument; ReActAgent passes raw strings as {@code input}. */
    protected static String arg(Map<String, Object> args, String... names) {
        for (String name : names) {
            Object value = args.get(name);
            if (value != null && !value.toString().isBlank()) {
                return value.toString().trim();
            }
        }
        Object raw = args.get("input");
        return raw != null ? raw.toString().trim() : "";
    }

    protected static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    protected static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText();
    }

    protected static String clip(String value, int max) {
        if (value == null) return "";
        String flat = value.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max - 1) + "…";
    }

    /** Test hook: forget cached responses. */
    public static void clearCache() {
        CACHE.clear();
    }
}
