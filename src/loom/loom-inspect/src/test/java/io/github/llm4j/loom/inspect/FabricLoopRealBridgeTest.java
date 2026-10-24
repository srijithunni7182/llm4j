package io.github.llm4j.loom.inspect;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.fabric.FabricLoop;
import io.github.llm4j.loom.inspect.control.LoopActions;
import io.github.llm4j.loom.inspect.otel.Ids;
import io.github.llm4j.loom.inspect.otel.LogRec;
import io.github.llm4j.loom.inspect.otel.Resource;
import io.github.llm4j.loom.inspect.otel.Semconv;
import io.github.llm4j.loom.inspect.state.RunRegistry;
import io.github.llm4j.loom.travel.ScriptHash;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R36 to R38 and R40 of spec 14 with Loom on the class path: Fabric finds Loom's bridge by name,
 * the call binds, and the case comes back (and is counted against the incident kind).
 */
class FabricLoopRealBridgeTest {

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

    private Path store() throws Exception {
        Path repo = dir.resolve("repo");
        Files.createDirectories(repo.resolve(".git"));
        Path script = repo.resolve("main.loom");
        Files.writeString(script, "workflow Main(topic) {\n    return \"done: {topic}\"\n}\n");
        Path run = dir.resolve("store/runs/r-1");
        Files.createDirectories(run);
        Files.writeString(
                run.resolve("run.json"),
                "{\"script\":\""
                        + script
                        + "\",\"workflow\":\"Main\",\"inputs\":{\"topic\":\"tea\"},\"scriptHash\":\""
                        + ScriptHash.of(script)
                        + "\"}");
        Files.writeString(run.resolve("journal.json"), "{}");
        return dir.resolve("store");
    }

    @Test
    void aCaptureGoesThroughTheBridgeAndIsCountedAgainstTheIncidentKind() throws Exception {
        FabricLoop loop =
                new FabricLoop(
                        new FabricLoop.Settings(true, store(), null),
                        dir.resolve("data"),
                        registry());
        LoopActions.Outcome o = loop.capture(TRACE, "looping", false, "admin@x");
        assertThat(o.ok()).as(o.message()).isTrue();
        assertThat(o.text()).contains("incident:looping").contains("status: observed");
        assertThat(o.name()).endsWith(".test.yaml");
        assertThat(loop.testsFor("looping")).isEqualTo(1);
        loop.capture(TRACE, "looping", false, "admin@x"); // the same run again is not a second test
        assertThat(loop.testsFor("looping")).isEqualTo(1);
        // the count survives a restart
        assertThat(
                        new FabricLoop(
                                        new FabricLoop.Settings(true, null, null),
                                        dir.resolve("data"),
                                        registry())
                                .testsFor("looping"))
                .isEqualTo(1);
        assertThat(Files.exists(dir.resolve("repo/tests"))).isFalse();
    }

    @Test
    void aRunThatIsNotInTheStoreFallsBackToTheCommand() throws Exception {
        Files.createDirectories(dir.resolve("empty"));
        FabricLoop loop =
                new FabricLoop(
                        new FabricLoop.Settings(true, dir.resolve("empty"), null),
                        dir.resolve("data"),
                        registry());
        LoopActions.Outcome o = loop.capture(TRACE, null, false, "w");
        assertThat(o.ok()).isFalse();
        assertThat(o.command()).startsWith("weave capture r-1");
        assertThat(o.message()).contains("does not have that run");
    }

    @Test
    void tryAChangeBindsToo() throws Exception {
        FabricLoop loop =
                new FabricLoop(
                        new FabricLoop.Settings(true, store(), null),
                        dir.resolve("data"),
                        registry());
        LoopActions.Outcome o =
                loop.tryChange(TRACE, "Main/s0", Map.of("topic", "coffee"), Map.of(), "w");
        assertThat(o.ok()).as(o.message()).isTrue();
        assertThat(o.text()).contains("coffee");
    }
}
