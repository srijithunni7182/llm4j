# Ollama (Local Models) Integration Guide

`ai-agent4j` provides native support for [Ollama](https://ollama.com/), enabling you to run state-of-the-art open-weight models like **Gemma**, **Llama**, and **Mistral** entirely on your local machine.

## Prerequisites

1.  **Install Ollama**: Follow the instructions at [ollama.com](https://ollama.com/download).
2.  **Download a Model**: For the best results with ReAct agents, we recommend the latest Gemma models.
    ```bash
    ollama run gemma3
    ```

## Basic Configuration

To use Ollama, configure the `LLMConfig` with your local server's base URL (usually `http://localhost:11434`).

```java
import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.LLMClient;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.provider.ollama.OllamaProvider;

public class LocalGemmaExample {
    public static void main(String[] args) {
        LLMConfig config = LLMConfig.builder()
                .baseUrl("http://localhost:11434")
                .defaultModel("gemma3")
                .build();

        LLMClient client = new DefaultLLMClient(new OllamaProvider(config));
        
        // Use the client as normal...
    }
}
```

## Using with ReAct Agents

When using smaller local models (like Gemma 3 4B), the reasoning loop can sometimes hit edge cases. `ai-agent4j` includes optimizations to make these more reliable.

### Optimized Agent Setup

```java
ReActAgent agent = ReActAgent.builder()
        .llmClient(client)
        .addTool(new CalculatorTool())
        .addTool(new CurrentTimeTool())
        .maxIterations(5)  // Recommended for local models to prevent infinite loops
        .temperature(0.1)  // Lower temperature increases reasoning stability
        .build();
```

### Performance Tips for Local Models

1.  **Lower Temperature**: Set `temperature(0.1)` or `0.0` for more deterministic JSON outputs.
2.  **Explicit System Prompts**: The core library uses a ReAct prompt optimized for JSON. If the model struggles, ensure you are using the most recent version of `ai-agent4j` which includes enhanced instructions for the `final_answer` field.
3.  **Tool Registration**: Always ensure that any tool cited in your prompt is explicitly added to the agent builder to avoid "Unknown tool" errors.

## Advanced: Customizing the Ollama Provider

The `OllamaProvider` supports standard Chat API features including:
- **Streaming**: Real-time token output.
- **Model Parameters**: Passing custom options like `num_predict`, `top_k`, etc., via `LLMRequest`.

---

*For more information on the ReAct framework, see the [core documentation](../README.md).*
