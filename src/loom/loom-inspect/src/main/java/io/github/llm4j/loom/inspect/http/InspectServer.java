package io.github.llm4j.loom.inspect.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.llm4j.loom.inspect.state.RunRegistry;
import io.github.llm4j.loom.inspect.state.RunState;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The embedded page and its read-only JSON, on the JDK's own HTTP server (no framework). It only reads; nothing here changes a run.
 *
 * <p>It listens on a loopback address only, and refuses a request whose {@code Host} header is not a loopback name, which stops a
 * web page from another site reaching it through DNS rebinding. Other addresses wait for a login (a later phase).
 */
public final class InspectServer {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String UI = "/io/github/llm4j/loom/inspect/ui/";
    private static final Map<String, String[]> ASSETS = Map.of(
            "/", new String[] {"index.html", "text/html; charset=utf-8"},
            "/app.css", new String[] {"app.css", "text/css; charset=utf-8"},
            "/app.js", new String[] {"app.js", "text/javascript; charset=utf-8"},
            "/logo.png", new String[] {"loom-logo.png", "image/png"},
            "/favicon.ico", new String[] {"loom-logo.png", "image/png"},
            "/fonts/HankenGrotesk.woff2", new String[] {"fonts/HankenGrotesk.woff2", "font/woff2"},
            "/fonts/JetBrainsMono.woff2", new String[] {"fonts/JetBrainsMono.woff2", "font/woff2"});
    private static final String CSP = "default-src 'self'; img-src 'self'; font-src 'self'; style-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

    private final RunRegistry registry;
    private final HttpServer server;
    private final ExecutorService pool;
    private final Set<String> allowedHosts;

    /**
     * @param bind a loopback address (127.0.0.1, ::1 or localhost); anything else is refused
     * @param port the port, or 0 for a free one
     */
    public InspectServer(RunRegistry registry, String bind, int port) {
        this.registry = registry;
        InetAddress address;
        try {
            address = InetAddress.getByName(bind);
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot use '" + bind + "' as an address: " + e.getMessage(), e);
        }
        if (!address.isLoopbackAddress()) {
            throw new IllegalArgumentException("loom-inspect will not listen on " + bind + " yet: the page has no login. Use 127.0.0.1 (the default) or ::1.");
        }
        this.allowedHosts = Set.of("localhost", "127.0.0.1", "[::1]", "::1");
        try {
            this.server = HttpServer.create(new InetSocketAddress(address, port), 0);
        } catch (IOException e) {
            throw new IllegalStateException("cannot listen on " + bind + ":" + port + ": " + e.getMessage(), e);
        }
        this.pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "loom-inspect-http");
            t.setDaemon(true);
            return t;
        });
        this.server.setExecutor(pool);
        this.server.createContext("/", this::handle);
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
        pool.shutdownNow();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    // ------------------------------------------------------------------------------ requests

    private void handle(HttpExchange x) throws IOException {
        try {
            if (!hostAllowed(x.getRequestHeaders().getFirst("Host"))) {
                send(x, 403, "application/json", json(Map.of("error", "unknown host")));
                return;
            }
            if (!"GET".equals(x.getRequestMethod()) && !"HEAD".equals(x.getRequestMethod())) {
                x.getResponseHeaders().add("Allow", "GET, HEAD");
                send(x, 405, "application/json", json(Map.of("error", "this page is read-only")));
                return;
            }
            String path = x.getRequestURI().getPath();
            String[] asset = ASSETS.get(path);
            if (asset != null) {
                byte[] body = resource(asset[0]);
                if (body == null) send(x, 404, "application/json", json(Map.of("error", "missing asset")));
                else send(x, 200, asset[1], body);
            } else if (path.equals("/api/health")) {
                send(x, 200, "application/json", json(Map.of("status", "ok", "runs", registry.summaries().size())));
            } else if (path.equals("/api/runs")) {
                send(x, 200, "application/json", json(Map.of("runs", registry.summaries())));
            } else if (path.startsWith("/api/runs/")) {
                String trace = path.substring("/api/runs/".length());
                Optional<RunState> run = trace.matches("[0-9a-f]{32}") ? registry.findByTrace(trace) : Optional.empty();
                if (run.isPresent()) send(x, 200, "application/json", json(run.get().view()));
                else send(x, 404, "application/json", json(Map.of("error", "no such run")));
            } else {
                send(x, 404, "application/json", json(Map.of("error", "not found")));
            }
        } catch (RuntimeException e) {
            send(x, 500, "application/json", json(Map.of("error", "internal error")));
        } finally {
            x.close();
        }
    }

    private boolean hostAllowed(String host) {
        if (host == null) return false;
        String h = host.trim().toLowerCase(java.util.Locale.ROOT);
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            h = end < 0 ? h : h.substring(0, end + 1);
        } else {
            int colon = h.lastIndexOf(':');
            if (colon >= 0) h = h.substring(0, colon);
        }
        return allowedHosts.contains(h);
    }

    private static byte[] json(Object value) {
        try {
            return JSON.writeValueAsBytes(value);
        } catch (IOException e) {
            return "{\"error\":\"internal error\"}".getBytes(StandardCharsets.UTF_8);
        }
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = InspectServer.class.getResourceAsStream(UI + name)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    private static void send(HttpExchange x, int status, String type, byte[] body) throws IOException {
        x.getResponseHeaders().add("Content-Type", type);
        x.getResponseHeaders().add("Cache-Control", "no-store");
        x.getResponseHeaders().add("X-Content-Type-Options", "nosniff");
        x.getResponseHeaders().add("X-Frame-Options", "DENY");
        x.getResponseHeaders().add("Referrer-Policy", "no-referrer");
        x.getResponseHeaders().add("Content-Security-Policy", CSP);
        boolean head = "HEAD".equals(x.getRequestMethod());
        x.sendResponseHeaders(status, head ? -1 : body.length);
        if (!head) {
            try (OutputStream out = x.getResponseBody()) {
                out.write(body);
            }
        }
    }
}
