<p align="center">
  <img src="docs/images/developer-guide/hero.svg" width="100%" alt="llm4j: the one stop shop for rapid development of enterprise ready autonomous agentic workflows">
</p>

<p align="center">
  <img alt="Java 17+" src="https://img.shields.io/badge/Java-17%2B-f89820?style=for-the-badge&logo=openjdk&logoColor=white">
  <img alt="No vendor SDKs" src="https://img.shields.io/badge/vendor%20SDKs-none-2ea44f?style=for-the-badge">
  <img alt="Providers" src="https://img.shields.io/badge/LLM%20providers-9%2B-7c5cff?style=for-the-badge">
  <img alt="OWASP" src="https://img.shields.io/badge/OWASP%20LLM%20Top%2010-mapped-e44f9b?style=for-the-badge">
  <img alt="Compliance" src="https://img.shields.io/badge/GDPR%20%C2%B7%20EU%20AI%20Act%20%C2%B7%20NIST%20AI%20RMF-mapped-1b8bff?style=for-the-badge">
  <img alt="Tests" src="https://img.shields.io/badge/agent%20tests-%240-14b88a?style=for-the-badge">
  <a href="LICENSE"><img alt="Apache 2.0" src="https://img.shields.io/badge/License-Apache%202.0-blue?style=for-the-badge"></a>
</p>

<p align="center">
  <a href="README.md">README</a> ·
  <b>🚀 Developer guide</b> ·
  <a href="docs/COMMAND_REFERENCE.md">📖 Command reference</a> ·
  <a href="src/loom/vscode-loom/README.md">🧩 VS Code extension</a> ·
  <a href="wiki/Home.md">📚 Wiki</a> ·
  <a href="CONTRIBUTING.md">🤝 Contributing</a> ·
  <a href="SECURITY.md">🔒 Security</a>
</p>

<h2 align="center">💬 Say what you want. 🏗️ Watch it get built, 🧪 tested, 🛡️ reviewed, 🔭 watched and 🚢 shipped.<br>All of it in Java. Very little effort. Very few tokens.</h2>

---

## ⚡ Try it in the next five minutes. No key. No account. Nothing to install but Java.

```
jbang weave@srijithunni7182/llm4j try
```

One line, and a real workflow runs in front of you: a classifier routes a message, an agent answers, **Loom itself checks every branch** (no model decides it), a person is asked before anything goes out, and every step is explained in plain English as it happens. Cost: **$0**. Files written: **none**.

<p align="center">
  <img src="docs/images/developer-guide/terminal-try.png" width="900" alt="weave try: a workflow runs on demo models and explains each step">
</p>

Then make one of your own. 👇

---

## 🎯 Why teams reach for it

| | | |
|---|---|---|
| ⚡ **Rapid** | A **sentence** becomes a tested, reviewed project in minutes, for a few cents. | `weave generate "..."` |
| 🏢 **Enterprise ready** | Approvals, budgets, audit trail, PII masking, secrets, an OWASP audit. Built in, not bolted on. | `weave audit` |
| 🤖 **Truly autonomous** | Workflows that run for **days**: journaled, resumable, scheduled, crash-safe, with autonomy that is *earned*. | `weave run --journal` |
| 🧪 **Testable** | Agent logic tested with **no model, no key, no spend**, in milliseconds. The tool writes the tests. | `weave test` |
| 🔭 **Observable** | Cost, context, incidents and injection signals live, on one page or across a fleet. | `weave run --observe` |
| ☕ **Java-native** | An agent is as ordinary as a `PaymentService`: builders, JUnit, Spring, Maven. No vendor SDKs. | `ai-agent4j` |

---

## 🧱 The whole stack. One experience.

<p align="center">
  <img src="docs/images/developer-guide/stack.svg" width="100%" alt="The llm4j stack: where you work, Loom, ai-agent4j, eval4j, Fabric, security, and the building blocks you plug in">
</p>

| | Module | You get |
|---|---|---|
| 🏗️ | **[ai-agent4j](src/ai-agent4j/)** | ReAct agents, tools, skills, memory, RAG, knowledge graphs, personas, budgets and rate limits, and **one contract over Gemini, Claude, OpenAI, xAI, Perplexity, Kimi, Qwen, Sarvam, Ollama and any OpenAI-style server**, with web-search grounding and typed citations. About 440 KB. No vendor SDKs. |
| 🧵 | **[Loom](src/loom/ai-agent4j-loom/)** | A small workflow language for agents: journaled and resumable, budgeted, scheduled, human approvals, retries and failure handlers, steer / stop / supervise, **earned autonomy**. And a CLI, `weave`, with a command for every job. |
| 🧪 | **[eval4j](src/eval4j/)** | AssertJ assertions on agent behaviour, LLM judges, golden datasets, and a **report you open, compare and share**. Prompt A/B and an optimizer. |
| 🔭 | **[loom-inspect and Fabric](src/loom/loom-inspect/)** | A live page per run or one server for the fleet: cost, context windows and what compaction cut, tool health, **the lethal trifecta and suspected injection**, incidents, a time bar, **Pause / Suspend / Stop**. |
| 🧠 | **[Engram](src/engram/engram-core/)** | Memory that retrieves and synthesises, so prompts stay small and agents stay sharp. |
| 🧩 | **[Addons](src/ai-agent4j-addons/)** | **Local ONNX embeddings** (data never leaves), pgvector and Pinecone. |
| 🔧 | **[Tools](src/ai-agent4j-tools/)** | `webhook`, `email`, `http`, `file`, `shell` and read-only `sql`, with allow-lists and journals. |
| 🏷️ | **[Classification](src/ai-agent4j/wiki/Classification.md)** | Typed questions (a choice, a yes/no, a score) answered in one call; `intentclassify` in Loom. |
| 🌱 | **[Spring Boot starter](src/spring/llm4j-spring-boot-starter/)** | Clients, budgets, agents, tools, RAG and the Loom runtime as beans, wired from `application.yml`. |
| 🧩 | **[VS Code extension](src/loom/vscode-loom/README.md)** | The live graph, the review panel, generate and change, a step debugger, the guide, and one-click set-up for your coding agent. |
| 🤝 | **[Agent skills](docs/PUBLISHING_SKILL.md)** | Three skills that walk a coding agent through designing, building and deploying, one gate at a time. |

---

# 🚀 The journey: from a sentence to a deployed, watched workflow

## 1️⃣ ✍️ Describe it

You don't start from a blank file. You start from a sentence.

```
weave generate "Turn meeting notes into action items with an owner and a due date,
                and post them to a team webhook after a person approves."
```

It asks at most five questions (with sensible defaults), then **shows you the plan first**: the agents, the tools and *what each one can reach*, who is asked, which effects need a person's approval, the budget, what happens when something fails. Correct the plan: it is the cheapest place to be wrong. Then it builds the project: the script, a prompt for each agent, tests, an evaluation dataset, a README.

And it hands you nothing it hasn't proven. The project lands in your folder only after **five gates**: ✅ it checks, ✅ it reviews clean of anything high-risk, ✅ its tests pass, ✅ those tests reach the steps, ✅ the script is exactly the plan you approved.

<p align="center">
  <img src="docs/images/developer-guide/terminal-generate.png" width="900" alt="weave generate: a description in, a gated project out">
</p>

> [!TIP]
> 💸 A cheap model writes it for a few cents. Want a better plan? `--plan-model` lets a stronger model plan while a cheap one writes. Want a change later? `weave generate --edit main.loom "also escalate legal threats"` shows the diff, the review change and the cost change, and writes nothing until you say so.

## 2️⃣ 👀 See it

A workflow is a picture before it is a file. In VS Code the graph is live: every agent, branch, loop, approval and failure handler, the happy path along the bottom, the cost of each step, and a click from any box to the line that wrote it.

<p align="center">
  <img src="docs/images/developer-guide/graph-meeting-dark.png" width="900" alt="The Loom graph of the generated workflow in VS Code">
</p>

Prefer words? `weave explain main.loom` says, in plain English, which agent is asked what, where a person decides, and what can spend money. Read it back and ask: *is that what I meant?*

## 3️⃣ 🛡️ Have it reviewed

Every workflow is reviewed the moment it exists: offline, free, ranked. Security, privacy, cost, reliability, prompts, validity and more, each finding with **why it matters** and **what to do**. The famous one is the ☠️ *lethal trifecta*: untrusted text, private data and a way out meeting in one agent. Loom finds it before an attacker does, and offers the fix for you to apply.

<p align="center">
  <img src="docs/images/developer-guide/review-meeting-dark.png" width="900" alt="The Loom review panel: findings beside the graph">
</p>

💰 Worst-case cost per run, ⏱️ how long it can take, 📤 how many things it can send: all there before it has ever run.

## 4️⃣ 🧪 Test it. For free.

This is the part that changes how building with agents feels. Tests run on **scripted models and tools**: no key, no network, no spend, the same result every time, in milliseconds. And you don't write most of them. The tool walks the script down every classifier label, every answer, every failing step, every value a condition compares with, and writes the cases for you.

<p align="center">
  <img src="docs/images/developer-guide/terminal-test.png" width="860" alt="weave test: every step reached, in under half a second">
</p>

Twenty-one cases, every step reached, in under half a second, for **$0**. Change a prompt, a model or a branch, and you know in a blink if a route moved.

## 5️⃣ 📊 Evaluate it

Tests prove the *logic*. Evaluation tells you whether the *answers* are any good. `weave eval` runs your golden dataset against real models with LLM judges, under a spend cap you set, and **eval4j** turns the result into a report you can open, compare, filter and share: pass rate against your goal, every dimension, every case, cost, models, prompt A/B.

<p align="center">
  <img src="src/eval4j-report/docs/images/overview-dark.png" width="900" alt="The eval4j report: pass rate against a goal, by dimension">
</p>

<table>
  <tr>
    <td width="50%"><img src="src/eval4j-report/docs/images/compare.png" alt="eval4j: compare two runs"><br><sub>🔀 <b>Compare runs</b>: what got better, what regressed</sub></td>
    <td width="50%"><img src="src/eval4j-report/docs/images/ab.png" alt="eval4j: prompt A/B"><br><sub>🅰️🅱️ <b>Prompt A/B</b>: pick the prompt on evidence</sub></td>
  </tr>
  <tr>
    <td width="50%"><img src="src/eval4j-report/docs/images/cost.png" alt="eval4j: cost"><br><sub>💰 <b>Cost and evidence</b>: what each answer cost</sub></td>
    <td width="50%"><img src="src/eval4j-report/docs/images/optimizer.png" alt="eval4j: prompt optimizer"><br><sub>🪄 <b>Prompt optimizer</b>: let the data improve the prompt</sub></td>
  </tr>
</table>

`weave eval --mock` runs the same wiring for nothing. In Java the same checks are plain JUnit: `assertThat(agent.run(question)).usesTool("refund").completedSuccessfully()`.

## 6️⃣ 🔭 Watch it run

```
weave run main.loom --observe
```

A live page for the run with no set-up: 💵 cost as it grows, 🤖 which agent is working, the step it is on, each agent's 🪟 context window and what compaction cut, 🔧 tool health, 💉 suspected prompt injection, 🔁 loops, a ⏪ time bar to scrub back to how the run looked at any moment, and **⏸️ Pause · 💤 Suspend · ⛔ Stop** for when you want to step in. Run many workflows and one **Fabric** server shows them all.

<table>
  <tr>
    <td width="50%"><img src="docs/images/observability/fabric-incidents.png" alt="Fabric: incidents across a fleet"><br><sub>🚨 <b>Incidents</b>: loops, failing tools, a run that broke and why</sub></td>
    <td width="50%"><img src="docs/images/observability/fabric-fleet-security.png" alt="Fabric: security across a fleet"><br><sub>🔒 <b>Fleet security</b>: the lethal trifecta and injection, everywhere at once</sub></td>
  </tr>
  <tr>
    <td width="50%"><img src="docs/images/observability/fabric-run-context.png" alt="Fabric: context windows"><br><sub>🪟 <b>Context</b>: each agent's window and what compaction cut</sub></td>
    <td width="50%"><img src="docs/images/observability/fabric-run-broken.png" alt="Fabric: a broken run"><br><sub>🧯 <b>Broken runs</b>: what failed, where, and what to do</sub></td>
  </tr>
</table>

Something odd? `weave why <run>` explains each choice the run made, and why the other way didn't happen. `weave whatif` changes one thing in a past run and shows how it would have gone. Export to your own stack over OTLP (`--observe-otlp`).

## 7️⃣ 🔁 Learn from production

The loop that makes it better every week:

1. 🔥 A real run goes wrong. **`weave capture`** turns it into a test case with the recorded answers scripted in, personal data masked, waiting for a person to read and approve it.
2. 🔧 You fix the script, the prompt or the model.
3. 🧬 **`weave regress`** tries your change against a sample of real past runs, with no model and no spend, and lists the unsafe changes first.
4. 🚦 **`weave gate`** gives CI one verdict: check, review, test, regress and eval.

An incident becomes a regression test **before lunch**.

## 8️⃣ 🚢 Ship it

```
weave deploy main.loom --target docker      # or compose, or k8s
weave bindgen main.loom --package com.acme  # typed Java for the workflow
```

🐳 A container recipe, a compose file or Kubernetes manifests, **read from the script**: the environment names, the hosts it talks to, the volumes, the schedules. Text only; secrets never appear. ☕ In Java, `bindgen` writes one typed method per workflow and a result type, and the Spring Boot starter runs it as a bean.

<p align="center">
  <img src="docs/images/developer-guide/terminal-deploy.png" width="860" alt="weave deploy: container files written from the script">
</p>

## 9️⃣ 🤝 Bring your coding agent

You don't have to type any of it. Run **Loom: Set Up My Coding Agent** in VS Code, or:

```
weave guide --install-skill . --for claude,agents,copilot,cursor
```

🧠 Claude Code gets the skills. Codex, Copilot, Gemini, Cursor and any agent that reads `AGENTS.md` get the rules. They learn to **look commands up instead of guessing** (`weave man`), to read you the plan before building, to show you the graph and the plain-English explanation, to run the free checks as often as they like, and to ask before anything that spends money.

<p align="center">
  <img src="docs/images/developer-guide/terminal-man.png" width="760" alt="weave man: a reference page for every command">
</p>

So the session goes like this: **you** say what you want. Your agent plans it, builds it, shows you the picture, runs the tests, reads you the review, and asks when it is your call.

---

# 🏢 Enterprise ready, out of the box

| | |
|---|---|
| ✅ **Authority in code, not in prompts** | What an agent may touch, spend, send and decide is declared and **enforced on every call**. The model reasons; the code holds the authority. |
| ✅ **Approvals before anything leaves** | `approve:` puts a person in front of any effect. Answer on the console, through a command, or on Telegram. |
| ✅ **Budgets that cannot be overspent** | Tokens, calls and cost, checked **before** each model call, per run and per step. |
| ✅ **Crash-safe by design** | Every step and effect is journaled. A restart resumes where it was, and an effect is **never repeated**. |
| ✅ **Audit trail and the right to explanation** | Every Thought, Action and Observation is an immutable audit event. Mapped to **GDPR, the EU AI Act and the NIST AI RMF** ([xAI: Beyond Black Boxes](src/ai-agent4j/wiki/xAI_BEYOND_BLACK_BOXES.md)). |
| ✅ **Confidence and escalation** | Every run carries a confidence score and `shouldEscalateToHuman()`. |
| ✅ **PII masked, bias watched** | PII is masked **before** it reaches a model or a log. Bias monitors can flag or block a response. |
| ✅ **Secrets that never become text** | Environment, your own vault, or an encrypted file store; never in a script, never in a prompt, never written by the tool. |
| ✅ **A security audit you can run in CI** | `weave audit` maps findings to the **OWASP Top 10 for LLM Applications**: the lethal trifecta, unapproved effects, missing budgets, outside dependencies. |
| ✅ **Prompt-injection signals** | Suspected injection and untrusted-to-outward paths flagged live in Fabric, and statically in review. |
| ✅ **Who may sign in, TLS, retention** | Decisions for the observability server, guided by the deployer skill. |
| ✅ **One verdict for CI** | `weave gate`: check, review, test, regress, eval, with skips shown. |

[The risks and the controls →](docs/security/secure-by-construction.md) · [Security guide →](SECURITY.md)

---

# 🤖 Autonomous, for real

- ⏳ **Runs that last days.** Sleep, wait for a person, wait for a time: the run is a file on disk, not a process in memory.
- 🗓️ **Schedules and triggers.** `weave daemon`, `weave tick` (for cron, systemd, launchd), cron blocks in the script.
- 🪜 **Earned autonomy.** A decision starts supervised and **earns** the right to decide alone, on recorded evidence, with promote, demote, freeze and approve in a person's hands (`weave autonomy`, `weave ladder`).
- 🎛️ **Steer, stop, supervise.** Tell a running agent what to do, wrap it up, or stop it where it stands, and handle `on_stopped`.
- ⏪ **Time travel.** Send a run back to a checkpoint, fork it with other values, ask *what if*.
- 🧯 **Failure is a first-class citizen.** `retry`, `timeout`, `on_failure`, `on_exhausted`: tested by injecting the failure, in milliseconds.

---

# 🔌 Plays well with everything you already have

☁️ **Nine hosted LLM vendors** and any OpenAI-style server · 🔗 **MCP servers** and **OpenAPI** specs as tools · 🗄️ **pgvector** and **Pinecone** · 🏠 **Local ONNX embeddings** · 🌱 **Spring Boot** · ☕ **JUnit and AssertJ** · 🐳 **Docker, compose, Kubernetes** · 📡 **OTLP** · 🗣️ **Voice in Indian languages** through Sarvam · 🧩 **VS Code** · 🤖 **Claude Code, Copilot, Cursor, Codex, Gemini**.

---

# 🏆 See it in the wild

| | |
|---|---|
| 🎬 **[GetViral](src/examples/getviral/)** | One idea in, a ready-to-post pack out: twelve agents, one Loom workflow, every module, and no API key to try it. |
| 🏛️ **[Hexamind Hub](src/examples/hexamind-hub/README.md)** | A boardroom of debating agents that reach consensus. |
| 🏭 **[Nirmaan Yantra](src/examples/nirmaan-yantra/README.md)** | An autonomous software factory: spec to tests to code to QA to release. |
| 🧒 **[Kingini](src/examples/kingini/README.md)** | A voice-first companion for children, in Malayalam, built on Sarvam. |
| ✉️ **[Gmail MCP app](src/examples/gmail-mcp-app/)** | Agents that read, draft and send email through MCP, with human approval. |

---

## 🗺️ The whole experience on one page

| You want to | You run | It costs |
|---|---|---|
| 👋 See what Loom does | `weave try` | nothing |
| 🩺 Check your setup | `weave doctor` | nothing |
| ✍️ Build from a sentence | `weave generate "..."` | a few cents |
| 🔧 Change what you built | `weave generate --edit main.loom "..."` | a few cents |
| 👀 See it | `weave graph`, `weave explain`, the VS Code graph | nothing |
| 🛡️ Have it reviewed | `weave review`, `weave audit` | nothing |
| 🧪 Test it | `weave test --coverage` | nothing |
| 📊 Evaluate answers | `weave eval` (`--mock` is free) | a cap you set |
| 🔭 Watch a run | `weave run --observe`, Fabric | nothing extra |
| 🕵️ Understand a run | `weave why`, `weave whatif`, `weave replay` | nothing |
| 🔁 Turn a bad run into a test | `weave capture`, `weave regress`, `weave gate` | nothing |
| 🚢 Ship it | `weave deploy`, `weave bindgen` | nothing |
| 🧭 Know what to do next | `weave next` | nothing |
| 📖 Look anything up | `weave man` | nothing |

---

## 📌 Where to go next

- 📖 **[Command reference](docs/COMMAND_REFERENCE.md)**: every command, every option with its default, examples and exit codes. In a terminal: `weave man`.
- 🧩 **[VS Code extension](src/loom/vscode-loom/README.md)**: the graph, the review panel, generate and change, the step debugger, the guide and the agent set-up.
- 🏁 **[Getting started with Loom](docs/GETTING_STARTED.md)** · **[Your first run](wiki/Your-First-Run.md)**
- ✍️ **[Generating a workflow](wiki/Generating-a-Workflow.md)** · 🔁 **[From an incident to a regression test](wiki/From-an-Incident-to-a-Regression-Test.md)** · ☕ **[Typed workflows in Java, and deploying](wiki/Typed-Workflows-in-Java-and-Deploying.md)** · 🤝 **[Working with a coding agent](wiki/Working-with-a-Coding-Agent.md)**
- 🔭 **[Observability](docs/OBSERVABILITY.md)** · **[Fabric server](wiki/Fabric-Server.md)** · 📊 **[eval4j report guide](src/eval4j-report/docs/USER-GUIDE.md)**
- 🧱 **[The stack](docs/THE_STACK.md)** · **[Why ai-agent4j](src/ai-agent4j/wiki/WHY_AI_AGENT4J.md)** · **[Why Loom](src/loom/ai-agent4j-loom/WHY_LOOM.md)** · **[Why eval4j](src/ai-agent4j/wiki/WHY_EVAL4J.md)**

<p align="center">
  <br>
  <b>☕ Java 17. 🔌 One line to try. ✍️ Your sentence in. 🧪 A tested, 🛡️ reviewed, 🔭 watched, 🚢 shippable workflow out.</b>
  <br><br>
  <a href="README.md#get-started"><img alt="Get started" src="https://img.shields.io/badge/Get%20started-in%205%20minutes-2ea44f?style=for-the-badge"></a>
  <a href="docs/COMMAND_REFERENCE.md"><img alt="Command reference" src="https://img.shields.io/badge/Command%20reference-every%20command-1b8bff?style=for-the-badge"></a>
</p>
