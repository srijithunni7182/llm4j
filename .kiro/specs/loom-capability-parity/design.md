# Design Document: Loom Capability Parity (P0 + P1)

## Overview

Two layers, as elsewhere in Loom:

- **Script**: declares *what*. `tool` blocks, `knowledge` blocks, and the agent properties `tools:`,
  `knowledge:`, `approve:` and `max_iterations:`.
- **Runtime**: does the work, reusing ai-agent4j as it is:
  - tools: `DuckDuckGoSearchTool`, `SerpApiSearchTool`, `WebSearchTool`, `OpenAPITool`, …;
  - RAG: `EmbeddingProvider`, `VectorStore`, `FixedSizeChunkingStrategy`;
  - approvals: `ApprovalCallback` + `Tool.requiresApproval`.

ai-agent4j gains nothing new except, if useful, a small `NamedTool`. Loom gains a **validation pass** that
collects every problem before anything runs.

```
parse ──► Validator (all load-time checks, collects problems) ──► throws LoadErrors(list) / warnings
            │  used by initialize() and by `weave check` (which stops here)
            ▼
initialize: ToolFactory (declared + built-in tools) ─► KnowledgeIndexer (index / update) ─► agents
            (tools wrapped: NamedTool, ApprovalTool; knowledge: context retrieval or search_<kb> tool;
             max_iterations; audit logger + session id)
            ▼
delegate:  [mode: context] Retriever.augment(task) ─► agent.run ─► approvals via ApprovalGate
```

---

## 1. Validation (P0)

`io.github.llm4j.loom.execution.ScriptValidator`:

```java
record Problem(int line, String construct, String message, Severity severity) { }   // ERROR | WARNING
List<Problem> validate(LoomScript script, Context ctx);  // ctx: tool registry names, env lookup, lenient flag
```

- The AST gains a `line` on `AgentDef`, `KnowledgeDef`, `ToolDef`, `RoutingPolicyDef`, `McpServerDef`,
  `GuardrailStmt` and `MemoryConfig` (set by the parser from the opening token).
- Checks, in order:

  | Check | Severity |
  |---|---|
  | Unsupported syntax: agent `memory`; guardrail types other than PII | ERROR, or WARNING in lenient mode |
  | Unknown tool, knowledge, routing policy or MCP names on agents | ERROR |
  | `approve:` names not among the agent's tools; `approve` without a human interface | ERROR |
  | Tool declarations: unknown kind or option, missing required option, secret given as a literal, missing env var | ERROR |
  | Knowledge: source missing; embedding model not supported by the factory | ERROR |
  | Skills that can't be loaded; unknown routing strategy; `max_iterations` < 1 | ERROR |
  | Deprecated `type:` on knowledge; a KB with no indexable files | WARNING |

- `initialize()` runs the validator first and throws `LoomLoadException(List<Problem>)`, whose message lists
  every error as `line N: <construct>: <message>`. Warnings are logged.
- **Env lookup** is a `Function<String, String>` (default `System::getenv`), injectable for tests and never
  logged. Values are resolved at load time into the tool options.
- `weave check` builds the same context (the `.loot` names, a stub human interface that counts as present,
  since the CLI always has one), runs `validate`, and prints the problems. It never creates clients,
  embeds or starts MCP servers. MCP start-up failures (Req 1.5) are therefore reported only by
  `initialize()`.

**Lenient mode**: `HarnessExecutor.setLenient(boolean)`, `weave run|check --lenient`.

**Skills fix**: `resolveSystemPrompt` computes the base prompt (template, persona or `system`), then always
appends the skills section. A skill that fails to load becomes a Problem (the resolution is moved into
validation).

**Routing**: `RoutingPolicyDef.strategy` is normalised (lower case, `-` → `_`) and mapped to
`CostAwareRoutingStrategy` or `FallbackRoutingStrategy`. `fallback` adds the primary at `REASONING` and the
fallbacks at `BALANCED`, in order. The existing fallback strategy tries tiers in order: verify, and adjust
the tier mapping if needed.

---

## 2. Tools (P1)

### 2.1 Syntax and AST

```loom
tool Search   { use: serpapi  api_key: env.SERPAPI_KEY }
tool Web      { use: duckduckgo }
tool Petstore { use: openapi  spec: "specs/petstore.json"  auth_header: "X-API-Key"  auth_value: env.PETSTORE_KEY }
tool Mine     { use: class  class: "com.acme.tools.InvoiceTool" }
```

- `tool` is a **contextual keyword** (`tool` IDENTIFIER followed by IDENTIFIER then `{`), so existing
  scripts that use `tool` as a name keep parsing.
- `ToolDef(name, kind, Map<String, OptionValue> options, line)`, where
  `OptionValue = Literal(String | Number | Boolean) | Env(String name)`.
- An `env.NAME` value lexes as one dotted IDENTIFIER. The parser turns identifiers starting with `env.`
  into `Env`.

### 2.2 `ToolFactory`

```java
interface ToolKind {
    String name();                                   // "serpapi"
    Set<String> required(); Set<String> optional(); Set<String> secrets();
    Tool create(Map<String, String> resolvedOptions); // values already resolved from env
}
```

`ToolFactory` holds the kinds below, and hosts can add their own (`executor.addToolKind(kind)`):

| Kind | Required | Optional | Secrets | Creates |
|---|---|---|---|---|
| `duckduckgo` | | `base_url` | | `new DuckDuckGoSearchTool()` or `(client, base_url)` |
| `serpapi` | `api_key` | `base_url` | `api_key` | `SerpApiSearchTool(key[, client, base_url])` |
| `google_search` | `api_key`, `cx` | | `api_key` | `WebSearchTool(key, cx)` |
| `openapi` | `spec` | `auth_header`, `auth_query`, `auth_value` | `auth_value` | `OpenAPITool.builder().name(n).spec(OpenAPIParser.parse(spec)).headerAuth(…)/apiKeyAuth(…)` |
| `calculator`, `datetime`, `current_time` | | | | the no-arg tools |
| `class` | `class` | | | `Class.forName(c).getDeclaredConstructor().newInstance()`, which must implement `Tool` |

Rules:
- `auth_value` requires exactly one of `auth_header` or `auth_query`.
- `spec` accepts a path, relative to the script's directory, or an `http(s)` URL. It is parsed at load time
  and a bad spec is a Problem.

**Built-ins** are pre-declared `ToolDef`s: `web_search → duckduckgo`, `calculator`, `datetime`,
`current_time`.

**Resolution** for each name in an agent's `tools:`:
1. script declaration → factory;
2. `ToolRegistry` (`.loot` or Java), unchanged behaviour;
3. built-in.

Declared and built-in tools are wrapped in `NamedTool(name, delegate)` so the model sees the script's
name. Descriptions and arguments come from the delegate.

---

## 3. Knowledge (P1)

### 3.1 AST

`KnowledgeDef` gains `source` (with `path` as an alias), `overlap`, `store` (a path or `memory`), `topK`,
`mode` (`CONTEXT`, `TOOL`) and `line`. `embedding` and `chunkSize` already exist.

### 3.2 `EmbeddingFactory`

```java
@FunctionalInterface
public interface EmbeddingFactory { EmbeddingProvider create(String model); }   // Loom, like LLMClientFactory
```

`DefaultEmbeddingFactory`:
- `gemini/<m>` → `GeminiEmbeddingProvider(LLMConfig(apiKey=GEMINI_API_KEY), m)`;
- `onnx/<m>` → `OnnxEmbeddingProvider`, and `djl/<m>` → `DjlEmbeddingProvider`, both by reflection if
  `ai-agent4j-addons` is present;
- anything else, or a missing module or key, is a Problem.

Hosts and tests call `executor.setEmbeddingFactory(…)`.

### 3.3 `KnowledgeIndexer`

For each KB at load time:

1. **List files.** Sources are listed recursively, sorted; indexable extensions are kept. HTML has tags
   stripped with a small regex (no new dependency). Each file becomes a `Document` with metadata
   `{source: relative path}`.
2. **Chunk.** `FixedSizeChunkingStrategy(chunkSize, overlap)`.
3. **Store.** `memory` gives an `InMemoryVectorStore`; a path gives `FileVectorStore(<store>)` plus a
   manifest `<store>.manifest.json`:

   ```json
   { "embedding": "gemini/text-embedding-004", "chunkSize": 800, "overlap": 100,
     "files": { "guide/refunds.md": { "sha256": "…", "chunks": ["guide/refunds.md#0", "…#1"] } } }
   ```

4. **Update.**
   - A changed embedding, chunk size or overlap means clear and rebuild.
   - Otherwise, for each file: if its hash is unchanged, keep it; if changed, delete its chunk ids then
     add the new chunks; if the file is gone, delete its chunk ids.
   - New chunks are embedded with `embedBatch` in batches of 64.
5. **Report.** One `knowledge_indexed` audit event (`kb`, `files`, `skipped`, `chunks`, `embedded`) and a
   log line.

Chunk ids are `<relative path>#<n>`, so re-indexing a file replaces exactly its chunks.

### 3.4 Retrieval

`Retriever(kb list for the agent)`:
- `search(query, k)` embeds the query once per embedding model, searches each KB's store, merges results
  by similarity and takes the top `k`, where `k` is the largest `top_k` among the agent's KBs;
- `format` returns:

  ```text
  Relevant knowledge (from your knowledge bases):
  [1] (guide/refunds.md) …chunk text…
  [2] (faq.md) …
  ```

**Context mode.** In `executeDelegate`, after `memoryEngine.assembleContext` and before
`beforeDelegateExecution`: if the agent has context-mode KBs, then
`contextBriefing = retriever.format(search(resolvedPayload)) + "\n\n" + contextBriefing`. It is skipped on
replay (replays return before this point).

**Tool mode.** The agent gets a `search_<kb>` tool (name lower-cased): `execute({query})` returns the
formatted top-k for that KB.

Embedding calls go through the `EmbeddingProvider` directly, so they are not metered by budgets
(Req 5.8). Their errors are ordinary step failures: `on_failure`, retry and so on.

---

## 4. Approvals (P1)

- **Parsing.** `AgentDef` gains `approve` (a list of names, or `ALL`); the parser accepts
  `approve: [A, B]` or `approve: all`.
- **Wrapping.** Each approved tool is wrapped in `ApprovalTool(delegate)`, whose `requiresApproval(args)`
  returns true. The agent's builder gets an `approvalCallback` bound to the `ApprovalGate`.
- **`ApprovalGate.approve(agentName, toolName, args, thought)`**:
  1. `key = step.get() + "#approve:" + toolName + ":" + sha256(toolName + canonicalJson(args))[0..12]`
  2. `answer = journal.get(key)`. If it is absent, the gate asks
     `humanInterface.promptHuman(key, question)` and journals the answer. The prompt may throw
     `RunSuspended`, which is an `AgentInterrupt`, so ReActAgent propagates it and the run pauses.
  3. It audits `approval_granted` or `approval_rejected` (arguments passed through the PII detector's
     masking) and returns `yes(answer)`.

  `step.get()` is the delegate's step (the agent runs on the step's thread, or on a timeout thread that
  carries the step).
- **Rejection.** ReActAgent already turns `false` into the observation "A human supervisor rejected this
  action…" and continues (`StepOutcome.REJECTED_BY_HUMAN`).
- **Resuming** re-runs the step. The model may propose the same call (answered from the journal) or a
  different one (asked anew).
- **Canonical JSON** means map keys are sorted recursively, so the hash is stable.

## 5. Agent settings

- `max_iterations: N` sets `AgentDef.maxIterations` and `builder.maxIterations(n)`.
- The builder always gets `.auditLogger(auditLogger).sessionId(sessionId)`. The audit logger is configured
  before agents are built (existing step 0).

## 6. CLI

- `weave check <script> [-l loot] [--lenient]` prints the problems, then ✓ or the count; exit 0 or 2.
- `weave run --lenient` passes lenient mode to the executor.
- Run output (with a knowledge base): `📚 Handbook: 42 files (3 skipped), 311 chunks, 12 embedded (index up to date)`.

## 7. Testing approach

- **Embeddings.** A deterministic `HashingEmbeddingProvider` (test-only): a bag of words hashed into 256
  dimensions and L2-normalised. Similar texts get similar vectors, with no network, so retrieval tests
  have exact expectations. It counts calls, to assert that re-indexing is incremental.
- **Tools.** MockWebServer (already a Loom test dependency) serves SerpApi, DuckDuckGo and OpenAPI specs and
  endpoints, so the tests exercise a real HTTP round trip with the configured key.
- **Env.** An injected lookup map. Tests assert that secret values never appear in messages or logs.
- **Approvals.** The durable path is tested with a suspending `HumanInterface` and a `FileRunJournal`, as in
  the resumable-runs tests.

## 8. Decisions

- **Errors over warnings.** Scripts must not lie; lenient mode eases migration without hiding
  unknown-name mistakes.
- **Secrets only from the environment**, enforced by the parser/validator (a standing project rule).
- **Retrieval in context by default**: simplest for authors, and it matches `RAGAgent`. Tool mode suits
  agents that should decide when to look.
- **Approvals keyed by tool and arguments**, not call order. A resumed step's model may call tools
  differently, and an answer must never approve a call nobody saw.
- **No workflow-level `approve` statement.** `human_prompt` plus `alt` already gates workflows; approvals
  belong on tool calls. The README is corrected instead.
- **HTML stripped with a regex**, and PDF not supported in P1: no new dependencies.
