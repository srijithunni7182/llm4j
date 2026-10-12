package io.github.llm4j.loom.inspect.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.github.llm4j.loom.inspect.control.AuditSink;
import io.github.llm4j.loom.inspect.control.Controls;
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
        AdminSessions sessions;
        Controls controls;
        io.github.llm4j.loom.inspect.control.Secrets secrets;
        io.github.llm4j.loom.inspect.control.ServerConfig settings;
        AuditSink audit = AuditSink.NONE;
        int threads = 8;
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

        /** Sign-in for the administrator: {@code POST /api/login}, {@code /api/logout} and {@code GET /api/session}. */
        public Config sessions(AdminSessions sessions) {
            this.sessions = sessions;
            return this;
        }

        /** Let a signed-in administrator pause, resume, suspend and stop runs: {@code POST /api/runs/{trace}/{action}}. Needs {@link #sessions}. */
        public Config controls(Controls controls, AuditSink audit) {
            this.controls = controls;
            this.audit = audit == null ? AuditSink.NONE : audit;
            return this;
        }

        /** Let a signed-in administrator list secrets and rotate one: {@code GET /api/secrets}, {@code PUT /api/secrets/{name}}. Needs {@link #sessions}. */
        public Config secrets(io.github.llm4j.loom.inspect.control.Secrets secrets) {
            this.secrets = secrets;
            return this;
        }

        /** Let a signed-in administrator read and change the server's settings: {@code GET /api/config}, {@code PUT /api/config/{section}}. Needs {@link #sessions}. */
        public Config settings(io.github.llm4j.loom.inspect.control.ServerConfig settings) {
            this.settings = settings;
            return this;
        }

        /** Where sign-ins and controls are recorded. */
        public Config audit(AuditSink audit) {
            this.audit = audit == null ? AuditSink.NONE : audit;
            return this;
        }

        /** Worker threads. A server that holds requests open (Fabric's command long-poll) needs many. */
        public Config threads(int threads) {
            this.threads = threads;
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
        this.pool = Executors.newFixedThreadPool(config.threads, r -> {
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
            String p = x.getRequestURI().getPath();
            if (config.sessions != null && handleAdmin(x, p)) return;
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
                else if (run.isPresent()) send(x, 200, "application/json", json(withControls(run.get().view(), run.get().traceId(), x)));
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

    // ------------------------------------------------------------------------------ the administrator

    /** The run's view, with what the page may do to it now: nothing at all unless controls are on here. */
    private Map<String, Object> withControls(Map<String, Object> view, String trace, HttpExchange x) {
        if (config.controls == null) return view;
        Map<String, Object> out = new java.util.LinkedHashMap<>(view);
        Map<String, Object> c = new java.util.LinkedHashMap<>();
        c.put("enabled", config.controls.enabled());
        if (!config.controls.enabled()) c.put("reason", config.controls.disabledReason());
        boolean in = config.sessions != null && config.sessions.valid(x);
        c.put("signedIn", in);
        c.put("allowed", in ? config.controls.allowed(trace).stream().map(Controls.Action::word).sorted().toList() : java.util.List.of());
        out.put("controls", c);
        return out;
    }

    /** Sign-in, sign-out, the session's state and the control requests. Returns true when it answered. */
    private boolean handleAdmin(HttpExchange x, String path) throws IOException {
        AdminSessions admin = config.sessions;
        if (path.equals("/api/session") && "GET".equals(x.getRequestMethod())) {
            AdminSessions.Session s = admin.session(x);
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("authRequired", config.gate != null);
            m.put("passwordSet", admin.passwordSet());
            m.put("signedIn", s != null);
            if (s != null) m.put("csrf", s.csrf());
            m.put("secretsEnabled", config.secrets != null && admin.passwordSet());
            m.put("configEnabled", config.settings != null && admin.passwordSet());
            if (config.controls != null) {
                m.put("controlsEnabled", config.controls.enabled());
                if (!config.controls.enabled()) m.put("controlsReason", config.controls.disabledReason());
            }
            send(x, 200, "application/json", json(m));
            return true;
        }
        if (path.equals("/api/login")) {
            login(x, admin);
            return true;
        }
        if (path.equals("/api/logout")) {
            if (!"POST".equals(x.getRequestMethod())) {
                x.getResponseHeaders().add("Allow", "POST");
                send(x, 405, "application/json", json(Map.of("error", "POST only")));
                return true;
            }
            admin.logout(x);
            x.getResponseHeaders().add("Set-Cookie", admin.clearCookie());
            send(x, 200, "application/json", json(Map.of("ok", true)));
            return true;
        }
        if (config.settings != null && path.startsWith("/api/config")) {
            settings(x, admin, path);
            return true;
        }
        if (config.secrets != null && path.startsWith("/api/secrets")) {
            secrets(x, admin, path);
            return true;
        }
        if (config.controls != null && path.startsWith("/api/runs/") && "POST".equals(x.getRequestMethod())) {
            String rest = path.substring("/api/runs/".length());
            int slash = rest.indexOf('/');
            if (slash > 0) {
                control(x, admin, rest.substring(0, slash), rest.substring(slash + 1));
                return true;
            }
        }
        return false;
    }

    private void login(HttpExchange x, AdminSessions admin) throws IOException {
        if (!"POST".equals(x.getRequestMethod())) {
            x.getResponseHeaders().add("Allow", "POST");
            send(x, 405, "application/json", json(Map.of("error", "POST only")));
            return;
        }
        if (!sameSite(x) || !isJson(x)) {
            send(x, 403, "application/json", json(Map.of("error", "cross-site or malformed login")));
            return;
        }
        String password = null;
        try (InputStream in = x.getRequestBody()) {
            com.fasterxml.jackson.databind.JsonNode n = JSON.readTree(in.readNBytes(4096));
            if (n != null && n.hasNonNull("password")) password = n.get("password").asText();
        } catch (IOException e) {
            // treated as a wrong password
        }
        String address = x.getRemoteAddress().getAddress().getHostAddress();
        AdminSessions.Attempt a = admin.login(address, password);
        if (a.noPasswordSet()) {
            send(x, 503, "application/json", json(Map.of("error", "no admin password is set")));
        } else if (a.retryAfterSeconds() > 0) {
            x.getResponseHeaders().add("Retry-After", Long.toString(a.retryAfterSeconds()));
            audit("login", address, null, null, "throttled");
            send(x, 429, "application/json", json(Map.of("error", "too many attempts, wait")));
        } else if (a.session() == null) {
            audit("login", address, null, null, "wrong password"); // never the attempted value
            send(x, 401, "application/json", json(Map.of("error", "wrong password")));
        } else {
            audit("login", address, null, null, "signed in");
            x.getResponseHeaders().add("Set-Cookie", admin.setCookie(a.session()));
            send(x, 200, "application/json", json(Map.of("ok", true, "csrf", a.session().csrf())));
        }
    }

    private void control(HttpExchange x, AdminSessions admin, String trace, String word) throws IOException {
        String address = x.getRemoteAddress().getAddress().getHostAddress();
        AdminSessions.Session s = admin.session(x);
        if (s == null) {
            send(x, 401, "application/json", json(Map.of("error", "sign in first")));
            return;
        }
        if (!s.csrf().equals(x.getRequestHeaders().getFirst(AdminSessions.CSRF_HEADER)) || !sameSite(x)) {
            audit(word, address, trace, null, "refused: bad CSRF token or origin");
            send(x, 403, "application/json", json(Map.of("error", "cross-site request refused")));
            return;
        }
        Controls.Action action = Controls.Action.of(word);
        if (action == null || !trace.matches("[0-9a-f]{32}") || registry.findByTrace(trace).isEmpty()) {
            send(x, 404, "application/json", json(Map.of("error", "no such run or action")));
            return;
        }
        Controls.Outcome o = config.controls.request(trace, action, "admin@" + address);
        audit(word, address, trace, registry.findByTrace(trace).map(r -> r.client()).orElse(null), o.accepted() ? "accepted" : "refused: " + o.message());
        send(x, o.status(), "application/json", json(Map.of("ok", o.accepted(), "message", o.message())));
    }

    private static final int MAX_SECRET_BODY = 16 * 1024;
    private static final int MAX_CONFIG_BODY = 256 * 1024;

    private void settings(HttpExchange x, AdminSessions admin, String path) throws IOException {
        String address = x.getRemoteAddress().getAddress().getHostAddress();
        AdminSessions.Session s = admin.session(x);
        if (s == null) {
            send(x, 401, "application/json", json(Map.of("error", "sign in first")));
            return;
        }
        if (path.equals("/api/config") && "GET".equals(x.getRequestMethod())) {
            send(x, 200, "application/json", json(Map.of("sections", config.settings.view())));
            return;
        }
        String section = path.startsWith("/api/config/") ? path.substring("/api/config/".length()) : "";
        if (!"PUT".equals(x.getRequestMethod()) || !section.matches("[a-z]{1,20}") || !config.settings.view().containsKey(section)) {
            send(x, 404, "application/json", json(Map.of("error", "not found")));
            return;
        }
        if (!s.csrf().equals(x.getRequestHeaders().getFirst(AdminSessions.CSRF_HEADER)) || !sameSite(x) || !isJson(x)) {
            auditConfig(address, section, "refused: bad CSRF token, origin or content type", 0);
            send(x, 403, "application/json", json(Map.of("error", "cross-site request refused")));
            return;
        }
        byte[] raw;
        try (java.io.InputStream in = x.getRequestBody()) {
            raw = in.readNBytes(MAX_CONFIG_BODY + 1);
        }
        if (raw.length > MAX_CONFIG_BODY) {
            send(x, 413, "application/json", json(Map.of("error", "too large")));
            return;
        }
        String text;
        try {
            text = JSON.readTree(raw).path("text").asText(null);
        } catch (IOException e) {
            send(x, 400, "application/json", json(Map.of("error", "not valid JSON")));
            return;
        }
        if (text == null) {
            send(x, 400, "application/json", json(Map.of("error", "give the text")));
            return;
        }
        io.github.llm4j.loom.inspect.control.ServerConfig.Result r = config.settings.save(section, text, "admin@" + address);
        auditConfig(address, section, r.saved() ? "saved" : "refused: " + r.message(), text.length());
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("ok", r.saved());
        body.put("message", r.message());
        body.put("problems", r.problems());
        send(x, r.status(), "application/json", json(body));
    }

    private void auditConfig(String address, String section, String outcome, int chars) {
        Map<String, Object> e = new java.util.LinkedHashMap<>();
        e.put("who", "admin");
        e.put("address", address);
        e.put("action", "change-config");
        e.put("section", section);
        e.put("characters", chars);
        e.put("outcome", outcome);
        try {
            config.audit.record(e);
        } catch (RuntimeException ex) {
            System.err.println("loom-inspect: cannot write the audit log: " + ex.getMessage());
        }
    }

    private void secrets(HttpExchange x, AdminSessions admin, String path) throws IOException {
        String address = x.getRemoteAddress().getAddress().getHostAddress();
        AdminSessions.Session s = admin.session(x);
        if (s == null) {
            send(x, 401, "application/json", json(Map.of("error", "sign in first")));
            return;
        }
        if (path.equals("/api/secrets") && "GET".equals(x.getRequestMethod())) {
            send(x, 200, "application/json", json(Map.of("secrets", config.secrets.list())));
            return;
        }
        String name = path.startsWith("/api/secrets/") ? path.substring("/api/secrets/".length()) : "";
        if (!"PUT".equals(x.getRequestMethod()) || !name.matches("[A-Za-z0-9_.-]{1,128}")) {
            send(x, 404, "application/json", json(Map.of("error", "not found")));
            return;
        }
        if (!s.csrf().equals(x.getRequestHeaders().getFirst(AdminSessions.CSRF_HEADER)) || !sameSite(x) || !isJson(x)) {
            auditSecret(address, name, "refused: bad CSRF token, origin or content type");
            send(x, 403, "application/json", json(Map.of("error", "cross-site request refused")));
            return;
        }
        byte[] raw;
        try (java.io.InputStream in = x.getRequestBody()) {
            raw = in.readNBytes(MAX_SECRET_BODY + 1);
        }
        if (raw.length > MAX_SECRET_BODY) {
            send(x, 413, "application/json", json(Map.of("error", "too large")));
            return;
        }
        String value;
        String password;
        try {
            com.fasterxml.jackson.databind.JsonNode body = JSON.readTree(raw);
            value = body.path("value").asText(null);
            password = body.path("password").asText(null);
        } catch (IOException e) {
            send(x, 400, "application/json", json(Map.of("error", "not valid JSON")));
            return;
        }
        if (value == null || value.isBlank() || value.length() > 8192) {
            send(x, 400, "application/json", json(Map.of("error", "give the new value")));
            return;
        }
        if (!admin.confirm(address, password)) { // the password again: a stolen session alone cannot replace a key
            auditSecret(address, name, "refused: password not confirmed");
            send(x, 403, "application/json", json(Map.of("error", "the password was not accepted")));
            return;
        }
        char[] chars = value.toCharArray();
        Controls.Outcome o;
        try {
            o = config.secrets.rotate(name, chars, "admin@" + address);
        } finally {
            java.util.Arrays.fill(chars, '\0');
        }
        auditSecret(address, name, o.accepted() ? "rotated" : "refused: " + o.message());
        send(x, o.status(), "application/json", json(Map.of("ok", o.accepted(), "message", o.message())));
    }

    private void auditSecret(String address, String name, String outcome) {
        Map<String, Object> e = new java.util.LinkedHashMap<>();
        e.put("who", "admin");
        e.put("address", address);
        e.put("action", "rotate-secret");
        e.put("secret", name);
        e.put("outcome", outcome);
        try {
            config.audit.record(e);
        } catch (RuntimeException ex) {
            System.err.println("loom-inspect: cannot write the audit log: " + ex.getMessage());
        }
    }

    private void audit(String action, String address, String trace, String client, String outcome) {
        Map<String, Object> e = new java.util.LinkedHashMap<>();
        e.put("who", "admin");
        e.put("address", address);
        e.put("action", action);
        if (trace != null) e.put("run", trace);
        if (client != null) e.put("client", client);
        e.put("outcome", outcome);
        try {
            config.audit.record(e);
        } catch (RuntimeException ex) {
            // an audit that cannot be written must be seen, but must not turn a refusal into success
            System.err.println("loom-inspect: cannot write the audit log: " + ex.getMessage());
        }
    }

    private static boolean isJson(HttpExchange x) {
        String type = x.getRequestHeaders().getFirst("Content-Type");
        return type != null && type.toLowerCase(java.util.Locale.ROOT).startsWith("application/json");
    }

    /** A write must come from this page: when the browser says where it came from, that must be this host. */
    private static boolean sameSite(HttpExchange x) {
        String origin = x.getRequestHeaders().getFirst("Origin");
        String host = x.getRequestHeaders().getFirst("Host");
        return origin == null || host == null || origin.endsWith("//" + host);
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
