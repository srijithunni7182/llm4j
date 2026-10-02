package io.github.llm4j.loom.rewind;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.generic.support.ScriptedRun;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.loom.runtime.RunStopped;
import io.github.llm4j.loom.travel.OverlayJournal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Simulated runs, forks that never touch their parent, and stopping at a step (spec loom-rewind-and-fork R4.5, R5, R8.4). */
class SimulateAndForkTest {

    @TempDir
    Path dir;

    /** A host-registered Java tool that changes something: counts its real calls. */
    static final class Stamper implements Tool {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());

        @Override public String getName() { return "Stamp"; }
        @Override public String getDescription() { return "Stamps a document."; }
        @Override public String execute(Map<String, Object> args) { calls.add(String.valueOf(args)); return "stamped"; }
    }

    static final String SCRIPT = """
            tool Log { use: file  root: "out"  mode: write }
            agent Worker { model: "m" system: "You are Worker." tools: [Log, Stamp, calculator] max_iterations: 8 }
            workflow W() {
                delegate "Do the work" to Worker -> done
            }
            """;

    /** Worker: write a line, stamp, calculate, then answer: one tool call per turn. */
    static String worker(io.github.llm4j.model.LLMRequest request) {
        String message = ScriptedRun.lastMessage(request);
        int observed = message.split("Observation:", -1).length - 1;
        return switch (observed) {
            case 0 -> ScriptedRun.call("Log", "{\"action\": \"append\", \"path\": \"n.md\", \"content\": \"hello\"}");
            case 1 -> ScriptedRun.call("Stamp", "{\"doc\": \"a\"}");
            case 2 -> ScriptedRun.call("calculator", "{\"expression\": \"2+2\"}");
            default -> ScriptedRun.done("finished: " + message.substring(message.lastIndexOf("Observation:")).replace("\n", " "));
        };
    }

    @Test
    @Tag("RW-V4.8")
    void inASimulatedRunNoEffectIsPerformedAndNothingIsRecordedAsDone() throws IOException {
        Stamper stamper = new Stamper();
        ScriptedRun run = new ScriptedRun(dir);
        run.responder = SimulateAndForkTest::worker;
        run.journal = RunJournal.inMemory();
        run.tools.register("Stamp", stamper);
        HarnessExecutor executor = run.executor(SCRIPT);
        executor.setSimulate(true);
        executor.initialize();
        executor.executeWorkflow("W", Map.of());

        assertThat(dir.resolve("out/n.md")).doesNotExist(); // the file tool pretended
        assertThat(stamper.calls).isEmpty();                // the host's own tool pretended too, by class
        assertThat(run.journal.all().keySet().stream().filter(k -> k.contains("#effect:"))).isEmpty();
        assertThat(run.journal.all().get("W/s0").value().toString()).contains("4"); // the calculator really ran
    }

    @Test
    @Tag("RW-V4.8")
    void aRealRunPerformsTheSameCalls() throws IOException {
        Stamper stamper = new Stamper();
        ScriptedRun run = new ScriptedRun(dir);
        run.responder = SimulateAndForkTest::worker;
        run.journal = RunJournal.inMemory();
        run.tools.register("Stamp", stamper);
        HarnessExecutor executor = run.executor(SCRIPT);
        executor.initialize();
        executor.executeWorkflow("W", Map.of());

        assertThat(Files.readString(dir.resolve("out/n.md"))).contains("hello");
        assertThat(stamper.calls).hasSize(1);
    }

    @Test
    @Tag("RW-V5.10")
    @Tag("RW-V8.4")
    void anEphemeralForkRunsOnAnOverlayAndNeverWritesTheParent() {
        ReportScript model = new ReportScript(5, 8);
        RunJournal parent = RunJournal.inMemory();
        ScriptedRun first = new ScriptedRun(dir);
        first.responder = model::reply;
        first.journal = parent;
        var e1 = first.executor(ReportScript.SCRIPT);
        e1.initialize();
        e1.executeWorkflow("Report", Map.of("topic", "bees"));
        Map<String, RunJournal.Entry> before = Map.copyOf(parent.all());
        long publishers = model.count("Publisher");

        OverlayJournal overlay = new OverlayJournal(parent);
        io.github.llm4j.loom.runtime.Generations g = new io.github.llm4j.loom.runtime.Generations(overlay);
        g.start("Report/s", 1, "collected", "test", null, "what if", Map.of("feedback", "forked"), "keep", false);
        ScriptedRun fork = new ScriptedRun(dir);
        fork.responder = model::reply;
        fork.journal = overlay;
        var e2 = fork.executor(ReportScript.SCRIPT);
        e2.initialize();
        e2.executeWorkflow("Report", Map.of("topic", "bees"));

        assertThat(parent.all()).isEqualTo(before); // not one entry added, changed or removed
        assertThat(overlay.written()).isNotEmpty();
        assertThat(model.count("Publisher")).isGreaterThan(publishers);
    }

    @Test
    @Tag("RW-V5.9")
    void aRunStopsRightAfterANamedStepAndResumesFromThere() {
        ReportScript model = new ReportScript(8);
        RunJournal journal = RunJournal.inMemory();
        ScriptedRun run = new ScriptedRun(dir);
        run.responder = model::reply;
        run.journal = journal;
        HarnessExecutor executor = run.executor(ReportScript.SCRIPT);
        executor.setStopAt("Report/s2");
        executor.initialize();
        assertThatThrownBy(() -> executor.executeWorkflow("Report", Map.of("topic", "bees"))).isInstanceOf(RunStopped.class).hasMessageContaining("Report/s2");
        assertThat(journal.all()).containsKey("Report/s2").doesNotContainKey("Report/s3");
        assertThat(model.count("Writer")).isZero();

        ScriptedRun again = new ScriptedRun(dir);
        again.responder = model::reply;
        again.journal = journal;
        var e = again.executor(ReportScript.SCRIPT);
        e.initialize();
        e.executeWorkflow("Report", Map.of("topic", "bees"));
        assertThat(model.count("Collector")).isEqualTo(1); // finished steps were not run again
        assertThat(journal.all()).containsKey("Report/s6");
    }
}
