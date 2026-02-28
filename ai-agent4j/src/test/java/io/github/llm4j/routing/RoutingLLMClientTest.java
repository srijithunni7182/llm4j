package io.github.llm4j.routing;

import static org.junit.jupiter.api.Assertions.*;

import io.github.llm4j.LLMClient;
import io.github.llm4j.exception.LLMException;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class RoutingLLMClientTest {

    @Test
    void testSuccessfulRouting() {
        MockClient cheapClient = new MockClient("Cheap", false);
        MockClient premiumClient = new MockClient("Premium", false);
        
        RoutingLLMClient client = RoutingLLMClient.builder()
                .strategy(new FallbackRoutingStrategy())
                .addClient(ProviderTier.FAST_CHEAP, cheapClient)
                .addClient(ProviderTier.REASONING, premiumClient)
                .build();
                
        LLMRequest request = LLMRequest.builder().addUserMessage("Hi").build();
        LLMResponse response = client.chat(request);
        
        assertEquals("Cheap", response.getContent());
    }

    @Test
    void testAutoFailoverOnException() {
        // Cheap fails, premium succeeds
        MockClient cheapClient = new MockClient("Cheap", true);
        MockClient premiumClient = new MockClient("Premium", false);
        
        RoutingLLMClient client = RoutingLLMClient.builder()
                .strategy(new FallbackRoutingStrategy())
                .addClient(ProviderTier.FAST_CHEAP, cheapClient)
                .addClient(ProviderTier.REASONING, premiumClient)
                .build();
                
        LLMRequest request = LLMRequest.builder().addUserMessage("Hi").build();
        LLMResponse response = client.chat(request);
        
        // Expected premium since cheap throws an exception
        assertEquals("Premium", response.getContent());
    }
    
    @Test
    void testFatalWhenAllFail() {
        MockClient cheapClient = new MockClient("Cheap", true);
        MockClient premiumClient = new MockClient("Premium", true);
        
        RoutingLLMClient client = RoutingLLMClient.builder()
                .strategy(new FallbackRoutingStrategy())
                .addClient(ProviderTier.FAST_CHEAP, cheapClient)
                .addClient(ProviderTier.REASONING, premiumClient)
                .build();
                
        LLMRequest request = LLMRequest.builder().addUserMessage("Hi").build();
        
        assertThrows(LLMException.class, () -> {
            client.chat(request);
        });
    }

    private static class MockClient implements LLMClient {
        private final String name;
        private final boolean fail;
        
        MockClient(String name, boolean fail) { 
            this.name = name; 
            this.fail = fail;
        }
        
        @Override
        public LLMResponse chat(LLMRequest request) { 
            if (fail) throw new RuntimeException("Simulated API failure");
            return LLMResponse.builder().content(name).build(); 
        }
        
        @Override
        public Stream<LLMResponse> chatStream(LLMRequest request) { 
            if (fail) throw new RuntimeException("Simulated API failure");
            return Stream.of(LLMResponse.builder().content(name).build()); 
        }
    }
}
