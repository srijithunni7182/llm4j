# Requirements Document

## Introduction

The Spring Boot starter makes llm4j feel native to Spring applications without making llm4j depend on Spring. Version 1
(already on the branch) wires one `LLMClient`, the core `ToolRegistry`, a `ReActAgent.Builder` and a basic `LoomRuntime`.
A review against the library found that most of llm4j's important capabilities cannot be reached through it, and that
some of what it already wires is unsafe or incomplete for a real web application:

- a suspended Loom run (`human_prompt`, `sleep`, `approve:`, a rate limit, a windowed budget) can never be resumed;
- per-user credentials (`InvokerCredentials`) cannot be passed to a run;
- `LoomRuntime.start()` never shuts its executor down;
- the client's API key can only be a literal, bypassing the secret stores;
- a script packaged in a jar cannot `import` another script;
- budgets, PII masking, retries, audit logging, routing, context compaction, conversation memory, prompts, skills,
  tasks, hooks, MCP, RAG, the guarded tools, triggers and schedules, human channels, earned autonomy, `weave audit`,
  loom-inspect / Fabric, metrics and health, and eval4j test support are all missing.

This spec turns the starter into a complete, production-grade integration while keeping llm4j's **zero-magic** promise.

### Zero-magic principles (apply to every requirement)

- **ZM1. Configured, not guessed.** A bean exists only when its property is set, or when it is harmless and inert (an
  empty registry). Nothing is inferred from a model name, a class name or the presence of a file.
- **ZM2. Yours wins.** Every bean is `@ConditionalOnMissingBean`. Declaring your own replaces ours, with no other change.
- **ZM3. Plain Java underneath.** Every bean is built from public llm4j API only, and each one's Javadoc and the README
  show the equivalent plain-Java lines. The starter adds wiring, never behaviour. Where a capability needs new behaviour,
  it is added to the llm4j module it belongs to (usable without Spring), and the starter only wires it.
- **ZM4. No surprise side effects at start-up.** Start-up calls no model, sends nothing and starts no process, except what
  a property explicitly asks for (an MCP server, a trigger runner, an observe page). Each such property is documented as
  starting something.
- **ZM5. Fail fast, name the fix.** A misconfiguration fails start-up with a message that names the property to set.
- **ZM6. Secure by default.** Secrets are never logged, never exposed by Actuator, and should come from a secret store.
  Web endpoints that can act on runs are off by default and refuse to start unsecured. Safety checks (`weave audit`,
  tool allow-lists) are never weakened by the starter.
- **ZM7. Spring never leaks.** No llm4j library module gains a Spring dependency. Removing the starter leaves a working
  plain-Java application.

---

## Glossary

- **Starter**: the published artifacts `llm4j-spring-boot-autoconfigure`, `llm4j-spring-boot-starter`,
  `llm4j-spring-boot-starter-loom`, `llm4j-spring-boot-starter-test`.
- **Named_Client**: a provider client configured under `llm4j.clients.<name>`.
- **Primary_Client**: the single `LLMClient` bean: the default Named_Client, or a router over several, wrapped by the
  configured decorators.
- **Decorator**: a client that wraps another: `MaskingLLMClient`, `BudgetedLLMClient`.
- **Script_Runtime**: a `LoomRuntime` for one named script, under `llm4j.loom.scripts.<name>`.
- **Run_Store**: where the starter records, per run id, which script, workflow and inputs a run was started with, so it
  can be resumed later. File or JDBC.
- **Durable_Mode**: Loom persistence is `file` or `jdbc`: runs have a journal and can suspend and resume.
- **Run_Request**: what starts a run: workflow, inputs, optional run id, label, origin, invoker credentials.
- **Run_Outcome**: how a blocking run ended: completed, suspended (reason, resume time, step, prompt), stopped or failed,
  with the run id.

---

## Requirements

### Requirement 1: Module layout and dependencies

**User Story:** As a Spring developer, I want to add one dependency for what I use, so that I get those beans and
nothing I didn't ask for.

#### Acceptance Criteria

1. THE Starter SHALL be split the Spring Boot way:
   - `llm4j-spring-boot-autoconfigure`: all auto-configuration, every integration dependency `optional`;
   - `llm4j-spring-boot-starter`: a POM that brings the autoconfigure module, `ai-agent4j` and `spring-boot-starter`;
   - `llm4j-spring-boot-starter-loom`: adds `ai-agent4j-loom` and `ai-agent4j-tools`;
   - `llm4j-spring-boot-starter-test`: test scope, adds `eval4j` and `spring-boot-test`.
2. Optional integrations (addons, loom-inspect, Actuator, Micrometer, Spring MVC/WebFlux, JDBC, Spring Security) SHALL
   activate only when their classes are on the class path, and SHALL never be pulled in transitively.
3. THE Starter SHALL support Spring Boot 3.2 and later on Java 17 and 21, and SHALL not import Spring Boot's BOM into llm4j
   modules (Jackson stays on the llm4j version).
4. WHEN a property under `llm4j.loom.*` is set but `ai-agent4j-loom` is absent, start-up SHALL fail with a message that
   names `llm4j-spring-boot-starter-loom`, instead of silently creating no beans. The same holds for each optional
   integration (`llm4j.rag.store=pgvector` without addons, `llm4j.observe.*` without loom-inspect).
5. THE existing v1 properties (`llm4j.provider`, `llm4j.model`, `llm4j.api-key`, `llm4j.base-url`, `llm4j.timeout`,
   `llm4j.connect-timeout`, `llm4j.agent.*`, `llm4j.loom.script`, `llm4j.loom.loot`) SHALL keep working as shorthands for
   the client named `default` and the script named `default`, and SHALL be marked deprecated in the metadata only if a
   later release removes them.

### Requirement 2: Secret stores

**User Story:** As an operator, I want API keys and passwords to come from llm4j's secret stores, so that no key is held
as a plain string or bound to the wrong host.

#### Acceptance Criteria

1. WHEN `llm4j.secrets.store` is `env`, THE Starter SHALL create `EnvSecretStore` backed by the Spring `Environment`.
2. WHEN it is `encrypted-file`, THE Starter SHALL open `EncryptedFileSecretStore` at `llm4j.secrets.file`, with the master
   key from exactly one of `master-key-env`, `master-key-file` (never a literal passphrase property).
3. WHEN it is `chained`, THE Starter SHALL build `ChainedSecretStore.of(...)` over `llm4j.secrets.chain` (an ordered list of
   the kinds above).
4. A user `SecretStore` bean SHALL replace all of these, and SHALL be closed on context shutdown only if the Starter
   created it.
5. Every Starter property that holds a credential SHALL have a `*-secret` twin (`api-key-secret`, `password-secret`)
   naming an entry in the store. THE Starter SHALL pass it on as `SecretRef.of(store, name)`, so the value is fetched for
   each request and keeps its host binding.
6. WHEN both the literal and the `*-secret` form are set, start-up SHALL fail naming both.
7. Secret values SHALL never appear in logs, exception messages, `/actuator/env`, `/actuator/configprops` or `toString()`.
   Literal credential properties SHALL be registered as keys to sanitize.

### Requirement 3: Named clients and the primary client

**User Story:** As a developer, I want several models configured by name, so that I can use a cheap model for simple
work and a strong one for reasoning, and switch providers without code changes.

#### Acceptance Criteria

1. Each `llm4j.clients.<name>` SHALL accept `provider` (gemini, anthropic, ollama, sarvam), `model`, `api-key` or
   `api-key-secret`, `base-url`, `timeout`, `connect-timeout`, `retry.*`, `tier` (`FAST_CHEAP`, `BALANCED`, `REASONING`)
   and, for Anthropic, `effort`.
2. THE Starter SHALL expose every Named_Client through one `LLMClients` bean (`get(name)`, `names()`), not as several
   `LLMClient` beans, so that injecting `LLMClient` is never ambiguous.
3. THE Primary_Client SHALL be:
   - the client named by `llm4j.primary` (default `default`); or
   - WHEN `llm4j.routing.strategy` is `fallback` or `cost-aware`, a `RoutingLLMClient` over the listed
     `llm4j.routing.clients` (each with its tier), using `FallbackRoutingStrategy` or `CostAwareRoutingStrategy`.
4. WHEN no client is configured, THE Starter SHALL create no `LLMClient` (ZM1).
5. WHEN a provider needs a key and none is configured, start-up SHALL fail naming `llm4j.clients.<name>.api-key-secret`.
6. A provider client SHALL be built without contacting the provider (ZM4).
7. `LLMClientCustomizer` beans SHALL be applied to the Primary_Client last, in `@Order`, so an application can wrap it.

### Requirement 4: Retries and rate limits

**User Story:** As an operator, I want to tune retries and rate-limit waits per client, so that short limits are absorbed
and long ones are handed to the workflow.

#### Acceptance Criteria

1. `llm4j.clients.<name>.retry` SHALL map to `RetryPolicy.builder()`: `max-retries`, `initial-backoff`, `max-backoff`,
   `retryable-status-codes`, `inline-wait-threshold`, `fallback-delay`, `max-fallback-delay`, `daily-reset-zone`;
   `retry.enabled=false` SHALL use `RetryPolicy.noRetry()`.
2. Unset fields SHALL keep `RetryPolicy.defaultPolicy()` values.
3. The `Clock` and `Sleeper` used SHALL be beans of those types when present (so tests control time).

### Requirement 5: Budgets and prices

**User Story:** As a team lead, I want a hard spend cap on every model call, so that a loop or an attack can't run up
an unbounded bill.

#### Acceptance Criteria

1. `llm4j.pricing.file` SHALL load a `PriceTable` (`PriceTable.load`), or `llm4j.pricing.models.<model>.input-per-million`
   / `output-per-million` SHALL build one with `PriceTable.of`. A user `PriceTable` bean wins.
2. WHEN `llm4j.budget` sets any of `tokens`, `calls`, `cost`, THE Primary_Client SHALL be wrapped in
   `BudgetedLLMClient` with a `Budget` built from `tokens`, `calls`, `cost`, `warn-at`, `window` (`minute`, `hour`, `day`)
   and `per-call-max-tokens`.
   `llm4j.budget.scope` (`primary`, default, or `each`) SHALL say whether one budget wraps the Primary_Client or each
   Named_Client gets its own.
3. WHEN `cost` is set and there is no `PriceTable`, start-up SHALL fail (a cost cap that cannot price a call is no cap).
4. THE `Budget` SHALL be a bean, so the application can read `spent()` and `remaining()` and add listeners;
   `BudgetListener` beans SHALL be added to it.
5. Loom executors SHALL get the same `PriceTable` (`setPriceTable`) and, when `llm4j.loom.budget.*` is set,
   `setBudgetOverrides`.

### Requirement 6: Privacy and fairness

**User Story:** As a compliance owner, I want PII masked before it reaches a provider and biased answers flagged, so that
policy is enforced in one place.

#### Acceptance Criteria

1. WHEN `llm4j.privacy.mask-pii=true`, THE Primary_Client SHALL be wrapped in `MaskingLLMClient` with the `PIIDetector`
   bean (default `RegexPIIDetector`) and the types in `llm4j.privacy.types` (default all). Its `onMasked` callback SHALL
   feed the `llm4j.privacy.masked` counter (by PII type, never the value) and a `PiiMaskedEvent` application event.
2. Masking SHALL be the outermost decorator, so neither the budget estimator nor any provider sees unmasked text.
3. WHEN `llm4j.fairness.monitor` is `rule-based` or `llm`, THE Starter SHALL create the `BiasMonitor` and set it on every
   agent builder; a user `BiasMonitor` bean wins.

### Requirement 7: Audit logging

**User Story:** As an auditor, I want one audit log for agents and workflows, so that every tool call, approval and
budget event is recorded.

#### Acceptance Criteria

1. WHEN `llm4j.audit.file` is set, THE Starter SHALL create `FileAuditLogger(path, max-file-size, max-files)`; a user
   `AuditLogger` bean wins; otherwise no audit logger is set (the library default stays).
2. THE `AuditLogger` bean SHALL be set on every agent builder and every Loom executor (`setAuditLogger`), unless a script's
   own `audit` block configures one.

### Requirement 8: Agents

**User Story:** As a developer, I want agents defined in configuration and fully wired from my beans, so that I write
only the business logic.

#### Acceptance Criteria

1. THE `ReActAgent.Builder` bean SHALL stay prototype-scoped, and SHALL additionally receive, when present as beans:
   `AgentEventListener`s, `RunHook`s, `ToolHook`s (in `@Order`), an `ApprovalCallback`, an `EffectClassifier`, the
   `AuditLogger`, the `BiasMonitor`, the `PromptRegistry`, the `SkillRegistry`'s skills listed in config, a `Budget`, the
   `PriceTable`, a `TokenEstimator` and the `Clock`.
2. `llm4j.agent.*` SHALL add: `client` (a Named_Client instead of the primary), `persona`, `system-prompt-id`,
   `instructions`, `tool-calling` (`native`, `text`, `auto`), `streaming`, `reasoning`, `validate-tool-arguments`,
   `max-tokens-per-call`, `on-budget-exhausted`, `tools` (names to include; default all), `skills`.
3. `llm4j.agents.<name>.*` SHALL define named agents with the same keys, each falling back to `llm4j.agent.*`.
   THE Starter SHALL expose them through an `Agents` bean: `builder(name)` returns a fresh builder and `create(name)`
   a fresh agent. A name that is not configured SHALL throw, listing the configured names.
4. `ReActAgentBuilderCustomizer` beans SHALL be applied to every builder the Starter makes, last, in `@Order`.
5. THE Starter SHALL never expose a singleton `ReActAgent` bean (an agent holds a conversation).

### Requirement 9: Context windows and compaction

**User Story:** As a developer, I want long-running agents to stay inside the model's context window, so that they don't
fail with "prompt too long".

#### Acceptance Criteria

1. `llm4j.agent.context.window` (tokens) SHALL set `contextWindow`, and `llm4j.agent.context.windows-file` SHALL load
   `ContextWindows` per model.
2. `llm4j.agent.context.compaction` SHALL be a name accepted by `ContextCompactors.byName` (`none`, `drop-oldest`,
   `trim-tool-results`, `summarize`, or a chain), with options from `compaction-options`. `summarize` SHALL use the
   Named_Client in `compaction-client` (default primary).
3. A `ContextPolicy` or `ContextCompactor` bean SHALL win over properties.
4. Loom executors SHALL get `setContextWindows(WindowLayers...)` built from `llm4j.loom.context.windows-files` over the
   seeded catalogue.

### Requirement 10: Conversation and semantic memory

**User Story:** As a developer of a chat feature, I want each user's conversation kept across requests and restarts,
so that a new agent per request still remembers the chat.

#### Acceptance Criteria

1. `llm4j.memory.conversations` (`memory`, `file`) SHALL create a `ConversationStore` (`InMemoryConversationStore`,
   `FileConversationStore(dir)`). A user `ConversationStore` bean wins (for example a JDBC one).
2. THE Starter SHALL provide `AgentSessions` with `agent(sessionId)` and `agent(name, sessionId)`: a fresh agent whose
   `ConversationHistory(sessionId, store, max-history)` is loaded from the store.
3. WHEN `llm4j.memory.semantic.*` is set, THE Starter SHALL create a `SemanticMemoryService` via
   `SemanticMemoryFactory.create`, set it on agent builders, and register its `MemoryManagementTool` only when
   `llm4j.memory.semantic.tool=true`.

### Requirement 11: Prompts, skills and personas

**User Story:** As a prompt engineer, I want prompts, skills and personas kept as files in my application, so that they
are versioned and reviewed like code.

#### Acceptance Criteria

1. `llm4j.prompts.location` SHALL create a `PromptRegistry`: `MarkdownFolderPromptRegistry` for a folder of `.md` files,
   `FileSystemPromptRegistry` for YAML. Locations SHALL accept `file:` and `classpath:`.
2. `llm4j.skills.locations` SHALL load skills through `FileSystemSkillLoader`, `ClasspathSkillLoader` or
   `RemoteSkillLoader`; remote URLs SHALL require `llm4j.skills.allow-remote=true`.
3. `llm4j.personas.location` SHALL load a `PersonaLibrary`.
4. Loom executors SHALL get the `PromptRegistry` (`setPromptRegistry`) unless the script sets its own prompts folder.

### Requirement 12: Tools

**User Story:** As a developer, I want guarded tools and MCP servers configured in YAML, so that agents can act safely
without hand-written wiring.

#### Acceptance Criteria

1. `llm4j.tools.<name>.kind` (`webhook`, `email`, `http`, `file`, `shell`, `sql`) with `llm4j.tools.<name>.options.*`
   SHALL create the tool with the matching `ToolKind.create(name, options, baseDir)`. Options SHALL be the same keys a
   Loom script writes.
2. THE Starter SHALL call `kind.check(options, baseDir)` first, and SHALL fail start-up with its message.
3. Credential options (`url` of a webhook, `password` of sql/email) SHALL accept `*-secret` forms (Requirement 2.5).
4. `llm4j.tools.builtin` SHALL register named built-ins from ai-agent4j (`calculator`, `current-time`, `date-time`,
   `duckduckgo`, `serpapi`, `openapi`, `echo`); none is registered by default.
5. `llm4j.mcp.servers.<name>` (`command`, `args`, `env`, `tools` allow-list) SHALL start the server over
   `StdioMcpTransport`, list its tools and register each as `McpToolAdapter` named `<name>.<tool>`. Starting a server is
   the documented side effect of this property (ZM4). Servers SHALL be closed on shutdown.
6. A tool from any of these sources SHALL go into both the core and the Loom `ToolRegistry`. Two tools with the same name
   SHALL fail start-up naming both sources.
7. Effectful generic tools used by plain agents SHALL journal through an `EffectContext` bean when present, so a durable
   journal can stop repeats after a crash.

### Requirement 13: RAG and knowledge

**User Story:** As a developer, I want vector stores and embeddings from configuration, so that retrieval works with my
existing database.

#### Acceptance Criteria

1. `llm4j.rag.embedding` (`gemini`, `onnx`, `djl`) SHALL create an `EmbeddingProvider`; onnx/djl need ai-agent4j-addons.
2. `llm4j.rag.store` (`memory`, `file`, `pgvector`, `pinecone`) SHALL create a `VectorStore`. `pgvector` SHALL default its
   URL, user and password to `spring.datasource.*` (PGVectorStore takes a JDBC URL, not a `DataSource`) and use
   `PGVectorStore.withSecret` when `password-secret` is set; `pinecone` SHALL use `PineconeVectorStore.withSecret`.
3. WHEN both exist, THE Starter SHALL provide a prototype `RAGAgent` builder and the `ChunkingStrategy` from
   `llm4j.rag.chunking.*`.
4. Loom executors SHALL get an `EmbeddingFactory` bean when present (`setEmbeddingFactory`).

### Requirement 14: Deterministic tasks

**User Story:** As a developer, I want my Spring beans to be the tasks a Loom `run` step calls, so that payments and
policy checks use my services and their transactions.

#### Acceptance Criteria

1. THE Starter SHALL build a `TaskRegistry` from every `Task` bean, plus `TaskRegistry.discovered()` unless
   `llm4j.tasks.discover=false`; a user `TaskRegistry` bean wins.
2. It SHALL be set on every executor (`setTaskRegistry`). A script still can only name a task, never load one.
3. Two tasks with the same name SHALL fail start-up.

### Requirement 15: Loom scripts and runtimes

**User Story:** As a developer, I want several workflows in one application, packaged in my jar, so that deployment is
one artifact.

#### Acceptance Criteria

1. `llm4j.loom.scripts.<name>.location` (and `.loot`) SHALL each create a Script_Runtime; THE Starter SHALL expose them
   through a `LoomRuntimes` bean (`get(name)`, `names()`), and SHALL expose the one named `default` (or the only one) as
   the `LoomRuntime` bean.
2. A script in a jar SHALL resolve `import "x.loom"` relative to itself inside the jar. This needs a resolver-based
   `LoomLoader` API in ai-agent4j-loom (Design §4.2), usable without Spring.
3. A `.loot` file in a jar SHALL be read in place, not copied to a temporary file (new `LootLoader` overloads taking a
   `Reader`), and its `Name.reach` declarations SHALL reach `setToolReach`.
4. Every script SHALL be parsed and validated at start-up (`ScriptValidator` with the registered tools, tasks, prompts
   and environment), and start-up SHALL fail listing every error, so a broken script never deploys.
   `llm4j.loom.validate=lenient` SHALL report errors as warnings instead.
5. Model keys for scripts SHALL resolve through the secret store first, then the `Environment` (unchanged from v1).

### Requirement 16: Running workflows

**User Story:** As a developer, I want to start, await, steer and stop runs, and run them for the person who asked, so
that workflows fit my request handling.

#### Acceptance Criteria

1. `LoomRuntime.run(RunRequest)` SHALL run on the calling thread and return a Run_Outcome; it SHALL NOT throw for a
   suspension or a stop (those are outcomes), and SHALL throw only for a failure, carrying the run id.
2. `LoomRuntime.start(RunRequest)` SHALL return a handle (`LoomRun`) and SHALL shut its executor down when the run ends,
   in every outcome.
3. A Run_Request SHALL carry `InvokerCredentials`, passed to `executeWorkflow(workflow, inputs, invoker)`. A script that
   needs credentials the invoker cannot provide SHALL fail before its first step (library behaviour, kept).
4. Each run SHALL get a run id (given, or a new UUID), `setRunId`, `setScriptRef` and a label for `LiveRuns`.
5. THE run SHALL execute on a `TaskExecutor`; `llm4j.loom.executor` SHALL choose `virtual-threads` (Java 21),
   `platform` or `application` (the application's `applicationTaskExecutor`). `start` SHALL never block the caller.
6. `llm4j.loom.max-concurrent-runs` SHALL bound running runs; beyond it `start` SHALL throw a typed `TooManyRuns`.
7. `LoomExecutorCustomizer` beans SHALL run last, before `initialize()` (unchanged from v1).
8. On context shutdown, THE Starter SHALL ask running runs to wrap up, wait up to `llm4j.loom.shutdown-grace`, then
   suspend (Durable_Mode) or cancel them, and log what it did to each.

### Requirement 17: Durable runs: journal, run store, resume and answers

**User Story:** As an operator, I want a run that waits for a person, a timer or a rate limit to resume on its own, even
after a restart, without repeating paid-for or side-effecting work.

#### Acceptance Criteria

1. `llm4j.loom.persistence` SHALL be `none` (default), `file` or `jdbc`.
   - `file`: `FileRunJournal` per run under `llm4j.loom.persistence.dir/runs/<runId>/`, `FileTriggerStore`,
     file Run_Store.
   - `jdbc`: `JdbcRunJournal(dataSource, runId)`, `JdbcTriggerStore`, JDBC Run_Store, on the `DataSource` bean (or the
     one named in `llm4j.loom.persistence.datasource`).
2. `llm4j.loom.persistence.initialize-schema` (`always`, `embedded`, `never`; default `embedded`) SHALL run the modules'
   own `createTable` methods, never hand-written DDL.
3. In Durable_Mode, THE Starter SHALL record each run's script name, workflow, inputs and origin in the Run_Store before
   the first step. Invoker credentials SHALL never be stored.
4. `LoomRuntime.resume(runId, invoker)` SHALL rebuild the executor with the same journal and continue; finished steps
   SHALL be replayed from the journal, not repeated.
5. `LoomRuntime.answer(runId, stepId, text, by)` SHALL record a person's answer for a `human_prompt` or `approve:` step
   in the run's journal, audit it, and schedule an immediate resume trigger. An answer to a step that is not waiting
   SHALL be refused with the reason.
6. WHEN persistence is `none` and a script can suspend (`human_prompt`, `approve:`, `sleep`, `rate_limits`, a windowed
   budget), start-up SHALL fail naming `llm4j.loom.persistence`, unless `llm4j.loom.allow-non-durable=true`, in which
   case a suspension SHALL become a failed Run_Outcome saying it cannot resume.
7. `LoomRuntime.runs()` SHALL list recorded runs with their status, and `RunTravel`'s rewind and fork SHALL be reachable
   through `LoomRuntime.travel(runId)`.

### Requirement 18: People in the loop

**User Story:** As a product owner, I want approvals and questions to reach people in my web UI or chat, so that a
workflow can wait for them without holding a thread.

#### Acceptance Criteria

1. In Durable_Mode with no `HumanInterface` bean, THE Starter SHALL set a suspending interface: a question suspends the
   run (`RunSuspended(stepId, prompt)`) and is answered with `LoomRuntime.answer`.
2. `HumanQuestionListener` beans SHALL be told of each question (run id, step id, prompt, choices), so the application can
   show it in its own UI.
3. WHEN `llm4j.loom.human.ask-via` names a channel (Telegram, a command channel), THE Starter SHALL open it with
   `Channels.open(...)`, use `humanFor(runDir, runId)` per run, and run its listener as a `SmartLifecycle`.
4. A user `HumanInterface` bean SHALL win.

### Requirement 19: Schedules and triggers

**User Story:** As an operator, I want `schedule` blocks and resume wake-ups fired by my application, so that I don't
need a separate `weave daemon`.

#### Acceptance Criteria

1. WHEN `llm4j.loom.triggers.enabled=true` (Durable_Mode required), THE Starter SHALL run a `TriggerRunner` over the
   trigger store as a `SmartLifecycle`, polling every `poll-interval`, with `owner` (default host and pid) and
   `stale-after`, so several replicas claim each trigger once.
2. Its `TriggerTarget` SHALL resume runs (`ResumeRun`), start scripts' workflows by script name (`StartWorkflow`) and give
   tasks to agents (`AgentTask`), and SHALL map results to `TriggerTarget.Outcome` as `weave` does.
3. The `schedule` blocks of each script SHALL be written to the trigger store once at start-up, and SHALL not be
   registered again by each run's executor (mirroring `WeaveTriggerTarget`).
4. WHEN `llm4j.loom.triggers.endpoint.enabled=true`, THE Starter SHALL expose `TriggerEndpoint` at
   `POST {path}` (default `/llm4j/loom/tick`) for Cloud Scheduler and cron, with the shared token from the secret
   `LOOM_TRIGGER_TOKEN` or a `BearerVerifier` bean. Without either, start-up SHALL fail.
5. `TriggerRunner.onFired` events SHALL be published as Spring `ApplicationEvent`s.

### Requirement 20: Earned autonomy

**User Story:** As a team adopting autonomy, I want decision ledgers and levels stored with my application's data, so
that agents earn trust across deployments.

#### Acceptance Criteria

1. WHEN a script uses `decision`/`ladder`, THE Starter SHALL set `setAutonomy(ledger, levels)` with `FileLedger` /
   `FileLevelStore` or `JdbcLedger` / `JdbcLevelStore`, following `llm4j.loom.persistence`; user `Ledger` and
   `LevelStore` beans win.
2. A script that uses autonomy with persistence `none` SHALL fail start-up (a ledger in memory forgets earned trust).

### Requirement 21: Security audit at start-up

**User Story:** As a security reviewer, I want `weave audit` run on every script when the application starts, so that a
workflow with the lethal trifecta cannot be deployed by accident.

#### Acceptance Criteria

1. THE Starter SHALL run `SecurityAudit.audit(script, name, lootReach)` for every script at start-up.
2. WHEN the report `failsAt(llm4j.loom.audit.fail-on)` (default `high`), start-up SHALL fail with the findings in
   Markdown. `fail-on=none` SHALL log every finding at WARN and SHALL log once that the audit gate is off.
3. Acceptance of a risk SHALL stay in the script (`trifecta: accepted "reason"`); THE Starter SHALL offer no property that
   silences a single rule.
4. The JSON report SHALL be available at `/actuator/loom` (Requirement 23) when Actuator is present.

### Requirement 22: Observing runs (loom-inspect and Fabric)

**User Story:** As an operator, I want the live run page or Fabric reporting without the CLI, so that I can watch
workflows in production.

#### Acceptance Criteria

1. WHEN loom-inspect is on the class path and `llm4j.observe.enabled=true`, THE Starter SHALL open one
   `ObserveProvider.Session` (`Observe.open`) at start-up, with `ObserveOptions` from `llm4j.observe.port`, `bind`
   (default `127.0.0.1`), `detail`, `name`, `linger`, `rules-file`, and `FabricOptions` from `llm4j.observe.fabric.*`.
2. Every executor SHALL be attached (`session.attach(executor.observable(), scriptFile)`) before `initialize()`.
3. The admin password and Fabric client key SHALL come from the secret store under their existing names; THE Starter
   SHALL refuse a non-loopback `bind` without them.
4. The session SHALL close on shutdown, flushing the outbox within `flush-timeout`.

### Requirement 23: Metrics, health and Actuator

**User Story:** As an SRE, I want llm4j in my existing dashboards and health checks, so that I monitor it like the rest
of the service.

#### Acceptance Criteria

1. WHEN Micrometer is present, THE Starter SHALL record: `llm4j.model.calls` (client, provider, model, outcome),
   `llm4j.model.tokens` (type: input/output), `llm4j.model.cost`, `llm4j.model.latency`, `llm4j.tool.calls` (tool,
   outcome), `llm4j.budget.events` (kind), `llm4j.context.compactions`, and for Loom `loom.runs.started`,
   `loom.runs.finished` (status), `loom.runs.active`, `loom.steps`, `loom.suspensions` (reason), `loom.triggers.fired`
   (outcome) and `loom.triggers.overdue`. Tags SHALL never carry prompt or answer text, user ids or secrets.
2. WHEN Actuator is present, THE Starter SHALL contribute health indicators: `llm4j` (clients configured and their keys
   present, without calling a provider), `loom` (scripts valid, persistence reachable, trigger runner alive and not
   behind by more than `llm4j.loom.triggers.overdue-threshold`).
3. A read-only `loom` endpoint SHALL list scripts, live runs, recorded runs (status, suspension reason, resume time) and
   audit summaries. Write operations SHALL not be exposed through Actuator.
4. Micrometer Observation / OpenTelemetry tracing SHALL wrap model calls and runs when an `ObservationRegistry` is
   present; Loom's own OTLP export (`OtlpOptions`) SHALL remain configurable under `llm4j.observe.otlp.*`.

### Requirement 24: Run-control web API (opt-in)

**User Story:** As a developer of an internal tool, I want ready REST endpoints to answer, steer, pause and cancel runs,
so that I don't write that controller myself.

#### Acceptance Criteria

1. WHEN `llm4j.loom.web.enabled=true` and Spring MVC or WebFlux is present, THE Starter SHALL expose under
   `llm4j.loom.web.path` (default `/llm4j/loom`): list runs, get a run, start a workflow, answer a question, steer,
   wrap up, pause, resume, cancel.
2. Every endpoint SHALL require an authenticated principal when Spring Security is present, and the authority in
   `llm4j.loom.web.authority` (default `LOOM_OPERATOR`) for write operations. WHEN Spring Security is absent, start-up
   SHALL fail unless `llm4j.loom.web.allow-unsecured=true`, which SHALL also log a WARN at every start.
3. The acting principal's name SHALL be the origin recorded in the journal and audit log.
4. Responses SHALL never include secrets or invoker credentials, and SHALL include prompt text only when
   `llm4j.loom.web.include-text=true`.

### Requirement 25: Testing support

**User Story:** As a developer, I want tests that never call a paid model by accident, and live tests with a spend cap,
so that CI is free and safe.

#### Acceptance Criteria

1. `llm4j-spring-boot-starter-test` SHALL provide `@AutoConfigureLlm4jMock`, which replaces the Primary_Client and every
   Named_Client with a `ScriptedClient` bean, and Loom's `LLMClientFactory` with `MockModels` (or a user-supplied factory),
   so no provider is contacted. `@Llm4jTest` SHALL be a slice that starts only llm4j's auto-configuration.
2. `@AutoConfigureLlm4jLive` SHALL keep real clients but wrap each in the eval4j `SpendGuard`, stopping the test once
   `llm4j.test.max-spend` is reached, and SHALL be skipped (not failed) when a required key is missing.
3. `llm4j.test.replay.mode` (`off`, `record`, `replay`) with `llm4j.test.replay.dir` SHALL use eval4j `AgentReplay` so
   recorded live runs can be replayed for free.
4. A `FakeJudge` bean SHALL be offered for judge clients, and eval4j results SHALL go to `target/eval4j`, where
   eval4j-report reads them.
5. Loom tests SHALL be able to fix time (a `Clock` bean) and run durable runs on an in-memory journal and trigger store.

### Requirement 26: Configuration metadata and documentation

**User Story:** As a developer, I want my IDE to complete and explain every `llm4j.*` property, and docs that show what
each bean is in plain Java.

#### Acceptance Criteria

1. Every property SHALL have a description and default in `spring-configuration-metadata.json`, plus value hints for
   enumerations (providers, kinds, strategies, persistence, severities).
2. The starter README SHALL have, per capability: the YAML, the bean it creates and the plain-Java equivalent.
3. An example application (`src/examples/spring-boot-starter-demo`, built with `-Pextended`) SHALL run a durable
   workflow with an approval answered over the web API, with mock models.
4. `docs/API_COMPATIBILITY.md` SHALL list the new public types; the LoomLoader and LootLoader additions SHALL keep the
   existing signatures.

### Requirement 27: Quality gates

**User Story:** As a maintainer, I want the starter held to the same bar as the rest of llm4j, so that it doesn't
become the weakest module.

#### Acceptance Criteria

1. Default-build tests SHALL be hermetic (no network, no real provider). JDBC tests SHALL use H2 and be tagged
   `integration` only if they need anything outside the JVM.
2. JaCoCo SHALL require line coverage ≥ 0.85 and branch coverage ≥ 0.80 for the autoconfigure module, and ≥ 0.90 branch
   for the classes that decide security (secret wiring, web security guard, audit gate, durability guard).
3. CI SHALL build the starter on JDK 17 and 21 and against the lowest and the latest supported Spring Boot 3.x.
