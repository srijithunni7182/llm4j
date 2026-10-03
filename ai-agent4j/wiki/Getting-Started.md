# Getting Started with AI Agent4J

This guide will help you get started with **AI Agent4J**, a Java library for building ReAct agents. It talks to Google Gemini, Anthropic Claude, Sarvam and Ollama (local models) through one interface, so you can start with any of them and switch later by changing one line.

## Prerequisites

- Java 17 or higher
- Maven 3.6 or higher
- An API key for the provider you want to use (Gemini, Claude or Sarvam), **or** a local [Ollama](../docs/OLLAMA.md) server, which needs no key

## Installation

Add the following dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>io.github.srijithunni7182</groupId>
    <artifactId>ai-agent4j</artifactId>
    <version>5.0</version>
</dependency>
```

### Gradle

Add this to your `build.gradle`:

```gradle
implementation 'io.github.srijithunni7182:ai-agent4j:5.0'
```

## Your First LLM Call

### Step 1: Pick a Provider and Get a Key

| Provider | Get a key | Environment variable | Example model |
|----------|-----------|----------------------|---------------|
| Google Gemini | [Google AI Studio](https://aistudio.google.com/app/apikey) | `GEMINI_API_KEY` | `gemini-2.5-flash` |
| Anthropic Claude | [Anthropic Console](https://console.anthropic.com/) | `ANTHROPIC_API_KEY` | `claude-opus-5-5`, `claude-haiku-4-5` |
| Sarvam | [Sarvam AI](https://www.sarvam.ai/) | `SARVAM_API_KEY` | see the [Sarvam guide](../docs/SARVAM.md) |
| Ollama (local) | none: [install Ollama](../docs/OLLAMA.md) | none | e.g. `gemma3` |

The variable names are only a convention: you pass the key to `LLMConfig` yourself, so use whatever name you like.

### Step 2: Set the Environment Variable

```bash
export GEMINI_API_KEY="your-api-key-here"
```

### Step 3: Create Your First Program

```java
import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.LLMClient;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.provider.google.GoogleProvider;

public class HelloLLM {
    public static void main(String[] args) {
        // 1. Configure the client
        LLMConfig config = LLMConfig.builder()
                .apiKey(System.getenv("GEMINI_API_KEY"))
                .defaultModel("gemini-2.5-flash")
                .build();
        
        // 2. Create client. To use another provider, swap this one line:
        //    new AnthropicProvider(config), new SarvamChatProvider(config) or new OllamaProvider(config)
        LLMClient client = new DefaultLLMClient(
            new GoogleProvider(config)
        );
        
        // 3. Build request
        LLMRequest request = LLMRequest.builder()
                .addUserMessage("Say hello in 5 different languages")
                .temperature(0.7)
                .maxTokens(500)
                .build();
        
        // 4. Get response
        LLMResponse response = client.chat(request);
        
        // 5. Print results
        System.out.println("Response: " + response.getContent());
        System.out.println("Tokens used: " + response.getTokenUsage().getTotalTokens());
        System.out.println("Model: " + response.getModel());
    }
}
```

### Step 4: Run It

```bash
mvn exec:java -Dexec.mainClass="HelloLLM"
```

## Next Steps

### Auto-Discover Models

With Gemini, the library can discover the available models for you:

```java
LLMConfig tempConfig = LLMConfig.builder()
        .apiKey(System.getenv("GEMINI_API_KEY"))
        .build();

GoogleProvider provider = new GoogleProvider(tempConfig);
String latestModel = provider.getFirstAvailableModel();  // e.g. "gemini-2.5-flash"

// Use the discovered model
LLMConfig config = LLMConfig.builder()
        .apiKey(System.getenv("GEMINI_API_KEY"))
        .defaultModel(latestModel)
        .build();
```

### Multi-Turn Conversations

```java
LLMRequest request = LLMRequest.builder()
        .addSystemMessage("You are a helpful coding assistant.")
        .addUserMessage("How do I reverse a string in Java?")
        .addAssistantMessage("You can use StringBuilder.reverse()...")
        .addUserMessage("Can you show me an example?")
        .build();
```

### Choosing a Model

Model names change often, so check each provider's own list. These are the ones used in this project's tests and examples:

| Model | Provider | Best For |
|-------|----------|----------|
| `gemini-2.5-flash` | Gemini | Fast, low-cost general use |
| `gemini-2.5-pro` | Gemini | Complex reasoning, long context |
| `claude-opus-5-5` | Claude | Hard reasoning and agent tasks |
| `claude-haiku-4-5` | Claude | Fast, low-cost general use |
| `gemma3` | Ollama | Local and private, with no API cost |

See [Providers and the Uniform Contract](Providers-and-the-Uniform-Contract.md) for what stays the same across providers, and how to switch.

### Build Your First Agent

```java
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.tools.CalculatorTool;

ReActAgent agent = ReActAgent.builder()
        .llmClient(client)
        .addTool(new CalculatorTool())
        .maxIterations(10)
        .build();

AgentResult result = agent.run("What is 15 * 234 + 567?");
System.out.println("Answer: " + result.getFinalAnswer());
```

### Use OpenAPI Tools

Automatically discover and use REST APIs:

```java
import io.github.llm4j.agent.tools.openapi.OpenAPITool;

OpenAPITool aviationTool = OpenAPITool.builder()
    .name("AviationStack")
    .specLocation("https://api.aviationstack.com/openapi.json")
    .apiKeyAuth("access_key", "YOUR_KEY")
    .build();

agent = ReActAgent.builder()
    .llmClient(client)
    .addTool(aviationTool)
    .build();
```

## Common Patterns

### Error Handling

```java
import io.github.llm4j.exception.*;

try {
    LLMResponse response = client.chat(request);
} catch (AuthenticationException e) {
    System.err.println("Invalid API key");
} catch (RateLimitException e) {
    System.err.println("Rate limited. Retry after: " + 
                       e.getRetryAfterSeconds() + "s");
} catch (InvalidRequestException e) {
    System.err.println("Bad request: " + e.getMessage());
} catch (LLMException e) {
    System.err.println("Error: " + e.getMessage());
}
```

### Custom Configuration

```java
import io.github.llm4j.config.RetryPolicy;
import java.time.Duration;

RetryPolicy customRetry = RetryPolicy.builder()
        .maxRetries(5)
        .backoffStrategy(RetryPolicy.BackoffStrategy.EXPONENTIAL)
        .initialBackoff(Duration.ofMillis(1000))
        .build();

LLMConfig config = LLMConfig.builder()
        .apiKey(apiKey)
        .retryPolicy(customRetry)
        .timeout(Duration.ofSeconds(90))
        .enableLogging(true)
        .build();
```

### Request Parameters

```java
LLMRequest request = LLMRequest.builder()
        .addUserMessage("Explain quantum computing")
        .model("gemini-2.5-pro")        // Specific model
        .temperature(0.7)                // 0-1, creativity
        .maxTokens(1000)                 // Max output tokens
        .topP(0.9)                       // Nucleus sampling
        .build();
```

## What's Next?

- **[ReAct Agent](ReAct-Agent.md)** - Build powerful AI agents
- **[Providers](Providers-and-the-Uniform-Contract.md)** - Switch between Gemini, Claude, Sarvam and Ollama
- **[Budgets and Rate Limits](Budgets-and-Rate-Limits.md)** - Cap spend before each call
- **[OpenAPI Tool](OpenAPI-Tool.md)** - Auto-discover APIs from specs
- **[Creating Custom Tools](Creating-Custom-Tools.md)** - Extend agent capabilities

## Troubleshooting

### Common Issues

**Issue**: `AuthenticationException`
- **Solution**: Check that your API key is correct and set in the environment (for example `GEMINI_API_KEY` or `ANTHROPIC_API_KEY`)

**Issue**: `RateLimitException`
- **Solution**: Implement exponential backoff or reduce request rate

**Issue**: `Content blocked by safety filters`
- **Solution**: Rephrase your prompt or adjust content

**Issue**: `NoClassDefFoundError`
- **Solution**: Ensure all dependencies are properly included in your `pom.xml`

## Need Help?

- Search [GitHub Issues](https://github.com/srijithunni7182/llm4j/issues)
- Ask in [Discussions](https://github.com/srijithunni7182/llm4j/discussions)
