package io.github.llm4j.engram.core;

import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.runtime.DefaultVariableContext;
import io.github.llm4j.loom.runtime.VariableContext;
import io.github.llm4j.provider.google.GoogleProvider;
import org.junit.jupiter.api.Test;

import java.io.File;

import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.LLMClient;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CIAIntegrationTest {

    @Test
    void testCIAExtractionAndSynthesis() throws Exception {
        String apiKey = System.getenv("GOOGLE_API_KEY");
        org.junit.jupiter.api.Assumptions.assumeTrue(
            apiKey != null && !apiKey.isBlank(),
            "GOOGLE_API_KEY not set; skipping live CIA integration test"
        );

        LLMConfig config = LLMConfig.builder()
            .apiKey(apiKey)
            .defaultModel("gemini-3.5-flash") // Standard model for JSON tasks
            .build();
            
        GoogleProvider provider = new GoogleProvider(config);
        LLMClient llmClient = new DefaultLLMClient(provider);
        
        ContextIntelligenceAgent cia = new LLMContextIntelligenceAgent(llmClient);
        
        File tempFile = File.createTempFile("cia-integration", ".json");
        tempFile.deleteOnExit();
        
        EngramEngine engine = new EngramEngine(tempFile.getAbsolutePath(), cia);
        AgentDef agent = new AgentDef("ArchitectAgent");
        VariableContext ctx = new DefaultVariableContext();

        // 1. Simulate an outcome to trigger CIA extraction
        String taskIntent = "Design a distributed caching strategy for the user service.";
        String finalAnswer = "I decided to use Redis for the caching layer. To ensure high availability, I configured Redis Cluster with 3 master nodes and 3 replicas. A critical constraint is that the cache TTL for user sessions must strictly be 15 minutes to comply with security policies.";
        
        AgentResult result = AgentResult.builder()
            .finalAnswer(finalAnswer)
            .completed(true)
            .iterations(2)
            .build();
            
        System.out.println("Executing CIA Extraction...");
        engine.storeOutcome(agent, taskIntent, result, ctx);
        
        // 2. Assemble context for a related task to trigger CIA synthesis
        String nextTaskIntent = "Implement the login function for the user service.";
        System.out.println("Executing CIA Synthesis...");
        String briefing = engine.assembleContext(agent, nextTaskIntent, ctx);
        
        System.out.println("---- CIA SYNTHESIZED BRIEFING ----");
        System.out.println(briefing);
        System.out.println("----------------------------------");
        
        // Verify that the CIA picked up the core facts (lenient check to avoid flakiness)
        boolean hasRedis = briefing.toLowerCase().contains("redis");
        boolean hasCaching = briefing.toLowerCase().contains("cache") || briefing.toLowerCase().contains("caching");
        boolean hasTTL = briefing.toLowerCase().contains("15") || briefing.toLowerCase().contains("ttl");
        
        assertTrue(hasRedis || hasCaching, "Briefing should mention Redis or caching");
        assertTrue(hasTTL, "Briefing should mention the TTL constraint");
    }
}
