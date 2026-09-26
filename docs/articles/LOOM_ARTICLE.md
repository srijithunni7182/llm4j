# I Built a Programming Language for AI Agents. Here's Why.

*A personal story about frustration, neuro-symbolic AI, and the DSL I wish had existed two years ago.*

---

There's a moment every developer building multi-agent AI systems eventually hits. You've wired up your agents, your tools are working, your prompts are tuned — and then you need to add a simple rule: *if the first agent returns something suspicious, escalate to a supervisor instead of proceeding.*

You open your Python file. You add an `if` statement. Then another. Then a retry loop. Then a callback. Three weeks later, your "orchestration logic" is 400 lines of imperative Python scattered across five files, half of it is prompt engineering disguised as code, and you genuinely cannot tell where the AI ends and the application begins.

That's the moment I started thinking about Loom.

---

## Working with the Harnesses That Exist

Before building anything, I spent a long time working *with* what was already out there. I want to be honest about that experience — not to dismiss these tools, which are genuinely impressive, but to explain what kept nagging at me.

**Claude Code** is remarkable for what it is: an AI that can reason about your codebase, write code, run tests, and iterate. But it's fundamentally an autonomous agent operating in a loop. When I used it for multi-step workflows involving multiple specialized agents, I found myself writing elaborate system prompts to describe the coordination logic — essentially programming in natural language. "First do X, then if Y, do Z." The model would follow these instructions most of the time. But "most of the time" is not a trust boundary. It's a probability.

**CrewAI** takes a different approach — you define agents and tasks in Python, and the framework handles the orchestration. It's elegant and the abstractions are clean. But I kept running into the same fundamental issue: the coordination logic lives in Python code that's tightly coupled to the framework's execution model. When I wanted to express something like "retry this agent three times, and if it still fails, hand off to a human reviewer" — that's not a data structure, that's control flow. And control flow expressed as Python dictionaries and callback chains is hard to read, hard to audit, and hard to hand to a non-engineer.

What I kept wanting was something I could *read*. A file I could open and immediately understand the full shape of a workflow — who calls whom, under what conditions, what happens when things go wrong — without needing to trace through framework internals or decode nested Python objects.

---

## Why I Thought About Loom

The insight that changed my thinking came from an unexpected direction: compilers.

I'd been reading about how programming languages handle the boundary between high-level intent and low-level execution. A compiler doesn't *guess* what you mean. It parses your source code into an AST, applies deterministic rules, and produces deterministic output. The semantics are fixed. The behavior is guaranteed.

Multi-agent AI systems desperately need something like this. Not for the reasoning — that's what the models are for. But for the *coordination*. The routing. The branching. The retry logic. The safety rules. These are not things you want a model to infer from a prompt. These are things you want a runtime to enforce.

So I asked myself: what if the coordination layer was a real language? Not a Python DSL, not a YAML config, not a prompt — but an actual external DSL with a lexer, a parser, an AST, and a runtime that executes it symbolically?

That's Loom.

---

## What Neuro-Symbolic AI Actually Means

"Neuro-symbolic" is one of those terms that gets thrown around a lot without much precision. Let me explain what it means in the context of Loom, because it's the core idea everything else builds on.

**Neural** systems — large language models — are extraordinarily good at reasoning about content. Give a model a document and ask it to summarize, critique, classify, or transform it, and it will do so with remarkable fluency. But neural systems are probabilistic. They don't guarantee outcomes. They don't enforce rules. They approximate.

**Symbolic** systems — traditional software — are the opposite. An `if` statement always evaluates the same way. A loop always terminates when its condition is met. A function always returns the same output for the same input. Symbolic systems are deterministic, auditable, and trustworthy in a way that neural systems are not.

The problem is that most real-world AI applications need both. You need the model's reasoning ability to handle the content — the research, the writing, the analysis. But you need symbolic guarantees to handle the coordination — the routing, the sequencing, the safety rules.

Neuro-symbolic AI is the discipline of combining these two things with a hard boundary between them. The model reasons. The harness governs.

Loom is that harness, expressed as a language.

---

## What Loom Can Do

Let me show you what this looks like in practice. Here's a real Loom workflow — a research and publishing pipeline:

```loom
agent Researcher {
    model: "gpt-4o"
    system: "You are a rigorous research analyst."
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

agent Publisher {
    model: "gpt-4o-mini"
    system: "You format and publish content."
}

routing CostAware {
    strategy: "COST_AWARE"
    primary: "gpt-4o"
    fallback: ["claude-3-haiku", "gemini-1.5-flash"]
}

workflow ResearchAndPublish(topic) {

    delegate "Research: {topic}" to Researcher -> findings
        retry 3
        on_failure {
            note "Research failed after 3 attempts: {_error}"
            handoff "Manual research needed for: {topic}" to Publisher
        }

    alt (findings.status == "SUFFICIENT") {
        delegate "Write article about: {findings}" to Writer -> draft
        handoff "Publish: {draft}" to Publisher
    } else {
        loop until (approved == "true") {
            delegate "Expand research on: {findings}" to Researcher -> findings
            human_prompt "Approve findings? (true/false)" -> approved
        }
    }
}
```

Let me walk through what's happening here, because every line is doing something specific.

**Agent definitions** are declarations, not instantiations. `Researcher` has RAG-backed knowledge — when it runs, the runtime will retrieve relevant context from the knowledge base before sending the prompt. This is configured in the DSL, not in Python code.

**`delegate`** is the fundamental primitive. It sends a task string to an agent, waits for the response, and binds it to a variable. The `retry 3` and `on_failure` block are resilience contracts — expressed in the DSL, enforced by the runtime. If the Researcher fails three times, the runtime executes the `on_failure` block. The model never sees this logic.

**`alt`** is symbolic conditional branching. `findings.status == "SUFFICIENT"` is a typed check against the agent's structured output — not a string match against free text. The agent is configured with an output schema that forces it to return a structured JSON response. The runtime coerces the model's output before any condition is evaluated.

**`loop until`** is a symbolic retry loop. It will keep executing until the condition is met. The model doesn't decide when to stop. The runtime does.

**`human_prompt`** suspends execution and waits for human input. This is a first-class primitive, not a workaround.

The entire coordination logic — the retries, the branching, the human approval gate — is in the `.loom` file. The models only see their individual task strings. The harness handles everything else.

---

## The Full Primitive Set

Loom ships with twelve primitives that cover the full space of multi-agent coordination patterns:

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

These aren't abstractions over Python. They're language primitives with defined semantics, parsed into an AST and executed by a runtime that has no awareness of the models' internal reasoning.

---

## Building the VS Code Extension: Why It Mattered

Once Loom was working, I ran into a practical problem: writing `.loom` files in a plain text editor is painful. No syntax highlighting. No error feedback. No way to navigate a large workflow without reading every line.

This is the moment I understood something important about DSL adoption: **a language without tooling is a language nobody uses.** SQL without a query editor. HTML without browser DevTools. The language might be elegant, but if the authoring experience is friction, developers will reach for the familiar Python file instead.

So I built the VS Code extension.

The extension provides:
- **Syntax highlighting** via TextMate grammars — keywords, strings, comments, operators all visually distinct
- **LSP diagnostics** — real-time error detection as you type, with precise line and column information
- **Hover documentation** — hover over an agent name and see its definition
- **Go-to-definition** — click through to where an agent or workflow is declared
- **Completion** — all Loom keywords and all identifiers defined in the current file
- **Workflow Outline** — a sidebar tree view showing all agents, workflows, and schedules at a glance
- **Run Workflow** — execute the current `.loom` file directly from the editor via the `weave` CLI

The most interesting engineering challenge was the Language Server. A proper LSP implementation means the editor experience is editor-agnostic — the same server could power Neovim, Emacs, or any other LSP-compatible editor. But it also means you need to parse the document on every keystroke, debounce intelligently, and produce diagnostics that are precise enough to be useful without being noisy.

The lesson I took from building it: **tooling is not optional for a DSL**. It's part of the language design. The moment I had syntax highlighting and real-time error feedback, writing Loom scripts felt qualitatively different. The cognitive load dropped. The iteration speed increased. The language felt real.

---

## The Conformance Test Kit: Why Behavioral Contracts Matter

Here's a problem that doesn't exist when you have one runtime: how do you know that a second implementation of the same language behaves the same way?

This matters because Loom is designed to run on multiple runtimes. The Java implementation is the reference. The Python implementation (loom4py, coming soon) needs to produce identical behavior for identical scripts. And "identical behavior" needs to be defined precisely — not as "the outputs look similar" but as "the execution trace matches the expected trace step for step."

This is what the Conformance Test Kit (CTK) is for.

The CTK is a suite of canonical `.loom` scripts — one for each primitive — paired with expected execution traces and mock agent fixtures. When you run the CTK against a runtime, it:

1. Executes each canonical script against mock agents (deterministic, no real LLM calls)
2. Captures the execution trace — every `delegate`, `broadcast`, `call`, and `handoff` that fired, in order
3. Compares the actual trace against the expected trace, step by step
4. Reports pass/fail with precise difference descriptions

The key insight in the trace comparison algorithm: we compare *structure*, not *values*. We check that the right agent was called, in the right order, with the right statement type. We deliberately ignore the output values — because those come from mock fixtures and are deterministic, but more importantly because a conformance test should not be brittle to changes in model output.

Building the CTK taught me something I hadn't fully appreciated before: **a language specification without an executable test suite is just documentation**. The CTK is the executable specification of Loom's behavioral contract. Any runtime that passes the CTK is, by definition, a conformant Loom runtime.

The CTK currently covers 15 canonical test cases:
- All twelve statement primitives
- Retry with `on_failure` handling
- Sub-workflow call with scope isolation
- All mock fixtures verified to contain no PII or real API keys

---

## What's Coming: loom4py

The next component in the Loom ecosystem is **loom4py** — a native Python implementation of the Loom runtime.

The Python AI/ML community is enormous, and a lot of the most interesting agent work is happening in Python. Loom should be accessible there. But "accessible" doesn't mean a thin wrapper around the Java runtime — it means a proper Python implementation with idiomatic Python APIs, `asyncio` support, and full CTK validation.

loom4py will include:
- A character-by-character Lexer (no regex — structural parity with the Java implementation)
- A recursive descent Parser producing the same AST structure
- AST nodes as Python dataclasses
- A `HarnessExecutor` using `concurrent.futures` for parallel blocks
- Full CTK validation — loom4py will not ship until it passes every canonical test

The goal is behavioral parity: a `.loom` script that runs on the Java runtime should produce an identical execution trace when run on loom4py. The CTK is what makes that guarantee verifiable.

---

## An Invitation

Loom is open source. The full ecosystem — the Java runtime, the VS Code extension, the CTK, and soon loom4py — lives at:

**[https://github.com/srijithunni7182/llm4j](https://github.com/srijithunni7182/llm4j)**

If you're building multi-agent systems and you've felt the friction I described at the beginning of this article — the coordination logic bleeding into your application code, the prompt engineering masquerading as control flow, the inability to audit what your system actually does — I'd love for you to try Loom.

There are several ways to get involved:

**Try it.** Clone the repo, run one of the sample workflows in `loom/ai-agent4j-loom/samples/`, and see what the authoring experience feels like. The `weave` CLI makes it easy to get started without writing any Java.

**Build a runtime.** If you work in Go, Rust, TypeScript, or any other language — the CTK gives you a complete behavioral specification. Build a conformant runtime and run the CTK against it. If it passes, it's a Loom runtime.

**Contribute primitives.** There are patterns in multi-agent coordination that Loom doesn't yet express well. If you have a use case that doesn't fit the current primitive set, open an issue. The language is young and the design is open.

**Write Loom scripts.** The best way to stress-test a language is to use it for real problems. If you build something interesting with Loom, share it. The samples directory is a good place to contribute.

The problem Loom is trying to solve — the need for a symbolic coordination layer with a hard trust boundary — is not going away. As AI systems become more capable and more consequential, the question of *who governs the harness* becomes more important, not less.

The model reasons. The harness governs. That's the bet Loom is making.

I think it's the right one.

---

*The Loom ecosystem is part of the [llm4j](https://github.com/srijithunni7182/llm4j) project. Star the repo, open an issue, or just say hello.*
