![LLM4J Ecosystem Hero](docs/images/hero_ecosystem.png)

# llm4j: The Pure Java AI Stack

> **Build intelligent, reasoning applications from the ground up.**

**llm4j** is a monorepo dedicated to exploring the future of AI engineering in Java. Unlike Python-heavy ecosystems or heavy abstractions, this project proves that you can build sophisticated, production-ready AI solutions using pure, idiomatic Java.

It provides a complete stack: from a low-level **Gemini 3.5 Flash** client to a high-level ReAct agent framework with **Human-in-the-Loop** approval gates, and fully fledged multi-agent applications.

---

## 🏗️ The Core: [AI Agent4J](ai-agent4j/)

The heart of this repository is **ai-agent4j**, a lightweight yet powerful Java library for building LLM-powered applications.

* **Multi-Provider**: Native support for **Google Gemini 3.5 Flash**, **Sarvam AI (Sarvam-30B / Sarvam-105B)**, and **Local models via Ollama (Gemma, Llama, Phi)**, with an extensible architecture for others.
* **Voice-Native**: First-class support for Speech-to-Text (STT via Saaras v3) and Text-to-Speech (TTS via Bulbul v3) pipelines.
* **Zero Magic**: No confusing "magic" abstractions. Just clean, typed Java code.
* **ReAct Agents**: Implements the **Re**asoning + **Act**ing paradigm, allowing agents to solve complex problems by thinking and using tools.
* **Human-in-the-Loop (HITL)**: Built-in approval gates — any tool can declare `requiresApproval()` and the agent will block for an `ApprovalCallback` before executing sensitive actions (e.g. sending emails, running queries, making payments).
* **Autonomous Foundations**: Built-in support for **Agent Delegation** (Manager/Worker patterns), **Background Task Scheduling**, and **Semantic Long-Term Memory**.
* **Model Routing**: Cost-aware and fallback routing strategies with a tri-lane `HybridModelRegistry` that routes `gemini-*` → Gemini Cloud, `sarvam-*` → Sarvam Cloud, and `ollama/*` → Local Ollama.
* **Tooling**: Includes ready-to-use tools (Calculator, Web Search) and an **OpenAPI Tool** that can turn any REST API into an AI function instantly.
* **MCP Support**: Full support for the **Model Context Protocol (MCP)**, enabling connection to any external MCP server (Python, Node, etc.).
* **Structured Output**: Native support for JSON modes and structured object mapping.
* **Skill Injection & Discovery**: Inject domain knowledge dynamically using **AgentSkill** (Markdown-based instructions) and automatically discover available skills.
* **xAI Compliant**: Industry-leading **95% xAI compliance** with built-in PII masking, confidence scoring, and transparent reasoning audit trails.
* **AI-Optimized**: Includes comprehensive `llms.txt` and specialized documentation optimized for AI scrapers and crawlers.

## 🧵 The Orchestrator: [Loom](loom/ai-agent4j-loom/)

**Loom** is the **Neuro-Symbolic** orchestration layer of the llm4j stack. It provides a specialized DSL (`.loom`) to manage complex, multi-agent workflows with deterministic precision.

* **Neuro-Symbolic**: Combines the reasoning power of LLMs with the rigid reliability of symbolic logic.
* **DSL-Driven**: Define agents and workflows in a human-readable script; boot systems without Java recompilation.
* **Deterministic Routing**: Native support for `handoff`, `delegate`, `parallel` execution, and `loop until` patterns.
* **Enterprise Governance**: PII guardrails, cost-aware and fallback routing, persistent scheduling, and **human approval gates**: `approve: [Publish]` on an agent makes those tool calls wait for a person (durably, holding no thread).
* **Tools, Knowledge and Checks in the Script**: `tool Search { use: serpapi api_key: env.SERPAPI_KEY }`, built-in `web_search`/`calculator`, OpenAPI specs as tools, and `knowledge Handbook { source: "docs/" embedding: "gemini/text-embedding-004" }` for grounded answers. Nothing is silently ignored: `weave check` reports every problem with its line before anything runs.
* **Memory, Voice, Guards and Providers in the Script**: agents that remember each user (`memory { conversation: "chats" session: "{user_id}" facts: "facts.json" }`), speak and listen in Indian languages (`voice { speak: "sarvam/bulbul:v2" }`, and `translate`, `transcribe` and friends as tools), keep personal data from the model (`guard { pii: mask bias: warn }`), use knowledge graphs, script-defined personas and remote skills, and reach any Gemini, Claude, Ollama or Sarvam endpoint (`provider Box { use: ollama base_url: "…" }`). `weave run --trace` shows every thought, tool call and cost live.
* **Durable Runs**: Every step is journaled (memory, file or SQL). A run waiting on a person holds no thread, resumes on any server when the answer arrives, and a crashed run picks up after its last step. The script doesn't change.
* **Data-Driven Routing**: `for each fix in review.fixes { delegate "{fix.task}" to {fix.owner} -> {fix.output} }`: iterate over what an agent returned and route each item to the agent it names, in one line.
* **Cost Budgets**: `budget { tokens: 200000 }` for a run, `budget { tokens: 20000 per_call: 2000 }` for an agent, `budget 5000 tokens` for a step or loop. Over-budget calls are refused before they reach the model; `weave run --max-tokens` caps any script, and every run reports where its tokens went.
* **Pause and Resume on Limits**: a rate limit or daily quota no longer kills a long-running workflow. ai-agent4j reads when the limit resets; Loom pauses the run (no thread held) and resumes it then. `budget { tokens: 100000 per day when_exhausted: suspend }` gives background agents a daily allowance, and `schedule { cron: "0 7 * * *" run: Digest() }` runs workflows on a schedule, all kept in a trigger store that cron, systemd, launchd, Windows Task Scheduler or Cloud Scheduler can wake (`weave triggers install`).
* **Bounded, Resilient Steps**: `loop until … max 5 … on_exhausted`, `retry 2 backoff 2s timeout 90s`, per-step `expecting { }` schemas and per-agent `temperature:`.

👉 **[Master Loom Orchestration](loom/ai-agent4j-loom/LOOM_GUIDE.md)** · **[Budgets, Pausing and Scheduling](loom/ai-agent4j-loom/BUDGETS_AND_SCHEDULING.md)** · **[ai-agent4j Budgets and Rate Limits](ai-agent4j/wiki/Budgets-and-Rate-Limits.md)**

## 🧠 The Memory: [Engram](engram/engram-core/)

**Engram** is a **Neuro-Symbolic Memory Engine** that solves the "Context Bloat" problem. It replaces naive transcript accumulation with a smart, synthesized retrieval-synthesis loop.

*   **Context Intelligence**: Automatically extracts key facts and synthesizes task-specific briefings.
*   **Constant Context**: Maintains high-signal prompts regardless of conversation length.
*   **Self-Correction**: Features an Introspection Loop that retroactively updates and shadows memories.

👉 **[Building Agentic Workflows with Loom & Engram](docs/AGENTIC_WORKFLOWS_GUIDE.md)**

## 🧪 The Judge: [eval4j](eval4j/)

**eval4j** is a ground-up evaluation framework for testing agents built with `ai-agent4j` — the
Java answer to what `deepeval` does for Python, built on AssertJ and JUnit 5 instead of ported
line-for-line from Python.

* **Fluent Assertions**: AssertJ-style custom assertions on `AgentResult` — `usesTool`,
  `usesToolsExactly`, `hasFinalAnswerContaining`, `isConfidentAbove`, and more.
* **LLM-as-Judge**: `LlmJudgeCondition` is a real AssertJ `Condition`, so judging correctness,
  relevancy, or groundedness composes with `.is(...)` and every other AssertJ combinator.
* **Standard Presets**: `correctness`, `answerRelevancy`, `faithfulness`/`groundedness`,
  `hallucinationFree`, the agent-specific `taskCompletion`, `bias`, and `toxicity`.
* **Golden Datasets**: author eval scenarios in YAML, still run through plain JUnit 5
  `@ParameterizedTest` — no separate config-driven test runner.
* **Pass-Rate Aggregation**: assert a dataset clears an overall pass-rate threshold instead of
  requiring every single noisy judge call to agree.

👉 **[Read the eval4j Documentation](eval4j/README.md)**

### 🧩 The Extensions: [RAG Addons](ai-agent4j-addons/)

For advanced use-cases, the **RAG Addons** module brings heavy-lifting capabilities while keeping the core light:

* **Local Embeddings**: Run **ONNX** and **DJL** models locally (no API costs).
* **Persistent Storage**: Store vectors in **PostgreSQL (pgvector)** or **Pinecone**.

👉 **[Read the Documentation](ai-agent4j/README.md)**

---

## ⚡ The Flagship: [GetViral](examples/getviral/)

**GetViral** is a creator studio that uses *every* module in this repo. Drop one idea and twelve AI agents turn it into a ready-to-post pack for **X**, **Instagram Reels** and **YouTube**. It runs as a multi-user website that deploys to Google Cloud Run, and still starts on a laptop with one command.

* **Loom** runs the workflow: PII guardrail, parallel specialists, a critic `loop until`, a bounded quality loop that routes each fix with one `for each`, and human hook-pick and publish gates that suspend the run instead of holding a thread.
* **Prompts written live**: a Showrunner agent writes every specialist's system prompt per brief and rewrites them after critic feedback.
* **Research before writing**: a Researcher agent searches the web (DuckDuckGo, Google Search via Gemini, GDELT news, Wikipedia), reads the best sources and hands the team a dossier where every fact carries its source.
* **Live trends** from free public REST APIs (Wikipedia, Hacker News, Mastodon, Datamuse, Apple Music, Openverse, Nager.Date).
* **RAG** over a viral playbook and your past posts (addons), plus **Engram** memory that makes every run sharper.
* **Images and video**: an ArtDirector agent generates the thumbnail, cover and B-roll (Gemini, free Pollinations.ai, or a local render), and a VideoEditor agent renders the Reel into a real MP4.
* **Not done until it's right**: the Showrunner reviews the finished build against the quality gate (files, platform limits, originality, eval4j judges) and sends every failing artifact back to its specialist until X, Instagram and YouTube all pass.
* **Original over time**: every casting is remembered; the Showrunner is dealt lenses and visual styles the creator hasn't used, and an originality gate (Engram similarity) sends repeats back.
* **Durable runs**: every step is journaled, so a pack waiting for its creator holds no thread, and a pack whose server restarts picks up where it left off. The Reel ships as an Instagram-ready MP4 (which doubles as the YouTube Short) plus a WebM copy so it plays in every browser, and a live build tracker shows every artifact being made and checked.
* **eval4j** grades every pack at runtime and gates the test suite. Publishes to Instagram only with your approval.
* **A real website**: Google sign-in, onboarding with connected Instagram/YouTube/X accounts, a library of everything you've made, quotas, Postgres and a Cloud Run deployment guide.

👉 **[Get viral](examples/getviral/README.md)** (runs with no API key)

---

## 🚀 The Showcase: [Hexamind Hub](examples/hexamind-hub/)

**Hexamind Hub** demonstrates what `ai-agent4j` can do. It is a "Digital Boardroom" where 6 specialized AI agents (including a Cynical Skeptic and a Creative Thinker) collaborate to solve your problems.

* **Multi-Agent Orchestration**: See how different personas debate, critique, and build consensus.
* **Real-Time**: Built with Spring Boot and WebSockets for a live, streaming experience.
* **Visual**: A stunning, modern UI to watch the AI thought process unfold.

👉 **[Launch Hexamind Hub](examples/hexamind-hub/README.md)**

---

## 🏭 The Factory: [Nirmaan Yantra](examples/nirmaan-yantra/)

**Nirmaan Yantra** is an autonomous software factory where a team of AI agents builds entire applications from a single-line prompt.

* **Autonomous Workflow**: Spec -> Test -> Code -> QA -> Release.
* **Self-Healing**: Automatically fixes compilation errors and missing dependencies.
* **Loop Prevention**: Detects dead-ends and "reboots" the implementation process.
* **Real-Time Dashboard**: Watch Vihaan (Dev), Dhruv (QA), and others collaborate live.

👉 **[Enter the Factory](examples/nirmaan-yantra/README.md)**

---

## 🐈 The Companion: [Kingini](examples/kingini/)

**Kingini** is a voice-first AI agent designed for children, featuring a wise and whimsical Kerala cat persona.

*   **Voice-First**: Talk naturally in Malayalam.
*   **Persona**: A character-driven AI with a unique backstory and voice ("Ritu").
*   **Tech**: Spring Boot + Sarvam AI (STT/LLM/TTS) + Web Audio API.

👉 **[Meet Kingini](examples/kingini/README.md)**

---

## 📧 The Connector: [Gmail MCP App](examples/gmail-mcp-app/)

**Gmail MCP App** demonstrates the power of the **Model Context Protocol**. It connects your LLM directly to your Gmail inbox, allowing agents to read, draft, and send emails securely.

*   **MCP Server**: Implements the Model Context Protocol for email.
*   **Secure**: Uses OAuth2 for authentication.
*   **HITL-Ready**: The Gmail send action is a perfect candidate for `requiresApproval()` — the agent will ask for human confirmation before sending any email.
*   **Agent-Ready**: Plug-and-play with any MCP-compliant client (like Claude or `ai-agent4j` agents).

---

> [!TIP]
> **[Why AI Agent4J? Read our comparison against LangChain4j and Spring AI](ai-agent4j/wiki/WHY_AI_AGENT4J.md)**
>
> **[xAI Beyond Black Boxes: Our 95% Compliance Guide](ai-agent4j/wiki/xAI_BEYOND_BLACK_BOXES.md)**
>
> **[Why eval4j? Our comparison against deepeval](ai-agent4j/wiki/WHY_EVAL4J.md)**
>
> **[Version Matrix](docs/VERSION_MATRIX.md)** for canonical coordinates and compatibility.
>
> **[Migration Guide 5.0](docs/MIGRATION_GUIDE_5_0.md)** for legacy coordinate upgrades.

## 📐 Project Standards

- [Testing Strategy](docs/TESTING_STRATEGY.md)
- [API Compatibility Policy](docs/API_COMPATIBILITY.md)
- [Contributing Guide](CONTRIBUTING.md)

## 💡 Our Philosophy

1. **Java First**: AI isn't just for Python. Java's strong typing, concurrency, and ecosystem make it perfect for building robust AI systems.
2. **Ground Up**: We minimize dependencies. By building our own ReAct loop and provider clients, we gain full control and understanding of the LLM's behavior.
3. **Transparency**: We believe in "glass-box" AI. You should be able to see exactly what your agent is thinking and why it made a decision.

---

MIT License
