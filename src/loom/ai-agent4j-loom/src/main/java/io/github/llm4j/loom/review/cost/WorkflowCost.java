package io.github.llm4j.loom.review.cost;

import java.util.List;

/**
 * The estimate for one workflow.
 *
 * @param name the workflow
 * @param typical what a normal run spends
 * @param worst what the longest allowed run spends; when {@code unbounded}, only the part that has
 *     a bound
 * @param unbounded true when something in the workflow can repeat without limit
 * @param steps each model step
 * @param notes things the estimate left out or could not know
 * @param time how long it takes, or null when not estimated
 * @param effects how many sends and writes it can cause, or null when not estimated
 */
public record WorkflowCost(
        String name,
        Usage typical,
        Usage worst,
        boolean unbounded,
        List<StepCost> steps,
        List<String> notes,
        TimeEstimate time,
        EffectsEstimate effects) {

    /** A workflow's cost with no time or effects estimate. */
    public WorkflowCost(
            String name,
            Usage typical,
            Usage worst,
            boolean unbounded,
            List<StepCost> steps,
            List<String> notes) {
        this(name, typical, worst, unbounded, steps, notes, null, null);
    }

    public WorkflowCost {
        steps = List.copyOf(steps);
        notes = List.copyOf(notes);
    }
}
