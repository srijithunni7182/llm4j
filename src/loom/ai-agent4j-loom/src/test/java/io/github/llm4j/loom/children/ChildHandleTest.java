package io.github.llm4j.loom.children;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.runtime.ChildHandle;
import io.github.llm4j.loom.runtime.ChildStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Phase 33, D9: the status transitions of a child and the facts a script may read. */
class ChildHandleTest {

    private final ChildHandle h = new ChildHandle("run-1", "Research");

    @Test
    void itKnowsItsRunAndItsWorkflowAndStartsRunning() {
        assertThat(h.runId()).isEqualTo("run-1");
        assertThat(h.workflow()).isEqualTo("Research");
        assertThat(h.status()).isEqualTo(ChildStatus.RUNNING);
        assertThat(h.isOk()).isFalse();
    }

    @Test
    void onlyAWayOfNotCompletingCanEndAChildWithoutAResult() {
        assertThatThrownBy(() -> h.end(ChildStatus.COMPLETED, "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> h.end(ChildStatus.RUNNING, "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> h.end(ChildStatus.SLEEPING, "x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aFailureKeepsItsMessageAsTheFailureAndEveryOtherEndAsTheReason() {
        h.end(ChildStatus.FAILED, "boom");
        assertThat(h.fact("failure")).isEqualTo("boom");
        assertThat(h.fact("reason")).isNull();

        ChildHandle stopped = new ChildHandle("r2", "W");
        stopped.end(ChildStatus.STOPPED, "a person");
        assertThat(stopped.fact("reason")).isEqualTo("a person");
        assertThat(stopped.fact("failure")).isNull();
        assertThat(stopped.status().word()).isEqualTo("stopped");
    }

    @Test
    void onlySleepingAndWaitingArePausesAndTheyCarryTheWakeTime() {
        assertThatThrownBy(() -> h.pause(ChildStatus.RUNNING, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> h.pause(ChildStatus.COMPLETED, null)).isInstanceOf(IllegalArgumentException.class);
        Instant wake = Instant.parse("2026-01-01T01:00:00Z");

        h.pause(ChildStatus.SLEEPING, wake);

        assertThat(h.status()).isEqualTo(ChildStatus.SLEEPING);
        assertThat(h.wakes()).isEqualTo(wake);
        assertThat(h.fact("wakes")).isEqualTo(wake.toString());
        h.resume();
        assertThat(h.status()).isEqualTo(ChildStatus.RUNNING);
    }

    @Test
    void aSettledChildIsNeitherPausedNorResumed() {
        h.complete("done");

        h.pause(ChildStatus.WAITING, null);
        h.resume();

        assertThat(h.status()).isEqualTo(ChildStatus.COMPLETED);
        assertThat(h.isOk()).isTrue();
        assertThat(h.fact("result")).isEqualTo("done");
    }

    @Test
    void aTimedOutChildIsNotCancelledAndAnUnknownFactIsAnError() {
        h.timedOut("5 minutes");
        assertThat(h.status()).isEqualTo(ChildStatus.TIMED_OUT);
        assertThat(h.status().word()).isEqualTo("timed out");
        assertThat(h.fact("reason")).isEqualTo("5 minutes");
        assertThat(h.fact("status")).isEqualTo("timed out");
        assertThatThrownBy(() -> h.fact("tokens")).hasMessageContaining("not available");
        assertThatThrownBy(() -> h.fact("result")).hasMessageContaining("timed out");
    }

    @Test
    void aCancelKeepsWhatItWasGivenAsPartial() {
        h.cancelled("enough", java.util.Map.of("a", 1));

        assertThat(h.status()).isEqualTo(ChildStatus.CANCELLED);
        assertThat(h.fact("partial")).isEqualTo(java.util.Map.of("a", 1));
        assertThat(h.fact("failure")).isNull();
        assertThat(h.status().isSettled()).isTrue();
        assertThat(ChildStatus.SLEEPING.isSettled()).isFalse();
        assertThat(ChildStatus.WAITING.isSettled()).isFalse();
    }

    @Test
    void itPrintsAsAFixedWordNeverItsRunId() {
        assertThat(h.toString()).isEqualTo("[handle to Research]");
    }
}
