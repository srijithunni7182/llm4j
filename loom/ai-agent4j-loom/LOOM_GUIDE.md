<img src="loom_logo.png" align="right" width="200" alt="Loom Logo">

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

## 🚀 5. The Frontier: Advanced Orchestration

Loom now supports advanced features that bridge the gap between stochastic neural generation and deterministic symbolic logic.

### Output Schema Enforcement
Agents can now define a strict contract for their responses. Loom automatically instructs the LLM to respond in JSON and parses it into a structured object, enabling path-based symbolic checks.

```loom
agent Auditor {
    model: "gemini-1.5-pro"
    output_schema: {
        status: enum["SECURE", "VULNERABLE"],
        issues: list<string>
    }
}

workflow Audit() {
    delegate "Check this" to Auditor -> report
    
    // Typed symbolic check (Status is an enum, not just a string)
    alt (report.status == "SECURE") {
        note "System is secured."
    }
}
```

### Workflow-Level Retry & Error Contracts
Define resilience logic directly in the DSL. If an agent fails (API error, timeout, or malformed JSON), Loom handles retries and triggers the `on_failure` recovery block.

```loom
workflow RobustTask() {
    delegate "Process important data" to Worker -> res
        retry 3
        on_failure {
            note "Worker failed after 3 tries: {_error}"
            handoff "Manual recovery needed" to Supervisor
        }
}
```

### Workflow Composition & Modularity
Loom supports building complex multi-agent systems by composing workflows from multiple files. This is achieved using the `import` statement and the `call` statement.

#### 1. Importing External Files
Use `import` at the top of your script to bring in agents, workflows, and other definitions from another `.loom` file.

```loom
import "agents_library.loom"
import "sub_workflows/research.loom"

workflow Main() {
    call Analyze(topic="Neuro-Symbolic AI") -> researchData
}
```

> [!NOTE]
> **Flat Namespace**: Loom uses a flat namespace for all merged files. If multiple files define an agent or workflow with the same name, the system will use the one parsed last.
> **Relative Paths**: Import paths are resolved relative to the directory of the file containing the `import` statement.
> **Circular Safety**: Loom automatically detects and prevents circular imports (e.g., File A importing File B which imports File A), throwing a parser error if a cycle is found.

#### 2. Reusable Primitives (`call`)
Treat workflows as reusable components. Sub-workflows run in isolated variable scopes.

```loom
workflow Analyze(topic) {
    delegate "Research {topic}" to Researcher -> result
}

workflow Main() {
    call Analyze(topic="Neuro-Symbolic AI") -> researchData
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
