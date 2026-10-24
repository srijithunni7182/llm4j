# llm4j Wiki

**AI agents, written the Java way.** Typed, testable, observable: from a single tool call to autonomous
workflows that run for days. llm4j is a complete AI stack in idiomatic Java, with no vendor SDKs.

Gemini, Claude, OpenAI, xAI, Perplexity, Kimi, Qwen, Sarvam, Ollama and any OpenAI-style server sit behind [one contract](../src/ai-agent4j/wiki/Providers-and-the-Uniform-Contract.md).
Start with the [project README](../README.md) for the overview, then use this page to find your way around.

**New here?** [Build a multi-agent workflow step by step](../docs/guide/README.md), from choosing agents to a
budgeted live run. Or go straight to the [Quick Start](../src/ai-agent4j/wiki/Getting-Started.md).

---

## 🧱 The stack

Each module answers the question the previous one raises.

| Module | Question it answers | Start here |
|--------|---------------------|------------|
| **ai-agent4j** | How do I build an agent? | [Quick Start](../src/ai-agent4j/wiki/Getting-Started.md) · [Module home](../src/ai-agent4j/wiki/Home.md) |
| **Loom** | How do many agents work together, reliably, for days? | [Overview](../src/loom/ai-agent4j-loom/README.md) · [Language guide](../src/loom/ai-agent4j-loom/LOOM_GUIDE.md) |
| **Engram** | How does an agent remember without drowning in context? | [Engram](../src/engram/engram-core/README.md) · [Loom + Engram workflows](../docs/AGENTIC_WORKFLOWS_GUIDE.md) |
| **Addons** | How do I keep my data private and my costs at zero? | [Addons](../src/ai-agent4j-addons/) (ONNX and DJL embeddings, pgvector, Pinecone) |
| **Tools** | How do I let an agent act on the world safely? | [Ready-made tools](../src/ai-agent4j-tools/docs/README.md) (`webhook`, `email`, `http`, `file`, `shell`, `sql`) |
| **Observability** | How do I see what a workflow is doing right now? | [Overview](Observability.md) · [Fabric server](Fabric-Server.md) · [Deployer skill](Deployer-Skill.md) |
| **eval4j** | How do I know it works, and keeps working? | [eval4j guide](../src/eval4j/README.md) · [Dashboard guide](../src/eval4j-report/docs/USER-GUIDE.md) |

---

## 📚 Explore the docs

### Build agents

- [Quick Start](../src/ai-agent4j/wiki/Getting-Started.md)
- [ReAct Agent Guide](../src/ai-agent4j/wiki/ReAct-Agent-Guide.md)
- [Providers and the Uniform Contract](../src/ai-agent4j/wiki/Providers-and-the-Uniform-Contract.md) · [Web-search grounding](../src/ai-agent4j/wiki/Web-Search-Grounding.md) · [Sarvam](../src/ai-agent4j/docs/SARVAM.md) · [Ollama](../src/ai-agent4j/docs/OLLAMA.md)
- [Creating Custom Tools](../src/ai-agent4j/wiki/Creating-Custom-Tools.md) · [Ready-made tools](../src/ai-agent4j-tools/docs/README.md)
- [Agent Skills](../src/ai-agent4j/wiki/Agent-Skills-Guide.md) · [Personas](../src/ai-agent4j/wiki/Agent-Personas.md) · [Prompt Registry](../src/ai-agent4j/wiki/Prompt-Registry-Guide.md)
- [Memory](../src/ai-agent4j/wiki/Memory-and-Persistence.md) · [Semantic Memory](../src/ai-agent4j/wiki/SEMANTIC_MEMORY.md)
- [RAG](../src/ai-agent4j/wiki/RAG-Support.md) · [Knowledge Graphs](../src/ai-agent4j/wiki/Knowledge-Graphs.md)
- [MCP](../src/ai-agent4j/wiki/MCP-Integration.md) (also [this overview](MCP-Integration.md)) · [OpenAPI Tool](../src/ai-agent4j/wiki/OpenAPI-Tool.md)

### Orchestrate

- [Loom overview](../src/loom/ai-agent4j-loom/README.md) · [Why Loom?](../src/loom/ai-agent4j-loom/WHY_LOOM.md)
- [Loom Language Guide](../src/loom/ai-agent4j-loom/LOOM_GUIDE.md)
- [Budgets, Pausing and Scheduling](../src/loom/ai-agent4j-loom/BUDGETS_AND_SCHEDULING.md)
- [Agentic Workflows with Loom and Engram](../docs/AGENTIC_WORKFLOWS_GUIDE.md)
- [Loom in VS Code](Loom-in-VS-Code.md): the review panel, rename and completion that know your script, checks as you type, a debugger
- [Generating a workflow](Generating-a-Workflow.md): a description becomes a plan you read first, then a project that passes check, review and its own tests, or you are told which gate failed
- [Debugging a workflow](Debugging-a-Workflow.md): why a run did what it did, what it would have done, stepping through it and back, and the run drawn on the graph
- [Observability: watching runs live](Observability.md) · [Deployment guide](../docs/OBSERVABILITY_DEPLOYMENT.md) · [Fabric server](Fabric-Server.md) · [Deployer skill](Deployer-Skill.md) · [Incidents and rules](Observability-Incidents-and-Rules.md) · [Security signals](Observability-Security-Signals.md) · [Controls, secrets and security](Observability-Controls-and-Security.md) · [Reference](Observability-Reference.md)
- [Loom CTK (conformance)](../src/loom/ctk/README.md) · [VS Code extension](../src/loom/vscode-loom/README.md)

### Ship with confidence

- [eval4j guide](../src/eval4j/README.md) · [Why eval4j?](../src/ai-agent4j/wiki/WHY_EVAL4J.md)
- [Budgets and Rate Limits](../src/ai-agent4j/wiki/Budgets-and-Rate-Limits.md)
- [xAI: Beyond Black Boxes](../src/ai-agent4j/wiki/xAI_BEYOND_BLACK_BOXES.md)
- [Testing Strategy](../docs/TESTING_STRATEGY.md) · [API Compatibility Policy](../docs/API_COMPATIBILITY.md)
- [Version Matrix](../docs/VERSION_MATRIX.md) · [Migration Guide 5.0](../docs/MIGRATION_GUIDE_5_0.md)

### Why llm4j

- [Why AI Agent4J?](../src/ai-agent4j/wiki/WHY_AI_AGENT4J.md): the comparison with LangChain4j and Spring AI
- [Why Loom?](../src/loom/ai-agent4j-loom/WHY_LOOM.md): long-running, autonomous workflows, and how it goes further than LangGraph
- [Why eval4j?](../src/ai-agent4j/wiki/WHY_EVAL4J.md): the comparison with deepeval

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
| **[GetViral](../src/examples/getviral/README.md)** | The flagship. One idea in; a ready-to-post pack for X, Instagram Reels and YouTube out. Twelve agents, one Loom workflow, every module in the repo. |
| **[Hexamind Hub](../src/examples/hexamind-hub/README.md)** | A digital boardroom where six agents debate your problem live and reach consensus. |
| **[Nirmaan Yantra](../src/examples/nirmaan-yantra/README.md)** | An autonomous software factory: spec → tests → code → QA → release. |
| **[Kingini](../src/examples/kingini/README.md)** | A voice-first companion for children, in Malayalam, built on Sarvam. |
| **[Gmail MCP App](../src/examples/gmail-mcp-app/)** | Agents that read, draft and send email through MCP, with human approval. |

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
