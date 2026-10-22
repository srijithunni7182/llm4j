package io.github.llm4j.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** R11, R13, R14: the recorder behind the HTTP and routing hooks. */
class CallTraceTest {

    @AfterEach
    void leaveNothingBehind() {
        while (CallTrace.active()) CallTrace.end();
    }

    @Test
    void withNoTraceInForceEveryHookDoesNothing() {
        assertThat(CallTrace.active()).isFalse();
        CallTrace.attempt();
        CallTrace.waited(Duration.ofSeconds(5));
        CallTrace.failover("x");
        CallTrace.Totals t = CallTrace.end();
        assertThat(t).isEqualTo(CallTrace.Totals.NOTHING);
        assertThat(t.attempts()).isEqualTo(1);
        assertThat(t.waitedMillis()).isZero();
        assertThat(t.failovers()).isEmpty();
    }

    @Test
    void countsAttemptsWaitsAndFailoversInOrder() {
        CallTrace.begin();
        assertThat(CallTrace.active()).isTrue();
        CallTrace.attempt();
        CallTrace.waited(Duration.ofMillis(500));
        CallTrace.attempt();
        CallTrace.waited(Duration.ofMillis(1500));
        CallTrace.attempt();
        CallTrace.failover("OpenAIProvider");
        CallTrace.failover("GoogleProvider");
        CallTrace.Totals t = CallTrace.end();
        assertThat(t.attempts()).isEqualTo(3);
        assertThat(t.waitedMillis()).isEqualTo(2000);
        assertThat(t.failovers()).containsExactly("OpenAIProvider", "GoogleProvider");
        assertThat(CallTrace.active()).isFalse();
    }

    @Test
    void aCallThatSentNothingStillCountsAsOneAttempt() {
        CallTrace.begin();
        assertThat(CallTrace.end().attempts()).isEqualTo(1);
    }

    @Test
    void aNestedTraceDoesNotDisturbTheOuterOne() {
        CallTrace.begin();
        CallTrace.attempt();
        CallTrace.begin();
        CallTrace.attempt();
        CallTrace.attempt();
        CallTrace.waited(Duration.ofMillis(10));
        CallTrace.Totals inner = CallTrace.end();
        CallTrace.attempt();
        CallTrace.Totals outer = CallTrace.end();
        assertThat(inner.attempts()).isEqualTo(2);
        assertThat(inner.waitedMillis()).isEqualTo(10);
        assertThat(outer.attempts()).isEqualTo(2);
        assertThat(outer.waitedMillis()).isZero();
    }

    @Test
    void anotherThreadIsNotAttributed() throws Exception {
        CallTrace.begin();
        Thread t =
                new Thread(
                        () -> {
                            CallTrace.attempt();
                            CallTrace.failover("elsewhere");
                        });
        t.start();
        t.join();
        CallTrace.Totals totals = CallTrace.end();
        assertThat(totals.attempts()).isEqualTo(1);
        assertThat(totals.failovers()).isEmpty();
    }

    @Test
    void eachThreadHasItsOwnStack() throws Exception {
        AtomicReference<CallTrace.Totals> other = new AtomicReference<>();
        CallTrace.begin();
        CallTrace.attempt();
        Thread t =
                new Thread(
                        () -> {
                            CallTrace.begin();
                            CallTrace.attempt();
                            CallTrace.attempt();
                            CallTrace.attempt();
                            other.set(CallTrace.end());
                        });
        t.start();
        t.join();
        assertThat(other.get().attempts()).isEqualTo(3);
        assertThat(CallTrace.end().attempts()).isEqualTo(1);
    }

    @Test
    void negativeAndNullInputsAreIgnored() {
        CallTrace.begin();
        CallTrace.waited(null);
        CallTrace.waited(Duration.ofMillis(-5));
        CallTrace.failover(null);
        CallTrace.Totals t = CallTrace.end();
        assertThat(t.waitedMillis()).isZero();
        assertThat(t.failovers()).isEmpty();
    }

    @Test
    void totalsAreImmutable() {
        CallTrace.begin();
        CallTrace.failover("a");
        CallTrace.Totals t = CallTrace.end();
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class, () -> t.failovers().add("b"));
    }
}
