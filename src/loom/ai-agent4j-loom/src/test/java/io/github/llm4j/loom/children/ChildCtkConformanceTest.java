package io.github.llm4j.loom.children;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.task.TaskRegistry;
import io.github.llm4j.agent.task.TaskResult;
import io.github.llm4j.agent.tool.EffectPolicy;
import io.github.llm4j.loom.execution.ControlledChildScheduler;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.runtime.ChildHandle;
import io.github.llm4j.loom.runtime.ChildStatus;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.loom.runtime.RunSuspended;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Runs the canonical started-workflow scenarios of the Loom Conformance Test Kit ({@code loom/ctk}):
 * a child is a run of its own, its status is a value, a cancel before it ran means it never runs,
 * and a parent that is killed and run again finds the same child and repeats no effect.
 */
class ChildCtkConformanceTest {

    private static final Path CTK = Path.of("..", "ctk", "scripts").toAbsolutePath().normalize();

    private static final class MovingClock extends java.time.Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }
    }

    private final AtomicInteger lookups = new AtomicInteger();
    private final Map<String, RunJournal> journals = new ConcurrentHashMap<>();
    private final MovingClock clock = new MovingClock();

    private HarnessExecutor executor(String script) throws Exception {
        TaskRegistry tasks = new TaskRegistry();
        tasks.register(
                Task.of(
                        "Lookup",
                        TaskEffect.CHANGES,
                        EffectPolicy.DEFAULT,
                        ctx -> {
                            lookups.incrementAndGet();
                            return TaskResult.value("found");
                        }));
        HarnessExecutor e =
                new HarnessExecutor(new LoomLoader().load(CTK.resolve(script).toString()), new ToolRegistry(), model -> null);
        e.setTaskRegistry(tasks);
        e.setClock(clock);
        e.setRunId("ctk");
        e.setJournal(durable(journals.computeIfAbsent("ctk", k -> RunJournal.inMemory())));
        e.setChildRuns(id -> durable(journals.computeIfAbsent(id, k -> RunJournal.inMemory())));
        e.setChildScheduler(new ControlledChildScheduler());
        e.initialize();
        return e;
    }

    private static RunJournal durable(RunJournal base) {
        return new RunJournal() {
            @Override
            public java.util.Optional<Entry> get(String stepId) {
                return base.get(stepId);
            }

            @Override
            public void put(String stepId, Entry entry) {
                base.put(stepId, entry);
            }

            @Override
            public Map<String, Entry> all() {
                return base.all();
            }

            @Override
            public boolean isDurable() {
                return true;
            }
        };
    }

    @Test
    void childStartWaitCompletesAndTheChildHasARunOfItsOwn() throws Exception {
        HarnessExecutor e = executor("child_start_wait.loom");

        e.executeWorkflow("main", Map.of());

        assertThat(((ChildHandle) e.getContext().getVariable("work")).status()).isEqualTo(ChildStatus.COMPLETED);
        assertThat(lookups).hasValue(1);
        assertThat(journals).as("the parent's journal and the child's own").hasSize(2);
    }

    @Test
    void childProgressIsReportedByTheChildAndReadByTheParentAndSurvivesARunAgain() throws Exception {
        HarnessExecutor e = executor("child_progress.loom");
        e.executeWorkflow("main", Map.of());
        long reports = journals.entrySet().stream().filter(en -> !en.getKey().equals("ctk"))
                .flatMap(en -> en.getValue().all().values().stream()).filter(en -> "progress".equals(en.kind())).count();

        HarnessExecutor again = executor("child_progress.loom"); // the same journals: a restart
        again.executeWorkflow("main", Map.of());

        assertThat(reports).isEqualTo(2);
        assertThat(((ChildHandle) again.getContext().getVariable("work")).fact("progress"))
                .asString().contains("writing");
        assertThat(lookups).as("the effect happened once across the run again").hasValue(1);
    }

    @Test
    void childCancelledBeforeItRanNeverRunsAndIsCancelledWithItsReason() throws Exception {
        HarnessExecutor e = executor("child_cancel.loom");

        e.executeWorkflow("main", Map.of());

        ChildHandle work = (ChildHandle) e.getContext().getVariable("work");
        assertThat(work.status()).isEqualTo(ChildStatus.CANCELLED);
        assertThat(work.fact("reason")).isEqualTo("not needed");
        assertThat(lookups).hasValue(0);
    }

    @Test
    void childRestartFindsTheSameChildAfterTheWakeTimeAndRepeatsNoEffect() throws Exception {
        assertThatThrownBy(() -> executor("child_restart.loom").executeWorkflow("main", Map.of()))
                .isInstanceOf(RunSuspended.class);
        assertThat(lookups).hasValue(0);

        clock.advance(Duration.ofHours(2));
        executor("child_restart.loom").executeWorkflow("main", Map.of());

        assertThat(lookups).as("the effect after the sleep happened once across the restart").hasValue(1);
        assertThat(journals).hasSize(2);
    }
}
