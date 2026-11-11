package io.github.llm4j.a2a;

import java.util.List;
import java.util.Objects;

/**
 * What the handler answers. A failure is an {@link A2AException} that completes the stage
 * exceptionally.
 */
public sealed interface A2AResponse {

    /** A task. */
    record TaskResponse(RemoteTask task) implements A2AResponse {
        /** Requires the task. */
        public TaskResponse {
            Objects.requireNonNull(task, "task");
        }
    }

    /** A plain message and no task. */
    record MessageResponse(A2AMessage message) implements A2AResponse {
        /** Requires the message. */
        public MessageResponse {
            Objects.requireNonNull(message, "message");
        }
    }

    /**
     * One page of tasks.
     *
     * @param tasks the tasks, newest first
     * @param nextPageToken the token for the next page; empty on the last page, never null
     * @param pageSize the page size used
     * @param totalSize how many tasks match in all
     */
    record TaskPage(List<RemoteTask> tasks, String nextPageToken, int pageSize, int totalSize)
            implements A2AResponse {
        /** Copies the list; the token is never null. */
        public TaskPage {
            tasks = List.copyOf(tasks);
            nextPageToken = nextPageToken == null ? "" : nextPageToken;
        }
    }
}
