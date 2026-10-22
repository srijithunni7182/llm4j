package io.github.llm4j.loom.inspect.emit;

import io.github.llm4j.loom.inspect.otel.LogRec;
import io.github.llm4j.loom.inspect.otel.OtlpJson;
import io.github.llm4j.loom.inspect.otel.Resource;
import io.github.llm4j.loom.inspect.otel.SpanRec;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongConsumer;
import java.util.function.UnaryOperator;

/**
 * The same traces and logs, to any OTLP/HTTP endpoint (a collector, Tempo, Honeycomb, Datadog's
 * intake). Only OTLP: no hello, snapshot or heartbeat, because a generic backend has nowhere to put
 * them. It reads the same outbox from its own cursor, so it survives an outage the same way and is
 * independent of Fabric. Header values may be {@code ${secret:NAME}}, resolved when sent.
 */
public final class OtlpExport implements AutoCloseable {

    private static final int BATCH = 500;

    /**
     * Sends one request; a seam for tests. Throws {@link IOException} when the endpoint cannot be
     * reached.
     */
    public interface Sender {
        int post(String path, String body) throws IOException;
    }

    private final Outbox outbox;
    private final Cursor cursor;
    private final Sender sender;
    private final ServerLink.Timing timing;
    private final PrintStream out;
    private final LongConsumer afterAck;
    private final Thread thread;
    private volatile boolean stopping;
    private volatile boolean dead;
    private volatile long closeDeadline;
    private volatile long lost;
    private long lastReport;

    public OtlpExport(
            Outbox outbox,
            Cursor cursor,
            Sender sender,
            ServerLink.Timing timing,
            PrintStream out,
            LongConsumer afterAck) {
        this.outbox = outbox;
        this.cursor = cursor;
        this.sender = sender;
        this.timing = timing;
        this.out = out;
        this.afterAck = afterAck;
        this.thread = new Thread(this::run, "loom-inspect-otlp");
        this.thread.setDaemon(true);
    }

    /** The real sender: signed by nothing, authenticated by the headers the user gave. */
    public static Sender http(URI endpoint, Map<String, String> headers) {
        HttpClient http =
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(Duration.ofSeconds(5))
                        .build();
        String root = endpoint.toString().replaceAll("/+$", "");
        return (path, body) -> {
            HttpRequest.Builder b =
                    HttpRequest.newBuilder(URI.create(root + path))
                            .timeout(Duration.ofSeconds(30))
                            .header("Content-Type", "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            body, StandardCharsets.UTF_8));
            headers.forEach(b::header);
            try {
                HttpResponse<Void> r = http.send(b.build(), HttpResponse.BodyHandlers.discarding());
                return r.statusCode();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            }
        };
    }

    /**
     * Resolves {@code ${secret:NAME}} in header values; a missing secret is a clear error, never an
     * empty header.
     */
    public static Map<String, String> resolveHeaders(
            List<String> headers, UnaryOperator<String> secret) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String h : headers) {
            int eq = h.indexOf('=');
            String value = h.substring(eq + 1);
            var m =
                    java.util.regex.Pattern.compile("\\$\\{secret:([A-Za-z0-9_.-]+)}")
                            .matcher(value);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String v = secret == null ? null : secret.apply(m.group(1));
                if (v == null || v.isBlank())
                    throw new IllegalStateException(
                            "the secret "
                                    + m.group(1)
                                    + " (used in an --observe-otlp-header) is not in the secret store");
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(v));
            }
            m.appendTail(sb);
            out.put(h.substring(0, eq), sb.toString());
        }
        return out;
    }

    public void start() {
        thread.start();
    }

    public boolean dead() {
        return dead;
    }

    public long lost() {
        return lost;
    }

    public long backlog() {
        return Math.max(0, outbox.nextOffset() - 1 - cursor.get());
    }

    private void run() {
        long backoff = timing.minBackoff().toMillis();
        while (!dead) {
            try {
                int n = drainOnce();
                backoff = timing.minBackoff().toMillis();
                if (stopping && (n == 0 || System.currentTimeMillis() > closeDeadline)) return;
                if (n == 0) sleep(timing.poll().toMillis());
            } catch (IOException | RuntimeException e) {
                if (stopping) return;
                report(
                        "cannot reach the OTLP endpoint ("
                                + e.getMessage()
                                + "); events are kept on disk and will be sent when it is back");
                sleep(
                        backoff / 2
                                + ThreadLocalRandom.current()
                                        .nextLong(Math.max(1, backoff / 2 + 1)));
                backoff = Math.min(backoff * 2, timing.maxBackoff().toMillis());
            }
        }
    }

    private int drainOnce() throws IOException {
        long first = outbox.firstOffset();
        if (cursor.get() < first - 1) {
            lost += first - 1 - cursor.get();
            cursor.advance(first - 1);
        }
        List<Outbox.Entry> batch = outbox.read(cursor.get(), BATCH);
        int i = 0;
        while (i < batch.size()) {
            char type = (char) batch.get(i).payload()[0];
            int j = i;
            while (j < batch.size() && (char) batch.get(j).payload()[0] == type) j++;
            send(type, batch.subList(i, j));
            cursor.advance(batch.get(j - 1).offset());
            i = j;
        }
        if (!batch.isEmpty()) afterAck.accept(cursor.get());
        return batch.size();
    }

    private void send(char type, List<Outbox.Entry> entries) throws IOException {
        if (type == 'L') {
            Map<Resource, List<LogRec>> by = new LinkedHashMap<>();
            for (Outbox.Entry e : entries)
                for (OtlpJson.Logs g : OtlpJson.decodeLogs(text(e)))
                    by.computeIfAbsent(g.resource(), k -> new ArrayList<>()).addAll(g.records());
            for (var e : by.entrySet())
                deliver(
                        "/v1/logs",
                        OtlpJson.encodeLogs(e.getKey(), "0.1", e.getValue()),
                        entries.size());
        } else if (type == 'S') {
            Map<Resource, List<SpanRec>> by = new LinkedHashMap<>();
            for (Outbox.Entry e : entries)
                for (OtlpJson.Traces g : OtlpJson.decodeTraces(text(e)))
                    by.computeIfAbsent(g.resource(), k -> new ArrayList<>()).addAll(g.spans());
            for (var e : by.entrySet())
                deliver(
                        "/v1/traces",
                        OtlpJson.encodeTraces(e.getKey(), "0.1", e.getValue()),
                        entries.size());
        } else {
            lost += entries.size();
        }
    }

    private static String text(Outbox.Entry e) {
        return new String(e.payload(), 1, e.payload().length - 1, StandardCharsets.UTF_8);
    }

    private void deliver(String path, String body, int count) throws IOException {
        int status = sender.post(path, body);
        if (status >= 200 && status < 300) return;
        if (status == 401 || status == 403) {
            // say so before the flag flips, so whoever sees the export is dead can already read why
            out.println(
                    "loom-inspect: the OTLP endpoint rejected the credentials (HTTP "
                            + status
                            + "). Exporting has stopped; the workflow is not affected.");
            dead = true;
            throw new IOException("rejected");
        }
        if (status == 400 || status == 413 || status == 404 || status == 415) {
            lost += count;
            report("the OTLP endpoint refused a batch (HTTP " + status + "); skipped");
            return;
        }
        throw new IOException("HTTP " + status); // 429, 5xx: back off and retry the same batch
    }

    private void report(String message) {
        long now = System.currentTimeMillis();
        if (lastReport == 0 || now - lastReport > 60_000) {
            lastReport = now;
            out.println("loom-inspect: " + message);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(Math.max(1, millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** At the end of the process: a short try at delivering what is left. */
    public void finish(Duration timeout) {
        closeDeadline = System.currentTimeMillis() + timeout.toMillis();
        stopping = true;
        try {
            thread.join(timeout.toMillis() + 500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        stopping = true;
        closeDeadline = 0;
        thread.interrupt();
    }
}
