# llm4j Wiki

**AI agents, written the Java way.** Typed, testable, observable: from a single tool call to autonomous
workflows that run for days. llm4j is a complete AI stack in idiomatic Java, with no vendor SDKs.

Gemini, Claude, Sarvam and Ollama sit behind [one contract](../ai-agent4j/wiki/Providers-and-the-Uniform-Contract.md).
Start with the [project README](../README.md) for the overview, then use this page to find your way around.

**New here?** [Build a multi-agent workflow step by step](../docs/guide/README.md), from choosing agents to a
budgeted live run. Or go straight to the [Quick Start](../ai-agent4j/wiki/Getting-Started.md).

---

## 🧱 The stack

Each module answers the question the previous one raises.

| Module | Question it answers | Start here |
|--------|---------------------|------------|
| **ai-agent4j** | How do I build an agent? | [Quick Start](../ai-agent4j/wiki/Getting-Started.md) · [Module home](../ai-agent4j/wiki/Home.md) |
| **Loom** | How do many agents work together, reliably, for days? | [Overview](../loom/ai-agent4j-loom/README.md) · [Language guide](../loom/ai-agent4j-loom/LOOM_GUIDE.md) |
| **Engram** | How does an agent remember without drowning in context? | [Engram](../engram/engram-core/README.md) · [Loom + Engram workflows](../docs/AGENTIC_WORKFLOWS_GUIDE.md) |
| **Addons** | How do I keep my data private and my costs at zero? | [Addons](../ai-agent4j-addons/) (ONNX and DJL embeddings, pgvector, Pinecone) |
| **Tools** | How do I let an agent act on the world safely? | [Ready-made tools](../ai-agent4j-tools/docs/README.md) (`webhook`, `email`, `http`, `file`, `shell`, `sql`) |
| **eval4j** | How do I know it works, and keeps working? | [eval4j guide](../eval4j/README.md) · [Dashboard guide](../eval4j-report/docs/USER-GUIDE.md) |

---

## 📚 Explore the docs

### Build agents

- [Quick Start](../ai-agent4j/wiki/Getting-Started.md)
- [ReAct Agent Guide](../ai-agent4j/wiki/ReAct-Agent-Guide.md)
- [Providers and the Uniform Contract](../ai-agent4j/wiki/Providers-and-the-Uniform-Contract.md) · [Sarvam](../ai-agent4j/docs/SARVAM.md) · [Ollama](../ai-agent4j/docs/OLLAMA.md)
- [Creating Custom Tools](../ai-agent4j/wiki/Creating-Custom-Tools.md) · [Ready-made tools](../ai-agent4j-tools/docs/README.md)
- [Agent Skills](../ai-agent4j/wiki/Agent-Skills-Guide.md) · [Personas](../ai-agent4j/wiki/Agent-Personas.md) · [Prompt Registry](../ai-agent4j/wiki/Prompt-Registry-Guide.md)
- [Memory](../ai-agent4j/wiki/Memory-and-Persistence.md) · [Semantic Memory](../ai-agent4j/wiki/SEMANTIC_MEMORY.md)
- [RAG](../ai-agent4j/wiki/RAG-Support.md) · [Knowledge Graphs](../ai-agent4j/wiki/Knowledge-Graphs.md)
- [MCP](../ai-agent4j/wiki/MCP-Integration.md) (also [this overview](MCP-Integration.md)) · [OpenAPI Tool](../ai-agent4j/wiki/OpenAPI-Tool.md)

### Orchestrate

- [Loom overview](../loom/ai-agent4j-loom/README.md) · [Why Loom?](../loom/ai-agent4j-loom/WHY_LOOM.md)
- [Loom Language Guide](../loom/ai-agent4j-loom/LOOM_GUIDE.md)
- [Budgets, Pausing and Scheduling](../loom/ai-agent4j-loom/BUDGETS_AND_SCHEDULING.md)
- [Agentic Workflows with Loom and Engram](../docs/AGENTIC_WORKFLOWS_GUIDE.md)
- [Loom CTK (conformance)](../loom/ctk/README.md) · [VS Code extension](../loom/vscode-loom/README.md)

### Ship with confidence

- [eval4j guide](../eval4j/README.md) · [Why eval4j?](../ai-agent4j/wiki/WHY_EVAL4J.md)
- [Budgets and Rate Limits](../ai-agent4j/wiki/Budgets-and-Rate-Limits.md)
- [xAI: Beyond Black Boxes](../ai-agent4j/wiki/xAI_BEYOND_BLACK_BOXES.md)
- [Testing Strategy](../docs/TESTING_STRATEGY.md) · [API Compatibility Policy](../docs/API_COMPATIBILITY.md)
- [Version Matrix](../docs/VERSION_MATRIX.md) · [Migration Guide 5.0](../docs/MIGRATION_GUIDE_5_0.md)

### Why llm4j

- [Why AI Agent4J?](../ai-agent4j/wiki/WHY_AI_AGENT4J.md): the comparison with LangChain4j and Spring AI
- [Why Loom?](../loom/ai-agent4j-loom/WHY_LOOM.md): long-running, autonomous workflows, and how it goes further than LangGraph
- [Why eval4j?](../ai-agent4j/wiki/WHY_EVAL4J.md): the comparison with deepeval

---

## 🔒 Security

The model is treated as untrusted; the authority lives in code. Read the [Security guide](../SECURITY.md) for the
threat model, the building blocks, how to secure a Loom workflow, and the OWASP LLM Top 10 mapping with the gaps
named.

---

## ⚡ See it built

Every app below lives in this repo and runs.

| App | What it is |
|-----|------------|
| **[GetViral](../examples/getviral/README.md)** | The flagship. One idea in; a ready-to-post pack for X, Instagram Reels and YouTube out. Twelve agents, one Loom workflow, every module in the repo. |
| **[Hexamind Hub](../examples/hexamind-hub/README.md)** | A digital boardroom where six agents debate your problem live and reach consensus. |
| **[Nirmaan Yantra](../examples/nirmaan-yantra/README.md)** | An autonomous software factory: spec → tests → code → QA → release. |
| **[Kingini](../examples/kingini/README.md)** | A voice-first companion for children, in Malayalam, built on Sarvam. |
| **[Gmail MCP App](../examples/gmail-mcp-app/)** | Agents that read, draft and send email through MCP, with human approval. |

### Hexamind Hub internals

- **[Shared Brain](Shared-Brain.md)**: how agents share knowledge using RAG and knowledge graphs.
- **[Neural Metrics](Neural-Metrics.md)**: the real-time visualizations of the hive mind.

---

## 📐 Project standards

- [Security guide](../SECURITY.md)
- [Testing Strategy](../docs/TESTING_STRATEGY.md)
- [API Compatibility Policy](../docs/API_COMPATIBILITY.md)
- [Contributing Guide](../CONTRIBUTING.md)

Licensed under the [Apache License 2.0](../LICENSE).
