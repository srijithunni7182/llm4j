package io.github.llm4j.loom.inspect.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * R26: a run broken by a task or delegate, and a failure that on_failure took over, are incidents.
 */
class TaskIncidentsTest {

    private final TaskFailureFixtures f = new TaskFailureFixtures();

    @SuppressWarnings("unchecked")
    private static String detail(Map<String, Object> incident) {
        return String.valueOf(
                ((java.util.List<Map<String, Object>>) incident.get("evidence"))
                        .get(0)
                        .get("detail"));
    }

    @Test
    void aHandledFailureIsADivertedIncidentThatClosesWhenTheRunEnds() {
        f.failure(1, "task", "Pay", "Main/s2", "server_error", true);
        assertThat(f.incidents("DIVERTED"))
                .singleElement()
                .satisfies(
                        i -> {
                            assertThat(i)
                                    .containsEntry("severity", "warn")
                                    .containsEntry("state", "open")
                                    .containsEntry("step", "Main/s2");
                            assertThat(i.get("title").toString()).contains("Pay");
                            assertThat(detail(i)).contains("server_error").contains("on_failure");
                        });
        f.runEnd(5, "completed");
        assertThat(f.incidents("DIVERTED").get(0)).containsEntry("state", "closed");
    }

    @Test
    void anUnhandledFailureAloneRaisesNothingUntilTheRunEnds() {
        f.failure(1, "task", "Pay", "Main/s2", "server_error", false);
        assertThat(f.incidents("DIVERTED")).isEmpty();
        assertThat(f.incidents("BROKEN")).isEmpty();
    }

    @Test
    void aRunThatEndsFailedNamesTheTaskInABrokenIncidentThatStaysOpen() {
        f.failure(1, "task", "Pay", "Main/s2", "server_error", false);
        f.runEnd(
                2,
                "failed",
                "loom.run.failed_kind",
                "task",
                "loom.run.failed_name",
                "Pay",
                "loom.run.failed_step",
                "Main/s2",
                "loom.run.failed_category",
                "server_error");
        assertThat(f.incidents("BROKEN"))
                .singleElement()
                .satisfies(
                        i -> {
                            assertThat(i)
                                    .containsEntry("severity", "high")
                                    .containsEntry("state", "open")
                                    .containsEntry("step", "Main/s2");
                            assertThat(i.get("title").toString()).contains("Pay").contains("task");
                            assertThat(detail(i)).contains("server_error");
                        });
        assertThat(f.state.view().get("status")).isEqualTo("failed");
    }

    @Test
    void aDelegateThatBrokeTheRunSaysSo() {
        f.runEnd(
                2,
                "failed",
                "loom.run.failed_kind",
                "delegate",
                "loom.run.failed_name",
                "Writer",
                "loom.run.failed_step",
                "Main/s0",
                "loom.run.failed_category",
                "timeout");
        assertThat(f.incidents("BROKEN"))
                .singleElement()
                .satisfies(
                        i ->
                                assertThat(i.get("title").toString())
                                        .contains("Writer")
                                        .contains("delegate"));
    }

    @Test
    void aFailedRunWithNoNamedCulpritRaisesNoBrokenIncident() {
        f.runEnd(2, "failed");
        assertThat(f.incidents("BROKEN")).isEmpty();
    }

    @Test
    void aStoppedOrCompletedRunIsNeverBroken() {
        f.runEnd(2, "stopped");
        assertThat(f.incidents("BROKEN")).isEmpty();
    }

    @Test
    void replayingTheSameRecordsGivesTheSameIncidents() {
        TaskFailureFixtures g = new TaskFailureFixtures();
        for (TaskFailureFixtures x : new TaskFailureFixtures[] {f, g}) {
            x.failure(1, "task", "Pay", "Main/s2", "server_error", true);
            x.failure(2, "task", "Pay", "Main/s2", "server_error", false);
            x.runEnd(
                    3,
                    "failed",
                    "loom.run.failed_kind",
                    "task",
                    "loom.run.failed_name",
                    "Pay",
                    "loom.run.failed_step",
                    "Main/s2",
                    "loom.run.failed_category",
                    "server_error");
        }
        assertThat(g.incidents("BROKEN")).isEqualTo(f.incidents("BROKEN"));
        assertThat(g.incidents("DIVERTED")).isEqualTo(f.incidents("DIVERTED"));
        assertThat(f.incidents("DIVERTED")).hasSize(1);
    }
}
