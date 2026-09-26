package io.github.llm4j.getviral.app.runs;

import java.util.Set;

public enum RunStatus {
    QUEUED, RUNNING, WAITING_FOR_HUMAN, DONE, BLOCKED, FAILED;

    public static final Set<RunStatus> ACTIVE = Set.of(QUEUED, RUNNING, WAITING_FOR_HUMAN);

    public boolean terminal() {
        return this == DONE || this == BLOCKED || this == FAILED;
    }
}
