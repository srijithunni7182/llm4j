<p align="center">
  <img src="docs/images/hero_llm4j.svg" width="640" alt="llm4j: ai-agent4j builds agents, Loom orchestrates them, eval4j tests them">
</p>

<h1 align="center">llm4j</h1>

<p align="center">
  <b>Find the security holes in your AI agent before it runs.</b><br>
  <code>weave audit</code> reads a workflow, names the mistake, the line and the fix. Offline, free, no API key.
</p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/io.github.srijithunni7182/ai-agent4j"><img alt="Maven Central" src="https://img.shields.io/maven-central/v/io.github.srijithunni7182/ai-agent4j.svg?label=Maven%20Central"></a>
  <a href="https://www.oracle.com/java/technologies/downloads/#java17"><img alt="Java 17+" src="https://img.shields.io/badge/Java-17%2B-orange"></a>
  <a href="LICENSE"><img alt="License: Apache 2.0" src="https://img.shields.io/badge/License-Apache_2.0-blue.svg"></a>
</p>

## See it in 30 seconds

Here is a small agent. It reads the web, reads your customer files and can send mail. That is the ☠️ *lethal trifecta*: text it reads on a web page can tell it to mail your customers' data out.

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
jbang weave@srijithunni7182/llm4j audit main.loom
```

```
**4 findings**: 1 high, 2 medium, 1 low, 0 info.

### HIGH | LA01 | One agent holds the lethal trifecta with an open way out
- Where: Helper (line 7)
- OWASP: LLM01:2025 Prompt Injection, LLM02:2025 Sensitive Information Disclosure, LLM06:2025 Excessive Agency
- Fix: Split the work: one agent reads the outside world, another touches your data,
  a third sends; connect them with expecting { ... }.

### MEDIUM | LA03 | No run budget
### MEDIUM | LA02 | An effect with no approval
### LOW    | LA04 | Private data with no PII guard
```

It exits non-zero, so it can gate CI. (Output trimmed; run it for the full text.)

**In code-first frameworks the architecture is a convention. In Loom it is checked.** In the [probes](probes/README.md#p11-a-reply-that-breaks-a-written-rule-can-it-leave-without-a-check), the same mistakes built on LangGraph, LangChain and Google ADK raised zero warnings; rerun them yourself with fake models and no key. [More on reviewable architecture](docs/REVIEWABLE_ARCHITECTURE.md).

## Try it

```
jbang weave@srijithunni7182/llm4j try        # a demo workflow: Java 17, one line, no key, no account
weave generate "Turn meeting notes into action items and post them to a team webhook after a person approves."
```

Or install the **Loom** extension for VS Code (Java 17 is all it needs) and run *Loom: Try a Demo*.
To keep `weave` on your PATH: `jbang app install weave@srijithunni7182/llm4j` ([how](install/README.md)).

## What else is in the box

`weave audit` is one check of a whole stack, all in plain Java with no vendor SDKs.

- 🧵 **Loom**, a small workflow language: journaled, resumable, budgeted, with approvals before anything leaves. Tools such as `webhook`, `email`, `http`, `file`, `shell` and read-only `sql` carry allow-lists. ([Why Loom](src/loom/ai-agent4j-loom/WHY_LOOM.md))
- 🏗️ **ai-agent4j**, the library: ReAct agents, tools, memory, RAG and budgets over Gemini, Claude, OpenAI, xAI, Ollama and more.
- 🧪 **Testing without a model**: scripted models, golden datasets and LLM judges (**eval4j**). A bad production run becomes a test.
- 🔭 **Watching a run**: a live page per run, or one server for many, with cost, context, incidents, pause and stop.
- 🛡️ **Secure by construction**: the model reasons and the code holds the authority. ([The controls](docs/security/secure-by-construction.md), [Security guide](SECURITY.md))

[**Developer guide**](DEVELOPER_GUIDE.md) · [Command reference](docs/COMMAND_REFERENCE.md) · [The whole stack at a glance](docs/OVERVIEW.md) · [Examples](docs/EXAMPLES.md) · [Docs index](docs/README.md)

## Use it as a library

```xml
<dependency>
    <groupId>io.github.srijithunni7182</groupId>
    <artifactId>ai-agent4j</artifactId>
    <version>6.2.0</version>
</dependency>
```

```java
ReActAgent support = ReActAgent.builder()
        .llmClient(client)
        .addTool(new RefundTool(payments))                   // requiresApproval(args) puts a person in the loop
        .budget(Budget.builder().tokens(20_000).build())     // it cannot overspend
        .build();

assertThat(support.run(question)).usesTool("refund").completedSuccessfully();   // eval4j, in plain JUnit
```

[Quick Start](src/ai-agent4j/wiki/Getting-Started.md) · [Secret Store](src/ai-agent4j/wiki/Secret-Store.md) · [How it compares](docs/WHY_LLM4J.md) (to [LangChain4j and Spring AI](src/ai-agent4j/wiki/WHY_AI_AGENT4J.md), [LangGraph](src/loom/ai-agent4j-loom/WHY_LOOM.md), [deepeval](src/ai-agent4j/wiki/WHY_EVAL4J.md))

---

Apache License 2.0 - see [LICENSE](LICENSE) and [NOTICE](NOTICE). [Contributing](CONTRIBUTING.md) · [About](docs/ABOUT.md)
