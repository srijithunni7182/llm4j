package io.github.llm4j.routing;

import static org.junit.jupiter.api.Assertions.*;

import io.github.llm4j.LLMClient;
import io.github.llm4j.exception.LLMException;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class FallbackRoutingStrategyTest {

    @Test
    void testFallbackRouting_selectsCheapestAvailable() {
        FallbackRoutingStrategy strategy = new FallbackRoutingStrategy();
        LLMRequest request = LLMRequest.builder().addUserMessage("Hi").build();

        MockClient cheapClient = new MockClient();
        MockClient premiumClient = new MockClient();

        Map<ProviderTier, List<LLMClient>> clients = Map.of(
            ProviderTier.FAST_CHEAP, List.of(cheapClient),
            ProviderTier.REASONING, List.of(premiumClient)
        );

        LLMClient selected = strategy.selectClient(request, clients);
        assertSame(cheapClient, selected);
    }
    
    @Test
    void testFallbackRouting_skipsEmptyTiers() {
        FallbackRoutingStrategy strategy = new FallbackRoutingStrategy();
        LLMRequest request = LLMRequest.builder().addUserMessage("Hi").build();

        // No fast cheap available
        MockClient premiumClient = new MockClient();

        Map<ProviderTier, List<LLMClient>> clients = Map.of(
            ProviderTier.FAST_CHEAP, List.of(),
            ProviderTier.REASONING, List.of(premiumClient)
        );

        LLMClient selected = strategy.selectClient(request, clients);
        assertSame(premiumClient, selected);
    }

    private static class MockClient implements LLMClient {
        @Override
        public LLMResponse chat(LLMRequest request) { return null; }
        @Override
        public Stream<LLMResponse> chatStream(LLMRequest request) { return null; }
    }
}
