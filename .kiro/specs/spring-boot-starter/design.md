# Design Document: Spring Boot Starter

## Overview

The starter is thin wiring. The only substantial new code is `LoomHost` in the Loom library, because the run/pause/resume
behaviour a web application needs exists today only in package-private CLI classes.

```
 application.yml ─► Llm4jProperties (llm4j.*)                    user beans always win
                         │
 ┌──── llm4j-spring-boot-starter (one artifact, wiring only) ──────────────────────────┐
 │ Secrets      SecretStore                                                            │
 │ Clients      LLMClients{name→client} ─► Masking(Budgeted(Routing|Named)) = LLMClient │
 │ Agents       ReActAgent.Builder (prototype) ◄─ Tool beans, hooks, prompts, context   │
 │ Tools        ToolCatalog ─► ToolRegistry (core) + Loom registry; TaskRegistry        │
 │ Memory/Rag   ConversationStore, PromptRegistry, VectorStore, EmbeddingProvider       │
 │ Loom         LoomRuntime ─► LoomHost ──────────────┐ validate + audit + guards       │
 │ Triggers     SmartLifecycle ─► host.startTriggers  │                                 │
 │ Observe      loom-inspect session (optional)       │                                 │
 └────────────────────────────────────────────────────┼─────────────────────────────────┘
                                                      ▼
   ai-agent4j-loom: io.github.llm4j.loom.host.LoomHost   (NEW, public, no Spring)
        │ builds + initializes a HarnessExecutor per run, in one place
        │ RunStore (file: weave's run-dir layout │ jdbc: llm4j_loom_runs) · journals · trigger store
        │ run · start · resume · answer · runs · triggerTarget · startTriggers
        ▼
   HarnessExecutor · FileRunJournal/JdbcRunJournal · TriggerRunner · Answers · RunTravel
```

## 1. Packaging

One artifact `src/spring/llm4j-spring-boot-starter` (v1's `src/llm4j-spring-boot-starter` moves there; the root `pom.xml` entry is updated).
Dependencies: `ai-agent4j`, `spring-boot-autoconfigure` (compile); `ai-agent4j-loom`, `ai-agent4j-tools`, `ai-agent4j-addons`, `loom-inspect`,
`spring-jdbc`, `spring-boot-configuration-processor` (`optional`). Spring Boot 3.2.x pinned by property; BOM not imported (Jackson stays on llm4j's).
It can be split into autoconfigure + starter later without changing properties or beans.

Auto-configuration classes (each `@AutoConfiguration`, ordered with `after`/`before`, excludable on its own):
`Llm4jSecretsAutoConfiguration` → `Llm4jClientsAutoConfiguration` → `Llm4jToolsAutoConfiguration` → `Llm4jAgentsAutoConfiguration`
→ `Llm4jRagAutoConfiguration` → `Llm4jLoomAutoConfiguration` (→ nested `Triggers`, `Observe`).

**Module guard (R1.3).** An `EnvironmentPostProcessor` checks each property prefix against `ClassUtils.isPresent` of a marker class and throws
`Llm4jConfigurationException` naming the artifact. A `FailureAnalyzer` renders every `Llm4jConfigurationException` as Description/Action
(ZM5), so each failure message in the requirements has one code path.

## 2. Properties

```yaml
llm4j:
  enabled: true
  primary: default
  secrets: { store: env|encrypted-file|chained, file: …, master-key-env: …, master-key-file: …, chain: [encrypted-file, env] }
  clients:
    default: { provider: anthropic, model: claude-sonnet-5-5, api-key-secret: ANTHROPIC_API_KEY, base-url: …, timeout: 60s,
               connect-timeout: 10s, tier: REASONING, effort: medium,
               retry: { enabled: true, max-retries: 3, initial-backoff: 1s, max-backoff: 30s, inline-wait-threshold: 30s } }
    cheap:   { provider: gemini, model: gemini-2.5-flash, api-key-secret: GEMINI_API_KEY, tier: FAST_CHEAP }
  routing: { strategy: none|fallback|cost-aware, clients: [cheap, default] }
  pricing: { file: classpath:prices.yaml }            # or models.<model>.{input-per-million,output-per-million}
  budget:  { tokens: 2000000, calls: 5000, cost: "25.00", window: day, warn-at: 0.8, per-call-max-tokens: 4000 }
  privacy: { mask-pii: true, types: [EMAIL, PHONE] }
  audit:   { file: /var/log/app/llm4j-audit.jsonl, max-file-size: 50MB, max-files: 10 }
  agent:   { client: default, system-prompt: …, system-prompt-id: triage, max-iterations: 8, temperature: 0.2, tools: [Slack],
             context: { window: 200000, compaction: summarize, compaction-client: cheap } }
  memory:  { conversations: file, dir: /var/app/chats, max-history: 50 }
  prompts: { location: classpath:prompts/ }
  skills:  { locations: [classpath:skills/], allow-remote: false }
  tasks:   { discover: true }
  tools:
    builtin: [calculator]
    Slack:  { kind: webhook, options: { url-secret: SLACK_WEBHOOK, format: slack } }
  rag: { embedding: gemini, store: pgvector, pgvector: { table: docs, dimension: 768, password-secret: PG_PASSWORD } }
  loom:
    script: classpath:workflows/support.loom
    loot:   classpath:workflows/tools.loot
    validate: strict|lenient
    persistence: none|file|jdbc                       # + dir / datasource / initialize-schema: always|embedded|never
    allow-non-durable: false
    executor: virtual-threads|platform|application
    max-concurrent-runs: 32
    shutdown-grace: 30s
    budget: { tokens: …, calls: …, cost: … }
    context: { windows-files: [classpath:windows.yaml] }
    audit: { fail-on: high }
    triggers: { enabled: false, poll-interval: 15s, stale-after: 5m }
  observe: { enabled: false, port: 8787, bind: 127.0.0.1, detail: metadata, name: svc, rules-file: …, fabric: { url: …, ca: …, outbox: … } }
```

**Credentials (R2.3).** `CredentialProperty.ref(path, literal, secretName, store)`: both set → fail naming both; secret → `SecretRef.of(store, name)`
(fail if no store); literal → `SecretRef.literal`. Literal credential keys are added to the sanitizing function; properties classes print `***`.

## 3. Clients

```java
LLMConfig.builder().defaultModel(p.model()).apiKey(ref).baseUrl(...).timeout(...).connectTimeout(...).retryPolicy(RetryPolicies.from(p.retry(), clock, sleeper))
provider = switch (p.provider()) { gemini→GoogleProvider, anthropic→AnthropicProvider(config[, effort]), ollama→OllamaProvider (base-url + "/api"), sarvam→SarvamChatProvider }
new DefaultLLMClient(provider)      // validate() checks config only
```

Decoration order, outermost first: `LLMClientCustomizer`s → `MaskingLLMClient` → `BudgetedLLMClient` → `RoutingLLMClient` | named client.
Masking is outside the budget so the estimator and providers see masked text; the budget is outside routing so a logical call is charged once.
The budget estimator is the `TokenEstimator` bean when present.

## 4. LoomHost (library)

```java
package io.github.llm4j.loom.host;

public final class LoomHost implements AutoCloseable {
  public static Builder builder(LoomScript script, io.github.llm4j.loom.execution.ToolRegistry tools, LLMClientFactory models);
  public RunOutcome run(RunRequest r);            // blocking
  public LoomRun   start(RunRequest r);           // on the configured Executor; executor.shutdown() in every outcome
  public RunOutcome resume(String runId, InvokerCredentials invoker);
  public LoomRun   resumeAsync(String runId, InvokerCredentials invoker);
  public AnswerResult answer(String runId, String stepId, String text, String by);
  public List<RunRecord> runs();  public Optional<RunRecord> run(String id);
  public TriggerTarget triggerTarget();
  public void startTriggers(Duration poll, String owner, Duration staleAfter, Consumer<TriggerRunner.Fired> listener);  public void stopTriggers();
  public HarnessExecutor newExecutor(String runId);   // assembled and initialized; for tests and advanced use
  public static void createSchema(DataSource ds);      // JdbcRunJournal, JdbcTriggerStore, JdbcLedger, JdbcLevelStore, llm4j_loom_runs
}
public record RunRequest(String workflow, Map<String,String> inputs, String runId, String label, String origin, InvokerCredentials invoker) { … }
public sealed interface RunOutcome { Completed, Suspended(reason, resumeAt, stepId, prompt), Stopped }   // failure → LoomRunFailedException(runId, cause)
public record RunRecord(String runId, String workflow, Map<String,String> inputs, String origin, Status status, Reason reason, Instant resumeAt, String stepId, Instant updated)
public interface QuestionListener { void asked(String runId, String stepId, String prompt, List<String> choices); }
```

**Executor assembly (one method, one order):** construct → `setBaseDir`, `setScriptRef`, `setRunId`, `setRunLocator` → `setSecretStore`, `setEnvLookup`, `setClock`, `setSleeper`
→ `setToolReach`, `setTaskRegistry`, `setPromptRegistry`, `setPriceTable`, `setBudgetOverrides`, `setContextWindows`, `setEmbeddingFactory`, `setMemoryEngine`, `setAuditLogger`
→ durable: `setJournal`, `setTriggerStore`, `setAutonomy` → `setHumanInterface` → observe `attach` (before initialize) → customizer → `initialize()`.
Each step is skipped when its input is absent, so a host with no options behaves like v1's `LoomRuntime`.

**Persistence.** `file(dir)`: `<dir>/runs/<id>/journal.json` (`FileRunJournal`) and the run's spec file in the format `RunSpec` writes (so `weave resume` can read it; the
host reads `RunSpec`'s JSON through a small public `RunSpecFile` reader/writer extracted from the CLI, with the CLI delegating to it), `<dir>/triggers/` (`FileTriggerStore`),
ledger and levels beside them. `jdbc(ds)`: `JdbcRunJournal(ds, runId)`, `JdbcTriggerStore`, `JdbcLedger`/`JdbcLevelStore`, and `llm4j_loom_runs`
(run_id PK, workflow, inputs JSON, origin, status, reason, resume_at, step_id, created, updated). Invoker credentials are never written.

**Answering.** `answer` loads the RunRecord (must be `SUSPENDED/HUMAN` at `stepId`), matches choices with `Answers.match`, writes the journal entry that
`ApprovalGate`/`human_prompt` read on replay (the form `channel/Answers` and the CLI's `weave answer` already produce — Task 3.1 confirms the key and kind and, if the CLI uses
a different path, factors it into one public `HumanAnswers.record(journal, stepId, answer)` that both call), audits `answered`, sets status `RESUMING`, and upserts
`Trigger.resume(runId, now, "answered", 0)`; without a running trigger runner it resumes asynchronously itself.

**Suspending human interface.** `promptHuman(stepId, message, hints)`: journaled answer present → return it; otherwise notify `QuestionListener`s and throw `RunSuspended(stepId, message)`.

**Triggers.** `triggerTarget()` is `WeaveTriggerTarget`'s logic over the host: `ResumeRun` → `resume` (a run needing credentials → `Outcome.human("needs the person's credentials")`),
`StartWorkflow` → `run` with the slot run id, `AgentTask` → executor with an in-memory trigger store then `runAgentTask`. Schedule triggers are written once by `registerSchedules()` at host
start (Task 3.5 checks whether `HarnessExecutor.initialize()` re-registers them per run and, if so, adds `HarnessExecutor.setRegisterSchedules(boolean)`, default `true`).

**Scripts in jars.** `ScriptSource { String read(String location); String resolve(String from, String imported); Optional<Path> directory(String location); static fileSystem(); }`,
`LoomLoader.load(location, source)`, `LootLoader.loadIntoRegistry(Reader, ToolRegistry)` and `reaches(Reader)`. The existing path-based methods delegate.

**CLI.** The CLI is not rewritten. Only `RunSpec` read/write is extracted (Task 3.1). A follow-up, out of scope here, can move `Runs.execute` onto `LoomHost`.

## 5. Spring wiring of Loom

```java
@Bean LoomRuntime loomRuntime(...):
   script  = load via ResourceScriptSource (Spring ResourceLoader)           tools = ToolCatalog.loomRegistry() + .loot
   models  = LLMClientFactory bean | new DefaultLLMClientFactory(environment::getProperty, secretStore)
   validate: ScriptValidator.validateOrThrow(...)                            // R9.3
   audit:    SecurityAudit.audit(script, name, lootReach).failsAt(threshold) // R9.4
   guard:    DurabilityGuard.check(script, persistence, allowNonDurable)     // R9.5
   host = LoomHost.builder(...)…build();   return new LoomRuntime(host);
```

`LoomRuntime` is a `final` class whose methods call the host; it exists so the bean has a Spring-free-looking surface and so the shutdown hook lives in one place.
A `SmartLifecycle` (`Llm4jLoomLifecycle`) starts triggers after validation and, on stop, stops triggers, wraps up running runs, waits `shutdown-grace`, then suspends/cancels, then closes the host.
`HumanQuestionEvent` is published by a `QuestionListener` registered by the Starter.

## 6. Tools, agents, RAG

`ToolCatalog` gathers `Tool` beans → built-ins → kinds (`check` then `create`, `-secret` options resolved from the store) and fails on duplicate names naming both sources. The core `ToolRegistry`
and the Loom registry are filled from it. `TaskRegistry` = `Task` beans + `discovered()`. `ReActAgent.Builder` is assembled by one method in the order: client, tools, prompts/system, loop settings,
context, budget/prices, audit, hooks, listeners, then customizers. `PromptRegistry`, `ConversationStore`, `VectorStore`, `EmbeddingProvider` are one `@Bean` each.

## 7. Compatibility and risks

| Topic | Decision |
|---|---|
| v1 | v1 was never released; its two properties stay as shorthands. v1's `LoomRuntime.run/start(String, Map)` stay as overloads. |
| Library changes | Additive only: `loom.host.*`, `ScriptSource`, `LoomLoader`/`LootLoader` overloads, a public `RunSpecFile`, possibly `HumanAnswers` and `setRegisterSchedules`. All listed in `API_COMPATIBILITY.md`; all tested in `ai-agent4j-loom` without Spring. |
| Two `ToolRegistry` types | The core one is the bean; Loom's is internal to the `LoomRuntime`. |
| Replicas | Trigger claims make firing once-only; `LiveRuns` is per process. |
| JDBC | The library's DDL targets PostgreSQL and H2; other databases use `initialize-schema=never` and the DDL constants. |
| Biggest unknowns | Whether `weave`'s answer path writes the key `human_prompt` replays from (3.1); whether `initialize()` re-registers schedules (3.5). Both are scheduled first. |
