package io.github.llm4j.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class AgentTaskRunnerTest {

    /** Answers with a fixed text, or throws. */
    private static final class Scripted implements LLMClient {
        final String answer;
        final RuntimeException failure;

        Scripted(String answer, RuntimeException failure) {
            this.answer = answer;
            this.failure = failure;
        }

        @Override
        public LLMResponse chat(LLMRequest request) {
            if (failure != null) throw failure;
            return LLMResponse.builder()
                    .content(answer)
                    .model("m")
                    .tokenUsage(1, 1, 2)
                    .finishReason("stop")
                    .build();
        }

        @Override
        public Stream<LLMResponse> chatStream(LLMRequest request) {
            return Stream.of(chat(request));
        }

        @Override
        public boolean supportsToolCalling() {
            return true;
        }
    }

    /** A store the consumer would supply; here, in memory. */
    private static final class MemoryStore implements TaskStore {
        final Map<String, TaskRunner.OwnedTask> tasks = new LinkedHashMap<>();

        @Override
        public synchronized void save(TaskRunner.OwnedTask task) {
            tasks.put(task.task().id(), task);
        }

        @Override
        public synchronized Optional<TaskRunner.OwnedTask> find(String scope, String taskId) {
            return Optional.ofNullable(tasks.get(taskId)).filter(t -> t.scope().equals(scope));
        }

        @Override
        public synchronized List<TaskRunner.OwnedTask> list(String scope) {
            return tasks.values().stream().filter(t -> t.scope().equals(scope)).toList();
        }
    }

    /** Runs nothing until the test says so: llm4j starts no threads of its own. */
    private static final class ManualExecutor implements Executor {
        final Deque<Runnable> queue = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            queue.add(command);
        }

        void runAll() {
            while (!queue.isEmpty()) queue.poll().run();
        }
    }

    private final MemoryStore store = new MemoryStore();
    private final ManualExecutor executor = new ManualExecutor();
    private final List<String> asked = new ArrayList<>();
    private LLMClient model = new Scripted("The total is 42.", null);

    private AgentTaskRunner runner() {
        return AgentTaskRunner.builder(
                        (scope, context) -> {
                            asked.add(scope + "/" + context);
                            return ReActAgent.builder().llmClient(model).maxIterations(3).build();
                        },
                        store,
                        executor)
                .confirmAgentsHaveLimits()
                .clock(Clock.fixed(Instant.parse("2026-11-07T10:00:00Z"), ZoneOffset.UTC))
                .build();
    }

    private static A2AMessage ask(String text) {
        return A2AMessage.user("m-1", List.of(new Part.Text(text)));
    }

    @Test
    void refusesToStartUnlessTheConsumerConfirmsTheAgentsHaveLimitsAndPointsToLoom() {
        var builder = AgentTaskRunner.builder((s, c) -> null, store, executor);
        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("limits")
                .hasMessageContaining("Loom");
    }

    @Test
    void startingATaskRunsNothingByItselfUntilTheExecutorDoes() {
        var runner = runner();
        var started = runner.start("alice", ask("Total?"), null);
        assertThat(started.task().state()).isEqualTo(TaskState.WORKING);
        assertThat(asked).isEmpty();
        assertThat(store.tasks).containsKey(started.task().id());
        executor.runAll();
        assertThat(asked).hasSize(1);
    }

    @Test
    void aTaskRunsOneAgentRunAndCompletesWithTheAnswerAsAnArtifact() {
        var runner = runner();
        var id = runner.start("alice", ask("Total?"), null).task().id();
        var settled = runner.settled("alice", id).toCompletableFuture();
        assertThat(settled).isNotDone();
        executor.runAll();
        var done = runner.get("alice", id).orElseThrow().task();
        assertThat(done.state()).isEqualTo(TaskState.COMPLETED);
        assertThat(((Part.Text) done.artifacts().get(0).parts().get(0)).text())
                .isEqualTo("The total is 42.");
        assertThat(settled).isDone();
    }

    @Test
    void theAgentFunctionIsCalledPerTaskWithTheScopeAndContextSoScopesNeverShareAnAgent() {
        var runner = runner();
        runner.start("alice", ask("a"), "ctx-a");
        runner.start("bob", ask("b"), "ctx-b");
        runner.start("alice", ask("c"), null);
        executor.runAll();
        assertThat(asked).hasSize(3);
        assertThat(asked).contains("alice/ctx-a", "bob/ctx-b");
    }

    @Test
    void aTasksAreInvisibleToAnotherScope() {
        var runner = runner();
        var id = runner.start("alice", ask("a"), null).task().id();
        assertThat(runner.get("bob", id)).isEmpty();
        assertThat(runner.list("bob")).isEmpty();
        assertThat(runner.list("alice")).hasSize(1);
    }

    @Test
    void aPlainAgentNeverAsksSoAMessageToItsTaskIsRefusedAndPointsToLoom() {
        var runner = runner();
        var id = runner.start("alice", ask("a"), null).task().id();
        assertThatThrownBy(() -> runner.answer("alice", id, ask("more")))
                .isInstanceOf(A2AException.UnsupportedOperation.class)
                .hasMessageContaining("Loom");
    }

    @Test
    void aFailingAgentFailsTheTaskWithoutLeakingItsMessage() {
        model = new Scripted(null, new IllegalStateException("jdbc:secret-host/password"));
        var runner = runner();
        var id = runner.start("alice", ask("a"), null).task().id();
        executor.runAll();
        var task = runner.get("alice", id).orElseThrow().task();
        assertThat(task.state()).isEqualTo(TaskState.FAILED);
        assertThat(String.valueOf(task)).doesNotContain("secret-host");
    }

    @Test
    void cancelInterruptsTheRunAndTheFinalResultNeverOverwritesIt() {
        var runner = runner();
        var id = runner.start("alice", ask("a"), null).task().id();
        var canceled = runner.cancel("alice", id);
        assertThat(canceled.task().state()).isEqualTo(TaskState.CANCELED);
        executor.runAll();
        assertThat(runner.get("alice", id).orElseThrow().task().state())
                .isEqualTo(TaskState.CANCELED);
    }

    @Test
    void aCrashLeavesAWorkingTaskInTheStoreTheConsumerCanCleanUp() {
        var runner = runner();
        var id = runner.start("alice", ask("a"), null).task().id();
        // the process dies: the executor never runs the task; a restarted runner over the same
        // store sees it
        var restarted = runner();
        assertThat(restarted.get("alice", id).orElseThrow().task().state())
                .isEqualTo(TaskState.WORKING);
        assertThat(restarted.list("alice"))
                .extracting(t -> t.task().state())
                .containsExactly(TaskState.WORKING);
    }

    @Test
    void anExecutorThatRefusesFailsTheTaskInsteadOfLosingIt() {
        var runner =
                AgentTaskRunner.builder(
                                (s, c) -> null,
                                store,
                                command -> {
                                    throw new RejectedExecutionException("full");
                                })
                        .confirmAgentsHaveLimits()
                        .build();
        var started = runner.start("alice", ask("a"), null);
        assertThat(runner.get("alice", started.task().id()).orElseThrow().task().state())
                .isEqualTo(TaskState.FAILED);
    }

    @Test
    void onlyTextIsAcceptedAsInput() {
        var runner = runner();
        var withFile =
                A2AMessage.user(
                        "m",
                        List.of(
                                Part.FileOffered.link(
                                        "https://f.example.com/a.pdf", "a", "application/pdf")));
        assertThatThrownBy(() -> runner.start("alice", withFile, null))
                .isInstanceOf(A2AException.ContentTypeNotSupported.class);
    }

    @Test
    void theRunnerWorksBehindTheHandlerForAScope() {
        var runner = runner();
        var handler = new A2AHandler(scope -> runner);
        var stage =
                handler.handle(
                                new CallerIdentity("alice"),
                                new A2ARequest.Send(ask("Total?"), false, null, null))
                        .toCompletableFuture();
        assertThat(stage).isNotDone();
        executor.runAll();
        assertThat(((A2AResponse.TaskResponse) stage.join()).task().state())
                .isEqualTo(TaskState.COMPLETED);
    }
}
