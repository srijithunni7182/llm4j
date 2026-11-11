package io.github.llm4j.a2a;

import java.time.Instant;
import java.util.Objects;

/**
 * A request to the handler, in the version-free model. The consumer's web layer builds it ({@link
 * A2AJsonRpc10#parseRequest} does for A2A 1.0 over JSON-RPC).
 */
public sealed interface A2ARequest {

    /**
     * Send a message: a new task, or an answer to a waiting one.
     *
     * @param message the message
     * @param returnImmediately whether the caller does not want to be held until the task finishes
     *     or pauses
     * @param historyLength how many history messages to return, or null
     * @param tenant an endpoint tenant, passed through and never interpreted, or null
     */
    record Send(A2AMessage message, boolean returnImmediately, Integer historyLength, String tenant)
            implements A2ARequest {
        /** Requires the message. */
        public Send {
            Objects.requireNonNull(message, "message");
        }
    }

    /**
     * Read one task.
     *
     * @param taskId the task
     * @param historyLength how many history messages to return; null or 0 means none
     */
    record GetTask(String taskId, Integer historyLength) implements A2ARequest {
        /** Requires the task id. */
        public GetTask {
            Objects.requireNonNull(taskId, "taskId");
        }
    }

    /**
     * List the caller's tasks.
     *
     * @param contextId only this context, or null
     * @param status only this state, or null
     * @param pageSize 1 to 100; null means 50
     * @param pageToken the token from the previous page, or null
     * @param historyLength how many history messages per task; null or 0 means none
     * @param statusTimestampAfter only tasks updated at or after this, or null
     * @param includeArtifacts whether to include artifacts
     */
    record ListTasks(
            String contextId,
            TaskState status,
            Integer pageSize,
            String pageToken,
            Integer historyLength,
            Instant statusTimestampAfter,
            boolean includeArtifacts)
            implements A2ARequest {}

    /**
     * Cancel a task; idempotent.
     *
     * @param taskId the task
     */
    record CancelTask(String taskId) implements A2ARequest {
        /** Requires the task id. */
        public CancelTask {
            Objects.requireNonNull(taskId, "taskId");
        }
    }
}
