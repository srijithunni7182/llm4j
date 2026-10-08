# Real-time Thought Streaming

To build responsive user interfaces, it is essential to show the agent's reasoning process as it happens. `ai-agent4j` provides an `AgentEventListener` interface that allows you to capture thoughts, actions, and observations in real-time.

## Using AgentEventListener

By attaching a listener to your `ReActAgent`, you can stream each step of the reasoning loop to your frontend via WebSockets, Server-Sent Events (SSE), or simply log them to the console.

```java
import io.github.llm4j.agent.AgentEventListener;

// 1. Define a listener
AgentEventListener listener = new AgentEventListener() {
    @Override
    public void onThought(String thought) {
        System.out.println("Thinking: " + thought);
        // Push to UI via SSE or WebSocket here
    }

    @Override
    public void onAction(String toolName, String toolInput) {
        System.out.println("Executing: " + toolName + " with input: " + toolInput);
    }

    @Override
    public void onObservation(String toolOutput) {
        System.out.println("Tool Result: " + toolOutput);
    }
};

// 2. Register the listener in the builder
ReActAgent agent = ReActAgent.builder()
        .llmClient(client)
        .addListener(listener) 
        .build();

agent.run("What is the current weather in Tokyo?");
```

## Integration with Web Frameworks

### Spring Boot / WebFlux

In a reactive application, you can use the listener to sink events into a `Flux` or `Sinks.Many` to stream updates directly to a browser.

```java
Sinks.Many<String> sink = Sinks.many().multicast().directBestEffort();

AgentEventListener streamingListener = new AgentEventListener() {
    @Override
    public void onThought(String thought) {
        sink.tryEmitNext("THOUGHT: " + thought);
    }
    // ... other methods
};
```

## Benefits of Streaming

- **Reduced Perceived Latency**: Users see progress immediately rather than waiting for the final answer.
- **Transparency**: Users can see "how" the agent arrived at a conclusion, which matches **xAI (Explainable AI)** standards.
- **Debugging**: Makes it easier to identify where an agent might be getting stuck or looping during development.

---

*Related: [xAI Standards Guide](xAI_BEYOND_BLACK_BOXES.md)*
