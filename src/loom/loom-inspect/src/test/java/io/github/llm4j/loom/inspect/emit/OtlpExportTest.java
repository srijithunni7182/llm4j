package io.github.llm4j.loom.inspect.emit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.inspect.otel.LogRec;
import io.github.llm4j.loom.inspect.otel.OtlpJson;
import io.github.llm4j.loom.inspect.otel.Resource;
import io.github.llm4j.loom.inspect.otel.Semconv;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OtlpExportTest {

    private static final Resource RES = Resource.of("p", "i_1");
    private static final ServerLink.Timing FAST =
            new ServerLink.Timing(
                    Duration.ofMillis(5),
                    Duration.ofMillis(40),
                    Duration.ofMillis(5),
                    Duration.ofMillis(20));

    @TempDir Path dir;

    private final List<Long> seqs = new CopyOnWriteArrayList<>();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final ByteArrayOutputStream console = new ByteArrayOutputStream();
    private volatile int status = 200;
    private final OtlpExport.Sender sender =
            (path, body) -> {
                if (status < 0) throw new IOException("connection refused");
                paths.add(path);
                if (status == 200 && path.equals("/v1/logs"))
                    for (OtlpJson.Logs g : OtlpJson.decodeLogs(body))
                        g.records().forEach(r -> seqs.add(r.seq()));
                return status < 0 ? 200 : status;
            };
    private Outbox outbox;
    private OtlpExport export;

    @AfterEach
    void stop() {
        if (export != null) export.close();
        if (outbox != null) outbox.close();
    }

    private static byte[] log(long seq) {
        LogRec r =
                new LogRec(
                        1_000_000_000L * seq,
                        LogRec.INFO,
                        "w",
                        Map.of(Semconv.EVENT_NAME, Semconv.EV_RUN_START, Semconv.LOOM_SEQ, seq),
                        "0".repeat(32),
                        null);
        return ("L" + OtlpJson.encodeLogs(RES, "0.1", List.of(r))).getBytes(StandardCharsets.UTF_8);
    }

    private OtlpExport open() {
        outbox =
                new Outbox(
                        dir.resolve("o"),
                        8 << 20,
                        256L << 20,
                        Duration.ofDays(7),
                        Clock.systemUTC());
        export =
                new OtlpExport(
                        outbox,
                        new Cursor(outbox.dir(), "otlp"),
                        sender,
                        FAST,
                        new PrintStream(console),
                        acked -> outbox.trim(acked));
        return export;
    }

    private static void until(BooleanSupplier c) throws InterruptedException {
        long end = System.currentTimeMillis() + 5000;
        while (!c.getAsBoolean() && System.currentTimeMillis() < end) Thread.sleep(5);
        assertThat(c.getAsBoolean()).isTrue();
    }

    @Test
    void recordsAreSentInOrderToTheStandardPaths_andNothingIsSentTwice() throws Exception {
        open();
        for (long i = 1; i <= 5; i++) outbox.append(log(i));
        export.start();
        until(() -> seqs.size() == 5);
        assertThat(seqs).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(paths).containsOnly("/v1/logs");
        Thread.sleep(50);
        assertThat(seqs).hasSize(5);
    }

    @Test
    void anEndpointThatIsDownGetsTheBacklogWhenItReturns_inOrder() throws Exception {
        status = -1;
        open();
        export.start();
        for (long i = 1; i <= 30; i++) outbox.append(log(i));
        Thread.sleep(60);
        assertThat(seqs).isEmpty();
        assertThat(export.backlog()).isEqualTo(30);
        status = 200;
        until(() -> seqs.size() == 30);
        assertThat(seqs).isSorted().doesNotHaveDuplicates();
        assertThat(console.toString()).contains("cannot reach the OTLP endpoint");
    }

    @Test
    void serverErrorsAreRetried_butRejectedCredentialsStopTheExportForGood() throws Exception {
        status = 503;
        open();
        outbox.append(log(1));
        export.start();
        Thread.sleep(50);
        assertThat(export.dead()).isFalse();
        status = 200;
        until(() -> seqs.size() == 1);

        status = 401;
        outbox.append(log(2));
        until(() -> export.dead());
        assertThat(console.toString())
                .contains("rejected the credentials")
                .contains("not affected");
    }

    @Test
    void aBatchTheEndpointCallsMalformedIsSkippedAndCounted_soTheRestCanFlow() throws Exception {
        status = 400;
        open();
        outbox.append(log(1));
        export.start();
        until(() -> export.lost() == 1);
        status = 200;
        outbox.append(log(2));
        until(() -> seqs.contains(2L));
    }

    @Test
    void secretsInHeadersAreResolvedWhenSent_andAMissingOneIsAClearError() {
        Map<String, String> h =
                OtlpExport.resolveHeaders(
                        List.of("Authorization=Bearer ${secret:OTLP_TOKEN}", "X-Team=core"),
                        n -> n.equals("OTLP_TOKEN") ? "tok-123" : null);
        assertThat(h)
                .containsEntry("Authorization", "Bearer tok-123")
                .containsEntry("X-Team", "core");
        assertThatThrownBy(
                        () ->
                                OtlpExport.resolveHeaders(
                                        List.of("Authorization=Bearer ${secret:NOPE}"), n -> null))
                .hasMessageContaining("NOPE")
                .hasMessageNotContaining("tok");
    }
}
