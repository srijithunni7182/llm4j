package io.github.llm4j.loom.host;

/**
 * A run that ended in an error. Everything paid for before it is in the journal; {@link #runId()}
 * says which run.
 */
public class LoomRunFailedException extends RuntimeException {

    private final String runId;

    public LoomRunFailedException(String runId, Throwable cause) {
        super(
                "run "
                        + runId
                        + " failed: "
                        + (cause.getMessage() == null
                                ? cause.getClass().getSimpleName()
                                : cause.getMessage()),
                cause);
        this.runId = runId;
    }

    public String runId() {
        return runId;
    }
}
