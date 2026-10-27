package io.github.llm4j.loom.travel;

/**
 * Thrown inside an observing replay the moment the script would need something the journal does not
 * hold: a model answer, a tool result, a task. It is an {@link Error} so the handlers a script (and
 * the runtime) have for failures do not take it for one and carry on with a made-up trajectory: the
 * replay stops where the real run stopped, and says what was wanted.
 */
public final class ObserveStop extends Error {

    private final String what;

    public ObserveStop(String what) {
        super("the replay needs " + what + ", which the journal does not hold", null, false, false);
        this.what = what;
    }

    /** What was wanted ({@code a model answer from Replier}). */
    public String what() {
        return what;
    }
}
