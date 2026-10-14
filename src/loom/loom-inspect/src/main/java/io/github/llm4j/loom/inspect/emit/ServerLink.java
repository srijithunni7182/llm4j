package io.github.llm4j.loom.inspect.emit;

import io.github.llm4j.loom.inspect.otel.LogRec;
import io.github.llm4j.loom.inspect.otel.OtlpJson;
import io.github.llm4j.loom.inspect.otel.Resource;
import io.github.llm4j.loom.inspect.otel.SpanRec;
import io.github.llm4j.loom.inspect.wire.Heartbeat;
import io.github.llm4j.loom.inspect.wire.Hello;
import io.github.llm4j.loom.inspect.wire.Snapshot;
import io.github.llm4j.loom.inspect.wire.Wire;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * One process's connection to a Fabric server, on its own thread, entirely off the run's path. It reads the outbox from the cursor and
 * sends it; the cursor moves only when the server acknowledges. A server that is down, slow or rejecting never reaches the workflow: the
 * link backs off (1 s to 60 s, with jitter) and keeps trying, and the outbox keeps growing on disk until the server returns.
 *
 * <p>On every (re)connect it sends {@code hello}, then a snapshot of each live run (so the console is right at once), then the backlog
 * in batches of up to 500 records, oldest first. Live records go through the same ordered log, so nothing is reordered.
 *
 * <p>A wrong key or a refused request is reported once on the console and reporting stops. It never stops the workflow.
 */
public final class ServerLink implements AutoCloseable {

    /** Intervals, so a test can run the link in milliseconds. */
    public record Timing(Duration poll, Duration heartbeat, Duration minBackoff, Duration maxBackoff) {
        public static Timing normal() {
            return new Timing(Duration.ofMillis(200), Duration.ofSeconds(5), Duration.ofSeconds(1), Duration.ofSeconds(60));
        }
    }

    /** A log to drain: this process's own, or one a finished instance left behind. */
    record Source(Outbox outbox, Cursor cursor, boolean orphan) { }

    private static final int BATCH = 500;

    private final String client;
    private final Transport transport;
    private final List<Source> sources = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Hello hello;
    private final Supplier<Map<String, List<Map<String, Object>>>> ladders;
    private final Timing timing;
    private final Clock clock;
    private final PrintStream out;
    private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<String, Boolean> dirty = new ConcurrentHashMap<>();
    private final Thread thread;

    private volatile boolean stopping;
    private volatile boolean dead;
    private volatile boolean connected;
    private volatile long lost;
    private volatile long sent;
    private volatile long closeDeadline;
    private volatile boolean drainedAtEnd;
    private volatile java.util.function.LongSupplier trimLimit;
    private volatile boolean keepOrphans;
    private long lastReport;

    public ServerLink(String client, Transport transport, Source own, List<Source> orphans, Hello hello,
                      Supplier<Map<String, List<Map<String, Object>>>> ladders, Timing timing, Clock clock, PrintStream out) {
        this.client = client;
        this.transport = transport;
        this.sources.addAll(orphans);
        this.sources.add(own);
        this.hello = hello;
        this.ladders = ladders;
        this.timing = timing;
        this.clock = clock;
        this.out = out;
        this.thread = new Thread(this::run, "loom-inspect-fabric");
        this.thread.setDaemon(true);
    }

    public void start() {
        thread.start();
    }

    /** With a second destination reading the same log, nothing may be trimmed past what that one has also acknowledged. */
    public void trimLimit(java.util.function.LongSupplier limit) {
        this.trimLimit = limit;
    }

    /** Leave an emptied earlier instance's directory in place (another destination has not drained it). */
    public void keepOrphans(boolean keep) {
        this.keepOrphans = keep;
    }

    /** True while the server is reachable and accepting. */
    public boolean connected() {
        return connected;
    }

    /** True when reporting has stopped for good (a wrong key, a refused request). */
    public boolean dead() {
        return dead;
    }

    /** Records dropped because the outbox passed its cap before they were sent. */
    public long lost() {
        return lost;
    }

    /** Records not yet acknowledged, across the logs being drained. */
    public long backlog() {
        long n = 0;
        for (Source s : sources) n += Math.max(0, s.outbox().nextOffset() - 1 - s.cursor().get());
        return n;
    }

    /** Remembers what a run is (its graph, its ladders) to send now and after every reconnect. */
    public void snapshot(Snapshot s) {
        snapshots.put(s.traceId(), s);
        dirty.put(s.traceId(), Boolean.TRUE);
    }

    // ------------------------------------------------------------------------------ the loop

    private void run() {
        long backoff = timing.minBackoff().toMillis();
        while (!dead && !(stopping && System.currentTimeMillis() > closeDeadline)) {
            try {
                connect();
                backoff = timing.minBackoff().toMillis();
                connected = true;
                serve();
                if (stopping) {
                    drainedAtEnd = true;
                    break;
                }
            } catch (Retry r) {
                connected = false;
                if (stopping) break;
                report("the Fabric server is not accepting reports yet (" + r.getMessage() + "); events are kept on disk and will be sent when it is");
                pause(Math.max(r.afterMillis, jitter(backoff)));
                backoff = Math.min(backoff * 2, timing.maxBackoff().toMillis());
            } catch (IOException e) {
                connected = false;
                if (stopping) break;
                report("cannot reach the Fabric server (" + e.getClass().getSimpleName() + ": " + e.getMessage() + "); events are kept on disk and will be sent when it is back");
                pause(jitter(backoff));
                backoff = Math.min(backoff * 2, timing.maxBackoff().toMillis());
            } catch (Fatal f) {
                connected = false;
                dead = true;
                out.println("loom-inspect: " + f.getMessage() + " Reporting to Fabric has stopped; the workflow is not affected.");
            }
        }
        connected = false;
    }

    private void connect() throws IOException {
        Transport.Reply r = transport.post("/loom-inspect/v1/hello", Wire.write(hello));
        check(r);
        try {
            Wire.read(r.body(), java.util.Map.class);
        } catch (IllegalArgumentException e) {
            throw new Retry("an unreadable answer to hello", 0);
        }
        if (r.body().contains("\"supported\":false")) {
            out.println("loom-inspect: the Fabric server does not support this process's wire version " + Wire.VERSION + "; it will show as unsupported there.");
        }
        snapshots.keySet().forEach(t -> dirty.put(t, Boolean.TRUE));
    }

    private void serve() throws IOException {
        long nextBeat = 0;
        while (!dead) {
            sendDirtySnapshots();
            int n = drainOnce();
            long now = System.currentTimeMillis();
            if (now >= nextBeat) {
                heartbeat();
                nextBeat = now + timing.heartbeat().toMillis();
            }
            if (stopping) {
                if (n == 0 || now > closeDeadline) return;
                continue;
            }
            if (n == 0) pause(timing.poll().toMillis());
        }
    }

    private void sendDirtySnapshots() throws IOException {
        for (String trace : new ArrayList<>(dirty.keySet())) {
            Snapshot s = snapshots.get(trace);
            if (s != null) check(transport.post("/loom-inspect/v1/snapshot", Wire.write(s)));
            dirty.remove(trace);
        }
    }

    private void heartbeat() throws IOException {
        Heartbeat hb = new Heartbeat(client, hello.instance(), clock.instant().toString(), sent, backlog(), lost, ladders.get());
        check(transport.post("/loom-inspect/v1/heartbeat", Wire.write(hb)));
    }

    /** Sends one batch from the first log that has anything; returns how many records it moved. */
    private int drainOnce() throws IOException {
        for (Source s : sources) {
            long first = s.outbox().firstOffset();
            if (s.cursor().get() < first - 1) {
                lost += first - 1 - s.cursor().get(); // the cap dropped records that were never sent; say so rather than pretend
                s.cursor().advance(first - 1);
            }
            List<Outbox.Entry> batch = s.outbox().read(s.cursor().get(), BATCH);
            if (batch.isEmpty()) {
                if (s.orphan() && !keepOrphans) finished(s); // we hold its lock, so nobody else is writing to it
                continue;
            }
            // One request per kind of record in the batch, logs first and then spans, each in the order it was written. A batch is mostly alternating
            // logs and spans, and sending every change of kind as its own request would cost hundreds of requests for a few hundred records. The
            // server tolerates the order (a span that ends before its start log is seen is counted once), and the cursor moves only when the whole
            // batch has been acknowledged, so a failure part-way resends it and the server drops the repeats.
            for (char type : new char[] {'L', 'S'}) {
                List<Outbox.Entry> same = new ArrayList<>();
                for (Outbox.Entry e : batch) if ((char) e.payload()[0] == type) same.add(e);
                if (!same.isEmpty()) send(type, same);
            }
            List<Outbox.Entry> unknown = new ArrayList<>();
            for (Outbox.Entry e : batch) if ((char) e.payload()[0] != 'L' && (char) e.payload()[0] != 'S') unknown.add(e);
            if (!unknown.isEmpty()) send('?', unknown);
            s.cursor().advance(batch.get(batch.size() - 1).offset());
            sent += batch.size();
            long limit = s.cursor().get();
            if (!s.orphan() && trimLimit != null) limit = Math.min(limit, trimLimit.getAsLong());
            s.outbox().trim(limit);
            return batch.size();
        }
        return 0;
    }

    private void finished(Source s) {
        sources.remove(s);
        Path dir = s.outbox().dir();
        s.outbox().close();
        deleteTree(dir);
    }

    private static void deleteTree(Path dir) {
        try (var walk = java.nio.file.Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // leave it; it is empty of anything that matters
        }
    }

    private void send(char type, List<Outbox.Entry> entries) throws IOException {
        // one request per resource in the batch: a run resumed in this process is a new instance with its own sequence
        if (type == 'L') {
            Map<Resource, List<LogRec>> by = new java.util.LinkedHashMap<>();
            for (Outbox.Entry e : entries) for (OtlpJson.Logs g : OtlpJson.decodeLogs(text(e))) by.computeIfAbsent(g.resource(), k -> new ArrayList<>()).addAll(g.records());
            for (var e : by.entrySet()) deliver("/v1/logs", OtlpJson.encodeLogs(e.getKey(), "0.1", e.getValue()), entries.size());
        } else if (type == 'S') {
            Map<Resource, List<SpanRec>> by = new java.util.LinkedHashMap<>();
            for (Outbox.Entry e : entries) for (OtlpJson.Traces g : OtlpJson.decodeTraces(text(e))) by.computeIfAbsent(g.resource(), k -> new ArrayList<>()).addAll(g.spans());
            for (var e : by.entrySet()) deliver("/v1/traces", OtlpJson.encodeTraces(e.getKey(), "0.1", e.getValue()), entries.size());
        } else {
            lost += entries.size(); // a record this version does not know: skipped, and counted
        }
    }

    private static String text(Outbox.Entry e) {
        return new String(e.payload(), 1, e.payload().length - 1, java.nio.charset.StandardCharsets.UTF_8);
    }

    private void deliver(String path, String body, int count) throws IOException {
        Transport.Reply r = transport.post(path, body);
        if (r.status() == 400 || r.status() == 413) {
            lost += count; // the server cannot read this batch and never will: skip it so the rest can flow
            report("the Fabric server refused a batch of " + count + " records as unreadable; skipped");
            return;
        }
        check(r);
    }

    /** Turns an answer into what the loop does next. */
    private void check(Transport.Reply r) {
        if (r.ok()) return;
        if (r.status() == 401 || r.status() == 403) {
            throw new Fatal("The Fabric server rejected this process's key or name (HTTP " + r.status() + "). Check --observe-name and the client key.");
        }
        if (r.status() == 429) throw new Retry("HTTP 429, the server asked for a slower pace", r.retryAfterSeconds() * 1000);
        if (r.status() >= 500 || r.status() == 408) throw new Retry("HTTP " + r.status(), 0);
        throw new Fatal("The Fabric server refused a request (HTTP " + r.status() + ").");
    }

    private long jitter(long millis) {
        return millis / 2 + ThreadLocalRandom.current().nextLong(Math.max(1, millis / 2 + 1));
    }

    private void pause(long millis) {
        try {
            Thread.sleep(Math.max(1, millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Tells the console once a minute at most: an outage must not fill the log. */
    private void report(String message) {
        long now = System.currentTimeMillis();
        if (lastReport == 0 || now - lastReport > 60_000) {
            lastReport = now;
            out.println("loom-inspect: " + message);
        }
    }

    private static final class Retry extends RuntimeException {
        final long afterMillis;

        Retry(String message, long afterMillis) {
            super(message);
            this.afterMillis = afterMillis;
        }
    }

    private static final class Fatal extends RuntimeException {
        Fatal(String message) {
            super(message);
        }
    }

    /**
     * At the end of the process: tries for up to {@code flushTimeout} to deliver what is left, then stops. It never waits for a server
     * that is down; what is left stays in the outbox for the next process with this name.
     */
    public void finish(Duration flushTimeout) {
        closeDeadline = System.currentTimeMillis() + flushTimeout.toMillis();
        stopping = true;
        try {
            thread.join(flushTimeout.toMillis() + 500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (drainedAtEnd && !dead) {
            try {
                transport.post("/loom-inspect/v1/bye", Wire.write(Map.of("client", client, "instance", hello.instance())));
            } catch (IOException ignored) {
                // best effort: the server marks a silent process offline by itself
            }
        }
    }

    @Override
    public void close() {
        stopping = true;
        closeDeadline = 0;
        thread.interrupt();
        try {
            thread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
