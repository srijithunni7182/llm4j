package io.github.llm4j.a2a;

import java.util.Locale;

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

    /**
     * Maps a state name to a state. Matching ignores case, hyphens and a {@code TASK_STATE_}
     * prefix; "submitted" is {@link #WORKING}. Anything else, and {@code null}, is {@link
     * #UNKNOWN}.
     *
     * @param name a state name, or null
     */
    public static TaskState fromName(String name) {
        if (name == null) {
            return UNKNOWN;
        }
        String key = name.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if (key.startsWith("TASK_STATE_")) {
            key = key.substring("TASK_STATE_".length());
        }
        return switch (key) {
            case "SUBMITTED", "WORKING" -> WORKING;
            case "INPUT_REQUIRED" -> INPUT_REQUIRED;
            case "AUTH_REQUIRED" -> AUTH_REQUIRED;
            case "COMPLETED" -> COMPLETED;
            case "FAILED" -> FAILED;
            case "CANCELED", "CANCELLED" -> CANCELED;
            case "REJECTED" -> REJECTED;
            default -> UNKNOWN;
        };
    }
}
