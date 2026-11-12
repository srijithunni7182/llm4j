package io.github.llm4j.loom.children;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.task.TaskRegistry;
import io.github.llm4j.agent.task.TaskResult;
import io.github.llm4j.agent.tool.EffectPolicy;
import io.github.llm4j.loom.execution.ControlledChildScheduler;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.runtime.RunJournal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Phase 33f: the four assertions in code (D24); {@code never calls} is preventive (guard G10). */
class ChildAssertionTest {

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

    private final MovingClock clock = new MovingClock();
    private final Map<String, RunJournal> journals = new ConcurrentHashMap<>();
    private final AtomicInteger danger = new AtomicInteger();
    private final AtomicInteger first = new AtomicInteger();
    private HarnessExecutor parent;

    private HarnessExecutor executor(String source) {
        TaskRegistry tasks = new TaskRegistry();
        tasks.register(
                Task.of(
                        "Danger",
                        TaskEffect.CHANGES,
                        EffectPolicy.DEFAULT,
                        ctx -> {
                            danger.incrementAndGet();
                            return TaskResult.value("done");
                        }));
        tasks.register(
                Task.of(
                        "Search",
                        TaskEffect.NONE,
                        EffectPolicy.DEFAULT,
                        ctx -> {
                            first.incrementAndGet();
                            return TaskResult.value("found");
                        }));
        tasks.register(Task.of("Plan", TaskEffect.NONE, EffectPolicy.DEFAULT, ctx -> TaskResult.value("plan")));
        tasks.register(Task.of("Write", TaskEffect.NONE, EffectPolicy.DEFAULT, ctx -> TaskResult.value("text")));
        tasks.register(
                Task.of(
                        "Later",
                        TaskEffect.NONE,
                        EffectPolicy.DEFAULT,
                        ctx -> {
                            clock.advance(Duration.ofMinutes(Long.parseLong(String.valueOf(ctx.arg("minutes")))));
                            parent.tickChildren();
                            return TaskResult.value("later");
                        }));
        parent = new HarnessExecutor(StartParseTest.parse(source), new ToolRegistry(), model -> null);
        parent.setTaskRegistry(tasks);
        parent.setClock(clock);
        parent.setSuperviseTick(Duration.ZERO);
        parent.setRunId("p");
        parent.setJournal(journals.computeIfAbsent("p", k -> RunJournal.inMemory()));
        parent.setChildRuns(id -> journals.computeIfAbsent(id, k -> RunJournal.inMemory()));
        parent.setChildScheduler(new ControlledChildScheduler());
        parent.initialize();
        return parent;
    }

    private List<RunJournal.Entry> firings() {
        return journals.get("p").all().entrySet().stream()
                .filter(e -> e.getKey().contains("#fire."))
                .map(Map.Entry::getValue)
                .toList();
    }

    @Test
    void neverCallsRefusesTheCallBeforeItRunsThenTheBlockRuns() {
        HarnessExecutor e =
                executor(
                        "workflow Child(x) { run Danger() -> d  return d }\n"
                                + """
                                workflow Main(q) {
                                    start Child(x = q) budget { tokens: 1 } -> work
                                    supervise work { assert never calls Danger { cancel } }
                                    wait work timeout 1m
                                    run Later(minutes = 0) -> later
                                    set seen = work.status
                                    set why = work.failure
                                }
                                """);

        e.executeWorkflow("Main", Map.of("q", "x"));

        assertThat(danger).as("the effect never happened").hasValue(0);
        assertThat(e.getContext().getVariable("seen")).isEqualTo("failed");
        assertThat(String.valueOf(e.getContext().getVariable("why"))).contains("Danger").contains("refused");
        assertThat(firings()).as("the violation was recorded and the block run").hasSize(1);
        assertThat(firings().get(0).value()).asString().contains("action=CANCEL");
    }

    @Test
    void aChildThatNeverMakesTheForbiddenCallIsLeftAlone() {
        HarnessExecutor e =
                executor(
                        "workflow Child(x) { run Search() -> d  return d }\n"
                                + "workflow Elsewhere(x) { run Danger() -> d }\n"
                                + """
                                workflow Main(q) {
                                    start Child(x = q) budget { tokens: 1 } -> work
                                    supervise work { assert never calls Danger { cancel } }
                                    wait work timeout 1m -> out
                                    run Later(minutes = 0) -> later
                                    set seen = work.status
                                }
                                """);

        e.executeWorkflow("Main", Map.of("q", "x"));

        assertThat(e.getContext().getVariable("seen")).isEqualTo("completed");
        assertThat(firings()).isEmpty();
    }

    @Test
    void callsAtMostFiresWhenTheChildCallsMoreOften() {
        HarnessExecutor e =
                executor(
                        "workflow Child(x) { run Search() -> a  run Search() -> b  return b }\n"
                                + """
                                workflow Main(q) {
                                    start Child(x = q) budget { tokens: 1 } -> work
                                    supervise work { assert calls Search at most 1 times { wrap_up } }
                                    wait work timeout 1m -> out
                                    run Later(minutes = 0) -> later
                                }
                                """);

        e.executeWorkflow("Main", Map.of("q", "x"));

        assertThat(firings()).hasSize(1);
        assertThat(firings().get(0).value()).asString().contains("action=WRAP_UP");
    }

    @Test
    void callsBeforeFiresWhenTheSecondComesFirst() {
        String child = "workflow Child(x) { run Write() -> w  run Plan() -> p  return p }\n";
        HarnessExecutor wrongOrder =
                executor(
                        child
                                + """
                                workflow Main(q) {
                                    start Child(x = q) budget { tokens: 1 } -> work
                                    supervise work { assert calls Plan before Write { steer "Plan first." } }
                                    wait work timeout 1m -> out
                                    run Later(minutes = 0) -> later
                                }
                                """);
        wrongOrder.executeWorkflow("Main", Map.of("q", "x"));
        assertThat(firings()).as("Write came before Plan").hasSize(1);

        journals.clear();
        HarnessExecutor rightOrder =
                executor(
                        "workflow Child(x) { run Plan() -> p  run Write() -> w  return w }\n"
                                + """
                                workflow Main(q) {
                                    start Child(x = q) budget { tokens: 1 } -> work
                                    supervise work { assert calls Plan before Write { steer "Plan first." } }
                                    wait work timeout 1m -> out
                                    run Later(minutes = 0) -> later
                                }
                                """);
        rightOrder.executeWorkflow("Main", Map.of("q", "x"));
        assertThat(firings()).isEmpty();
    }

    @Test
    void reachesWithinFiresWhenTheCheckpointIsNotReachedInTime() {
        HarnessExecutor e =
                executor(
                        "workflow Child(x) { run Search() -> a  checkpoint collected  return a }\n"
                                + """
                                workflow Main(q) {
                                    start Child(x = q) budget { tokens: 1 } -> work
                                    supervise work { assert reaches "collected" within 5m { cancel } }
                                    run Later(minutes = 6) -> later
                                    wait work timeout 1m
                                    set seen = work.status
                                }
                                """);

        e.executeWorkflow("Main", Map.of("q", "x"));

        assertThat(e.getContext().getVariable("seen")).isEqualTo("cancelled");
        assertThat(firings()).hasSize(1);
    }

    @Test
    void reachesWithinIsQuietWhenTheCheckpointWasReached() {
        HarnessExecutor e =
                executor(
                        "workflow Child(x) { run Search() -> a  checkpoint collected  return a }\n"
                                + """
                                workflow Main(q) {
                                    start Child(x = q) budget { tokens: 1 } -> work
                                    supervise work { assert reaches "collected" within 5m { cancel } }
                                    wait work timeout 1m -> out
                                    run Later(minutes = 6) -> later
                                    set seen = work.status
                                }
                                """);

        e.executeWorkflow("Main", Map.of("q", "x"));

        assertThat(e.getContext().getVariable("seen")).isEqualTo("completed");
        assertThat(firings()).isEmpty();
    }
}
