# 🧠 Building Agentic Workflows with Loom & Engram

This guide explains how to combine **Loom** (the neuro-symbolic orchestrator) and **Engram** (the semantic memory engine) to build advanced, self-optimizing AI agents in Java.

---

## 🌊 The Problem: The "Context Wall"

In standard agentic workflows, LLMs are given a complete transcript of the conversation. While this works for short tasks, it fails in professional, long-running cycles:

1.  **Quadratic Growth**: The transcript grows with every turn, quickly hitting model context limits.
2.  **Signal-to-Noise Ratio**: As the history grows, the "signal" (key facts) gets buried in "noise" (redundant greetings, intermediate logs), leading to hallucinations.
3.  **Cost & Latency**: Large contexts make every request slower and more expensive.

---

## 🧠 The Solution: Neuro-Symbolic Memory

**Engram** solves this by implementing a **Neuro-Symbolic Memory Loop**. Instead of sending a raw transcript, it uses a **Context Intelligence Agent (CIA)** to:
1.  **Extract**: Identify key facts and decisions from every turn.
2.  **Score**: Rank memories by similarity, recency, and importance.
3.  **Synthesize**: Create a focused "Briefing" for the next agent task.

---

## 🚀 Quick Start

### 1. Initialize the Engine
```java
// Local Memory (In-Memory + Persistence to JSON)
VectorStore store = new InMemoryStore("memories.json");

// Context Agent (Using Gemini or Ollama)
ContextIntelligenceAgent cia = new LLMContextIntelligenceAgent(llmClient);

EngramEngine engram = new EngramEngine(store, cia);
```

### 2. Inject into Loom
```java
HarnessExecutor executor = new HarnessExecutor(script, tools, clientProvider);
executor.setMemoryEngine(engram);

executor.executeWorkflow("my-workflow", Map.of());
```

---

## 🛡️ Best Practices

- **Use the Introspector**: Enable the introspection loop for long-running workflows to ensure outdated or erroneous memories are "shadowed."
- **Optimize Briefing Size**: Adjust the `MAX_CANDIDATES` in `EngramEngine` based on your model's context sensitivity.
