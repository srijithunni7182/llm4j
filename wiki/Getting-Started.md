# Getting Started with Gemini ReAct Java

This guide will help you get started with `gemini-react-java` and Google Gemini in minutes.

## Prerequisites

- Java 17 or higher
- Maven 3.6+ or Gradle 7+
- Google API key ([Get one here](https://makersuite.google.com/app/apikey))

## Installation

### Maven

Add this dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>io.github.llm4j</groupId>
    <artifactId>gemini-react-java</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

### Gradle

Add this to your `build.gradle`:

```gradle
implementation 'io.github.llm4j:gemini-react-java:0.1.0-SNAPSHOT'
```

## Your First LLM Call

### Step 1: Get an API Key

Get your Google API key from: https://makersuite.google.com/app/apikey

### Step 2: Set Environment Variable

```bash
export GOOGLE_API_KEY="your-api-key-here"
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
                .apiKey(System.getenv("GOOGLE_API_KEY"))
                .defaultModel("gemini-1.5-flash")
                .build();
        
        // 2. Create client
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

The library can automatically discover the latest available Gemini models:

```java
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

### Available Models

| Model | Description | Best For |
|-------|-------------|----------|
| `gemini-1.5-flash` | Fast, efficient | General use, quick responses |
| `gemini-1.5-pro` | More capable | Complex reasoning, longer context |
| `gemini-2.5-flash` | Latest, fastest | Newest features, optimal performance |

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

### Request Parameters

```java
LLMRequest request = LLMRequest.builder()
        .addUserMessage("Explain quantum computing")
        .model("gemini-1.5-pro")        // Specific model
        .temperature(0.7)                // 0-1, creativity
        .maxTokens(1000)                 // Max output tokens
        .topP(0.9)                       // Nucleus sampling
        .build();
```

## What's Next?

- **[ReAct Agent](ReAct-Agent)** - Build powerful AI agents
- **[Creating Custom Tools](Creating-Custom-Tools)** - Extend agent capabilities

## Troubleshooting

### Common Issues

**Issue**: `AuthenticationException`
- **Solution**: Check that your `GOOGLE_API_KEY` is correct and set in the environment

**Issue**: `RateLimitException`
- **Solution**: Implement exponential backoff or reduce request rate

**Issue**: `Content blocked by safety filters`
- **Solution**: Rephrase your prompt or adjust content

**Issue**: `NoClassDefFoundError`
- **Solution**: Ensure all dependencies are properly included in your `pom.xml`

## Need Help?

- Search [GitHub Issues](https://github.com/srijithunni7182/llm4j/issues)
- Ask in [Discussions](https://github.com/srijithunni7182/llm4j/discussions)
