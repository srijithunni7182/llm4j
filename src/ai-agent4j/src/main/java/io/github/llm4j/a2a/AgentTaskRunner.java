package io.github.llm4j.a2a;

import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.RunHandle;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Adapts plain {@link ReActAgent}s to a {@link TaskRunner}, for someone who uses ai-agent4j without
 * Loom. The consumer supplies a function that builds the agent <em>for each task</em> (given the
 * caller's scope and the context id, so each caller can have its own memory, budget and masking), a
 * {@link TaskStore} and an {@link Executor}; llm4j starts no threads. A task is one {@code
 * agent.run(...)}.
 *
 * <p>Limits of this runner, which are the reason to prefer a Loom workflow when they matter:
 *
 * <ul>
 *   <li>There is no {@code INPUT_REQUIRED}: a plain agent has no way to ask, so a task runs to
 *       completion or fails.
 *   <li>A crash loses running tasks unless the store is durable and the consumer restarts or fails
 *       unfinished tasks at startup. There is no journal.
 *   <li>Cancel works through the agent's run handle, which interrupts at the next step.
 *   <li>The limits are what the consumer configured on each agent. There is no audit for Java.
 * </ul>
 *
 * <p>If you need questions to the caller, crash-safe tasks, cancel and the audit, expose a Loom
 * workflow instead. The runner refuses to start unless the consumer confirms with {@link
 * Builder#confirmAgentsHaveLimits()} that the agents carry limits (a budget, a maximum number of
 * iterations).
 */
public final class AgentTaskRunner implements TaskRunner {

    /** Builds the agent for one task. */
    @FunctionalInterface
    public interface AgentFactory {
        /**
         * Creates an agent for a task. Do not share one agent between scopes.
         *
         * @param scope the caller's scope key
         * @param contextId the task's context id
         */
        ReActAgent agentFor(String scope, String contextId);
    }

    private static final String LOOM_ADVICE =
            "If you need questions to the caller, crash-safe tasks, cancel and the audit, expose a Loom workflow instead.";

    private final AgentFactory factory;
    private final TaskStore store;
    private final Executor executor;
    private final Clock clock;
    private final Map<String, RunHandle> handles = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<OwnedTask>> settled = new ConcurrentHashMap<>();

    private AgentTaskRunner(Builder b) {
        this.factory = b.factory;
        this.store = b.store;
        this.executor = b.executor;
        this.clock = b.clock;
    }

    /**
     * Starts a builder.
     *
     * @param factory builds the agent for each task
     * @param store keeps tasks between requests
     * @param executor runs the tasks; llm4j starts no threads
     */
    public static Builder builder(AgentFactory factory, TaskStore store, Executor executor) {
        return new Builder(factory, store, executor);
    }

    /** Builds an {@link AgentTaskRunner}. */
    public static final class Builder {
        private final AgentFactory factory;
        private final TaskStore store;
        private final Executor executor;
        private Clock clock = Clock.systemUTC();
        private boolean limitsConfirmed;

        private Builder(AgentFactory factory, TaskStore store, Executor executor) {
            this.factory = Objects.requireNonNull(factory, "factory");
            this.store = Objects.requireNonNull(store, "store");
            this.executor = Objects.requireNonNull(executor, "executor");
        }

        /**
         * Confirms that every agent the factory builds has limits (a budget and a maximum number of
         * iterations). Callers outside can drive the cost of what this runner exposes.
         */
        public Builder confirmAgentsHaveLimits() {
            this.limitsConfirmed = true;
            return this;
        }

        /** Sets the clock; for tests. */
        public Builder clock(Clock value) {
            this.clock = Objects.requireNonNull(value, "clock");
            return this;
        }

        /**
         * Builds the runner.
         *
         * @throws IllegalStateException unless {@link #confirmAgentsHaveLimits()} was called
         */
        public AgentTaskRunner build() {
            if (!limitsConfirmed) {
                throw new IllegalStateException(
                        "an agent exposed over A2A can be driven by outsiders, so its limits (a budget, a maximum number of iterations) must be set; call confirmAgentsHaveLimits() once they are. "
                                + LOOM_ADVICE);
            }
            return new AgentTaskRunner(this);
        }
    }

    @Override
    public OwnedTask start(String scope, A2AMessage message, String contextId) {
        for (Part part : message.parts()) {
            if (part instanceof Part.FileOffered) {
                throw new A2AException.ContentTypeNotSupported("this agent accepts text only");
            }
        }
        String text = message.text();
        if (text.isBlank()) {
            throw new A2AException.InvalidParams("the message has no text to work on");
        }
        String id = "task-" + UUID.randomUUID();
        String context = contextId == null ? "ctx-" + UUID.randomUUID() : contextId;
        OwnedTask working = owned(scope, new RemoteTask(id, context, TaskState.WORKING, null, List.of(), List.of(message)));
        store.save(working);
        RunHandle handle = new RunHandle();
        handles.put(id, handle);
        settled.put(id, new CompletableFuture<>());
        try {
            executor.execute(() -> run(scope, id, context, text, handle));
        } catch (RejectedExecutionException e) {
            finish(scope, id, TaskState.FAILED, "the agent could not be started: no capacity", List.of(), false);
            return store.find(scope, id).orElse(working);
        }
        return working;
    }

    private void run(String scope, String id, String context, String text, RunHandle handle) {
        try {
            AgentResult result = factory.agentFor(scope, context).run(text, handle);
            if (result.isCancelled()) {
                finish(scope, id, TaskState.CANCELED, null, List.of(), true);
            } else if (result.isCompleted()) {
                String answer = result.getFinalAnswer() == null ? "" : result.getFinalAnswer();
                finish(scope, id, TaskState.COMPLETED, null, List.of(new Artifact("answer", "Answer", List.of(new Part.Text(answer)))), true);
            } else {
                finish(scope, id, TaskState.FAILED, "the agent did not finish", List.of(), true);
            }
        } catch (RuntimeException e) {
            // the message is not shown to the caller: it may name internal systems
            finish(scope, id, TaskState.FAILED, "the agent failed", List.of(), true);
        } finally {
            handles.remove(id);
        }
    }

    private void finish(String scope, String id, TaskState state, String note, List<Artifact> artifacts, boolean keepCancel) {
        Optional<OwnedTask> current = store.find(scope, id);
        if (keepCancel && current.isPresent() && current.get().task().state() == TaskState.CANCELED) {
            complete(scope, id, current.get());
            return;
        }
        String context = current.map(c -> c.task().contextId()).orElse("");
        List<A2AMessage> history = current.map(c -> c.task().history()).orElse(List.of());
        A2AMessage status = note == null ? null : A2AMessage.agent("status-" + id, List.of(new Part.Text(note)));
        OwnedTask next = owned(scope, new RemoteTask(id, context, state, status, artifacts, history));
        store.save(next);
        complete(scope, id, next);
    }

    private void complete(String scope, String id, OwnedTask task) {
        CompletableFuture<OwnedTask> future = settled.get(id);
        if (future != null) {
            future.complete(task);
        }
    }

    private OwnedTask owned(String scope, RemoteTask task) {
        return new OwnedTask(scope, task, clock.instant());
    }

    @Override
    public Optional<OwnedTask> get(String scope, String taskId) {
        return store.find(scope, taskId);
    }

    @Override
    public List<OwnedTask> list(String scope) {
        return store.list(scope);
    }

    @Override
    public OwnedTask answer(String scope, String taskId, A2AMessage message) {
        throw new A2AException.UnsupportedOperation("this agent cannot ask questions, so it takes no further messages for a task. " + LOOM_ADVICE);
    }

    @Override
    public OwnedTask cancel(String scope, String taskId) {
        OwnedTask current = store.find(scope, taskId).orElseThrow(() -> new A2AException.TaskNotFound(taskId));
        RunHandle handle = handles.get(taskId);
        if (handle != null) {
            handle.cancel("canceled by the caller");
        }
        OwnedTask canceled = owned(scope, RemoteTask.of(taskId, current.task().contextId(), TaskState.CANCELED));
        store.save(canceled);
        complete(scope, taskId, canceled);
        return canceled;
    }

    @Override
    public CompletionStage<OwnedTask> settled(String scope, String taskId) {
        Optional<OwnedTask> current = store.find(scope, taskId);
        if (current.isPresent() && current.get().task().isTerminal()) {
            return CompletableFuture.completedFuture(current.get());
        }
        return settled.computeIfAbsent(taskId, k -> new CompletableFuture<>());
    }
}
