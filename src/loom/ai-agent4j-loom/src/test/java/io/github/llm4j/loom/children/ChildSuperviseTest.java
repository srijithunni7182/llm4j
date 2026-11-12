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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/** Phase 33e: {@code supervise} on a handle, on a virtual clock, ticked by the test (D17, D26). */
class ChildSuperviseTest {

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
    private HarnessExecutor parent;

    private HarnessExecutor executor(String source) {
        TaskRegistry tasks = new TaskRegistry();
        tasks.register(
                Task.of("Fine", TaskEffect.NONE, EffectPolicy.DEFAULT, ctx -> TaskResult.value("ok")));
        // "Later(minutes)": the virtual clock moves and the supervisor takes one look
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
        parent.setSuperviseTick(Duration.ZERO); // the test is the tick
        parent.setRunId("p");
        parent.setJournal(journals.computeIfAbsent("p", k -> RunJournal.inMemory()));
        parent.setChildRuns(id -> journals.computeIfAbsent(id, k -> RunJournal.inMemory()));
        parent.setChildScheduler(new ControlledChildScheduler());
        parent.initialize();
        return parent;
    }

    private static final String CHILD = "workflow Child(x) { run Fine() -> r  return r }\n";

    private long fires() {
        return journals.get("p").all().keySet().stream().filter(k -> k.contains("#fire.")).count();
    }

    @Test
    void aRuleOnRunningTimeCancelsAChildThatTookTooLong() {
        HarnessExecutor e =
                executor(
                        CHILD
                                + """
                                workflow Main(q) {
                                    start Child(x = q) budget { tokens: 1 } -> work
                                    supervise work { when elapsed > 5m { cancel use partial } }
                                    run Later(minutes = 6) -> later
                                    wait work timeout 1m
                                    set seen = work.status
                                    set why = work.reason
                                }
                                """);

        e.executeWorkflow("Main", Map.of("q", "x"));

        assertThat(e.getContext().getVariable("seen")).isEqualTo("cancelled");
        assertThat(String.valueOf(e.getContext().getVariable("why"))).contains("supervise rule 0");
        assertThat(fires()).isEqualTo(1);
    }

    @Test
    void aRuleThatIsNotYetTrueDoesNothing() {
        HarnessExecutor e =
                executor(
                        CHILD
                                + """
                                workflow Main(q) {
                                    start Child(x = q) budget { tokens: 1 } -> work
                                    supervise work { when elapsed > 5m { cancel } }
                                    run Later(minutes = 4) -> later
                                    wait work timeout 1m -> out
                                    set seen = work.status
                                }
                                """);

        e.executeWorkflow("Main", Map.of("q", "x"));

        assertThat(e.getContext().getVariable("seen")).isEqualTo("completed");
        assertThat(fires()).isZero();
    }

    @Test
    void aStallIsSeenByTimeWithNoProgressAndASteerWithNoRunningAgentIsRecordedNotDelivered() {
        HarnessExecutor e =
                executor(
                        CHILD
                                + """
                                workflow Main(q) {
                                    start Child(x = q) budget { tokens: 1 } -> work
                                    supervise work { when idle > 3m { steer "You seem stuck." once } }
                                    run Later(minutes = 4) -> later
                                    wait work timeout 1m -> out
                                }
                                """);

        e.executeWorkflow("Main", Map.of("q", "x"));

        var entry =
                journals.get("p").all().entrySet().stream()
                        .filter(k -> k.getKey().contains("#fire."))
                        .findFirst()
                        .orElseThrow()
                        .getValue();
        assertThat(entry.kind()).isEqualTo("supervise");
        assertThat(entry.value()).asString().contains("action=STEER").contains("not delivered");
    }

    @Test
    void aChildThatSettledIsNoLongerWatched() {
        HarnessExecutor e =
                executor(
                        CHILD
                                + """
                                workflow Main(q) {
                                    start Child(x = q) budget { tokens: 1 } -> work
                                    supervise work { when elapsed > 1m { cancel } }
                                    wait work timeout 1m -> out
                                    run Later(minutes = 10) -> later
                                    set seen = work.status
                                }
                                """);

        e.executeWorkflow("Main", Map.of("q", "x"));

        assertThat(e.getContext().getVariable("seen")).isEqualTo("completed");
        assertThat(fires()).isZero();
    }
}
