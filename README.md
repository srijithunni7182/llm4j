# Gemini ReAct Java

**A production-ready Java client for Google Gemini with built-in ReAct Agents and Tooling.**

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-orange)](https://www.oracle.com/java/technologies/downloads/#java17)

`gemini-react-java` (formerly `llm4j`) is a flexible, configurable, and **comprehensively tested** Java library for interacting with Google Gemini through a clean, unified API. It features a robust **ReAct Agent** framework that allows you to build AI agents capable of using tools to solve complex problems.

> **Note**: This library is specialized for **Google Gemini**. We believe in honest, verified support—every feature is backed by comprehensive integration tests against real Gemini endpoints.

## Features

- **🤖 Google Gemini First**: Full integration with Gemini 1.5 Flash, Pro, and 2.x models.
- **🛠️ ReAct Agent Framework**: Build AI agents that can reason and use tools (Calculator, Web Search, etc.).
- **⚡ Robust Tooling**: Typed tool interface with JSON input parsing and error feedback loops.
- **🔄 Production Ready**: Automatic retries, error handling, and thread-safe design.
- **🧪 100% Tested**: Comprehensive integration test suite verifying real-world usage.

## Installation

### Maven

```xml
<dependency>
    <groupId>io.github.llm4j</groupId>
    <artifactId>gemini-react-java</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

### Gradle

```gradle
implementation 'io.github.llm4j:gemini-react-java:0.1.0-SNAPSHOT'
```

## Quick Start

### Google Gemini

```java
import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.LLMClient;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.provider.google.GoogleProvider;

public class GeminiExample {
    public static void main(String[] args) {
        // Configure the client
        LLMConfig config = LLMConfig.builder()
                .apiKey(System.getenv("GOOGLE_API_KEY"))
                .defaultModel("gemini-1.5-flash")  // or "gemini-1.5-pro"
                .build();
        
        // Create client with Google provider
        LLMClient client = new DefaultLLMClient(new GoogleProvider(config));
        
        // Build and send request
        LLMRequest request = LLMRequest.builder()
                .addUserMessage("What is the capital of France?")
                .temperature(0.7)
                .maxTokens(500)
                .build();
        
        LLMResponse response = client.chat(request);
        System.out.println(response.getContent());
        System.out.println("Tokens used: " + response.getTokenUsage().getTotalTokens());
    }
}
```

### Auto-Discover Models

```java
// The library can automatically discover available Gemini models
LLMConfig tempConfig = LLMConfig.builder()
        .apiKey(System.getenv("GOOGLE_API_KEY"))
        .build();

GoogleProvider provider = new GoogleProvider(tempConfig);
String latestModel = provider.getFirstAvailableModel();  // e.g. "gemini-2.5-flash"

// Use the discovered model
LLMConfig config = LLMConfig.builder()
        .apiKey(System.getenv("GOOGLE_API_KEY"))
        .defaultModel(latestModel)
        .build();
}
```

## ReAct Agent

The library includes a powerful ReAct (Reasoning and Acting) agent framework that enables LLMs to use tools through a loop of thought, action, and observation.

### Basic Agent Usage

```java
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.tools.CalculatorTool;
import io.github.llm4j.agent.tools.CurrentTimeTool;

// Create agent with tools
ReActAgent agent = ReActAgent.builder()
        .llmClient(client)
        .addTool(new CalculatorTool())
        .addTool(new CurrentTimeTool())
        .maxIterations(10)
        .temperature(0.7)
        .build();

// Run agent
AgentResult result = agent.run("What is (15 * 23) + 47?");
System.out.println(result.getFinalAnswer());

// Inspect reasoning steps
for (AgentResult.AgentStep step : result.getSteps()) {
    System.out.println("Thought: " + step.getThought());
    System.out.println("Action: " + step.getAction());
    System.out.println("Observation: " + step.getObservation());
}
```

### Built-in Tools

- **CalculatorTool**: Evaluate mathematical expressions
- **CurrentTimeTool**: Get current date and time
- **EchoTool**: Simple echo tool (useful for testing)

### 🌐 OpenAPI Support (New!)

The library now supports **dynamic tool generation** from OpenAPI/Swagger specifications. This allows your agents to automatically discover and use any REST API without writing manual tool code.

```java
// Create tool from OpenAPI spec (URL or file)
OpenAPITool aviationTool = OpenAPITool.builder()
    .name("AviationStack")
    .specLocation("https://api.aviationstack.com/openapi.json")
    .apiKeyAuth("access_key", System.getenv("AVIATION_STACK_API_KEY"))
    .build();

// Add to agent
ReActAgent agent = ReActAgent.builder()
    .llmClient(client)
    .addTool(aviationTool)
    .build();
```

See the [OpenAPI Tool Wiki](wiki/OpenAPI-Tool) for full documentation.

### Creating Custom Tools

```java
import io.github.llm4j.agent.Tool;

public class WebSearchTool implements Tool {
    @Override
    public String getName() {
        return "WebSearch";
    }
    
    @Override
    public String getDescription() {
        return "Search the web for information. Input should be a search query.";
    }
    
    @Override
    public String execute(String input) throws Exception {
        // Implement web search logic
        return searchWeb(input);
    }
}

// Use custom tool
agent = ReActAgent.builder()
        .llmClient(client)
        .addTool(new WebSearchTool())
        .build();
```

### Agent Configuration

```java
ReActAgent agent = ReActAgent.builder()
        .llmClient(client)
        .addTool(new CalculatorTool())
        .maxIterations(15)              // Max reasoning steps
        .temperature(0.7)                // LLM temperature
        .systemPrompt(customPrompt)      // Custom prompt template
        .build();
```

## Advanced Configuration

```java

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

### Custom Base URL

```java
LLMConfig config = LLMConfig.builder()
        .apiKey("your-api-key")
        .baseUrl("https://generativelanguage.googleapis.com/v1")
        .defaultModel("gemini-1.5-flash")
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
