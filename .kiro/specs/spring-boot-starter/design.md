# Design Document: Spring Boot Starter

## Overview

```
 application.yml ──► Llm4jProperties (llm4j.*)                 user beans (always win)
                         │                                          │
                         ▼                                          ▼
 ┌──────────────────────────── llm4j-spring-boot-autoconfigure ────────────────────────────┐
 │ SecretsAutoConfiguration      SecretStore ─────────────────────────────┐                │
 │ ClientsAutoConfiguration      LLMClients{name→client} ─► Primary LLMClient              │
 │                               Masking( Budgeted( Routing|Named( DefaultLLMClient ))))    │
 │ ToolsAutoConfiguration        Tool beans + kinds + built-ins + MCP ─► ToolRegistry      │
 │ AgentsAutoConfiguration       ReActAgent.Builder (prototype), Agents, AgentSessions     │
 │ Memory/Rag/Prompts/Tasks      ConversationStore, VectorStore, PromptRegistry, Tasks     │
 │ LoomAutoConfiguration         LoomRuntimes{name→LoomRuntime}                            │
 │   ├─ LoomPersistence          RunJournalFactory, TriggerStore, RunStore, Ledger…        │
 │   ├─ LoomTriggers             TriggerRunner (SmartLifecycle), TriggerEndpoint           │
 │   ├─ LoomHuman                HumanInterface, channels                                  │
 │   └─ LoomSecurityGate         SecurityAudit at start-up                                 │
 │ ObserveAutoConfiguration      ObserveProvider.Session (loom-inspect / Fabric)           │
 │ Llm4jMetrics/Health/Endpoint  Micrometer binders, HealthIndicators, /actuator/loom      │
 │ LoomWebAutoConfiguration      /llm4j/loom/** (opt-in, secured)                          │
 └─────────────────────────────────────────────────────────────────────────────────────────┘
                         ▼ only public llm4j API ▼
      ai-agent4j · ai-agent4j-tools · ai-agent4j-addons · ai-agent4j-loom · loom-inspect · eval4j
```

Every box is a separate `@AutoConfiguration` class with explicit `after`/`before` ordering, so each can be excluded on
its own (`spring.autoconfigure.exclude`), and each is guarded by `@ConditionalOnClass` for its optional module.

---

## 1. Modules

| Artifact | Contents | Dependencies |
|---|---|---|
| `llm4j-spring-boot-autoconfigure` | all auto-configuration, properties, `LoomRuntime`, web adapters | `ai-agent4j`, `spring-boot-autoconfigure`; everything else `optional` |
| `llm4j-spring-boot-starter` | POM only | autoconfigure, `ai-agent4j`, `spring-boot-starter` |
| `llm4j-spring-boot-starter-loom` | POM only | starter, `ai-agent4j-loom`, `ai-agent4j-tools` |
| `llm4j-spring-boot-starter-test` | test annotations and auto-configuration | autoconfigure, `eval4j`, `spring-boot-test` |

The v1 single module `src/llm4j-spring-boot-starter` becomes `src/spring/` with the four sub-modules. Spring Boot is pinned
by a `spring-boot.version` property (3.2.x baseline); its BOM is not imported, so Jackson stays on the llm4j version.

### 1.1 Missing-module guard (Req 1.4)

`Llm4jModuleGuard` is an `EnvironmentPostProcessor`. For each property prefix that needs an optional module, it checks
with `ClassUtils.isPresent` and throws an `IllegalStateException` naming the artifact:

| Prefix set | Needs class | Message names |
|---|---|---|
| `llm4j.loom.*` | `io.github.llm4j.loom.execution.HarnessExecutor` | `llm4j-spring-boot-starter-loom` |
| `llm4j.tools.*.kind` | `io.github.llm4j.tools.GenericKind` | `ai-agent4j-tools` |
| `llm4j.rag.store=pgvector\|pinecone`, `llm4j.rag.embedding=onnx\|djl` | the addon class | `ai-agent4j-addons` |
| `llm4j.observe.*` | `io.github.llm4j.loom.observe.Observe` provider on the class path | `loom-inspect` |

A `FailureAnalyzer` turns these, and every other `Llm4jConfigurationException`, into Spring Boot's
"Description / Action" output.

---

## 2. Properties

One root `@ConfigurationProperties("llm4j")` class per area (each a `record` or JavaBean with constructor binding),
validated with `@Validated` where the rule is static. The full tree:

```yaml
llm4j:
  enabled: true
  primary: default                         # which client is the LLMClient bean
  secrets:
    store: env | encrypted-file | chained | none
    file: /var/app/keys.store
    master-key-env: LLM4J_MASTER_KEY       # or master-key-file
    chain: [encrypted-file, env]
  clients:
    default:
      provider: anthropic                  # gemini | anthropic | ollama | sarvam
      model: claude-sonnet-5-5
      api-key-secret: ANTHROPIC_API_KEY    # or api-key: ${…} (literal; discouraged)
      base-url: …
      timeout: 60s
      connect-timeout: 10s
      tier: REASONING
      effort: medium                       # anthropic only
      retry: { max-retries: 3, initial-backoff: 1s, max-backoff: 30s, inline-wait-threshold: 30s }
    cheap:
      provider: gemini
      model: gemini-2.5-flash
      api-key-secret: GEMINI_API_KEY
      tier: FAST_CHEAP
  routing:
    strategy: none | fallback | cost-aware
    clients: [cheap, default]
  pricing:
    file: classpath:prices.yaml            # or models.<model>.input-per-million / output-per-million
  budget: { tokens: 2000000, calls: 5000, cost: "25.00", window: day, warn-at: 0.8, per-call-max-tokens: 4000,
            scope: primary }                # primary | each (each named client gets its own Budgeted wrapper)
  privacy: { mask-pii: true, types: [EMAIL, PHONE, CREDIT_CARD] }
  fairness: { monitor: rule-based }
  audit: { file: /var/log/app/llm4j-audit.jsonl, max-file-size: 50MB, max-files: 10 }
  agent:                                   # defaults for every agent
    client: default
    system-prompt: …                       # or system-prompt-id (from llm4j.prompts)
    persona: support
    max-iterations: 8
    temperature: 0.2
    tool-calling: auto
    tools: [calculator, Slack]             # default: all
    skills: [refunds]
    context: { window: 200000, compaction: summarize, compaction-client: cheap }
  agents:
    triage: { system-prompt-id: triage, client: cheap, tools: [Search] }
  memory:
    conversations: file                    # memory | file
    dir: /var/app/conversations
    max-history: 50
    semantic: { … SemanticMemoryConfig … , tool: false }
  prompts: { location: classpath:prompts/ }
  skills: { locations: [classpath:skills/], allow-remote: false }
  personas: { location: classpath:personas.yaml }
  tasks: { discover: true }
  tools:
    builtin: [calculator, current-time]
    Slack: { kind: webhook, options: { format: slack, url-secret: SLACK_WEBHOOK } }
    Orders: { kind: sql, options: { url: "jdbc:postgresql://db/orders", user: ro, password-secret: ORDERS_DB, max_rows: "200" } }
  mcp:
    servers:
      github: { command: npx, args: [-y, "@modelcontextprotocol/server-github"], env: { GITHUB_TOKEN-secret: GH_TOKEN }, tools: [search_issues] }
  rag: { embedding: gemini, store: pgvector, pgvector: { table: docs, dimension: 768 }, chunking: { size: 800, overlap: 100 } }
  loom:
    scripts:
      default: { location: classpath:workflows/support.loom, loot: classpath:workflows/tools.loot }
      digest:  { location: classpath:workflows/digest.loom }
    validate: strict | lenient
    persistence: none | file | jdbc
    persistence.dir: /var/app/loom            # file
    persistence.datasource: loomDataSource    # jdbc; default the DataSource bean
    persistence.initialize-schema: embedded   # always | embedded | never
    allow-non-durable: false
    executor: virtual-threads | platform | application
    max-concurrent-runs: 32
    shutdown-grace: 30s
    budget: { tokens: …, calls: …, cost: … }  # setBudgetOverrides
    context: { windows-files: [classpath:windows.yaml] }
    audit: { fail-on: high }                  # high | medium | low | info | none
    human: { ask-via: telegram }
    triggers: { enabled: true, poll-interval: 15s, stale-after: 5m, overdue-threshold: 2m,
                endpoint: { enabled: false, path: /llm4j/loom/tick } }
    web: { enabled: false, path: /llm4j/loom, authority: LOOM_OPERATOR, allow-unsecured: false, include-text: false }
  observe:
    enabled: false
    port: 8787
    bind: 127.0.0.1
    detail: metadata
    name: support-prod
    rules-file: classpath:support.inspect
    fabric: { url: https://fabric.internal:9090, ca: /etc/ssl/fabric-ca.pem, outbox: /var/app/outbox, flush-timeout: 5s }
    otlp: { … }
  test:                                       # starter-test only
    max-spend: "0.50"
    replay: { mode: off, dir: src/test/resources/replays }
```

Shorthand mapping for v1 compatibility (`Llm4jLegacyProperties` `EnvironmentPostProcessor`): `llm4j.provider|model|
api-key|base-url|timeout|connect-timeout` → `llm4j.clients.default.*`; `llm4j.loom.script|loot` →
`llm4j.loom.scripts.default.location|loot`. Setting both forms fails (ZM5).

### 2.1 Credential properties (Req 2.5–2.7)

`CredentialProperty` resolves a pair `(literal, secretName)`:

```java
SecretRef ref(String property, String literal, String secretName, SecretStore store)
  // both set      → Llm4jConfigurationException("set " + property + " or " + property + "-secret, not both")
  // secretName    → SecretRef.of(requireStore(property), secretName)   (fails if no SecretStore bean)
  // literal       → SecretRef.literal(literal)
  // neither       → null
```

All literal credential keys (`*api-key`, `*password`, `*token`, `url` of webhook) are added to
`management.endpoint.env.additional-keys-to-sanitize` / `configprops` sanitizing via a `SanitizingFunction` bean.
Properties classes override `toString()` to print `***`.

For tool options, an option key ending in `-secret` is resolved to the value at **tool creation** only when the kind
needs a string (the generic kinds take resolved strings); the README says this value then lives in the tool, which is
the same as a Loom script's `env.NAME`.

---

## 3. Clients

### 3.1 Building a Named_Client

```java
final class ClientFactory {
  LLMClient create(String name, ClientProperties p, SecretStore secrets, Clock clock, Sleeper sleeper) {
    LLMConfig.Builder c = LLMConfig.builder().defaultModel(p.model());
    SecretRef key = CredentialProperty.ref("llm4j.clients." + name + ".api-key", p.apiKey(), p.apiKeySecret(), secrets);
    if (key == null && !p.provider().equals("ollama")) throw missingKey(name, p.provider());
    if (key != null) c.apiKey(key);
    if (p.baseUrl() != null) c.baseUrl(p.provider().equals("ollama") ? ollamaApi(p.baseUrl()) : p.baseUrl());
    if (p.timeout() != null) c.timeout(p.timeout());
    if (p.connectTimeout() != null) c.connectTimeout(p.connectTimeout());
    c.retryPolicy(RetryPolicies.from(p.retry(), clock, sleeper));
    LLMProvider provider = switch (p.provider()) {
      case "gemini" -> new GoogleProvider(c.build());
      case "anthropic" -> p.effort() == null ? new AnthropicProvider(c.build()) : new AnthropicProvider(c.build(), p.effort());
      case "ollama" -> new OllamaProvider(c.build());
      case "sarvam" -> new SarvamChatProvider(c.build());
      default -> throw unknownProvider(name, p.provider());
    };
    return new DefaultLLMClient(provider);   // validate() checks config only; no network
  }
}
```

`LLMClients` (bean) holds `Map<String, LLMClient>` in declaration order, plus the tier of each.

### 3.2 Decoration order (Req 3, 5, 6)

```
Primary = customizers( Masking( Budgeted( base ) ) )
base    = routing.strategy == none ? clients.get(primary)
                                   : RoutingLLMClient.builder().strategy(s).addClient(tier, client)….build()
```

- **Masking outermost**: masked text is what the budget estimates and what every provider sees.
- **Budget outside routing**: one logical call is charged once, whichever tier answered; a failover's failed attempt is
  not charged (it returned no usage).
- `BudgetedLLMClient.builder(base).budget(budget).prices(prices).model(primaryModel).perCallCap(n).estimator(est)`.
- The decorated client is the `@Primary LLMClient` bean. The undecorated named clients stay reachable through
  `LLMClients` for code that must bypass routing (never the budget: `LLMClients.get(name)` returns
  `Budgeted(Masking?)` per client when `llm4j.budget.scope=each`; default `scope=primary`).

### 3.3 Retry policy

`RetryPolicies.from(RetryProperties, Clock, Sleeper)` → `RetryPolicy.builder()` with only the set fields; `enabled=false`
→ `RetryPolicy.noRetry()`. `Clock`/`Sleeper` beans are used when present, else system defaults.

---

## 4. Loom

### 4.1 Script runtimes

```java
public final class LoomRuntime {                 // one per script; thread-safe
  public String name();
  public LoomScript script();
  public RunOutcome run(RunRequest request);     // blocking; suspension/stop are outcomes
  public LoomRun start(RunRequest request);      // async; executor shut down on completion
  public RunOutcome resume(String runId, InvokerCredentials invoker);
  public LoomRun resumeAsync(String runId, InvokerCredentials invoker);
  public AnswerResult answer(String runId, String stepId, String text, String by);
  public List<RunRecord> runs();                 // from RunStore (empty when not durable)
  public Optional<RunRecord> run(String runId);
  public RunTravel travel(String runId);         // rewind / fork through the library
  public HarnessExecutor newExecutor(String runId);  // escape hatch, fully configured + initialized
}

public record RunRequest(String workflow, Map<String,String> inputs, String runId, String label, String origin,
                         InvokerCredentials invoker) {
  public static RunRequest of(String workflow, Map<String,String> inputs);
  public RunRequest withRunId(String id); public RunRequest as(InvokerCredentials invoker); public RunRequest origin(String o);
}

public sealed interface RunOutcome {
  String runId();
  record Completed(String runId, Map<String,Object> variables) implements RunOutcome {}
  record Suspended(String runId, RunSuspended.Reason reason, Instant resumeAt, String stepId, String prompt) implements RunOutcome {}
  record Stopped(String runId, String detail) implements RunOutcome {}
}
// a failure throws LoomRunFailedException(runId, cause)
```

v1's `run(String, Map)` and `start(String, Map)` stay as one-line overloads.

**Executor assembly** (`ExecutorAssembler`, the single place that knows every setter, in this order):

1. `new HarnessExecutor(script, loomTools, models)`
2. `setBaseDir(scriptDir)`, `setScriptRef(name)`, `setRunId(runId)`, `setRunLocator(...)` (durable)
3. `setSecretStore`, `setEnvLookup(environment::getProperty)`, `setClock`, `setSleeper`
4. `setToolReach(loot reach)`, `setTaskRegistry`, `setPromptRegistry`, `setPriceTable`, `setTokenEstimator`,
   `setBudgetOverrides`, `setContextWindows`, `setEmbeddingFactory`, `setMemoryEngine` (bean), `setAuditLogger`
5. durable: `setJournal(journals.forRun(runId))`, `setTriggerStore(runTriggerStore)`, `setAutonomy(ledger, levels)`
6. `setHumanInterface(humanInterfaces.forRun(runId))`
7. metrics `addTraceListener(...)`; observe `session.attach(executor.observable(), scriptFile)`
8. `LoomExecutorCustomizer`s (in `@Order`)
9. `initialize()`

Each step is skipped when its bean is absent, so a minimal app gets exactly v1's behaviour.

**Threads (Req 16.5–16.6).** `start` submits to a `TaskExecutor` (`SimpleAsyncTaskExecutor` with virtual threads,
a bounded `ThreadPoolTaskExecutor`, or `applicationTaskExecutor`) behind a `Semaphore(max-concurrent-runs)`.
`LoomRun` today creates its own platform thread; the library gains `LoomRun.start(executor, label, workflow, inputs,
origin, invoker, java.util.concurrent.Executor)` so the starter supplies the thread and the invoker (Req 16.3) and is
notified on completion (to `shutdown()` the executor and release the permit). The existing `start` overloads keep
their behaviour.

**Shutdown (Req 16.8).** `LoomRuntimes` is a `SmartLifecycle` with a late phase: `stop()` calls `wrapUp` on every live
run, waits `shutdown-grace`, then `cancel` (non-durable) or suspend by operator (`RunSuspended.byOperator`, durable).

### 4.2 Scripts inside jars (Req 15.2–15.3, library change)

ai-agent4j-loom gains:

```java
public interface ScriptSource {                      // io.github.llm4j.loom.execution
  String read(String location) throws IOException;   // the script text
  String resolve(String from, String imported);       // an import relative to the importing script
  default Optional<Path> directory(String location) { return Optional.empty(); }   // baseDir when on disk
  static ScriptSource fileSystem();                  // today's behaviour
}
public LoomScript LoomLoader.load(String location, ScriptSource source) throws IOException;   // new overload
public void LootLoader.loadIntoRegistry(Reader loot, ToolRegistry registry);                 // new overload
public static Map<String,String> LootLoader.reaches(Reader loot);                            // new overload
```

`load(String filePath)` becomes `load(filePath, ScriptSource.fileSystem())`. The starter implements `ScriptSource` over
Spring's `ResourceLoader` (`Resource.createRelative`), with circular-import detection keyed by the resource URL. The
`.loom` parse-test and `RepositoryScriptsTest` are unaffected. `docs/API_COMPATIBILITY.md` lists the additions.

For a script in a jar the run's `baseDir` (used by `file` tools, prompts folder, knowledge sources) is
`llm4j.loom.scripts.<name>.base-dir` (default `llm4j.loom.persistence.dir`, else the working directory); the README
states this and validation reports a script whose relative paths cannot resolve.

### 4.3 Validation and security gate (Req 15.4, 21)

At `ContextRefreshedEvent`-independent bean creation of each `LoomRuntime`:

1. `ScriptValidator.validateOrThrow(script, context)` with `registeredTools(loomTools.names())`, `tasks(taskRegistry)`,
   `env(environment::getProperty)`, `prompts(catalog)`, `baseDir(dir)`, `humanInterface(present)`,
   `lenient(validate == lenient)`. Errors fail start-up with all problems; warnings are logged.
2. `SecurityAudit.audit(script, name, lootReach)`; `report.failsAt(threshold)` → fail with `report.markdown()`.
3. Durability guard (Req 17.6, 20.2): `EffectScan`/`StatementWalker` finds `human_prompt`, `approve:`, `sleep`,
   `rate_limits`, windowed budgets, `decision`/`ladder`; with persistence `none` it fails unless `allow-non-durable`.

### 4.4 Persistence (Req 17, 20)

```java
interface RunJournals { RunJournal forRun(String runId); }        // file: FileRunJournal(dir/runs/<id>/journal.json)
                                                                  // jdbc: new JdbcRunJournal(ds, runId)
interface RunStore {                                              // new, in the starter
  void save(RunRecord r);  Optional<RunRecord> get(String runId);  List<RunRecord> all(); void update(String runId, Status s, …);
}
record RunRecord(String runId, String script, String workflow, Map<String,String> inputs, String origin,
                 Status status, RunSuspended.Reason reason, Instant resumeAt, String stepId, Instant created, Instant updated) {}
```

- File `RunStore` writes `runs/<id>/run.json` (the same directory `weave` uses, so `weave timeline`/`weave resume`
  can read a starter run's journal by path).
- JDBC `RunStore` uses table `llm4j_loom_runs`; DDL ships as a constant in the starter, created the same way as the
  library tables, by `Llm4jSchemaInitializer` (`initialize-schema`, using `DatabaseInitializationMode` semantics) which
  calls `JdbcRunJournal.createTable`, `JdbcTriggerStore.createTable`, `JdbcLedger.createTable`,
  `JdbcLevelStore.createTable` and the RunStore DDL.
- Inputs are stored; invoker credentials never (Req 17.3). Inputs are application data: the README warns not to pass
  secrets as inputs.

**Answering (Req 17.5).** `answer` checks the RunRecord is `SUSPENDED` with reason `HUMAN` and `stepId` matches, then
`journal.put(stepId, new RunJournal.Entry("human", text))` (the form `ApprovalGate` and `BudgetCoordinator` read), audits
`answered` with `by`, sets the record to `RESUMING`, and `triggerStore.upsert(Trigger.resume(runId, now, "answered", 0))`.
Choices are matched with `Answers.match`. When triggers are disabled, `answer` resumes asynchronously itself.
Verification task: confirm against `human_prompt` and `approve:` replay that this key and kind are exactly what they
read (task 7.3); if a step uses a different key, add a small public `RunJournal`-level helper in ai-agent4j-loom
(`HumanAnswers.record(journal, stepId, answer)`) and use it from both the CLI and the starter.

### 4.5 Human interface (Req 18)

`SuspendingHumanInterface implements HumanInterface`: `promptHuman(stepId, message, hints)` → if the journal already holds
an answer for `stepId`, return it; else publish `HumanQuestionEvent(runId, stepId, message, hints.choices())` to
`HumanQuestionListener` beans and the `ApplicationEventPublisher`, and throw `new RunSuspended(stepId, message)`.
With `ask-via`, `Channels.open(dir, askVia, env, clock)` gives a `Runtime`; its `humanFor(runDir, runId)` is used instead,
and `listen(...)` runs in a `SmartLifecycle` thread with the library's backoff.

### 4.6 Triggers (Req 19)

- `LoomTriggerTarget implements TriggerTarget`: `ResumeRun` → `runtimes.forRun(runId).resume(runId, null)` (the RunStore
  says which script); `StartWorkflow(script, workflow, args)` → `runtimes.get(script).run(...)`; `AgentTask` →
  `newExecutor` with an `InMemoryTriggerStore` then `runAgentTask(agent, task)` (as `WeaveTriggerTarget`). A run that
  needs invoker credentials cannot be resumed by a trigger: the outcome is `human("needs the person's credentials")`
  and the RunRecord says so.
- `TriggerRunner(store, target, clock, owner).staleAfter(d).onFired(publisher::publishEvent)`, `start(pollInterval)` /
  `stop()` from a `SmartLifecycle`.
- Schedule registration (Req 19.3): at start-up, for each script, one executor is initialized with the persistent
  trigger store (this writes its `schedule` triggers, idempotent upserts by id) and shut down; run executors get the
  persistent store only for resume triggers. Task 9.2 verifies the library's split between schedule and resume
  registration and, if executors re-register schedules on every `initialize()`, adds
  `HarnessExecutor.setRegisterSchedules(boolean)` (default `true`) in ai-agent4j-loom.
- Endpoint: `TriggerEndpoint(runner, () -> secret("LOOM_TRIGGER_TOKEN"), bearerVerifier)`, adapted to a
  `RouterFunction` (MVC) / `RouterFunction` (WebFlux) that passes method and headers and returns `Response.status/body`.

### 4.7 Observe (Req 22)

`ObserveSessionLifecycle` opens `Observe.open(options, logPrintStream)` in `start()`, closes it in `stop()`.
`ObserveOptions(port, bind, detail, name, linger, embedded=true, fabric, otlp).withRules(file)`. The `PrintStream` is
an SLF4J-backed stream (one log line per println). Bind other than loopback requires `LOOM_INSPECT_ADMIN_PASSWORD` in the
secret store (checked before open).

---

## 5. Agents (Req 8–11)

```java
@Bean @Scope("prototype") ReActAgent.Builder reActAgentBuilder(...)    // defaults (llm4j.agent.*)
@Bean Agents agents(ObjectProvider<…> …)                               // llm4j.agents.<name>
@Bean AgentSessions agentSessions(Agents agents, ConversationStore store)   // only with a ConversationStore
```

`AgentBuilderAssembler.assemble(AgentProperties merged)`:

```
ReActAgent.builder()
  .llmClient(merged.client == null ? primary : clients.get(merged.client))
  .addTools(tools.select(merged.tools))           // all when unset; unknown name → fail
  .systemPrompt | .promptRegistry(reg).systemPromptId(id) | .instructions
  .persona(personas.get(merged.persona)) .skills(skills.select(merged.skills))
  .maxIterations .temperature .toolCalling .streaming .reasoning .validateToolArguments
  .contextPolicy | .contextWindow | .contextWindows  .compaction(ContextCompactors.byName(name, opts, providerContext))
  .budget(budget) .priceTable(prices) .tokenEstimator(est) .maxTokensPerCall .onBudgetExhausted
  .auditLogger .biasMonitor .approvalCallback .effectClassifier .clock
  .addListener(each AgentEventListener + metrics listener) .addRunHook(each) .addToolHook(each, ordered)
  .semanticMemory(service)
→ ReActAgentBuilderCustomizer beans
```

`AgentSessions.agent(name, sessionId)` adds `.conversationHistory(new ConversationHistory(sessionId, store, maxHistory))`.

## 6. Tools (Req 12)

`ToolCatalog` (bean) collects, in this order, and fails on a duplicate name naming both sources:
`Tool` beans → `llm4j.tools.builtin` → `llm4j.tools.<name>` kinds → MCP tools → `.loot` tools (Loom registry only).
The core `ToolRegistry` and each script's Loom `ToolRegistry` are filled from it.

Kinds: `Map<String, ToolKind>` = `{webhook: new WebhookKind(), email: new EmailKind(), http: new HttpKind(),
file: new FileKind(), shell: new ShellKind(), sql: new SqlKind()}` plus any `ToolKind` bean (so applications add kinds).
`check(options, baseDir)` then `create(name, options, baseDir, effectContext)` when an `EffectContext` bean exists, else
`create(name, options, baseDir)`.

MCP: `McpServerLifecycle` (`SmartLifecycle`, early phase): for each server, `new McpClient(new StdioMcpTransport(command + args, env))`,
`initialize()`, `listTools()`, filter by `tools` allow-list, `new McpToolAdapter(client, def)`; close on stop. Because the tool
list is known only after the process starts, MCP tools are registered in `start()`; the Loom runtimes validate after the
MCP lifecycle phase (they validate in their own `start()`), which is why Loom validation runs in `SmartLifecycle.start`
rather than in bean creation when MCP servers are configured.

## 7. Metrics, health, endpoint (Req 23)

- `MetricsAgentListener implements AgentEventListener` (`onModelCall`, `onBudget`, `onCompaction`, tool events) and
  `MetricsTraceListener implements TraceListener` for Loom: registered on every builder/executor when a `MeterRegistry`
  exists. Tags: `client`, `provider`, `model`, `outcome`, `tool`, `script`, `workflow`, `reason`, `status`. No free text.
- `Llm4jHealthIndicator`: per client, `provider` and `keyPresent` (`SecretRef.exists()`/non-blank), never the key.
- `LoomHealthIndicator`: scripts valid; journal/trigger store reachable (a `SELECT 1` on JDBC or directory writable);
  runner running; `overdue = due(now - threshold).size()`.
- `@Endpoint(id = "loom")` read operations only: `scripts`, `runs`, `runs/{id}`, `audit/{script}` (JSON from
  `AuditReport.json()`).
- `ObservationRegistry`: `ObservedLLMClient` decorator (innermost of the customizers) and an observation per run.

## 8. Run-control web API (Req 24)

`LoomRunController` (MVC) / `LoomRunHandler` (WebFlux), mounted at `llm4j.loom.web.path`:

| Method | Path | Does |
|---|---|---|
| GET | `/runs` | `runs()` (+ `LiveRuns.shared().all()` labels) |
| GET | `/runs/{id}` | one RunRecord |
| POST | `/scripts/{script}/workflows/{workflow}` | `start` with JSON inputs; origin = principal |
| POST | `/runs/{id}/answers/{step}` | `answer` |
| POST | `/runs/{id}/steer` · `/wrap-up` · `/pause` · `/resume` · `/cancel` | `LoomRun` / executor control, origin = principal |

`LoomWebSecurityGuard` (`SmartInitializingSingleton`): with Spring Security present, contributes a `SecurityFilterChain`
for the path (authenticated, `hasAuthority(authority)` for POST) at an order before the application's default chain, and
backs off if the application defines one matching the path; without Spring Security, fails unless `allow-unsecured`.

## 9. Test support (Req 25)

- `@AutoConfigureLlm4jMock` imports `Llm4jMockAutoConfiguration`: `@Primary ScriptedClient llm4jScriptedClient()`,
  replaces `LLMClients` entries with the same scripted client (or one per name via `Llm4jMocks.client(name)`), and
  `@Primary LLMClientFactory` = `MockModels`. Persistence defaults to in-memory journal/trigger stores; `Clock` is a
  `MutableClock` bean the test can advance.
- `@AutoConfigureLlm4jLive`: JUnit `ExecutionCondition` skips when keys are missing; each client wrapped by
  `SpendGuard.withPrices(priceTable)` stopping at `llm4j.test.max-spend`.
- `@Llm4jTest`: `@BootstrapWith` slice including only `Llm4j*AutoConfiguration` + `@AutoConfigureLlm4jMock`.
- Replay: `AgentReplay.at(dir)` wrapping the primary client in `record`/`replay` mode.

---

## 10. Compatibility and risks

| Topic | Decision |
|---|---|
| v1 API | `LoomRuntime.run/start(String, Map)` and all v1 properties keep working (shorthands). v1 was never released, so no deprecation cycle is needed; shorthands stay because they are the simplest form. |
| Library changes | Only additive: `ScriptSource` + `LoomLoader.load(location, source)`, `LootLoader` Reader overloads, `LoomRun.start(..., invoker, Executor)`, possibly `setRegisterSchedules` and `HumanAnswers`. Each is usable without Spring and listed in `API_COMPATIBILITY.md`. |
| Two `ToolRegistry` types | Kept: the core one is the `ToolRegistry` bean; Loom's are per script, inside each `LoomRuntime`, and not beans (avoids a second bean of a confusingly named type). v1's `loomToolRegistry` bean is removed; `LoomRuntime.tools()` exposes it. |
| Multiple replicas | Trigger claims (`claim/complete` with owner and stale-after) make firing once-only; `LiveRuns` and run control are per process, so the web API answers only for runs in this replica (documented; Fabric gives the fleet view). |
| JDBC on any database | Library DDL targets PostgreSQL and H2; MySQL/Oracle users set `initialize-schema=never` and create tables themselves (documented with the DDL constants). |
