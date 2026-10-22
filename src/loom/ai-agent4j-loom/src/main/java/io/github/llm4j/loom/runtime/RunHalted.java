package io.github.llm4j.loom.runtime;

/**
 * Thrown out of {@code executeWorkflow} when a script's own {@code halt} ended the run. It is
 * deliberately not a step failure: {@code retry}, {@code on_failure}, {@code rewind} and a loop's
 * {@code on_exhausted} all let it through.
 */
public class RunHalted extends RuntimeException {

    private final String reason;

    public RunHalted(String reason) {
        super(reason);
        this.reason = reason;
    }

    /** Why the workflow halted, as the script wrote it (placeholders filled in). */
    public String reason() {
        return reason;
    }
}
