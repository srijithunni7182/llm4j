# Provider Guides

Detailed guides for each LLM provider supported by LLM4J.

## Supported Providers

- [OpenAI](#openai-gpt)
- [Anthropic](#anthropic-claude)
- [Google](#google-gemini)

---

## OpenAI (GPT)

### Setup

1. Get API key from https://platform.openai.com/api-keys
2. Set environment variable: `export OPENAI_API_KEY="your-key"`

### Basic Usage

```java
import io.github.llm4j.provider.openai.OpenAIProvider;

LLMConfig config = LLMConfig.builder()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .defaultModel("gpt-4")
        .build();

LLMClient client = new DefaultLLMClient(new OpenAIProvider(config));
```

### Available Models

| Model | Description | Max Tokens | Best For |
|-------|-------------|------------|----------|
| `gpt-4` | Most capable | 8,192 | Complex reasoning |
| `gpt-4-turbo` | Faster, cheaper | 128,000 | Long context |
| `gpt-3.5-turbo` | Fast and efficient | 16,385 | General use |

### Model-Specific Parameters

```java
LLMRequest request = LLMRequest.builder()
        .addUserMessage("Explain quantum computing")
        .model("gpt-4")
        .temperature(0.7)           // 0-2, default 1
        .maxTokens(500)             // Max completion tokens
        .topP(0.9)                  // Nucleus sampling
        .addParameter("presence_penalty", 0.6)  // -2.0 to 2.0
        .addParameter("frequency_penalty", 0.5)  // -2.0 to 2.0
        .build();
```

### Custom Base URL

For Azure OpenAI or compatible APIs:

```java
LLMConfig config = LLMConfig.builder()
        .apiKey(apiKey)
        .baseUrl("https://your-resource.openai.azure.com/openai/deployments/your-deployment")
        .defaultModel("gpt-4")
        .build();
```

### Best Practices

- Use `gpt-3.5-turbo` for most tasks (fast and cheap)
- Use `gpt-4` for complex reasoning
- Set `temperature=0` for deterministic outputs
- Use `max_tokens` to control costs

---

## Anthropic (Claude)

### Setup

1. Get API key from https://console.anthropic.com/
2. Set environment variable: `export ANTHROPIC_API_KEY="your-key"`

### Basic Usage

```java
import io.github.llm4j.provider.anthropic.AnthropicProvider;

LLMConfig config = LLMConfig.builder()
        .apiKey(System.getenv("ANTHROPIC_API_KEY"))
        .defaultModel("claude-3-opus-20240229")
        .build();

LLMClient client = new DefaultLLMClient(new AnthropicProvider(config));
```

### Available Models

| Model | Description | Max Tokens | Best For |
|-------|-------------|------------|----------|
| `claude-3-opus-20240229` | Most capable | 200,000 | Complex tasks |
| `claude-3-sonnet-20240229` | Balanced | 200,000 | General use |
| `claude-3-haiku-20240307` | Fastest | 200,000 | Simple tasks |

### Important Notes

**System Messages**: Anthropic requires system messages to be separate:

```java
// ✅ Correct
LLMRequest request = LLMRequest.builder()
        .addSystemMessage("You are a helpful assistant")
        .addUserMessage("Hello")
        .build();

// The library handles this automatically
```

**Max Tokens Required**: Always specify `maxTokens`:

```java
LLMRequest request = LLMRequest.builder()
        .addUserMessage("Write a poem")
        .maxTokens(1024)  // Required!
        .build();
```

### Model-Specific Parameters

```java
LLMRequest request = LLMRequest.builder()
        .addUserMessage("Explain AI")
        .model("claude-3-opus-20240229")
        .temperature(0.7)           // 0-1, default 1
        .maxTokens(1024)            // Required
        .topP(0.9)                  // 0-1
        .addParameter("top_k", 40)  // Anthropic specific
        .build();
```

### Best Practices

- Always set `maxTokens` (required by Anthropic)
- Use haiku for simple, fast tasks
- Use opus for complex reasoning
- Claude excels at longer contexts

---

## Google (Gemini)

### Setup

1. Get API key from https://makersuite.google.com/app/apikey
2. Set environment variable: `export GOOGLE_API_KEY="your-key"`

### Basic Usage

```java
import io.github.llm4j.provider.google.GoogleProvider;

LLMConfig config = LLMConfig.builder()
        .apiKey(System.getenv("GOOGLE_API_KEY"))
        .defaultModel("gemini-pro")
        .build();

LLMClient client = new DefaultLLMClient(new GoogleProvider(config));
```

### Available Models

| Model | Description | Max Tokens | Best For |
|-------|-------------|------------|----------|
| `gemini-pro` | Text generation | 32,760 | General use |
| `gemini-pro-vision` | Multimodal | 16,384 | Image + text |

### Important Notes

**Role Mapping**: Google uses different role names:
- `assistant` → `model` (automatically handled)
- `user` → `user`

**API Endpoint**: Google uses query parameters for API key:

```java
// The library handles this automatically
// URL: https://generativelanguage.googleapis.com/v1beta/models/gemini-pro:generateContent?key=YOUR_KEY
```

### Model-Specific Parameters

```java
LLMRequest request = LLMRequest.builder()
        .addUserMessage("Explain machine learning")
        .model("gemini-pro")
        .temperature(0.9)                    // 0-1
        .maxTokens(2048)                     // maxOutputTokens
        .topP(0.95)                          // 0-1
        .addParameter("topK", 40)            // Google specific
        .addParameter("candidateCount", 1)    // Number of responses
        .build();
```

### Safety Settings

```java
// Google has content safety filters
// Blocked content returns an error
// Check finishReason for safety blocks
```

### Best Practices

- Free tier has rate limits
- Good for general-purpose tasks
- Strong multimodal capabilities
- Watch for safety filter blocks

---

## Comparison Chart

| Feature | OpenAI | Anthropic | Google |
|---------|--------|-----------|--------|
| **Context Window** | 8K-128K | 200K | 32K |
| **Streaming** | ⏳ Planned | ⏳ Planned | ⏳ Planned |
| **Function Calling** | ❌ | ❌ | ❌ |
| **Cost** | $$ | $$$ | $ |
| **Speed** | Fast | Medium | Fast |
| **Quality** | High | Very High | High |

---

## Switching Providers

Easy to switch between providers:

```java
// OpenAI
LLMConfig openaiConfig = LLMConfig.builder()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .defaultModel("gpt-4")
        .build();
LLMClient openaiClient = new DefaultLLMClient(
    new OpenAIProvider(openaiConfig)
);

// Anthropic
LLMConfig anthropicConfig = LLMConfig.builder()
        .apiKey(System.getenv("ANTHROPIC_API_KEY"))
        .defaultModel("claude-3-opus-20240229")
        .build();
LLMClient anthropicClient = new DefaultLLMClient(
    new AnthropicProvider(anthropicConfig)
);

// Same request works with both
LLMRequest request = LLMRequest.builder()
        .addUserMessage("Hello!")
        .build();

LLMResponse response1 = openaiClient.chat(request);
LLMResponse response2 = anthropicClient.chat(request);
```

## Cost Optimization

### OpenAI
- Use `gpt-3.5-turbo` instead of `gpt-4` when possible
- Set `max_tokens` to limit output
- Cache responses when appropriate

### Anthropic
- Use Haiku for simple tasks
- Leverage long context for fewer API calls
- Always set `maxTokens` appropriately

### Google
- Free tier available for testing
- Good for budget-conscious projects
- Watch rate limits

## Error Handling

All providers throw the same exceptions:

```java
try {
    LLMResponse response = client.chat(request);
} catch (AuthenticationException e) {
    // Invalid API key
} catch (RateLimitException e) {
    // Rate limit hit
} catch (InvalidRequestException e) {
    // Bad request parameters
} catch (ProviderException e) {
    // Provider-specific error
}
```

## Next Steps

- **[Configuration Guide](Configuration-Guide)** - Advanced configuration
- **[Examples](Examples)** - Code examples for each provider
- **[API Reference](API-Reference)** - Complete API docs
