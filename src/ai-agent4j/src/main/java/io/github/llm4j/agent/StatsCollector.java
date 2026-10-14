package io.github.llm4j.agent;

import io.github.llm4j.telemetry.ModelCallEvent;
import io.github.llm4j.telemetry.RunStats;
import io.github.llm4j.telemetry.StatsAccumulator;
import io.github.llm4j.telemetry.ToolCallEvent;

/**
 * A listener that keeps statistics across runs and threads: add it once and read {@link #snapshot()} whenever you like.
 *
 * <pre>{@code
 * StatsCollector stats = new StatsCollector();
 * ReActAgent agent = ReActAgent.builder()....addListener(stats).build();
 * ...
 * stats.snapshot().tool("search").successRate();
 * }</pre>
 *
 * One collector may be shared by many agents and threads.
 */
public final class StatsCollector implements AgentEventListener {

    private final StatsAccumulator accumulator = new StatsAccumulator();

    @Override
    public void onThought(String thought) {}

    @Override
    public void onAction(String toolName, String toolInput) {}

    @Override
    public void onObservation(String observation) {}

    @Override
    public void onModelCall(ModelCallEvent event) {
        accumulator.onModelCall(event);
    }

    @Override
    public void onToolCall(ToolCallEvent event) {
        accumulator.onToolCall(event);
    }

    /** A consistent copy of everything counted so far; the collector keeps counting. */
    public RunStats snapshot() {
        return accumulator.snapshot();
    }

    /** Forgets everything counted so far. */
    public void reset() {
        accumulator.reset();
    }
}
