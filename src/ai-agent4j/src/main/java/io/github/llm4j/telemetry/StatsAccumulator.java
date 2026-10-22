package io.github.llm4j.telemetry;

import io.github.llm4j.model.LLMResponse;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * Adds model-call and tool-call events up into a {@link RunStats}. Thread-safe; {@link #snapshot()}
 * is always consistent.
 */
public final class StatsAccumulator {

    private final Map<String, Model> models = new HashMap<>();
    private final Map<String, Tool> tools = new HashMap<>();

    public synchronized void onModelCall(ModelCallEvent e) {
        String key =
                e.model() != null
                        ? e.model()
                        : e.requestedModel() != null ? e.requestedModel() : "unknown";
        Model m = models.computeIfAbsent(key, k -> new Model());
        m.calls++;
        m.latency.add(e.durationMillis());
        m.retries += e.retries();
        m.failovers += e.failovers().size();
        m.waited += e.waitedMillis();
        if (e.failed()) {
            m.failures.merge(
                    e.category() != null ? e.category() : ErrorCategory.OTHER, 1L, Long::sum);
            return;
        }
        m.prompt += e.promptTokens();
        m.completion += e.completionTokens();
        if (e.cost() != null) m.cost = m.cost == null ? e.cost() : m.cost.add(e.cost());
        if (e.finishReason() != null) m.finishReasons.merge(e.finishReason(), 1L, Long::sum);
        if (e.truncated()) m.truncated++;
        if (e.searches() != null) m.searches += e.searches();
        m.citations += e.citations();
    }

    public synchronized void onToolCall(ToolCallEvent e) {
        Tool t = tools.computeIfAbsent(e.tool(), k -> new Tool());
        t.calls++;
        t.resultChars += e.resultChars();
        if (e.ran()) t.latency.add(e.durationMillis());
        if (e.failed()) {
            if (e.softFailure()) t.soft++;
            ErrorCategory c =
                    e.category() != null
                            ? e.category()
                            : e.outcome().defaultCategory() != null
                                    ? e.outcome().defaultCategory()
                                    : ErrorCategory.OTHER;
            t.failures.merge(c, 1L, Long::sum);
        } else {
            t.successes++;
        }
    }

    public synchronized RunStats snapshot() {
        Map<String, ModelStats> m = new HashMap<>();
        models.forEach(
                (k, v) ->
                        m.put(
                                k,
                                new ModelStats(
                                        k,
                                        v.calls,
                                        v.failures,
                                        v.prompt,
                                        v.completion,
                                        v.cost,
                                        v.retries,
                                        v.failovers,
                                        v.waited,
                                        v.finishReasons,
                                        v.truncated,
                                        v.latency.snapshot(),
                                        v.searches,
                                        v.citations)));
        Map<String, ToolStats> t = new HashMap<>();
        tools.forEach(
                (k, v) ->
                        t.put(
                                k,
                                new ToolStats(
                                        k,
                                        v.calls,
                                        v.successes,
                                        v.failures,
                                        v.soft,
                                        v.resultChars,
                                        v.latency.snapshot())));
        return new RunStats(m, t);
    }

    public synchronized void reset() {
        models.clear();
        tools.clear();
    }

    private static final class Model {
        long calls, prompt, completion, retries, failovers, waited, truncated, searches, citations;
        BigDecimal cost;
        final Map<ErrorCategory, Long> failures = new EnumMap<>(ErrorCategory.class);
        final Map<LLMResponse.FinishReason, Long> finishReasons =
                new EnumMap<>(LLMResponse.FinishReason.class);
        final LatencyRecorder latency = new LatencyRecorder();
    }

    private static final class Tool {
        long calls, successes, soft, resultChars;
        final Map<ErrorCategory, Long> failures = new EnumMap<>(ErrorCategory.class);
        final LatencyRecorder latency = new LatencyRecorder();
    }
}
