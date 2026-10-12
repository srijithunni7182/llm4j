package io.github.llm4j.loom.inspect.receive;

import io.github.llm4j.loom.inspect.otel.LogRec;
import io.github.llm4j.loom.inspect.otel.OtlpJson;
import io.github.llm4j.loom.inspect.otel.SpanRec;
import io.github.llm4j.loom.inspect.state.RunRegistry;
import io.github.llm4j.loom.inspect.state.RunState;
import io.github.llm4j.loom.inspect.wire.Heartbeat;
import io.github.llm4j.loom.inspect.wire.Hello;
import io.github.llm4j.loom.inspect.wire.Snapshot;
import io.github.llm4j.loom.inspect.wire.Wire;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Takes what the processes send and keeps the registry and the process table current. It knows nothing of HTTP or of keys: the
 * transport has already checked the signature and says who the caller is ({@code client}), and the receiver refuses anything that
 * claims to be somebody else.
 */
public final class Receiver {

    /** The message was not from who it said it was from, or was not understood. */
    public static final class Rejected extends RuntimeException {
        private final int status;

        public Rejected(String message) {
            this(message, 400);
        }

        public Rejected(String message, int status) {
            super(message);
            this.status = status;
        }

        /** The HTTP status that fits: 403 for a client reporting as another, 400 for what could not be read. */
        public int status() {
            return status;
        }
    }

    /** What a server answers to {@code hello}. */
    public record HelloReply(int wire, boolean supported, String serverTime) { }

    /** Online means a message within this long. Heartbeats come every 5 seconds, so three missed ones is offline. */
    public static final Duration ONLINE_WITHIN = Duration.ofSeconds(15);

    private static final class Process {
        String name;
        String instance;
        String host;
        long pid;
        String loomVersion;
        String inspectVersion;
        int wire;
        List<Hello.Workflow> workflows = List.of();
        Instant firstSeen;
        Instant lastSeen;
        boolean bye;
        long events;
        long backlog;
        long lost;
        double rate;
        long rateEvents;
        Instant rateAt;
    }

    private final RunRegistry registry;
    private final Clock clock;
    private final Map<String, Process> processes = new ConcurrentHashMap<>();

    public Receiver(RunRegistry registry, Clock clock) {
        this.registry = registry;
        this.clock = clock;
    }

    public RunRegistry registry() {
        return registry;
    }

    public HelloReply hello(String client, Hello h) {
        same(client, h.client());
        Process p = processes.computeIfAbsent(client, k -> new Process());
        synchronized (p) {
            Instant now = clock.instant();
            if (p.firstSeen == null) p.firstSeen = now;
            p.name = client;
            p.instance = h.instance();
            p.host = h.host();
            p.pid = h.pid();
            p.loomVersion = h.loomVersion();
            p.inspectVersion = h.inspectVersion();
            p.wire = h.wire();
            p.workflows = h.workflows();
            p.lastSeen = now;
            p.bye = false;
            p.rateAt = null;
        }
        return new HelloReply(Wire.VERSION, h.wire() <= Wire.VERSION, clock.instant().toString());
    }

    public void snapshot(String client, Snapshot s) {
        same(client, s.client());
        if (s.traceId() == null || !s.traceId().matches("[0-9a-f]{32}")) throw new Rejected("snapshot without a trace id");
        registry.setGraph(client, s.traceId(), s.graph());
        registry.setLadders(client, s.traceId(), s::ladders);
        registry.setRules(client, s.traceId(), s.inspect());
        registry.setModels(client, s.traceId(), s.models());
        touch(client);
    }

    /** @return the server's clock, so the process can warn when the two differ by more than the signature window */
    public String heartbeat(String client, Heartbeat hb) {
        same(client, hb.client());
        Process p = processes.computeIfAbsent(client, k -> new Process());
        Instant now = clock.instant();
        synchronized (p) {
            p.name = client;
            if (p.firstSeen == null) p.firstSeen = now;
            p.lastSeen = now;
            p.bye = false;
            p.backlog = hb.backlog();
            p.lost = hb.lost();
            if (p.rateAt != null && now.isAfter(p.rateAt)) {
                double secs = Duration.between(p.rateAt, now).toMillis() / 1000.0;
                if (secs >= 1 && hb.events() >= p.rateEvents) p.rate = (hb.events() - p.rateEvents) / secs;
            }
            if (p.rateAt == null || Duration.between(p.rateAt, now).toSeconds() >= 1) {
                p.rateEvents = hb.events();
                p.rateAt = now;
            }
            p.events = hb.events();
        }
        hb.ladders().forEach((trace, ladders) -> {
            if (trace != null && trace.matches("[0-9a-f]{32}")) registry.setLadders(client, trace, () -> ladders);
        });
        return now.toString();
    }

    /** A clean exit: the process is not offline by accident. */
    public void bye(String client) {
        Process p = processes.get(client);
        if (p == null) return;
        synchronized (p) {
            p.bye = true;
            p.lastSeen = clock.instant();
        }
    }

    public void logs(String client, String otlpJson) {
        List<OtlpJson.Logs> groups = decode(() -> OtlpJson.decodeLogs(otlpJson));
        for (OtlpJson.Logs g : groups) {
            same(client, g.resource().serviceName());
            registry.applyLogs(g.resource(), g.records());
            count(client, g.records().size());
        }
    }

    public void traces(String client, String otlpJson) {
        List<OtlpJson.Traces> groups = decode(() -> OtlpJson.decodeTraces(otlpJson));
        for (OtlpJson.Traces g : groups) {
            same(client, g.resource().serviceName());
            registry.applySpans(g.resource(), g.spans());
            count(client, g.spans().size());
        }
    }

    private static <T> List<T> decode(java.util.function.Supplier<List<T>> parse) {
        try {
            return parse.get();
        } catch (RuntimeException e) {
            throw new Rejected("not valid OTLP/JSON: " + e.getMessage());
        }
    }

    private void count(String client, int n) {
        Process p = processes.computeIfAbsent(client, k -> new Process());
        synchronized (p) {
            p.name = client;
            if (p.firstSeen == null) p.firstSeen = clock.instant();
            p.lastSeen = clock.instant();
        }
    }

    private void touch(String client) {
        Process p = processes.get(client);
        if (p != null) synchronized (p) {
            p.lastSeen = clock.instant();
        }
    }

    private static void same(String authenticated, String claimed) {
        if (claimed == null || !claimed.equals(authenticated)) {
            throw new Rejected("a client may only report as itself (authenticated as '" + authenticated + "')", 403);
        }
    }

    /** One row per process the receiver has heard from, by name. */
    public List<Map<String, Object>> processes() {
        Instant now = clock.instant();
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Integer> live = new LinkedHashMap<>();
        for (Map<String, Object> r : registry.summaries()) {
            String st = String.valueOf(r.get("status"));
            if (st.equals("running") || st.equals("sleeping") || st.equals("waiting") || st.equals("paused")) live.merge(String.valueOf(r.get("client")), 1, Integer::sum);
        }
        for (Process p : processes.values()) {
            synchronized (p) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", p.name);
                m.put("instance", p.instance);
                m.put("host", p.host);
                m.put("pid", p.pid);
                m.put("loomVersion", p.loomVersion);
                m.put("inspectVersion", p.inspectVersion);
                m.put("wire", p.wire);
                m.put("workflows", p.workflows.stream().map(Hello.Workflow::name).toList());
                m.put("state", state(p, now));
                m.put("lastSeen", p.lastSeen == null ? null : p.lastSeen.toString());
                m.put("firstSeen", p.firstSeen == null ? null : p.firstSeen.toString());
                m.put("liveRuns", live.getOrDefault(p.name, 0));
                m.put("eventsPerSecond", Math.round(p.rate * 10) / 10.0);
                m.put("backlog", p.backlog);
                m.put("lost", p.lost);
                out.add(m);
            }
        }
        out.sort((a, b) -> String.valueOf(a.get("name")).compareTo(String.valueOf(b.get("name"))));
        return out;
    }

    private static String state(Process p, Instant now) {
        if (p.wire > Wire.VERSION) return "unsupported";
        if (p.bye) return "finished";
        boolean online = p.lastSeen != null && Duration.between(p.lastSeen, now).compareTo(ONLINE_WITHIN) <= 0;
        if (!online) return "offline";
        return p.backlog > 0 ? "catching up" : "online";
    }

    /** True when a process of this name has been heard from (used to mark a run's connection as stale). */
    public boolean online(String client) {
        Process p = processes.get(client);
        if (p == null) return false;
        synchronized (p) {
            return !p.bye && p.lastSeen != null && Duration.between(p.lastSeen, clock.instant()).compareTo(ONLINE_WITHIN) <= 0;
        }
    }

    /** A run of a process that has gone quiet, for the page to dim. */
    public Map<String, Object> connection(RunState run) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("client", run.client());
        m.put("online", online(run.client()));
        return m;
    }
}
