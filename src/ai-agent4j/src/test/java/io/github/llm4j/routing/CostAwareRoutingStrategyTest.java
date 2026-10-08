package io.github.llm4j.routing;

import static org.junit.jupiter.api.Assertions.*;

import io.github.llm4j.LLMClient;
import io.github.llm4j.model.ComplexityHint;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CostAwareRoutingStrategyTest {

    private CostAwareRoutingStrategy strategy;
    private MockClient cheapClient;
    private MockClient premiumClient;
    private Map<ProviderTier, List<LLMClient>> clients;

    @BeforeEach
    void setup() {
        strategy = new CostAwareRoutingStrategy();
        cheapClient = new MockClient("Cheap");
        premiumClient = new MockClient("Premium");
        
        clients = Map.of(
            ProviderTier.FAST_CHEAP, List.of(cheapClient),
            ProviderTier.REASONING, List.of(premiumClient)
        );
    }

    @Test
    void testSelectsFastCheapByDefault() {
        LLMRequest request = LLMRequest.builder().addUserMessage("Hi").build();
        LLMClient selected = strategy.selectClient(request, clients);
        assertEquals(cheapClient, selected);
    }

    @Test
    void testSelectsReasoningByExplicitHint() {
        LLMRequest request = LLMRequest.builder()
                .addUserMessage("Solve P=NP")
                .complexityHint(ComplexityHint.HIGH)
                .build();
        LLMClient selected = strategy.selectClient(request, clients);
        assertEquals(premiumClient, selected);
    }

    @Test
    void testSelectsReasoningByHeuristic_Temperature() {
        LLMRequest request = LLMRequest.builder()
                .addUserMessage("Write a crazy poem")
                .temperature(0.9) // Above 0.8 defaults to reasoning
                .build();
        LLMClient selected = strategy.selectClient(request, clients);
        assertEquals(premiumClient, selected);
    }
    
    @Test
    void testSelectsReasoningByHeuristic_SystemKeyword() {
        LLMRequest request = LLMRequest.builder()
                .addSystemMessage("You are an expert mathematician.")
                .addUserMessage("2+2")
                .build();
        LLMClient selected = strategy.selectClient(request, clients);
        assertEquals(premiumClient, selected);
    }
    
    @Test
    void testSelectsReasoningByHeuristic_LongContext() {
        // Build a giant string
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 41000; i++) {
            sb.append("a");
        }
        
        LLMRequest request = LLMRequest.builder()
                .addUserMessage(sb.toString())
                .build();
        LLMClient selected = strategy.selectClient(request, clients);
        assertEquals(premiumClient, selected);
    }

    private static class MockClient implements LLMClient {
        private final String name;
        MockClient(String name) { this.name = name; }
        @Override
        public LLMResponse chat(LLMRequest request) { return null; }
        @Override
        public Stream<LLMResponse> chatStream(LLMRequest request) { return null; }
    }
}
