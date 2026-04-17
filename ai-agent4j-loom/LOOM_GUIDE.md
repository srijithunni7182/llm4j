# 🧵 Loom: The Neuro-Symbolic Orchestration Standard

Loom is a **Neuro-Symbolic Domain Specific Language (DSL)** designed to bridge the gap between the probabilistic world of LLMs (**Neural**) and the deterministic world of business logic (**Symbolic**). 

This guide provides a comprehensive technical reference for setting up, writing, and executing Loom-based agentic workflows.

### ⚒️ The 'weave' CLI Utility
Loom comes with a built-in CLI called `weave` to simplify the developer experience.

- **Run Workflows**: Execute any `.loom` script directly from your terminal.
- **Package Workflows**: Bundle your scripts, tool mappings, and dependencies into a single, executable JAR file.

---

## 🚀 1. The Loom Developer Experience (DX)

### Installation & Alias
To use the Loom CLI, first build the project and set up an alias:

```bash
# Build the loom module
cd ai-agent4j-loom && mvn clean install

# Add alias for the 'weave' CLI
alias weave='java -cp "target/classes:target/lib/*" io.github.llm4j.loom.cli.WeaveCLI'
```

### The "Weave-First" Workflow
1.  **Define Logic** (`.loom` file): Write your neuro-symbolic agents and workflows.
2.  **Define Tools** (`.loot` file): (Optional) Map logical tool names to real Java implementation classes.
3.  **Run or Package**:
    - **Test**: Run `weave run script.loom` to see immediate results.
    - **Deploy**: Run `weave package script.loom --fat` to create a standalone executable JAR.

---

## 🛠️ 2. Using the 'weave' CLI

### 1. Direct Execution (`run`)
Test your scripts immediately without writing a single line of Java.

```bash
# Run the 'Main' workflow with inputs
weave run research.loom --loot tools.loot --input topic="NeuroSymbolic AI"
```

### 2. Packaging for Deployment (`package`)
Encapsulate your workflow into a JAR that can run anywhere.

- **Thin JAR** (Default): Contains only your script and logic. Requires dependencies on the classpath.
- **Fat JAR**: Bundles all dependencies (LLM clients, JSON parsers, etc.) into one file.

```bash
# Create a portable fat JAR
weave package research.loom --loot tools.loot --fat --out my-app.jar

# Run the packaged app anywhere
java -jar my-app.jar topic="Advanced Agentic Coding"
```

---

## 🤖 3. Deep Dive: Agent Configuration

Agents in Loom are more than just LLM wrappers; they are stateful entities with memory, knowledge, and governance.

### Agent Definition Syntax
```loom
agent Analyst {
    model: "gpt-4o"
    persona: "SeniorResearchAnalyst" // Reflective lookup from PersonaLibrary
    system: "You are an analyst specializing in {domain}."
    
    // Skill injection (Markdown-based instructions)
    skills: ["fs://skills/analyst_best_practices.md"]
    
    // Tools defined here must be mapped in .loot
    tools: [WebSearch, Calculator]
    
    // Configure Domain Knowledge (RAG)
    knowledge {
        type: "RAG"
        path: "data/kb/"
        chunk_size: 1024
        embedding: "text-embedding-3-small"
    }

    // Configure Long-Term Semantic Memory
    memory {
        type: "SEMANTIC"
        threshold: 0.85
        max_results: 5
    }

    // Apply a specific routing policy
    routing: HighReliability
}
```

### Model Routing Policies
Define global policies to manage costs and reliability across different LLM providers.

```loom
routing HighReliability {
    strategy: "COST_AWARE"
    primary: "gpt-4o"
    fallback: ["claude-3-haiku", "gemini-1.5-flash"]
}
```

---

## ⛓️ 3. Deep Dive: Workflow Orchestration

Loom provides **Symbolic Controls** that ensure your agents follow a rigid sequence of events.

### The Primitive Set
- **`delegate`**: Pass a task to an agent and await a result.
- **`handoff`**: Terminal node. Pass control to an agent and end the current script branch.
- **`broadcast`**: Parallel Map-Reduce. Executes a list of agents simultaneously and returns a combined JSON result.
- **`parallel { }`**: Concurrency block. Executes every statement inside the block in its own thread.
- **`alt` / `loop until`**: Comparison-based branching using `==`, `!=`, `>`, `<`, `>=`, `<=`.

### Concurrency Example (Parallel Branches)
```loom
workflow AuditData(data) {
    parallel {
        delegate "Scan security: {data}" to SecAgent -> sec_log
        delegate "Scan efficiency: {data}" to PerfAgent -> perf_log
    }
    // Execution waits here for both branches to finish
    handoff "Combine logs: {sec_log} + {perf_log}" to Manager
}
```

### Human-in-the-Loop (Interactive flows)
```loom
workflow ApprovedTransfer(amount) {
    delegate "Check balance for {amount}" to BankBot -> is_enough
    
    alt (is_enough == "true") {
        human_prompt "Approve transfer of {amount}? (type 'yes')" -> approval
        alt (approval == "yes") {
            delegate "Transfer {amount}" to BankBot -> tx_id
        }
    }
}
```

---

## 🛡️ 4. Enterprise Safety & Lifecycle

### PII Guardrails
You can wrap statement blocks in guardrails to prevent sensitive data leakage.

```loom
workflow ProcessFeedback(rawText) {
    guardrail (PII) {
        delegate "Summarize this: {rawText}" to NeuralAgent -> summary
    } on_violation {
        note "PII detected in input. Aborting request."
        handoff "Security Warning" to AdminAgent
    }
}
```

### Scheduled Background Tasks
Define recurring tasks that run independently of workflows.

```loom
schedule DailyCleanup {
    initial_delay: "30s"      // Boot grace period
    pattern: "24h"           // Interval
    agent: AdminBot
    task: "Purge temporary RAG indices"
}
```

### Observability
Use `observe` to log the state of variables at specific points for tracing.

```loom
workflow TracedExecute(id) {
    observe "Workflow Started" {id}
    delegate "..." to ... -> res
    observe "Step 1 Complete" {res}
}
```

---

## ☕ 5. Advanced: Custom Java Integration

If you are embedding Loom into an existing complex Java application rather than using the `weave` CLI, you can use the `HarnessExecutor` directly.

### The Library Bridge
The `HarnessExecutor` implements the standard `LoomEngine` interface and manages the AST execution lifecycle.

```java
// 1. Initialize Script and Tool Registry
LoomScript script = ... // Parsed via LoomParser
ToolRegistry registry = new ToolRegistry();
new LootLoader().loadIntoRegistry("tools.loot", registry);

// 2. Build the Engine
LLMClientFactory factory = new DefaultLLMClientFactory(); // Or your custom factory
HarnessExecutor executor = new HarnessExecutor(script, registry, factory);
executor.setHumanInterface(new ConsoleHumanInterface()); 

// 3. Initialize & Execute
executor.initialize(); 
executor.executeWorkflow("Main", Map.of("input", "data"));

// 4. Cleanup
executor.shutdown();
```

> [!TIP]
> **Why use the Java API?** While `weave` is best for standalone microservices and CLI tools, the Java API is essential for building custom UI wrappers, injecting proprietary database connections as tools, or integrating with Spring Boot / Quarkus.

---

## 🏗️ 6. Full Sample: The Autonomous Project Factory
A complete multi-agent system that plans, codes, and audits in parallel.

```loom
agent Architect { model: "gpt-4o"; system: "Generate a technical plan." }
agent Developer { model: "claude-3-opus"; system: "Implement according to plan." }
agent Auditor   { model: "gemini-1.5-pro"; system: "Find bugs and security risks." }

workflow BuildApp(prompt) {
    delegate "Plan for project: {prompt}" to Architect -> projectPlan
    
    parallel {
        delegate "Write code for: {projectPlan}" to Developer -> sourceCode
        delegate "Write unit tests for: {projectPlan}" to Developer -> testCode
    }
    
    delegate "Analyze vulnerabilities in {sourceCode}" to Auditor -> auditReport
    
    alt (auditReport == "SECURE") {
        handoff "Submit project: {sourceCode}" to Terminal
    } else {
        handoff "Fix these issues: {auditReport}" to Developer
    }
}
```
