package io.github.llm4j.aviation;

import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.tools.CurrentTimeTool;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.provider.google.GoogleProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.*;

public class AviationIntegrationTest {

    private static String googleApiKey;
    private static String aviationStackApiKey;

    @BeforeAll
    static void setup() {
        googleApiKey = System.getenv("GOOGLE_API_KEY");
        aviationStackApiKey = System.getenv("AVIATION_STACK_API_KEY");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "How many flights are departing from BLR today?",
            "What is the status of flight UA123?",
            "Which terminal is flight BA11 departing from?",
            "List some active flights for American Airlines.",
            "Is flight LH755 scheduled or cancelled?",
            "What is the IATA code for Heathrow Airport?",
            "Show me flights arriving at JFK.",
            "What is the current time?",
            "Are there any flights from DEL to BOM right now?",
            "Who operates flight SQ502?"
    })
    @EnabledIfEnvironmentVariable(named = "GOOGLE_API_KEY", matches = ".+")
    @EnabledIfEnvironmentVariable(named = "AVIATION_STACK_API_KEY", matches = ".+")
    void testAgentCapabilities(String query) {
        LLMConfig config = LLMConfig.builder()
                .apiKey(googleApiKey)
                .defaultModel("gemini-2.0-flash")
                .build();

        LLMClient llmClient = new DefaultLLMClient(new GoogleProvider(config));

        io.github.llm4j.agent.tools.openapi.OpenAPITool aviationTool = io.github.llm4j.agent.tools.openapi.OpenAPITool
                .builder()
                .name("AviationStack")
                .specLocation("aviation-chatbot/src/main/resources/aviationstack-openapi.json")
                .apiKeyAuth("access_key", aviationStackApiKey)
                .build();

        ReActAgent agent = ReActAgent.builder()
                .llmClient(llmClient)
                .addTool(aviationTool)
                .addTool(new CurrentTimeTool())
                .maxIterations(10)
                .build();

        System.out.println("--------------------------------------------------");
        System.out.println("Running query: " + query);

        AgentResult result = agent.run(query);

        System.out.println("Final Answer: " + result.getFinalAnswer());

        assertTrue(result.isCompleted(), "Agent should complete the task for query: " + query);
        assertNotNull(result.getFinalAnswer());
        assertFalse(result.getFinalAnswer().contains("I am unable to determine"), "Agent failed to answer: " + query);
        assertFalse(result.getFinalAnswer().contains("Error:"), "Agent returned an error: " + query);
    }
}
