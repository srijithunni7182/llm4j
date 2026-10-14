package io.github.llm4j.loom.inspect.state;

import io.github.llm4j.loom.inspect.otel.Ids;
import io.github.llm4j.loom.inspect.otel.LogRec;
import io.github.llm4j.loom.inspect.otel.OtlpJson;
import io.github.llm4j.loom.inspect.otel.Semconv;
import io.github.llm4j.loom.inspect.otel.SpanRec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tool spans, guard logs and run ends for the security tests, fed to a {@link RunState}. */
final class SecurityFixtures {

    static final String RUN = "r-sec";
    static final String TRACE = Ids.traceId(RUN);
    static final long T0 = OtlpJson.nanos(Instant.parse("2026-10-14T10:00:00Z"));

    RunState state = new RunState("p", TRACE);
    private long seq;
    private int spanNo;

    static long at(double seconds) {
        return T0 + (long) (seconds * 1_000_000_000L);
    }

    /** A tool call that ran and worked. */
    void tool(double sec, String agent, String tool, String reach) {
        span(sec, agent, tool, reach, true, null, null, 0, false);
    }

    void tool(double sec, String agent, String tool, String reach, boolean known) {
        span(sec, agent, tool, reach, known, null, null, 0, false);
    }

    /** A tool call whose result tripped the injection scan. */
    void suspect(double sec, String agent, String tool, String reach, String groups, int count) {
        span(sec, agent, tool, reach, true, null, groups, count, false);
    }

    /** A tool call that failed with this category (lower case, as the mapper writes it); {@code noResult} for one the runtime never ran. */
    void failed(double sec, String agent, String tool, String reach, String category, boolean noResult) {
        span(sec, agent, tool, reach, true, category, null, 0, noResult);
    }

    private void span(double sec, String agent, String tool, String reach, boolean known, String category, String groups, int count, boolean noResult) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put(Semconv.LOOM_SPAN_KIND, Semconv.KIND_TOOL);
        a.put(Semconv.GEN_AI_AGENT_NAME, agent);
        a.put(Semconv.GEN_AI_TOOL_NAME, tool);
        a.put(Semconv.LOOM_STEP, "Main/s0");
        a.put(Semconv.LOOM_TOOL_DURATION_MS, 100L);
        if (reach != null) {
            a.put(Semconv.LOOM_TOOL_REACH, reach);
            a.put(Semconv.LOOM_TOOL_REACH_KNOWN, known);
        }
        if (groups != null) {
            a.put(Semconv.LOOM_INJECTION_PATTERNS, groups);
            a.put(Semconv.LOOM_INJECTION_COUNT, (long) count);
        }
        if (noResult) a.put(Semconv.LOOM_TOOL_NO_RESULT, true);
        if (category != null) a.put(Semconv.ERROR_TYPE, category);
        long end = at(sec);
        state.apply(new SpanRec(TRACE, Ids.spanId(RUN, "t" + (++spanNo)), null, "execute_tool " + tool, SpanRec.CLIENT, end - 100_000_000L, end, a,
                category == null ? SpanRec.STATUS_OK : noResult ? SpanRec.STATUS_UNSET : SpanRec.STATUS_ERROR, null, List.of()));
    }

    void guard(double sec, String agent, String event) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put(Semconv.EVENT_NAME, Semconv.EV_GUARD);
        a.put(Semconv.LOOM_SEQ, ++seq);
        a.put(Semconv.GEN_AI_AGENT_NAME, agent);
        if (event != null) a.put("loom.data.event", event);
        state.apply("i_1", new LogRec(at(sec), LogRec.WARN, "guard", a, TRACE, null));
    }

    void runEnd(double sec, String status) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put(Semconv.EVENT_NAME, Semconv.EV_RUN_END);
        a.put(Semconv.LOOM_SEQ, ++seq);
        a.put(Semconv.LOOM_RUN_STATUS, status);
        state.apply("i_1", new LogRec(at(sec), LogRec.INFO, "end", a, TRACE, null));
    }

    void accept(String agent, String reason) {
        state.setRules(Map.of(agent, new InspectRules(null, null, null, null, null, null, null, null, null, new InspectRules.Security(null, null, null, reason))));
    }

    void rules(String agent, InspectRules.Security security) {
        state.setRules(Map.of(agent, new InspectRules(null, null, null, null, null, null, null, null, null, security)));
    }

    List<Map<String, Object>> incidents(String kind) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : (List<?>) state.view().get("incidents")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) o;
            if (kind.equals(m.get("kind"))) out.add(m);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> security() {
        return (Map<String, Object>) state.view().get("security");
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> agent(String name) {
        return ((List<Map<String, Object>>) security().get("agents")).stream().filter(m -> name.equals(m.get("agent"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    static String detail(Map<String, Object> incident) {
        return String.valueOf(((List<Map<String, Object>>) incident.get("evidence")).get(0).get("detail"));
    }
}
