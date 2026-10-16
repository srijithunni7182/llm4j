# Requirements Document

## Introduction

The Spring Boot starter lets a Spring application get llm4j as beans, configured from `application.yml`, without making
llm4j depend on Spring. Version 1 (on the branch) wires one `LLMClient`, the core `ToolRegistry`, a `ReActAgent.Builder`
and a basic `LoomRuntime`. A review found that v1 leaves most capabilities out and, for Loom, cannot resume a paused
run, pass per-user credentials, or shut an executor down.

**Scope rule: the starter is wiring.** Properties become constructor calls on public llm4j API. Anything that is
behaviour, such as running, pausing, resuming and answering workflow runs, belongs in the library (`ai-agent4j-loom`),
where a plain-Java application can use it too. Today that behaviour lives in package-private CLI classes (`Runs`,
`WeaveTriggerTarget`, `RunSpec`, `WeaveEnv`), so Requirement 8 adds it to Loom as a public API and the starter wires that.

**Deferred (not in this spec; each can be added later as a separate add-on without changing what is here):** a run-control
web API, Actuator `loom` endpoint, Micrometer metrics and health indicators, a test-support module, several scripts per
application, named agents (`llm4j.agents.<name>`), MCP servers, semantic memory, personas, fairness monitor, chat channels
(Telegram, command), the trigger HTTP endpoint, and `RAGAgent`/chunking. `docs/` states this list.

### Zero-magic principles (apply to every requirement)

- **ZM1. Configured, not guessed.** A bean exists only when its property is set, or when it is inert (an empty registry).
- **ZM2. Yours wins.** Every bean is `@ConditionalOnMissingBean`.
- **ZM3. Plain Java underneath.** Each bean is built from public llm4j API only; the README shows the equivalent plain-Java lines.
- **ZM4. No surprise side effects at start-up.** Start-up calls no model and sends nothing. The only things that start
  work are the properties documented as doing so (`llm4j.loom.triggers.enabled`, `llm4j.observe.enabled`).
- **ZM5. Fail fast, name the fix.** A misconfiguration fails start-up with a message that names the property to set.
- **ZM6. Secure by default.** Secrets never appear in logs, messages or Actuator output. Safety checks (`weave audit`,
  tool allow-lists) are never weakened by the starter.
- **ZM7. Spring never leaks.** No llm4j library module gains a Spring dependency.

---

## Glossary

- **Starter**: the one published artifact `llm4j-spring-boot-starter`.
- **Named_Client**: a provider client under `llm4j.clients.<name>`; **Primary_Client**: the `LLMClient` bean (a named client or a router, with decorators).
- **Decorator**: a client that wraps another (`BudgetedLLMClient`, `MaskingLLMClient`).
- **Loom_Host**: the new public class `io.github.llm4j.loom.host.LoomHost` in `ai-agent4j-loom`: assembles executors and runs, suspends, resumes and answers workflow runs against a persistent store.
- **Run_Store**: where a Loom_Host records, per run id, the script, workflow, inputs and status of a run. File or JDBC.
- **Durable_Mode**: a Loom_Host with a Run_Store: runs have a journal and can suspend and resume.
- **Run_Outcome**: how a blocking run ended: completed, suspended (reason, resume time, step, prompt), stopped; a failure is thrown.

---

## Requirements

### Requirement 1: Packaging and guards

**User Story:** As a Spring developer, I want to add one dependency and get only what I use.

#### Acceptance Criteria

1. THE Starter SHALL be one artifact, `llm4j-spring-boot-starter`, depending on `ai-agent4j` and `spring-boot-autoconfigure`;
   `ai-agent4j-loom`, `ai-agent4j-tools`, `ai-agent4j-addons`, `loom-inspect`, Spring JDBC and `spring-boot-configuration-processor`
   SHALL be `optional`. Its Spring Boot baseline is 3.2 on Java 17 and 21, and it SHALL NOT import Spring Boot's BOM.
2. Each capability SHALL be its own `@AutoConfiguration` class, excludable on its own, guarded by `@ConditionalOnClass`.
3. WHEN a property that needs an optional module is set and the module is absent, start-up SHALL fail naming the
   artifact to add (`llm4j.loom.*` → `ai-agent4j-loom`; `llm4j.tools.*.kind` → `ai-agent4j-tools`;
   `llm4j.rag.*` onnx/djl/pgvector/pinecone → `ai-agent4j-addons`; `llm4j.observe.*` → `loom-inspect`;
   `persistence=jdbc` → a `DataSource` and `spring-jdbc`).
4. THE v1 properties (`llm4j.provider|model|api-key|base-url|timeout|connect-timeout`, `llm4j.loom.script|loot`) SHALL keep
   working as shorthands for the client `default` and the Loom script. Setting a shorthand and its long form SHALL fail.

### Requirement 2: Secret stores

**User Story:** As an operator, I want keys to come from llm4j's secret stores.

#### Acceptance Criteria

1. `llm4j.secrets.store` = `env` SHALL create `EnvSecretStore` over the Spring `Environment`; `encrypted-file` SHALL open
   `EncryptedFileSecretStore` at `llm4j.secrets.file` with the master key from exactly one of `master-key-env` or
   `master-key-file` (never a literal passphrase); `chained` SHALL build `ChainedSecretStore.of(...)` over `llm4j.secrets.chain`.
2. A user `SecretStore` bean SHALL replace these and SHALL NOT be closed by the Starter; one the Starter created SHALL be closed on shutdown.
3. Every credential property SHALL have a `-secret` twin (`api-key-secret`, `password-secret`) naming a store entry, passed as
   `SecretRef.of(store, name)` so the value is fetched per request and keeps its host binding. Setting both forms SHALL fail
   naming both; setting `-secret` with no store SHALL fail naming `llm4j.secrets.store`.
4. Secret values SHALL never appear in logs, exception messages, `toString()` or Actuator `env`/`configprops` output; literal
   credential properties SHALL be registered for sanitizing.

### Requirement 3: Clients, routing and retries

**User Story:** As a developer, I want several models by name, with failover and tuned retries.

#### Acceptance Criteria

1. `llm4j.clients.<name>` SHALL accept `provider` (gemini, anthropic, ollama, sarvam), `model`, `api-key`/`api-key-secret`,
   `base-url`, `timeout`, `connect-timeout`, `tier` (`FAST_CHEAP`, `BALANCED`, `REASONING`), `effort` (anthropic) and `retry.*`
   (`enabled`, `max-retries`, `initial-backoff`, `max-backoff`, `retryable-status-codes`, `inline-wait-threshold`,
   `fallback-delay`, `max-fallback-delay`, `daily-reset-zone`) mapped to `RetryPolicy.builder()`; unset fields keep the defaults.
2. All named clients SHALL be reachable through one `LLMClients` bean (`get(name)`, `names()`); only the Primary_Client SHALL be an
   `LLMClient` bean, so injection is never ambiguous.
3. THE Primary_Client SHALL be the client named by `llm4j.primary` (default `default`), or when `llm4j.routing.strategy` is
   `fallback` or `cost-aware`, a `RoutingLLMClient` over `llm4j.routing.clients` using their tiers.
4. WHEN no client is configured, no `LLMClient` SHALL be created. A missing key SHALL fail naming `llm4j.clients.<name>.api-key-secret`;
   an unknown provider SHALL fail listing the four. Building a client SHALL NOT contact the provider.
5. A `Clock` and a `Sleeper` bean, when present, SHALL be used by the retry policy. `LLMClientCustomizer` beans SHALL wrap the
   Primary_Client last, in `@Order`.

### Requirement 4: Budgets, privacy and audit

**User Story:** As a team lead, I want a hard spend cap, PII masking and one audit log.

#### Acceptance Criteria

1. `llm4j.pricing.file` or `llm4j.pricing.models.<model>.input-per-million|output-per-million` SHALL build a `PriceTable`; a user bean wins.
2. WHEN `llm4j.budget.tokens|calls|cost` is set, THE Primary_Client SHALL be wrapped in `BudgetedLLMClient` with a `Budget`
   (`warn-at`, `window` minute|hour|day, `per-call-max-tokens`); the `Budget` SHALL be a bean and `BudgetListener` beans added to it.
   `cost` with no `PriceTable` SHALL fail start-up.
3. WHEN `llm4j.privacy.mask-pii=true`, THE Primary_Client SHALL be wrapped in `MaskingLLMClient` (detector bean or `RegexPIIDetector`,
   `llm4j.privacy.types`) as the OUTERMOST decorator, so neither the budget estimator nor a provider sees unmasked text.
4. WHEN `llm4j.audit.file` is set, THE Starter SHALL create `FileAuditLogger(path, max-file-size, max-files)`; an `AuditLogger` bean
   (ours or the user's) SHALL be set on every agent builder and every Loom executor.

### Requirement 5: Agents

**User Story:** As a developer, I want agents built from my beans, with context limits and conversation memory.

#### Acceptance Criteria

1. THE `ReActAgent.Builder` bean SHALL stay prototype-scoped and SHALL be created with the Primary_Client (or `llm4j.agent.client`), the
   registry's tools (or those named in `llm4j.agent.tools`), `llm4j.agent.*` (`system-prompt`|`system-prompt-id`, `instructions`,
   `max-iterations`, `temperature`, `tool-calling`, `streaming`, `reasoning`, `validate-tool-arguments`, `max-tokens-per-call`,
   `on-budget-exhausted`) and, when present as beans: `AgentEventListener`, `RunHook`, `ToolHook` (in `@Order`), `ApprovalCallback`,
   `EffectClassifier`, `AuditLogger`, `Budget`, `PriceTable`, `TokenEstimator`, `Clock`, `PromptRegistry`.
   An unknown tool name in `llm4j.agent.tools` SHALL fail listing the registered names. No singleton `ReActAgent` bean SHALL exist.
2. `llm4j.agent.context.window`, `windows-file`, `compaction` (a name accepted by `ContextCompactors.byName`) with
   `compaction-options` and `compaction-client` (for `summarize`) SHALL configure the agent's context policy; a `ContextPolicy` or
   `ContextCompactor` bean SHALL win.
3. `llm4j.memory.conversations` = `memory` or `file` (+ `dir`, `max-history`) SHALL create a `ConversationStore`; a user bean wins.
   THE Starter SHALL NOT create an agent per session; applications build `ConversationHistory(sessionId, store, max)` and pass it to the builder.
4. `llm4j.prompts.location` SHALL create a `PromptRegistry` (`MarkdownFolderPromptRegistry` for a folder, `FileSystemPromptRegistry` for YAML),
   accepting `file:` and `classpath:`. `llm4j.skills.locations` SHALL load skills via the file-system or classpath loader; remote URLs
   SHALL need `llm4j.skills.allow-remote=true`.
5. `ReActAgentBuilderCustomizer` beans SHALL be applied last, in `@Order`.

### Requirement 6: Tools and tasks

**User Story:** As a developer, I want guarded tools and my services as tasks, from configuration.

#### Acceptance Criteria

1. `llm4j.tools.<name>.kind` (`webhook`, `email`, `http`, `file`, `shell`, `sql`, or a `ToolKind` bean's name) with `options.*` SHALL create the
   tool via `kind.check(options, baseDir)` then `create(...)`; a check failure SHALL fail start-up with its message and the property path.
   An option ending `-secret` SHALL be resolved from the secret store at creation. An `EffectContext` bean, when present, SHALL be passed.
2. `llm4j.tools.builtin` SHALL register the named built-ins (`calculator`, `current-time`, `date-time`, `echo`, `duckduckgo`, `serpapi`);
   none is registered by default; an unknown name SHALL fail listing the valid names.
3. `Tool` beans, built-ins and kinds SHALL all go into the core `ToolRegistry` and into Loom's registry. Two tools with the same name SHALL
   fail start-up naming both sources.
4. THE Starter SHALL build a `TaskRegistry` from every `Task` bean plus `TaskRegistry.discovered()` (off with `llm4j.tasks.discover=false`),
   fail on duplicate names, and set it on every Loom executor.

### Requirement 7: RAG beans

**User Story:** As a developer, I want a vector store and embeddings from configuration.

#### Acceptance Criteria

1. `llm4j.rag.embedding` (`gemini`, `onnx`, `djl`) SHALL create an `EmbeddingProvider`; `llm4j.rag.store` (`memory`, `file`, `pgvector`,
   `pinecone`) SHALL create a `VectorStore`.
2. `pgvector` SHALL default its URL, user and password to `spring.datasource.*` and use `PGVectorStore.withSecret` when `password-secret`
   is set; `pinecone` SHALL use `PineconeVectorStore.withSecret`.
3. A user `EmbeddingFactory` bean SHALL be set on Loom executors for `knowledge` blocks.

### Requirement 8: Loom_Host (library, ai-agent4j-loom)

**User Story:** As any Java developer (Spring or not), I want to run Loom workflows from my application, pausing and resuming them
across restarts, without copying the CLI's code.

#### Acceptance Criteria

1. THE library SHALL provide `io.github.llm4j.loom.host.LoomHost` with a builder taking: script, loom tools, model factory, and optionally
   base dir, script reference, secret store, environment lookup, clock, sleeper, tool reach, task registry, prompt registry, price table,
   budget overrides, context windows, embedding factory, memory engine, audit logger, human interface, autonomy (ledger, levels),
   an observe session, an executor customizer, a persistence choice (`none`, `file(dir)`, `jdbc(DataSource)`) and a `java.util.concurrent.Executor`.
2. `LoomHost` SHALL provide `run(RunRequest)` (blocking, returns a Run_Outcome, throws only for failure with the run id),
   `start(RunRequest)` (returns a `LoomRun`, runs on the given executor, shuts the executor down in every outcome), `resume(runId, invoker)`,
   `resumeAsync`, `answer(runId, stepId, text, by)`, `runs()`, `run(id)` and `close()`. A `RunRequest` carries workflow, inputs, optional
   run id, label, origin and `InvokerCredentials`.
3. WITH persistence `file`, runs SHALL use the layout `weave` uses (`<dir>/runs/<id>/journal.json` and the run's spec file), so
   `weave resume`, `weave timeline` and `weave rewind` work on a host run and a host can resume a `weave` run. WITH `jdbc` it SHALL use
   `JdbcRunJournal`, `JdbcTriggerStore`, `JdbcLedger`, `JdbcLevelStore` and two new tables (`llm4j_loom_runs`, `llm4j_loom_answers`), all created by `LoomHost.createSchema(DataSource)`.
4. `answer` SHALL record a person's answer for a `human_prompt`/`approve:` step in an answer book (the executor journals it when the step replays, as it does for the CLI's channel answers), audit
   it, and make the run resumable at once; an answer for a step that is not waiting, or a repeat, SHALL be refused with the reason; choices SHALL be matched
   with `Answers.match`.
5. IN Durable_Mode with no `HumanInterface`, THE host SHALL use a suspending interface (the run suspends at the question) and SHALL notify a
   `QuestionListener` with run id, step id, prompt and choices.
6. THE host SHALL provide `triggerTarget()` (resume a run, start a workflow, give an agent a task, as `weave` does) and `startTriggers(pollInterval, owner, staleAfter)` /
   `stopTriggers()` around `TriggerRunner`, and SHALL write the script's `schedule` triggers to the store (idempotently) when triggers start.
7. THE host SHALL never store invoker credentials, SHALL refuse to resume a run that needs credentials when none are given (outcome "needs credentials"), and SHALL
   fail construction in Durable_Mode `none` for a script that can suspend (see Requirement 9.4) unless told `allowNonDurable`.
8. `LoomLoader` SHALL gain `load(location, ScriptSource)` and `LootLoader` Reader-based overloads so a script and its imports can be read from a
   jar; existing signatures SHALL keep their behaviour.
9. Everything in this requirement SHALL work without Spring and SHALL be covered by tests in `ai-agent4j-loom`, and listed in `docs/API_COMPATIBILITY.md`.

### Requirement 9: Loom in Spring

**User Story:** As a developer, I want my workflow configured and started like any other bean.

#### Acceptance Criteria

1. `llm4j.loom.script` (+ `loot`) SHALL load the script (`classpath:` or `file:`, including imports and `.loot` from inside a jar) and create a
   `LoomRuntime` bean, a thin wrapper over a `LoomHost`: `run`, `start`, `resume`, `answer`, `runs`. Loom tools SHALL be the Requirement 6 tools plus
   the `.loot` file.
2. THE `LoomRuntime` SHALL take its `LLMClientFactory` from a bean, else `DefaultLLMClientFactory` over the secret store, then the Spring `Environment`.
3. At start-up THE Starter SHALL validate the script (`ScriptValidator`, with registered tools, tasks, prompts and the environment) and fail listing every error;
   `llm4j.loom.validate=lenient` SHALL report them as warnings.
4. At start-up THE Starter SHALL run `SecurityAudit.audit(script, name, lootReach)` and fail with the Markdown report when it `failsAt(llm4j.loom.audit.fail-on)`
   (default `high`); `none` SHALL log each finding at WARN and the gate being off once. THE Starter SHALL offer no property that silences one rule (acceptance stays in the script).
5. A script that can suspend (`human_prompt`, `approve:`, `sleep`, `rate_limits`, windowed budget, `decision`/`ladder`) with persistence `none` SHALL fail
   start-up naming `llm4j.loom.persistence`, unless `llm4j.loom.allow-non-durable=true` (a suspension then fails the run, saying it cannot resume). A `decision`/`ladder` with `none` SHALL always fail.
6. `llm4j.loom.executor` (`virtual-threads` on Java 21, `platform`, `application`) and `max-concurrent-runs` SHALL set the host's executor and a bound (beyond it `start`
   throws a typed `TooManyRuns`). On shutdown THE Starter SHALL wrap up running runs, wait `llm4j.loom.shutdown-grace`, then suspend (durable) or cancel them.
7. `LoomExecutorCustomizer` beans SHALL run on every executor after the Starter's setup and before `initialize()`.
8. `llm4j.loom.budget.*` SHALL set the executor budget overrides, `llm4j.loom.context.windows-files` the context windows (over the seeded catalogue), and
   the `PriceTable`, `PromptRegistry`, `AuditLogger`, `EmbeddingFactory`, `Clock`, `Sleeper` beans SHALL be set on the host.

### Requirement 10: Persistence, resume and people

**User Story:** As an operator, I want a run that waits for a person, a timer or a rate limit to resume after a restart, without repeating paid-for or side-effecting work.

#### Acceptance Criteria

1. `llm4j.loom.persistence` = `none` (default), `file` (+ `dir`) or `jdbc` (+ optional `datasource` bean name) SHALL set the host's persistence.
2. `llm4j.loom.persistence.initialize-schema` (`always`, `embedded` default, `never`) SHALL call `LoomHost.createSchema`; with `never` and a missing table, start-up SHALL fail naming the table.
3. WITH Durable_Mode, a suspended run SHALL resume via `resume`/`answer` and finished steps SHALL replay from the journal (no repeated model call, no repeated side effect), including after the application restarts between suspension and resume.
4. `HumanQuestionEvent` SHALL be published as a Spring `ApplicationEvent` for each question, and `QuestionListener` beans SHALL be told; a user `HumanInterface` bean SHALL replace the suspending one.
5. A run needing invoker credentials SHALL receive them per `run`/`start`/`resume` call (never stored).

### Requirement 11: Triggers

**User Story:** As an operator, I want `schedule` blocks and wake-ups fired by my application.

#### Acceptance Criteria

1. WHEN `llm4j.loom.triggers.enabled=true` (needs Durable_Mode), THE Starter SHALL run the host's trigger runner as a `SmartLifecycle` (`poll-interval`, `owner` default host:pid,
   `stale-after`), start it after validation and stop it before the executor shuts down. Several replicas on one JDBC store SHALL fire each trigger once.
2. `TriggerRunner.onFired` events SHALL be published as Spring `ApplicationEvent`s.

### Requirement 12: Autonomy and observation

**User Story:** As an operator, I want earned-autonomy records kept with my data, and the live run page available.

#### Acceptance Criteria

1. A script using `decision`/`ladder` SHALL get `setAutonomy(ledger, levels)` from `FileLedger`/`FileLevelStore` or `JdbcLedger`/`JdbcLevelStore` following the persistence; user `Ledger`/`LevelStore` beans win.
2. WHEN loom-inspect is on the class path and `llm4j.observe.enabled=true`, THE Starter SHALL open one session via `Observe.open` (`port`, `bind` default `127.0.0.1`, `detail`, `name`, `linger`, `rules-file`, `fabric.*`),
   attach every executor before `initialize()`, and close it on shutdown. A non-loopback `bind` SHALL fail unless `LOOM_INSPECT_ADMIN_PASSWORD` is in the secret store.

### Requirement 13: Metadata and documentation

#### Acceptance Criteria

1. Every property SHALL have a description and default in the configuration metadata, with value hints for enumerations.
2. THE README SHALL show, per capability, the YAML, the bean it creates and the plain-Java equivalent, and SHALL list the deferred capabilities. `docs/API_COMPATIBILITY.md` SHALL list the new public library types.

### Requirement 14: Quality

#### Acceptance Criteria

1. Default-build tests SHALL be hermetic (no network, no real provider; JDBC on H2); live tests SHALL be tagged `live`.
2. JaCoCo SHALL require line ≥ 0.85 and branch ≥ 0.80 for the Starter and for `io.github.llm4j.loom.host`; branch ≥ 0.90 for the secret wiring, the audit gate, the durability guard and the module guard.
3. CI SHALL build the Starter on JDK 17 and 21 against the lowest and the latest supported Spring Boot 3.x. Existing tests of every library module SHALL pass unchanged.
