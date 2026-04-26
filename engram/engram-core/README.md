# 🧠 Engram: Context Intelligence for Agentic Workflows

Engram is a **Neuro-Symbolic Memory Engine** for LLM agents. It solves the "Context Bloat" problem by replacing naive transcript accumulation with a smart, synthesized retrieval-synthesis loop.

> **"Stop sending your model the entire history. Send it only what matters."**

---

## 🚀 The Engram Advantage

In professional agentic workflows, history grows quadratically. Standard frameworks eventually hit a "Context Wall" where the model becomes slow, expensive, and hallucination-prone. 

Engram uses a **Context Intelligence Agent (CIA)** to extract, score, and synthesize memories in real-time, maintaining a constant-size "Reasoning Window."

### 📊 Benchmark: Scale Efficiency
We simulated a 20-turn complex development cycle (600 tokens/turn). 

| Turn | Transcript Mode | Engram Mode | Savings |
|------|-----------------|-------------|---------|
| 1    | 300 tokens      | 930 tokens  | -210% (Tax) |
| 10   | 6,600 tokens    | 1,200 tokens| **81%** |
| 20   | 13,600 tokens   | 1,500 tokens| **89%** |

*Engram reaches its "Inversion Point" at Turn 2, where the efficiency gain permanently outweighs the CIA overhead.*

### 💻 Case Study: Local Resilience
Running a multi-agent Tic-Tac-Toe build on a **local 8GB laptop** (Gemma 2B):
- **Transcript Mode**: Crashed with 503 Socket Closed at Turn 3 due to context bloat.
- **Engram Mode**: Successfully completed the entire build with **5x lower latency** per turn.

---

## 🛠️ Key Features

- **Multi-Tiered Memory**: Episodic (Recent), Semantic (Facts), and Working (Current) tiers with custom decay rates.
- **Introspection Loop**: A secondary LLM pass that retroactively "Shadows" old memories when they are superseded by new facts.
- **Vector Persistence**: Plug-and-play support for `InMemoryStore` (Local) and `PGVectorStore` (Production).
- **Edge-Optimized**: Hardened "Primitive Intelligence" prompts that enable 2B-8B local models to manage their own memory.

---

## 📦 Getting Started

### 1. Add Dependency
```xml
<dependency>
    <groupId>io.github.llm4j</groupId>
    <artifactId>engram-core</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 2. Initialize the Engine
```java
VectorStore store = new PGVectorStore(jdbcUrl, user, pass);
ContextIntelligenceAgent cia = new LLMContextIntelligenceAgent(geminiClient);

EngramEngine engram = new EngramEngine(store, cia);

// Inject into your Loom executor
executor.setMemoryEngine(engram);
```

👉 **[Mastering Agentic Workflows with Loom & Engram](../../docs/AGENTIC_WORKFLOWS_GUIDE.md)**

---

## 🛡️ Architecture: The Neuro-Symbolic Loop

1.  **Retrieval**: Engram scores candidate memories using a blend of Cosine Similarity, Recency, and Importance.
2.  **Synthesis**: The CIA synthesizes a "Context Briefing" tailored to the specific next task.
3.  **Execution**: The Agent executes with a lean, high-signal prompt.
4.  **Extraction**: The CIA extracts new facts/decisions from the outcome.
5.  **Introspection**: The "Self-Correction" pass identifies and prunes interference.

---
*Built with ❤️ by the LLM4J Team. Part of the Loom Ecosystem.*
