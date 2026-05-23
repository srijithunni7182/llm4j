package io.github.llm4j.tantrik.console.service;

import io.github.llm4j.tantrik.console.model.RunStatus;
import io.github.llm4j.tantrik.console.model.RunSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TantrikRunService} ring buffer and cancellation.
 * Validates: Requirements 4.6, 4.7, 4.8
 */
class TantrikRunServiceTest {

    private TantrikRunService service;

    @BeforeEach
    void setUp() {
        service = new TantrikRunService();
    }

    // -------------------------------------------------------------------------
    // Helper: inject a RunSummary directly into the internal runs map
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, RunSummary> getRunsMap() throws Exception {
        Field field = TantrikRunService.class.getDeclaredField("runs");
        field.setAccessible(true);
        return (Map<String, RunSummary>) field.get(service);
    }

    private RunSummary putRun(String runId, RunStatus status) throws Exception {
        RunSummary summary = new RunSummary();
        summary.setRunId(runId);
        summary.setStatus(status);
        getRunsMap().put(runId, summary);
        return summary;
    }

    // -------------------------------------------------------------------------
    // Ring buffer — capped at 50 entries
    // -------------------------------------------------------------------------

    @Test
    void ringBuffer_retainsUpTo50Entries() throws Exception {
        Map<String, RunSummary> runs = getRunsMap();
        for (int i = 0; i < 50; i++) {
            RunSummary s = new RunSummary();
            s.setRunId("run-" + i);
            s.setStatus(RunStatus.SUCCESS);
            runs.put("run-" + i, s);
        }

        assertThat(runs).hasSize(50);
    }

    @Test
    void ringBuffer_evictsOldestEntryWhenCapExceeded() throws Exception {
        Map<String, RunSummary> runs = getRunsMap();
        // Insert 51 entries — the first one should be evicted
        for (int i = 0; i < 51; i++) {
            RunSummary s = new RunSummary();
            s.setRunId("run-" + i);
            s.setStatus(RunStatus.SUCCESS);
            runs.put("run-" + i, s);
        }

        assertThat(runs).hasSize(50);
        // The least-recently-accessed entry (run-0) should have been evicted
        assertThat(runs).doesNotContainKey("run-0");
        // The most recently inserted entry should still be present
        assertThat(runs).containsKey("run-50");
    }

    @Test
    void listRuns_returnsAllBufferedRuns() throws Exception {
        for (int i = 0; i < 10; i++) {
            putRun("run-" + i, RunStatus.SUCCESS);
        }

        List<RunSummary> result = service.listRuns();

        assertThat(result).hasSize(10);
    }

    // -------------------------------------------------------------------------
    // cancelRun — sets CANCELLED status and completedAt
    // -------------------------------------------------------------------------

    @Test
    void cancelRun_returnsFalse_whenRunNotFound() {
        boolean result = service.cancelRun("nonexistent-run");

        assertThat(result).isFalse();
    }

    @Test
    void cancelRun_returnsTrue_whenRunExists() throws Exception {
        putRun("run-abc", RunStatus.RUNNING);

        boolean result = service.cancelRun("run-abc");

        assertThat(result).isTrue();
    }

    @Test
    void cancelRun_setsStatusToCancelled() throws Exception {
        RunSummary summary = putRun("run-xyz", RunStatus.RUNNING);

        service.cancelRun("run-xyz");

        assertThat(summary.getStatus()).isEqualTo(RunStatus.CANCELLED);
    }

    @Test
    void cancelRun_setsCompletedAt() throws Exception {
        RunSummary summary = putRun("run-xyz", RunStatus.RUNNING);
        assertThat(summary.getCompletedAt()).isNull();

        service.cancelRun("run-xyz");

        assertThat(summary.getCompletedAt()).isNotNull();
    }

    @Test
    void cancelRun_emitsCancelledEvent() throws Exception {
        RunSummary summary = putRun("run-evt", RunStatus.RUNNING);

        service.cancelRun("run-evt");

        boolean hasCancelledEvent = summary.getEvents().stream()
                .anyMatch(e -> "RUN_CANCELLED".equals(e.type()));
        assertThat(hasCancelledEvent).isTrue();
    }

    // -------------------------------------------------------------------------
    // getRun — basic lookup
    // -------------------------------------------------------------------------

    @Test
    void getRun_returnsNull_whenNotFound() {
        assertThat(service.getRun("missing")).isNull();
    }

    @Test
    void getRun_returnsCorrectSummary() throws Exception {
        RunSummary summary = putRun("run-lookup", RunStatus.PENDING);

        assertThat(service.getRun("run-lookup")).isSameAs(summary);
    }
}
