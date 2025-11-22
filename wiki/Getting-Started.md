# Getting Started with LLM4J

This guide will help you get started with LLM4J in minutes.

## Prerequisites

- Java 17 or higher
- Maven 3.6+ or Gradle 7+
- API key from at least one LLM provider (OpenAI, Anthropic, or Google)

## Installation

### Maven

Add this dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>io.github.llm4j</groupId>
    <artifactId>llm4j</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

### Gradle

Add this to your `build.gradle`:

```gradle
implementation 'io.github.llm4j:llm4j:0.1.0-SNAPSHOT'
```

## Your First LLM Call

### Step 1: Get an API Key

Obtain an API key from your chosen provider:
- **OpenAI**: https://platform.openai.com/api-keys
- **Anthropic**: https://console.anthropic.com/
- **Google**: https://makersuite.google.com/app/apikey

### Step 2: Set Environment Variable

```bash
export OPENAI_API_KEY="your-api-key-here"
```

### Step 3: Create Your First Program

```java
import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.LLMClient;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.provider.openai.OpenAIProvider;

public class HelloLLM {
    public static void main(String[] args) {
        // 1. Configure the client
        LLMConfig config = LLMConfig.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .defaultModel("gpt-3.5-turbo")
                .build();
        
        // 2. Create client
        LLMClient client = new DefaultLLMClient(
            new OpenAIProvider(config)
        );
        
        // 3. Build request
        LLMRequest request = LLMRequest.builder()
                .addUserMessage("Say hello in 5 different languages")
                .temperature(0.7)
                .maxTokens(200)
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

### Multi-Turn Conversations

```java
LLMRequest request = LLMRequest.builder()
        .addSystemMessage("You are a helpful coding assistant.")
        .addUserMessage("How do I reverse a string in Java?")
        .addAssistantMessage("You can use StringBuilder.reverse()...")
        .addUserMessage("Can you show me an example?")
        .build();
```

### Try Different Providers

#### Anthropic (Claude)

```java
import io.github.llm4j.provider.anthropic.AnthropicProvider;

LLMConfig config = LLMConfig.builder()
        .apiKey(System.getenv("ANTHROPIC_API_KEY"))
        .defaultModel("claude-3-opus-20240229")
        .build();

LLMClient client = new DefaultLLMClient(
    new AnthropicProvider(config)
);
```

#### Google (Gemini)

```java
import io.github.llm4j.provider.google.GoogleProvider;

LLMConfig config = LLMConfig.builder()
        .apiKey(System.getenv("GOOGLE_API_KEY"))
        .defaultModel("gemini-pro")
        .build();

LLMClient client = new DefaultLLMClient(
    new GoogleProvider(config)
);
```

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

## What's Next?

- **[Configuration Guide](Configuration-Guide)** - Learn about all configuration options
- **[Provider Guides](Provider-Guides)** - Detailed guides for each provider
- **[ReAct Agent](ReAct-Agent)** - Build powerful AI agents
- **[Examples](Examples)** - More code examples

## Troubleshooting

### Common Issues

**Issue**: `AuthenticationException`
- **Solution**: Check that your API key is correct and set in the environment

**Issue**: `RateLimitException`
- **Solution**: Implement exponential backoff or reduce request rate

**Issue**: `NoClassDefFoundError`
- **Solution**: Ensure all dependencies are properly included

## Need Help?

- Check the [FAQ](FAQ)
- Search [GitHub Issues](https://github.com/srijithunni7182/llm4j/issues)
- Ask in [Discussions](https://github.com/srijithunni7182/llm4j/discussions)
