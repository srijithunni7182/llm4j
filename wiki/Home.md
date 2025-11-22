# Welcome to LLM4J

LLM4J is a flexible, configurable, and **comprehensively tested** Java library for interacting with Google Gemini through a clean, unified API.

> **Note**: This library currently supports **Google Gemini only**. We believe in honest, verified support—every feature is backed by comprehensive integration tests.

## Quick Links

- **[Getting Started](Getting-Started)** - Installation and first steps
- **[ReAct Agent](ReAct-Agent)** - Building AI agents with tools
- **[Custom Tools](Creating-Custom-Tools)** - Extending agent capabilities

## Features Overview

### 🤖 Google Gemini Support
- Full integration with Gemini 1.5 and 2.x models
- Auto-discovery of latest available models
- Comprehensive integration test coverage

### 🛠️ ReAct Agent Framework
- Build AI agents that can use tools
- Reasoning and action loop
- Pluggable tool system
- Built-in tools: Calculator, Time, Echo

### 🎯 Clean API
- Simple, intuitive interface
- Consistent request/response format
- Builder pattern for configuration

### ⚙️ Highly Configurable
- Builder pattern for clean API
- Retry policies with backoff strategies
- Timeout management
- Custom prompts

### 🔄 Production Ready
- Comprehensive error handling
- Automatic retries
- Thread-safe immutable objects
- 100% integration test coverage

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
GoogleProvider
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
        .apiKey(System.getenv("GOOGLE_API_KEY"))
        .defaultModel("gemini-1.5-flash")
        .build();

LLMClient client = new DefaultLLMClient(new GoogleProvider(config));

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
