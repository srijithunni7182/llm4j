# ReAct Agent Guide

The ReAct (Reasoning and Acting) agent framework enables LLMs to use tools through an iterative loop of thought, action, and observation.

## What is ReAct?

ReAct is a paradigm where an LLM:
1. **Thinks** about what to do next
2. **Acts** by calling a tool
3. **Observes** the result
4. Repeats until reaching a final answer

This enables LLMs to solve complex problems by breaking them down into steps and using tools for capabilities they don't have built-in.

## Basic Usage

### Creating an Agent

```java
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.tools.CalculatorTool;
import io.github.llm4j.agent.tools.CurrentTimeTool;

// Create agent with tools
ReActAgent agent = ReActAgent.builder()
        .llmClient(client)                  // Your configured LLM client
        .addTool(new CalculatorTool())      // Add tools
        .addTool(new CurrentTimeTool())
        .maxIterations(10)                  // Max reasoning steps
        .temperature(0.7)                   // LLM temperature
        .build();
```

### Running the Agent

```java
AgentResult result = agent.run("What is (15 * 23) + 47?");

System.out.println("Answer: " + result.getFinalAnswer());
System.out.println("Steps taken: " + result.getSteps().size());
System.out.println("Completed: " + result.isCompleted());
```

### Inspecting Agent Reasoning

```java
for (AgentResult.AgentStep step : result.getSteps()) {
    System.out.println("Thought: " + step.getThought());
    System.out.println("Action: " + step.getAction());
    System.out.println("Action Input: " + step.getActionInput());
    System.out.println("Observation: " + step.getObservation());
    System.out.println("---");
}
```

## Built-in Tools

### CalculatorTool

Evaluates mathematical expressions:

```java
import io.github.llm4j.agent.tools.CalculatorTool;
import java.util.Map;

CalculatorTool calc = new CalculatorTool();
String result = calc.execute(Map.of("expression", "(100 - 25) * 2")); // Returns "150"
```

**Supported operations**: `+`, `-`, `*`, `/`, parentheses

**Example queries**:
- "What is 15 * 23 + 47?"
- "Calculate (100 - 25) * 2 and add 50 to it"

### CurrentTimeTool

Returns current date and time:

```java
import io.github.llm4j.agent.tools.CurrentTimeTool;
import java.util.Map;

CurrentTimeTool time = new CurrentTimeTool();
String result = time.execute(Map.of()); // Returns "2024-11-21 23:20:52 IST"
```

**Example queries**:
- "What is the current date and time?"
- "What day is it today?"

### EchoTool

Simple echo tool (mainly for testing):

```java
import io.github.llm4j.agent.tools.EchoTool;
import java.util.Map;

EchoTool echo = new EchoTool();
String result = echo.execute(Map.of("text", "Hello")); // Returns "Hello"
```

## Configuration Options

### Max Iterations

Control how many reasoning steps the agent can take:

```java
ReActAgent agent = ReActAgent.builder()
        .llmClient(client)
        .addTool(new CalculatorTool())
        .maxIterations(15)  // Default: 10
        .build();
```

### Temperature

Control LLM creativity:

```java
ReActAgent agent = ReActAgent.builder()
        .llmClient(client)
        .addTool(new CalculatorTool())
        .temperature(0.3)   // Lower = more focused, Higher = more creative
        .build();
```

### Custom System Prompt

Provide your own prompt template. Note that the agent expects `Action Input` to be a JSON object.

```java
String customPrompt = """
    You are a specialized math assistant.
    Use the Calculator tool for all mathematical operations.
    
    Available tools:
    {tool_descriptions}
    
    Format:
    Question: the question
    Thought: your reasoning
    Action: tool name
    Action Input: tool input as JSON object
    Observation: result
    Final Answer: the answer
    """;

ReActAgent agent = ReActAgent.builder()
        .llmClient(client)
        .addTool(new CalculatorTool())
        .systemPrompt(customPrompt)
        .build();
```

## Advanced Examples

### Multi-Step Reasoning

```java
agent.run("I have $100. I spend $25 on lunch and $15 on coffee. " +
          "Then I earn $50 from a side job. How much do I have now?");
```

The agent will:
1. Calculate: 100 - 25 = 75
2. Calculate: 75 - 15 = 60
3. Calculate: 60 + 50 = 110
4. Return: Final Answer: $110

### Complex Queries

```java
agent.run("What is the sum of the first 10 even numbers?");
```

The agent will:
1. Reason about the problem
2. Calculate: 2 + 4 + 6 + 8 + 10 + 12 + 14 + 16 + 18 + 20
3. Return: Final Answer: 110

## Error Handling

### Max Iterations Reached

```java
AgentResult result = agent.run("Very complex question");

if (!result.isCompleted()) {
    System.out.println("Agent didn't finish in time");
    System.out.println("Iterations used: " + result.getIterations());
    // Consider increasing maxIterations
}
```

### Unknown Tool

If the agent tries to use a tool that doesn't exist:

```java
// The agent will receive an error observation
// "Error: Unknown tool 'WebSearch'. Available tools: Calculator, CurrentTime"
```

### Tool Execution Errors

Tools can throw exceptions which are caught and returned as observations:

```java
// If calculator gets invalid input
// Observation: "Error evaluating expression: Unexpected character"
```

### Loop Detection

The agent automatically detects if it repeats the same action and input. It will receive an error observation prompting it to try a different approach.

## Performance Tips

1. **Use specific prompts**: Clear, specific questions get better results
2. **Right temperature**: Use lower (0.2-0.5) for math, higher (0.7-0.9) for creative tasks
3. **Limit iterations**: Set appropriate `maxIterations` based on task complexity
4. **Tool descriptions**: Make tool descriptions clear and specific about expected JSON inputs

## Best Practices

### 1. Choose the Right Tools

Only add tools that are relevant to your use case:

```java
// Good: Focused agent
ReActAgent mathAgent = ReActAgent.builder()
        .llmClient(client)
        .addTool(new CalculatorTool())
        .build();

// Avoid: Too many irrelevant tools
ReActAgent agent = ReActAgent.builder()
        .llmClient(client)
        .addTool(new CalculatorTool())
        .addTool(new WeatherTool())      // Not needed
        .addTool(new WebSearchTool())    // Not needed
        .addTool(new FileReadTool())     // Not needed
        .build();
```

### 2. Test with Simple Queries First

```java
// Start simple
result = agent.run("What is 2 + 2?");

// Then increase complexity
result = agent.run("Calculate (15 * 23) + (89 - 34)");
```

### 3. Monitor Agent Steps

```java
AgentResult result = agent.run(query);

// Log for debugging
System.out.println("Iterations: " + result.getIterations());
System.out.println("Steps: " + result.getSteps().size());

for (AgentResult.AgentStep step : result.getSteps()) {
    // Debug each step
}
```

## Next Steps

- **[Creating Custom Tools](Creating-Custom-Tools)** - Build your own tools
- **[Examples](Examples)** - More agent examples
- **[API Reference](API-Reference)** - Complete API documentation

## Troubleshooting

**Agent keeps hitting max iterations**
- Increase `maxIterations`
- Simplify your query
- Provide more specific tool descriptions

**Agent doesn't use tools**
- Check tool descriptions are clear
- Lower temperature (e.g., 0.3)
- Ensure tools are actually needed for the query

**Tool errors**
- Validate tool input/output
- Check tool implementation for bugs
- Add try-catch in tool's `execute()` method
