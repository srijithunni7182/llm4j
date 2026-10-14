package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.runtime.HumanInterface;
import io.github.llm4j.loom.runtime.RunJournal;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Set;

/**
 * What the parts of a run (approvals, effects, guards, recorded tools) need from the run they belong to, and nothing more: the journal, the
 * trace and audit, the clock, where this thread is in the script, and whether the run is a simulation. They depend on this, not on the
 * {@link HarnessExecutor}, so the executor can change without them. The executor hands out its own instance; it does not implement this.
 */
interface RunServices {

    RunJournal journal();

    /** Tells trace listeners; free when nobody listens. */
    void trace(String type, String agent, String text, Map<String, Object> data);

    void audit(String event, Map<String, Object> data);

    /** The stable id of the step running on this thread. */
    String currentStep();

    /** The current step as an effect or a person's answer identifies it: the same place in the script, whichever attempt reached it. */
    String identityStep();

    /** The attempt number of the delegate running on this thread, so a retried delegate's tool calls are numbered afresh. */
    long currentAttempt();

    Clock clock();

    io.github.llm4j.ratelimit.Sleeper sleeper();

    String maskPii(String text);

    /** True when a run describes what it would do and does not do it. */
    boolean simulating();

    HumanInterface humanInterface();

    /** The run journal's file and the trigger store's directory: tools that touch files must stay out of them. */
    Set<Path> reservedPaths();
}
