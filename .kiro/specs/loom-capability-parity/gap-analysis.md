# Gap Analysis: ai-agent4j Capabilities vs. What Loom Exposes

*Status: P0 and P1 are implemented (spec in this folder); P2 and P3 are implemented (spec in
[`../loom-capability-depth/`](../loom-capability-depth/)). The Anthropic provider exists now, with every provider
meeting one contract (spec in [`../uniform-providers/`](../uniform-providers/)); an OpenAI provider is what
remains of the "Library" row.*

## 1. Summary

Loom scripts can define agents with a model, a system prompt, tools, MCP servers, skills, a temperature,
an output schema and a budget, and orchestrate them richly. But a large part of ai-agent4j can't be
reached from a script today. There are three kinds of gap:

1. **Silently ignored syntax.** `knowledge:` and `memory { }` on an agent parse, then only log
   *"Placeholder implementation"*. A routing policy's `strategy:` is parsed and ignored. Skills are
   dropped when an agent also has a `persona` or `system_template`. Scripts look like they do something
   they don't. This is the most urgent kind of gap.
2. **Capabilities with no script syntax.** RAG (vector stores, embeddings, chunking), conversation and
   semantic memory, knowledge graphs, voice (text-to-speech, speech-to-text, audio), Indic language
   services (translation, transliteration, language detection), tool approvals, bias monitoring, PII
   masking, iteration limits, agent event listeners and streaming.
3. **Capabilities reachable only through Java.** Every tool that needs configuration (API keys, a spec
   URL, a graph, a memory service) can't be declared: `.loot` only creates tools with a no-argument
   constructor. Prompt registries, custom personas, and model providers other than Gemini and Ollama
   need Java code.

One documentation error: the root README advertises *"human approval gates (`approve` step in
workflows)"*. There is no `approve` syntax in Loom.

---

## 2. Scorecard

✅ exposed · ⚠️ partial or misleading · ❌ not exposed

| Area | ai-agent4j has | Loom today | |
|---|---|---|---|
| **Models** | Google (Gemini), Ollama, Sarvam chat; `RoutingLLMClient` with cost-aware and fallback strategies | `model:` resolves only Gemini and Ollama (`DefaultLLMClientFactory`); `routing` block always uses cost-aware (`strategy:` ignored) | ⚠️ |
| **System prompt** | `systemPrompt`, `instructions`, `PromptRegistry` + `systemPromptId` | `system:` ✅; `system_template:` only if Java calls `setPromptRegistry` | ⚠️ |
| **Personas** | `AgentPersona` (custom), `PersonaLibrary` | `persona:` names a `PersonaLibrary` method only (reflection); no custom personas | ⚠️ |
| **Skills** | `AgentSkill` from filesystem, classpath, remote URL; `RestSkillRegistry`; `SkillDiscoveryTool` | `skills: ["fs://…", "classpath://…"]` appended as text, **dropped if `persona` or `system_template` is set**; no remote skills or discovery | ⚠️ |
| **Tools: built-in** | DuckDuckGo, Calculator, DateTime, CurrentTime, Echo, CachedSearch, FallbackSearch | only via a `.loot` file mapping a name to a class; no built-in names | ⚠️ |
| **Tools: configurable** | SerpApi (key), Google Custom Search (key + cx), OpenAPI (spec → tools), DelegateTask, MemoryManagement, SkillDiscovery, Graph extraction/query, ScheduledAction | **none**: `.loot` needs a no-arg constructor | ❌ |
| **MCP** | `McpClient` over stdio: list and call tools | `mcp Name { cmd … env … }` + `mcp_servers: […]` | ✅ (both sides stdio-only) |
| **RAG** | `RAGAgent`, `VectorStore` (in-memory, file; PGVector, Pinecone in addons), `EmbeddingProvider` (Gemini; DJL, ONNX in addons), `Document`, chunking | `knowledge Name { type path chunk_size embedding_provider }` + `knowledge: [..]`, **placeholder: logged, never built** | ❌ (misleading) |
| **Conversation memory** | `ConversationHistory`, `ConversationStore` (in-memory, file, async) | agent `memory { type path limit }` **placeholder** | ❌ (misleading) |
| **Semantic memory** | `SemanticMemoryService`, `SemanticMemoryConfig`, `MemoryManagementTool` | none (Loom has its own `MemoryEngine`/Engram for workflow context, not the agent's) | ❌ |
| **Knowledge graphs** | `KnowledgeGraph`, `GraphExtractionTool`, `GraphQueryTool` | none | ❌ |
| **Voice** | TTS / STT providers (Sarvam), `AudioPlayer`, agent `ttsProvider`, `sttProvider`, `autoPlayAudio`, `ttsLanguage`, `ttsModel` | none | ❌ |
| **Indic language services** | Sarvam translation, transliteration, language detection | none | ❌ |
| **Human approval** | `ApprovalCallback` on tool calls | only `human_prompt` (a question), and the Java `customizeAgent` hook; no `approve` syntax despite the README | ❌ (doc says ✅) |
| **Iteration limit** | `maxIterations` | not settable (library default) | ❌ |
| **Privacy** | `PIIDetector` + `MaskingStrategy` | `guardrail PII { } on_violation { }`: detects in variables before the body; no masking, no output check, other guardrail types just run the body | ⚠️ |
| **Fairness** | `BiasMonitor` | none | ❌ |
| **Audit** | `AuditLogger` (file, no-op), per-agent `sessionId` | `audit { logger: "file" path }` for workflow events; agents are not given the workflow's audit logger | ⚠️ |
| **Events / streaming** | `AgentEventListener` (thoughts, tools, observations, budget), `chatStream` | Java hooks only (`customizeAgent`); nothing a script or the CLI can show | ⚠️ |
| **Budgets, rate limits** | budgets, windows, rate-limit signal | ✅ fully exposed (this release) | ✅ |
| **Scheduling** | `AgentScheduler`, `ScheduledActionTool` (in memory) | ✅ `schedule` blocks, trigger store, system triggers (goes further than ai-agent4j) | ✅ |
| **Structured output** | parsing in the agent | ✅ `output_schema`, `expecting` | ✅ |
| **Multi-agent delegation** | `DelegateTaskTool` (manager spawns workers) | ✅ covered differently: `delegate`, `broadcast`, `for each … to {x.owner}`, `handoff` | ✅ |

---

## 3. The gaps in detail

### 3.1 Silently ignored syntax (fix first)

| Where | What happens | Evidence |
|---|---|---|
| agent `knowledge: [KB]` + `knowledge KB { … }` | parsed; the executor logs "RAG enabled … (Placeholder implementation)" and builds a plain agent | `HarnessExecutor.initialize()`, "Tier 2: Wrap with RAG" block |
| agent `memory { type path limit }` | parsed; logs "Semantic memory enabled … (Placeholder implementation)" | same block |
| `routing P { strategy: "…" }` | `RoutingPolicyDef.strategy` parsed, never read; always `CostAwareRoutingStrategy` | `initialize()`, routing branch |
| `skills:` with `persona:` or `system_template:` | `resolveSystemPrompt` returns before appending skills | `resolveSystemPrompt` |
| `guardrail X { }` for any X other than PII | the body just runs | `executeStatement`, guardrail branch |

Until these are implemented, each should at least **fail at load time** with a clear message.

### 3.2 Tools that need configuration

`.loot` is a `Properties` file of `name = fully.qualified.Class`, instantiated with a no-arg constructor
(`LootLoader`). So none of these can be used from a script:

| Tool | Needs |
|---|---|
| `SerpApiSearchTool` | API key |
| `WebSearchTool` (Google Custom Search) | API key, `cx` |
| `OpenAPITool` | spec location (one spec becomes many tools) |
| `GraphExtractionTool`, `GraphQueryTool` | a `KnowledgeGraph` |
| `MemoryManagementTool` | a `SemanticMemoryService` |
| `SkillDiscoveryTool` | a `SkillRegistry` |
| `DelegateTaskTool` | a `ToolRegistry` and a client |
| `ScheduledActionTool` | an `AgentScheduler` (now better served by Loom triggers) |

Even no-arg built-ins (DuckDuckGo, Calculator, DateTime) need a `.loot` line. A script can't say
`tools: [web_search, calculator]` on its own.

### 3.3 Knowledge and RAG

ai-agent4j has every piece: documents, chunking, embeddings (Gemini, and DJL/ONNX for local), vector stores
(memory, file, PGVector, Pinecone) and `RAGAgent` to put retrieval in front of an agent. Loom already has
the syntax shape (`knowledge` blocks and `knowledge:` on agents) but none of the wiring: no loading,
chunking, embedding, indexing or retrieval.

### 3.4 Memory

Two different things are called "memory":

- **Workflow memory** (Loom's `MemoryEngine`, Engram): what each delegate sees of earlier steps. It works.
- **Agent memory** (ai-agent4j): an agent's own conversation history across runs (`ConversationStore`)
  and long-term facts (`SemanticMemoryService`, with a tool to manage them). The `memory { }` block was
  meant for this, but it is a placeholder.

### 3.5 Voice and languages

ai-agent4j's Sarvam integration covers speech-to-text, text-to-speech, translation, transliteration and
language detection, and `ReActAgent` can speak its answers. None of it is reachable from a script, which
rules out voice agents and multilingual (Indic) workflows in Loom.

### 3.6 Governance

- **Approvals**: `ApprovalCallback` lets a person approve a tool call before it runs. Loom can't declare
  which tools need approval, and can't route approvals through its durable `human_prompt` machinery (which
  already pauses runs without holding a thread).
- **PII**: detection only, on variables, before a block. No masking (`MaskingStrategy`) and no check of
  what an agent produces.
- **Fairness**: `BiasMonitor` isn't reachable.
- **Audit**: agents keep their own (default) audit logger, so tool-level audit events from agents don't
  reach the script's `audit` log.

### 3.7 Agent tuning

`max_iterations`, and the budget's partial-answer vs fail policy per agent (`onBudgetExhausted`), can't be
set from a script.

### 3.8 Models and providers

`DefaultLLMClientFactory` understands `gemini*` and `ollama/…` (plus llama/gemma/mistral names). ai-agent4j's
Sarvam chat provider isn't reachable. Neither are custom base URLs or keys per agent, or the fallback
routing strategy. (ai-agent4j itself has no OpenAI or Anthropic chat provider, although its rate-limit
parsers already understand their headers. That gap is in the library, not in Loom.)

---

## 4. Recommendations

Guiding rules (from how Loom has grown so far):

- **Fix the framework, keep scripts symbolic.** Put the real logic in ai-agent4j and Loom's runtime; the
  script says *what*, in a line or two.
- **Capabilities become agent properties or tools, not new statements.** Speaking, translating, searching
  and retrieving are things agents *use*. Exposing them as tools and agent properties adds almost no
  grammar.
- **Secrets stay in the environment.** Scripts refer to `env.NAME`, never a value.
- **Fail loudly.** Anything the runtime can't honour is a load-time error, never a log line.

### 4.1 Proposed shape (illustrative)

```loom
// Tools declared once, configured in the script (secrets from the environment)
tool Search   { use: serpapi  api_key: env.SERPAPI_KEY }
tool Petstore { use: openapi  spec: "https://petstore3.swagger.io/api/v3/openapi.json" }
tool Graph    { use: knowledge_graph  store: "graphs/support.json" }

knowledge Handbook {
    source: "docs/handbook/"               // files, URLs
    chunk_size: 800
    embeddings: "gemini/text-embedding-004" // or "onnx/all-MiniLM-L6-v2" (addons)
    store: file("index/handbook")           // memory | file(…) | pgvector(env.PG_URL) | pinecone(…)
    top_k: 5
}

agent Support {
    model: "gemini-2.5-flash"
    persona: "customerSupport"
    skills: ["fs://skills/refunds.md"]       // now kept alongside persona/template
    tools: [Search, Petstore, calculator, datetime]   // built-ins by name, no .loot needed
    knowledge: [Handbook]                    // real retrieval
    memory { conversation: file("chats/")  semantic: true  limit: 20 }
    approve: [Petstore]                      // these tool calls need a person (durable: pauses the run)
    max_iterations: 8
    guard { pii: mask  bias: warn }
    voice { speak: "sarvam/bulbul:v2"  language: "hi-IN" }
}

routing Cheap { strategy: fallback  primary: "gemini-2.5-flash"  fallbacks: ["ollama/gemma3"] }
```

Voice and language services also become built-in tools (`translate`, `transliterate`,
`detect_language`, `speak`, `transcribe`), so a workflow can use them through any agent.

### 4.2 Priorities

| Priority | Item | Why | Size |
|---|---|---|---|
| **P0** | Make ignored syntax fail loudly (knowledge, memory, routing strategy, unknown guardrail types); fix skills being dropped with persona/template; correct the README's `approve` claim | Scripts must not lie | S |
| **P1** | `tool` declarations with config + built-in tool names (search, calculator, datetime, OpenAPI, SerpApi, Google CSE) | Unlocks most real agents without Java | M |
| **P1** | Real `knowledge` → RAG (sources, chunking, embeddings, stores, `top_k`) | The syntax exists and users expect it to work | M–L |
| **P1** | `approve: [tools]` wired to `ApprovalCallback` and durable human prompts | Governance; the README already promises it | M |
| **P1** | `max_iterations`; agents use the script's audit logger | Small, frequently needed | S |
| **P2** | Agent `memory { conversation, semantic }` via `ConversationStore` / `SemanticMemoryService` | Assistants that remember users | M |
| **P2** | Voice and Indic language tools/properties (Sarvam) | Voice and multilingual agents | M |
| **P2** | More providers in `model:` (Sarvam chat; custom `base_url`/key per agent); `strategy: fallback` | Choice and cost | S–M |
| **P3** | Knowledge graph tools; `guard { pii: mask, bias: … }`; remote skills and discovery; custom personas in script | Depth | M |
| **P3** | Event streaming to the CLI (`weave run --trace` showing thoughts, tools, spend live) | Observability | S–M |
| **Library** | OpenAI and Anthropic chat providers in ai-agent4j | Loom can only expose what exists | M |

### 4.3 Suggested next step

Turn P0 and P1 into a spec (`requirements.md`, `design.md`, `tasks.md`, `verification.md`) in this folder,
as we did for budgets and resumable runs. P0 is small enough to ship on its own first.

---

## Appendix: where this was checked

- ai-agent4j:
  - `ReActAgent.Builder` (all options);
  - packages `agent/tools`, `agent/tool`, `agent/rag`, `agent/memory`, `agent/knowledge`, `agent/skill`,
    `agent/persona`, `agent/prompt`, `provider/*`, `privacy`, `fairness`, `routing`, `mcp`, `media`, `audit`;
  - the `ai-agent4j-addons` module: PGVector, Pinecone, DJL, ONNX.
- Loom:
  - `LoomParser.parseAgent` and the top-level declarations;
  - `AgentDef`, `KnowledgeDef`, `RoutingPolicyDef`, `McpServerDef`;
  - `HarnessExecutor.initialize` / `resolveSystemPrompt` / guardrail handling;
  - `LootLoader`, `DefaultLLMClientFactory`.
