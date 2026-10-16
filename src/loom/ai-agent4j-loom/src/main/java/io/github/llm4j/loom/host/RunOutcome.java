package io.github.llm4j.loom.host;

import io.github.llm4j.loom.runtime.RunSuspended;
import java.time.Instant;
import java.util.Map;

/** How a blocking run ended. A failure is not an outcome: it is thrown as {@link LoomRunFailedException}. */
public sealed interface RunOutcome {

    String runId();

    /** The workflow finished; {@code variables} are the context's values at the end. */
    record Completed(String runId, Map<String, Object> variables) implements RunOutcome { }

    /** The run is waiting, holding no thread. {@code resumeAt} is null while it waits for a person; {@code prompt} is the question then. */
    record Suspended(String runId, RunSuspended.Reason reason, Instant resumeAt, String stepId, String prompt) implements RunOutcome { }

    /** An operator or the application stopped the run at a safe point; resuming carries on. */
    record Stopped(String runId, String detail) implements RunOutcome { }
}
