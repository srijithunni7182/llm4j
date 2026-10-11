package io.github.llm4j.loom.fabric;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import io.github.llm4j.loom.inspect.http.InspectServer;
import io.github.llm4j.loom.inspect.receive.Receiver;
import io.github.llm4j.loom.inspect.state.RunRegistry;
import io.github.llm4j.loom.inspect.wire.Heartbeat;
import io.github.llm4j.loom.inspect.wire.Hello;
import io.github.llm4j.loom.inspect.wire.Signer;
import io.github.llm4j.loom.inspect.wire.Snapshot;
import io.github.llm4j.loom.inspect.wire.Wire;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;

/**
 * The server many processes report to. Outbound-only from the processes' point of view: they connect here, it never connects to them.
 *
 * <p>What it does with each request: finds the client by the name header, finds that client's key in the vault (a missing, revoked or
 * wrong key all answer the same 401), checks the signature and its timestamp, applies the rate limit, lets the {@link Receiver} apply the
 * message, appends it to the history and forces it to disk, and only then answers 200. That answer is the acknowledgement the process
 * moves its cursor on, so an acknowledged record survives a restart.
 *
 * <p>Without TLS it listens on loopback only; for another address it needs TLS, or {@code trustProxy} for a reverse proxy that
 * terminates TLS in front of it.
 */
public final class FabricServer implements AutoCloseable {

    /** How the server is set up. */
    public record Settings(Path data, String bind, int port, SSLContext tls, boolean trustProxy, int retentionDays, long retentionBytes) {
        public Settings {
            bind = bind == null || bind.isBlank() ? "127.0.0.1" : bind;
            retentionDays = retentionDays <= 0 ? 30 : retentionDays;
            retentionBytes = retentionBytes <= 0 ? 2L * 1024 * 1024 * 1024 : retentionBytes;
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final int MAX_BODY = 16 * 1024 * 1024;

    /** The wall clock, except while the history is replayed: then it reads the time each message was stored. */
    private static final class ReplayClock extends Clock {
        private final AtomicReference<Instant> pinned = new AtomicReference<>();

        @Override
        public ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            Instant p = pinned.get();
            return p != null ? p : Instant.now();
        }
    }

    private final Settings settings;
    private final Vault vault;
    private final PrintStream out;
    private final ReplayClock clock = new ReplayClock();
    private final RunRegistry registry = new RunRegistry();
    private final Receiver receiver = new Receiver(registry, clock);
    private final History history;
    private final Admin admin;
    private final RateLimiter limiter;
    private final InspectServer http;
    private final ScheduledExecutorService janitor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "fabric-retention");
        t.setDaemon(true);
        return t;
    });

    public FabricServer(Settings settings, Vault vault, PrintStream out) {
        this(settings, vault, out, 100, 200);
    }

    /** As above with the per-client request rate set (requests per second, and the burst). */
    FabricServer(Settings settings, Vault vault, PrintStream out, double ratePerSecond, double burst) {
        this.settings = settings;
        this.vault = vault;
        this.out = out;
        this.history = new History(settings.data(), clock);
        this.admin = new Admin(vault, clock, settings.tls() != null || settings.trustProxy());
        this.limiter = new RateLimiter(clock, ratePerSecond, burst);
        boolean loopback;
        try {
            loopback = InetAddress.getByName(settings.bind()).isLoopbackAddress();
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot use '" + settings.bind() + "' as an address: " + e.getMessage(), e);
        }
        if (!loopback && settings.tls() == null && !settings.trustProxy()) {
            throw new IllegalArgumentException("Fabric will not listen on " + settings.bind() + " without TLS. Give it a certificate (--tls-keystore, or --tls-cert and --tls-key), or run it behind a reverse proxy that terminates TLS and pass --trust-proxy.");
        }
        InspectServer.Config c = InspectServer.config().bind(settings.bind(), settings.port());
        if (settings.tls() != null) c.tls(settings.tls());
        if (!loopback || settings.trustProxy()) c.anyAddress();
        c.gate(x -> {
            if (admin.valid(x)) return true;
            InspectServer.send(x, 401, "application/json", InspectServer.json(Map.of("error", "login required")));
            return false;
        });
        c.api("session", () -> Map.of("ok", true));
        c.api("processes", () -> Map.of("processes", receiver.processes()));
        c.route("/api/login", this::login);
        c.route("/api/logout", x -> {
            admin.logout(x);
            x.getResponseHeaders().add("Set-Cookie", admin.clearCookie());
            InspectServer.send(x, 200, "application/json", InspectServer.json(Map.of("ok", true)));
        });
        c.route("/v1/logs", x -> ingest(x, "logs"));
        c.route("/v1/traces", x -> ingest(x, "traces"));
        c.route("/v1/metrics", x -> ingest(x, "metrics"));
        c.route("/loom-inspect/v1/hello", x -> ingest(x, "hello"));
        c.route("/loom-inspect/v1/snapshot", x -> ingest(x, "snapshot"));
        c.route("/loom-inspect/v1/heartbeat", x -> ingest(x, "heartbeat"));
        c.route("/loom-inspect/v1/bye", x -> ingest(x, "bye"));
        this.http = new InspectServer(registry, c);
    }

    /** Rebuilds live state from the history, starts serving, and starts the retention timer. */
    public void start() {
        restore();
        http.start();
        janitor.scheduleWithFixedDelay(this::retain, 1, 60, TimeUnit.MINUTES);
    }

    public int port() {
        return http.port();
    }

    public Receiver receiver() {
        return receiver;
    }

    public History history() {
        return history;
    }

    public RunRegistry registry() {
        return registry;
    }

    // ------------------------------------------------------------------------------ ingest

    private void ingest(HttpExchange x, String kind) throws IOException {
        if (!"POST".equals(x.getRequestMethod())) {
            x.getResponseHeaders().add("Allow", "POST");
            reply(x, 405, Map.of("error", "POST only"));
            return;
        }
        String client = x.getRequestHeaders().getFirst(Signer.CLIENT_HEADER);
        byte[] body = readBody(x);
        if (body == null) {
            reply(x, 413, Map.of("error", "body too large"));
            return;
        }
        String key = client != null && NAME.matcher(client).matches() ? vault.get("client." + client) : null;
        if (key == null || !Signer.verify(key, x.getRequestHeaders().getFirst(Signer.HEADER), body, clock.instant())) {
            reply(x, 401, Map.of("error", "unauthorised")); // the same answer for an unknown, revoked or wrong key
            return;
        }
        if (!limiter.allow(client)) {
            x.getResponseHeaders().add("Retry-After", "1");
            reply(x, 429, Map.of("error", "slow down"));
            return;
        }
        String text = new String(body, StandardCharsets.UTF_8);
        try {
            Object answer = apply(client, kind, text);
            if (!kind.equals("heartbeat") && !kind.equals("metrics")) history.append(client, kind, text);
            reply(x, 200, answer);
        } catch (Receiver.Rejected r) {
            reply(x, r.status(), Map.of("error", r.getMessage()));
        } catch (RuntimeException e) {
            out.println("fabric: could not store a " + kind + " from " + client + ": " + e.getMessage());
            reply(x, 500, Map.of("error", "could not store the message"));
        }
    }

    /** Applies one message. Used by ingest and by the replay of the history. */
    private Object apply(String client, String kind, String text) {
        return switch (kind) {
            case "logs" -> {
                receiver.logs(client, text);
                yield Map.of("ok", true);
            }
            case "traces" -> {
                receiver.traces(client, text);
                yield Map.of("ok", true);
            }
            case "hello" -> receiver.hello(client, read(text, Hello.class));
            case "snapshot" -> {
                receiver.snapshot(client, read(text, Snapshot.class));
                yield Map.of("ok", true);
            }
            case "heartbeat" -> Map.of("serverTime", receiver.heartbeat(client, read(text, Heartbeat.class)));
            case "bye" -> {
                receiver.bye(client);
                yield Map.of("ok", true);
            }
            default -> Map.of("ok", true); // metrics: accepted and not kept yet
        };
    }

    private static <T> T read(String text, Class<T> type) {
        try {
            return Wire.read(text, type);
        } catch (IllegalArgumentException e) {
            throw new Receiver.Rejected(e.getMessage());
        }
    }

    private static byte[] readBody(HttpExchange x) throws IOException {
        try (InputStream in = x.getRequestBody()) {
            byte[] bytes = in.readNBytes(MAX_BODY + 1);
            return bytes.length > MAX_BODY ? null : bytes;
        }
    }

    private static void reply(HttpExchange x, int status, Object body) throws IOException {
        InspectServer.send(x, status, "application/json", InspectServer.json(body));
    }

    // ------------------------------------------------------------------------------ login

    private void login(HttpExchange x) throws IOException {
        if (!"POST".equals(x.getRequestMethod())) {
            x.getResponseHeaders().add("Allow", "POST");
            reply(x, 405, Map.of("error", "POST only"));
            return;
        }
        String type = x.getRequestHeaders().getFirst("Content-Type");
        String origin = x.getRequestHeaders().getFirst("Origin");
        String host = x.getRequestHeaders().getFirst("Host");
        if (type == null || !type.toLowerCase().startsWith("application/json") || (origin != null && host != null && !origin.endsWith("//" + host))) {
            reply(x, 403, Map.of("error", "cross-site or malformed login"));
            return;
        }
        String password = null;
        try (InputStream in = x.getRequestBody()) {
            JsonNode n = JSON.readTree(in.readNBytes(4096));
            if (n != null && n.hasNonNull("password")) password = n.get("password").asText();
        } catch (IOException e) {
            // treated as a wrong password
        }
        String address = x.getRemoteAddress().getAddress().getHostAddress();
        Admin.Attempt a = admin.login(address, password);
        if (a.noPasswordSet()) {
            reply(x, 503, Map.of("error", "no admin password is set: run 'fabric init' or 'fabric admin set-password'"));
        } else if (a.retryAfterSeconds() > 0) {
            x.getResponseHeaders().add("Retry-After", Long.toString(a.retryAfterSeconds()));
            reply(x, 429, Map.of("error", "too many attempts, wait"));
        } else if (a.token() == null) {
            out.println("fabric: failed login from " + address); // never the attempted value
            reply(x, 401, Map.of("error", "wrong password"));
        } else {
            x.getResponseHeaders().add("Set-Cookie", admin.setCookie(a.token()));
            reply(x, 200, Map.of("ok", true));
        }
    }

    // ------------------------------------------------------------------------------ history

    /** Reads the history back through the same code that took it in, with the clock set to when each message was stored. */
    private void restore() {
        int[] n = {0};
        history.replay(e -> {
            clock.pinned.set(e.at());
            try {
                apply(e.client(), e.kind(), e.body());
                n[0]++;
            } catch (RuntimeException ex) {
                // a stored message this version cannot read: skipped, the rest still counts
            }
        });
        clock.pinned.set(null);
        if (n[0] > 0) out.println("fabric: restored " + n[0] + " stored messages");
    }

    private void retain() {
        try {
            int gone = history.enforce(settings.retentionDays(), settings.retentionBytes());
            if (gone > 0) out.println("fabric: retention removed " + gone + " old history file(s)");
        } catch (RuntimeException e) {
            out.println("fabric: retention failed: " + e.getMessage());
        }
    }

    /** Applies the retention now (at start-up and on a timer). */
    public void applyRetention() {
        retain();
    }

    @Override
    public void close() {
        janitor.shutdownNow();
        http.stop();
    }
}
