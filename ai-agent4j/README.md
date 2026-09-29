# AI Agent4J (Formerly Gemini ReAct Java)

> [!NOTE]
> This project was formerly known as `gemini-react-java`.
> **The Lightweight LLM Library for Java developers.**

<img src="docs/images/hero.png" width="50%" alt="AI Agent4J Hero">

**Build autonomous agents, RAG pipelines, and specialized tools with Google Gemini, Anthropic Claude, Sarvam AI, and local LLMs.**

[![Maven Central](https://img.shields.io/maven-central/v/io.github.srijithunni7182/ai-agent4j.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.srijithunni7182/ai-agent4j)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-orange)](https://www.oracle.com/java/technologies/downloads/#java17)

`ai-agent4j` is a high-performance, modular LLM library for Java that prioritizes simplicity and correctness. It provides a unified API for cloud providers (Gemini, Claude), regional specialists (Sarvam AI), and local models (Ollama). Every provider meets [one contract](wiki/Providers-and-the-Uniform-Contract.md): the same requests, finish reasons, exceptions and streaming, so you switch models by changing a name.

---

## 📊 Project Stats

| 📏 **Lines of Code** | 🧪 **Test Cases** | ⏱️ **Development Time** | 📦 **Commits** | 🧠 **Supported LLMs** | 🪶 **Library Size** |
| :---: | :---: | :---: | :---: | :---: | :---: |
| **13,700+** | **438+** | **1.5+ Months** | **36+** | **Gemini, Sarvam, Ollama** | **~308 KB** |

---

## 📚 Documentation Hub

Explore the full capabilities of the framework through our detailed guides:

### Core Framework

- [**Quick Start Guide**](wiki/Getting-Started.md) — Get up and running in 5 minutes.
- [**ReAct Agent Guide**](wiki/ReAct-Agent-Guide.md) — Reasoning, tool-use, and the Thought-Action-Observation loop.
- [**Memory & Persistence**](wiki/Memory-and-Persistence.md) — Managing conversation history and long-term storage.
- [**Real-time Streaming**](wiki/Thought-Streaming.md) — Capturing agent "thoughts" for responsive UIs via SSE/WebSockets.
- [**Advanced Configuration**](wiki/Advanced-Configuration.md) — Retry policies, custom tools, and error handling.
- [**Budgets and Rate Limits**](wiki/Budgets-and-Rate-Limits.md) — Cap tokens, calls or money; budgets that refill; reading 429s and pausing until the limit lifts.

### Advanced Features

- [**Agent Personas**](wiki/Agent-Personas.md) — Configuring behavioral traits and expertise.
- [**Agent Skills**](wiki/Agent-Skills-Guide.md) — Injecting domain knowledge via Markdown files.
- [**Semantic Long-Term Memory**](wiki/SEMANTIC_MEMORY.md) — Vector-backed recall of user facts.
- [**Prompt Registry (xAI Standard)**](wiki/Prompt-Registry-Guide.md) — Versioned, externalized prompt management.
- [**RAG & Embeddings**](wiki/RAG-Support.md) — Retrieval-Augmented Generation and Vector Stores.
- [**Knowledge Graphs**](wiki/Knowledge-Graphs.md) — Reasoning over structured entity-relationship data.

### Integrations

- [**🇮🇳 Sarvam AI Guide**](docs/SARVAM.md) — Indian language voice agents (TTS, STT, Translation).
- [**🏠 Ollama Integration**](docs/OLLAMA.md) — Running local models like Gemma and Llama with zero cost/internet.
- [**🔌 MCP Integration**](wiki/MCP-Integration.md) — Connecting to Model Context Protocol servers.

---

## 🚀 Key Features

- **🔁 One Contract, Any Provider**: Gemini, Claude, Sarvam and Ollama behave the same — requests, finish reasons, exceptions, token usage and streaming — checked by a shared conformance suite and a key-gated live suite. See [Providers and the Uniform Contract](wiki/Providers-and-the-Uniform-Contract.md).
- **🤖 Google Gemini Native**: Optimized support for Gemini 1.5 Flash, Pro, and 2.x.
- **🧠 Anthropic Claude**: `AnthropicProvider` over plain HTTP (no SDK): handles Claude's quirks (top-level system prompt, required `max_tokens`, models that reject `temperature`, refusals, 529 "overloaded") and streams.
- **🛠️ ReAct Agent Framework**: Built-in reasoning loops with self-correction.

### Steps to Create a Tool

1. **Implement the `Tool` interface**: Define the tool's name, description, and execution logic.
2. **Add to the Agent Builder**: Register your tool so the agent can discover it.

- **🧬 Autonomous Orchestration**: Manager-Worker patterns with agent delegation.
- **⏰ Scheduled Tasks**: Native support for recurring autonomous background actions.
- **🚦 Intelligent Provider Routing**: Cost-aware routing and automatic rate-limit failover.
- **🔍 xAI Standards Compliance**: Transparent reasoning and audit trails for explainable AI.
- **🔒 Private & Local**: Zero-cost, 100% private retrieval via `rag-addons`.
- **💸 Cost Budgets**: Cap tokens, calls or money per agent; over-budget calls are refused before they reach the model. See below.

### Cost budgets

> The full guide — windows, `BudgetSet`, price tables, events, rate-limit parsing, recipes and API
> reference — is [**Budgets and Rate Limits**](wiki/Budgets-and-Rate-Limits.md).

Every token an agent spends goes through `LLMClient.chat()`, so that is where budgets are enforced:

```java
Budget budget = Budget.builder().tokens(50_000).calls(40).warnAt(0.8).build();

ReActAgent agent = ReActAgent.builder()
        .llmClient(client)
        .budget(budget)                 // refuse calls that can't be paid for
        .maxTokensPerCall(1500)         // and cap every answer
        .build();

AgentResult r = agent.run("Research the topic");
if (r.budgetExhausted()) {             // ran out part-way: this is its best answer so far
    System.out.println(r.getFinalAnswer() + " — " + r.getBudgetExceeded().getMessage());
}
System.out.println(budget.spent());    // tokens, calls, cost, and whether any usage was estimated
```

- **Before each call** the budget is checked and the call reserved; a call that can't fit throws
  `BudgetExceeded` without reaching the model (it is never retried). The answer's `maxTokens` is lowered
  to what is left.
- **After each call** the reservation is replaced by the provider's reported usage, or an estimate
  (about 4 characters per token) marked as estimated when a provider reports none.
- **Money** needs your own `PriceTable` (`PriceTable.load(path)`, lines of `model = input / output` per
  million tokens); none ship with the library. `ollama/*` models are free.
- **Many budgets at once** — run, agent, step — go in a `BudgetSet`; a call must fit them all and is charged
  to each. Concurrent calls can't jointly overspend. Use `BudgetedLLMClient` to meter any client directly.
- `onBudgetExhausted(BudgetPolicy.FAIL)` throws instead of returning the partial answer.
- **Budgets that refill**: `Budget.builder().tokens(100_000).window(Window.DAY)` limits spend per minute,
  hour or day; a refusal says when it refills (`BudgetExceeded.resetAt()`), and
  `onBudgetExhausted(BudgetPolicy.SUSPEND)` turns it into `RateLimited` for a harness to pause on.

### Rate limits

A provider's 429 says when to come back. The HTTP layer reads it — Gemini's error body (`RetryInfo`,
`QuotaFailure`; a per-day quota resets at midnight Pacific), Anthropic's `anthropic-ratelimit-*-reset`,
OpenAI's `x-ratelimit-reset-*`, or `Retry-After` — and:

- waits out limits that lift within 30 s, retrying exactly when they reset (plus a little jitter);
- raises `RateLimitException` for longer ones, with `info()` giving the reset **instant**, the kind of limit
  (requests, tokens, daily quota) and whether the time was estimated (no reset information: 60 s, doubling
  on repeated 429s, up to an hour).

```java
try {
    agent.run("Summarise today's papers");
} catch (RateLimited limited) {           // an AgentInterrupt: never retried, never a half answer
    scheduleRetryAt(limited.resetAt());   // e.g. 2026-09-28T07:00:00Z for Gemini's free-tier daily quota
}
```

`RetryPolicy.builder().inlineWaitThreshold(…).fallbackDelay(…).dailyResetZone(…)` tunes this. A refused call
counts as a call against a budget but costs no tokens, and `RoutingLLMClient` reports the soonest reset when
every tier is limited. Loom builds on this to pause workflows and resume them automatically.

---

## Recent Module Updates

Additive notes describing recent functional work in the `ai-agent4j` module:

- **ReAct agent & event handling**: Improved event hooks and tool invocation paths in `ReActAgent` and `AgentEventListener` to make tool execution more observable and reliable.
- **Human-in-the-Loop (HITL)**: An `ApprovalCallback` pattern was formalized to make approvals explicit for sensitive operations. See the `HumanInTheLoopTest` for usage examples.
- **Sarvam provider**: `SarvamChatProvider` now handles recent Sarvam API changes and improves response parsing and error handling.
- **Loom runtime integration**: Small compatibility and harnessing improvements to better integrate `ai-agent4j` agents with the Loom orchestration runtime.
- **Tests & Benchmarks**: Updated integration tests and benchmark artifacts to reflect storage and performance adjustments.

These notes are intentionally additive — they summarize intent and guidance for maintainers. If you want me to expand any bullet into an exact code-level changelog (file + relevant method summaries), tell me which items to expand.

## 🏗️ Architecture

```mermaid
flowchart TB
    UserCode["User Application"]:::user

    subgraph Orchestration ["Orchestration & Planning"]
        Manager["Manager Agent"]:::agent
        SubAgent["Sub-Agents"]:::agent
        Planner["ReAct Loop<br/>(Thought-Action)"]:::agent
        Scheduler["Agent Scheduler"]:::agent
    end

    subgraph Memory ["Memory & Context"]
        History["Conversation History<br/>(Short-term)"]:::memory
        Semantic["Semantic Memory<br/>(Long-term Vector)"]:::memory
        Registry["Prompt Registry<br/>(xAI Standard)"]:::memory
    end

    subgraph Tooling ["Tooling Layer"]
        RegistryT["Tool Registry"]:::tool
        Builtin["Built-in Tools<br/>(Calc, Search, Time)"]:::tool
        MCP["MCP Connectors"]:::tool
        OpenAPI["OpenAPI Discovery"]:::tool
        Delegate["Delegation Tool"]:::tool
    end

    subgraph Core ["LLM Intelligence"]
        Router["Routing LLM Client<br/>(Cost/Fallback)"]:::core
        GoogleP["Google Provider"]:::core
        SarvamP["Sarvam Provider"]:::core
        OllamaP["Ollama Provider"]:::core
    end

    subgraph External ["External Services"]
        Gemini[("Google Gemini")]:::external
        Sarvam[("Sarvam AI")]:::external
        Ollama[("Local Ollama")]:::external
        Web["Web / APIs / MCP"]:::external
    end

    %% Connections
    UserCode --> Manager
    Manager --> Planner
    Planner --> SubAgent
    Planner --> Registry
    Planner --> History
    Planner --> RegistryT
    Planner --> Router

    Manager --> Scheduler
    Manager --> Semantic
    
    RegistryT --> Builtin
    RegistryT --> MCP
    RegistryT --> OpenAPI
    RegistryT --> Delegate
    Delegate --> SubAgent

    Router --> GoogleP
    Router --> SarvamP
    Router --> OllamaP

    GoogleP --> Gemini
    SarvamP --> Sarvam
    OllamaP --> Ollama
    Builtin --> Web
    MCP --> Web
    OpenAPI --> Web

    classDef user fill:#e1f5fe,stroke:#01579b,stroke-width:2px,color:#000;
    classDef core fill:#f3e5f5,stroke:#4a148c,stroke-width:2px,color:#000;
    classDef agent fill:#e8f5e9,stroke:#1b5e20,stroke-width:2px,color:#000;
    classDef tool fill:#fff3e0,stroke:#e65100,stroke-width:2px,color:#000;
    classDef memory fill:#e0f2f1,stroke:#00695c,stroke-width:2px,color:#000;
    classDef external fill:#fee,stroke:#b71c1c,stroke-width:2px,color:#000;
```

---

## Installation

### Maven

```xml
<dependency>
    <groupId>io.github.srijithunni7182</groupId>
    <artifactId>ai-agent4j</artifactId>
    <version>5.0</version>
</dependency>
```

### Gradle

```kotlin
implementation("io.github.srijithunni7182:ai-agent4j:5.0")
```

---

## Quick Start (Google Gemini)

```java
import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.LLMClient;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.provider.google.GoogleProvider;

public class GeminiExample {
    public static void main(String[] args) {
        LLMConfig config = LLMConfig.builder()
                .apiKey(System.getenv("GOOGLE_API_KEY"))
                .defaultModel("gemini-1.5-flash")
                .build();
        
        LLMClient client = new DefaultLLMClient(new GoogleProvider(config));
        
        LLMResponse response = client.chat(LLMRequest.builder()
                .addUserMessage("What is ai-agent4j?")
                .build());
                
        System.out.println(response.getContent());
    }
}
```

---

## License

This project is licensed under the MIT License - see the [LICENSE](../LICENSE) file for details.

## Support

For issues, questions, or contributions, please use the [GitHub Issues](https://github.com/srijithunni7182/llm4j/issues) page.
