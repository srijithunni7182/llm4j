package io.github.llm4j.a2a;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * What the {@link A2AHandler} maps requests onto: something that runs tasks. A Loom workflow host is
 * one, an agent wrapper another, and a consumer may write their own. Every method takes the caller's
 * scope key and must show a task only to the scope that created it; the handler checks again.
 * Methods must not block on the work itself: a task runs elsewhere.
 */
public interface TaskRunner {

    /**
     * A task and who owns it.
     *
     * @param scope the scope key that created it
     * @param task the task as the caller may see it
     * @param updated when its status last changed
     */
    record OwnedTask(String scope, RemoteTask task, Instant updated) {
        /** Requires every component. */
        public OwnedTask {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(task, "task");
            Objects.requireNonNull(updated, "updated");
        }
    }

    /**
     * Starts a task for a message. Never called for a message that names a task.
     *
     * @param contextId the context the caller proposed, or null
     * @throws A2AException.InvalidParams if the context cannot be honoured; it is never replaced
     */
    OwnedTask start(String scope, A2AMessage message, String contextId);

    /** A task of this scope, or empty (also for another scope's task). */
    Optional<OwnedTask> get(String scope, String taskId);

    /** Every task of this scope; the handler filters, sorts and pages. */
    List<OwnedTask> list(String scope);

    /** Delivers a message to a task that is waiting on the caller. */
    OwnedTask answer(String scope, String taskId, A2AMessage message);

    /** Cancels a task; the handler has already checked it is not finished. */
    OwnedTask cancel(String scope, String taskId);

    /**
     * Completes when the task finishes or pauses for the caller. The host decides how long to wait
     * and what to do on a timeout; the handler holds no thread while this is pending.
     */
    CompletionStage<OwnedTask> settled(String scope, String taskId);
}
