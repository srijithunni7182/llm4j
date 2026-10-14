package io.github.llm4j.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** R11 to R14 through the agent: the model-call event carries what the layers below reported. */
class ModelCallTraceTest {

    @AfterEach
    void clean() {
        while (CallTrace.active()) CallTrace.end();
    }

    /** A client that behaves like a provider whose HTTP layer retried and slept before answering. */
    private static LLMClient retrying(TestClock clock, int attempts, long waitMs) {
        return new LLMClient() {
            @Override public LLMResponse chat(LLMRequest r) {
                for (int i = 0; i < attempts; i++) {
                    CallTrace.attempt();
                    if (i < attempts - 1) {
                        clock.advance(waitMs);
                        CallTrace.waited(Duration.ofMillis(waitMs));
                    }
                }
                return Scripts.done(true);
            }
            @Override public Stream<LLMResponse> chatStream(LLMRequest r) { return Stream.of(chat(r)); }
        };
    }

    @Test
    void theEventCarriesAttemptsAndWaits() {
        TestClock clock = new TestClock();
        TelemetryListener l = new TelemetryListener();
        AgentResult result = ReActAgent.builder().llmClient(retrying(clock, 3, 400)).clock(clock).addListener(l).build().run("Go.");
        ModelCallEvent e = l.models.get(0);
        assertThat(e.attempts()).isEqualTo(3);
        assertThat(e.retries()).isEqualTo(2);
        assertThat(e.waitedMillis()).isEqualTo(800);
        assertThat(e.durationMillis()).isEqualTo(800);
        assertThat(e.processingMillis()).isZero();
        assertThat(result.getStats().totalRetries()).isEqualTo(2);
        assertThat(result.getStats().model("m").waitedMillis()).isEqualTo(800);
    }

    @Test
    void aClientThatReportsNothingIsOneAttempt() {
        TelemetryListener l = new TelemetryListener();
        ReActAgent.builder().llmClient(Scripts.by(true, n -> Scripts.done(true))).addListener(l).build().run("Go.");
        assertThat(l.models.get(0).attempts()).isEqualTo(1);
        assertThat(l.models.get(0).waitedMillis()).isZero();
        assertThat(l.models.get(0).failovers()).isEmpty();
    }

    @Test
    void failoversAreOnTheEventInOrder() {
        TelemetryListener l = new TelemetryListener();
        LLMClient client = new LLMClient() {
            @Override public LLMResponse chat(LLMRequest r) {
                CallTrace.failover("OpenAIProvider");
                CallTrace.failover("GoogleProvider");
                return Scripts.done(true);
            }
            @Override public Stream<LLMResponse> chatStream(LLMRequest r) { return Stream.of(chat(r)); }
        };
        AgentResult result = ReActAgent.builder().llmClient(client).addListener(l).build().run("Go.");
        assertThat(l.models.get(0).failovers()).containsExactly("OpenAIProvider", "GoogleProvider");
        assertThat(result.getStats().model("m").failovers()).isEqualTo(2);
    }

    @Test
    void aFailedCallStillReportsWhatHappenedBeforeItFailed() {
        TestClock clock = new TestClock();
        TelemetryListener l = new TelemetryListener();
        LLMClient client = new LLMClient() {
            @Override public LLMResponse chat(LLMRequest r) {
                CallTrace.attempt();
                clock.advance(300);
                CallTrace.waited(Duration.ofMillis(300));
                CallTrace.attempt();
                throw new IllegalStateException("boom");
            }
            @Override public Stream<LLMResponse> chatStream(LLMRequest r) { return Stream.empty(); }
        };
        assertThatThrownBy(() -> ReActAgent.builder().llmClient(client).clock(clock).addListener(l).build().run("Go."))
                .isInstanceOf(IllegalStateException.class);
        ModelCallEvent e = l.models.get(0);
        assertThat(e.failed()).isTrue();
        assertThat(e.attempts()).isEqualTo(2);
        assertThat(e.waitedMillis()).isEqualTo(300);
    }

    @Test
    void theTraceIsAlwaysPoppedEvenAfterAFailure() {
        LLMClient client = new LLMClient() {
            @Override public LLMResponse chat(LLMRequest r) { throw new IllegalStateException("boom"); }
            @Override public Stream<LLMResponse> chatStream(LLMRequest r) { return Stream.empty(); }
        };
        assertThatThrownBy(() -> ReActAgent.builder().llmClient(client).build().run("Go.")).isInstanceOf(IllegalStateException.class);
        assertThat(CallTrace.active()).isFalse();
    }

    @Test
    void waitingNeverExceedsTheDurationEvenWhenTheClockDoesNotMove() {
        // the client slept for real but the agent's clock is frozen: the event stays valid
        TestClock frozen = new TestClock();
        TelemetryListener l = new TelemetryListener();
        LLMClient client = new LLMClient() {
            @Override public LLMResponse chat(LLMRequest r) {
                CallTrace.attempt();
                CallTrace.waited(Duration.ofSeconds(5));
                CallTrace.attempt();
                return Scripts.done(true);
            }
            @Override public Stream<LLMResponse> chatStream(LLMRequest r) { return Stream.of(chat(r)); }
        };
        ReActAgent.builder().llmClient(client).clock(frozen).addListener(l).build().run("Go.");
        ModelCallEvent e = l.models.get(0);
        assertThat(e.waitedMillis()).isLessThanOrEqualTo(e.durationMillis());
        assertThat(e.attempts()).isEqualTo(2);
    }

    @Test
    void anAgentInsideAToolHasItsOwnTrace() {
        TelemetryListener outer = new TelemetryListener();
        TelemetryListener inner = new TelemetryListener();
        AtomicInteger calls = new AtomicInteger();
        LLMClient innerClient = new LLMClient() {
            @Override public LLMResponse chat(LLMRequest r) {
                CallTrace.attempt();
                CallTrace.attempt();
                CallTrace.attempt();
                return Scripts.done(true);
            }
            @Override public Stream<LLMResponse> chatStream(LLMRequest r) { return Stream.of(chat(r)); }
        };
        TestTool nested = new TestTool("sub", (args, n) -> {
            ReActAgent.builder().llmClient(innerClient).addListener(inner).build().run("sub");
            return "ok";
        });
        LLMClient outerClient = Scripts.by(true, n -> {
            CallTrace.attempt();
            calls.incrementAndGet();
            return n == 0 ? Scripts.call(true, "sub", "x") : Scripts.done(true);
        });
        ReActAgent.builder().llmClient(outerClient).addTool(nested).addListener(outer).build().run("Go.");
        assertThat(inner.models.get(0).attempts()).isEqualTo(3);
        assertThat(outer.models).allSatisfy(e -> assertThat(e.attempts()).isEqualTo(1));
    }
}
