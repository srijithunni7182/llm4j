package io.github.llm4j.loom.travel;

/**
 * Called before each statement of an observe-replay runs, with what is known at that point. It may
 * wait (a debugger pausing) and may throw {@link ObserveStop} to end the replay there. The default
 * is to have none, which costs nothing.
 */
@FunctionalInterface
public interface StepGate {

    void before(StepView view);
}
