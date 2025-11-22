package io.github.llm4j.examples;

import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.LLMClient;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.config.RetryPolicy;
import io.github.llm4j.exception.*;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.provider.google.GoogleProvider;

import java.time.Duration;

/**
 * Example demonstrating advanced configuration options.
 */
public class AdvancedConfigExample {

    public static void main(String[] args) {
        // Check for API key
        String apiKey = System.getenv("GOOGLE_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("Please set GOOGLE_API_KEY environment variable");
            System.exit(1);
        }

        // Custom retry policy with exponential backoff
        RetryPolicy customRetry = RetryPolicy.builder()
                .maxRetries(5)
                .backoffStrategy(RetryPolicy.BackoffStrategy.EXPONENTIAL)
                .initialBackoff(Duration.ofMillis(500))
                .maxBackoff(Duration.ofSeconds(60))
                .addRetryableStatusCode(503) // Service unavailable
                .build();

        // Advanced configuration
        LLMConfig config = LLMConfig.builder()
                .apiKey(apiKey)
                .defaultModel("gemini-1.5-flash")
                .timeout(Duration.ofSeconds(90))
                .connectTimeout(Duration.ofSeconds(30))
                .retryPolicy(customRetry)
                .enableLogging(true)
                .build();

        LLMClient client = new DefaultLLMClient(new GoogleProvider(config));

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
