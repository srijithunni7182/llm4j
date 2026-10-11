package io.github.llm4j.loom.inspect.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.github.llm4j.loom.inspect.state.RunRegistry;
import io.github.llm4j.loom.inspect.state.RunState;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;

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

    /** One extra path with its own handler. It checks its own method and credentials. */
    public interface Route {
        void handle(HttpExchange x) throws IOException;
    }

    /** Decides whether a request may read the JSON API. When it says no it has already answered (a 401, say). */
    public interface Gate {
        boolean pass(HttpExchange x) throws IOException;
    }

    /**
     * How a server that is not the embedded page is set up: any address (when it has a gate and TLS, or a trusted proxy in front), extra
     * routes (a receiver's endpoints, a login), extra read-only JSON, and an optional gate in front of the API.
     */
    public static final class Config {
        String bind = "127.0.0.1";
        int port;
        SSLContext tls;
        boolean loopbackOnly = true;
        Gate gate;
        final Map<String, Route> routes = new LinkedHashMap<>();
        final Map<String, Supplier<Object>> api = new LinkedHashMap<>();

        public Config bind(String bind, int port) {
            this.bind = bind;
            this.port = port;
            return this;
        }

        /** Serve HTTPS with this context. */
        public Config tls(SSLContext tls) {
            this.tls = tls;
            return this;
        }

        /** Allow a non-loopback address and any Host header. The caller has put a gate and TLS (or a trusted proxy) in front. */
        public Config anyAddress() {
            this.loopbackOnly = false;
            return this;
        }

        public Config gate(Gate gate) {
            this.gate = gate;
            return this;
        }

        public Config route(String path, Route route) {
            routes.put(path, route);
            return this;
        }

        /** A read-only JSON document at {@code /api/<name>}, behind the gate. */
        public Config api(String name, Supplier<Object> body) {
            api.put("/api/" + name, body);
            return this;
        }
    }

    private final RunRegistry registry;
    private final HttpServer server;
    private final ExecutorService pool;
    private final Set<String> allowedHosts;
    private final Config config;

    /**
     * The embedded page.
     *
     * @param bind a loopback address (127.0.0.1, ::1 or localhost); anything else is refused
     * @param port the port, or 0 for a free one
     */
    public InspectServer(RunRegistry registry, String bind, int port) {
        this(registry, new Config().bind(bind, port));
    }

    public static Config config() {
        return new Config();
    }

    public InspectServer(RunRegistry registry, Config config) {
        this.registry = registry;
        this.config = config;
        InetAddress address;
        try {
            address = InetAddress.getByName(config.bind);
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot use '" + config.bind + "' as an address: " + e.getMessage(), e);
        }
        if (config.loopbackOnly && !address.isLoopbackAddress()) {
            throw new IllegalArgumentException("loom-inspect will not listen on " + config.bind + ": the embedded page has no login. Use 127.0.0.1 (the default) or ::1.");
        }
        this.allowedHosts = config.loopbackOnly ? Set.of("localhost", "127.0.0.1", "[::1]", "::1") : null;
        try {
            if (config.tls != null) {
                HttpsServer https = HttpsServer.create(new InetSocketAddress(address, config.port), 0);
                https.setHttpsConfigurator(new HttpsConfigurator(config.tls));
                this.server = https;
            } else {
                this.server = HttpServer.create(new InetSocketAddress(address, config.port), 0);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot listen on " + config.bind + ":" + config.port + ": " + e.getMessage(), e);
        }
        this.pool = Executors.newFixedThreadPool(8, r -> {
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
            if (allowedHosts != null && !hostAllowed(x.getRequestHeaders().getFirst("Host"))) {
                send(x, 403, "application/json", json(Map.of("error", "unknown host")));
                return;
            }
            Route route = config.routes.get(x.getRequestURI().getPath());
            if (route != null) {
                route.handle(x);
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
            } else if (path.startsWith("/api/") && !path.equals("/api/health") && config.gate != null && !config.gate.pass(x)) {
                return; // the gate has answered
            } else if (config.api.containsKey(path)) {
                send(x, 200, "application/json", json(config.api.get(path).get()));
            } else if (path.equals("/api/health")) {
                send(x, 200, "application/json", json(Map.of("status", "ok", "runs", registry.summaries().size())));
            } else if (path.equals("/api/runs")) {
                send(x, 200, "application/json", json(Map.of("runs", registry.summaries())));
            } else if (path.startsWith("/api/runs/")) {
                String trace = path.substring("/api/runs/".length());
                boolean wantsGraph = trace.endsWith("/graph");
                if (wantsGraph) trace = trace.substring(0, trace.length() - "/graph".length());
                Optional<RunState> run = trace.matches("[0-9a-f]{32}") ? registry.findByTrace(trace) : Optional.empty();
                if (run.isPresent() && wantsGraph) send(x, 200, "application/json", json(run.get().graph() == null ? Map.of() : run.get().graph()));
                else if (run.isPresent()) send(x, 200, "application/json", json(run.get().view()));
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

    /** A value as JSON bytes. */
    public static byte[] json(Object value) {
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

    /** Answers with the standard security headers (no caching, no sniffing, no framing, strict content policy). */
    public static void send(HttpExchange x, int status, String type, byte[] body) throws IOException {
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
