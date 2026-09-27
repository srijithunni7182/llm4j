# Requirements Document

## Introduction

Loom should expose what ai-agent4j can do, and **never accept syntax it doesn't honour**. This spec covers
priorities P0 and P1 of the [gap analysis](gap-analysis.md):

- **P0, honesty.** Every declaration either works or is rejected when the script loads, with a message
  naming the line and what to do instead. Fix skills being dropped, honour the routing strategy, and
  correct the documentation.
- **P1, capability.**
  - Tools declared and configured in the script (with secrets from the environment), plus built-in tools
    by name, with no `.loot` file needed.
  - Knowledge bases that really retrieve (RAG).
  - Tool calls that need a person's approval, durably.
  - `max_iterations`.
  - Agents writing to the script's audit log.

Out of scope (P2 and later): agent conversation/semantic memory, voice and language services, new model
providers, knowledge graphs, PII masking, bias monitoring, remote skills, live tracing.

Standing rules: keep scripts symbolic (capabilities are agent properties or tools, not new statements); put
logic in the framework, not in apps; secrets only in environment variables; existing valid scripts keep
working.

---

## Glossary

- **Load time**: `HarnessExecutor.initialize()` (and `weave check`): after parsing, before any model call.
- **Load error**: an exception thrown at load time naming the line, the construct and the fix.
- **Lenient mode**: an opt-in (`setLenient(true)`, `weave run --lenient`) that turns *unsupported
  feature* load errors into warnings, for migrating old scripts. It never relaxes unknown names or
  missing secrets.
- **Tool declaration**: a top-level `tool Name { use: kind … }` block.
- **Built-in tool**: a tool usable by a fixed name without any declaration.
- **Secret field**: a tool option that holds a credential (`api_key`, `token`, auth values).
- **Env reference**: `env.NAME`, the value of environment variable `NAME`, read at load time.
- **Knowledge base (KB)**: a top-level `knowledge Name { … }` block: sources, chunking, embeddings, index.
- **Embedding factory**: creates an `EmbeddingProvider` from a model name (`gemini/text-embedding-004`);
  replaceable by the host, like `LLMClientFactory`.
- **Approval**: a person's yes/no before a specific tool call runs.

---

## Requirements

### Requirement 1 (P0): Nothing Silently Ignored

**User Story:** As a script author, I want Loom to tell me when something I wrote won't do anything, so I
never ship an agent that looks configured but isn't.

#### Acceptance Criteria

1. An agent's `memory` block SHALL be a load error naming the line: agent memory is not supported yet, and
   workflow context comes from Loom's memory engine.
2. A `guardrail` of a type other than `PII` SHALL be a load error listing the supported types.
3. A name in an agent's `tools:` that resolves to no tool SHALL be a load error naming the agent and the
   tool, and listing where names come from (tool declarations, built-ins, `.loot`, Java registration).
   Today it is only a warning.
4. An agent referring to an undefined `knowledge`, `routing` policy or `mcp` server SHALL be a load error.
5. An MCP server that fails to start SHALL be a load error naming the server and the cause (today it is
   logged and the agent silently has no tools).
6. In lenient mode, 1 and 2 SHALL be warnings instead; 3–5 SHALL remain errors.

---

### Requirement 2 (P0): Skills, Routing Strategy and Docs Honoured

**User Story:** As a script author, I want every setting I write to take effect.

#### Acceptance Criteria

1. An agent's `skills` SHALL be added to its prompt whether or not it also has `persona` or
   `system_template`.
2. A `routing` policy's `strategy:` SHALL select the routing strategy:
   - `cost_aware` (the default);
   - `fallback`: in order, primary then fallbacks.

   Values are case-insensitive and accept `-` or `_`. An unknown strategy SHALL be a load error.
3. A skill that can't be loaded (missing file, bad URI) SHALL be a load error naming the skill (today it is
   logged and skipped).
4. The root README, Loom READMEs and LOOM_GUIDE SHALL describe only syntax that exists:
   - no `approve` workflow step;
   - no inline `knowledge { }` / `memory { }` agent blocks;
   - the agent example updated to the syntax of this spec.

---

### Requirement 3 (P0): `weave check`

**User Story:** As a script author, I want to validate a script (and its tools, knowledge bases and
secrets) without running it or spending anything.

#### Acceptance Criteria

1. `weave check <script> [-l tools.loot] [--lenient]` SHALL parse the script and run every load-time check,
   without calling a model, embedding anything or starting MCP servers.
2. It SHALL print every problem (not just the first), each with its line, and exit 0 if there are none,
   else 2.
3. It SHALL report missing environment variables by name and never print secret values.

---

### Requirement 4 (P1): Tool Declarations

**User Story:** As a script author, I want to declare and configure an agent's tools in the script, so I
don't need Java or a `.loot` file for common tools.

#### Acceptance Criteria

1. The parser SHALL accept top-level `tool Name { use: <kind>  <option>: <value> … }`. Values are strings,
   numbers, booleans or env references.
2. The kinds SHALL be:

   | `use:` | Options | Tool |
   |---|---|---|
   | `duckduckgo` | `base_url?` | `DuckDuckGoSearchTool` |
   | `serpapi` | `api_key` (secret), `base_url?` | `SerpApiSearchTool` |
   | `google_search` | `api_key` (secret), `cx` | `WebSearchTool` |
   | `openapi` | `spec` (path or URL), `auth_header?` + `auth_value?` (secret), or `auth_query?` + `auth_value?` (secret) | `OpenAPITool` |
   | `calculator` | | `CalculatorTool` |
   | `datetime` | | `DateTimeTool` |
   | `current_time` | | `CurrentTimeTool` |
   | `class` | `class: "com.acme.MyTool"` | any no-arg `Tool` on the classpath |

3. A secret field SHALL only accept an env reference. A literal SHALL be a load error: "api_key must come
   from the environment, e.g. api_key: env.SERPAPI_KEY".
4. A missing environment variable, a missing required option, an unknown option or an unknown kind SHALL be
   a load error naming the tool and the line.
5. The built-in names `web_search`, `calculator`, `datetime` and `current_time` SHALL be usable in `tools:`
   without a declaration (`web_search` is DuckDuckGo).
6. A name in `tools:` SHALL resolve in this order: script tool declarations, then tools registered by the
   host (`.loot` or Java), then built-ins. A declaration that shadows a built-in SHALL win without warning.
7. The tool an agent sees SHALL be named as declared (`Search`), so prompts and approvals use the script's
   names.
8. `.loot` files and Java-registered tools SHALL keep working unchanged.

---

### Requirement 5 (P1): Knowledge Bases That Retrieve

**User Story:** As a script author, I want `knowledge:` on an agent to give it the relevant passages from
my documents, so answers are grounded in them.

#### Acceptance Criteria

1. The parser SHALL accept:

   ```text
   knowledge Name {
       source: "<file or dir>"
       chunk_size: N
       overlap: N
       embedding: "<model>"
       store: "<file>" | memory
       top_k: N
       mode: context | tool
   }
   ```

   The existing `path:` (alias of `source:`) and `type:` SHALL still parse; `type:` is ignored with a
   deprecation warning.
2. Sources SHALL be a file, or a directory read recursively. P1 indexes UTF-8 text files: `.md`,
   `.markdown`, `.txt`, `.html` (tags stripped), `.json`, `.csv`. Other files SHALL be skipped and counted
   in a load-time log line. A source that doesn't exist SHALL be a load error.
3. Embeddings SHALL come from the embedding factory:
   - `gemini/<model>` uses `GeminiEmbeddingProvider` with `GEMINI_API_KEY`;
   - `onnx/…` and `djl/…` use the addons when they are on the classpath, else a load error saying which
     module to add;
   - the host MAY replace the factory.

   Defaults: `chunk_size` 1000, `overlap` 100, `top_k` 4, `mode: context`, `store: memory`.
4. Indexing SHALL happen once at load time.
   - With a file `store`, the index and a manifest (source file hashes, chunk settings, embedding model)
     are saved.
   - A later load SHALL re-embed only files that changed, drop files that were removed, and rebuild
     everything if the chunk settings or embedding model changed.
5. `mode: context`: before each delegate to an agent with knowledge, the resolved task SHALL be embedded,
   the top `top_k` chunks across its KBs retrieved, and a *Relevant knowledge* section with each chunk's
   source prepended to the agent's context. A replayed step SHALL NOT retrieve.
6. `mode: tool`: the agent SHALL get a tool `search_<kb>` (`query` argument) returning the top chunks with
   their sources, and nothing is prepended.
7. An empty index (no indexable files) SHALL be a load warning; retrieval then adds nothing.
8. Embedding calls are not LLM calls: they SHALL NOT be charged to budgets. They SHALL be counted in the
   audit log (`knowledge_indexed`, with files, chunks and embedded chunks).

---

### Requirement 6 (P1): Tool Approvals

**User Story:** As a script author, I want certain tool calls to wait for a person's yes, so agents can't
publish, pay or delete on their own, and so a run waiting for that yes holds no thread.

#### Acceptance Criteria

1. The parser SHALL accept on an agent `approve: [Tool, …]` or `approve: all`. Every listed name SHALL be
   one of the agent's tools; otherwise it is a load error.
2. Before a listed tool runs, the executor SHALL ask the human interface: *"Agent <A> wants to call <Tool>
   with <args>. Reason: <thought>. Approve? yes/no"*. `yes`, `y`, `ok`, `approve` and `true` (any case)
   approve; anything else rejects.
3. The question SHALL be keyed `<step>#approve:<Tool>:<hash of tool and args>`, and the answer journaled.
   - On a resumed run, the same call SHALL reuse the recorded answer without asking.
   - A different call (other arguments) SHALL ask again.
4. With a durable journal and a human interface that suspends (as for `human_prompt`), the run SHALL pause
   (`RunSuspended`, reason `HUMAN`) and resume when the answer is recorded.
5. A rejected call SHALL NOT run. The agent receives the rejection as the tool's result and continues.
6. An agent with `approve` and no human interface SHALL be a load error.
7. The audit log SHALL record `approval_requested`, `approval_granted` and `approval_rejected` (agent, tool,
   step, and arguments with PII masked).

---

### Requirement 7 (P1): Agent Settings and Audit

**User Story:** As a script author, I want to bound how long an agent may reason, and see its tool calls in
the run's audit log.

#### Acceptance Criteria

1. The parser SHALL accept `max_iterations: N` on an agent (a positive whole number, else a load error),
   applied to the agent.
2. Agents SHALL use the executor's audit logger and session id, so the agent's own audit events (tool
   executions, decisions) reach the script's `audit` log.

---

### Requirement 8: Compatibility and Documentation

#### Acceptance Criteria

1. Every existing test SHALL still pass.
2. Scripts in the repository that used now-rejected syntax SHALL be migrated:
   - `tier2-test.loom`;
   - the tantrik-console `agents.loom` files;
   - any others found by `weave check`.
3. The LOOM_GUIDE, READMEs, LOOM_PROMPT and VS Code grammar and hovers SHALL document the new syntax. A
   "Tools, Knowledge and Approvals" section SHALL be added to the guide, and its examples SHALL be
   parse-checked in tests.
4. The documentation SHALL state plainly what is not supported yet (agent memory, voice, and the other P2
   items).
