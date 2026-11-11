package io.github.llm4j.a2a;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** A hand-written runner: tasks live in memory, scripted by the test; nothing runs by itself. */
final class FakeTaskRunner implements TaskRunner {

    final List<String> calls = new ArrayList<>();
    final Map<String, TaskRunner.OwnedTask> tasks = new LinkedHashMap<>();
    final Map<String, CompletableFuture<TaskRunner.OwnedTask>> settled = new LinkedHashMap<>();
    private int counter;
    private long clock;
    TaskState startState = TaskState.WORKING;
    String rejectContext;

    /** When set, list returns a foreign scope's task too, to show the handler filters again. */
    boolean leaky;

    /** When set, a started task settles at once as COMPLETED (a blocking send then returns). */
    boolean autoSettle;

    TaskRunner.OwnedTask put(String scope, String id, String context, TaskState state) {
        var owned =
                new TaskRunner.OwnedTask(
                        scope, RemoteTask.of(id, context, state), Instant.ofEpochSecond(++clock));
        tasks.put(id, owned);
        return owned;
    }

    @Override
    public TaskRunner.OwnedTask start(String scope, A2AMessage message, String contextId) {
        calls.add("start");
        if (rejectContext != null && rejectContext.equals(contextId)) {
            throw new A2AException.InvalidParams(
                    "this agent cannot accept the context " + contextId);
        }
        var task =
                new RemoteTask(
                        "task-" + (++counter),
                        contextId == null ? "ctx-" + counter : contextId,
                        startState,
                        null,
                        List.of(),
                        List.of(message));
        var owned = new TaskRunner.OwnedTask(scope, task, Instant.ofEpochSecond(++clock));
        tasks.put(task.id(), owned);
        var future = new CompletableFuture<TaskRunner.OwnedTask>();
        settled.put(task.id(), future);
        if (autoSettle) {
            future.complete(
                    new TaskRunner.OwnedTask(
                            scope,
                            RemoteTask.of(task.id(), task.contextId(), TaskState.COMPLETED),
                            Instant.ofEpochSecond(++clock)));
        }
        return owned;
    }

    @Override
    public Optional<TaskRunner.OwnedTask> get(String scope, String taskId) {
        calls.add("get");
        return Optional.ofNullable(tasks.get(taskId));
    }

    @Override
    public List<TaskRunner.OwnedTask> list(String scope) {
        calls.add("list");
        return tasks.values().stream().filter(t -> leaky || t.scope().equals(scope)).toList();
    }

    @Override
    public TaskRunner.OwnedTask answer(String scope, String taskId, A2AMessage message) {
        calls.add("answer");
        var old = tasks.get(taskId);
        var history = new ArrayList<>(old.task().history());
        history.add(message);
        var next =
                new TaskRunner.OwnedTask(
                        scope,
                        new RemoteTask(
                                taskId,
                                old.task().contextId(),
                                TaskState.WORKING,
                                null,
                                List.of(),
                                history),
                        Instant.ofEpochSecond(++clock));
        tasks.put(taskId, next);
        return next;
    }

    @Override
    public TaskRunner.OwnedTask cancel(String scope, String taskId) {
        calls.add("cancel");
        var old = tasks.get(taskId);
        var next =
                new TaskRunner.OwnedTask(
                        scope,
                        RemoteTask.of(taskId, old.task().contextId(), TaskState.CANCELED),
                        Instant.ofEpochSecond(++clock));
        tasks.put(taskId, next);
        return next;
    }

    @Override
    public CompletionStage<TaskRunner.OwnedTask> settled(String scope, String taskId) {
        calls.add("settled");
        return settled.computeIfAbsent(taskId, k -> new CompletableFuture<>());
    }
}
