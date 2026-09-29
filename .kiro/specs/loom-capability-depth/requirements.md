# Requirements Document

## Introduction

This spec covers priorities P2 and P3 of the [capability-parity gap analysis](../loom-capability-parity/gap-analysis.md),
following P0 and P1 (tools, knowledge, approvals, load-time validation):

- **P2**
  - Agent memory: conversations that persist across runs, and long-term facts.
  - Voice and Indic language services, as tools and as an agent property.
  - More model providers (Sarvam chat), and providers declared in the script with their own base URL and
    key.
- **P3**
  - Knowledge graphs as a tool.
  - Agent guards: PII masking or blocking, and bias checks.
  - Remote skills and skill discovery.
  - Custom personas in the script.
  - A live trace of a run in the CLI (`weave run --trace`).

The standing rules from P0 and P1 still apply:

- **Nothing is silently ignored.** Anything the runtime can't honour is a load error that names the line.
- **Scripts stay symbolic.** Capabilities are agent properties or tools, not new statements.
- **Logic lives in the framework.** When ai-agent4j lacks a piece, it is added to ai-agent4j.
- **Secrets only come from the environment.**
- **Existing valid scripts keep working.**

Still out of scope: OpenAI and Anthropic chat providers (a library gap, not a Loom gap), vector stores
other than memory and file for agent facts, and voice streaming.

---

## Glossary

| Term | Meaning |
|---|---|
| **Conversation memory** | An agent's earlier messages (task, answer) for one *session*, kept in a store and put in front of its next task. |
| **Session** | Whose conversation it is. An expression resolved per delegate (e.g. `"{user_id}"`); by default the agent's name. |
| **Facts** | Long-term semantic memory: sentences the agent chose to save (with the `save_memory_fact` tool), recalled by meaning before a task. |
| **Sarvam** | The provider ai-agent4j uses for chat, translation, transliteration, language detection, text-to-speech and speech-to-text. |
| **Provider declaration** | A top-level `provider Name { use: gemini\|ollama\|sarvam … }` block. It lets `model: "Name/<model>"` reach a specific endpoint with its own key. |
| **Guard** | An agent's `guard { pii: … bias: … }` property, checked on every delegate to that agent. |
| **Trace** | Live events from a run: thoughts, tool calls, observations, spend, approvals, delegates. |

---

## Requirements

### Requirement 1 (P2): Agent Memory

**User Story:** As a script author, I want an assistant that remembers earlier conversations with a user
and the facts it learned about them, across runs.

#### Acceptance Criteria

1. **Syntax.** The parser SHALL accept on an agent (with or without `:` after `memory`):

   ```loom
   memory {
       conversation: "chats/" | memory   // where conversations are kept
       limit: 20                         // messages put in the prompt (default 20)
       session: "{user_id}"              // whose conversation (default: the agent's name)
       facts: "memory/facts.json" | memory   // long-term facts (optional)
       embedding: "<model>"              // required with facts
       recall: 5                         // facts recalled per task (default 5)
       min_similarity: 0.7               // (default 0.7)
   }
   ```

   At least one of `conversation` or `facts` SHALL be given.
2. **Old keys are rejected.** `type:` and `path:` SHALL be load errors that give the new form. Numbers
   SHALL be validated:
   - `limit` and `recall` are positive whole numbers;
   - `min_similarity` is between 0 and 1.
3. **Conversation.** On each delegate to the agent:
   - the session SHALL be resolved from the workflow's variables;
   - the stored conversation for (agent, session) SHALL be put in front of the task;
   - after an answer, the task and answer SHALL be appended to it.

   A directory store SHALL survive the process. `memory` keeps it for the executor's lifetime. Different
   sessions SHALL NOT see each other's messages.
4. **Facts.**
   - The agent SHALL get the tool `save_memory_fact`.
   - Before a task, the top `recall` facts for the session with similarity ≥ `min_similarity` SHALL be put
     in front of it.
   - Facts SHALL be embedded with the embedding factory used for knowledge bases.
   - A file store SHALL survive the process. Facts SHALL be separated by session.
5. **Replay.** A replayed step SHALL NOT read or write memory.
6. **Validation.**
   - Embedding problems SHALL be load errors, as for knowledge bases.
   - A `session` expression SHALL be resolved per delegate. An unresolved `{name}` stays literal, as in
     payloads.
7. **Not budgeted, but audited.** Embedding calls are not charged to budgets. The audit log SHALL record
   `memory_recalled` (agent, session, messages, facts).

---

### Requirement 2 (P2): Voice and Indic Language Services

**User Story:** As a script author, I want agents that translate, detect languages, and speak or listen,
without Java.

#### Acceptance Criteria

1. **Built-in tools.** The built-in tool names `translate`, `transliterate`, `detect_language`, `speak`
   and `transcribe` SHALL be usable in `tools:` without a declaration. They use Sarvam, with the key from
   `SARVAM_API_KEY`. If that variable is missing, it SHALL be a load error naming the tool and the
   variable.
2. **Declared forms and options.** Each kind SHALL also be declarable (`tool Hindi { use: translate … }`)
   with these options:

   | Kind | Options |
   |---|---|
   | all kinds | `api_key` (secret; default `env.SARVAM_API_KEY`), `base_url?` |
   | `translate` | `target?`, `source?` |
   | `transliterate` | `target?`, `source?` |
   | `speak` | `language?`, `voice?`, `model?`, `out?` (default `audio`) |
   | `transcribe` | `language?`, `model?`, `translate_to_english?` |

3. **Tool behaviour.**

   | Tool | Arguments | Returns |
   |---|---|---|
   | `translate` | `text`, `target?`, `source?` | the translation |
   | `transliterate` | `text`, `target?`, `source?` | the transliteration |
   | `detect_language` | `text` | the language code |
   | `speak` | `text`, `language?` | writes a WAV file under `out` (relative to the script's directory) and returns its path |
   | `transcribe` | `path` | the transcript |

   A tool call with a missing argument SHALL return an error text, not throw.
4. **Paths stay inside the script's directory.** `transcribe` SHALL only read files, and `speak` SHALL only
   write files, inside the script's directory. A path outside it SHALL be refused.
5. **Agent `voice` property.** The parser SHALL accept on an agent:

   ```loom
   voice {
       listen: "sarvam/<model>"
       speak: "sarvam/<model>"
       language: "hi-IN"
       voice: "<speaker>"
       out: "audio"
   }
   ```

   - **`listen`.** When a delegate's resolved task is the path of an audio file (`.wav`, `.mp3`, `.ogg`,
     `.flac`, `.m4a`, `.aac`, `.webm`) inside the script's directory, it SHALL be transcribed, and the
     transcript SHALL be the task.
   - **`speak`.** After a successful delegate, the answer SHALL be synthesised to `<out>/<file>.wav`, and
     the file's path stored in the variable `<result>_audio`. The path SHALL be journaled, so a replay
     restores it without synthesising again.
   - **Failure.** A failure to listen or speak SHALL fail the step: `on_failure` runs with `_error`, as
     for any failed delegate.
   - **Validation.** A provider prefix other than `sarvam/` SHALL be a load error, as SHALL a missing
     `SARVAM_API_KEY`.
6. **Library fixes.**
   - Language detection SHALL call Sarvam's `/text-lid` endpoint.
   - `TranscriptionRequest` SHALL carry an optional model.
   - `ReActAgent.toBuilder()` SHALL keep the TTS language and model.
   - Sarvam chat SHALL use the configured default model when a request names none.

---

### Requirement 3 (P2): Model Providers

**User Story:** As a script author, I want to use Sarvam chat models, and to point an agent at a specific
Ollama, Gemini or Sarvam endpoint with its own key.

#### Acceptance Criteria

1. **Sarvam models.** `model: "sarvam/<model>"` (e.g. `sarvam/sarvam-m`) SHALL use Sarvam chat with
   `SARVAM_API_KEY`. `SARVAM_BASE_URL` MAY override the endpoint.
2. **Provider declarations.** The parser SHALL accept top-level:

   ```loom
   provider Name {
       use: gemini | ollama | sarvam
       base_url: "…"
       api_key: env.X
   }
   ```

   - `model: "Name/<model>"` in an agent or a routing policy SHALL use that provider.
   - `api_key` is a secret: environment only.
   - `ollama` needs no key. `gemini` and `sarvam` need one.
3. **Validation.** These SHALL be load errors:
   - an unknown `use:`;
   - a provider named like a built-in prefix (`gemini`, `ollama`, `sarvam`);
   - a declaration given twice;
   - a missing key or variable.
4. **Unknown models.** A `model:` that no provider understands SHALL be a load error when the executor
   uses the default client factory. A host factory is trusted with its own names.
5. **Clients are replaceable.** Clients for declared providers SHALL be created through
   `LLMClientFactory`, so hosts and tests can replace them.

---

### Requirement 4 (P3): Knowledge Graphs

**User Story:** As a script author, I want agents to record and query entities and relations in a graph
that persists.

#### Acceptance Criteria

1. **Declaration.** `tool Graph { use: knowledge_graph store: "graphs/x.json" | memory read_only: true? }`
   SHALL give agents one tool, named as declared. Its `action` argument is:
   - `add`: `subject`, `predicate`, `object`, as for `GraphExtractionTool`;
   - `query`: `entityId`, or `entityType`, or `subjectId` with an optional `predicateType`, as for
     `GraphQueryTool`.
2. **Read-only graphs.** With `read_only: true`, `add` SHALL be refused.
3. **File stores.**
   - A file store SHALL be loaded at load time and saved after every change.
   - A corrupt file SHALL be a load error.
   - Declarations with the same store file SHALL share one graph.
4. **Library.** ai-agent4j SHALL gain `FileGraphStore`, a JSON-persisted `KnowledgeGraph`.

---

### Requirement 5 (P3): Agent Guards (PII and Bias)

**User Story:** As a script author, I want to keep personal data away from models and out of results, and
to catch biased answers.

#### Acceptance Criteria

1. **Syntax.** The parser SHALL accept on an agent:

   ```loom
   guard {
       pii: mask | block | warn
       bias: warn | block
       bias_model: "<model>"
   }
   ```

   - At least one of `pii` or `bias` SHALL be given.
   - `bias_model` requires `bias`.
   - Unknown keys or values SHALL be load errors.
2. **`pii: mask`.**
   - Every message the agent sends to its model SHALL have PII replaced by placeholders. This covers the
     task, context, memory and tool observations.
   - The answer SHALL be masked before it is stored.
   - The audit log SHALL record `pii_masked` with counts by type.
3. **`pii: block`.**
   - If the task contains PII, the step SHALL fail before any model call.
   - If the answer contains PII, the step SHALL fail and the answer SHALL NOT be stored.
   - In both cases `_error` names the PII types, not the values. The audit log records `pii_blocked`.
4. **`pii: warn`.** PII found in the task or answer SHALL be recorded as `pii_detected` (types and where),
   and the step continues.
5. **Bias checks.**
   - The answer SHALL be checked by a bias monitor: rule-based by default, or an LLM judge when
     `bias_model` is set.
   - A judge's calls SHALL be charged to budgets like the agent's.
   - The audit log SHALL record findings as `bias_detected` (type, severity, explanation).
   - With `bias: block`, an intervention-worthy finding (HIGH or CRITICAL) SHALL fail the step, and the
     answer SHALL NOT be stored.
6. **Replay.** A replayed step SHALL NOT be re-checked.
7. **Library.** ai-agent4j SHALL gain:
   - `MaskingLLMClient` (masks every message);
   - `RuleBasedBiasMonitor`;
   - `LLMBiasMonitor`.

---

### Requirement 6 (P3): Remote Skills and Discovery

**User Story:** As a script author, I want to use skills published at a URL, and let an agent find skills
in a registry.

#### Acceptance Criteria

1. **Remote skills.** `skills: ["https://…/skill.md"]` SHALL load the skill over HTTPS at load time.
   - `http://` SHALL be accepted only for `localhost` and `127.0.0.1`.
   - A skill that can't be fetched SHALL be a load error.
   - `weave check` fetches remote skills to verify them.
2. **Discovery.** `tool Skills { use: skill_registry url: "…" api_key?: env.X }` SHALL give the agent the
   skill-discovery tool (search, read), named as declared.

---

### Requirement 7 (P3): Custom Personas

**User Story:** As a script author, I want to define personas in the script and have them combine with
the agent's own prompt.

#### Acceptance Criteria

1. **Declaration.** The parser SHALL accept top-level:

   ```loom
   persona Name {
       role: "…"
       expertise: "…"
       tone: "…"
       description: "…"
       constraints: ["…"]
   }
   ```

   `role` is required.
2. **Lookup.** An agent's `persona:` (a string or a name) SHALL resolve to the script's persona first,
   then to a `PersonaLibrary` persona. If neither exists, it SHALL be a load error listing both kinds.
   (Today this is a warning and a silent fallback.)
3. **Persona and `system:` combine.** An agent with both SHALL get the persona followed by its `system:`
   prompt. (Today `system:` is dropped.)
4. **Templates.** A `system_template:` with no prompt registry, or naming a missing template, SHALL be a
   load error. (Today it silently falls back.)

---

### Requirement 8 (P3): Live Trace

**User Story:** As a script author, I want to watch what agents think and do while a run is in progress.

#### Acceptance Criteria

1. **Listener API.** `HarnessExecutor.addTraceListener(TraceListener)` SHALL receive `TraceEvent`s. Each
   carries its type, agent, step, text and data. The types are:
   - `delegate_start`, `delegate_end`, `delegate_replayed`;
   - `thought`, `action`, `observation`;
   - `budget`;
   - `approval`;
   - `memory`;
   - `guard`;
   - `suspended`.
2. **Human-readable trace.** `weave run --trace` SHALL print events live to stderr, one line each, with
   the agent and step. Long texts are cut to 300 characters.
3. **JSON trace.** `weave run --trace=json` SHALL print one JSON object per line instead.
4. **Zero cost when off.** A run without listeners SHALL behave and cost exactly as before.

---

### Requirement 9: Compatibility and Documentation

#### Acceptance Criteria

1. Every existing test SHALL still pass. Every `.loom` file in the repository SHALL still pass
   `weave check`.
2. Scripts using `memory { type path }` SHALL be migrated, if any remain.
3. The following SHALL document the new syntax, and the guide's examples SHALL be parse-checked in tests:
   - the LOOM_GUIDE;
   - the READMEs;
   - LOOM_PROMPT;
   - the VS Code grammar and hovers.
4. The guide's "Not supported yet" note SHALL be updated.
