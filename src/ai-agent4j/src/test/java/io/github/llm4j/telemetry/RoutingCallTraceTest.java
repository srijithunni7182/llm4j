package io.github.llm4j.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.LLMClient;
import io.github.llm4j.exception.LLMException;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.routing.FallbackRoutingStrategy;
import io.github.llm4j.routing.ProviderTier;
import io.github.llm4j.routing.RoutingLLMClient;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** R12: the routing client reports each client that failed, in order. */
class RoutingCallTraceTest {

    static class Flaky implements LLMClient {
        private final boolean fail;
        Flaky(boolean fail) { this.fail = fail; }
        @Override public LLMResponse chat(LLMRequest r) {
            if (fail) throw new RuntimeException("down");
            return LLMResponse.builder().content("ok").build();
        }
        @Override public Stream<LLMResponse> chatStream(LLMRequest r) { return Stream.of(chat(r)); }
    }
    static final class Second extends LLMClientBase { Second(boolean f) { super(f); } }
    static final class Third extends LLMClientBase { Third(boolean f) { super(f); } }
    static class LLMClientBase extends Flaky { LLMClientBase(boolean f) { super(f); } }

    private static final LLMRequest REQUEST = LLMRequest.builder().addUserMessage("hi").build();

    @AfterEach
    void clean() {
        while (CallTrace.active()) CallTrace.end();
    }

    private RoutingLLMClient route(LLMClient a, LLMClient b, LLMClient c) {
        return RoutingLLMClient.builder().strategy(new FallbackRoutingStrategy())
                .addClient(ProviderTier.FAST_CHEAP, a)
                .addClient(ProviderTier.BALANCED, b)
                .addClient(ProviderTier.REASONING, c)
                .build();
    }

    @Test
    void noFailureMeansNoFailover() {
        CallTrace.begin();
        route(new Flaky(false), new Second(false), new Third(false)).chat(REQUEST);
        assertThat(CallTrace.end().failovers()).isEmpty();
    }

    @Test
    void eachClientThatFailedIsListedInOrder() {
        CallTrace.begin();
        LLMResponse r = route(new Flaky(true), new Second(true), new Third(false)).chat(REQUEST);
        assertThat(r.getContent()).isEqualTo("ok");
        assertThat(CallTrace.end().failovers()).containsExactly("Flaky", "Second");
    }

    @Test
    void whenEveryClientFailsTheyAreAllListedAndTheErrorStillPropagates() {
        CallTrace.begin();
        assertThatThrownBy(() -> route(new Flaky(true), new Second(true), new Third(true)).chat(REQUEST))
                .isInstanceOf(LLMException.class);
        assertThat(CallTrace.end().failovers()).containsExactly("Flaky", "Second", "Third");
    }

    @Test
    void withNoTraceRoutingBehavesAsBefore() {
        LLMResponse r = route(new Flaky(true), new Second(false), new Third(false)).chat(REQUEST);
        assertThat(r.getContent()).isEqualTo("ok");
        assertThat(CallTrace.active()).isFalse();
    }
}
