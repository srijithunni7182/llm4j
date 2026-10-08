# Conversation Memory and Persistence

Managing the state of a conversation is critical for multi-turn interactions. `ai-agent4j` provide several layers of memory, from short-term conversation history to persistent, summarized storage.

## In-Memory Conversation History

The `ConversationHistory` class tracks recent messages in a list. You can limit the number of messages to prevent the context window from overflowing.

```java
import io.github.llm4j.agent.memory.ConversationHistory;

// Keep the last 10 messages in memory
ConversationHistory history = new ConversationHistory(10);

ReActAgent agent = ReActAgent.builder()
        .llmClient(client)
        .conversationHistory(history)
        .build();

agent.run("My name is Srijith.");
AgentResult result = agent.run("What is my name?"); 
// Result: "Your name is Srijith."
```

## Persistent Conversation Storage

To preserve conversations across application restarts, use a `ConversationStore`.

### File-Based Storage

The `FileConversationStore` saves conversations as JSON files on the local disk.

```java
import io.github.llm4j.agent.memory.*;
import java.nio.file.Paths;

// 1. Create a file-based store
ConversationStore fileStore = new FileConversationStore(Paths.get("conversations"));

// 2. Optional: Wrap with an Async store for non-blocking I/O
ConversationStore asyncStore = new AsyncConversationStore(fileStore);

// 3. Create history tied to a specific session ID
ConversationHistory history = new ConversationHistory(
    "user-session-123", 
    asyncStore, 
    20
);
```

### Async Storage Wrapper

The `AsyncConversationStore` is a proxy that performs write operations in a background thread, ensuring that saving the conversation doesn't slow down the agent's response time.

## Conversation Summarization

Long conversations can become expensive and hit context limits. You can generate a concise summary of the conversation to save space.

```java
import io.github.llm4j.util.ConversationSummarizer;

ConversationSummarizer summarizer = new ConversationSummarizer(llmClient);
String summary = summarizer.summarize(history.getMessages());

// Update the store with the new summary
asyncStore.updateSummary("user-session-123", summary);
```

### Managing Sessions

You can list and retrieve stored sessions easily:

```java
List<ConversationMetadata> sessions = asyncStore.listSessions();

for (ConversationMetadata meta : sessions) {
    System.out.println(meta.getSessionId() + ": " + meta.getSummary());
}
```

## Available Store Implementations

| Store | Purpose |
| :--- | :--- |
| `InMemoryConversationStore` | Default, ephemeral storage (lost on restart). |
| `FileConversationStore` | Simple JSON file-based persistence. |
| `AsyncConversationStore` | Non-blocking wrapper for heavy I/O operations. |

---

*Related: [Semantic Long-Term Memory](SEMANTIC_MEMORY.md)*
