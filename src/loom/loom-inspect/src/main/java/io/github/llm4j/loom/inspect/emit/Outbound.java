package io.github.llm4j.loom.inspect.emit;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.inspect.map.GraphView;
import io.github.llm4j.loom.inspect.map.TraceMapper;
import io.github.llm4j.loom.inspect.otel.OtlpJson;
import io.github.llm4j.loom.inspect.otel.Resource;
import io.github.llm4j.loom.inspect.tls.Tls;
import io.github.llm4j.loom.inspect.wire.Hello;
import io.github.llm4j.loom.inspect.wire.Snapshot;
import io.github.llm4j.loom.inspect.wire.Wire;
import io.github.llm4j.loom.observe.FabricOptions;
import io.github.llm4j.loom.observe.OtlpOptions;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * This process's side of reporting, to Fabric and/or an OTLP endpoint: the durable outbox, the thread that sends it, and the timer that flushes it (every 200 ms)
 * and forces it to disk (every second), which is the stated bound on what a crash or power cut can lose. Writing is on the event sink's
 * thread, never the workflow's, and a failure to write (a full disk) is counted and reported at most once a minute; it never reaches the run.
 */
public final class Outbound implements AutoCloseable {

    private static final long SEGMENT = 8L * 1024 * 1024;
    private static final long CAP_BYTES = 256L * 1024 * 1024;
    private static final Duration CAP_AGE = Duration.ofDays(7);
    public static final String DEFAULT_OUTBOX = "loom-inspect-outbox";
    private static volatile ServerLink.Timing timing = ServerLink.Timing.normal();

    /** Sets the send intervals for links opened from now on. For tests, so an outage and its recovery take milliseconds, not minutes. */
    public static void useTiming(ServerLink.Timing t) {
        timing = t == null ? ServerLink.Timing.normal() : t;
    }

    private final Outbox outbox;
    private final ServerLink link;
    private final OtlpExport otlp;
    private final ScheduledExecutorService timer;
    private final Duration flushTimeout;
    private final PrintStream out;
    private final Map<String, Supplier<List<Map<String, Object>>>> ladders = new ConcurrentHashMap<>();
    private volatile long writeFailures;
    private volatile long lastReport;

    private Outbound(Outbox outbox, ServerLink link, OtlpExport otlp, Duration flushTimeout, PrintStream out) {
        this.outbox = outbox;
        this.link = link;
        this.otlp = otlp;
        this.flushTimeout = flushTimeout;
        this.out = out;
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "loom-inspect-outbox-flush");
            t.setDaemon(true);
            return t;
        });
    }

    /** Where the events go. Either may be null; at least one is not. */
    public record Destinations(FabricOptions fabric, String fabricKey, OtlpOptions otlp, java.util.function.UnaryOperator<String> secrets) { }

    /** Opens the outbox for this process name and instance, picks up the logs earlier instances left, and starts sending. */
    public static Outbound open(String name, Destinations d, LoomScript script, Clock clock, PrintStream out) {
        Transport transport = null;
        if (d.fabric() != null) {
            FabricOptions f = d.fabric();
            javax.net.ssl.SSLContext tls = "https".equalsIgnoreCase(f.url().getScheme()) ? Tls.clientContext(f.ca(), f.caOnly(), f.pin()) : null;
            transport = new HttpTransport(f.url(), tls, name, d.fabricKey(), clock);
        }
        OtlpExport.Sender sender = d.otlp() == null ? null
                : OtlpExport.http(d.otlp().endpoint(), OtlpExport.resolveHeaders(d.otlp().headers(), d.secrets()));
        java.nio.file.Path dir = d.fabric() != null ? d.fabric().outbox() : d.otlp().outbox();
        Duration flush = d.fabric() != null ? d.fabric().flushTimeout() : FabricOptions.DEFAULT_FLUSH;
        return open(name, dir, transport, sender, flush, script, clock, out, timing);
    }

    /** As above with the transports and timing given (tests). Either transport may be null. */
    public static Outbound open(String name, Path outboxDir, Transport transport, OtlpExport.Sender otlpSender, Duration flushTimeout, LoomScript script,
                                Clock clock, PrintStream out, ServerLink.Timing timing) {
        String instance = "i_" + UUID.randomUUID().toString().substring(0, 8);
        Path root = (outboxDir != null ? outboxDir : Path.of(DEFAULT_OUTBOX)).resolve(name);
        Outbox own = new Outbox(root.resolve(instance), SEGMENT, CAP_BYTES, CAP_AGE, clock);
        List<ServerLink.Source> orphans = new ArrayList<>();
        if (transport != null) {
            try (var dirs = Files.list(root)) {
                dirs.filter(Files::isDirectory).filter(d -> !d.getFileName().toString().equals(instance)).sorted(java.util.Comparator.comparingLong(Outbox::lastWrite)).forEach(d -> {
                    try {
                        Outbox o = new Outbox(d, SEGMENT, CAP_BYTES, CAP_AGE, clock); // fails when a live process holds it
                        orphans.add(new ServerLink.Source(o, new Cursor(d, "fabric"), true));
                    } catch (IllegalStateException e) {
                        // in use by another running process: not ours to drain
                    }
                });
            } catch (IOException e) {
                // no earlier instances
            }
        }
        Outbound[] self = new Outbound[1];
        Cursor fabricCursor = new Cursor(own.dir(), "fabric");
        Cursor otlpCursor = new Cursor(own.dir(), "otlp");
        java.util.function.LongSupplier minCursor = () -> Math.min(transport == null ? Long.MAX_VALUE : fabricCursor.get(), otlpSender == null ? Long.MAX_VALUE : otlpCursor.get());
        ServerLink link = null;
        if (transport != null) {
            Hello hello = new Hello(name, instance, host(), ProcessHandle.current().pid(), version("ai-agent4j-loom"), version("loom-inspect"), Wire.VERSION, workflows(script));
            link = new ServerLink(name, transport, new ServerLink.Source(own, fabricCursor, false), orphans, hello, () -> self[0].currentLadders(), timing, clock, out);
            link.trimLimit(minCursor);
            link.keepOrphans(otlpSender != null);
        }
        OtlpExport export = otlpSender == null ? null
                : new OtlpExport(own, otlpCursor, otlpSender, timing, out, acked -> own.trim(minCursor.getAsLong()));
        Outbound fl = new Outbound(own, link, export, flushTimeout, out);
        self[0] = fl;
        fl.timer.scheduleWithFixedDelay(() -> fl.flush(false), 200, 200, TimeUnit.MILLISECONDS);
        fl.timer.scheduleWithFixedDelay(() -> fl.flush(true), 1000, 1000, TimeUnit.MILLISECONDS);
        if (link != null) link.start();
        if (export != null) export.start();
        return fl;
    }

    private void flush(boolean sync) {
        try {
            if (sync) outbox.sync();
            else outbox.flush();
        } catch (RuntimeException e) {
            failed(e);
        }
    }

    private Map<String, List<Map<String, Object>>> currentLadders() {
        Map<String, List<Map<String, Object>>> now = new java.util.LinkedHashMap<>();
        ladders.forEach((trace, s) -> {
            try {
                now.put(trace, s.get());
            } catch (RuntimeException e) {
                // a store that cannot be read is simply absent from this heartbeat
            }
        });
        return now;
    }

    /** Writes what the mapper produced, in the order it came, to the outbox. Never throws. */
    public void offer(TraceMapper.Output output, Resource resource) {
        try {
            if (!output.logs().isEmpty()) outbox.append(("L" + OtlpJson.encodeLogs(resource, "0.1", output.logs())).getBytes(StandardCharsets.UTF_8));
            if (!output.spans().isEmpty()) outbox.append(("S" + OtlpJson.encodeTraces(resource, "0.1", output.spans())).getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            failed(e);
        }
    }

    /** A run's graph and ladders, sent now and again after every reconnect. */
    public void snapshot(Snapshot s, Supplier<List<Map<String, Object>>> ladderSource) {
        if (ladderSource != null) ladders.put(s.traceId(), ladderSource);
        if (link != null) link.snapshot(s);
    }

    private void failed(RuntimeException e) {
        writeFailures++;
        long now = System.currentTimeMillis();
        if (lastReport == 0 || now - lastReport > 60_000) {
            lastReport = now;
            out.println("loom-inspect: cannot write the local outbox (" + e.getMessage() + "); " + writeFailures + " record(s) dropped so far. The workflow is not affected.");
        }
    }

    public ServerLink link() {
        return link;
    }

    public Outbox outbox() {
        return outbox;
    }

    /** At the end of the process: a short try at delivering what is left, then stop. What is left stays on disk for the next process of this name. */
    @Override
    public void close() {
        timer.shutdownNow();
        try {
            outbox.sync();
        } catch (RuntimeException e) {
            failed(e);
        }
        if (link != null) link.finish(flushTimeout);
        if (otlp != null) otlp.finish(flushTimeout);
        if (link != null) link.close();
        if (otlp != null) otlp.close();
        outbox.close();
    }

    private static String host() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            return "unknown";
        }
    }

    private static String version(String artifact) {
        Package p = Outbound.class.getPackage();
        String v = p == null ? null : p.getImplementationVersion();
        return v == null ? "dev" : v;
    }

    private static List<Hello.Workflow> workflows(LoomScript script) {
        List<Hello.Workflow> out = new ArrayList<>();
        if (script == null) return out;
        Object g = GraphView.of(script).get("workflows");
        if (g instanceof List<?> list) {
            for (Object w : list) {
                if (w instanceof Map<?, ?> m) out.add(new Hello.Workflow(String.valueOf(m.get("name")), sha(Wire.write(m))));
            }
        }
        return out;
    }

    private static String sha(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))).substring(0, 12);
        } catch (java.security.GeneralSecurityException e) {
            return "";
        }
    }
}
