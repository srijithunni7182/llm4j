package io.github.llm4j.spring;

/** {@code llm4j.loom.max-concurrent-runs} runs are already going; try again when one has finished. */
public class TooManyRuns extends IllegalStateException {

    public TooManyRuns(int max) {
        super("already running " + max + " workflow runs (llm4j.loom.max-concurrent-runs)");
    }
}
