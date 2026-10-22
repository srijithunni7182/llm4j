package io.github.llm4j.loom.inspect.state;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.inspect.otel.Ids;
import io.github.llm4j.loom.inspect.otel.LogRec;
import io.github.llm4j.loom.inspect.otel.Resource;
import io.github.llm4j.loom.inspect.otel.Semconv;
import io.github.llm4j.loom.inspect.otel.SpanRec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** R39: the security counts of every run, added up, and limited to one client. */
class SecurityRollupTest {

    private final RunRegistry registry = new RunRegistry();
    private int n;

    private void tool(
            String client,
            String runId,
            String agent,
            String tool,
            String reach,
            String patterns,
            String category) {
        String trace = Ids.traceId(runId);
        Map<String, Object> a = new LinkedHashMap<>();
        a.put(Semconv.LOOM_SPAN_KIND, Semconv.KIND_TOOL);
        a.put(Semconv.GEN_AI_AGENT_NAME, agent);
        a.put(Semconv.GEN_AI_TOOL_NAME, tool);
        a.put(Semconv.LOOM_TOOL_REACH, reach);
        a.put(Semconv.LOOM_TOOL_REACH_KNOWN, true);
        if (patterns != null) {
            a.put(Semconv.LOOM_INJECTION_PATTERNS, patterns);
            a.put(Semconv.LOOM_INJECTION_COUNT, 1L);
        }
        if (category != null) a.put(Semconv.ERROR_TYPE, category);
        long end = SecurityFixtures.at(++n);
        registry.applySpans(
                Resource.of(client, "i_1"),
                List.of(
                        new SpanRec(
                                trace,
                                Ids.spanId(runId, "t" + n),
                                null,
                                "execute_tool " + tool,
                                SpanRec.CLIENT,
                                end - 1000,
                                end,
                                a,
                                category == null ? SpanRec.STATUS_OK : SpanRec.STATUS_ERROR,
                                null,
                                List.of())));
    }

    private void guard(String client, String runId, String event) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put(Semconv.EVENT_NAME, Semconv.EV_GUARD);
        a.put(Semconv.LOOM_SEQ, (long) ++n);
        a.put("loom.data.event", event);
        registry.applyLogs(
                Resource.of(client, "i_1"),
                List.of(
                        new LogRec(
                                SecurityFixtures.at(n),
                                LogRec.WARN,
                                "g",
                                a,
                                Ids.traceId(runId),
                                null)));
    }

    @Test
    void theRollupAddsUpEveryRun() {
        tool("shop", "r1", "A", "Swiss", "outward,private,untrusted", "override", null);
        tool("shop", "r2", "A", "Fetch", "untrusted", "exfil", null);
        tool("blog", "r3", "B", "DB", "private", null, "permission_denied");
        guard("blog", "r3", "pii_masked");
        guard("shop", "r1", "pii_masked");
        Map<String, Long> total = registry.securityRollup(null);
        assertThat(total)
                .containsEntry("trifectaExercised", 1L)
                .containsEntry("injectionSuspect", 2L)
                .containsEntry("accessRefused", 1L)
                .containsEntry("piiMasked", 2L);
    }

    @Test
    void itCanBeLimitedToOneClient() {
        tool("shop", "r1", "A", "Fetch", "untrusted", "exfil", null);
        tool("blog", "r3", "B", "DB", "private", null, "permission_denied");
        assertThat(registry.securityRollup("shop"))
                .containsEntry("injectionSuspect", 1L)
                .containsEntry("accessRefused", 0L);
        assertThat(registry.securityRollup("blog"))
                .containsEntry("injectionSuspect", 0L)
                .containsEntry("accessRefused", 1L);
        assertThat(registry.securityRollup("nobody")).isEmpty();
    }

    @Test
    void itEqualsTheMergeOfEveryRunsOwnCounts() {
        tool("shop", "r1", "A", "Swiss", "outward,private,untrusted", "override", null);
        tool("shop", "r2", "A", "Fetch", "untrusted", "exfil", "permission_denied");
        Map<String, Long> merged = new LinkedHashMap<>();
        for (String run : List.of("r1", "r2"))
            registry.find("shop", Ids.traceId(run))
                    .orElseThrow()
                    .securityCounts()
                    .forEach((k, v) -> merged.merge(k, v, Long::sum));
        assertThat(registry.securityRollup(null)).isEqualTo(merged);
    }

    @Test
    void anAcceptedTrifectaIsCountedApartFromAnExercisedOne() {
        tool("shop", "r1", "Support", "Swiss", "outward,private,untrusted", null, null);
        registry.setRules(
                "shop",
                Ids.traceId("r1"),
                Map.of(
                        "Support",
                        new InspectRules(
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                new InspectRules.Security(null, null, null, "reviewed"))));
        assertThat(registry.securityRollup(null))
                .containsEntry("trifectaAccepted", 1L)
                .containsEntry("trifectaExercised", 0L);
    }
}
