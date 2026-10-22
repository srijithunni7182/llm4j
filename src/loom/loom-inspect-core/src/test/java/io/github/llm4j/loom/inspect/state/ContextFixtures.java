package io.github.llm4j.loom.inspect.state;

import io.github.llm4j.loom.inspect.otel.Ids;
import io.github.llm4j.loom.inspect.otel.LogRec;
import io.github.llm4j.loom.inspect.otel.OtlpJson;
import io.github.llm4j.loom.inspect.otel.Semconv;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Context and compaction records for the Phase 5 view tests, fed to a {@link RunState}. */
final class ContextFixtures {

    static final String RUN = "r-ctx";
    static final String TRACE = Ids.traceId(RUN);
    static final long T0 = OtlpJson.nanos(Instant.parse("2026-10-14T10:00:00Z"));

    final RunState state = new RunState("p", TRACE);
    private long seq;

    static long at(double seconds) {
        return T0 + (long) (seconds * 1_000_000_000L);
    }

    /**
     * One model call's context: {@code window} and {@code usable} null when the process did not
     * know them.
     */
    void context(double sec, String agent, long used, Long window, Long usable) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put(Semconv.EVENT_NAME, Semconv.EV_CONTEXT);
        a.put(Semconv.LOOM_SEQ, ++seq);
        a.put(Semconv.GEN_AI_AGENT_NAME, agent);
        a.put(Semconv.LOOM_CONTEXT_USED, used);
        a.put(Semconv.LOOM_CONTEXT_SOURCE, "REPORTED");
        if (window != null) a.put(Semconv.LOOM_CONTEXT_WINDOW, window);
        if (usable != null) {
            a.put(Semconv.LOOM_CONTEXT_USABLE, usable);
            a.put(Semconv.LOOM_CONTEXT_FRACTION, used / (double) usable);
        }
        state.apply("i_1", new LogRec(at(sec), LogRec.INFO, "c", a, TRACE, null));
    }

    void compaction(double sec, String agent, String outcome, long before, long after) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put(Semconv.EVENT_NAME, Semconv.EV_COMPACTION);
        a.put(Semconv.LOOM_SEQ, ++seq);
        a.put(Semconv.GEN_AI_AGENT_NAME, agent);
        a.put(Semconv.LOOM_COMPACTION_STRATEGY, "drop_oldest");
        a.put(Semconv.LOOM_COMPACTION_OUTCOME, outcome);
        a.put(Semconv.LOOM_COMPACTION_BEFORE, before);
        a.put(Semconv.LOOM_COMPACTION_AFTER, after);
        state.apply("i_1", new LogRec(at(sec), LogRec.INFO, "c", a, TRACE, null));
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> context(String agent) {
        List<Map<String, Object>> agents = (List<Map<String, Object>>) state.view().get("agents");
        return (Map<String, Object>)
                agents.stream()
                        .filter(m -> agent.equals(m.get("name")))
                        .findFirst()
                        .orElseThrow()
                        .get("context");
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> incidents(String kind) {
        return ((List<Map<String, Object>>) state.view().get("incidents"))
                .stream().filter(m -> kind.equals(m.get("kind"))).toList();
    }
}
