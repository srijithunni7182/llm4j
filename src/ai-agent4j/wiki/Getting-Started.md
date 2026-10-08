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

The variable names are only a convention: you pass the key to `LLMConfig` yourself, so use whatever name or source you like (see Step 2).

<a id="set-up-your-api-key"></a>

### Step 2: Set Up Your API Key

Pick **one** of the ways below. They all end the same way: `LLMConfig` gets the key, and the provider uses it on each request.
You choose where keys live; llm4j never picks a location for you.

**A. An environment variable** (quickest for a first run)

```bash
export GEMINI_API_KEY="your-api-key-here"
```

```java
LLMConfig config = LLMConfig.builder()
        .apiKey(System.getenv("GEMINI_API_KEY"))
        .defaultModel("gemini-2.5-flash")
        .build();
```

**B. Your own vault or configuration service** (HashiCorp Vault, AWS or GCP secret managers, Kubernetes secrets, ...). Fetch the value
however your application already does, and hand it over in memory. Two ways:

```java
// 1. Put the value in an in-memory store once, and pass a reference
SecretStore secrets = new InMemorySecretStore();
secrets.put("GEMINI_API_KEY", myVault.read("llm/gemini"));      // any String you already have

LLMConfig config = LLMConfig.builder()
        .apiKey(SecretRef.of(secrets, "GEMINI_API_KEY"))        // a reference, not the key
        .defaultModel("gemini-2.5-flash")
        .build();

// To rotate: secrets.put("GEMINI_API_KEY", newValue). The next request uses it; nothing is rebuilt.
```

```java
// 2. Or let llm4j ask your vault each time it needs the key: implement SecretStore over it
class VaultSecrets implements SecretStore {
    public String resolve(String name)             { return myVault.read("llm/" + name); }   // called per request
    public boolean contains(String name)           { return myVault.exists("llm/" + name); }
    public Set<String> names()                     { return myVault.list("llm/"); }
    public Optional<SecretMetadata> metadata(String name) { return Optional.empty(); }
}

LLMConfig config = LLMConfig.builder().apiKey(SecretRef.of(new VaultSecrets(), "GEMINI_API_KEY")).build();
```

**C. An encrypted file** (when you have no vault). The file holds your keys encrypted with AES-256-GCM. **You choose the path and how the
master key is supplied** (a passphrase, a key file, or an environment variable you name), and you keep the file's directory
access-controlled. Nothing is stored anywhere you didn't choose.

```java
SecretStore secrets = EncryptedFileSecretStore.openOrCreate(
        Path.of("/etc/myapp/secrets.store"),
        MasterKey.fromEnv("MYAPP_MASTER_KEY"));                 // or MasterKey.fromFile(...), MasterKey.of(passphrase)

secrets.put("GEMINI_API_KEY", key);                             // once, e.g. from an admin task; later runs only read it

LLMConfig config = LLMConfig.builder().apiKey(SecretRef.of(secrets, "GEMINI_API_KEY")).build();
```

With Loom installed, `weave secrets` does the same from a terminal (the value is typed without echo, never an argument):

```bash
weave secrets create --secrets keys.store
weave secrets set GEMINI_API_KEY --secrets keys.store --allow-host generativelanguage.googleapis.com
```

**Good to know**

- You can mix modes: `ChainedSecretStore.of(vaultStore, encryptedFile, EnvSecretStore.system())` uses the first store that has the name.
- `allowedHosts` (via `SecretMetadata.allowing("host")` when you `put`) stops a key being sent anywhere but its provider.
- The same `SecretRef` works for every provider (`GoogleProvider`, `AnthropicProvider`, `SarvamChatProvider`), embeddings and tools such as search and OpenAPI.
- A key must never be committed to your repository or logged. If one ever is, revoke it at the provider.

The full API, and what the encrypted file protects, is in the [Secret Store](Secret-Store.md) guide.

### Step 3: Create Your First Program

This uses an environment variable; swap in any option from Step 2.

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
    .apiKeyAuth("access_key", SecretRef.of(secrets, "AVIATION_KEY"))   // fetched on each request
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
- **Solution**: Check that your API key is correct and reachable: set in the environment, present in your `SecretStore`, and, if the secret was stored with `allowedHosts`, allowed for the provider's host. The exception names the secret, never its value.

**Issue**: `RateLimitException`
- **Solution**: Implement exponential backoff or reduce request rate

**Issue**: `Content blocked by safety filters`
- **Solution**: Rephrase your prompt or adjust content

**Issue**: `NoClassDefFoundError`
- **Solution**: Ensure all dependencies are properly included in your `pom.xml`

## Need Help?

- Search [GitHub Issues](https://github.com/srijithunni7182/llm4j/issues)
- Ask in [Discussions](https://github.com/srijithunni7182/llm4j/discussions)
