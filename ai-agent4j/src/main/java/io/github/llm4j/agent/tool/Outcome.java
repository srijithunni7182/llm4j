package io.github.llm4j.agent.tool;

/**
 * What a call to a side-effect tool came to. The status, not the text, tells the effect journal whether the
 * action provably didn't happen ({@link Status#FAILED}) or may have happened ({@link Status#UNKNOWN}).
 */
public record Outcome(String text, Status status) {

    public enum Status { OK, FAILED, UNKNOWN }

    public static Outcome ok(String text) {
        return new Outcome(text, Status.OK);
    }

    public static Outcome failed(String message) {
        return new Outcome("Error: " + message, Status.FAILED);
    }

    public static Outcome unknown(String message) {
        return new Outcome("Error: " + message, Status.UNKNOWN);
    }
}
