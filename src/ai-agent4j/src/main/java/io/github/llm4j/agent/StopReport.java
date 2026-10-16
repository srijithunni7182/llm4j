package io.github.llm4j.agent;

import java.util.List;

/**
 * What an agent reports when its run ended through its {@link RunHandle}: why, who asked, where it was, what it had said, what it had done, and whether
 * anything it did may have changed something outside the process. The caller (a workflow runtime, say) reads it to decide what to do next; for
 * instance, a run that may have changed things must not simply be started again.
 *
 * @param kind whether the run was cancelled or asked to wrap up
 * @param reason why, as given to {@link RunHandle#cancel(String, String)} or {@link RunHandle#wrapUp(String, String)}; null when none was given
 * @param origin who or what asked, as given; null when not given
 * @param phase where the agent was when it stopped
 * @param partialAnswer the last thing the agent said (its latest note or reply), or its last observation; empty when it had said nothing
 * @param steps what it had done, in order
 * @param mayHaveChangedThings true when a step ran a tool that is not known to only read (see {@link EffectClassifier}); false when it only read, or ran nothing
 */
public record StopReport(
        Kind kind,
        String reason,
        String origin,
        Phase phase,
        String partialAnswer,
        List<AgentResult.AgentStep> steps,
        boolean mayHaveChangedThings) {

    public enum Kind {
        /** The run was ended; its answer, if any, is only what it had so far. */
        CANCEL,
        /** The run was asked to finish now: one last model call with tools off produced its answer. */
        WRAP_UP
    }

    /** Where the agent was when the stop took effect. */
    public enum Phase {
        /** Before it had called the model at all. */
        BEFORE_START,
        /** In the middle of a model call (the call was closed; only a streaming call can be). */
        MODEL_CALL,
        /** Between the tool calls of one turn: some ran, the rest did not. */
        BETWEEN_TOOLS,
        /** Between two steps: nothing was in flight. */
        BETWEEN_STEPS
    }

    public StopReport {
        steps = steps == null ? List.of() : List.copyOf(steps);
        partialAnswer = partialAnswer == null ? "" : partialAnswer;
    }
}
