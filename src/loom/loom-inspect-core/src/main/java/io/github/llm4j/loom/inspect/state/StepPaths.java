package io.github.llm4j.loom.inspect.state;

/**
 * Step ids as the executor writes them ({@code Main/s2/r3.0}, {@code Main/s5~2}) reduced to the position in the script they stand
 * for, so every round of a loop and every rewind generation lands on the same place. The graph overlay matches on these.
 */
public final class StepPaths {

    private StepPaths() { }

    /** Drops a rewind generation ({@code ~2}) and the loop round ({@code r3.} becomes {@code r.}). */
    public static String normalize(String step) {
        if (step == null) return null;
        return step.replaceAll("~\\d+", "").replaceAll("/r\\d+\\.", "/r.");
    }
}
