package io.github.llm4j.getviral.app.runs;

import java.util.Set;

public enum RunStatus {
    QUEUED, RUNNING, WAITING_FOR_HUMAN, DONE, BLOCKED, FAILED;

    /** Runs using a worker. A run waiting for its creator holds nothing, so it isn't "active". */
    public static final Set<RunStatus> ACTIVE = Set.of(QUEUED, RUNNING);

    public boolean terminal() {
        return this == DONE || this == BLOCKED || this == FAILED;
    }
}
