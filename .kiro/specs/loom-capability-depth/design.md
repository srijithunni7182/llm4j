# Design Document

## Overview

Every capability either becomes an agent property (`memory`, `voice`, `guard`, `persona`) or a tool kind
(`translate`, `speak`, `knowledge_graph`, `skill_registry`, …). The one exception is two new top-level
declarations (`provider`, `persona`) that agents refer to by name. The executor wires each one to
existing ai-agent4j classes. Where a piece is missing, it is added to ai-agent4j first.

```
             parse                 validate (ScriptValidator + executor checks)       build agents                  per delegate
 .loom ──► AST (AgentDef.memory,  ──► load errors with lines                   ──►  ReActAgent (+ memory tools,  ──► session memory, listen,
           voice, guard; ProviderDef,                                               guard client, listeners)        guard, speak, trace
           PersonaDef)
```

## 1. Agent memory (R1)

**AST.** `AgentDef.MemoryConfig` gains the following fields. The old `type`/`path` are kept only so the
validator can reject them with a migration message.

| Field | Type | Meaning |
|---|---|---|
| `conversation` | String | directory, or `"memory"` |
| `limit` | int | default 20 |
| `session` | String | |
| `facts` | String | file, or `"memory"` |
| `embedding` | String | |
| `recall` | int | default 5 |
| `minSimilarity` | double | default 0.7 |

**Parser.** `memory` with or without `:`. Keys are read with `word()`, since `memory` is a token.

**Runtime.** Handled by the package-private class `src/loom/memory/AgentMemory`, one per agent that has
memory:

- **Conversation store.** `FileConversationStore(baseDir.resolve(conversation))`, or
  `InMemoryConversationStore`, created once.
- **Facts.**
  - The store is `FileMemoryVectorStore` (new in ai-agent4j, `agent/memory`) or
    `agent.memory.InMemoryVectorStore`, created once.
  - The embedding provider comes from `EmbeddingFactory`.
- **`forSession(String session)`** returns a `ConversationHistory(agent + ":" + session, store, limit)`
  and a `SemanticMemoryService(embedding, factsStore, session)`.
- **Building the agent.** It is built with `save_memory_fact` bound to a *session-switching*
  `SemanticMemoryService`, a thin delegating subclass whose current session is a thread-local set for the
  duration of the delegate, so the tool writes to the right user.

**Per delegate** (in `executeDelegate`, not on replay):

1. Resolve the session with `resolvePayload(session)`, or use the agent's name.
2. Build a per-delegate agent with `agent.toBuilder().conversationHistory(h).semanticMemory(s).build()`.
3. `ReActAgent` already prepends the history and the facts, and appends the task and answer on success.
4. Audit and trace `memory_recalled`.

**Library changes.**

- `ReActAgent.Builder.semanticRecall(int topK, float minSimilarity)` (defaults 5 and 0.7, as hard-coded
  today).
- `toBuilder()` copies `ttsLanguage`, `ttsModel`, `semanticRecallTopK` and `semanticRecallThreshold`.
- `FileMemoryVectorStore extends agent.memory.InMemoryVectorStore`: a JSON file of `{id, embedding,
  metadata}`, written atomically (temp file, then move) on `add`, `delete` and `clear`, and loaded in the
  constructor.

## 2. Voice and languages (R2)

**New Loom classes.**

- **`tools/SarvamServices`** creates the providers from `api_key`/`base_url`: `SarvamTextProvider`,
  `SarvamTextToSpeechProvider`, `SarvamAudioProvider`.
- **`tools/LanguageTools`**: five `ToolKind`s registered in `ToolFactory`.
  - `translate`, `transliterate` and `detect_language` wrap `SarvamTextProvider`.
  - `speak` wraps TTS and writes `<out>/speech-<n>.wav` via `SafePaths.inside(baseDir, …)`.
  - `transcribe` wraps STT and reads through `SafePaths`.
- **`tools/SafePaths.inside(base, relative)`** normalises the path and refuses anything that leaves
  `base` (`..`, absolute paths elsewhere, symlinks resolved with `toRealPath` when the file exists).

**Built-ins.** `ToolFactory.BUILT_INS` gains these names, mapped to themselves. `builtIn(name)` gives
them `api_key: env.SARVAM_API_KEY`, and `base_url: env.SARVAM_BASE_URL` if that is set, so the existing
checks report a missing key.

**Agent `voice`.**

- **AST.** New `AgentDef.VoiceConfig` with `listen`, `speak`, `language`, `voice`, `out` and `line`.
- **Parser.** `voice { … }`.
- **Runtime.**
  - `executeDelegate`: after resolving the payload, if `voice.listen` is set and the payload is an audio
    path inside `baseDir`, the executor transcribes it (`TranscriptionRequest` with the language and
    model) and uses the transcript as the task.
  - After success (not on replay), if `voice.speak` is set, the executor synthesises the answer text and
    saves it through `SafePaths` as `<out>/<var>-<hash8 of step>.wav`.
  - It sets `<var>_audio` and journals `stepId + "#audio"`. On replay the value is restored from that
    entry.
  - Errors in listen or speak are handled like other step failures: `on_failure` with `_error`, else the
    run fails.

**Library changes.**

- `SarvamTextProvider.detectLanguage` calls `/text-lid` and reads `language_code` (and `script_code` into
  the response).
- `TranscriptionRequest.Builder.model(String)`. `SarvamAudioProvider` uses it when present.
- `SarvamChatProvider` uses `config.getDefaultModel()` when the request has no model.

## 3. Providers (R3)

- **AST.** `ProviderDef(name, kind, options: Map<String, OptionValue>, line)`. The parser reuses the
  tool-option grammar.
- **Factory interface.** `LLMClientFactory` gains:
  - `default LLMClient createClient(ProviderSpec spec, String model)`, which builds a real provider
    through `DefaultLLMClientFactory.forSpec(spec, model)`;
  - `default String problem(String model)`, returning null (trusted).
- **`ProviderSpec(name, kind, baseUrl, apiKey)`.** A record with values resolved from the environment.
  Its `toString` never prints the key.
- **`DefaultLLMClientFactory`.**
  - A constructor taking an env lookup (for tests), used for `GEMINI_API_KEY`, `OLLAMA_BASE_URL`,
    `SARVAM_API_KEY` and `SARVAM_BASE_URL`.
  - Adds `sarvam/…`.
  - Implements `problem(model)`: an unknown pattern, or a missing key, names the variable.
- **Executor.** `clientFor(model)`: if the prefix before `/` names a declared provider, it calls
  `factory.createClient(spec, rest)`; else `factory.createClient(model)`. Used for agents and routing
  policies.
- **Validation** (`checkProviders` in the executor):
  - the kind is one of `gemini`, `ollama`, `sarvam`;
  - the options are known;
  - the key comes from the environment and is present (required for gemini and sarvam);
  - the name isn't reserved or a duplicate;
  - each agent or routing model resolves: a declared provider, or `factory.problem(model) == null`.

## 4. Knowledge graphs (R4)

- **Library.** `agent/knowledge/store/FileGraphStore extends InMemoryGraphStore`.
  - It persists `{entities: [...], triples: [{subject, predicate: {type, properties}, object}]}` with
    Jackson.
  - It loads in the constructor (throwing `IllegalStateException` on a corrupt file) and saves atomically
    after `addEntity`, `addTriple` and `clear`.
- **Loom.** `tools/GraphTool(name, graph, readOnly)` dispatches `action` to `GraphExtractionTool` and
  `GraphQueryTool`, with a description listing both forms. The `knowledge_graph` kind has options
  `store` (required) and `read_only`.
- **Sharing graphs.** Graphs are cached in `ToolFactory` by absolute store path, or by tool name for
  `memory`.
- **Load time.** A corrupt store file is a creation error. `ToolKind.check` reports it at load time: it
  loads the file once to validate it.

## 5. Guards (R5)

- **AST.** `AgentDef.GuardConfig` with `pii` (MASK/BLOCK/WARN), `bias` (WARN/BLOCK), `biasModel` and
  `line`.
- **Library** (`io.github.llm4j.privacy.MaskingLLMClient`).
  - It wraps an `LLMClient` and rebuilds each `LLMRequest` with every message's content masked
    (`PIIDetector.mask(text, PLACEHOLDER)`).
  - It keeps the other request fields.
  - Its listener reports what it masked (`Map<PIIType, Integer>`).
- **Library** (`io.github.llm4j.fairness`).
  - `RuleBasedBiasMonitor` is a set of regex rules: group generalisations ("women are …", "all
    immigrants …", "old people can't …"), gendered role statements and age disqualifiers. Each rule has
    a `BiasType` and a `BiasSeverity`, and an explanation that names the matched group.
  - `LLMBiasMonitor(LLMClient)` asks for JSON `{"findings": [{"type", "severity", "explanation"}]}`.
    Output it can't parse counts as no findings, with a warning.
- **Executor.**
  - **Building the agent.** `pii: mask` wraps the agent's (metered) client in `MaskingLLMClient`, whose
    listener audits `pii_masked` and emits a trace event.
  - **Per delegate** (not on replay):
    - `pii: block`: the task is checked before the run.
    - `pii: block` or `warn`: the answer is checked after the run.
    - `pii: mask`: the answer is masked.
    - `bias`: the monitor runs on the answer. For `block`, `shouldIntervene` decides.
  - **Failures.** A block raises `GuardViolation(message)`. The existing failure path turns it into
    `on_failure`/`_error` and journals it as `failed` (only when `on_failure` exists, as today). A guard
    violation is not retried.
  - **Judge client.** The judge client is `metered(clientFor(biasModel), agentDef, biasModel)`, so budgets
    apply.

## 6. Remote skills and discovery (R6)

- **Remote skills.** `ScriptValidator.loadSkill` handles `https://` and `http://` (for `localhost` and
  `127.0.0.1` only) with `RemoteSkillLoader`, cached per URL in a static bounded map for the process.
- **Discovery.** A `skill_registry` kind with options `url` (required) and `api_key` (optional secret)
  builds `RestSkillRegistry`, wrapped in `SkillDiscoveryTool`, which is wrapped in `NamedTool`.

## 7. Personas (R7)

- **AST.** `PersonaDef(name, role, expertise, tone, description, constraints, line)`, stored in
  `LoomScript.personas` (merged on import).
- **Parser.**
  - A top-level `persona Name { … }`. `persona` is already a token, and the top-level parse checks for an
    identifier after it.
  - An agent's `persona:` accepts a string or an identifier.
- **Validator.**
  - The persona must exist, in the script or as a `PersonaLibrary` no-arg static method.
  - `role` is required.
  - `system_template` needs `Context.promptTemplates` (the registry's ids, or null when there is no
    registry), and the id must be in it.
- **Executor.** `basePrompt` builds the persona's prompt addition, adds `"\n\n" + system` when set, then
  the skills as today.

## 8. Trace (R8)

- **API.**
  - `execution/TraceEvent(String type, String agent, String step, String text, Map<String, Object> data,
    Instant at)`.
  - `execution/TraceListener`, a functional interface.
  - `HarnessExecutor.addTraceListener`, and a package-private `trace(type, agent, text, data)` that
    returns immediately when there are no listeners.
- **Agent events.** Each agent built gets an `AgentEventListener` adapter. It reads the current step from
  the thread-local and emits `thought`, `action`, `observation` and `budget`. It is added only when there
  are listeners at `initialize()`; listeners must therefore be added before `initialize()`, which is
  documented.
- **Executor events.** The executor emits:
  - `delegate_start` (the task, cut short);
  - `delegate_end` (the answer, cut short, plus usage);
  - `delegate_replayed`;
  - `approval` (asked, granted, rejected);
  - `memory`;
  - `guard`;
  - `suspended`.
- **CLI.**
  - `WeaveCLI run` gains `--trace[=text|json]` (arity 0..1, fallback `text`), stored in `RunSpec.trace`.
  - `Runs` adds a `ConsoleTrace` that writes to `WeaveEnv.err`: text as
    `HH:mm:ss [step] Agent  💭 …`, or JSON lines through Jackson.

## Error handling summary

| Situation | When | Result |
|---|---|---|
| Bad syntax or keys | parse | parse error with line |
| Unknown persona, template, provider, model, missing env, bad memory numbers, bad voice prefix, corrupt graph file, unreachable remote skill | load | `LoomLoadException` listing all |
| Listen/speak failure, guard block | delegate | step failure: `on_failure`/`_error`, else the run fails |
| Tool misuse (missing args, path outside) | tool call | error text returned to the agent |

## Testing approach

- Sarvam and remote registries use MockWebServer, through `base_url` or `SARVAM_BASE_URL` taken from an
  injected env.
- Embeddings use the deterministic `HashingEmbeddingProvider` from the P1 tests.
- Chat uses scripted mock `LLMClient`s that record the requests they receive, to assert on what reached
  the model.
