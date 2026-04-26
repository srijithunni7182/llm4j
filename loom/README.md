<img src="ai-agent4j-loom/loom_logo.png" align="right" width="200" alt="Loom Logo">

# Loom DSL

**A neuro-symbolic orchestration DSL for multi-agent AI workflows.**

Most multi-agent frameworks ask you to express coordination logic in Python code,
YAML config, or worse — in natural language inside a prompt. None of these belong
in the trust-critical layer of your system.

Loom gives you a purpose-built external DSL where routing, sequencing, branching,
and safety rules are interpreted by a symbolic runtime — not inferred by a model.
**The model reasons. The harness governs.**

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
They are symbolic control flow — evaluated by the Loom runtime, enforced
regardless of what any agent produces.

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

## The primitive set

| Primitive | What it does |
|---|---|
| `delegate` | Call one agent, await result, bind to variable |
| `broadcast` | Fan out to multiple agents in parallel, collect combined result |
| `handoff` | Terminal node — pass control to an agent and end the current branch |
| `alt` / `else` | Symbolic conditional branching on typed context variable values |
| `loop until` | Retry block — executes until a symbolic condition is met |
| `human_prompt` | Blocking suspension — parks execution until human input arrives |
| `guardrail` | Wraps a block — intercepts output before it escapes (e.g. PII detection) |
| `call` | Invoke a sub-workflow with isolated variable scope |
| `parallel { }` | Concurrent execution block — every statement runs in its own thread |
| `observe` | Emit a structured trace event without affecting control flow |
| `note` | Inline documentation — ignored by the runtime |
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

**Resilience contracts** — expressed in the DSL, not in application code:

```text
delegate "Analyze data" to AnalystAgent -> result
    retry 3
    on_failure { handoff "Escalate" to AdminAgent }
```

**Sub-workflow composition** — build reusable, scope-isolated primitives:

```text
call ValidateAndApprove(draft) -> approved_draft
```

---

## 📊 Loom vs. The World

Loom was designed to address specific gaps in existing AI orchestration frameworks.

![Loom Capability Radar](ai-agent4j-loom/capability_radar.png)

Loom excels in **DSL-Driven Knowledge** and **Developer Efficiency** by providing
a symbolic layer decoupled from the underlying runtime, allowing for rapid
iteration and robust neuro-symbolic control.

---

## The Ecosystem

This directory contains all Loom components, designed to work together:

### [ai-agent4j-loom](./ai-agent4j-loom/) — Java Runtime
The reference implementation. A handcrafted **Lexer → Parser → AST → HarnessExecutor**
pipeline that drives real LLM calls via ai-agent4j. The model never sees the routing
logic — it receives a task string and returns output. The harness handles everything else.

Tools are wired via a companion `.loot` file — a key-value mapping of logical tool names
to Java classpaths, resolved via reflection at runtime:

```properties
# tools.loot
WebSearch = io.github.llm4j.tools.BraveSearchTool
SqlQuery  = com.mycompany.tools.DatabaseTool
```

### [vscode-loom](./vscode-loom/) — IDE Support
First-class authoring experience for `.loom` and `.loot` files:
- Syntax highlighting via TextMate grammars
- LSP-backed diagnostics, hover, go-to-definition, and completion
- Workflow Outline tree view for navigating large scripts
- Run Workflow command that invokes `weave` directly from the editor

### [ctk](./ctk/) — Conformance Test Kit
The behavioral contract for all Loom runtimes. Any implementation — Java, Python,
or future — must pass the CTK to be considered conformant:
- 15 canonical test scripts covering every statement type
- Expected execution traces as ground truth
- Mock agent server for deterministic, isolated testing
- Trace comparison algorithm that ignores non-deterministic output values

### loom4py _(Coming Soon)_ — Python Runtime
A native Python port of the Loom runtime for the AI/ML community:
- Character-by-character Lexer (no regex, structural parity with Java)
- Recursive descent Parser
- AST nodes as Python dataclasses
- HarnessExecutor with `concurrent.futures` for parallel blocks
- CTK-validated behavioral parity with the Java reference implementation

---

## Getting Started

**Run a workflow (Java CLI):**

```bash
cd ai-agent4j-loom
mvn clean install
alias weave='java -cp "target/classes:target/lib/*" io.github.llm4j.loom.cli.WeaveCLI'
weave run research.loom --loot tools.loot --input topic="Neuro-Symbolic AI"
```

**Embed in a Java application:**

```java
LoomScript script = new LoomParser(new Lexer(source).tokenize()).parseScript();

ToolRegistry registry = new ToolRegistry();
new LootLoader().loadIntoRegistry("tools.loot", registry);

HarnessExecutor executor = new HarnessExecutor(script, registry, clientFactory);
executor.initialize();
executor.executeWorkflow("ResearchAndPublish", Map.of("topic", "AI Agents"));
```

**Run conformance tests:**

```bash
cd ctk
mvn test
mvn exec:java -Dexec.mainClass=io.github.loom.ctk.CtkMain
```

---

## Further Reading

- [Loom Language Guide](./ai-agent4j-loom/LOOM_GUIDE.md) — deep technical reference and advanced patterns
- [VS Code Extension Guide](./vscode-loom/README.md)
- [CTK Usage Guide](./ctk/README.md)
- [Contributing](../CONTRIBUTING.md)
