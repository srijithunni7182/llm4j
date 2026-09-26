<img src="loom_logo.png" align="right" width="200" alt="Loom Logo">

# Loom

**A neuro-symbolic orchestration DSL for multi-agent AI workflows on the JVM.**

Most multi-agent frameworks ask you to express coordination logic in Python code,
YAML config, or worse — in natural language inside a prompt. None of these belong
in the trust-critical layer of your system.

Loom gives you a purpose-built external DSL where routing, sequencing, branching,
and safety rules are interpreted by a symbolic runtime — not inferred by a model.
The model reasons. The harness governs.

```text
workflow ResearchAndPublish(topic) {

    delegate "Research: {topic}" to Researcher -> findings

    alt (findings.status == "SUFFICIENT") {
        delegate "Write report: {findings}" to Writer -> draft
        handoff "Publish: {draft}" to Publisher
    } else {
        loop until (approved == "true") {
            delegate "Expand research: {findings}" to Researcher -> findings
            human_prompt "Approve findings? (true/false)" -> approved
        }
    }
}
```

The `alt`, `loop until`, and `handoff` above are not instructions to a model.
They are symbolic control flow — evaluated by the Loom runtime in Java,
enforced regardless of what any agent produces.

---

## Why this exists

Every serious multi-agent system eventually builds a harness — the code that
decides who gets called, in what order, under what conditions, and when to stop.

Most teams write this in imperative Python, scattering coordination logic across
files and framework callbacks. Others embed it in prompts and hope the model
self-regulates. Neither approach gives you the thing you actually need: a symbolic
layer with a hard trust boundary, where the routing rules are code and the model
only does what models are good at — reasoning about content.

Loom is that layer, designed as a first-class language.

---

## How it works

Loom ships with a handcrafted **Lexer → Parser → AST → HarnessExecutor** pipeline.
Your `.loom` script is parsed into an AST and executed by the `HarnessExecutor`,
which drives real LLM calls via the ai-agent4j library. The model never sees the
routing logic. It receives a task string and returns output. The harness handles
everything else.

Tools are wired via a companion `.loot` file — a key-value mapping of logical
tool names to Java classpaths, resolved via reflection at runtime. Your `.loom`
scripts stay clean of implementation details.

```properties
# tools.loot
WebSearch   = io.github.llm4j.tools.BraveSearchTool
SqlQuery    = com.mycompany.tools.DatabaseTool
```

---

## The primitive set

| Primitive | What it does |
|---|---|
| `delegate` | Call one agent, await result, bind to variable |
| `broadcast` | Fan out to multiple agents in parallel (Java streams), collect combined result |
| `handoff` | Terminal node — pass control to an agent and end the current branch |
| `alt` / `else` | Symbolic conditional branching on typed context variable values |
| `loop until` | Repeats a block until a symbolic condition is met; `max N … on_exhausted` bounds it |
| `for each` | Runs a block per list item (`parallel for each` for all at once); targets may come from the item |
| `human_prompt` | Asks a person; with a run journal the run suspends (no thread held) and resumes on the answer |
| `guardrail` | Wraps a block — intercepts output before it escapes (e.g. PII detection) |
| `call` | Invoke a sub-workflow with isolated variable scope |
| `parallel { }` | Concurrent execution block — every statement runs on its own branch thread |
| `import` | Split large workflows across files — merged into a flat namespace at load time |

---

## The Frontier — what makes the symbolic guarantee real

The hard problem in neuro-symbolic design is the boundary: model output is free
text, but symbolic conditions need typed values. Loom addresses this directly.

**Typed output schemas** — enforce structured output per agent at the harness level:

```text
agent Auditor {
    model: "gpt-4o"
    system: "Audit the code for security vulnerabilities."
    output_schema {
        status: enum["SECURE", "VULNERABLE"]
        issues: list
    }
}
```

Now `alt (audit.status == "SECURE")` is a typed symbolic check, not a string
match against free model output. The harness coerces the model's response before
any condition is evaluated.

**Resilience contracts** — expressed in the DSL, not in Java code:

```text
delegate "Analyze data" to AnalystAgent -> result
    retry 3 backoff 2s timeout 60s
    on_failure { handoff "Escalate" to AdminAgent }
```

**Sub-workflow composition** — build reusable primitives:

```text
call ValidateAndApprove(draft) -> approved_draft
```

---

## New: durable, data-driven workflows in one file

These features came out of building [GetViral](../../examples/getviral/), a 12-agent content studio that
runs as a hosted website. Each is one keyword in the script. None needs a graph builder, a state class
or callback code.

**Runs that survive restarts and wait for people for free.** Every `delegate`, `broadcast` and
`human_prompt` records its result in a *run journal* (in memory, a file, or any SQL database). Run the
workflow again with the same journal and Loom replays what already happened, so no model is called twice,
then continues. A `human_prompt` can suspend the run instead of blocking a thread; record the answer days
later and resume it on any server. A crashed process resumes from its last step. The script doesn't
change at all:

```java
executor.setJournal(new JdbcRunJournal(dataSource, runId));
try { executor.executeWorkflow("Main", inputs); }
catch (RunSuspended waiting) { askTheUser(waiting.stepId(), waiting.prompt()); }   // thread is free
// …later, on any instance:
journal.answer(stepId, "yes");  executor.executeWorkflow("Main", inputs);        // replays, then continues
```

**`for each`, with routing decided by data.** Iterate over any list an agent returned, sequentially or
with `parallel for each`. The target agent and the output variable can come from the item, so one line
replaces a chain of `alt` branches:

```text
for each fix in review.fixes {
    delegate "Fix this: {fix.problem}" to {fix.owner} -> {fix.output}
}
```

**Loops that can't run away.** `loop until … max N { } on_exhausted { }` bounds the rounds, so a model
that never satisfies the condition can't spin (and bill) forever.

```text
loop until (review.verdict == "COMPLETE") max 5 {
    delegate "Review round {_loopRound}" to Lead -> review
    for each fix in review.fixes { delegate "{fix.task}" to {fix.owner} -> {fix.output} }
} on_exhausted {
    note "Shipping with open issues after {_loopRounds} rounds"
}
```

**Resilience you can read.** Exponential backoff and a per-attempt timeout sit next to the retry count:

```text
delegate "Summarise {doc}" to Writer -> summary retry 2 backoff 2s timeout 90s
    on_failure { handoff "Writer unavailable: {_error}" to Supervisor }
```

**A contract for each step, not just each agent.** `expecting { }` gives one delegate its own output schema,
so a single agent can cast a team in one step and return a verdict in another:

```text
delegate "Review the build" to Showrunner -> review expecting {
    verdict: enum["COMPLETE", "INCOMPLETE"],
    fixes: list
}
```

**Smaller things that make scripts shorter:**

- `temperature:` on an agent (creative roles high, checkers low);
- `{plan.hooks.0}` paths into structured results inside any payload;
- `parallel` branches that really run in parallel, on their own thread pool;
- block-local variables that don't leak out of a `for each`.

See the [Language Guide](LOOM_GUIDE.md#-5-the-frontier-advanced-orchestration) for each
feature in detail.

---

## Getting started

**Run a workflow directly:**

```bash
# Build and alias the CLI
cd ai-agent4j-loom && mvn clean install
alias weave='java -cp "target/classes:target/lib/*" io.github.llm4j.loom.cli.WeaveCLI'

# Run
weave run research.loom --loot tools.loot --input topic="Neuro-Symbolic AI"
```

**Embed in a Java application:**

```java
String script  = Files.readString(Path.of("workflow.loom"));

Lexer      lexer    = new Lexer(script);
LoomParser parser   = new LoomParser(lexer.tokenize());
LoomScript loomScript = parser.parseScript();

ToolRegistry registry = new ToolRegistry();
new LootLoader().loadIntoRegistry("tools.loot", registry);

HarnessExecutor executor = new HarnessExecutor(loomScript, registry, clientFactory);
executor.initialize();
executor.executeWorkflow("ResearchAndPublish", Map.of("topic", "AI Agents"));
```

**Package for deployment:**

```bash
weave package research.loom --loot tools.loot --fat --out my-app.jar
java -jar my-app.jar topic="Advanced Agentic Coding"
```

---

## The Loom Ecosystem

### IDE Support

**VS Code Extension** — Syntax highlighting, LSP diagnostics, workflow outline, and run commands for `.loom` and `.loot` files.

```bash
# Install from VS Code Marketplace
# Search for: "Loom DSL"
```

### Testing & Conformance

**Conformance Test Kit (CTK)** — A canonical suite of test scripts and execution traces that define the behavioral contract for all Loom implementations. The CTK validates runtime parity across Java and Python.

```bash
# Run conformance tests against the Java runtime
mvn -C ctk clean package
mvn -C ctk exec:java -Dexec.mainClass=io.github.loom.ctk.CtkMain
```

### Multi-Language Support

**loom4py** (In Development) — A Python implementation of the Loom runtime, enabling `.loom` workflows to run natively in Python environments while maintaining behavioral parity with the Java implementation via the CTK.

### 🧠 Advanced Memory (Engram)

Loom integrates seamlessly with **Engram**, a neuro-symbolic memory engine that prevents "Context Bloat" in long-running workflows by using a smart, synthesized retrieval-synthesis loop.

👉 **[Building Agentic Workflows with Loom & Engram](../../docs/AGENTIC_WORKFLOWS_GUIDE.md)**

---

## 📊 Loom vs. The World

Loom was designed to address the specific gaps in existing AI orchestration frameworks. Below is a multi-dimensional comparison based on core agentic requirements:

![Loom Capability Radar](capability_radar.png)

Loom excels in **DSL-Driven Knowledge** and **Developer Efficiency** by providing a symbolic layer that is decoupled from the underlying Java implementation, allowing for rapid iteration and robust "Neuro-Symbolic" control.

---

## Core capabilities

*   **External DSL Runtime:** Loom ships with a handcrafted Lexer, Recursive Descent Parser, and AST Execution Engine that dynamically boots agents without recompiling Java.
*   **Modular Architecture**: Split large workflows into multiple files using the `import` statement. All agents, workflows, and configurations are merged into a flat namespace.
*   **Human-In-The-Loop:** Built-in semantic support for `human_prompt`. With a run journal, a run suspends while it waits and resumes on any server when the answer arrives.
*   **Durable Runs:** Every step is journaled (memory, file or SQL); re-running replays finished steps and resumes after crashes.
*   **Inversion of Control via `.loot`:** Rigid adherence to the principle of least privilege. Workflows define tools by name inside `.loom`, but the actual fully qualified Java classpath mapping must be explicitly provided in a separate `.loot` file, dynamically instantiated using Reflection.

---

## Status

Loom is fully tested and capable of orchestrating highly sophisticated graphs including N-round debates, parallel MapReduce sweeps, and Human-in-the-Middle approvals directly out of the box.

👉 **[Read the Loom Learning Guide](LOOM_GUIDE.md)** for deeper technical reference and advanced patterns.
