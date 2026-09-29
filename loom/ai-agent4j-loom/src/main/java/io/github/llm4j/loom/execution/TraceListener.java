package io.github.llm4j.loom.execution;

/**
 * Receives a run's {@link TraceEvent}s live (see {@link HarnessExecutor#addTraceListener}). Called on the
 * thread doing the work — parallel branches call it concurrently — so keep it quick and thread-safe.
 */
@FunctionalInterface
public interface TraceListener {
    void onEvent(TraceEvent event);
}
