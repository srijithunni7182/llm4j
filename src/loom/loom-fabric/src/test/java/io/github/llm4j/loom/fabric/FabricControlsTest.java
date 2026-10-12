package io.github.llm4j.loom.fabric;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.inspect.control.Controls;
import io.github.llm4j.loom.inspect.otel.Ids;
import io.github.llm4j.loom.inspect.otel.LogRec;
import io.github.llm4j.loom.inspect.otel.OtlpJson;
import io.github.llm4j.loom.inspect.otel.Resource;
import io.github.llm4j.loom.inspect.otel.Semconv;
import io.github.llm4j.loom.inspect.receive.CommandQueue;
import io.github.llm4j.loom.inspect.receive.Receiver;
import io.github.llm4j.loom.inspect.state.RunRegistry;
import io.github.llm4j.loom.inspect.wire.Hello;
import io.github.llm4j.loom.inspect.wire.Wire;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** What the page may offer for a run in another process, and when a request is refused. */
class FabricControlsTest {

    private static final String TRACE = Ids.traceId("r-1");
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-12T10:00:00Z"));
    private final Clock clock = new Clock() {
        public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId z) { return this; }
        public Instant instant() { return now.get(); }
    };
    private final RunRegistry registry = new RunRegistry();
    private final Receiver receiver = new Receiver(registry, clock);
    private final CommandQueue queue = new CommandQueue(clock);
    private Controls controls(String password) {
        return new FabricControls(receiver, queue, () -> password);
    }

    private void report() {
        receiver.hello("alpha", new Hello("alpha", "i_1", "h", 1, "5", "0.1", Wire.VERSION, List.of()));
        LogRec r = new LogRec(1_000_000_000L, LogRec.INFO, "w", Map.of(Semconv.EVENT_NAME, Semconv.EV_RUN_START, Semconv.LOOM_SEQ, 1L, Semconv.LOOM_RUN_ID, "r-1", Semconv.LOOM_WORKFLOW, "Main"), TRACE, null);
        receiver.logs("alpha", OtlpJson.encodeLogs(Resource.of("alpha", "i_1"), "0.1", List.of(r)));
    }

    @Test
    void anOnlineRunOffersTheControlsAndARequestIsQueuedForItsProcess() throws Exception {
        report();
        Controls c = controls("pw");
        assertThat(c.allowed(TRACE)).containsExactlyInAnyOrder(Controls.Action.STOP, Controls.Action.SUSPEND, Controls.Action.PAUSE);
        assertThat(c.request(TRACE, Controls.Action.STOP, "ops").accepted()).isTrue();
        assertThat(queue.next("alpha", Duration.ZERO)).singleElement().satisfies(cmd -> assertThat(cmd.action()).isEqualTo("stop"));
        assertThat(queue.next("beta", Duration.ZERO)).isEmpty();
    }

    @Test
    void anOfflineProcessOffersNothing_andARequestIsRefusedNotQueued() throws Exception {
        report();
        now.set(now.get().plus(Duration.ofMinutes(5)));
        Controls c = controls("pw");
        assertThat(c.allowed(TRACE)).isEmpty();
        Controls.Outcome o = c.request(TRACE, Controls.Action.STOP, "ops");
        assertThat(o.accepted()).isFalse();
        assertThat(o.status()).isEqualTo(409);
        assertThat(queue.waiting("alpha")).isZero();
    }

    @Test
    void withoutAnAdminPasswordNothingIsOffered_andACommandNotCollectedInASecondMinuteIsDropped() throws Exception {
        report();
        assertThat(controls(null).allowed(TRACE)).isEmpty();
        controls("pw").request(TRACE, Controls.Action.PAUSE, "ops");
        now.set(now.get().plusSeconds(61));
        assertThat(queue.next("alpha", Duration.ZERO)).isEmpty();
    }
}
