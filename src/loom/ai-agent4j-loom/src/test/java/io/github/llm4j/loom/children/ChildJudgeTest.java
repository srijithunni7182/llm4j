package io.github.llm4j.loom.children;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.task.TaskRegistry;
import io.github.llm4j.agent.task.TaskResult;
import io.github.llm4j.agent.tool.EffectPolicy;
import io.github.llm4j.loom.execution.ChildScheduler;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Phase 33g: judged assertions (D25 to D27; guards G11, G12 and G13 at the engine). */
@Tag("slow") // real threads: the child is held inside a task while the supervisor looks at it
class ChildJudgeTest {

    private static final class MovingClock extends java.time.Clock {
        private volatile Instant now = Instant.parse("2026-01-01T00:00:00Z");

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
    private final List<String> judgeRequests = new ArrayList<>();
    private final List<String> answers = new ArrayList<>(List.of("fail", "pass", "pass"));
    /** Counted down by the parent once the supervise block is attached: the child makes no progress before it. */
    private final CountDownLatch attached = new CountDownLatch(1);

    private final CountDownLatch inBlocked = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private HarnessExecutor parent;

    private LLMClient judge(String model) {
        return new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                synchronized (judgeRequests) {
                    judgeRequests.add(
                            request.getMessages().stream().map(m -> m.getContent()).reduce("", (a, b) -> a + "\n" + b));
                    String answer = answers.isEmpty() ? "pass" : answers.remove(0);
                    return LLMResponse.builder().content(answer).model(model).tokenUsage(10, 1, 11).build();
                }
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                return Stream.of(chat(request));
            }
        };
    }

    private HarnessExecutor executor(String source) {
        TaskRegistry tasks = new TaskRegistry();
        tasks.register(
                Task.of(
                        "Gate",
                        TaskEffect.NONE,
                        EffectPolicy.DEFAULT,
                        ctx -> {
                            try {
                                attached.await(10, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return TaskResult.value("open");
                        }));
        tasks.register(Task.of("Search", TaskEffect.NONE, EffectPolicy.DEFAULT, ctx -> TaskResult.value("found")));
        tasks.register(
                Task.of(
                        "Blocked",
                        TaskEffect.NONE,
                        EffectPolicy.DEFAULT,
                        ctx -> {
                            inBlocked.countDown();
                            try {
                                release.await(10, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return TaskResult.value("unblocked");
                        }));
        tasks.register(
                Task.of(
                        "Pump",
                        TaskEffect.NONE,
                        EffectPolicy.DEFAULT,
                        ctx -> {
                            attached.countDown();
                            try {
                                inBlocked.await(10, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return TaskResult.value("pumped");
                        }));
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
        tasks.register(
                Task.of(
                        "Let",
                        TaskEffect.NONE,
                        EffectPolicy.DEFAULT,
                        ctx -> {
                            release.countDown();
                            return TaskResult.value("let");
                        }));
        parent = new HarnessExecutor(StartParseTest.parse(source), new ToolRegistry(), this::judge);
        parent.setTaskRegistry(tasks);
        parent.setClock(clock);
        parent.setSuperviseTick(Duration.ZERO);
        parent.setRunId("p");
        parent.setJournal(journals.computeIfAbsent("p", k -> RunJournal.inMemory()));
        parent.setChildRuns(id -> journals.computeIfAbsent(id, k -> RunJournal.inMemory()));
        parent.setChildScheduler(ChildScheduler.threads());
        parent.initialize();
        return parent;
    }

    private static final String SCRIPT =
            """
            evaluator J { model: "judge" }
            workflow Child(x) { run Gate() -> g  run Search() -> a  run Blocked() -> b  return b }
            workflow Main(q) {
                start Child(x = q) budget { tokens: 100000 } -> work
                supervise work budget { tokens: 5000 } {
                    assert "stays on topic" using J every 1m max 1 { steer "Go back." once }
                }
                run Pump() -> pumped
                run Later(minutes = 2) -> l1
                run Later(minutes = 2) -> l2
                run Let() -> let
                wait work timeout 10s
            }
            """;

    private List<RunJournal.Entry> entries(String infix) {
        return journals.get("p").all().entrySet().stream()
                .filter(e -> e.getKey().contains(infix))
                .map(Map.Entry::getValue)
                .toList();
    }

    @Test
    void theJudgeIsAskedOnItsCadenceSeesFactsOnlyAndItsVerdictIsJournaledWithAFixedAction() {
        executor(SCRIPT).executeWorkflow("Main", Map.of("q", "x"));

        assertThat(judgeRequests).as("asked once: max 1").hasSize(1);
        assertThat(judgeRequests.get(0)).contains("stays on topic").contains("Search x1").doesNotContain("found");
        var judgements = entries("#judge.");
        assertThat(judgements).hasSize(1);
        assertThat(judgements.get(0).value()).asString().contains("verdict=fail").contains("content=false");
        assertThat(entries("#fire.").toString())
                .as("the fixed steer was applied, and the ceiling was said once")
                .contains("action=STEER")
                .contains("judge limit reached");
    }

    @Test
    void aJudgementAlreadyJournaledIsCountedAndTheJudgeIsNotAskedAgain() {
        // what a run that was judged once before a restart leaves behind
        journals.computeIfAbsent("p", k -> RunJournal.inMemory())
                .put("Main/s1#judge.0.0", new RunJournal.Entry("judge", Map.of("rule", 0, "verdict", "fail", "steps", 1)));

        executor(SCRIPT).executeWorkflow("Main", Map.of("q", "x"));

        assertThat(judgeRequests).as("a resume never asks again").isEmpty();
    }

    @Test
    void theJudgeBudgetUsedUpStopsJudgingAndSaysSoWhileTheChildIsNotStopped() {
        answers.clear();
        executor(SCRIPT.replace("budget { tokens: 5000 }", "budget { tokens: 5 }").replace("max 1", "max 3"))
                .executeWorkflow("Main", Map.of("q", "x"));

        assertThat(entries("#fire.").toString()).contains("judge budget used up");
        assertThat(parent.getContext().getVariable("work")).isNotNull();
    }
}
