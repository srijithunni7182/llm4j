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

*   **Extract**: Identify only the key facts, decisions, and constraints from an agent's output.
*   **Synthesize**: Generate a high-signal "Context Briefing" tailored to the specific next task.
*   **Introspect**: Retroactively review its own memory to prune outdated facts or identify new strategies.

When combined with **Loom's** deterministic orchestration, you get an agentic system that is both reliable and highly efficient.

---

## 🚀 Getting Started

### 1. Add Dependencies

Ensure you have both modules in your `pom.xml`:

```xml
<dependencies>
    <!-- Loom: Orchestration -->
    <dependency>
        <groupId>io.github.llm4j</groupId>
        <artifactId>ai-agent4j-loom</artifactId>
        <version>1.0.0</version>
    </dependency>
    
    <!-- Engram: Memory -->
    <dependency>
        <groupId>io.github.llm4j</groupId>
        <artifactId>engram-core</artifactId>
        <version>1.0.0</version>
    </dependency>
</dependencies>
```

### 2. Configure the Memory Engine

In your Java application, initialize the `EngramEngine`. In production, you should use a persistent store like `PGVectorStore`, but for development, the `InMemoryStore` is sufficient.

```java
// 1. Setup the LLM Client for Memory Operations (Synthesizing/Extracting)
LLMClient gemini = new GeminiClient(apiKey);

// 2. Initialize the CIA (The "Brain" of the memory)
ContextIntelligenceAgent cia = new LLMContextIntelligenceAgent(gemini);

// 3. Initialize Engram with a persistent JSON store
EngramEngine engram = new EngramEngine("data/memories.json", cia);
```

### 3. Attach to Loom

Once you have the `EngramEngine`, inject it into the Loom `HarnessExecutor`.

```java
// 4. Load your Loom script and Tool registry
LoomScript script = new LoomParser().parse(Path.of("workflows/dev_factory.loom"));
ToolRegistry registry = new ToolRegistry();

// 5. Build the Executor and attach Engram
HarnessExecutor executor = new HarnessExecutor(script, registry, clientFactory);
executor.setMemoryEngine(engram); // This replaces the default transcript engine

// 6. Run your workflow
executor.initialize();
executor.executeWorkflow("Main", Map.of("project", "Build a Java CLI"));
```

---

## 🧵 Writing an Agentic Workflow (`.loom`)

When using Engram, your agents in the `.loom` script become much more powerful. Even if a workflow runs for 50 turns, the agents will only receive the relevant context briefing.

```loom
// workflows/dev_factory.loom

agent Architect {
    model: "gemini-1.5-pro"
    system: "You are a software architect. Define the core data structures."
}

agent Coder {
    model: "gemini-1.5-flash"
    system: "You implement logic based on the architect's plan."
}

workflow Main(project) {
    // 1. Initial Planning
    delegate "Plan the architecture for {project}" to Architect -> plan
    
    // 2. Implementation Loop (Simulating many turns)
    loop until (is_done == "true") {
        delegate "Implement the next module for {plan}" to Coder -> outcome
        
        // Engram automatically extracts 'outcome' facts and 
        // synthesizes them for the next turn!
        
        human_prompt "Is the module complete? (true/false)" -> is_done
    }
}
```

---

## 🔍 Advanced: The Introspection Loop

Engram doesn't just store what happened; it *thinks* about it. After each `delegate` or `handoff`, the `EngramEngine` performs an **Introspection Pass**:

1.  **Self-Correction**: If the agent failed or made a sub-optimal choice, the Introspector identifies a "Strategy Adjustment" fact.
2.  **Memory Shadowing**: If a new fact contradicts an old one (e.g., "The database is now MySQL" vs "The database is PostgreSQL"), the old memory is "shadowed" (deleted) to prevent confusion.

---

## 📊 Summary of Benefits

| Feature | Standard (Transcript) | Loom + Engram |
| :--- | :--- | :--- |
| **Context Size** | Grows Linearly (100k+ tokens) | Constant (approx. 1k-2k tokens) |
| **Recall** | Probabilistic (Lost in middle) | High-Precision (Vector Retrieval) |
| **Persistence** | Lost after session | Long-Term (Cross-session memory) |
| **Self-Healing** | Manual prompt tuning | Automated via Introspection Loop |

---

> [!TIP]
> **Observability**: Use `executor.setAuditLogger(new FileAuditLogger(path))` to see exactly what facts Engram is extracting and how it synthesizes them for each turn.
