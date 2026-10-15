package io.github.llm4j.loom.execution;

import static org.junit.jupiter.api.Assertions.*;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.runtime.RunJournal;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** An embedding application, a console and a chat reach the agents of a run going on in this process. */
class ReachingARunTest {

    private static final String SCRIPT = """
            agent Collector { model: "test" system: "You are Collector." }
            agent Looper { model: "test" system: "You are Looper." tools: [MockTool] max_iterations: 50 steerable: true }
            agent Closer { model: "test" system: "You are Closer." }
            workflow Main() {
                checkpoint collected starting with notes = "none"
                delegate "collect. Notes: {notes}" to Collector -> data
                delegate "write from {data}" to Looper -> out_text
                    on_stopped {
                        alt (_stop.reason == "budget") {
                            rewind to collected at most 2 times carrying notes = "{_stop.note}" side effects: keep
                        }
                    }
                delegate "close" to Closer -> done_text
            }
            """;

    private static HarnessExecutor executor(InterventionRuntimeTest.Model model) {
        ToolRegistry tools = new ToolRegistry();
        tools.register("MockTool", new MockTool());
        LoomScript script = new LoomParser(new Lexer(SCRIPT).tokenize()).parseScript();
        HarnessExecutor ex = new HarnessExecutor(script, tools, model.factory());
        ex.setJournal(RunJournal.inMemory());
        ex.initialize();
        return ex;
    }

    private static LoomRun start(InterventionRuntimeTest.Model model, String label) throws Exception {
        LoomRun run = LoomRun.start(executor(model), label, "Main", Map.of(), "test-app");
        assertTrue(model.entered.await(5, TimeUnit.SECONDS), "the agent started");
        return run;
    }

    private static void finish(LoomRun run, InterventionRuntimeTest.Model model) throws Exception {
        model.release.countDown();
        assertTrue(run.await(Duration.ofSeconds(10)), "the run ended");
    }

    // ---- the embedding class ----

    @Test
    void anApplicationCanSteerAndTheRunCompletes() throws Exception {
        var model = new InterventionRuntimeTest.Model(false);
        LoomRun run = start(model, "steer-run");
        assertEquals(LoomRun.Status.RUNNING, run.status());
        assertEquals(java.util.List.of("Looper"), run.runningAgents().values().stream().toList());
        assertEquals(1, run.steer("Looper", "focus on the EU"));
        model.finishNow = true;
        finish(run, model);
        assertEquals(LoomRun.Status.COMPLETED, run.status());
        assertEquals("done", run.executor().getContext().getVariable("out_text"));
    }

    @Test
    void anApplicationNamesTheOutcomeOfACancel() throws Exception {
        var model = new InterventionRuntimeTest.Model(false);
        LoomRun run = start(model, "skip-run");
        assertEquals(1, run.cancel("Looper", Outcome.SKIP, "not needed"));
        finish(run, model);
        assertEquals(LoomRun.Status.COMPLETED, run.status());
        assertEquals("", run.executor().getContext().getVariable("out_text"));
        assertEquals("done", run.executor().getContext().getVariable("done_text"));
    }

    @Test
    void anApplicationCanSendTheRunBackToACheckpoint() throws Exception {
        var model = new InterventionRuntimeTest.Model(false);
        LoomRun run = start(model, "back-run");
        model.finishNow = true;
        assertEquals(1, run.cancel("Looper", Outcome.rewindTo("collected"), "the sources were stale"));
        finish(run, model);
        assertEquals(LoomRun.Status.COMPLETED, run.status(), String.valueOf(run.failure()));
        assertEquals("done", run.executor().getContext().getVariable("out_text"));
    }

    @Test
    void aRunThatIsSuspendedByAStopSaysSo() throws Exception {
        var model = new InterventionRuntimeTest.Model(false);
        LoomRun run = start(model, "suspend-run");
        run.cancel("Looper", Outcome.SUSPEND, "going home");
        finish(run, model);
        assertEquals(LoomRun.Status.SUSPENDED, run.status());
        assertNotNull(run.failure());
    }

    @Test
    void aFinishedRunIsNoLongerLive() throws Exception {
        var model = new InterventionRuntimeTest.Model(false);
        model.blockFirstCall = false;
        model.finishNow = true;
        LoomRun run = LoomRun.start(executor(model), "gone-run", "Main", Map.of(), "test-app");
        assertTrue(run.await(Duration.ofSeconds(10)));
        assertTrue(LiveRuns.shared().all().stream().noneMatch(r -> r.label().equals("gone-run")));
    }

    // ---- the words a person types ----

    private final InterventionCommands commands = InterventionCommands.forThisProcess();

    @Test
    void theCommandsAreRecognised() {
        for (String ok : new String[] {"steer focus", "wrap up Looper", "wrapup", "cancel Looper skip", "cancel back to c: why", "pause", "resume", "agents", "#r1 agents", "Steer Looper x"}) {
            assertTrue(commands.isCommand(ok), ok);
        }
        for (String no : new String[] {"yes", "keep", "approve", "K7F3QX cancel", "", "steering wheel", "agentsmith"}) {
            assertFalse(commands.isCommand(no), no);
        }
    }

    @Test
    void withNothingRunningTheAnswerSaysSo() {
        assertEquals("Nothing is running here.", commands.handle("steer hello", "chat:1"));
    }

    @Test
    void aPersonCanListSteerAndWrapUp() throws Exception {
        var model = new InterventionRuntimeTest.Model(false);
        LoomRun run = start(model, "talk-run");
        assertEquals("#talk-run: Looper", commands.handle("agents", "console"));
        assertTrue(commands.handle("steer Looper focus on the EU", "console").startsWith("Sent to Looper"));
        assertTrue(commands.handle("steer focus on the EU market", "console").startsWith("Sent to Looper"), "the only agent working may be left out");
        assertTrue(commands.handle("steer Looper", "console").startsWith("Steer Looper with what?"));
        assertTrue(commands.handle("wrap up Looper : that is enough", "console").contains("finishing"));
        finish(run, model);
        assertEquals(LoomRun.Status.COMPLETED, run.status());
        var steers = run.executor().getJournal().all().values().stream().filter(e -> "steer".equals(e.kind())).count();
        assertEquals(2, steers);
    }

    @Test
    void aPersonCanCancelWithAnOutcomeAndAReason() throws Exception {
        var model = new InterventionRuntimeTest.Model(false);
        LoomRun run = start(model, "cancel-run");
        assertTrue(commands.handle("cancel Looper bogus", "console").startsWith("I do not know the outcome"));
        assertTrue(commands.handle("#cancel-run cancel Looper skip : not wanted", "console").contains("skip"));
        finish(run, model);
        assertEquals("", run.executor().getContext().getVariable("out_text"));
        assertTrue(run.executor().getJournal().all().values().stream().anyMatch(e -> "stop".equals(e.kind())
                && "not wanted".equals(((Map<?, ?>) e.value()).get("reason")) && "console".equals(((Map<?, ?>) e.value()).get("origin"))));
    }

    @Test
    void aPersonCanSendTheRunBackAndIsToldWhenTheyCannot() throws Exception {
        var model = new InterventionRuntimeTest.Model(false);
        LoomRun run = start(model, "backtalk-run");
        assertTrue(commands.handle("cancel Looper back to nowhere : x", "chat:7").startsWith("Not sent back"));
        model.finishNow = true;
        assertTrue(commands.handle("cancel Looper back to collected : the sources were stale", "chat:7").contains("goes back to collected"));
        finish(run, model);
        assertEquals(LoomRun.Status.COMPLETED, run.status(), String.valueOf(run.failure()));
    }

    @Test
    void whenTwoRunsHaveTheSameAgentThePersonSaysWhich() throws Exception {
        var first = new InterventionRuntimeTest.Model(false);
        var second = new InterventionRuntimeTest.Model(false);
        LoomRun a = start(first, "run-a");
        LoomRun b = start(second, "run-b");
        String refused = commands.handle("steer Looper hello", "console");
        assertTrue(refused.contains("more than one run") && refused.contains("#run-a") && refused.contains("#run-b"), refused);
        assertTrue(commands.handle("#run-b steer Looper hello", "console").startsWith("Sent to Looper"));
        assertEquals("No run called nope is going on here.", commands.handle("#nope agents", "console"));
        first.finishNow = true;
        second.finishNow = true;
        finish(a, first);
        finish(b, second);
    }

    @Test
    void anAgentThatIsNotSteerableSaysSo() throws Exception {
        var model = new InterventionRuntimeTest.Model(false);
        ToolRegistry tools = new ToolRegistry();
        tools.register("MockTool", new MockTool());
        LoomScript script = new LoomParser(new Lexer(SCRIPT.replace(" steerable: true", "")).tokenize()).parseScript();
        HarnessExecutor ex = new HarnessExecutor(script, tools, model.factory());
        ex.initialize();
        LoomRun run = LoomRun.start(ex, "plain-run", "Main", Map.of(), "test-app");
        assertTrue(model.entered.await(5, TimeUnit.SECONDS));
        assertTrue(commands.handle("steer Looper hello", "console").contains("not steerable"));
        model.finishNow = true;
        finish(run, model);
    }
}
