# ReAct Agent Guide (Reasoning and Acting)

The ReAct framework enables Large Language Models to solve complex problems by iterating through a loop of **Thought**, **Action**, and **Observation**. This "Reasoning and Acting" paradigm allows the agent to use external tools, observe their results, and refine its plan until a final answer is found.

## Core Loop
1.  **Thought**: The agent analyzes the current state and decides what to do next.
2.  **Action**: The agent selects a registered tool and provides the necessary input.
3.  **Observation**: The system executes the tool and provides the output back to the agent.

## Basic Usage

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

## Built-in Tools

`ai-agent4j` comes with a suite of standard tools to get you started:

- **CalculatorTool**: Evaluate mathematical expressions.
- **DateTimeTool**: Get current date and time (RFC 1123 format).
- **SerpApiSearchTool**: High-quality web search using SerpAPI.
- **DuckDuckGoSearchTool**: Free web search fallback using DuckDuckGo.
- **FallbackSearchTool**: Chained search implementation for high reliability.
- **CachedSearchTool**: Static caching wrapper to reduce API usage across agents.
- **GraphQueryTool**: Query Knowledge Graphs for entities and relationships.
- **GraphExtractionTool**: Extract structured knowledge triples from text using LLM.
- **EchoTool**: Simple echo tool (useful for testing).

## Configuration Options

You can fine-tune the agent's behavior via the builder:

```java
ReActAgent agent = ReActAgent.builder()
        .llmClient(client)
        .addTool(new CalculatorTool())
        .maxIterations(15)              // Max reasoning steps before giving up
        .temperature(0.7)               // LLM temperature for variety vs precision
        .systemPrompt(customPrompt)     // Custom prompt template
        .build();
```

---

*Related: [Creating Custom Tools](Creating-Custom-Tools.md)*
