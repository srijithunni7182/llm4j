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

# Cap what the run may spend (replaces the script's run budget) and see where it went
weave run research.loom --max-tokens 50000 --max-calls 40
weave run research.loom --max-cost 0.25 --prices prices.properties
```

With any budget in play, the run ends with a spend table (calls, prompt and completion tokens, cost per
agent). A run stopped by its budget exits with code 3 and keeps everything it already paid for.

```bash
# A durable run: it can pause on a rate limit or budget window and carry on later
weave run digest.loom --journal runs/digest-1          # exits 4 with "⏸ Paused … resumes at 07:00"
weave run digest.loom --journal runs/digest-1 --wait   # …or stay alive and resume by itself

weave resume runs/digest-1                  # resume a paused run now
weave tick runs/.loom-triggers              # fire whatever is due (what cron/systemd run)
weave daemon runs/.loom-triggers            # or keep one process watching
weave triggers list runs/.loom-triggers     # pause | enable | cancel | fire <id> too
weave triggers install runs/.loom-triggers --apply     # let the OS wake Loom (see Schedules and Triggers)
weave schedule sync digest.loom --store runs/.loom-triggers
```

Exit codes: `0` done, `1` failed, `2` bad options, `3` stopped by a budget, `4` paused.

```bash
weave check digest.loom          # every problem, with its line — without running anything or spending
weave run digest.loom --lenient  # accept not-yet-supported syntax as warnings while migrating
weave run digest.loom --trace    # watch agents think, call tools and spend, live, on stderr
weave run digest.loom --trace=json   # the same as JSON lines, for tools and dashboards
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

Agents in Loom are more than LLM wrappers: they carry tools, knowledge, skills, approvals, budgets and
governance, all declared in the script.

### Agent Definition Syntax
```loom
agent Analyst {
    model: "gemini-2.5-flash"            // or "sarvam/sarvam-m", "ollama/llama3", "Box/llama3" (a declared provider)
    persona: Mentor                      // a persona declared in the script, or a PersonaLibrary one ("technicalAnalyst")
    system: "You are an analyst specialising in {domain}."   // follows the persona when both are given
    temperature: 0.3                     // 0.0–2.0: creative roles high, checkers low
    max_iterations: 8                    // how many reasoning steps it may take

    skills: ["fs://skills/analyst_best_practices.md"]   // Markdown instructions, relative to the script
    tools: [Search, calculator]          // declared tools, built-ins, or .loot / Java tools
    knowledge: [Handbook]                // knowledge bases it draws on
    approve: [Search]                    // tool calls that need a person's yes
    routing: HighReliability             // a routing policy
    budget { tokens: 20000  per_call: 2000 }
    memory { conversation: "chats"  session: "{user_id}" }   // remembers each user's conversation
    voice  { speak: "sarvam/bulbul:v2"  language: "hi-IN" }  // speaks its answers
    guard  { pii: mask  bias: warn }                        // keeps personal data from the model
}
```

Every setting takes effect, and anything Loom can't honour is rejected before the run starts, with its
line number. `weave check` runs those checks without running anything.

**Not supported yet** (rejected with a clear message; `--lenient` turns them into warnings while you
migrate): guardrail statement types other than `PII` (use an agent's `guard { }` for bias). OpenAI chat
models aren't available yet: ai-agent4j has no provider for them.

### Tools, Knowledge and Approvals

**Tools** are declared once and configured in the script. Secrets can only come from the environment:

```loom
tool Search   { use: serpapi  api_key: env.SERPAPI_KEY }
tool Web      { use: duckduckgo }
tool Petstore { use: openapi  spec: "specs/petstore.json"  auth_header: "X-API-Key"  auth_value: env.PETSTORE_KEY }
tool Invoices { use: class  class: "com.acme.tools.InvoiceTool" }     // any no-arg Tool on the classpath
```

| `use:` | Options |
|---|---|
| `duckduckgo` | `base_url?` |
| `serpapi` | `api_key` (env), `base_url?` |
| `google_search` | `api_key` (env), `cx` |
| `openapi` | `spec` (path or URL), and optionally `auth_header` or `auth_query` with `auth_value` (env) |
| `calculator`, `datetime`, `current_time` | none |
| `class` | `class` |
| `translate`, `transliterate`, `detect_language`, `speak`, `transcribe` | see [Memory, Voice and Languages](#memory-voice-and-languages) |
| `knowledge_graph` | `store` (file or `memory`), `read_only?` |
| `skill_registry` | `url`, `api_key?` (env) |

- **Built-ins** work by name with no declaration: `web_search` (DuckDuckGo), `calculator`, `datetime`,
  `current_time`, and the language tools `translate`, `transliterate`, `detect_language`, `speak`,
  `transcribe` (with `SARVAM_API_KEY`).
- A name in `tools:` is looked up in the script's declarations, then in tools the host registered
  (`.loot` or Java), then among the built-ins. The model sees the name you gave the tool.
- Hosts can add their own kinds with `executor.addToolKind(...)`.

**Knowledge bases** index your documents, and agents get the relevant passages:

```loom
knowledge Handbook {
    source: "docs/handbook/"             // a file or directory: md, txt, html, json, csv
    embedding: "gemini/text-embedding-004"
    chunk_size: 800                      // default 1000
    overlap: 100                         // default 100
    top_k: 4                             // passages per question (default 4)
    store: "index/handbook.json"         // or memory (default)
    mode: context                        // context (default) | tool
}
```

- **`mode: context`**: before each delegate, the most relevant passages, with their source files, are put in
  front of the task.
- **`mode: tool`**: the agent instead gets a `search_handbook` tool and looks things up when it decides to.
- **Indexing** happens when the script loads. With a file `store`, later loads only re-embed files that
  changed, drop files that were removed, and rebuild if the embedding model or chunking changes.
- **Embeddings**: `gemini/<model>` uses `GEMINI_API_KEY`. `onnx/<model.onnx>|<tokenizer.json>` and
  `djl/<url>` run locally with the addons module. Hosts can plug in their own with `setEmbeddingFactory`.
- **Cost**: embedding calls are not LLM calls and are not charged to budgets. The audit log records a
  `knowledge_indexed` event with files, chunks and how many were embedded.

**Approvals** make chosen tool calls wait for a person:

```loom
agent Publisher {
    model: "gemini-2.5-flash"
    tools: [Instagram, calculator]
    approve: [Instagram]                 // or: approve: all
}
```

- **Before each approved call**, the human interface is asked: *"Agent Publisher wants to call Instagram
  with {…}. Reason: …. Approve? yes/no"*.
- **Rejected**: the call doesn't run, and the agent is told it was rejected.
- **Journaled**: the answer is recorded against the tool and its exact arguments. A resumed run never asks
  twice for the same call, and a different call is asked again. With a durable journal, the run pauses
  (holding no thread) until someone answers, as with `human_prompt`.
- **Audited**: `approval_requested`, `approval_granted` and `approval_rejected` are logged, with personal
  data masked.

### Memory, Voice and Languages

**Agent memory** lets an agent remember conversations and facts across runs. It's separate from Loom's
memory engine, which passes context between the steps of one run.

```loom
agent Concierge {
    model: "gemini-2.5-flash"
    memory {
        conversation: "chats"            // a directory (kept across runs), or memory
        limit: 20                        // messages put in front of each task (default 20)
        session: "{user_id}"             // whose conversation; default: the agent's name
        facts: "memory/facts.json"       // long-term facts (a file, or memory)
        embedding: "gemini/text-embedding-004"   // needed with facts
        recall: 5                        // facts recalled per task (default 5)
        min_similarity: 0.7              // how close a fact must be (default 0.7)
    }
}
```

- **Conversation**: before each delegate, the session's earlier messages come first. After an answer, the
  task and the answer are added.
- **Sessions**: `session` is resolved from the workflow's variables on every delegate, so one agent can
  serve many users, and they never see each other's messages or facts.
- **Facts**: the agent gets a `save_memory_fact` tool. Facts relevant to a task are recalled by meaning
  before it starts.
- **Resumed runs**: a replayed step neither recalls nor records.
- **Cost and audit**: embeddings aren't charged to budgets. Each recall is audited as `memory_recalled`.

**Language and voice tools** (Sarvam, with the key in `SARVAM_API_KEY`) work by name, like the other
built-ins:

| Tool | Does |
|---|---|
| `translate` | `text`, `target` (e.g. `hi-IN` or `Hindi`), optional `source` |
| `transliterate` | writes `text` in another script (`target`) |
| `detect_language` | returns a language code |
| `speak` | saves speech as a WAV under `audio/` and returns its path |
| `transcribe` | turns an audio file (`path`) into text |

Declare one to set defaults:

```loom
tool Hindi { use: translate  target: "hi-IN" }
tool Say   { use: speak  language: "ta-IN"  voice: "anushka"  out: "replies" }
```

Every kind also takes `api_key: env.X` and `base_url`. Files are only read and written inside the script's
directory.

An agent's **`voice`** makes the agent itself listen and speak:

```loom
agent Helpline {
    model: "gemini-2.5-flash"
    voice {
        listen: "sarvam/saarika:v2.5"
        speak: "sarvam/bulbul:v2"
        language: "hi-IN"
        voice: "anushka"
        out: "replies"
    }
}

workflow Main(recording) {
    delegate "{recording}" to Helpline -> answer   // recording = "calls/q1.wav": the agent hears it
    note "Spoken reply: {answer_audio}"       // replies/answer-….wav
}
```

- **Listening**: a task that is just the path of an audio file (wav, mp3, ogg, flac, m4a, aac, webm) is
  transcribed first.
- **Speaking**: the answer is spoken to a WAV file, and its path goes into `<result>_audio`. A resumed run
  restores that path without speaking again.
- **Failures**: if speaking or listening fails, the step fails, and `on_failure` gets `_error`.

### Models and Providers

`model:` understands these names:

- `gemini-…` (key in `GEMINI_API_KEY`);
- `claude-…` or `anthropic/<model>` (`ANTHROPIC_API_KEY`), e.g. `claude-opus-5-5`, `claude-haiku-4-5`;
- `ollama/<model>` (at `OLLAMA_BASE_URL`, default `http://localhost:11434`);
- `sarvam/<model>` (`SARVAM_API_KEY`).

To reach a specific endpoint with its own key, declare a **provider**:

```loom
provider Box    { use: ollama  base_url: "http://gpu-box:11434" }
provider Team   { use: sarvam  api_key: env.TEAM_SARVAM_KEY }

agent Local  { model: "Box/llama3" }
agent Indic  { model: "Team/sarvam-m" }
```

- `use:` is `gemini`, `anthropic`, `ollama` or `sarvam`.
- `api_key` must come from the environment.
- Declared providers work in routing policies too.
- An unknown model, or a missing key, is reported when the script loads.

### Model Routing Policies
Define global policies to manage costs and reliability across different LLM providers.

```loom
routing HighReliability {
    strategy: fallback                   // fallback: in the order written | cost_aware (default)
    primary: "gemini-2.5-pro"
    fallback: ["gemini-2.5-flash", "Box/gemma3"]
}
```

### Personas, Skills and Knowledge Graphs

**Personas** can be declared in the script. An agent's `system:` prompt follows its persona:

```loom
persona Mentor {
    role: "senior engineer who mentors juniors"
    expertise: "Java, testing"
    tone: "patient"
    description: "Explains the why before the how."
    constraints: ["Never write the code for them", "Ask one question at a time"]
}

agent Coach { model: "gemini-2.5-flash"  persona: Mentor  system: "Review the student's pull request." }
```

- `persona:` looks for a persona declared in the script first, then for one of the `PersonaLibrary`
  personas (`technicalAnalyst`, `softwareDeveloper`, …).
- An unknown persona is a load error, and so is a `system_template` with no prompt registry.

**Skills** can live at a URL:

```loom
skills: ["https://skills.example.com/refunds.md"]    // http:// only for localhost
```

- Remote skills are fetched when the script loads.
- A skill that can't be fetched fails the load.

A **skill registry** tool lets an agent find and read skills itself:

```loom
tool Skills { use: skill_registry  url: "https://skills.example.com/api"  api_key: env.SKILLS_KEY }
```

**Knowledge graphs** record entities and relations, and persist them:

```loom
tool Graph { use: knowledge_graph  store: "graphs/customers.json" }   // or store: memory
tool Facts { use: knowledge_graph  store: "graphs/customers.json"  read_only: true }
```

- The agent calls `Graph` with `action: "add"` (a subject, a predicate and an object) or `action: "query"`
  (by entity id, by type, or by relations from an entity).
- Declarations naming the same file share one graph.

---

## ⛓️ 3. Deep Dive: Workflow Orchestration

Loom provides **Symbolic Controls** that ensure your agents follow a rigid sequence of events.

### The Primitive Set
- **`delegate`**: Pass a task to an agent and await a result.
- **`handoff`**: Terminal node. Pass control to an agent and end the current script branch.
- **`broadcast`**: Parallel Map-Reduce. Executes a list of agents simultaneously and returns a combined JSON result.
- **`parallel { }`**: Concurrency block. Executes every statement inside the block in its own thread.
- **`alt` / `loop until`**: Comparison-based branching using `==`, `!=`, `>`, `<`, `>=`, `<=`.
- **`for each` / `parallel for each`**: Run a block once per item of a list, in order or all at once. See [for each](#for-each).
- **`human_prompt`**: Ask a person. With a run journal the run suspends instead of holding a thread. See [Durable Runs](#durable-runs-no-new-syntax).
- **`budget`**: Cap what a run, an agent or a single step may spend on LLM calls. See [Cost Budgets](#cost-budgets).

> **New in this release:** [cost budgets](#cost-budgets), [durable runs](#durable-runs-no-new-syntax), [`for each` with runtime routing](#for-each),
> [bounded loops](#bounded-loops), [retry backoff and timeouts](#retry-backoff-and-timeouts),
> [per-step schemas](#per-step-schemas-expecting), agent `temperature:` and `{var.list.0}` payload paths.

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

### Agent Guards

An agent's `guard` protects everything it does:

```loom
agent Support {
    model: "gemini-2.5-flash"
    guard {
        pii: mask                        // mask | block | warn
        bias: warn                       // warn | block
        bias_model: "gemini-2.5-flash"   // optional: a model judges bias instead of rules
    }
}
```

- **`pii: mask`**: every message the agent sends its model has emails, phone numbers, SSNs, card numbers
  and IP addresses replaced by placeholders (`[EMAIL]`). That covers the task, context, memory and tool
  results. The answer is masked before it is stored.
- **`pii: block`**: a task or an answer that contains personal data fails the step. `_error` names the
  kinds of data, never the values.
- **`pii: warn`**: records `pii_detected` and carries on.
- **`bias`**: the answer is checked. The default check uses rules that catch sweeping statements about
  groups of people; `bias_model` asks a model instead, charged to your budgets. Findings are audited as
  `bias_detected`, and `block` fails the step when a finding is HIGH or CRITICAL.
- **Audit and resumed runs**: the audit log records counts and kinds, never the data itself. Replayed
  steps aren't checked again.

URLs are not treated as personal data.

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

// Field types: string, number, boolean, enum[...], list<type>, a bare list (items of any shape),
// and nested { ... } objects. Fields keep the order they are written in.

workflow Audit() {
    delegate "Check this" to Auditor -> report
    
    // Typed symbolic check (Status is an enum, not just a string)
    alt (report.status == "SECURE") {
        note "System is secured."
    }

    // Fields of a structured result can be passed straight into the next task
    delegate "Fix the first issue: {report.issues.0}. Status was {report.status}." to Fixer -> patch
}
```

`{var.field}` paths work in delegate payloads exactly as they do in conditions: map keys and list
indexes (`{plan.hooks.0}`). A missing field reads as empty, as it does in `alt` conditions.

### Bounded Loops
`loop until` can carry a safety bound. If the condition still isn't true after `max` rounds, the loop stops
and runs its `on_exhausted` block, so a model that keeps failing can't spin (and bill) forever. The current
round is available as `{_loopRound}`, and the number of rounds run as `{_loopRounds}` in `on_exhausted`.

```loom
loop until (review.verdict == "COMPLETE") max 5 {
    delegate "Review round {_loopRound}" to Lead -> review
    alt (review.verdict == "INCOMPLETE") {
        delegate "Fix: {review.fix}" to Worker -> work
    }
} on_exhausted {
    note "Still incomplete after {_loopRounds} rounds"
}
```

### Durable Runs (no new syntax)
Every step with side effects (a `delegate`, a `human_prompt`, a `broadcast`) records its result in a
**run journal** under a stable step id: its position in the script, including loop round and branch.
Run the workflow again with the same journal and Loom **replays** the recorded steps, so no model is called
twice, and carries on from the first step that hasn't happened yet. That gives you:

- **Waiting for people without holding a thread.** A `HumanInterface` can throw `RunSuspended` instead of
  blocking. Record the answer with `journal.answer(stepId, answer)` whenever it arrives, even days later,
  and run the workflow again on any server.
- **Crash recovery.** If a process dies mid-run, run the workflow again with its journal; it resumes after
  the last recorded step.
- **Tools and approvals that need a human mid-step** call `executor.awaitHuman(key, question)`. It is not
  an error and is never retried: the step re-runs on resume and gets the answer.

Journals come in memory (the default), file (`FileRunJournal`) and SQL (`JdbcRunJournal`) versions.

```java
executor.setJournal(new JdbcRunJournal(dataSource, runId));
try {
    executor.executeWorkflow("Main", inputs);            // runs until done…
} catch (RunSuspended waiting) {                          // …or until a human is needed
    save(runId, waiting.stepId(), waiting.prompt());      // show the question, free the thread
}
// later, anywhere:
journal.answer(stepId, "yes");
newExecutor.setJournal(new JdbcRunJournal(dataSource, runId));
newExecutor.executeWorkflow("Main", inputs);              // replays, then continues
```

### for each
Run a block once per item of a list, one after another, or all at once with `parallel for each`. The item
is visible to the block as `{item.field}` (and its position as `{_index}`). The agent and the result
variable can come from the item, so one line routes work to whoever should do it:

```loom
for each fix in review.fixes {
    delegate "Fix this: {fix.problem}" to {fix.owner} -> {fix.output}
}

parallel for each shot in plan.shots {
    delegate "Shoot {shot.description}" to Photographer -> {shot.name}
}
```

### Retry Backoff and Timeouts
A step can wait between retries (the wait doubles each attempt) and give up on an attempt that hangs:

```loom
delegate "Summarise {doc}" to Writer -> summary retry 2 backoff 2s timeout 90s on_failure {
    note "Writer unavailable: {_error}"
}
```

### Per-Step Schemas (`expecting`)
One agent can return different structures in different steps. `expecting { ... }` on a delegate overrides
the agent's `output_schema` for that step only:

```loom
delegate "Cast the team" to Showrunner -> casting                      // uses Showrunner's output_schema
delegate "Review the build" to Showrunner -> review expecting {         // this step's own contract
    verdict: enum["COMPLETE", "INCOMPLETE"],
    fix: string
}
```

### Cost Budgets
> **In depth:** [Budgets, Pausing and Scheduling](BUDGETS_AND_SCHEDULING.md) covers budgets, refilling
> windows, rate limits, pausing and resuming, schedules, system triggers, the CLI and Java embedding.

A budget caps what LLM calls may spend, in **tokens** (the default), **calls**, or **money** (with a price
table you supply). The runtime enforces it at the call itself: before each call it checks what is left
and **refuses without calling the model** if the call can't fit, and it lowers the answer's `maxTokens`
so one long answer can't break the cap. Budgets go in three places, one line each:

```loom
budget { tokens: 200000  calls: 150  warn_at: 80% }       // the whole run

agent Writer {
    model: "gemini/gemini-2.5-flash"
    budget { tokens: 20000  per_call: 2000 }                // this agent's share; per_call caps each answer
}

workflow Main(topic) {
    delegate "Draft {topic}" to Writer -> draft budget 5000 tokens
        on_failure { note "Out of budget: {_error}" }

    loop until (review.verdict == "OK") max 5 budget 30000 tokens {
        delegate "Review {draft}" to Critic -> review
    } on_exhausted {
        note "Stopped by {_loopExhaustedBy} after {_loopRounds} rounds"
    }

    alt (_budget.remaining < 20000) {                        // cost-aware routing, symbolically
        delegate "Polish {draft}" to CheapWriter -> final
    } else {
        delegate "Polish {draft}" to Writer -> final
    }
}
```

What happens when a budget runs out:

| Situation | Behaviour |
|---|---|
| A step's call would exceed a budget | The model is **not** called. The step fails: its `on_failure` runs with `{_error}` naming the budget. A budget refusal is **never retried**. |
| An agent runs out part-way through a task | Its best answer so far is bound to the output variable, `{_budget.exhausted}` becomes `true`, and `on_failure` runs if present; otherwise the run continues. |
| A loop's or for-each's budget runs out | It stops and runs `on_exhausted`, with `{_loopExhaustedBy}` set to `budget` (or `rounds` when `max` was reached). |
| Nothing handles it | `executeWorkflow` throws `BudgetExceeded`. Every variable already bound is kept, and `spend()` shows where the money went. |

- **Budgets nest.** Each call is charged to the run, its agent and every enclosing step, loop and for-each
  budget, and must fit all of them. Parallel branches share the same budgets exactly.
- **`{_budget.spent}`, `{_budget.remaining}`, `{_budget.calls}`, `{_budget.cost}` and
  `{_budget.exhausted}`** are live, so scripts can route to cheaper agents as money runs low.
- **Money** needs a price table. Prices go stale, so none ship with Loom: write a file of
  `model = input / output` prices per million tokens and pass it with `--prices` (or `setPriceTable`).
  Local `ollama/*` models cost nothing. A cost budget with an unpriced model fails at start-up.
- **Durable.** Each step's usage is journaled. A replayed step is never charged again, and a run stopped
  by its budget can be resumed with a bigger one: it continues from the refused step, and nothing already
  paid for runs twice.
- **Estimates.** Before a call, prompts are estimated at about 4 characters per token (+10%). Reported
  usage always wins; if a provider reports none, the charge is estimated and marked as such.
- **Reporting.** `executor.spend()` totals tokens, calls and cost per agent and per step; warnings
  (default at 80%) and refusals go to the audit log as `budget_warning` and `budget_refused`.

`budget` is a contextual keyword: scripts that use it as a variable name keep working. Scripts without
budgets are not metered at all.

### Pausing and Resuming on Limits
> In depth: [Budgets, Pausing and Scheduling §3–4](BUDGETS_AND_SCHEDULING.md#3-rate-limits-and-quotas).

Background agents run into limits that **lift at a known time**: a provider's per-minute rate limit, a
free tier's daily quota, or your own budget that refills every hour. Loom reads the reset time and, instead
of failing or retrying blindly, **pauses the run and resumes it when the limit lifts** — holding no thread
while it waits.

```loom
rate_limits {
    on_limit: suspend        // suspend | wait | fail
    max_wait: 24h            // give up on limits further away than this
    max_resumes: 50
}

budget {
    tokens: 100000 per day   // per minute | per hour | per day: refills each window
    when_exhausted: suspend  // stop (default) | suspend | ask
}
```

**Where the reset time comes from.** ai-agent4j reads it from the provider's refusal:
Gemini's error body (`RetryInfo`, and `QuotaFailure` — a per-day quota resets at midnight Pacific),
Anthropic's `anthropic-ratelimit-*-reset` headers, OpenAI's `x-ratelimit-reset-*`, or `Retry-After`.
With nothing to go on, it assumes 60 s, doubling on repeated refusals (up to an hour). Limits that lift
within 30 s are simply waited out inside the HTTP call; anything longer reaches Loom.

| Policy | What happens |
|---|---|
| `suspend` (default when the run has a durable journal) | The run stops with `RunSuspended` (reason `RATE_LIMIT` or `BUDGET_WINDOW`, `resumeAt`). The journal records why and until when. Resuming replays every finished step for free and re-runs only the step that hit the limit. |
| `wait` (default otherwise, up to 5 minutes) | The step waits for the reset, then runs again. |
| `fail` | The step fails: `on_failure` runs with `{_error}` naming the limit and its reset time. |
| `when_exhausted: ask` | A person is asked "Allow N more?". A journaled yes raises the budget by its original amount. |

- **Parallel work finishes first.** In `parallel`, `broadcast` and `parallel for each`, branches that can
  still work finish and are journaled; the run then pauses once, until the latest reset. A sequential
  `for each` pauses at the limited item.
- **Budget windows.** Spend in earlier windows no longer counts once a window rolls over (reports still
  show it all), and a refusal says when the budget refills. Journaled usage records when it was spent, so a
  resumed run only counts this window's spend.
- **Safety valves.** A reset further away than `max_wait` fails the run with a clear message; a run
  resumed more than `max_resumes` times is failed rather than retried for ever.
- **Visible.** `{_run.resumes}` and `{_run.lastSuspension.reason}` are available after a pause; the audit
  log records `run_suspended`, `run_resumed` and `rate_limit_wait`.
- **Refused calls are free.** A 429 counts as a call against your budget but costs no tokens.

Who resumes the run? Give the executor a **trigger store** and a run id, and a paused run leaves a resume
trigger there — see the next section.

### Schedules and Triggers
> In depth, with per-OS setup and Cloud Run: [Budgets, Pausing and Scheduling §5–9](BUDGETS_AND_SCHEDULING.md#5-schedules).

Everything that must happen later is a **trigger**, kept in a durable **trigger store** (files for one
machine, a SQL table for several): resumes of paused runs, and your `schedule` blocks.

```loom
schedule MorningDigest {
    cron: "0 7 * * *"                  // or: every: 6h
    timezone: "Asia/Kolkata"           // default UTC; daylight-saving safe
    run: DailyDigest(topic="AI agents")
    misfire: run_once                  // missed slots while nothing ran: run_once (default) | skip
    overlap: skip                      // previous run still paused: skip (default) | queue
}

schedule DailyCleanup {                // the classic form still works
    initial_delay: "30s"
    pattern: "24h"
    agent: AdminBot
    task: "Purge temporary RAG indices"
}
```

- When a script loads with a trigger store, its schedules are **written to the store**: new ones added,
  changed ones updated (keeping when they last ran), removed ones disabled. Without a store, classic agent
  schedules still run in memory, as before.
- Each scheduled run gets its own run id (`MorningDigest@2026-09-28T01:30:00Z`) and journal, so it can
  pause and resume like any other run while the schedule moves on.
- A trigger fires **once** even with several processes or machines on one store (claims are atomic; a
  claim left by a crashed process is taken over after 10 minutes).

**Waking Loom.** Something must look at the store now and then. Pick one:

| How | When |
|---|---|
| `weave daemon <store>` | A process that is always running anyway. |
| `weave triggers install <store> --apply` | Let the OS do it: a **cron** line, a **systemd** user timer, a **launchd** agent or a **Windows** scheduled task runs `weave tick <store>` every 5 minutes (`--every 1m`). No Loom process waits in between. |
| `--mode exact` | Also add one OS entry at each pending trigger's exact time (re-synced after every tick). |
| `--backend cloud-scheduler --url https://… --service-account …` | Google Cloud Scheduler calls your service's `POST /loom/tick` (`TriggerEndpoint`), for services that scale to zero. |

`install` shows exactly what it will write and run, and changes nothing without `--apply`. It only writes
user-level entries (never needs root), tags them, and `weave triggers uninstall <store> --apply` removes
exactly those. System schedulers don't see your shell's environment: keep API keys in a file
(`chmod 600`) and pass `--env-file ~/.loom/env`.

In Java, the same pieces:

```java
TriggerStore store = new JdbcTriggerStore(dataSource);          // or new FileTriggerStore(dir)
executor.setJournal(new JdbcRunJournal(dataSource, runId));
executor.setTriggerStore(store);
executor.setRunId(runId);                                         // paused runs leave "resume:<runId>"

TriggerRunner runner = new TriggerRunner(store, (trigger, id) -> {
    // rebuild the run named by id and call executeWorkflow; map the result to an Outcome
    return TriggerTarget.Outcome.done();
}, Clock.systemUTC());
runner.start(Duration.ofSeconds(5));                              // embedded, or:
TriggerEndpoint tick = TriggerEndpoint.fromEnvironment(runner, oidcVerifier); // POST /loom/tick
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
Recurring work is declared with `schedule` blocks — see [Schedules and Triggers](#schedules-and-triggers)
for cron schedules, scheduled workflows and keeping them across restarts.

### Live Trace

`weave run --trace` shows what agents do as they do it, on stderr:

```text
09:14:02 [Main/s0] Researcher  ▶ Find three sources on {topic}
09:14:03 [Main/s0] Researcher  💭 I should search first
09:14:03 [Main/s0] Researcher  🔧 Search {"query": "neuro-symbolic AI"}
09:14:04 [Main/s0] Researcher  👁 1. Neuro-symbolic AI survey …
09:14:06 [Main/s0] Researcher  ✔ Here are three sources …
```

- `--trace=json` prints one JSON object per line.
- From Java, `executor.addTraceListener(event -> …)` (added before `initialize()`) receives the same events:
  - delegates starting, ending or replayed;
  - thoughts, tool calls and observations;
  - spend;
  - approvals;
  - memory, guard and voice events;
  - pauses.

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
