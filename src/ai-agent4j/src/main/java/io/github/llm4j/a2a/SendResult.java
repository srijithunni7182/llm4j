package io.github.llm4j.a2a;

import java.util.Objects;

/** The answer to a send: a task, or a plain message with no task. */
public sealed interface SendResult {

    /** The remote created a task. */
    record TaskResult(RemoteTask task) implements SendResult {
        /** Requires the task. */
        public TaskResult {
            Objects.requireNonNull(task, "task");
        }
    }

    /** The remote answered directly with a message and made no task. */
    record MessageResult(A2AMessage message) implements SendResult {
        /** Requires the message. */
        public MessageResult {
            Objects.requireNonNull(message, "message");
        }
    }
}
