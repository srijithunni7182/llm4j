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
 * Phase 32, R37: a call to a remote agent that failed, ran past its deadline or spent its question
 * limit is an incident, whether or not an {@code on_failure} took over. The incident names the
 * remote and the step and never carries what the remote said.
 */
class RemoteIncidentTest {

    static final String RUN = "r-remote";
    static final String TRACE = Ids.traceId(RUN);
    static final long T0 = OtlpJson.nanos(Instant.parse("2026-11-11T10:00:00Z"));

    private final RunState state = new RunState("p", TRACE);

    private void failure(String kind, boolean handled, String error) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put(Semconv.EVENT_NAME, Semconv.EV_FAILURE);
        a.put(Semconv.LOOM_SEQ, 1L);
        a.put(Semconv.LOOM_STEP, "Main/s3");
        a.put(Semconv.LOOM_FAILURE_KIND, kind);
        a.put(Semconv.LOOM_FAILURE_NAME, "Reviewer");
        a.put(Semconv.LOOM_FAILURE_CATEGORY, "TIMEOUT");
        a.put(Semconv.LOOM_FAILURE_ATTEMPTS, 2L);
        a.put(Semconv.LOOM_FAILURE_HANDLED, handled);
        if (error != null) a.put(Semconv.LOOM_FAILURE_ERROR, error);
        state.apply("i_1", new LogRec(T0, LogRec.ERROR, "failed", a, TRACE, null));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> incidents() {
        Map<String, Object> view = state.view();
        return ((List<Map<String, Object>>) view.getOrDefault("incidents", List.of()))
                .stream().filter(i -> "REMOTE".equals(i.get("kind"))).toList();
    }

    @Test
    void aRemoteThatFailedIsAnIncidentEvenWhenNothingTookOver() {
        failure("remote", false, "the remote agent Reviewer did not finish in time");

        assertThat(incidents())
                .singleElement()
                .satisfies(
                        i -> {
                            assertThat(i).containsEntry("severity", "high").containsEntry("step", "Main/s3");
                            assertThat(i.get("title").toString()).contains("Reviewer").contains("2");
                        });
    }

    @Test
    void aRemoteThatFailedButWasHandledIsOnlyAWarning() {
        failure("remote", true, null);

        assertThat(incidents()).singleElement().satisfies(i -> assertThat(i).containsEntry("severity", "warn"));
    }

    @Test
    void aSpentQuestionLimitIsNamedAsSuch() {
        failure("remote", false, "the channel desk refused: the question limit of 3 is spent; raise max_questions");

        assertThat(incidents())
                .singleElement()
                .satisfies(i -> assertThat(i.get("title").toString()).contains("question limit"));
    }

    @Test
    void theIncidentNeverCarriesTheRemoteText() {
        failure("remote", false, "the remote said: SECRET-CANARY; the question limit of 3 is spent");

        assertThat(String.valueOf(incidents())).doesNotContain("SECRET-CANARY");
    }

    @Test
    void aFailedTaskIsNotARemoteIncident() {
        failure("task", true, null);

        assertThat(incidents()).isEmpty();
    }
}
