<p align="center">
  <img src="docs/images/hero_llm4j.svg" width="560" alt="llm4j: ai-agent4j builds agents, Loom orchestrates them, eval4j tests them">
</p>

<h1 align="center">Say what you want.<br>Watch it get built, tested and shipped.</h1>

<p align="center">
  <b>The llm4j developer experience: from a sentence to a running, watched, tested AI workflow, in Java, with very little effort and very few tokens.</b>
</p>

<p align="center">
  <a href="README.md">README</a> ·
  <b>Developer guide</b> ·
  <a href="docs/COMMAND_REFERENCE.md">Command reference</a> ·
  <a href="src/loom/vscode-loom/README.md">VS Code extension</a> ·
  <a href="wiki/Home.md">Wiki</a> ·
  <a href="CONTRIBUTING.md">Contributing</a> ·
  <a href="SECURITY.md">Security</a>
</p>

---

## Five minutes, no key, no account

```
jbang weave@srijithunni7182/llm4j try
```

That is the whole install. Java 17, one line, and a workflow runs in front of you: a classifier routes a message, an agent answers, **Loom itself checks every branch** (no model decides it), a person is asked before anything goes out, and every step is explained in plain English as it happens. Nothing is spent. Nothing is written.

<p align="center">
  <img src="docs/images/developer-guide/terminal-try.png" width="900" alt="weave try: a workflow runs on demo models and explains each step">
</p>

Now make one of your own.

---

## 1. Describe it

You don't start from a blank file. You start from a sentence.

```
weave generate "Turn meeting notes into action items with an owner and a due date,
                and post them to a team webhook after a person approves."
```

It asks at most five questions (with sensible defaults), then **shows you the plan first**: the agents, the tools and *what each one can reach*, who is asked, which effects need a person's approval, the budget, what happens when something fails. You correct the plan, the cheapest place to be wrong. Then it builds the project: the script, a prompt for each agent, tests, an evaluation dataset, a README.

And it does not hand you anything it hasn't proven. The project only lands in your folder after it has passed **five gates**: it checks, it reviews clean of anything high-risk, its tests pass, those tests reach the steps, and the script is exactly the plan you approved.

<p align="center">
  <img src="docs/images/developer-guide/terminal-generate.png" width="900" alt="weave generate: a description in, a gated project out">
</p>

A cheap model writes it for a few cents. Want a better plan? `--plan-model` lets a stronger model plan while a cheap one writes. Want to change something later? `weave generate --edit main.loom "also escalate legal threats"` shows you the diff, the review change and the cost change, and writes nothing until you say so.

---

## 2. See it

A workflow is a picture before it is a file. In VS Code the graph is live: every agent, branch, loop, approval and failure handler, the happy path along the bottom, the cost of each step, and a click from any box to the line that wrote it.

<p align="center">
  <img src="docs/images/developer-guide/graph-meeting-dark.png" width="900" alt="The Loom graph of the generated workflow in VS Code">
</p>

Prefer words? `weave explain main.loom` tells you, in plain English, which agent is asked what, where a person decides, and what can spend money. Read it back and ask: *is that what I meant?*

---

## 3. Have it reviewed

Every workflow gets reviewed the moment it exists, offline, for free. Security, privacy, cost, reliability, prompts, validity and more, ranked, each finding with **why it matters** and **what to do**. The famous one is the *lethal trifecta*: untrusted text, private data and a way out meeting in one agent. Loom finds it before an attacker does, and offers the fix for you to apply.

<p align="center">
  <img src="docs/images/developer-guide/review-meeting-dark.png" width="900" alt="The Loom review panel: findings beside the graph">
</p>

Worst-case cost per run, how long it can take, how many things it can send: all there before it has ever run.

---

## 4. Test it, for free

Here is the part that changes how it feels to build with agents. Tests run on **scripted models and tools**: no key, no network, no spend, the same result every time, in milliseconds. And you don't write most of them. The tool walks the script down every classifier label, every answer, every failing step, every value a condition compares with, and writes the cases for you.

<p align="center">
  <img src="docs/images/developer-guide/terminal-test.png" width="860" alt="weave test: 21 cases, every step reached, in under half a second">
</p>

Twenty-one cases, every step reached, in under half a second, for $0. Change a prompt, a model or a branch, and you know in a blink if a route moved.

---

## 5. Evaluate it

Tests tell you the *logic* is right. Evaluation tells you whether the *answers* are any good. `weave eval` runs your golden dataset against real models with LLM judges, under a spend cap you set, and **eval4j** turns the result into a report you can open, compare, filter and share: pass rate against your goal, every dimension, every case, cost, models, prompt A/B.

<p align="center">
  <img src="src/eval4j-report/docs/images/overview-dark.png" width="900" alt="The eval4j report: pass rate against a goal, by dimension">
</p>

<p align="center">
  <img src="src/eval4j-report/docs/images/compare.png" width="900" alt="eval4j: compare two runs">
</p>

`weave eval --mock` runs the same wiring for nothing. In Java, the same checks are plain JUnit: `assertThat(agent.run(question)).usesTool("refund").completedSuccessfully()`.

---

## 6. Watch it run

```
weave run main.loom --observe
```

A live page for the run, no setup: cost as it grows, which agent is working, the step it is on, each agent's context window and what compaction cut, tool health, suspected prompt injection, loops, a time bar to scrub back to how the run looked at any moment, and **Pause, Suspend, Stop** buttons for when you want to step in. Run many workflows and one **Fabric** server shows them all.

<p align="center">
  <img src="docs/images/observability/fabric-incidents.png" width="900" alt="Fabric: incidents across a fleet of workflows">
</p>

Something odd? `weave why <run>` explains each choice the run made, and why the other way didn't happen. `weave whatif` changes one thing in a past run and shows how it would have gone.

---

## 7. Learn from production

The loop that makes it better every week:

1. A real run goes wrong. **`weave capture`** turns it into a test case with the recorded answers scripted in, personal data masked, waiting for a person to read and approve it.
2. You fix the script, the prompt or the model.
3. **`weave regress`** tries your change against a sample of real past runs, with no model and no spend, and lists the unsafe changes first.
4. **`weave gate`** gives CI one verdict: check, review, test, regress and eval.

An incident becomes a regression test before lunch.

---

## 8. Ship it

```
weave deploy main.loom --target docker      # or compose, or k8s
weave bindgen main.loom --package com.acme  # typed Java for the workflow
```

A container recipe, a compose file or Kubernetes manifests, **read from the script**: the environment names, the hosts it talks to, the volumes, the schedules. Text only; secrets never appear. In Java, `bindgen` writes one typed method per workflow and a result type, and the Spring Boot starter runs it as a bean.

<p align="center">
  <img src="docs/images/developer-guide/terminal-deploy.png" width="860" alt="weave deploy: container files written from the script">
</p>

---

## 9. Bring your coding agent

You don't have to type any of it. Run **Loom: Set Up My Coding Agent** in VS Code, or:

```
weave guide --install-skill . --for claude,agents,copilot,cursor
```

Claude Code gets the skills. Codex, Copilot, Gemini, Cursor and any agent that reads `AGENTS.md` get the rules. They learn to **look commands up instead of guessing** (`weave man`), to read you the plan before building, to show you the graph and the plain-English explanation, to run the free checks as often as they like, and to ask before anything that spends money.

<p align="center">
  <img src="docs/images/developer-guide/terminal-man.png" width="760" alt="weave man: a reference page for every command">
</p>

So the session goes like this: *you* say what you want. Your agent plans it, builds it, shows you the picture, runs the tests, reads you the review, and asks when it is your call.

---

## The whole experience on one page

| You want to | You run | It costs |
|---|---|---|
| See what Loom does | `weave try` | nothing |
| Check your setup | `weave doctor` | nothing |
| Build from a sentence | `weave generate "..."` | a few cents |
| Change what you built | `weave generate --edit main.loom "..."` | a few cents |
| See it | `weave graph`, `weave explain`, the VS Code graph | nothing |
| Have it reviewed | `weave review`, `weave audit` | nothing |
| Test it | `weave test --coverage` | nothing |
| Evaluate answers | `weave eval` (`--mock` is free) | a cap you set |
| Watch a run | `weave run --observe`, Fabric | nothing extra |
| Understand a run | `weave why`, `weave whatif`, `weave replay` | nothing |
| Turn a bad run into a test | `weave capture`, `weave regress`, `weave gate` | nothing |
| Ship it | `weave deploy`, `weave bindgen` | nothing |
| Know what to do next | `weave next` | nothing |
| Look anything up | `weave man` | nothing |

---

## Where to go next

- **[Command reference](docs/COMMAND_REFERENCE.md)**: every command, every option with its default, examples and exit codes. In a terminal: `weave man`.
- **[VS Code extension](src/loom/vscode-loom/README.md)**: the graph, the review panel, generate and change, debugging a workflow step by step, the guide and the agent set-up.
- **[Getting started with Loom](docs/GETTING_STARTED.md)** and **[Your first run](wiki/Your-First-Run.md)**
- **[Generating a workflow](wiki/Generating-a-Workflow.md)** · **[From an incident to a regression test](wiki/From-an-Incident-to-a-Regression-Test.md)** · **[Typed workflows in Java, and deploying](wiki/Typed-Workflows-in-Java-and-Deploying.md)** · **[Working with a coding agent](wiki/Working-with-a-Coding-Agent.md)**
- **[Observability](docs/OBSERVABILITY.md)** and the **[Fabric server](wiki/Fabric-Server.md)**
- **[eval4j report guide](src/eval4j-report/docs/USER-GUIDE.md)**
- The libraries underneath: **[ai-agent4j](src/ai-agent4j/)** (agents), **[Loom](src/loom/ai-agent4j-loom/)** (workflows), **[eval4j](src/eval4j/)** (evaluation). **[The stack](docs/THE_STACK.md)**

<p align="center">
  <b>Java 17. One line to try. Your sentence in, a tested, reviewed, watched workflow out.</b>
</p>
