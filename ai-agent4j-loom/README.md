<img src="loom_logo.png" align="right" width="200" alt="Loom Logo">

# AI Agent4J Loom

Loom is a **Neuro-Symbolic Domain Specific Language (DSL)** designed specifically for orchestrating stateful, multi-agent AI workflows.

Built as an extension to the `ai-agent4j` engine, Loom replaces complex framework boilerplate with a highly expressive, deterministic orchestration language (`.loom`). By enforcing a strict boundary between the non-deterministic "*Neural*" layer (agents generation) and the rigid, deterministic "*Symbolic*" layer (routing and execution flow), Loom maximizes reliability, interpretability, and scale.

## Core Features
*   **External DSL Runtime:** Loom ships with a handcrafted Lexer, Recursive Descent Parser, and AST Execution Engine that dynamically boots agents without recompiling Java.
*   **Expressive Routing:** Support for structural semantics like `handoff`, `delegate`, `broadcast` (parallel stream execution), `alt` (conditionals), and `loop until`.
*   **Human-In-The-Loop:** Built-in semantic support for `human_prompt` to easily pause execution and await external human verification or input without thread blocking trickery.
*   **Inversion of Control via `.loot`:** Rigid adherence to the principle of least privilege. Workflows define tools by name inside `.loom`, but the actual fully qualified Java classpath mapping must be explicitly provided in a separate `.loot` file, dynamically instantiated using Reflection.

---

## 1. Defining Agents
Agents are bounded blocks where you assign models, prompt architectures, and tool capabilities.

```text
agent WorkerA {
    model: "claude-3-haiku"
    system: "You are a data evaluation specialist."
    tools: [DatabaseSearch] 
}

agent Critic {
    model: "gpt-4o"
    system: "You evaluate logical flow."
    tools: []
}
```

## 2. Orchestrating Workflows
Workflows map execution sequences. Variables are routed downstream using the `->` operator.

```text
workflow ExecuteTask() {
    note "Initializing parallel fetch"
    
    // Broadcast concurrently runs all agents using parallel Java streams.
    broadcast "Analyze standard metrics" to [WorkerA, WorkerB] -> analysis

    // Conditional evaluation dynamically queries the context state
    alt (quality_score > 0.8) {
        delegate "Proceed with submission." to WorkerA -> submission
    } else {
        // Pausing for manual intervention
        loop until (is_approved == "true") {
             delegate "Fix formatting: analysis" to WorkerA -> analysis
             human_prompt "Review formatted output. Type true to approve." -> is_approved
        }
    }
    
    note "Execution concluded."
    handoff "Final Submission: analysis" to TerminalAgent
}
```

## 3. Dynamic Tooling (`.loot` Files)
"Loot" is "Tool" backwards! To ensure a neuro-symbolic separation, your `.loom` scripts never touch source code. Instead, you declare a `.loot` companion file containing key-value reflection targets that `LootLoader` will inject tightly upon initialization.

```properties
# tools.loot
DatabaseSearch = io.github.llm4j.tools.SqlTool
WebCalculator = com.mycompany.tools.MathEngine
```

## Usage in Java
The `HarnessExecutor` bridge evaluates the AST directly into active JVM `ReActAgent` calls.

```java
// 1. Read files
String scriptContent = Files.readString(Path.of("orchestration.loom"));
String lootPath = "tools.loot";

// 2. Parse Code
Lexer lexer = new Lexer(scriptContent);
LoomParser parser = new LoomParser(lexer.tokenize());
LoomScript script = parser.parseScript();

// 3. Register Tools
ToolRegistry registry = new ToolRegistry();
new LootLoader().loadIntoRegistry(lootPath, registry);

// 4. Execute Workflow
HarnessExecutor executor = new HarnessExecutor(script, registry, myClientFactory);
executor.initialize();
executor.executeWorkflow("ExecuteTask", new HashMap<>());
```

## Status
Loom is fully tested and capable of orchestrating highly sophisticated graphs including N-round debates, parallel MapReduce sweeps, and Human-in-the-Middle approvals directly out of the box.
