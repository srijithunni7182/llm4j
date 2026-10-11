package io.github.llm4j.loom.inspect.emit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.inspect.otel.LogRec;
import io.github.llm4j.loom.inspect.otel.OtlpJson;
import io.github.llm4j.loom.inspect.otel.Resource;
import io.github.llm4j.loom.inspect.otel.Semconv;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OutboxToolsTest {

    private static final ServerLink.Timing FAST = new ServerLink.Timing(Duration.ofMillis(5), Duration.ofMillis(40), Duration.ofMillis(5), Duration.ofMillis(20));

    @TempDir
    Path root;

    private final ByteArrayOutputStream console = new ByteArrayOutputStream();
    private final List<Long> seqs = new CopyOnWriteArrayList<>();
    private volatile boolean up = true;
    private final Transport transport = (path, body) -> {
        if (!up) throw new IOException("connection refused");
        if (path.equals("/v1/logs")) for (OtlpJson.Logs g : OtlpJson.decodeLogs(body)) g.records().forEach(r -> seqs.add(r.seq()));
        return new Transport.Reply(200, "{\"supported\":true}", 0);
    };

    private static byte[] log(long seq) {
        LogRec r = new LogRec(1_000_000_000L * seq, LogRec.INFO, "w", Map.of(Semconv.EVENT_NAME, Semconv.EV_RUN_START, Semconv.LOOM_SEQ, seq), "0".repeat(32), null);
        return ("L" + OtlpJson.encodeLogs(Resource.of("p", "i_1"), "0.1", List.of(r))).getBytes(StandardCharsets.UTF_8);
    }

    private void leftBehind(String instance, long from, long to) {
        try (Outbox o = new Outbox(root.resolve("p").resolve(instance), 8 << 20, 256L << 20, Duration.ofDays(7), Clock.systemUTC())) {
            for (long i = from; i <= to; i++) o.append(log(i));
            o.flush();
            try (var segs = Files.list(o.dir())) {
                for (Path p : (Iterable<Path>) segs.filter(f -> f.getFileName().toString().startsWith("seg-"))::iterator) {
                    Files.setLastModifiedTime(p, java.nio.file.attribute.FileTime.fromMillis(1_700_000_000_000L + from * 1000)); // older ones first
                }
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
    }

    @Test
    void flushDeliversWhatFinishedProcessesLeftBehind_oldestFirst_andRemovesTheEmptiedOutboxes() {
        leftBehind("i_b", 100, 101); // written second, named first: the order is by when, not by name
        leftBehind("i_a", 1, 3);
        long left = OutboxTools.flush("p", root, transport, Duration.ofSeconds(5), Clock.systemUTC(), new PrintStream(console), FAST);
        assertThat(left).isZero();
        assertThat(seqs).containsExactly(1L, 2L, 3L, 100L, 101L);
        assertThat(Files.exists(root.resolve("p/i_a"))).isFalse();
        assertThat(Files.exists(root.resolve("p/i_b"))).isFalse();
        assertThat(console.toString()).contains("Delivered 5 record(s); 0 remain");
    }

    @Test
    void whenTheServerIsDownItGivesUpAtTheTimeoutAndLeavesEverythingOnDisk() {
        up = false;
        leftBehind("i_a", 1, 3);
        long left = OutboxTools.flush("p", root, transport, Duration.ofMillis(200), Clock.systemUTC(), new PrintStream(console), FAST);
        assertThat(left).isEqualTo(3);
        assertThat(Files.exists(root.resolve("p/i_a"))).isTrue();
        assertThat(console.toString()).contains("3 remain");
    }

    @Test
    void nothingToFlushSaysSo_andARunningProcessOutboxIsLeftAlone() {
        assertThat(OutboxTools.flush("p", root, transport, Duration.ofSeconds(1), Clock.systemUTC(), new PrintStream(console), FAST)).isZero();
        assertThat(console.toString()).contains("Nothing to flush");
        try (Outbox live = new Outbox(root.resolve("p/i_live"), 8 << 20, 256L << 20, Duration.ofDays(7), Clock.systemUTC())) {
            live.append(log(1));
            live.flush();
            assertThat(OutboxTools.flush("p", root, transport, Duration.ofSeconds(1), Clock.systemUTC(), new PrintStream(console), FAST)).isZero();
            assertThat(seqs).isEmpty();
            OutboxTools.purge("p", root, Clock.systemUTC(), new PrintStream(console));
            assertThat(Files.exists(root.resolve("p/i_live"))).isTrue();
        }
    }

    @Test
    void purgeRemovesIdleOutboxesAndCountsWhatWasUndelivered() {
        leftBehind("i_a", 1, 4);
        long lost = OutboxTools.purge("p", root, Clock.systemUTC(), new PrintStream(console));
        assertThat(lost).isEqualTo(4);
        assertThat(Files.exists(root.resolve("p/i_a"))).isFalse();
        assertThat(console.toString()).contains("Removed 1 outbox(es)");
    }
}
