package io.github.llm4j.loom.runtime;

import io.github.llm4j.agent.AgentInterrupt;

/**
 * Thrown out of {@code executeWorkflow} when a run is waiting for a human: the run holds no thread
 * while it waits. Record the answer in the {@link RunJournal} under {@link #stepId()} and run the
 * workflow again with the same journal — it replays everything done so far and continues.
 */
public class RunSuspended extends AgentInterrupt {

    private final String stepId;
    private final String prompt;

    public RunSuspended(String stepId, String prompt) {
        super("Waiting for a human at " + stepId);
        this.stepId = stepId;
        this.prompt = prompt;
    }

    public String stepId() {
        return stepId;
    }

    public String prompt() {
        return prompt;
    }
}
