# Welcome to LLM4J

LLM4J is a flexible, configurable, and well-tested Java library for interacting with multiple Large Language Model providers through a unified API.

## Quick Links

- **[Getting Started](Getting-Started)** - Installation and first steps
- **[Configuration Guide](Configuration-Guide)** - Detailed configuration options
- **[Provider Guides](Provider-Guides)** - OpenAI, Anthropic, Google Gemini
- **[ReAct Agent](ReAct-Agent)** - Building AI agents with tools
- **[Custom Tools](Creating-Custom-Tools)** - Extending agent capabilities
- **[Examples](Examples)** - Code examples and use cases
- **[API Reference](API-Reference)** - Complete API documentation

## Features Overview

### 🔌 Multiple Provider Support
- OpenAI (GPT-3.5, GPT-4)
- Anthropic (Claude 3)
- Google (Gemini Pro)
- Easy to add new providers

### 🤖 ReAct Agent Framework
- Build AI agents that can use tools
- Reasoning and action loop
- Pluggable tool system
- Built-in tools: Calculator, Time, Echo

### 🎯 Unified API
- Single interface for all providers
- Consistent request/response format
- Provider-specific features accessible

### ⚙️ Highly Configurable
- Builder pattern for clean API
- Retry policies with backoff strategies
- Timeout management
- Custom prompts

### 🔄 Production Ready
- Comprehensive error handling
- Automatic retries
- Thread-safe immutable objects
- Extensive test coverage (47 tests)

## Architecture

```
User Code
    ↓
LLMClient Interface
    ↓
DefaultLLMClient
    ↓
LLMProvider Interface
    ↓
┌─────────┬──────────────┬──────────┐
│ OpenAI  │  Anthropic   │  Google  │
└─────────┴──────────────┴──────────┘
```

## Project Structure

```
llm4j/
├── src/main/java/io/github/llm4j/
│   ├── LLMClient.java              # Main interface
│   ├── model/                       # Request/Response models
│   ├── provider/                    # Provider implementations
│   ├── agent/                       # ReAct agent framework
│   ├── config/                      # Configuration classes
│   ├── http/                        # HTTP client wrapper
│   └── exception/                   # Exception hierarchy
└── src/test/java/                   # Test suite
```

## Requirements

- Java 17 or higher
- Maven 3.6+ or Gradle 7+

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

## Simple Example

```java
// Configure client
LLMConfig config = LLMConfig.builder()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .defaultModel("gpt-3.5-turbo")
        .build();

LLMClient client = new DefaultLLMClient(new OpenAIProvider(config));

// Make request
LLMRequest request = LLMRequest.builder()
        .addUserMessage("What is the capital of France?")
        .build();

LLMResponse response = client.chat(request);
System.out.println(response.getContent());
```

## Agent Example

```java
// Create agent with tools
ReActAgent agent = ReActAgent.builder()
        .llmClient(client)
        .addTool(new CalculatorTool())
        .addTool(new CurrentTimeTool())
        .build();

// Run agent
AgentResult result = agent.run("What is (15 * 23) + 47?");
System.out.println(result.getFinalAnswer());
```

## Contributing

Contributions are welcome! Please see our [Contributing Guidelines](Contributing) for details.

## Support

- **Issues**: [GitHub Issues](https://github.com/srijithunni7182/llm4j/issues)
- **Discussions**: [GitHub Discussions](https://github.com/srijithunni7182/llm4j/discussions)

## License

This project is licensed under the MIT License.
