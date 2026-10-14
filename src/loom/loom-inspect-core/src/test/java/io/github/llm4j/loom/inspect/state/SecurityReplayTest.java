package io.github.llm4j.loom.inspect.state;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** R39: the same records give the same security view and incidents. */
class SecurityReplayTest {

    private static void feed(SecurityFixtures f) {
        f.tool(1, "Support", "Fetch", "untrusted");
        f.suspect(2, "Support", "Fetch", "untrusted", "exfil,override", 2);
        f.tool(3, "Support", "CustomerDB", "private");
        f.tool(4, "Support", "SendEmail", "outward");
        f.failed(5, "Researcher", "CustomerDB", "private", "permission_denied", false);
        f.failed(6, "Researcher", "CustomerDB", "private", "permission_denied", false);
        f.failed(7, "Researcher", "CustomerDB", "private", "permission_denied", false);
        f.failed(8, "Researcher", "DeleteRecord", null, "unknown_tool", true);
        f.guard(9, "Support", "pii_masked");
        f.runEnd(10, "completed");
    }

    @Test
    void theSameRecordsGiveTheSameViewAndTheSameIncidents() {
        SecurityFixtures a = new SecurityFixtures();
        SecurityFixtures b = new SecurityFixtures();
        feed(a);
        feed(b);
        assertThat(b.security()).isEqualTo(a.security());
        for (String kind : new String[] {"TRIFECTA", "INJECTION", "ACCESS"}) assertThat(b.incidents(kind)).as(kind).isEqualTo(a.incidents(kind));
        assertThat(a.incidents("TRIFECTA")).hasSize(1);
        assertThat(a.incidents("INJECTION")).hasSize(1);
        assertThat(a.incidents("ACCESS")).hasSize(2);
    }

    @Test
    void feedingARecordTwiceDoesNotCountItTwice() {
        SecurityFixtures a = new SecurityFixtures();
        a.suspect(1, "A", "Fetch", "untrusted", "override", 1);
        a.state.apply(new io.github.llm4j.loom.inspect.otel.SpanRec(SecurityFixtures.TRACE, io.github.llm4j.loom.inspect.otel.Ids.spanId(SecurityFixtures.RUN, "t1"), null, "x",
                io.github.llm4j.loom.inspect.otel.SpanRec.CLIENT, 1, 2, java.util.Map.of(io.github.llm4j.loom.inspect.otel.Semconv.LOOM_SPAN_KIND, "tool", io.github.llm4j.loom.inspect.otel.Semconv.GEN_AI_TOOL_NAME, "Fetch",
                io.github.llm4j.loom.inspect.otel.Semconv.LOOM_INJECTION_PATTERNS, "override"), io.github.llm4j.loom.inspect.otel.SpanRec.STATUS_OK, null, java.util.List.of()));
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> inj = (java.util.Map<String, Object>) a.security().get("injection");
        assertThat(inj).containsEntry("suspect", 1L);
    }
}
