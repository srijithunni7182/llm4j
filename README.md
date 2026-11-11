<p align="center">
  <img src="docs/images/hero_llm4j.svg" width="640" alt="llm4j: ai-agent4j builds agents, Loom orchestrates them, eval4j tests them">
</p>

<h1 align="center">llm4j</h1>

<p align="center">
  <b>AI agents, written the Java way.</b><br>
  Typed, testable, observable: from a single tool call to autonomous workflows that run for days.<br>
  <i>And a workflow language that <b>reviews your agent's architecture before it ever runs.</b></i>
</p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/io.github.srijithunni7182/ai-agent4j"><img alt="Maven Central" src="https://img.shields.io/maven-central/v/io.github.srijithunni7182/ai-agent4j.svg?label=Maven%20Central"></a>
  <a href="https://www.oracle.com/java/technologies/downloads/#java17"><img alt="Java 17+" src="https://img.shields.io/badge/Java-17%2B-orange"></a>
  <a href="LICENSE"><img alt="License: Apache 2.0" src="https://img.shields.io/badge/License-Apache_2.0-blue.svg"></a>
  <img alt="Providers" src="https://img.shields.io/badge/LLMs-Gemini%20%7C%20Claude%20%7C%20OpenAI%20%7C%20xAI%20%7C%20Perplexity%20%7C%20Kimi%20%7C%20Qwen%20%7C%20Sarvam%20%7C%20Ollama-blueviolet">
</p>

<p align="center">
  <a href="DEVELOPER_GUIDE.md"><img src="docs/images/developer-guide/graph-meeting-dark.png" width="49%" alt="The graph of a generated workflow in VS Code"></a>
  <a href="DEVELOPER_GUIDE.md"><img src="docs/images/developer-guide/review-meeting-dark.png" width="49%" alt="The review panel beside the graph"></a>
</p>

## 💬 Say what you want. Watch it get built, tested, reviewed and shipped.

```
jbang weave@srijithunni7182/llm4j try        # ⌨️ Java 17, one line, no key, no account
weave generate "Turn meeting notes into action items and post them to a team webhook after a person approves."
```

Or install the **Loom** extension in VS Code, press `Ctrl/Cmd+Shift+P` and run *Loom: Try a Demo*, then *Generate Workflow...*.
A sentence in; a **plan you read first**; a project that has passed five gates before it lands in your folder.
To keep `weave` on your PATH: `jbang app install weave@srijithunni7182/llm4j` ([how](install/README.md)).

**[🚀 Take the tour: the Developer guide →](DEVELOPER_GUIDE.md)** · [🎓 Tutorial](docs/tutorial/README.md) · [🧵 17 starters](src/loom/starters/README.md) · [📖 Command reference](docs/COMMAND_REFERENCE.md) · [⬇️ VS Code Marketplace](https://marketplace.visualstudio.com/items?itemName=srijithunni7182.vscode-llm4j-loom)

## ☠️ Your agent has a hole. `weave audit` finds it before an attacker does.

This agent reads the web, reads your customer files and can send mail. That is the *lethal trifecta*: text on a web page can tell it to mail your customers' data out.

```loom
tool Web   { use: duckduckgo }
tool Files { use: file  root: "customers"  mode: read }
tool Mail  { use: email  host: "smtp.example.com"  username: env.SMTP_USER  password: env.SMTP_PASSWORD
             from: "support@example.com"  to: "team@example.com" }

agent Helper {
    model: "any-model"
    system: "Research the customer's question and mail the team a summary."
    tools: [Web, Files, Mail]
}

workflow Main(question) {
    delegate "Research: {question}" to Helper -> summary
    return summary
}
```

```
$ weave audit main.loom        # offline, free, no API key; exits non-zero so it can gate CI

4 findings: 1 high, 2 medium, 1 low
```

| | Finding | The fix it prints |
|---|---|---|
| 🔴 **HIGH** `LA01` | One agent holds the lethal trifecta with an open way out | Split the work: one agent reads the outside world, another touches your data, a third sends; connect them with `expecting { ... }` |
| 🟠 **MEDIUM** `LA03` | No run budget | Add a run budget, e.g. `budget { tokens: 200000  calls: 150 }` |
| 🟠 **MEDIUM** `LA02` | An effect with no approval | Let the agent propose, approve once at the edge, act with code: `use Mail(...) approve` |
| 🟡 **LOW** `LA04` | Private data with no PII guard | Add `guard { pii: mask }` |

Each finding is mapped to the OWASP Top 10 for LLM Applications. **In code-first frameworks the architecture is a convention; in Loom it is checked.** In the [probes](probes/README.md#p11-a-reply-that-breaks-a-written-rule-can-it-leave-without-a-check) the same mistakes built on LangGraph, LangChain and Google ADK raised zero warnings, and you can rerun them with fake models and no key.
[Architecture you can review](docs/REVIEWABLE_ARCHITECTURE.md) · [What a coding agent sees](docs/REVIEWABLE_ARCHITECTURE.md#what-a-coding-agent-sees)

## The whole stack

<p align="center">
  <a href="DEVELOPER_GUIDE.md"><img src="docs/images/developer-guide/stack.svg" width="100%" alt="The whole stack: where you work, Loom, ai-agent4j, eval4j, Fabric, security, building blocks"></a>
</p>

| | | |
|---|---|---|
| 🏗️ [**ai-agent4j**](src/ai-agent4j/) | How do I build an agent? | ReAct agents, tools, skills, memory, RAG and budgets over Gemini, Claude, OpenAI, xAI, Perplexity, Kimi, Qwen, Sarvam, Ollama and any OpenAI-style server |
| 🧵 [**Loom**](src/loom/ai-agent4j-loom/) | How do many agents work together, reliably, for days? | A small workflow language: journaled, resumable, budgeted, scheduled, with approvals and earned autonomy ([why](src/loom/ai-agent4j-loom/WHY_LOOM.md)) |
| 🧪 [**eval4j**](src/eval4j/) | How do I know it works? | Test with no model and no spend, golden datasets, LLM judges, a report you can open and share. A bad production run becomes a test |
| 🔭 [**Inspect and Fabric**](src/loom/loom-inspect/) | What is it doing right now, and has it gone wrong? | A live page per run or one server for many: cost, context, incidents, pause and stop ([guide](docs/OBSERVABILITY.md)) |
| 🔧 [**Tools**](src/ai-agent4j-tools/) | How do I let an agent act safely? | `webhook`, `email`, `http`, `file`, `shell` and read-only `sql`, with allow-lists and journals |
| 🌱 [**Spring Boot starter**](src/spring/llm4j-spring-boot-starter/) | I already use Spring. | `LLMClient`s, agents, tools and the Loom runtime as beans |

[Every module, the skills for your coding agent, and the examples](docs/OVERVIEW.md) · [Module diagram](docs/THE_STACK.md)

> *A workflow is a language in itself. It is the harness we build on top of the transformers, and its intelligence is symbolic. Loom was born from the desire to see the workflow, to preserve its beauty, and to get feedback on the workflow and not on the plumbing.*
>
> **Srijith Unni**, [why I built Loom](src/loom/ai-agent4j-loom/WHY_LOOM.md)

> [!IMPORTANT]
> **The most complete explainable-AI toolkit for Java agents.** Every step is an immutable audit event, each run carries a confidence score with `shouldEscalateToHuman()`, PII is masked before it reaches a model or a log, and bias monitors can flag or block a response, mapped to GDPR, the EU AI Act and the NIST AI RMF in [xAI: Beyond Black Boxes](src/ai-agent4j/wiki/xAI_BEYOND_BLACK_BOXES.md).

## Or just use the library

An agent is as ordinary as a `PaymentService`: built with a builder, tested with JUnit, bounded by a budget.

```xml
<dependency>
    <groupId>io.github.srijithunni7182</groupId>
    <artifactId>ai-agent4j</artifactId>
    <version>6.2.0</version>
</dependency>
```

```java
ReActAgent support = ReActAgent.builder()
        .llmClient(client)                                   // Gemini, Claude, OpenAI, xAI, Ollama and more: one contract
        .addTool(new RefundTool(payments))                   // requiresApproval(args) puts a person in the loop
        .budget(Budget.builder().tokens(20_000).build())     // it cannot overspend
        .build();

assertThat(support.run(question)).usesTool("refund").completedSuccessfully();   // eval4j, in plain JUnit
```

[Quick Start](src/ai-agent4j/wiki/Getting-Started.md) · [Secret Store](src/ai-agent4j/wiki/Secret-Store.md) · [Security guide](SECURITY.md) · [Examples](docs/EXAMPLES.md) · [A2A](docs/a2a/README.md) · [Docs index](docs/README.md)

> [!TIP]
> How it compares: [Why teams choose llm4j and Loom](docs/WHY_LLM4J.md) · [vs LangChain4j and Spring AI](src/ai-agent4j/wiki/WHY_AI_AGENT4J.md) · [Loom vs LangGraph](src/loom/ai-agent4j-loom/WHY_LOOM.md) · [eval4j vs deepeval](src/ai-agent4j/wiki/WHY_EVAL4J.md)

**Philosophy:** Java first. Ground up, with minimal dependencies. Glass-box AI you can see inside. Authority in code, not in prompts.

---

Apache License 2.0 - see [LICENSE](LICENSE) and [NOTICE](NOTICE). [Contributing](CONTRIBUTING.md) · [About and history](docs/ABOUT.md)
