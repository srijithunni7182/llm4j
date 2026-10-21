package io.github.llm4j.loom.inspect.state;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.inspect.otel.Ids;
import io.github.llm4j.loom.inspect.otel.OtlpJson;
import io.github.llm4j.loom.inspect.otel.Resource;
import io.github.llm4j.loom.inspect.otel.Semconv;
import io.github.llm4j.loom.inspect.otel.SpanRec;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** R19, R21: the tools of every run, rolled up, worst first; and the same per run in the view. */
class ToolRollupTest {

    private static final long T0 = OtlpJson.nanos(Instant.parse("2026-10-14T10:00:00Z"));

    private final RunRegistry registry = new RunRegistry();
    private int n;

    private void call(
            String client, String runId, String agent, String tool, boolean ok, String category) {
        String trace = Ids.traceId(runId);
        Map<String, Object> a = new LinkedHashMap<>();
        a.put(Semconv.LOOM_SPAN_KIND, Semconv.KIND_TOOL);
        a.put(Semconv.GEN_AI_AGENT_NAME, agent);
        a.put(Semconv.GEN_AI_TOOL_NAME, tool);
        a.put(Semconv.LOOM_TOOL_DURATION_MS, 100L);
        if (!ok) a.put(Semconv.ERROR_TYPE, category);
        long end = T0 + (++n) * 1_000_000_000L;
        registry.applySpans(
                Resource.of(client, "i_1"),
                List.of(
                        new SpanRec(
                                trace,
                                Ids.spanId(runId, "t" + n),
                                null,
                                "execute_tool " + tool,
                                SpanRec.CLIENT,
                                end - 100_000_000L,
                                end,
                                a,
                                ok ? SpanRec.STATUS_OK : SpanRec.STATUS_ERROR,
                                null,
                                List.of())));
    }

    @Test
    void theRollupMergesEveryRunAndListsFailingToolsFirst() {
        for (int i = 0; i < 6; i++) call("shop", "r1", "Editor", "Wiki", true, null);
        for (int i = 0; i < 4; i++) call("shop", "r2", "Writer", "Search", false, "timeout");
        for (int i = 0; i < 2; i++) call("blog", "r3", "Editor", "Search", false, "timeout");
        for (int i = 0; i < 3; i++) call("blog", "r3", "Editor", "Wiki", true, null);
        List<ToolSummary> rollup = registry.toolRollup(null);
        assertThat(rollup).extracting(ToolSummary::name).containsExactly("Search", "Wiki");
        assertThat(rollup.get(0).state()).isEqualTo("failing");
        assertThat(rollup.get(0).calls()).isEqualTo(6);
        assertThat(rollup.get(0).byAgent()).containsKeys("Writer", "Editor");
        assertThat(rollup.get(1).calls()).isEqualTo(9);
        assertThat(rollup.get(1).state()).isEqualTo("healthy");
    }

    @Test
    void aRollupCanBeLimitedToOneClient() {
        for (int i = 0; i < 4; i++) call("shop", "r1", "Writer", "Search", false, "timeout");
        for (int i = 0; i < 4; i++) call("blog", "r2", "Editor", "Search", true, null);
        assertThat(registry.toolRollup("shop"))
                .singleElement()
                .satisfies(
                        s -> {
                            assertThat(s.calls()).isEqualTo(4);
                            assertThat(s.state()).isEqualTo("failing");
                        });
        assertThat(registry.toolRollup("blog"))
                .singleElement()
                .satisfies(s -> assertThat(s.state()).isEqualTo("healthy"));
        assertThat(registry.toolRollup("nobody")).isEmpty();
    }

    @Test
    void orderIsFailingThenDegradedThenHealthyThenByCalls() {
        for (int i = 0; i < 10; i++) call("c", "r", "A", "Fine", true, null);
        for (int i = 0; i < 20; i++) call("c", "r", "A", "Busy", true, null);
        for (int i = 0; i < 10; i++) call("c", "r", "A", "Flaky", i % 2 == 0, "timeout");
        for (int i = 0; i < 3; i++) call("c", "r", "A", "Dead", false, "not_found");
        assertThat(registry.toolRollup(null))
                .extracting(ToolSummary::name)
                .containsExactly("Dead", "Flaky", "Busy", "Fine");
        assertThat(registry.toolRollup(null).get(1).state()).isEqualTo("degraded");
    }

    @Test
    @SuppressWarnings("unchecked")
    void eachRowOfARunsViewCarriesItsHealth() {
        for (int i = 0; i < 4; i++) call("shop", "r1", "Writer", "Search", false, "timeout");
        RunState run = registry.find("shop", Ids.traceId("r1")).orElseThrow();
        Map<String, Object> row = ((List<Map<String, Object>>) run.view().get("tools")).get(0);
        Map<String, Object> health = (Map<String, Object>) row.get("health");
        assertThat(health.get("state")).isEqualTo("failing");
        assertThat(String.valueOf(health.get("reason"))).contains("never succeeded");
        assertThat(((Map<?, ?>) health.get("byAgent")).get("Writer")).isNotNull();
        assertThat(row).containsKeys("calls", "errors", "inFlight", "avgMillis", "successRate");
    }

    @Test
    void anEmptyRegistryHasAnEmptyRollup() {
        assertThat(registry.toolRollup(null)).isEmpty();
    }
}
