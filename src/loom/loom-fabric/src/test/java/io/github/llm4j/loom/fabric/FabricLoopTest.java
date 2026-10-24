package io.github.llm4j.loom.fabric;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.inspect.control.LoopActions;
import io.github.llm4j.loom.inspect.otel.Ids;
import io.github.llm4j.loom.inspect.otel.LogRec;
import io.github.llm4j.loom.inspect.otel.Resource;
import io.github.llm4j.loom.inspect.otel.Semconv;
import io.github.llm4j.loom.inspect.state.RunRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R37, R38 and R40 of spec 14 on the Fabric side: without a store, or without Loom on the class
 * path, the answer is the command to run; nothing is written for a run Fabric cannot read.
 */
class FabricLoopTest {

    private static final String TRACE = Ids.traceId("r-1");

    @TempDir Path dir;

    private RunRegistry registry() {
        RunRegistry registry = new RunRegistry();
        registry.applyLogs(
                Resource.of("alpha", "i_1"),
                List.of(
                        new LogRec(
                                1_000_000_000L,
                                LogRec.INFO,
                                "w",
                                Map.of(
                                        Semconv.EVENT_NAME,
                                        Semconv.EV_RUN_START,
                                        Semconv.LOOM_SEQ,
                                        1L,
                                        Semconv.LOOM_RUN_ID,
                                        "r-1",
                                        Semconv.LOOM_WORKFLOW,
                                        "Main"),
                                TRACE,
                                null)));
        return registry;
    }

    @Test
    void offUntilAllowed() {
        FabricLoop off = new FabricLoop(FabricLoop.Settings.off(), dir, registry());
        assertThat(off.enabled()).isFalse();
        assertThat(off.disabledReason()).contains("--allow-capture");
        assertThat(
                        new FabricLoop(new FabricLoop.Settings(true, null, null), dir, registry())
                                .enabled())
                .isTrue();
    }

    @Test
    void withoutAStoreTheCommandComesBackAndNothingIsWritten() throws Exception {
        FabricLoop loop =
                new FabricLoop(
                        new FabricLoop.Settings(true, null, dir.resolve("out")), dir, registry());
        LoopActions.Outcome o = loop.capture(TRACE, "looping", false, "admin@x");
        assertThat(o.ok()).isFalse();
        assertThat(o.text()).isNull();
        assertThat(o.command())
                .startsWith("weave capture r-1 --store")
                .contains("--tag incident:looping");
        assertThat(Files.exists(dir.resolve("out"))).isFalse();
        assertThat(loop.testsFor("looping")).isZero();
    }

    @Test
    void withoutLoomOnTheClassPathTheCommandComesBackToo() {
        FabricLoop loop = new FabricLoop(new FabricLoop.Settings(true, dir, null), dir, registry());
        LoopActions.Outcome o = loop.capture(TRACE, null, false, "admin@x");
        assertThat(o.command()).startsWith("weave capture r-1");
        assertThat(o.message()).contains("Loom is not installed");
    }

    @Test
    void theWhatIfCommandCarriesTheChangeQuotedForAShell() {
        FabricLoop loop =
                new FabricLoop(new FabricLoop.Settings(true, null, null), dir, registry());
        LoopActions.Outcome o =
                loop.tryChange(
                        TRACE,
                        "Main/s1",
                        Map.of("tier", "a'b; rm -rf /"),
                        Map.of("Triage", "urgent"),
                        "w");
        assertThat(o.command())
                .startsWith("weave whatif r-1")
                .contains("--at Main/s1")
                .contains("--set 'tier=a'\\''b; rm -rf /'")
                .contains("--answer 'Triage=urgent'");
    }

    @Test
    void aRunThatIsNotKnownIs404() {
        FabricLoop loop =
                new FabricLoop(new FabricLoop.Settings(true, null, null), dir, new RunRegistry());
        assertThat(loop.capture(TRACE, null, false, "w").status()).isEqualTo(404);
        assertThat(loop.tryChange(TRACE, "1", Map.of(), Map.of(), "w").status()).isEqualTo(404);
    }
}
