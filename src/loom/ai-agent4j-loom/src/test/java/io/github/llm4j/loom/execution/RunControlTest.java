package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.runtime.FileRunJournal;
import io.github.llm4j.loom.runtime.RunStopped;
import io.github.llm4j.loom.runtime.RunSuspended;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What an observer may do to a run, and when it takes effect: between steps, never in the middle of one. No model, no clock. */
class RunControlTest {

    private static final String SCRIPT = """
            workflow Main() {
                note "one"
                note "two"
                note "three"
                note "four"
            }
            """;

    @TempDir
    Path dir;

    private final List<String> notes = Collections.synchronizedList(new ArrayList<>());
    private final List<TraceEvent> controls = Collections.synchronizedList(new ArrayList<>());

    private HarnessExecutor executor(java.util.function.Consumer<TraceEvent> onEvent) {
        LoomScript script = new LoomParser(new Lexer(SCRIPT).tokenize()).parseScript();
        HarnessExecutor e = new HarnessExecutor(script, new io.github.llm4j.loom.execution.ToolRegistry(), model -> {
            throw new IllegalStateException("no model in this test");
        });
        e.addTraceListener(event -> {
            if (TraceEvent.NOTE.equals(event.type())) notes.add(event.text());
            if (TraceEvent.CONTROL.equals(event.type())) controls.add(event);
            if (onEvent != null) onEvent.accept(event);
        });
        e.initialize();
        return e;
    }

    private static void until(BooleanSupplier c) throws InterruptedException {
        long end = System.currentTimeMillis() + 5000;
        while (!c.getAsBoolean() && System.currentTimeMillis() < end) Thread.sleep(5);
        assertThat(c.getAsBoolean()).as("within 5s").isTrue();
    }

    private List<String> states() {
        synchronized (controls) {
            return controls.stream().map(e -> e.data().get("action") + ":" + e.data().get("state")).toList();
        }
    }

    @Test
    void aStopAskedForDuringAStepTakesEffectAtTheNextStep_andTheRunEndsAsStopped() {
        AtomicReference<HarnessExecutor> self = new AtomicReference<>();
        HarnessExecutor e = executor(event -> {
            if (TraceEvent.NOTE.equals(event.type()) && "two".equals(event.text())) self.get().control().request(RunControl.Action.STOP, "alice");
        });
        self.set(e);
        List<String> ends = new ArrayList<>();
        e.addTraceListener(event -> {
            if (TraceEvent.RUN_END.equals(event.type())) ends.add(String.valueOf(event.data().get("status")));
        });
        assertThatThrownBy(() -> e.executeWorkflow("Main", Map.of())).isInstanceOfSatisfying(RunStopped.class,
                s -> assertThat(s.point()).contains("Main/s2").contains("asked by alice"));
        assertThat(notes).containsExactly("one", "two"); // the step that was running finished; the next never started
        assertThat(states()).containsExactly("stop:requested", "stop:applied");
        assertThat(ends).containsExactly("stopped");
    }

    @Test
    void aSuspendEndsTheRunAsSuspendedByAnOperator_andADurableRunGoesOnFromWhereItWas() {
        AtomicReference<HarnessExecutor> self = new AtomicReference<>();
        LoomScript script = new LoomParser(new Lexer(SCRIPT).tokenize()).parseScript();
        FileRunJournal journal = new FileRunJournal(dir.resolve("journal.json"));
        HarnessExecutor first = new HarnessExecutor(script, new io.github.llm4j.loom.execution.ToolRegistry(), m -> null);
        first.setJournal(journal);
        first.setRunId("r-control");
        first.addTraceListener(event -> {
            if (TraceEvent.NOTE.equals(event.type())) notes.add(event.text());
            if (TraceEvent.NOTE.equals(event.type()) && "one".equals(event.text())) first.control().request(RunControl.Action.SUSPEND, "alice");
        });
        first.initialize();
        assertThatThrownBy(() -> first.executeWorkflow("Main", Map.of())).isInstanceOfSatisfying(RunSuspended.class, s -> {
            assertThat(s.reason()).isEqualTo(RunSuspended.Reason.OPERATOR);
            assertThat(s.resumeAt()).isNull();
            assertThat(s.stepId()).isEqualTo("Main/s1");
            assertThat(s.prompt()).contains("alice");
        });
        assertThat(notes).containsExactly("one");

        HarnessExecutor second = new HarnessExecutor(script, new io.github.llm4j.loom.execution.ToolRegistry(), m -> null);
        second.setJournal(journal);
        second.setRunId("r-control");
        second.addTraceListener(event -> {
            if (TraceEvent.NOTE.equals(event.type())) notes.add(event.text());
        });
        second.initialize();
        second.executeWorkflow("Main", Map.of());           // a new executor has its own control: nothing is held over
        assertThat(notes).contains("two", "three", "four");
    }

    @Test
    void aPausedRunHoldsBeforeItsNextStepUntilResumed() throws Exception {
        HarnessExecutor e = executor(null);
        e.control().request(RunControl.Action.PAUSE, "alice");
        Thread run = new Thread(() -> e.executeWorkflow("Main", Map.of()));
        run.start();
        until(() -> e.control().state().held());
        assertThat(notes).isEmpty();                          // held before the first step
        assertThat(states()).containsExactly("pause:requested", "pause:held");
        assertThat(run.isAlive()).isTrue();

        e.control().request(RunControl.Action.RESUME, "alice");
        run.join(5000);
        assertThat(run.isAlive()).isFalse();
        assertThat(notes).containsExactly("one", "two", "three", "four");
        assertThat(states()).containsExactly("pause:requested", "pause:held", "pause:resumed");
        assertThat(e.control().state().paused()).isFalse();
    }

    @Test
    void aPausedRunCanStillBeStopped() throws Exception {
        HarnessExecutor e = executor(null);
        e.control().request(RunControl.Action.PAUSE, "alice");
        AtomicReference<Throwable> ended = new AtomicReference<>();
        Thread run = new Thread(() -> {
            try {
                e.executeWorkflow("Main", Map.of());
            } catch (Throwable t) {
                ended.set(t);
            }
        });
        run.start();
        until(() -> e.control().state().held());
        e.control().request(RunControl.Action.STOP, "bob");
        run.join(5000);
        assertThat(run.isAlive()).isFalse();
        assertThat(ended.get()).isInstanceOf(RunStopped.class);
        assertThat(notes).isEmpty();
    }

    @Test
    void aStopAskedForBeforeARunBeginsIsNotMeantForIt_butAPauseStays() throws Exception {
        HarnessExecutor e = executor(null);
        e.control().request(RunControl.Action.STOP, "alice");
        e.executeWorkflow("Main", Map.of());
        assertThat(notes).containsExactly("one", "two", "three", "four");
    }

    @Test
    void askingTwiceOrForNothingSaysNothing() {
        RunControl c = new RunControl();
        List<String> seen = new ArrayList<>();
        c.addListener(e -> seen.add(e.action() + ":" + e.state()));
        c.request(RunControl.Action.RESUME, "a");   // nothing to resume
        c.request(RunControl.Action.PAUSE, "a");
        c.request(RunControl.Action.PAUSE, "a");    // already asked
        c.request(RunControl.Action.RESUME, "a");   // paused but never held: cancelled, not "resumed"
        assertThat(seen).containsExactly("PAUSE:requested", "PAUSE:cancelled");
        assertThat(c.state().paused()).isFalse();
    }

    @Test
    void aRequestToStopCanBeWithdrawnBeforeTheRunReachesAStep() {
        RunControl c = new RunControl();
        List<String> seen = new ArrayList<>();
        c.addListener(e -> seen.add(e.action() + ":" + e.state()));
        c.request(RunControl.Action.STOP, "a");
        assertThat(c.state().pending()).isEqualTo(RunControl.Action.STOP);
        c.request(RunControl.Action.RESUME, "a");
        assertThat(c.state().pending()).isNull();
        assertThat(seen).containsExactly("STOP:requested", "STOP:cancelled");
    }

    @Test
    void anObserverThatFailsNeverBreaksTheRun() {
        HarnessExecutor e = executor(null);
        e.control().addListener(ev -> {
            throw new IllegalStateException("a broken observer");
        });
        e.control().request(RunControl.Action.PAUSE, "a");
        e.control().request(RunControl.Action.RESUME, "a");
        e.executeWorkflow("Main", Map.of());
        assertThat(notes).hasSize(4);
    }
}
