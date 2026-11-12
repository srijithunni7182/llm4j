package io.github.llm4j.loom.children;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.task.TaskRegistry;
import io.github.llm4j.agent.task.TaskResult;
import io.github.llm4j.agent.tool.EffectPolicy;
import io.github.llm4j.loom.eval.MockModels;
import io.github.llm4j.loom.execution.ControlledChildScheduler;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.runtime.RunJournal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/**
 * Guard for the rule "an effect is never repeated": a steer or a wrap-up is recorded as an intent
 * before it is delivered, so a run that dies between the delivery and the final record does not
 * send it again when it resumes.
 */
class ChildIntentTest {

    /** What a dead process leaves: the write that would have recorded the outcome never happens. */
    private static final class Crash extends Error {
        Crash() {
            super("simulated crash", null, false, false);
        }
    }

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
    private final Map<String, RunJournal> stores = new ConcurrentHashMap<>();
    private final List<String> audits = Collections.synchronizedList(new ArrayList<>());
    private HarnessExecutor parent;

    /** The parent's journal, dying on the second write of {@code crashKey}. */
    private RunJournal parentJournal(String crashKey) {
        RunJournal base = stores.computeIfAbsent("p", k -> RunJournal.inMemory());
        int[] writes = {0};
        return new RunJournal() {
            @Override
            public Optional<Entry> get(String stepId) {
                return base.get(stepId);
            }

            @Override
            public void put(String stepId, Entry entry) {
                if (stepId.equals(crashKey) && ++writes[0] == 2) throw new Crash();
                base.put(stepId, entry);
            }

            @Override
            public Map<String, Entry> all() {
                return base.all();
            }
        };
    }

    private HarnessExecutor executor(String crashKey) {
        TaskRegistry tasks = new TaskRegistry();
        tasks.register(Task.of("Fine", TaskEffect.NONE, EffectPolicy.DEFAULT, ctx -> TaskResult.value("ok")));
        tasks.register(
                Task.of(
                        "Later",
                        TaskEffect.NONE,
                        EffectPolicy.DEFAULT,
                        ctx -> {
                            clock.advance(Duration.ofSeconds(120));
                            parent.tickChildren();
                            return TaskResult.value("later");
                        }));
        parent =
                new HarnessExecutor(
                        StartParseTest.parse(
                                """
                                agent Looper { model: "m" system: "s" steerable: true streaming: true }
                                workflow Leaf(x) { run Fine() -> r  return r }
                                workflow Main(q) {
                                    start Leaf(x = q) budget { tokens: 1 } -> work
                                    steer work Looper "focus"
                                    supervise work { when elapsed > 1m { wrap_up } }
                                    run Later() -> later
                                    wait work timeout 1m
                                }
                                """),
                        new ToolRegistry(),
                        new MockModels());
        parent.setTaskRegistry(tasks);
        parent.setClock(clock);
        parent.setSuperviseTick(Duration.ZERO);
        parent.setRunId("p");
        parent.setJournal(parentJournal(crashKey));
        parent.setChildRuns(id -> stores.computeIfAbsent(id, k -> RunJournal.inMemory()));
        parent.setChildScheduler(new ControlledChildScheduler());
        parent.setAuditLogger(
                new io.github.llm4j.audit.AuditLogger() {
                    @Override
                    public void logAgentDecision(io.github.llm4j.audit.AuditEvent event) {}

                    @Override
                    public void logToolExecution(String s, String t, String i, String o, Instant ts) {}

                    @Override
                    public void logPromptUsage(String s, String p, String v, Instant ts) {}

                    @Override
                    public void logConversationEvent(String s, String u, String type, Map<String, Object> data) {
                        audits.add(type);
                    }
                });
        parent.initialize();
        return parent;
    }

    @Test
    void aSteerStatementIsRecordedBeforeItIsDeliveredSoAResumeDoesNotSendItAgain() {
        assertThatThrownBy(() -> executor("Main/s1").executeWorkflow("Main", Map.of("q", "x"))).isInstanceOf(Crash.class);

        var left = stores.get("p").get("Main/s1").orElseThrow();
        assertThat(left.kind()).isEqualTo("child-steer");
        assertThat(left.value()).asString().contains("delivered=-1");

        audits.clear();
        executor(null).executeWorkflow("Main", Map.of("q", "x"));
        assertThat(audits).as("the resumed run did not deliver the steer again").doesNotContain("child_steer");
    }

    @Test
    void aSuperviseRuleThatActedIsRecordedBeforeItActsSoAResumeDoesNotActAgain() {
        assertThatThrownBy(() -> executor("Main/s2#fire.0").executeWorkflow("Main", Map.of("q", "x"))).isInstanceOf(Crash.class);

        var left = stores.get("p").get("Main/s2#fire.0").orElseThrow();
        assertThat(left.value()).asString().contains("outcome=intent").contains("action=WRAP_UP");

        audits.clear();
        executor(null).executeWorkflow("Main", Map.of("q", "x"));
        assertThat(stores.get("p").all().keySet().stream().filter(k -> k.contains("#fire.")).count())
                .as("the rule did not fire a second time")
                .isEqualTo(1);
        assertThat(audits).doesNotContain("child_supervise");
    }
}
