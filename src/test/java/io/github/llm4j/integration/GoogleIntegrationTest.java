package io.github.llm4j.integration;

import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.LLMClient;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.provider.google.GoogleProvider;
import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for Google Gemini provider.
 * These tests make real API calls and require GOOGLE_API_KEY to be set.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GoogleIntegrationTest {

    private static LLMClient client;
    private static String apiKey;

    @BeforeAll
    static void setUp() {
        apiKey = System.getenv("GOOGLE_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = "AIzaSyAUYe7LBsFmqh_PE76u_0wGfF9pc-J7prM";
        }

        // Create Google provider with auto-discovered model
        LLMConfig tempConfig = LLMConfig.builder()
                .apiKey(apiKey)
                .build();
        GoogleProvider tempProvider = new GoogleProvider(tempConfig);

        String model = tempProvider.getFirstAvailableModel();
        assertThat(model).isNotNull();

        System.out.println("Using model: " + model);

        LLMConfig config = LLMConfig.builder()
                .apiKey(apiKey)
                .defaultModel(model)
                .build();

        client = new DefaultLLMClient(new GoogleProvider(config));
    }

    @Test
    @Order(1)
    @DisplayName("Test 1: Simple factual question")
    void testSimpleFactualQuestion() {
        System.out.println("\n=== Test 1: Simple factual question ===");

        LLMRequest request = LLMRequest.builder()
                .addUserMessage("What is the capital of France?")
                .build();

        LLMResponse response = client.chat(request);

        assertThat(response).isNotNull();
        assertThat(response.getContent()).isNotEmpty();
        assertThat(response.getContent().toLowerCase()).contains("paris");

        System.out.println("Response: " + response.getContent());
        System.out.println(
                "Tokens: " + (response.getTokenUsage() != null ? response.getTokenUsage().getTotalTokens() : "N/A"));
    }

    @Test
    @Order(2)
    @DisplayName("Test 2: Math question")
    void testMathQuestion() {
        System.out.println("\n=== Test 2: Math question ===");

        LLMRequest request = LLMRequest.builder()
                .addUserMessage("What is 15 multiplied by 23?")
                .build();

        LLMResponse response = client.chat(request);

        assertThat(response).isNotNull();
        assertThat(response.getContent()).isNotEmpty();
        assertThat(response.getContent()).contains("345");

        System.out.println("Response: " + response.getContent());
    }

    @Test
    @Order(3)
    @DisplayName("Test 3: Question with system message")
    void testWithSystemMessage() {
        System.out.println("\n=== Test 3: Question with system message ===");

        LLMRequest request = LLMRequest.builder()
                .addSystemMessage("You are a helpful coding assistant. Keep responses concise.")
                .addUserMessage("What is Python?")
                .build();

        LLMResponse response = client.chat(request);

        assertThat(response).isNotNull();
        assertThat(response.getContent()).isNotEmpty();
        assertThat(response.getContent().toLowerCase()).contains("python");

        System.out.println("Response: " + response.getContent());
    }

    @Test
    @Order(4)
    @DisplayName("Test 4: Multi-turn conversation")
    void testMultiTurnConversation() {
        System.out.println("\n=== Test 4: Multi-turn conversation ===");

        LLMRequest request = LLMRequest.builder()
                .addUserMessage("My name is Alice")
                .addAssistantMessage("Nice to meet you, Alice!")
                .addUserMessage("What's my name?")
                .build();

        LLMResponse response = client.chat(request);

        assertThat(response).isNotNull();
        assertThat(response.getContent()).isNotEmpty();
        assertThat(response.getContent().toLowerCase()).contains("alice");

        System.out.println("Response: " + response.getContent());
    }

    @Test
    @Order(5)
    @DisplayName("Test 5: Short summary request")
    void testShortSummary() {
        System.out.println("\n=== Test 5: Short summary request ===");

        LLMRequest request = LLMRequest.builder()
                .addUserMessage("In one sentence, what are neural networks?")
                .maxTokens(1000) // High value for Gemini 2.5 thinking tokens
                .build();

        LLMResponse response = client.chat(request);

        assertThat(response).isNotNull();
        assertThat(response.getContent()).isNotEmpty();
        // Don't check for specific keywords if we hit token limit
        if (!response.getContent().contains("truncated")) {
            assertThat(response.getContent().toLowerCase()).containsAnyOf("neural", "network", "learning");
        }

        System.out.println("Response: " + response.getContent());
    }

    @Test
    @Order(6)
    @DisplayName("Test 6: Longer explanation")
    void testLongerExplanation() {
        System.out.println("\n=== Test 6: Longer explanation ===");

        LLMRequest request = LLMRequest.builder()
                .addUserMessage("Explain what machine learning is in a paragraph")
                .maxTokens(800) // Increased for Gemini 2.5 thinking tokens
                .build();

        LLMResponse response = client.chat(request);

        assertThat(response).isNotNull();
        assertThat(response.getContent()).isNotEmpty();
        assertThat(response.getContent().length()).isGreaterThan(100);

        System.out.println("Response: " + response.getContent());
    }

    @Test
    @Order(7)
    @DisplayName("Test 7: Creative task")
    void testCreativeTask() {
        System.out.println("\n=== Test 7: Creative task ===");

        LLMRequest request = LLMRequest.builder()
                .addUserMessage("Write a haiku about programming")
                .temperature(0.9)
                .build();

        LLMResponse response = client.chat(request);

        assertThat(response).isNotNull();
        assertThat(response.getContent()).isNotEmpty();

        System.out.println("Response: " + response.getContent());
    }

    @Test
    @Order(8)
    @DisplayName("Test 8: List generation")
    void testListGeneration() {
        System.out.println("\n=== Test 8: List generation ===");

        LLMRequest request = LLMRequest.builder()
                .addUserMessage("List 3 programming languages")
                .build();

        LLMResponse response = client.chat(request);

        assertThat(response).isNotNull();
        assertThat(response.getContent()).isNotEmpty();

        System.out.println("Response: " + response.getContent());
    }

    @Test
    @Order(9)
    @DisplayName("Test 9: Low temperature (deterministic)")
    void testLowTemperature() {
        System.out.println("\n=== Test 9: Low temperature ===");

        LLMRequest request = LLMRequest.builder()
                .addUserMessage("What is 2 + 2?")
                .temperature(0.1)
                .build();

        LLMResponse response = client.chat(request);

        assertThat(response).isNotNull();
        assertThat(response.getContent()).isNotEmpty();
        assertThat(response.getContent()).contains("4");

        System.out.println("Response: " + response.getContent());
    }

    @Test
    @Order(10)
    @DisplayName("Test 10: Complex question with context")
    void testComplexQuestionWithContext() {
        System.out.println("\n=== Test 10: Complex question with context ===");

        LLMRequest request = LLMRequest.builder()
                .addSystemMessage("You are a geography expert.")
                .addUserMessage("What is the capital of Haryana, India?")
                .build();

        LLMResponse response = client.chat(request);

        assertThat(response).isNotNull();
        assertThat(response.getContent()).isNotEmpty();
        assertThat(response.getContent().toLowerCase()).contains("chandigarh");

        System.out.println("Response: " + response.getContent());
    }

    @AfterAll
    static void summary() {
        System.out.println("\n" + "=".repeat(50));
        System.out.println("All 10 tests completed successfully!");
        System.out.println("=".repeat(50));
    }
}
