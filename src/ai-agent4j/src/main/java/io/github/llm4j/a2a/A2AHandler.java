package io.github.llm4j.a2a;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * A framework-neutral handler for A2A requests: the consumer mounts it in any web stack, does the
 * authentication and passes in who the caller is. It maps requests onto a {@link TaskRunner}, applies
 * the specification's request rules, filters by the caller's scope and never blocks: a send that must
 * wait returns a stage that completes when the runner says the task has settled. It starts nothing
 * and holds no state.
 *
 * <p>A failure completes the stage exceptionally with an {@link A2AException}.
 */
public final class A2AHandler {

    /** The page size when a list request gives none. */
    public static final int DEFAULT_PAGE_SIZE = 50;

    /** The largest page size. */
    public static final int MAX_PAGE_SIZE = 100;

    private final TaskRunners runners;

    /**
     * Creates a handler.
     *
     * @param runners which runner serves which scope
     */
    public A2AHandler(TaskRunners runners) {
        this.runners = Objects.requireNonNull(runners, "runners");
    }

    /**
     * Handles one request.
     *
     * @param caller who is calling, as the host authenticated them; null is refused
     * @param request the request
     */
    public CompletionStage<A2AResponse> handle(CallerIdentity caller, A2ARequest request) {
        Objects.requireNonNull(request, "request");
        if (caller == null) {
            return failed(new A2AException.Refused("a request needs a caller identity; authenticate the caller and pass its scope key"));
        }
        try {
            String scope = caller.scopeKey();
            TaskRunner runner = Objects.requireNonNull(runners.forScope(scope), "no runner for this scope");
            if (request instanceof A2ARequest.Send send) {
                return send(scope, runner, send);
            }
            if (request instanceof A2ARequest.GetTask get) {
                return done(response(owned(scope, runner, get.taskId()), get.historyLength(), true));
            }
            if (request instanceof A2ARequest.CancelTask cancel) {
                return done(cancel(scope, runner, cancel.taskId()));
            }
            return done(list(scope, runner, (A2ARequest.ListTasks) request));
        } catch (A2AException e) {
            return failed(e);
        }
    }

    private CompletionStage<A2AResponse> send(String scope, TaskRunner runner, A2ARequest.Send send) {
        A2AMessage message = send.message();
        TaskRunner.OwnedTask started;
        if (message.taskId() != null) {
            TaskRunner.OwnedTask existing = owned(scope, runner, message.taskId());
            if (message.contextId() != null && !message.contextId().equals(existing.task().contextId())) {
                throw new A2AException.InvalidParams("the message names the context " + message.contextId() + " but the task is in another context; leave the context out or name the task's own");
            }
            if (existing.task().isTerminal()) {
                throw new A2AException.UnsupportedOperation("a message to the finished task " + message.taskId() + "; start a new task, naming the same context for a refinement");
            }
            started = runner.answer(scope, message.taskId(), message);
        } else {
            started = runner.start(scope, message, message.contextId());
        }
        check(scope, started);
        if (send.returnImmediately()) {
            return done(response(started, send.historyLength(), true));
        }
        String id = started.task().id();
        return runner.settled(scope, id).thenApply(settled -> {
            check(scope, settled);
            return response(settled, send.historyLength(), true);
        });
    }

    private A2AResponse cancel(String scope, TaskRunner runner, String taskId) {
        TaskRunner.OwnedTask existing = owned(scope, runner, taskId);
        if (existing.task().state() == TaskState.CANCELED) {
            return response(existing, null, true);
        }
        if (existing.task().isTerminal()) {
            throw new A2AException.TaskNotCancelable(taskId);
        }
        TaskRunner.OwnedTask canceled = runner.cancel(scope, taskId);
        check(scope, canceled);
        return response(canceled, null, true);
    }

    private A2AResponse list(String scope, TaskRunner runner, A2ARequest.ListTasks request) {
        int pageSize = request.pageSize() == null ? DEFAULT_PAGE_SIZE : request.pageSize();
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new A2AException.InvalidParams("pageSize must be between 1 and " + MAX_PAGE_SIZE);
        }
        int offset = offset(request.pageToken());
        Instant after = request.statusTimestampAfter();
        List<TaskRunner.OwnedTask> matching =
                runner.list(scope).stream()
                        .filter(t -> t.scope().equals(scope))
                        .filter(t -> request.contextId() == null || request.contextId().equals(t.task().contextId()))
                        .filter(t -> request.status() == null || request.status() == t.task().state())
                        .filter(t -> after == null || !t.updated().isBefore(after))
                        .sorted(Comparator.comparing(TaskRunner.OwnedTask::updated).reversed())
                        .toList();
        int from = Math.min(offset, matching.size());
        int end = Math.min(matching.size(), from + pageSize);
        List<RemoteTask> page =
                matching.subList(from, end).stream()
                        .map(t -> shape(t, request.historyLength(), request.includeArtifacts()))
                        .toList();
        return new A2AResponse.TaskPage(page, end < matching.size() ? String.valueOf(end) : "", pageSize, matching.size());
    }

    private static int offset(String token) {
        if (token == null || token.isEmpty()) {
            return 0;
        }
        try {
            int value = Integer.parseInt(token);
            if (value < 0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException e) {
            throw new A2AException.InvalidParams("pageToken is not one this agent gave; start again without it");
        }
    }

    /** A task of the caller's scope, or "not found": a foreign task looks exactly like a missing one. */
    private static TaskRunner.OwnedTask owned(String scope, TaskRunner runner, String taskId) {
        return runner.get(scope, taskId)
                .filter(t -> t.scope().equals(scope))
                .orElseThrow(() -> new A2AException.TaskNotFound(taskId));
    }

    private static void check(String scope, TaskRunner.OwnedTask task) {
        if (!task.scope().equals(scope)) {
            throw new A2AException("the runner returned a task of another scope; refusing to show it");
        }
    }

    private static A2AResponse response(TaskRunner.OwnedTask owned, Integer historyLength, boolean artifacts) {
        return new A2AResponse.TaskResponse(shape(owned, historyLength, artifacts));
    }

    private static RemoteTask shape(TaskRunner.OwnedTask owned, Integer historyLength, boolean artifacts) {
        RemoteTask t = owned.task();
        int keep = historyLength == null ? 0 : Math.max(0, historyLength);
        List<A2AMessage> history = t.history();
        List<A2AMessage> shown = history.subList(Math.max(0, history.size() - keep), history.size());
        return new RemoteTask(t.id(), t.contextId(), t.state(), t.statusMessage(), artifacts ? t.artifacts() : List.of(), keep == 0 ? List.of() : shown);
    }

    private static CompletionStage<A2AResponse> done(A2AResponse response) {
        return CompletableFuture.completedFuture(response);
    }

    private static CompletionStage<A2AResponse> failed(A2AException e) {
        return CompletableFuture.failedFuture(e);
    }
}
