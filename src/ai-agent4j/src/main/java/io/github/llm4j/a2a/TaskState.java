package io.github.llm4j.a2a;

/** The state of a remote task, reduced to the set llm4j's behaviour depends on. */
public enum TaskState {
    /** Accepted or running; includes the protocol's "submitted". */
    WORKING,
    /** The remote agent needs an answer from the caller. */
    INPUT_REQUIRED,
    /** The remote agent needs the caller to arrange authorization out of band. */
    AUTH_REQUIRED,
    /** Finished successfully. */
    COMPLETED,
    /** Finished with an error. */
    FAILED,
    /** Stopped on request. */
    CANCELED,
    /** The agent declined the task. */
    REJECTED,
    /** A state this library does not recognise. It is never treated as completed. */
    UNKNOWN;

    /** Whether the task can no longer change and takes no more messages. */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELED || this == REJECTED;
    }

    /** Whether the task is paused waiting for the caller (input or authorization). */
    public boolean isWaitingOnCaller() {
        return this == INPUT_REQUIRED || this == AUTH_REQUIRED;
    }
}
