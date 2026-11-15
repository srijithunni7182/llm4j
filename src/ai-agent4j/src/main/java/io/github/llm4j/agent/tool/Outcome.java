package io.github.llm4j.agent.tool;

/**
 * What a call to a side-effect tool came to. The status, not the text, tells the effect journal
 * whether the action provably didn't happen ({@link Status#FAILED}, or {@link
 * Status#CREDENTIAL_REJECTED} when the credential was refused) or may have happened ({@link
 * Status#UNKNOWN}).
 */
public record Outcome(String text, Status status) {

    public enum Status {
        OK,
        FAILED,
        UNKNOWN,
        /**
         * The system refused the credential the caller handed over, so nothing was done: like
         * {@link #FAILED} for the journal, and told apart so the caller can obtain a new credential
         * and resume.
         */
        CREDENTIAL_REJECTED
    }

    public static Outcome ok(String text) {
        return new Outcome(text, Status.OK);
    }

    public static Outcome failed(String message) {
        return new Outcome("Error: " + message, Status.FAILED);
    }

    public static Outcome credentialRejected(String message) {
        return new Outcome("Error: credential_rejected: " + message, Status.CREDENTIAL_REJECTED);
    }

    public static Outcome unknown(String message) {
        return new Outcome("Error: " + message, Status.UNKNOWN);
    }
}
