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
    private final io.github.llm4j.loom.inspect.receive.CommandQueue commands = new io.github.llm4j.loom.inspect.receive.CommandQueue(clock);
    private final FabricConfig config;
    private final io.github.llm4j.loom.inspect.http.AdminSessions admin;
    private final io.github.llm4j.loom.inspect.control.AuditSink audit;
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
        this.admin = new io.github.llm4j.loom.inspect.http.AdminSessions(() -> vault.get(Vault.ADMIN), clock, settings.tls() != null || settings.trustProxy());
        io.github.llm4j.loom.inspect.control.AuditSink file = new io.github.llm4j.loom.inspect.control.FileAuditSink(settings.data().resolve("audit.log"), clock);
        this.audit = entry -> {
            file.record(entry);
            if ("login".equals(entry.get("action")) && String.valueOf(entry.get("outcome")).startsWith("wrong")) out.println("fabric: failed login from " + entry.get("address")); // never the attempted value
        };
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
        this.config = new FabricConfig(settings.data(), registry, out);
        c.settings(config);
        c.sessions(admin).audit(audit).controls(new FabricControls(receiver, commands, () -> vault.get(Vault.ADMIN)), audit).threads(64); // many processes each hold a command long-poll open
        c.gate(x -> {
            if (admin.valid(x)) return true;
            InspectServer.send(x, 401, "application/json", InspectServer.json(Map.of("error", "login required")));
            return false;
        });
        c.api("processes", () -> Map.of("processes", receiver.processes()));
        c.route("/v1/logs", x -> ingest(x, "logs"));
        c.route("/v1/traces", x -> ingest(x, "traces"));
        c.route("/v1/metrics", x -> ingest(x, "metrics"));
        c.route("/loom-inspect/v1/hello", x -> ingest(x, "hello"));
        c.route("/loom-inspect/v1/snapshot", x -> ingest(x, "snapshot"));
        c.route("/loom-inspect/v1/heartbeat", x -> ingest(x, "heartbeat"));
        c.route("/loom-inspect/v1/bye", x -> ingest(x, "bye"));
        c.route("/loom-inspect/v1/commands", this::commandPoll);
        c.route("/loom-inspect/v1/command-result", this::commandResult);
        this.http = new InspectServer(registry, c);
    }

    /** Rebuilds live state from the history, starts serving, and starts the retention timer. */
    public void start() {
        config.load();
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
        byte[] body = readBody(x);
        String client = authenticate(x, body);
        if (client == null) return;
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

    /**
     * Checks size, signature and rate for a request from a process. Answers the failure itself and returns null, or returns the client's name.
     * An unknown, revoked or wrong key all get the same answer.
     */
    private String authenticate(HttpExchange x, byte[] body) throws IOException {
        String client = x.getRequestHeaders().getFirst(Signer.CLIENT_HEADER);
        if (body == null) {
            reply(x, 413, Map.of("error", "body too large"));
            return null;
        }
        String key = client != null && NAME.matcher(client).matches() ? vault.get("client." + client) : null;
        if (key == null || !Signer.verify(key, x.getRequestHeaders().getFirst(Signer.HEADER), body, clock.instant())) {
            reply(x, 401, Map.of("error", "unauthorised"));
            return null;
        }
        if (!limiter.allow(client)) {
            x.getResponseHeaders().add("Retry-After", "1");
            reply(x, 429, Map.of("error", "slow down"));
            return null;
        }
        return client;
    }

    /** A process asks for its commands and waits up to the time it names (at most 25 s). The answer is signed with that process's key. */
    private void commandPoll(HttpExchange x) throws IOException {
        if (!"POST".equals(x.getRequestMethod())) {
            x.getResponseHeaders().add("Allow", "POST");
            reply(x, 405, Map.of("error", "POST only"));
            return;
        }
        byte[] body = readBody(x);
        String client = authenticate(x, body);
        if (client == null) return;
        long wait = 25;
        try {
            wait = Math.max(0, Math.min(25, new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).path("wait").asLong(25)));
        } catch (IOException | RuntimeException e) {
            // an unreadable body means the default wait
        }
        java.util.List<io.github.llm4j.loom.inspect.wire.Command> due;
        try {
            due = commands.next(client, java.time.Duration.ofSeconds(wait));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        byte[] answer = InspectServer.json(Map.of("client", client, "commands", due));
        x.getResponseHeaders().add(Signer.HEADER, Signer.sign(vault.get("client." + client), clock.instant(), answer));
        InspectServer.send(x, 200, "application/json", answer);
    }

    /** A process says what it did with a command; recorded in the audit log. */
    private void commandResult(HttpExchange x) throws IOException {
        if (!"POST".equals(x.getRequestMethod())) {
            x.getResponseHeaders().add("Allow", "POST");
            reply(x, 405, Map.of("error", "POST only"));
            return;
        }
        byte[] body = readBody(x);
        String client = authenticate(x, body);
        if (client == null) return;
        try {
            io.github.llm4j.loom.inspect.wire.CommandResult r = read(new String(body, StandardCharsets.UTF_8), io.github.llm4j.loom.inspect.wire.CommandResult.class);
            commands.result(r);
            audit.record(new java.util.LinkedHashMap<>(Map.of("event", "command-result", "client", client, "command", String.valueOf(r.id()), "accepted", r.accepted(), "message", String.valueOf(r.message()))));
            reply(x, 200, Map.of("ok", true));
        } catch (Receiver.Rejected r) {
            reply(x, r.status(), Map.of("error", r.getMessage()));
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
