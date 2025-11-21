# LLM4J

A flexible, configurable, and well-tested Java library for interacting with multiple Large Language Model (LLM) providers through a unified API.

## Features

- **🔌 Multiple Provider Support**: OpenAI, Anthropic Claude, Google Gemini
- **🎯 Unified API**: Single interface for all providers
- **⚙️ Highly Configurable**: Flexible configuration with builder pattern
- **🔄 Retry Logic**: Built-in retry mechanism with configurable backoff strategies
- **🧪 Well Tested**: Comprehensive test suite with 70%+ code coverage
- **🔒 Thread-Safe**: Immutable request/response objects
- **📝 Clean Code**: Follows SOLID principles and best practices

## Installation

### Maven

```xml
<dependency>
    <groupId>io.github.llm4j</groupId>
    <artifactId>llm4j</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

### Gradle

```gradle
implementation 'io.github.llm4j:llm4j:0.1.0-SNAPSHOT'
```

## Quick Start

### OpenAI (ChatGPT)

```java
import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.LLMClient;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.provider.openai.OpenAIProvider;

public class OpenAIExample {
    public static void main(String[] args) {
        // Configure the client
        LLMConfig config = LLMConfig.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .defaultModel("gpt-4")
                .build();
        
        // Create client with OpenAI provider
        LLMClient client = new DefaultLLMClient(new OpenAIProvider(config));
        
        // Build and send request
        LLMRequest request = LLMRequest.builder()
                .addUserMessage("What is the capital of France?")
                .temperature(0.7)
                .maxTokens(100)
                .build();
        
        LLMResponse response = client.chat(request);
        System.out.println(response.getContent());
        System.out.println("Tokens used: " + response.getTokenUsage().getTotalTokens());
    }
}
```

### Anthropic (Claude)

```java
import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.LLMClient;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.provider.anthropic.AnthropicProvider;

public class AnthropicExample {
    public static void main(String[] args) {
        LLMConfig config = LLMConfig.builder()
                .apiKey(System.getenv("ANTHROPIC_API_KEY"))
                .defaultModel("claude-3-opus-20240229")
                .build();
        
        LLMClient client = new DefaultLLMClient(new AnthropicProvider(config));
        
        LLMRequest request = LLMRequest.builder()
                .addSystemMessage("You are a helpful coding assistant.")
                .addUserMessage("Write a hello world program in Python")
                .maxTokens(500)
                .build();
        
        LLMResponse response = client.chat(request);
        System.out.println(response.getContent());
    }
}
```

### Google (Gemini)

```java
import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.LLMClient;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.provider.google.GoogleProvider;

public class GoogleExample {
    public static void main(String[] args) {
        LLMConfig config = LLMConfig.builder()
                .apiKey(System.getenv("GOOGLE_API_KEY"))
                .defaultModel("gemini-pro")
                .build();
        
        LLMClient client = new DefaultLLMClient(new GoogleProvider(config));
        
        LLMRequest request = LLMRequest.builder()
                .addUserMessage("Explain quantum computing in simple terms")
                .build();
        
        LLMResponse response = client.chat(request);
        System.out.println(response.getContent());
    }
}
```

## Advanced Configuration

### Custom Retry Policy

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
        .enableLogging(true)
        .build();
```

### Multi-Turn Conversations

```java
LLMRequest request = LLMRequest.builder()
        .addSystemMessage("You are a friendly chatbot.")
        .addUserMessage("Hello! What's your name?")
        .addAssistantMessage("Hi! I'm Claude, an AI assistant. How can I help you today?")
        .addUserMessage("Can you help me write a poem about the ocean?")
        .build();

LLMResponse response = client.chat(request);
```

### Custom Base URL (for OpenAI-compatible APIs)

```java
LLMConfig config = LLMConfig.builder()
        .apiKey("your-api-key")
        .baseUrl("https://your-custom-endpoint.com/v1")
        .defaultModel("custom-model")
        .build();
```

## Error Handling

The library provides specific exception types for different error scenarios:

```java
import io.github.llm4j.exception.*;

try {
    LLMResponse response = client.chat(request);
} catch (AuthenticationException e) {
    // Invalid API key
    System.err.println("Authentication failed: " + e.getMessage());
} catch (RateLimitException e) {
    // Rate limit exceeded
    System.err.println("Rate limited. Retry after: " + e.getRetryAfterSeconds());
} catch (InvalidRequestException e) {
    // Bad request (invalid parameters)
    System.err.println("Invalid request: " + e.getMessage());
} catch (ProviderException e) {
    // Provider-specific error
    System.err.println("Provider error: " + e.getMessage());
} catch (LLMException e) {
    // Generic LLM error
    System.err.println("LLM error: " + e.getMessage());
}
```

## Configuration Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `apiKey` | String | - | API key for authentication (required) |
| `baseUrl` | String | Provider default | Custom base URL for API |
| `defaultModel` | String | null | Default model to use if not specified in request |
| `timeout` | Duration | 60s | Request timeout |
| `connectTimeout` | Duration | 10s | Connection timeout |
| `retryPolicy` | RetryPolicy | Default | Retry configuration |
| `enableLogging` | boolean | false | Enable HTTP request/response logging |

## Building from Source

### Requirements

- Java 17 or higher
- Maven 3.6+

### Build

```bash
mvn clean install
```

### Run Tests

```bash
mvn test
```

### Generate Coverage Report

```bash
mvn jacoco:report
```

Coverage report will be available at `target/site/jacoco/index.html`.

## Architecture

```
┌─────────────┐
│ User Code   │
└─────┬───────┘
      │
      ▼
┌─────────────┐
│ LLMClient   │ (Interface)
└─────┬───────┘
      │
      ▼
┌──────────────────┐
│ DefaultLLMClient │
└─────┬────────────┘
      │
      ▼
┌─────────────┐
│ LLMProvider │ (Interface)
└─────┬───────┘
      │
      ├─────► OpenAIProvider
      ├─────► AnthropicProvider
      └─────► GoogleProvider
```

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

### Adding a New Provider

1. Implement the `LLMProvider` interface
2. Create provider-specific request/response transformations
3. Add comprehensive tests
4. Update documentation

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Support

For issues and questions, please use the [GitHub Issues](https://github.com/srijithunni7182/llm4j/issues) page.

## Roadmap

- [ ] Streaming support (Server-Sent Events)
- [ ] Function calling / tool use support
- [ ] Additional providers (Cohere, Together AI, etc.)
- [ ] Embeddings API support
- [ ] Token counting utilities
- [ ] Async API support
- [ ] Spring Boot starter

## Acknowledgments

Built with:
- [OkHttp](https://square.github.io/okhttp/) - HTTP client
- [Jackson](https://github.com/FasterXML/jackson) - JSON processing
- [SLF4J](http://www.slf4j.org/) - Logging facade
- [JUnit 5](https://junit.org/junit5/) - Testing framework
