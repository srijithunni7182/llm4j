package io.github.llm4j.loom.execution;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * A started workflow's behaviour as a test scripts it instead of running the workflow (phase 34a,
 * F5, in the style of a scripted remote agent): a list of steps, each a state the child reaches
 * after some time on the injected sleeper. It runs without the child's script and without a model,
 * so a supervisor is tested on virtual time with nothing racing. The last step settles the child
 * or pauses it.
 *
 * @param steps the steps in order; never empty, and the last is not {@link State#WORKING}
 */
public record ScriptedChild(List<Step> steps) {

    /**
     * A signal the child sends its parent on reaching a step (phase 34c).
     *
     * @param name the signal's name
     * @param text its text, or empty
     * @param sticky whether it is a latch
     */
    public record Signal(String name, String text, boolean sticky) {}

    /** What a step does to the child. */
    public enum State {
        /** The child is working: time passes, nothing is settled. */
        WORKING,
        /** The child completes with the step's result. */
        COMPLETED,
        /** The child fails with the step's reason. */
        FAILED,
        /** The child waits for a person. */
        WAITING
    }

    /**
     * One step.
     *
     * @param state what the child becomes
     * @param after how long, on the injected sleeper, before it becomes that; zero for at once
     * @param result the result of a completed child, or null
     * @param reason the reason of a failed child, or null
     * @param progress what the child reports on reaching this step (phase 34b), or empty for no report
     * @param signal a signal the child sends its parent on reaching this step (phase 34c), or null
     */
    public record Step(
            State state, Duration after, Object result, String reason, java.util.Map<String, Object> progress, Signal signal) {
        public Step {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(after, "after");
            progress = progress == null ? java.util.Map.of() : java.util.Map.copyOf(progress);
        }

        /** A step that reports no progress. */
        public Step(State state, Duration after, Object result, String reason) {
            this(state, after, result, reason, java.util.Map.of(), null);
        }

        /** A step that may report progress and sends no signal. */
        public Step(State state, Duration after, Object result, String reason, java.util.Map<String, Object> progress) {
            this(state, after, result, reason, progress, null);
        }
    }

    public ScriptedChild {
        steps = List.copyOf(steps);
        if (steps.isEmpty()) throw new IllegalArgumentException("a scripted child needs at least one step");
        if (steps.get(steps.size() - 1).state() == State.WORKING)
            throw new IllegalArgumentException(
                    "the last step of a scripted child must settle it or pause it: completed, failed or waiting");
    }
}
