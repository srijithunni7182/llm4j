package io.github.llm4j.loom.execution;

/** Told, as a run goes, where it chose a way. Called on the thread that is running the step. */
@FunctionalInterface
public interface DecisionListener {
    void decided(DecisionPoint point);
}
