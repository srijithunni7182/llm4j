# Advanced Configuration and Customization

This guide covers advanced topics for fine-tuning `ai-agent4j`, including creating custom tools, configuring retry policies, and detailed error handling.

## Creating Custom Tools

Implementing your own tools allows your agents to interact with proprietary APIs, databases, or local logic.

### Steps to Create a Tool

1. **Implement the `Tool` interface**: Define the tool's name, description, and execution logic.
2. **Add to the Agent Builder**: Register your tool so the agent can discover it.

```java
import io.github.llm4j.agent.Tool;

public class MyCustomTool implements Tool {
    @Override
    public String getName() {
        return "MyTool";
    }
    
    @Override
    public String getDescription() {
        return "Does something specific. Input should be X.";
    }
    
    @Override
    public String execute(String input) throws Exception {
        // Your custom logic here
        return "Result of processing " + input;
    }
}

// Registering the tool
ReActAgent agent = ReActAgent.builder()
        .llmClient(client)
        .addTool(new MyCustomTool())
        .build();
```

## Retry Policies and Timeouts

In production, network flakiness and rate limits are common. `ai-agent4j` provides a robust `RetryPolicy` builder.

```java
import io.github.llm4j.config.RetryPolicy;
import java.time.Duration;

RetryPolicy customRetry = RetryPolicy.builder()
        .maxRetries(5)
        .backoffStrategy(RetryPolicy.BackoffStrategy.EXPONENTIAL)
        .initialBackoff(Duration.ofMillis(1000))
        .maxBackoff(Duration.ofSeconds(30))
        .addRetryableStatusCode(429) // Rate limit
        .addRetryableStatusCode(503) // Service unavailable
        .build();

LLMConfig config = LLMConfig.builder()
        .apiKey(apiKey)
        .retryPolicy(customRetry)
        .timeout(Duration.ofSeconds(90))
        .build();
```

## Detailed Error Handling

Use specific exception types to handle different failure modes gracefully.

| Exception | Cause | Recommendation |
| :--- | :--- | :--- |
| `AuthenticationException` | Invalid API key. | Check your environment variables. |
| `RateLimitException` | Quota exceeded (429). | Implement backoff or check billing. |
| `InvalidRequestException` | Bad parameters (400). | Verify input JSON or model name. |
| `ProviderException` | LLM Provider side error (500). | Retry or switch providers. |
| `LLMException` | Generic framework error. | Check logs for stack trace. |

```java
try {
    LLMResponse response = client.chat(request);
} catch (RateLimitException e) {
    System.err.println("Rate limited. Retry after: " + e.getRetryAfterSeconds());
} catch (LLMException e) {
    System.err.println("LLM error: " + e.getMessage());
}
```

## Full Configuration Options (`LLMConfig`)

| Option | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `apiKey` | String | - | API key for authentication (required) |
| `baseUrl` | String | Provider default | Custom base URL for API |
| `defaultModel` | String | null | Default model to use if not specified in request |
| `timeout` | Duration | 60s | Request timeout |
| `connectTimeout` | Duration | 10s | Connection timeout |
| `retryPolicy` | RetryPolicy | Default | Retry configuration (3 retries, exponential backoff) |
| `enableLogging` | boolean | false | Enable HTTP request/response logging |

---

*Related: [Getting Started](Getting-Started.md)*
