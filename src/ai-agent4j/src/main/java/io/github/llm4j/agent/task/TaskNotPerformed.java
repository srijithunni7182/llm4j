package io.github.llm4j.agent.task;

/**
 * Thrown by a task that <em>provably did nothing</em>: its input was unusable, or a rule refused it before any side effect.
 * The run can safely try the step again. Any other exception from a task that {@link TaskEffect#CHANGES changes things} means the
 * outcome is unknown (the call may have reached the other side), and the run treats it that way.
 */
public class TaskNotPerformed extends RuntimeException {

    public TaskNotPerformed(String message) {
        super(message);
    }

    public TaskNotPerformed(String message, Throwable cause) {
        super(message, cause);
    }
}
