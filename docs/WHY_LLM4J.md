<p align="center">
  <img src="images/developer-guide/hero.svg" width="100%" alt="llm4j: the one stop shop for rapid development of enterprise ready autonomous agentic workflows">
</p>

<h1 align="center">🏆 Why teams choose llm4j and Loom</h1>

<p align="center">
  <b>Most agent stacks give you the parts. llm4j gives you the <i>guarantees</i>: and the tools to prove them.</b>
</p>

<p align="center">
  <a href="../README.md">README</a> ·
  <a href="../DEVELOPER_GUIDE.md">🚀 Developer guide</a> ·
  <a href="COMMAND_REFERENCE.md">📖 Command reference</a> ·
  <a href="../src/loom/vscode-loom/README.md">🧩 VS Code extension</a>
</p>

---

## 🎯 What you get out of the box, and what you would otherwise build

| You need | 🧵 llm4j + Loom | On the usual path |
|---|---|---|
| 🔁 **A crash must never send twice** | Every step and effect is **journaled**. After a crash, a send whose outcome is unknown is **not run again** (you check, then retry), and an idempotent one runs again with **the same key the runtime supplies**. Nothing for you to design. | LangGraph's docs say the resumed node **runs again from its start** and that side effects before an interrupt "should (ideally) be idempotent". In our [crash probe](../probes/README.md), LangGraph 1.2.18 **sent twice** in every durability mode when the process died right after the send, and with its default settings even after the step had been recorded. |
| 💸 **A run must not overspend** | **Tokens, calls and dollars**, per run and per step, enforced **before** each model call. | LangChain 1.4.7's built-in limit middleware (`ModelCallLimitMiddleware`, `ToolCallLimitMiddleware`) count **model calls and tool calls**; a token or dollar cap is yours to write ([probe](../probes/README.md)). |
| 🧪 **Test the logic without a model, for free** | `weave test`: scripted models and tools, failures injected on a schedule, **step coverage**, in milliseconds. **The tool writes the cases** that reach every branch. | LangGraph's testing guide shows pytest with an in-memory checkpointer; the cases are yours to write. |
| 🛡️ **Review for security before anything runs** | `weave review` and `weave audit` read the **whole script**: the lethal trifecta, unapproved effects, missing budgets, mapped to the **OWASP Top 10 for LLM Applications**. | Guardrails (such as LangChain4j's input guardrails, including a prompt-injection pattern guardrail) check what arrives **at run time**. |
| ✍️ **Start from a sentence** | `weave generate`: a plan you read first, then a project that passed **five gates** (it checks, it reviews clean, its tests pass, they reach the steps, it matches the plan). | LangGraph graphs are written in code. |
| 📊 **Evaluate, and keep the report** | `weave eval` and an **eval4j** report you can open, compare and share, **on your own machine, free**. | LangSmith is a hosted service; its self-hosted option is on the Enterprise plan. |
| 🔭 **Watch it live** | `--observe` and **Fabric**: cost, context windows, incidents, injection and trifecta signals, a time bar, **Pause / Suspend / Stop**. **Self-hosted, free.** | Tracing and dashboards are LangSmith features, hosted; self-hosting is an Enterprise-plan option. |
| ☕ **A stack for Java teams** | Your agents, workflows, tests and evaluation in the language your services are written in: JUnit, Spring, Maven. | LangChain and LangGraph are Python and JavaScript; LangChain4j's `agentic` module is documented as experimental. |
| 🏢 **Compliance, mapped** | Audit events, PII masking, approvals, secrets that never become text, **mapped to GDPR, the EU AI Act and the NIST AI RMF**. | See each project's own documentation. |

<sub>Compared with the public documentation of LangChain and LangGraph (v1), LangSmith and LangChain4j as read in October 2026. The crash and budget rows are [reproducible probes](../probes/README.md): fake models, no keys, one script per stack.</sub>

---

## 🔌 Bring every integration you already like. Nothing stops you.

llm4j does not ask you to give anything up. A tool in Loom can be **any Java class** (`tool Invoices { use: class  class: "com.acme.tools.InvoiceTool" }`), **any MCP server** (`mcp Name { ... }`), **any REST API through its OpenAPI spec** (`use: openapi`), or a plain `http` or `webhook` call. So the connectors, vector stores, retrievers and loaders you like from the wider ecosystem plug straight in: a Java library (LangChain4j's included) is a thin `Tool` class away, and Python or JavaScript components come in through MCP or HTTP.

And here is the part that matters: **whoever wrote the tool, Loom puts the same guarantees around it.**

| Around every tool, built in or yours | |
|---|---|
| 🔐 **Authority** | Tools are granted **per agent**; an effect can require **a person's approval**. |
| 🔁 **Safety** | Its effects are **journaled**, so a resume never repeats them. |
| 💸 **Cost** | Its calls count against the **budget**, checked before the next model call. |
| 🛡️ **Review** | Declare what it reaches (`reach: reads`, `fetches`, `writes`, `sends`) and the **review** finds the lethal trifecta before it runs. |
| 🧪 **Tests** | It is **scripted in tests**, so the logic around it is tested without calling it. |
| 🔭 **Visibility** | Its health shows up **live in Fabric**. |

Keep your integrations. Gain the guarantees.

---

## 🧩 Pick your workflow

You don't need every capability for every workflow. Here is what the stack does for the shapes teams build most:

| Your workflow | What you get |
|---|---|
| 🏷️ **A router**: classify a message, answer or escalate | `intentclassify` gives exact, typed branching with no free-text guesswork. **Generated tests reach every label**, for $0. |
| ✅ **An approval pipeline**: draft, a person approves, send | `approve:` in front of the send, the answer **journaled**, the send **never repeated** after a restart, the approval path **reviewed before it runs**. Answer on the console, by a command, or on Telegram. |
| ⏰ **A scheduled digest that runs for days** | Schedules, `weave daemon` and `weave tick` (for cron, systemd, launchd), runs that **sleep and resume**, budgets that hold across restarts. |
| 🔎 **A research loop**: tools, a critic, bounded rounds | A loop with a **hard bound**, a **budget checked before every call**, context windows watched and **compaction** shown live in Fabric. |
| ☠️ **Untrusted input plus tools**: the security case | The **lethal trifecta** found by the review before it ever runs; effects behind approvals; PII masked before it reaches a model or a log. |
| 🏭 **A multi-agent workflow** | Retries, timeouts and `on_failure` handlers **tested by injecting the failure** (no waiting), a live graph, and `weave why` to explain every choice a run made. |

---

## 🚀 See it in five minutes

```
jbang weave@srijithunni7182/llm4j try
```

Or install the VS Code extension, open a folder and run **Loom: Try a Demo**. No key, no account, nothing written. [The developer guide](../DEVELOPER_GUIDE.md) takes it from a sentence to a deployed, watched workflow.

<p align="center">
  <a href="../DEVELOPER_GUIDE.md"><img alt="Take the tour" src="https://img.shields.io/badge/Take%20the%20tour-developer%20guide-2ea44f?style=for-the-badge"></a>
</p>
