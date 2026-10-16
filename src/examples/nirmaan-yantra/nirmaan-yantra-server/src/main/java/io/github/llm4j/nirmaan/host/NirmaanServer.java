package io.github.llm4j.nirmaan.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.llm4j.nirmaan.tasks.ProjectArtifacts;
import io.github.llm4j.nirmaan.tasks.Sandbox;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * The web face of the factory: the built React UI, and a small JSON and event-stream API.
 *
 * <p>There is no sign-in, so it listens on this computer only (a non-loopback address is refused), and every request is checked
 * against two things a web page on another site could abuse: the {@code Host} header must be this server's own address (so DNS
 * rebinding cannot reach it by name) and an {@code Origin} header, when present, must be this server's own origin (so another site
 * cannot start a run, which spends money, with a form post). Files from a project are served as {@code text/plain} with
 * {@code nosniff}, so code an agent wrote is never run by the browser.
 */
public final class NirmaanServer implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern PROJECT = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final int MAX_BODY = 64 * 1024;
    private static final int MAX_IDEA = 4000;

    private final FactoryHost host;
    private final Path uiDir;
    private final HttpServer http;
    private final Set<String> allowedHosts;

    public NirmaanServer(FactoryHost host, Path uiDir, String bind, int port) throws IOException {
        InetAddress address = InetAddress.getByName(bind);
        if (!address.isLoopbackAddress()) {
            throw new IllegalArgumentException("'" + bind + "' is not this computer. The factory has no sign-in, so it listens on loopback only (127.0.0.1).");
        }
        this.host = host;
        this.uiDir = uiDir;
        this.http = HttpServer.create(new InetSocketAddress(address, port), 0);
        int bound = http.getAddress().getPort();
        this.allowedHosts = Set.of("127.0.0.1:" + bound, "localhost:" + bound, "[::1]:" + bound);
        http.createContext("/", this::handle);
        http.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "nirmaan-http");
            t.setDaemon(true);
            return t;
        }));
    }

    public void start() { http.start(); }

    public int port() { return http.getAddress().getPort(); }

    public String url() { return "http://127.0.0.1:" + port(); }

    @Override
    public void close() { http.stop(0); }

    // ---- routing ------------------------------------------------------------------------------------------------------

    private void handle(HttpExchange x) throws IOException {
        try {
            if (!allowedHosts.contains(x.getRequestHeaders().getFirst("Host"))) {
                send(x, 403, "text/plain", "wrong host");
                return;
            }
            String origin = x.getRequestHeaders().getFirst("Origin");
            if (origin != null && !origin.equals("http://" + x.getRequestHeaders().getFirst("Host"))) {
                send(x, 403, "text/plain", "wrong origin");
                return;
            }
            String path = x.getRequestURI().getPath();
            String method = x.getRequestMethod();
            if (path.equals("/api/project/start") && method.equals("POST")) {
                start(x);
            } else if (path.startsWith("/api/project/")) {
                project(x, path.substring("/api/project/".length()).split("/", 3), method);
            } else if (method.equals("GET") || method.equals("HEAD")) {
                staticFile(x, path);
            } else {
                send(x, 405, "text/plain", "method not allowed");
            }
        } catch (Exception e) {
            // Never show a stack trace or a path to the browser; the console has the detail.
            System.err.println("request failed: " + e);
            try {
                send(x, 500, "text/plain", "something went wrong");
            } catch (IOException ignored) {
                // the client has gone
            }
        } finally {
            x.close();
        }
    }

    private void start(HttpExchange x) throws IOException {
        String body = readBody(x);
        if (body == null) {
            send(x, 413, "text/plain", "too long");
            return;
        }
        String idea = body.strip();
        String contentType = x.getRequestHeaders().getFirst("Content-Type");
        if (contentType != null && contentType.startsWith("application/json")) {
            JsonNode json = JSON.readTree(body);
            idea = json.path("idea").asText("").strip();
        }
        if (idea.isEmpty() || idea.length() > MAX_IDEA) {
            send(x, 400, "text/plain", "give an idea of 1 to " + MAX_IDEA + " characters");
            return;
        }
        try {
            HostRun run = host.start(idea);
            sendJson(x, 200, Map.of("projectId", run.id(), "status", run.status()));
        } catch (FactoryHost.Busy busy) {
            send(x, 409, "text/plain", busy.getMessage());
        }
    }

    private void project(HttpExchange x, String[] parts, String method) throws IOException {
        String id = parts[0];
        String what = parts.length > 1 ? parts[1] : "";
        if (!PROJECT.matcher(id).matches()) {
            send(x, 404, "text/plain", "not found");
            return;
        }
        HostRun run = host.run(id);
        switch (what) {
            case "stream" -> {
                if (run == null || !method.equals("GET")) send(x, 404, "text/plain", "not found");
                else stream(x, run);
            }
            case "state" -> {
                if (run == null) send(x, 404, "text/plain", "not found");
                else sendJson(x, 200, run.state());
            }
            case "answer" -> {
                if (run == null || !method.equals("POST")) {
                    send(x, 404, "text/plain", "not found");
                    return;
                }
                String body = readBody(x);
                HostRun.Answer result = run.answer(body == null ? "" : body);
                send(x, switch (result) { case ACCEPTED -> 200; case NO_QUESTION -> 409; case NOT_ONE_OF_THE_CHOICES -> 400; },
                        "text/plain", result.name().toLowerCase().replace('_', ' '));
            }
            case "stop" -> {
                if (run == null || !method.equals("POST")) {
                    send(x, 404, "text/plain", "not found");
                    return;
                }
                run.stop();
                send(x, 200, "text/plain", "stopping");
            }
            case "artifacts" -> artifacts(x, id, parts.length > 2 ? parts[2] : "");
            case "download" -> download(x, id);
            default -> send(x, 404, "text/plain", "not found");
        }
    }

    // ---- project files ------------------------------------------------------------------------------------------------

    private boolean exists(String id) {
        return Files.isDirectory(Sandbox.workspace().resolve(id).resolve("code"));
    }

    private void artifacts(HttpExchange x, String id, String file) throws IOException {
        if (!exists(id)) {
            send(x, 404, "text/plain", "not found");
            return;
        }
        if (file.isEmpty()) {
            sendJson(x, 200, ProjectArtifacts.list(id));
            return;
        }
        String decoded = java.net.URLDecoder.decode(file, StandardCharsets.UTF_8);
        var text = ProjectArtifacts.read(id, decoded);
        if (text.isEmpty()) send(x, 404, "text/plain", "not found");
        else send(x, 200, "text/plain; charset=utf-8", text.get());
    }

    private void download(HttpExchange x, String id) throws IOException {
        if (!exists(id)) {
            send(x, 404, "text/plain", "not found");
            return;
        }
        Path zip = ProjectArtifacts.archivePath(id);
        ProjectArtifacts.zip(id, zip);
        x.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + id + ".zip\"");
        byte[] bytes = Files.readAllBytes(zip);
        send(x, 200, "application/zip", bytes);
    }

    // ---- event stream -------------------------------------------------------------------------------------------------

    private void stream(HttpExchange x, HostRun run) throws IOException {
        x.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        x.getResponseHeaders().set("Cache-Control", "no-cache");
        x.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        x.sendResponseHeaders(200, 0);
        OutputStream out = x.getResponseBody();
        CountDownLatch closed = new CountDownLatch(1);
        HostRun.Sink sink = (event, data) -> {
            try {
                StringBuilder frame = new StringBuilder("event: ").append(event).append('\n');
                for (String line : data.split("\n", -1)) frame.append("data: ").append(line).append('\n');
                frame.append('\n');
                synchronized (out) {
                    out.write(frame.toString().getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                return true;
            } catch (IOException gone) {
                closed.countDown();
                return false;
            }
        };
        run.subscribe(sink);
        try {
            // Hold the connection open; a comment line every 15 seconds keeps proxies from closing it and finds a client that went away.
            while (!closed.await(15, TimeUnit.SECONDS)) {
                try {
                    synchronized (out) {
                        out.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                } catch (IOException gone) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- the UI -------------------------------------------------------------------------------------------------------

    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"), Map.entry("js", "text/javascript; charset=utf-8"), Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"), Map.entry("svg", "image/svg+xml"), Map.entry("json", "application/json"),
            Map.entry("woff2", "font/woff2"), Map.entry("ico", "image/x-icon"), Map.entry("map", "application/json"));

    private void staticFile(HttpExchange x, String path) throws IOException {
        if (!Files.isDirectory(uiDir)) {
            send(x, 200, "text/html; charset=utf-8", "<!doctype html><title>Nirmaan Yantra</title><body style=\"font-family:sans-serif;padding:2rem\">"
                    + "<h1>The UI has not been built</h1><p>Run <code>npm install &amp;&amp; npm run build</code> in <code>nirmaan-yantra-ui</code>, then reload.</p>");
            return;
        }
        String relative = path.equals("/") ? "index.html" : path.substring(1);
        Path file = uiDir.resolve(relative).normalize();
        if (!file.startsWith(uiDir)) {
            send(x, 404, "text/plain", "not found");
            return;
        }
        if (!Files.isRegularFile(file)) {
            // A path with no extension is a page of the single-page app; anything else is a missing file.
            if (relative.contains(".")) {
                send(x, 404, "text/plain", "not found");
                return;
            }
            file = uiDir.resolve("index.html");
        }
        String name = file.getFileName().toString();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "";
        x.getResponseHeaders().set("Content-Security-Policy",
                "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src 'self' https://fonts.gstatic.com; connect-src 'self'; frame-ancestors 'none'");
        send(x, 200, TYPES.getOrDefault(ext, "application/octet-stream"), Files.readAllBytes(file));
    }

    // ---- plumbing -----------------------------------------------------------------------------------------------------

    /** The request body as text, or null when it is longer than the limit. */
    private static String readBody(HttpExchange x) throws IOException {
        try (InputStream in = x.getRequestBody()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                if (out.size() > MAX_BODY) return null;
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private static void sendJson(HttpExchange x, int status, Object value) throws IOException {
        send(x, status, "application/json", JSON.writeValueAsString(value));
    }

    private static void send(HttpExchange x, int status, String type, String body) throws IOException {
        send(x, status, type, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange x, int status, String type, byte[] body) throws IOException {
        x.getResponseHeaders().set("Content-Type", type);
        x.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        x.getResponseHeaders().set("Cache-Control", "no-store");
        boolean head = x.getRequestMethod().equals("HEAD");
        x.sendResponseHeaders(status, head ? -1 : body.length == 0 ? -1 : body.length);
        if (!head && body.length > 0) {
            try (OutputStream out = x.getResponseBody()) {
                out.write(body);
            }
        }
    }
}
