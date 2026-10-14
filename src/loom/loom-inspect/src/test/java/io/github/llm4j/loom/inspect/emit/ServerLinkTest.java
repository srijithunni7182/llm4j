package io.github.llm4j.loom.inspect.emit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.inspect.otel.LogRec;
import io.github.llm4j.loom.inspect.otel.OtlpJson;
import io.github.llm4j.loom.inspect.otel.Resource;
import io.github.llm4j.loom.inspect.otel.Semconv;
import io.github.llm4j.loom.inspect.otel.SpanRec;
import io.github.llm4j.loom.inspect.wire.Hello;
import io.github.llm4j.loom.inspect.wire.Snapshot;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The sending side against a fake server: an outage and its backlog, a wrong key, a slow-down request, a cap that dropped records. Milliseconds, no sleeps of seconds. */
class ServerLinkTest {

    private static final Resource RES = Resource.of("p", "i_1");
    private static final ServerLink.Timing FAST = new ServerLink.Timing(Duration.ofMillis(5), Duration.ofMillis(40), Duration.ofMillis(5), Duration.ofMillis(20));

    @TempDir
    Path dir;

    private final ByteArrayOutputStream console = new ByteArrayOutputStream();
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final List<Long> seqs = new CopyOnWriteArrayList<>();
    private final List<String> heartbeats = new CopyOnWriteArrayList<>();
    private final AtomicInteger down = new AtomicInteger();
    private volatile int status = 200;
    private volatile long retryAfter;
    private final Transport transport = (path, body) -> {
        if (down.get() > 0) throw new IOException("connection refused");
        calls.add(path);
        if (status != 200) return new Transport.Reply(status, "{}", retryAfter);
        if (path.equals("/v1/logs")) for (OtlpJson.Logs g : OtlpJson.decodeLogs(body)) g.records().forEach(r -> seqs.add(r.seq()));
        if (path.endsWith("/heartbeat")) heartbeats.add(body);
        return new Transport.Reply(200, path.endsWith("/hello") ? "{\"wire\":1,\"supported\":true}" : "{}", 0);
    };

    private ServerLink link;
    private Outbox outbox;
    private Cursor cursor;

    @AfterEach
    void stop() {
        if (link != null) link.close();
        if (outbox != null) outbox.close();
    }

    private static byte[] log(long seq) {
        LogRec r = new LogRec(1_000_000_000L * seq, LogRec.INFO, "w", Map.of(Semconv.EVENT_NAME, Semconv.EV_RUN_START, Semconv.LOOM_SEQ, seq), "0".repeat(32), null);
        return ("L" + OtlpJson.encodeLogs(RES, "0.1", List.of(r))).getBytes(StandardCharsets.UTF_8);
    }

    private ServerLink open(Outbox ob, List<ServerLink.Source> orphans) {
        outbox = ob;
        cursor = new Cursor(ob.dir(), "fabric");
        Hello hello = new Hello("p", "i_1", "h", 1, "5.0", "0.1", 1, List.of());
        link = new ServerLink("p", transport, new ServerLink.Source(ob, cursor, false), orphans, hello, Map::of, FAST, Clock.systemUTC(), new PrintStream(console));
        return link;
    }

    private Outbox newOutbox(Path d) {
        return new Outbox(d, 8 * 1024 * 1024, 256L * 1024 * 1024, Duration.ofDays(7), Clock.systemUTC());
    }

    private static void until(BooleanSupplier cond) throws InterruptedException {
        long end = System.currentTimeMillis() + 5000;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < end) Thread.sleep(5);
        assertThat(cond.getAsBoolean()).as("condition within 5s").isTrue();
    }

    @Test
    void helloComesFirst_thenSnapshots_thenTheRecordsInOrder_andTheCursorFollows() throws Exception {
        Outbox ob = newOutbox(dir.resolve("o"));
        for (long i = 1; i <= 5; i++) ob.append(log(i));
        open(ob, List.of());
        link.snapshot(new Snapshot("p", "i_1", "a".repeat(32), "r", "W", Map.of(), List.of()));
        link.start();
        until(() -> seqs.size() == 5);
        assertThat(calls.get(0)).isEqualTo("/loom-inspect/v1/hello");
        assertThat(calls.indexOf("/loom-inspect/v1/snapshot")).isLessThan(calls.indexOf("/v1/logs"));
        assertThat(seqs).containsExactly(1L, 2L, 3L, 4L, 5L);
        until(() -> cursor.get() == 5);
        until(() -> !heartbeats.isEmpty());
    }

    @Test
    void aServerThatIsDownDoesNotLoseOrReorderAnything_theBacklogStreamsInWhenItReturns() throws Exception {
        down.set(1);
        Outbox ob = newOutbox(dir.resolve("o"));
        open(ob, List.of());
        link.start();
        for (long i = 1; i <= 50; i++) ob.append(log(i)); // the workflow keeps running: appends never wait for the server
        Thread.sleep(80);
        assertThat(seqs).isEmpty();
        assertThat(link.backlog()).isEqualTo(50);
        assertThat(link.connected()).isFalse();
        down.set(0);
        until(() -> seqs.size() == 50);
        assertThat(seqs).isSorted().doesNotHaveDuplicates();
        assertThat(link.backlog()).isZero();
        assertThat(console.toString()).contains("cannot reach the Fabric server").contains("kept on disk");
    }

    @Test
    void aRestartAfterSendingButBeforeTheAcknowledgementSendsTheBatchAgain_nothingIsMissed() throws Exception {
        Outbox ob = newOutbox(dir.resolve("o"));
        for (long i = 1; i <= 3; i++) ob.append(log(i));
        ob.close();
        // the cursor was never advanced, as if the process died before recording the acknowledgement
        open(newOutbox(dir.resolve("o")), List.of());
        link.start();
        until(() -> seqs.size() == 3);
        assertThat(seqs).containsExactly(1L, 2L, 3L);
    }

    @Test
    void aWrongKeyIsReportedOnceAndReportingStops_theOutboxKeepsWhatIsLeft() throws Exception {
        status = 401;
        Outbox ob = newOutbox(dir.resolve("o"));
        ob.append(log(1));
        open(ob, List.of());
        link.start();
        until(() -> link.dead());
        int seen = calls.size();
        Thread.sleep(60);
        assertThat(calls).hasSize(seen); // nothing more is sent
        assertThat(console.toString()).contains("rejected this process's key").contains("the workflow is not affected");
        assertThat(ob.read(0, 10)).hasSize(1);
    }

    @Test
    void aSlowDownRequestIsHonouredAndThenTheBatchGoesThrough() throws Exception {
        status = 429;
        retryAfter = 0;
        Outbox ob = newOutbox(dir.resolve("o"));
        ob.append(log(1));
        open(ob, List.of());
        link.start();
        Thread.sleep(50);
        assertThat(seqs).isEmpty();
        assertThat(link.dead()).isFalse();
        status = 200;
        until(() -> seqs.size() == 1);
    }

    @Test
    void recordsTheCapDroppedBeforeTheyWereSentAreCountedAsLost_notInvented() throws Exception {
        Outbox ob = new Outbox(dir.resolve("o"), 300, 900, Duration.ofDays(7), Clock.systemUTC());
        for (long i = 1; i <= 40; i++) ob.append(log(i));
        ob.flush();
        long first = ob.firstOffset();
        assertThat(first).isGreaterThan(1);
        open(ob, List.of());
        link.start();
        until(() -> !seqs.isEmpty() && link.backlog() == 0);
        assertThat(link.lost()).isEqualTo(first - 1);
        assertThat(seqs.get(0)).isEqualTo(first);
        until(() -> heartbeats.stream().anyMatch(h -> h.contains("\"lost\":" + (first - 1))));
    }

    @Test
    void theLogOfAFinishedEarlierInstanceIsDrainedFirstAndThenRemoved() throws Exception {
        Path old = dir.resolve("old");
        Outbox earlier = newOutbox(old);
        earlier.append(log(100));
        earlier.append(log(101));
        earlier.close(); // that process is gone: its lock is free
        Outbox ob = newOutbox(dir.resolve("new"));
        ob.append(log(1));
        ServerLink.Source orphan = new ServerLink.Source(newOutbox(old), new Cursor(old, "fabric"), true);
        open(ob, List.of(orphan));
        link.start();
        until(() -> seqs.size() == 3);
        assertThat(seqs).containsExactly(100L, 101L, 1L);
        until(() -> !java.nio.file.Files.exists(old));
    }

    @Test
    void finishDeliversWhatIsLeftQuickly_andDoesNotWaitForADownServer() throws Exception {
        Outbox ob = newOutbox(dir.resolve("o"));
        open(ob, List.of());
        link.start();
        until(() -> link.connected());
        for (long i = 1; i <= 20; i++) ob.append(log(i));
        link.finish(Duration.ofSeconds(2));
        assertThat(seqs).hasSize(20);
        assertThat(calls).contains("/loom-inspect/v1/bye");

        down.set(1);
        ServerLink second = new ServerLink("p", transport, new ServerLink.Source(ob, cursor, false), List.of(), new Hello("p", "i_2", "h", 1, "5", "0.1", 1, List.of()),
                Map::of, FAST, Clock.systemUTC(), new PrintStream(console));
        ob.append(log(21));
        second.start();
        long t0 = System.currentTimeMillis();
        second.finish(Duration.ofMillis(300));
        assertThat(System.currentTimeMillis() - t0).isLessThan(2000);
        assertThat(ob.read(cursor.get(), 10)).isNotEmpty(); // still on disk for the next process
        second.close();
    }

    private static byte[] span(long n) {
        SpanRec sp = new SpanRec("0".repeat(32), String.format("%016x", n), null, "s", SpanRec.CLIENT, 1_000L * n, 2_000L * n, Map.of(Semconv.LOOM_SPAN_KIND, "tool"), SpanRec.STATUS_OK, null, List.of());
        return ("S" + OtlpJson.encodeTraces(RES, "0.1", List.of(sp))).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void aBatchOfAlternatingLogsAndSpansCostsOneRequestPerKindNotOnePerChange() throws Exception {
        Outbox ob = newOutbox(dir.resolve("o"));
        for (long i = 1; i <= 100; i++) {
            ob.append(log(i));
            ob.append(span(i));
        }
        open(ob, List.of());
        link.start();
        until(() -> cursor.get() == 200);
        long dataCalls = calls.stream().filter(c -> c.equals("/v1/logs") || c.equals("/v1/traces")).count();
        assertThat(dataCalls).as("200 records, alternating, in one batch").isEqualTo(2);
        assertThat(seqs).as("the logs keep their order").isSorted();
    }

    @Test
    void theRunEndRecordIsSentAfterTheSpansOfItsBatch() throws Exception {
        Outbox ob = newOutbox(dir.resolve("o"));
        LogRec end = new LogRec(5_000_000_000L, LogRec.INFO, "end", Map.of(Semconv.EVENT_NAME, Semconv.EV_RUN_END, Semconv.LOOM_SEQ, 3L), "0".repeat(32), null);
        ob.append(("L" + OtlpJson.encodeLogs(RES, "0.1", List.of(end))).getBytes(StandardCharsets.UTF_8));
        ob.append(span(1));
        ob.append(log(4));
        open(ob, List.of());
        link.start();
        until(() -> cursor.get() == 3);
        List<String> data = calls.stream().filter(c -> c.equals("/v1/logs") || c.equals("/v1/traces")).toList();
        assertThat(data).containsExactly("/v1/logs", "/v1/traces", "/v1/logs");
    }
}
