package io.github.llm4j.loom.execution;

import java.util.Map;
import java.util.logging.Logger;

/**
 * What a run reports about itself: the trace events listeners receive (thoughts, tool calls, spend, compaction, control changes) and the statistics it keeps
 * whether or not anyone listens. {@link HarnessExecutor} owns one; the rest of the package still calls {@code executor.trace(...)}.
 */
final class RunTracing {
    private static final Logger log = Logger.getLogger(HarnessExecutor.class.getName());

    /** What tracing needs from the run. */
    interface Host {
        /** The id of the step the calling thread is executing. */
        String stepId();

        java.time.Instant now();

        /** Gives the decider a chance to hold the delivery (a hidden proposal must not leak early); false means deliver now. */
        boolean hold(Runnable deliver);

        io.github.llm4j.loom.security.ToolReach reachOf(String tool);
    }

    private final Host host;
    private final java.util.List<TraceListener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final io.github.llm4j.agent.StatsCollector runStats = new io.github.llm4j.agent.StatsCollector();
    private final java.util.concurrent.atomic.AtomicLong compactionCount = new java.util.concurrent.atomic.AtomicLong();

    RunTracing(Host host) {
        this.host = host;
    }

    void addListener(TraceListener listener) {
        listeners.add(listener);
    }

    boolean tracing() {
        return !listeners.isEmpty();
    }

    /** The statistics of the model and tool calls made so far in this run (kept whether or not anyone traces). */
    io.github.llm4j.telemetry.RunStats stats() {
        return runStats.snapshot();
    }

    long compactions() {
        return compactionCount.get();
    }

    /** Always attached to an agent: keeps the run's statistics and counts compactions, whether or not anyone listens to the trace. */
    io.github.llm4j.agent.AgentEventListener statsAdapter() {
        return new io.github.llm4j.agent.AgentEventListener() {
            @Override public void onThought(String thought) { }
            @Override public void onAction(String toolName, String toolInput) { }
            @Override public void onObservation(String observation) { }
            @Override public void onModelCall(io.github.llm4j.telemetry.ModelCallEvent event) { runStats.onModelCall(event); }
            @Override public void onToolCall(io.github.llm4j.telemetry.ToolCallEvent event) { runStats.onToolCall(event); }
            @Override public void onCompaction(io.github.llm4j.context.CompactionEvent event) {
                if (event.result() != null && event.result().outcome() == io.github.llm4j.context.CompactionResult.Outcome.COMPACTED) compactionCount.incrementAndGet();
            }
        };
    }

    /** Tells trace listeners; free when nobody listens. */
    void trace(String type, String agent, String text, Map<String, Object> data) {
        if (listeners.isEmpty()) return;
        Map<String, Object> copy = new java.util.LinkedHashMap<>();
        if (data != null) data.forEach((k, v) -> { if (k != null && v != null) copy.put(k, v); });
        TraceEvent event = new TraceEvent(type, agent, host.stepId(), text == null ? "" : text,
                java.util.Collections.unmodifiableMap(copy), host.now());
        Runnable deliver = () -> {
            for (TraceListener l : listeners) {
                try {
                    l.onEvent(event);
                } catch (RuntimeException e) {
                    log.warning("Trace listener failed: " + e.getMessage());
                }
            }
        };
        if (!host.hold(deliver)) deliver.run();
    }

    /** Forwards one agent's own events (thoughts, tool calls, observations, spend) to the trace. */
    io.github.llm4j.agent.AgentEventListener traceAdapter(String agentName) {
        return new io.github.llm4j.agent.AgentEventListener() {
            @Override
            public void onThought(String thought) {
                trace(TraceEvent.THOUGHT, agentName, thought, null);
            }

            @Override
            public void onAction(String toolName, String toolInput) {
                trace(TraceEvent.ACTION, agentName, toolName + " " + (toolInput == null ? "" : toolInput),
                        Map.of("tool", toolName));
            }

            @Override
            public void onObservation(String observation) {
                trace(TraceEvent.OBSERVATION, agentName, observation, null);
            }

            @Override
            public void onContextUsage(io.github.llm4j.context.ContextUsage usage) {
                Map<String, Object> d = new java.util.LinkedHashMap<>();
                d.put("used", usage.used());
                d.put("source", usage.source().name());
                String text = "context " + usage.used() + " tokens";
                if (usage.window() != null) {
                    d.put("window", usage.window().tokens());
                    d.put("usable", usage.window().usableInput());
                    usage.fraction().ifPresent(f -> d.put("fraction", f));
                    text += " of " + usage.window().usableInput() + " (" + Math.round(usage.fraction().orElse(0) * 100) + "%)";
                }
                trace(TraceEvent.CONTEXT, agentName, text, d);
            }

            @Override
            public void onCompaction(io.github.llm4j.context.CompactionEvent event) {
                io.github.llm4j.context.CompactionResult r = event.result();
                Map<String, Object> d = new java.util.LinkedHashMap<>();
                d.put("strategy", r.strategy());
                d.put("outcome", r.outcome().name());
                d.put("reason", event.reason().name());
                d.put("before", r.tokensBefore());
                d.put("after", r.tokensAfter());
                d.put("messages_before", r.messagesBefore());
                d.put("messages_after", r.messagesAfter());
                if (event.failure() != null) d.put("error_type", event.failure().split(":", 2)[0]);
                if (r.summarizerUsage() != null) {
                    d.put("summary_tokens", r.summarizerUsage().promptTokens() + r.summarizerUsage().completionTokens());
                    if (r.summarizerUsage().cost() != null) d.put("summary_cost", r.summarizerUsage().cost().toPlainString());
                }
                trace(TraceEvent.COMPACTION, agentName, r.strategy() + " " + r.outcome().name().toLowerCase() + ": " + r.tokensBefore() + " to " + r.tokensAfter() + " tokens", d);
            }

            @Override
            public void onModelCall(io.github.llm4j.telemetry.ModelCallEvent e) {
                Map<String, Object> d = new java.util.LinkedHashMap<>();
                d.put("model", e.model());
                d.put("requested_model", e.requestedModel());
                d.put("iteration", e.iteration());
                d.put("duration_ms", e.durationMillis());
                d.put("waited_ms", e.waitedMillis());
                d.put("attempts", e.attempts());
                if (!e.failovers().isEmpty()) d.put("failovers", e.failovers());
                d.put("prompt_tokens", e.promptTokens());
                d.put("completion_tokens", e.completionTokens());
                d.put("finish_reason", e.finishReason());
                d.put("truncated", e.truncated());
                d.put("outcome", e.outcome().name());
                if (e.failed()) {
                    d.put("category", e.category().name());
                    d.put("error_type", e.errorType());
                }
                trace(TraceEvent.MODEL_CALL, agentName, (e.model() != null ? e.model() : "model") + " " + e.durationMillis() + " ms, "
                        + e.promptTokens() + "+" + e.completionTokens() + " tokens" + (e.failed() ? ", failed: " + e.category() : ""), d);
            }

            @Override
            public void onToolCall(io.github.llm4j.telemetry.ToolCallEvent e) {
                Map<String, Object> d = new java.util.LinkedHashMap<>();
                d.put("tool", e.tool());
                d.put("outcome", e.outcome().name());
                d.put("duration_ms", e.durationMillis());
                d.put("approval_ms", e.approvalMillis());
                d.put("result_chars", e.resultChars());
                d.put("soft_failure", e.softFailure());
                io.github.llm4j.loom.security.ToolReach reach = host.reachOf(e.tool());
                d.put("reach", reach.legs());
                d.put("reach_known", reach.known());
                if (e.failed()) d.put("category", e.category().name());
                trace(TraceEvent.TOOL_CALL, agentName, e.tool() + " " + (e.failed() ? "failed: " + e.category() : "ok") + " in " + e.durationMillis() + " ms", d);
            }

            @Override
            public void onBudget(io.github.llm4j.budget.BudgetEvent event) {
                trace(TraceEvent.BUDGET, agentName, event.kind() + " " + event.budget() + ": spent " + event.spent()
                        + " of " + event.limits(), Map.of("budget", event.budget(), "kind", String.valueOf(event.kind())));
            }
        };
    }

    void traceControl(RunControl.Event e) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("action", e.action().name().toLowerCase(java.util.Locale.ROOT));
        data.put("state", e.state());
        if (e.by() != null) data.put("by", e.by());
        if (e.step() != null) data.put("step", e.step());
        trace(TraceEvent.CONTROL, null, e.action().name().toLowerCase(java.util.Locale.ROOT) + " " + e.state() + (e.by() == null ? "" : " by " + e.by()), data);
    }
}
