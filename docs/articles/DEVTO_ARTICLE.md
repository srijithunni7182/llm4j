---
title: "Building Production AI Agents in Java: Introducing llm4j and the Loom Orchestration DSL"
published: true
description: "A deep dive into llm4j — a Java-native AI agent framework with a neuro-symbolic orchestration DSL called Loom. For developers who want type safety, enterprise reliability, and a hard trust boundary between their models and their coordination logic."
tags: java, ai, agents, opensource
cover_image: https://raw.githubusercontent.com/srijithunni7182/llm4j/main/loom/ai-agent4j-loom/loom_logo.png
---

# Building Production AI Agents in Java: Introducing llm4j and the Loom Orchestration DSL

The AI agent ecosystem has a Python problem.

Not that Python is bad — it's a great language. But when you're building production systems that need type safety, JVM performance, Spring Boot integration, enterprise security controls, and the ability to onboard a team of Java engineers without retraining them on a new ecosystem — the Python-first assumption of most AI frameworks starts to feel like a tax.

**llm4j** is a Java-native AI agent framework that doesn't ask you to pay that tax. It's built from the ground up for the JVM, ships on Maven Central, and includes a purpose-built orchestration DSL called **Loom** that solves a problem most frameworks don't even acknowledge: the coordination layer of a multi-agent system should be symbolic, not probabilistic.

Let me show you what that means in practice.

---

## The Stack at a Glance

llm4j is organized into three layers:

```
llm4j/
├── ai-agent4j          # Core library — LLM clients, ReAct agents, RAG, memory
├── ai-agent4j-addons   # Local embeddings (ONNX/DJL), pgvector, Pinecone
└── loom/
    ├── ai-agent4j-loom # Loom DSL runtime — Lexer, Parser, AST, HarnessExecutor
    ├── vscode-loom     # VS Code extension for .loom files
    └── ctk             # Conformance Test Kit — behavioral contracts for all runtimes
```

You can use `ai-agent4j` standalone as a lightweight LLM client and ReAct agent framework. Or you can layer Loom on top for complex multi-agent orchestration. They're designed to work together but are independently useful.

---

## Part 1: ai-agent4j — The Core Library

### Installation

```xml
<dependency>
    <groupId>io.github.srijithunni7182</groupId>
    <artifactId>ai-agent4j</artifactId>
    <version>5.0</version>
</dependency>
```

### A Minimal LLM Client

```java
LLMConfig config = LLMConfig.builder()
    .apiKey(System.getenv("GOOGLE_API_KEY"))
    .defaultModel("gemini-1.5-flash")
    .build();

LLMClient client = new DefaultLLMClient(new GoogleProvider(config));

LLMResponse response = client.chat(LLMRequest.builder()
    .addUserMessage("Summarize the key risks in this contract: {text}")
    .build());
```

That's it. No decorators, no async event loops, no Python runtime embedded in your JVM process.

### Provider Support

ai-agent4j ships with first-class support for:

- **Google Gemini** (1.5 Flash, Pro, 2.x) — the primary target, deeply optimized
- **Sarvam AI** — Indian language voice agents with TTS, STT, and translation
- **Ollama** — local models (Gemma, Llama, Mistral) with zero cost and zero internet dependency

And a routing layer that makes provider switching transparent:

```java
LLMConfig config = LLMConfig.builder()
    .primaryModel("gpt-4o")
    .fallbackModels(List.of("claude-3-haiku", "gemini-1.5-flash"))
    .strategy(RoutingStrategy.COST_AWARE)
    .build();
```

When your primary provider rate-limits or fails, the router falls over to the next option automatically. No retry boilerplate in your application code.

### ReAct Agents with Tool Use

The ReAct (Reasoning + Acting) loop is a first-class primitive:

```java
ReActAgent agent = ReActAgent.builder()
    .client(client)
    .systemPrompt("You are a financial analyst.")
    .tool(new WebSearchTool())
    .tool(new CalculatorTool())
    .maxIterations(10)
    .build();

AgentResult result = agent.run("What is the current P/E ratio of NVDA?");
```

The agent reasons, decides which tool to call, observes the result, and iterates until it has a final answer — or hits the iteration limit. The Thought-Action-Observation loop is fully transparent and auditable.

### Memory: Short-Term and Long-Term

**Conversation history** (short-term) is managed automatically. **Semantic memory** (long-term) uses vector embeddings to recall relevant facts across sessions:

```java
ReActAgent agent = ReActAgent.builder()
    .client(client)
    .memory(SemanticMemory.builder()
        .embeddingProvider(new OnnxEmbeddingProvider("models/onnx/model.onnx", "models/onnx/tokenizer.json"))
        .vectorStore(new PGVectorStore("jdbc:postgresql://localhost/mydb", "user", "pass", "agent_memory", 384))
        .similarityThreshold(0.85)
        .maxResults(5)
        .build())
    .build();
```

The agent will automatically retrieve relevant memories before each response. No manual retrieval logic in your application.

### RAG Pipelines

The addons module adds local embeddings and persistent vector stores:

```xml
<dependency>
    <groupId>io.github.srijithunni7182</groupId>
    <artifactId>ai-agent4j-addons</artifactId>
    <version>5.0</version>
</dependency>
```

```java
// Local embeddings — no API calls, no cost
OnnxEmbeddingProvider embedder = new OnnxEmbeddingProvider(
    "models/onnx/model.onnx",
    "models/onnx/tokenizer.json"
);

// pgvector for production persistence
PGVectorStore store = new PGVectorStore(
    "jdbc:postgresql://localhost:5432/postgres",
    "postgres", "secret",
    "document_embeddings", 384
);

float[] vector = embedder.embed("Your document chunk here");
store.add("doc_001", vector, Map.of("source", "contracts", "year", "2024"));

// Semantic search
List<SearchResult> results = store.search(queryVector, 5);
```

Or use Pinecone for managed cloud storage — same API, different backend.

---

## Part 2: Loom — The Orchestration DSL

Here's where llm4j diverges from every other Java AI framework.

Most multi-agent frameworks — in any language — ask you to express coordination logic in the same language as your application. Python functions, YAML configs, JSON schemas. The routing rules, the retry logic, the branching conditions — all of it lives in your application code, tightly coupled to the framework's execution model.

This creates a problem that gets worse as your system grows: **you can't tell where the AI ends and the application begins.** The coordination logic bleeds into your business logic. The retry handling bleeds into your prompt engineering. The whole thing becomes a tangle that's hard to read, hard to audit, and hard to hand to a new engineer.

Loom's answer is a hard architectural boundary: **the coordination layer is a separate language with its own runtime.**

### The Core Idea: Neuro-Symbolic AI

The term "neuro-symbolic" describes a system that combines neural components (LLMs — probabilistic, reasoning about content) with symbolic components (deterministic rules — routing, branching, safety constraints).

The key insight is that these two things should not be mixed. The model should reason about content. The harness should govern coordination. And the boundary between them should be explicit, auditable, and enforced by a runtime — not inferred from a prompt.

Loom is that harness, expressed as a DSL.

### A Real Loom Script

```loom
// agents.loom

routing CostAware {
    strategy: "COST_AWARE"
    primary: "gpt-4o"
    fallback: ["claude-3-haiku", "gemini-1.5-flash"]
}

agent Researcher {
    model: "gpt-4o"
    system: "You are a rigorous research analyst."
    routing: CostAware
    knowledge {
        type: "RAG"
        path: "data/research_kb/"
        embedding: "text-embedding-3-small"
    }
}

agent Writer {
    model: "claude-3-opus"
    system: "You write clear, compelling technical articles."
}

agent Auditor {
    model: "gemini-1.5-pro"
    system: "You audit content for accuracy and compliance."
    output_schema: {
        status: enum["APPROVED", "NEEDS_REVISION"],
        issues: list<string>
    }
}

workflow ResearchAndPublish(topic) {

    // Retry with structured failure handling
    delegate "Research: {topic}" to Researcher -> findings
        retry 3
        on_failure {
            note "Research failed: {_error}"
            handoff "Manual research needed for: {topic}" to Writer
        }

    // Typed symbolic branching — not a string match
    alt (findings.status == "SUFFICIENT") {

        // Parallel execution — both run concurrently
        parallel {
            delegate "Write article: {findings}" to Writer -> draft
            delegate "Audit findings: {findings}" to Auditor -> audit
        }

        alt (audit.status == "APPROVED") {
            handoff "Publish: {draft}" to Writer
        } else {
            loop until (approved == "true") {
                delegate "Revise based on: {audit.issues}" to Writer -> draft
                human_prompt "Approve revised draft? (true/false)" -> approved
            }
        }

    } else {
        handoff "Insufficient research for: {topic}" to Researcher
    }
}
```

Let me walk through what's happening here, because every construct is doing something specific.

**`routing CostAware`** — a global policy. When the Researcher's primary model rate-limits, the runtime falls over to the fallback list automatically. This is declared once and applied by name.

**`knowledge { type: "RAG" }`** — the Researcher has RAG-backed context. Before each call, the runtime retrieves relevant documents from the knowledge base and injects them into the prompt. Configured in the DSL, not in Java code.

**`output_schema`** — the Auditor is constrained to return structured JSON. The runtime coerces the model's response before any condition is evaluated. `audit.status == "APPROVED"` is a typed enum check, not a string match against free text.

**`delegate ... retry 3 on_failure { }`** — resilience contracts in the DSL. If the Researcher fails three times, the runtime executes the `on_failure` block. The model never sees this logic.

**`parallel { }`** — concurrent execution. Both the Writer and Auditor run in separate threads. Execution waits at the closing brace for both to complete.

**`loop until`** — symbolic retry. The runtime evaluates the condition. The model doesn't decide when to stop.

**`human_prompt`** — first-class human-in-the-loop. Execution suspends until input arrives.

The entire coordination graph — retries, parallelism, branching, human approval gates — is in the `.loom` file. The models only see their individual task strings.

### Running It

```bash
# Build and alias the CLI
cd loom/ai-agent4j-loom && mvn clean install
alias weave='java -cp "target/classes:target/lib/*" io.github.llm4j.loom.cli.WeaveCLI'

# Run
weave run research.loom --loot tools.loot --input topic="Neuro-Symbolic AI"

# Package for deployment
weave package research.loom --loot tools.loot --fat --out research-app.jar
java -jar research-app.jar topic="Quantum Computing"
```

### Embedding in a Spring Boot Application

```java
@Service
public class ResearchService {

    private final HarnessExecutor executor;

    public ResearchService() throws Exception {
        String source = Files.readString(Path.of("workflows/research.loom"));
        LoomScript script = new LoomParser(new Lexer(source).tokenize()).parseScript();

        ToolRegistry registry = new ToolRegistry();
        new LootLoader().loadIntoRegistry("tools.loot", registry);

        this.executor = new HarnessExecutor(script, registry, new DefaultLLMClientFactory());
        this.executor.initialize();
    }

    public String research(String topic) {
        return executor.executeWorkflow("ResearchAndPublish", Map.of("topic", topic));
    }
}
```

### The Full Primitive Set

| Primitive | What it does |
|---|---|
| `delegate` | Call one agent, await result, bind to variable |
| `broadcast` | Fan out to multiple agents in parallel, collect combined result |
| `handoff` | Terminal node — pass control to an agent and end the current branch |
| `parallel { }` | Concurrent execution block — every statement runs in its own thread |
| `alt` / `else` | Symbolic conditional branching on typed variable values |
| `loop until` | Retry block — executes until a symbolic condition is met |
| `human_prompt` | Blocking suspension — parks execution until human input arrives |
| `guardrail` | Wraps a block — intercepts output before it escapes (e.g. PII detection) |
| `call` | Invoke a sub-workflow with isolated variable scope |
| `observe` | Emit a structured trace event without affecting control flow |
| `note` | Inline documentation — ignored by the runtime |
| `import` | Split large workflows across files — merged into a flat namespace |

---

## Part 3: The Tooling

### VS Code Extension

Writing `.loom` files without tooling is painful. The VS Code extension provides:

- Syntax highlighting for `.loom` and `.loot` files
- Real-time LSP diagnostics — errors as you type, with precise line/column
- Hover documentation and go-to-definition for agents and workflows
- Completion for all Loom keywords and identifiers
- Workflow Outline sidebar — see the full structure of a script at a glance
- Run Workflow command — execute the current file via `weave` without leaving the editor

### Conformance Test Kit (CTK)

This is the part that makes Loom a language rather than a library.

The CTK is a suite of 15 canonical `.loom` scripts — one per primitive — paired with expected execution traces and mock agent fixtures. Any Loom runtime (Java, Python, or future implementations) must pass the CTK to be considered conformant.

```bash
cd loom/ctk
mvn test
mvn exec:java -Dexec.mainClass=io.github.loom.ctk.CtkMain
```

The trace comparison algorithm checks structure, not values — it verifies that the right agents were called in the right order with the right statement types, without being brittle to changes in model output. This is what makes behavioral parity across runtimes verifiable rather than assumed.

---

## Why Java for AI Agents?

This is the question I get most often, so let me answer it directly.

**Type safety at the boundary.** The hardest problem in multi-agent systems is the boundary between model output (free text) and application logic (typed values). Java's type system, combined with Loom's output schemas, makes this boundary explicit and enforced at compile time where possible and at runtime where not.

**JVM performance.** The HarnessExecutor uses Java's `CompletableFuture` and thread pools for parallel execution. For workflows with many concurrent branches, the JVM's threading model is a genuine advantage over Python's GIL.

**Enterprise integration.** Spring Boot, Quarkus, Jakarta EE, JDBC, JPA — the Java ecosystem for enterprise integration is unmatched. If your AI agents need to talk to Oracle databases, SAP systems, or internal REST APIs with complex auth flows, you want to be in Java.

**Existing teams.** Most enterprise engineering teams are Java shops. Asking them to adopt Python for AI work means new tooling, new CI/CD pipelines, new security reviews, and a split codebase. llm4j lets them build AI agents in the language they already know.

**Auditability.** The Loom DSL produces a complete execution trace for every workflow run. Every `delegate`, `broadcast`, `call`, and `handoff` is recorded with its agent, payload, and output variable. This is not optional — it's built into the runtime. For regulated industries, this matters.

---

## Project Stats

| Metric | Value |
|---|---|
| Lines of Code | 13,700+ |
| Test Cases | 438+ |
| Maven Central | v5.0 |
| License | MIT |
| Java Version | 17+ |
| Library Size | ~308 KB |

---

## Getting Started

```bash
git clone https://github.com/srijithunni7182/llm4j.git
cd llm4j

# Build the core library
cd ai-agent4j && mvn clean install

# Build and run the Loom sample
cd ../loom/ai-agent4j-loom && mvn clean install
alias weave='java -cp "target/classes:target/lib/*" io.github.llm4j.loom.cli.WeaveCLI'
weave run samples/boardroom/main.loom --input topic="The future of AI agents"
```

Or add the Maven dependency and start with the [Quick Start Guide](https://github.com/srijithunni7182/llm4j/wiki/Getting-Started).

---

## What's Next

**loom4py** is in active development — a native Python implementation of the Loom runtime that will be validated against the CTK for full behavioral parity with the Java reference. The goal: write a `.loom` script once, run it on any conformant runtime.

---

## Contribute

llm4j is MIT licensed and actively developed. The repo is at:

**[https://github.com/srijithunni7182/llm4j](https://github.com/srijithunni7182/llm4j)**

Good first contributions:
- **New Loom samples** — real-world workflows in `loom/ai-agent4j-loom/samples/`
- **New tool implementations** — anything that implements the `Tool` interface
- **New provider integrations** — OpenAI, Anthropic, Cohere
- **loom4py** — the Python runtime is the most impactful open contribution right now
- **CTK runtime ports** — build a conformant Loom runtime in Go, Rust, or TypeScript

If you're building multi-agent systems in Java and you've been waiting for a framework that takes the JVM seriously — this is it.

---

*Star the repo, open an issue, or just say hello. The model reasons. The harness governs.*
