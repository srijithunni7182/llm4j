package io.github.llm4j.examples;

import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.LLMClient;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.config.RetryPolicy;
import io.github.llm4j.exception.*;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.provider.openai.OpenAIProvider;

import java.time.Duration;

/**
 * Example demonstrating advanced configuration options.
 */
public class AdvancedConfigExample {

    public static void main(String[] args) {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("Please set OPENAI_API_KEY environment variable");
            System.exit(1);
        }

        // Create custom retry policy
        RetryPolicy retryPolicy = RetryPolicy.builder()
                .maxRetries(5)
                .backoffStrategy(RetryPolicy.BackoffStrategy.EXPONENTIAL)
                .initialBackoff(Duration.ofMillis(1000))
                .maxBackoff(Duration.ofSeconds(30))
                .addRetryableStatusCode(429) // Rate limit
                .addRetryableStatusCode(500) // Server error
                .addRetryableStatusCode(503) // Service unavailable
                .build();

        // Create comprehensive config
        LLMConfig config = LLMConfig.builder()
                .apiKey(apiKey)
                .defaultModel("gpt-3.5-turbo")
                .timeout(Duration.ofSeconds(90))
                .connectTimeout(Duration.ofSeconds(10))
                .retryPolicy(retryPolicy)
                .enableLogging(true)
                .build();

        LLMClient client = new DefaultLLMClient(new OpenAIProvider(config));

        // Demonstrate error handling
        demonstrateErrorHandling(client);
    }

    private static void demonstrateErrorHandling(LLMClient client) {
        try {
            LLMRequest request = LLMRequest.builder()
                    .addUserMessage("Tell me a joke about programming")
                    .temperature(0.8)
                    .maxTokens(200)
                    .build();

            LLMResponse response = client.chat(request);
            System.out.println("Success!");
            System.out.println("Response: " + response.getContent());
            System.out.println("Model: " + response.getModel());
            System.out.println("Tokens: " + response.getTokenUsage().getTotalTokens());
            System.out.println("Finish Reason: " + response.getFinishReason());

        } catch (AuthenticationException e) {
            System.err.println("Authentication failed: " + e.getMessage());
            System.err.println("Please check your API key");

        } catch (RateLimitException e) {
            System.err.println("Rate limit exceeded!");
            if (e.getRetryAfterSeconds() != null) {
                System.err.println("Retry after: " + e.getRetryAfterSeconds() + " seconds");
            }

        } catch (InvalidRequestException e) {
            System.err.println("Invalid request: " + e.getMessage());
            System.err.println("Please check your request parameters");

        } catch (ProviderException e) {
            System.err.println("Provider error: " + e.getMessage());
            System.err.println("Provider: " + e.getProviderName());

        } catch (LLMException e) {
            System.err.println("LLM error: " + e.getMessage());
            if (e.getStatusCode() != null) {
                System.err.println("Status code: " + e.getStatusCode());
            }
        }
    }
}
