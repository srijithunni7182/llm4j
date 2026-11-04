package io.github.llm4j.loom.inspect.state;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.inspect.otel.Ids;
import io.github.llm4j.loom.inspect.otel.LogRec;
import io.github.llm4j.loom.inspect.otel.OtlpJson;
import io.github.llm4j.loom.inspect.otel.Semconv;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Phase 25, R7: a list far over its {@code for each … max N} bound is an incident, because a planner
 * regression and an injection attempt both look like this. It names who wrote the list and carries
 * counts, never an item.
 */
class FanoutIncidentTest {

    static final String RUN = "r-fanout";
    static final String TRACE = Ids.traceId(RUN);
    static final long T0 = OtlpJson.nanos(Instant.parse("2026-10-14T10:00:00Z"));

    private final RunState state = new RunState("p", TRACE);

    private void exceeded(Object... data) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put(Semconv.EVENT_NAME, Semconv.EV_FANOUT_EXCEEDED);
        a.put(Semconv.LOOM_SEQ, 1L);
        a.put(Semconv.LOOM_STEP, "Main/s2");
        for (int i = 0; i < data.length; i += 2) a.put("loom.data." + data[i], data[i + 1]);
        state.apply("i_1", new LogRec(T0, LogRec.ERROR, "plan.tasks has 412 items", a, TRACE, null));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> incidents() {
        Map<String, Object> view = state.view();
        return ((List<Map<String, Object>>) view.getOrDefault("incidents", List.of()))
                .stream().filter(i -> "FANOUT".equals(i.get("kind"))).toList();
    }

    @Test
    void aListOverItsBoundRaisesAnIncidentThatNamesWhoWroteIt() {
        exceeded("count", 412L, "max", 10L, "list", "plan.tasks", "producer", "Planner", "producerLine", 20L);

        assertThat(incidents())
                .singleElement()
                .satisfies(
                        i -> {
                            assertThat(i).containsEntry("severity", "high").containsEntry("step", "Main/s2");
                            assertThat(i.get("title").toString())
                                    .contains("Planner")
                                    .contains("412")
                                    .contains("10");
                            assertThat(String.valueOf(i.get("evidence")))
                                    .contains("upstream")
                                    .doesNotContain("task1");
                        });
    }

    @Test
    void withoutAProducerTheIncidentStillSaysWhatHappened() {
        exceeded("count", 50L, "max", 10L, "list", "items");

        assertThat(incidents()).singleElement().satisfies(i -> assertThat(i.get("title").toString()).contains("items").contains("50"));
    }

    @Test
    void noOtherEventRaisesIt() {
        assertThat(incidents()).isEmpty();
    }
}
