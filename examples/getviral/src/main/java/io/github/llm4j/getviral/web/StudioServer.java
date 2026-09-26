package io.github.llm4j.getviral.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.llm4j.getviral.config.GetViralConfig;
import io.github.llm4j.getviral.engine.GetViralEngine;
import io.github.llm4j.getviral.rag.KnowledgeBase;
import io.github.llm4j.getviral.studio.StudioRun;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The GetViral studio: a single-page app plus a tiny JSON + Server-Sent-Events API, served by the
 * JDK's built-in HTTP server — no web framework needed.
 */
public class StudioServer {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> TYPES = Map.of(
            "html", "text/html; charset=utf-8", "css", "text/css; charset=utf-8",
            "js", "text/javascript; charset=utf-8", "svg", "image/svg+xml", "png", "image/png");

    private final GetViralEngine engine;
    private final Map<String, StudioRun> runs = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newCachedThreadPool();

    public StudioServer(GetViralEngine engine) {
        this.engine = engine;
    }

    public HttpServer start() throws IOException {
        GetViralConfig config = engine.config();
        HttpServer server = HttpServer.create(new InetSocketAddress(config.port()), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/api/info", this::info);
        server.createContext("/api/runs", this::runs);
        server.createContext("/api/memory", this::memory);
        server.createContext("/api/feedback", this::feedback);
        server.createContext("/", this::staticFile);
        server.start();
        // Warm the embedding model + playbook index so the first run starts instantly.
        workers.submit(() -> KnowledgeBase.shared(config));
        System.out.println("""

                  ✦ GetViral studio is live → http://localhost:%d
                    mode: %s   ·   public APIs: %s   ·   Instagram: %s
                """.formatted(config.port(), config.mode(), config.offlineApis() ? "offline samples" : "live",
                config.instagramConfigured() ? "connected" : "dry-run"));
        return server;
    }

    private void info(HttpExchange ex) throws IOException {
        GetViralConfig c = engine.config();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("mode", c.mode().name());
        info.put("model", c.model());
        info.put("instagram", c.instagramConfigured() ? "connected" : "dry-run");
        info.put("publicApis", c.offlineApis() ? "offline samples" : "live (sample fallback)");
        info.put("maxRevisions", c.maxRevisions());
        json(ex, 200, info);
    }

    private void runs(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String[] parts = path.split("/"); // "", api, runs, {id}, {action}
        try {
            if (parts.length == 3 && ex.getRequestMethod().equals("POST")) {
                startRun(ex);
            } else if (parts.length == 5 && parts[4].equals("events")) {
                stream(ex, runs.get(parts[3]));
            } else if (parts.length == 5 && parts[4].equals("answer") && ex.getRequestMethod().equals("POST")) {
                StudioRun run = runs.get(parts[3]);
                Map<String, Object> body = body(ex);
                boolean ok = run != null && run.answer(String.valueOf(body.get("id")), String.valueOf(body.get("answer")));
                json(ex, ok ? 200 : 409, Map.of("ok", ok));
            } else if (parts.length == 5 && parts[4].equals("export.md")) {
                StudioRun run = runs.get(parts[3]);
                String md = run == null ? "Run not found" : PackMarkdown.render(run);
                send(ex, run == null ? 404 : 200, "text/markdown; charset=utf-8", md.getBytes(StandardCharsets.UTF_8));
            } else {
                json(ex, 404, Map.of("error", "not found"));
            }
        } catch (RuntimeException e) {
            json(ex, 500, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    @SuppressWarnings("unchecked")
    private void startRun(HttpExchange ex) throws IOException {
        Map<String, Object> body = body(ex);
        String idea = String.valueOf(body.getOrDefault("idea", "")).strip();
        if (idea.isEmpty() || idea.length() > 500) {
            json(ex, 400, Map.of("error", "Give GetViral an idea (up to 500 characters)."));
            return;
        }
        List<String> voices = body.get("voiceSamples") instanceof List<?> list
                ? ((List<Object>) list).stream().map(String::valueOf).filter(s -> !s.isBlank()).toList()
                : List.of();
        GetViralEngine.Brief brief = new GetViralEngine.Brief(idea, str(body, "handle"), str(body, "niche"),
                str(body, "tone"), str(body, "region"), voices);
        StudioRun run = new StudioRun(brief.toMap(), Duration.ofMinutes(15));
        runs.put(run.id(), run);
        workers.submit(() -> engine.run(run, brief));
        json(ex, 201, Map.of("id", run.id()));
    }

    private void stream(HttpExchange ex, StudioRun run) throws IOException {
        if (run == null) {
            json(ex, 404, Map.of("error", "run not found"));
            return;
        }
        ex.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().add("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0);
        BlockingQueue<Map<String, Object>> queue = new LinkedBlockingQueue<>();
        Runnable unsubscribe = run.subscribe(queue::add);
        try (OutputStream out = ex.getResponseBody()) {
            while (true) {
                Map<String, Object> event = queue.poll(15, TimeUnit.SECONDS);
                if (event == null) {
                    out.write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    continue;
                }
                out.write(("data: " + JSON.writeValueAsString(event) + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                if (isTerminal(event) && queue.isEmpty()) break;
            }
        } catch (IOException | InterruptedException e) {
            // browser went away
        } finally {
            unsubscribe.run();
        }
    }

    private static boolean isTerminal(Map<String, Object> event) {
        if (!"status".equals(event.get("type"))) return false;
        Object status = ((Map<?, ?>) event.get("data")).get("status");
        return "DONE".equals(status) || "BLOCKED".equals(status) || "FAILED".equals(status);
    }

    private void memory(HttpExchange ex) throws IOException {
        String handle = query(ex, "handle");
        json(ex, 200, Map.of("handle", handle, "memories", engine.memories(handle.isBlank() ? "creator" : handle)));
    }

    private void feedback(HttpExchange ex) throws IOException {
        Map<String, Object> body = body(ex);
        engine.feedback(str(body, "handle").replaceFirst("^@", ""), str(body, "platform"),
                Boolean.parseBoolean(String.valueOf(body.get("loved"))), str(body, "detail"));
        json(ex, 200, Map.of("ok", true));
    }

    private void staticFile(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/") || path.isBlank()) path = "/index.html";
        if (path.contains("..")) {
            send(ex, 400, "text/plain", "bad path".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try (InputStream in = StudioServer.class.getResourceAsStream("/getviral/web" + path)) {
            if (in == null) {
                send(ex, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String ext = path.substring(path.lastIndexOf('.') + 1);
            send(ex, 200, TYPES.getOrDefault(ext, "application/octet-stream"), in.readAllBytes());
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────

    private static Map<String, Object> body(HttpExchange ex) throws IOException {
        byte[] bytes = ex.getRequestBody().readAllBytes();
        if (bytes.length == 0) return Map.of();
        return JSON.readValue(bytes, new TypeReference<Map<String, Object>>() { });
    }

    private static String str(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value == null ? "" : value.toString().strip();
    }

    private static String query(HttpExchange ex, String key) {
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return "";
        for (String pair : raw.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv[0].equals(key) && kv.length == 2) return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
        }
        return "";
    }

    private static void json(HttpExchange ex, int status, Object body) throws IOException {
        send(ex, status, "application/json; charset=utf-8", JSON.writeValueAsBytes(body));
    }

    private static void send(HttpExchange ex, int status, String type, byte[] bytes) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
