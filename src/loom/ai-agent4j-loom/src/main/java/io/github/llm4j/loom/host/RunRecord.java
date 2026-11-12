package io.github.llm4j.loom.host;

import io.github.llm4j.loom.runtime.RunSuspended;
import java.time.Instant;
import java.util.Map;

/**
 * What the host remembers about one run. Inputs are application data; invoker credentials are never
 * part of it. {@code parentRunId} is the run that started this one with {@code start}, or null.
 */
public record RunRecord(
        String runId,
        String workflow,
        Map<String, String> inputs,
        String origin,
        Status status,
        RunSuspended.Reason reason,
        Instant resumeAt,
        String stepId,
        String prompt,
        String detail,
        Instant created,
        Instant updated,
        String parentRunId) {

    /**
     * A record of a run that no run started. Kept so code written before children existed still
     * compiles.
     */
    public RunRecord(
            String runId,
            String workflow,
            Map<String, String> inputs,
            String origin,
            Status status,
            RunSuspended.Reason reason,
            Instant resumeAt,
            String stepId,
            String prompt,
            String detail,
            Instant created,
            Instant updated) {
        this(
                runId, workflow, inputs, origin, status, reason, resumeAt, stepId, prompt, detail,
                created, updated, null);
    }

    public enum Status {
        RUNNING,
        SUSPENDED,
        RESUMING,
        COMPLETED,
        STOPPED,
        FAILED
    }

    public RunRecord {
        inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
    }

    RunRecord with(
            Status s,
            RunSuspended.Reason r,
            Instant at,
            String step,
            String question,
            String why,
            Instant now) {
        return new RunRecord(
                runId, workflow, inputs, origin, s, r, at, step, question, why, created, now,
                parentRunId);
    }
}
