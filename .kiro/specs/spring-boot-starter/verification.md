# Verification Plan: Spring Boot Starter

The work is done when every check below passes in an automated test, the gates under *Completion* pass at one commit, and their
evidence is committed under `evidence/`. A test carries its check as `@Tag("SB-V3.1")`, so
`scripts/verify_spec.py .kiro/specs/spring-boot-starter SB` proves every check has a passing test and no test has an unknown id.
Checks marked *(gate-checked: Gn)* are proved by a gate script. `LoomHost` checks (V8) live in `ai-agent4j-loom`; the rest in the starter.

## Fixtures and stand-ins

| Stand-in for | What |
|---|---|
| Context | `ApplicationContextRunner` with the starter's auto-configurations and only the properties the check names |
| Models | `RecordingScriptedClient`: answers per call from a list, records every request; "no model call" and "called again" are assertions on its count |
| Loom models | an `LLMClientFactory` bean returning scripted clients per model name |
| Provider HTTP | `MockWebServer` per provider; its request count proves "nothing was sent" |
| Effects | a `webhook` tool pointed at a second `MockWebServer`; its count proves "sent once" |
| Secrets | `InMemorySecretStore` holding the canary `sk-canary-7f3a9c41` |
| Database | H2 in PostgreSQL mode, fresh per test |
| Time | `MutableClock` at **2026-10-15T09:00:00Z** and a recording `Sleeper` |
| Files | `@TempDir` |

Scripts (`src/test/resources/scripts/`): `three-steps.loom` (research → draft → review), `approve.loom` (`delegate`, `human_prompt "Publish? (yes/no)" -> ok`, then a webhook post),
`sleepy.loom` (`delegate`, `sleep 1h`, `delegate`), `trifecta.loom` and `trifecta-accepted.loom`, `scheduled.loom` (a daily 07:00 `schedule` and workflow `Digest`),
`jar/main.loom` importing `jar/lib/agents.loom` (packaged into `target/test-scripts.jar`), `broken.loom` (three independent errors), `tasks.loom` (`run Refund(...) -> verdict`), `ladder.loom`.

---

## V0: Zero-magic (ZM1–ZM7)

| ID | Given | Then |
|---|---|---|
| V0.1 | empty context | llm4j beans are exactly the empty `ToolRegistry` and the properties; no `LLMClient`, `LLMClients`, `ReActAgent.Builder`, `SecretStore`, `Budget`, `LoomRuntime`, `ConversationStore`, `VectorStore` |
| V0.2 | for each capability: its minimum properties, then the same minus its enabling property | its bean present, then absent (the case list is generated from the auto-configuration classes, so a new capability without a case fails) |
| V0.3 | for each bean type the starter defines: a user bean of that type | exactly one bean of the type, the user's |
| V0.4 | every property that does not start work set together | provider MockWebServers received 0 requests; no child process; no new listening port |
| V0.5 | `triggers.enabled`, then `observe.enabled`, each alone | exactly that one effect (a thread named `llm4j-trigger-runner`, then one listening port) |
| V0.6 | `mvn dependency:tree` of every library module | no `org.springframework` artifact *(gate-checked: G4)* |
| V0.7 | a plain-Java `main` built from the README's "Without Spring" blocks | passes the same end-to-end assertions as the Spring context for client, agent, budget and Loom run |

## V1: Packaging and guards (R1)

| ID | Given | Then |
|---|---|---|
| V1.1 | the built POM | `ai-agent4j-loom`, `-tools`, `-addons`, `loom-inspect`, `spring-jdbc`, the configuration processor are `optional`; no Spring Boot BOM import |
| V1.2 | `llm4j.loom.script` set with `FilteredClassLoader("io.github.llm4j.loom")` | fails; action names `ai-agent4j-loom` |
| V1.3 | `llm4j.rag.store=pgvector` without addons; `llm4j.observe.enabled=true` without loom-inspect; a tool `kind` without tools; `persistence=jdbc` without a `DataSource` | each fails naming `ai-agent4j-addons`, `loom-inspect`, `ai-agent4j-tools`, `DataSource` |
| V1.4 | `llm4j.provider=ollama`, `llm4j.model=llama3`, `llm4j.loom.script=…` | `LLMClients.names()` = `[default]`; a `LoomRuntime` exists |
| V1.5 | `llm4j.provider` and `llm4j.clients.default.provider` both set | fails naming both |
| V1.6 | each auto-configuration class excluded in turn | the context starts; only its beans are missing |
| V1.7 | the Jackson version on the starter's test class path | equals the parent POM's `jackson.version` |

## V2: Secrets (R2)

| ID | Given | Then |
|---|---|---|
| V2.1 | `store=env`, property `GEMINI_API_KEY=<canary>` | the store returns the canary |
| V2.2 | `store=encrypted-file` with the master key from env, then from file | opens and returns the canary |
| V2.3 | both master-key sources set; neither set | fails naming both / naming `llm4j.secrets.master-key-env` |
| V2.4 | `store=chained`, `chain=[encrypted-file, env]`, name in both with different values | the encrypted-file value wins |
| V2.5 | starter-created store; user-supplied store | on close the first is closed, the second is not |
| V2.6 | `api-key-secret` and the store value changed between calls 1 and 2 | MockWebServer saw `v1` then `v2` |
| V2.7 | secret bound to host `api.anthropic.com`, client `base-url` a MockWebServer | `AuthenticationException`; the server received 0 requests |
| V2.8 | `api-key` and `api-key-secret` both set | fails naming both |
| V2.9 | `api-key-secret` with no store | fails naming `llm4j.secrets.store` |
| V2.10 | canary as literal `api-key`; start-up log, `/actuator/env`, `/actuator/configprops`, `toString()`, a forced provider error | the canary appears in none of them |

## V3: Clients, routing, retries (R3)

| ID | Given | Then |
|---|---|---|
| V3.1 | one client per provider, each `base-url` a MockWebServer | each hits only its own server on that provider's request path |
| V3.2 | clients `default`, `cheap`, `strong` | `names()` = `[default, cheap, strong]`; one `LLMClient` bean |
| V3.3 | `primary=strong` | requests reach `strong`'s server |
| V3.4 | `primary=missing` | fails listing the three names |
| V3.5 | `routing.strategy=fallback` over `[cheap, strong]`; `cheap` returns 503 | the answer is `strong`'s; each received 1 request (retry off) |
| V3.6 | `cost-aware`; a request with a low `ComplexityHint`, then a high one | low → `cheap`, high → `strong` |
| V3.7 | no client | no `LLMClient`, no `LLMClients` |
| V3.8 | `provider=gemini` without a key; `provider=openai` | fails naming `api-key-secret`; fails listing the four providers |
| V3.9 | `retry.max-retries=2`, `initial-backoff=1s`; server 500, 500, 200 | 3 requests; recorded sleeps 1 s and 2 s |
| V3.10 | `retry.enabled=false`; server 500 | 1 request |
| V3.11 | `inline-wait-threshold=30s`; 429 `Retry-After: 5` then 200; then `Retry-After: 3600` | 2 requests with a recorded 5 s wait; then 1 request and `RateLimitException` with resetAt **2026-10-15T10:00:00Z** |
| V3.12 | no retry properties | the `RetryPolicy` equals `RetryPolicy.defaultPolicy()` field by field |
| V3.13 | `Clock` and `Sleeper` beans | V3.11 used them |
| V3.14 | two `LLMClientCustomizer`s at `@Order(2)` and `@Order(1)` | the primary is `c2(c1(decorated))` |
| V3.15 | building any client | 0 requests to its server |

## V4: Budgets, privacy, audit (R4)

| ID | Given | Then |
|---|---|---|
| V4.1 | prices `3`/`15` per million; one call of 1 000 000 in / 100 000 out | `Budget.spent().cost()` = 4.50 (inline and file forms) |
| V4.2 | `budget.tokens=1000`; calls of 400 tokens | calls 1–2 succeed; call 3 refused before reaching the client |
| V4.3 | `budget.cost="1.00"` with no prices | fails naming `llm4j.pricing` |
| V4.4 | `window=hour`, exhausted at 09:20 | refused until 09:59:59; accepted at 10:00:00 |
| V4.5 | a `BudgetListener` bean, `warn-at=0.5`, spend crosses 50% | exactly one warning event |
| V4.6 | failover (V3.5 shape) under a budget | budget `calls` = 1 for the logical call |
| V4.7 | `mask-pii=true`, prompt with an email and a phone | the provider's request body has neither, only placeholders |
| V4.8 | V4.7 under a budget with a recording `TokenEstimator` | the estimator saw masked text |
| V4.9 | `privacy.types=[EMAIL]` | the phone reaches the server, the email does not |
| V4.10 | `audit.file` and an agent run with one tool call, then a Loom run | the file holds the tool-call event and the Loom run's events |
| V4.11 | a script with its own `audit` block; a user `AuditLogger` bean | the script's events go to its file; the bean receives events and no `FileAuditLogger` is made |

## V5: Agents (R5)

| ID | Given | Then |
|---|---|---|
| V5.1 | 2 `AgentEventListener`s, 2 `RunHook`s, `ToolHook`s at `@Order(2)`/`(1)`, an `ApprovalCallback`; a run calling a tool that requires approval | listeners saw the events; hooks ran in order 1 then 2; the callback was asked once |
| V5.2 | `agent.tools=[calculator]` with `calculator` and `echo` registered | only `calculator` is offered; `[nope]` fails listing the registered names |
| V5.3 | `agent.client=cheap` | the agent's calls reach `cheap` |
| V5.4 | `agent.system-prompt-id=triage` with `prompts.location=classpath:prompts/` | the system message equals `triage.md`'s body |
| V5.5 | a `ReActAgentBuilderCustomizer` setting `maxIterations(2)` and `agent.max-iterations=8` | the agent stops after 2 |
| V5.6 | two builders from the provider; two `build()`s | four distinct objects; one agent's exchange is absent from the other's requests |
| V5.7 | the context | no bean of type `ReActAgent` |
| V5.8 | `context.window=2000`, `compaction=drop-oldest`, a 30-exchange conversation | the last request estimates ≤ 2000 tokens and a compaction event fired |
| V5.9 | `compaction=summarize`, `compaction-client=cheap` | the summary request went to `cheap` |
| V5.10 | `compaction=trim-tool-results` with `max-chars=200` and a 5 000-char tool result | the next request carries ≤ 200 chars of it; a `ContextPolicy` bean wins over properties |
| V5.11 | `memory.conversations=file`; context A: an exchange under `ConversationHistory("s-1", store, max)`; close; context B on the same directory | B's history for `s-1` has the exchange; `s-2` has none; `max-history=4` keeps the last 4 |
| V5.12 | `prompts.location` as a folder and as a YAML file | `MarkdownFolderPromptRegistry` and `FileSystemPromptRegistry`, each returning the expected template |
| V5.13 | `skills.locations` with a classpath and a file folder | skills load; a remote URL without `allow-remote` fails naming it and makes no request |

## V6: Tools and tasks (R6)

| ID | Given | Then |
|---|---|---|
| V6.1 | `tools.Slack.kind=webhook` with `url-secret` and `format: slack` | executing the tool makes one POST with Slack's body to the stand-in |
| V6.2 | a kind whose `check` rejects its options | start-up fails with `check`'s message and `llm4j.tools.<name>` |
| V6.3 | one valid tool of each kind (`webhook`, `email`, `http`, `file`, `shell`, `sql` on H2) | each executes once against its stand-in |
| V6.4 | a `ToolKind` bean named `pager` and `tools.Page.kind=pager` | the bean created the tool |
| V6.5 | `tools.builtin=[calculator, current-time]`; none; `[nope]` | those two; none; fails listing the valid names |
| V6.6 | a `Tool` bean `Slack` and `tools.Slack.kind=webhook` | fails naming both sources |
| V6.7 | one tool from each source | each is in the core registry (except `.loot`) and in the Loom registry |
| V6.8 | an `EffectContext` with a durable journal; the webhook tool called twice with the same arguments under one step | the stand-in received 1 request |
| V6.9 | a `Task` bean `Refund`, `tasks.loom` | `verdict` carries the bean's result; called once; not called again on a replay |
| V6.10 | two `Task` beans named `Refund`; `tasks.discover=false` with a task in `META-INF/services`; a script naming `NotABean` | fails naming both; the discovered task is absent; validation fails naming `NotABean` |

## V7: RAG (R7)

| ID | Given | Then |
|---|---|---|
| V7.1 | `rag.embedding=gemini` (MockWebServer), `rag.store=memory`, then `file` | the beans exist; add then search returns the added id first; the file exists |
| V7.2 | `rag.store=pgvector`, `spring.datasource.*` set | the store is constructed with those URL, user and password (captured; no connection attempted) |
| V7.3 | `password-secret`; `pinecone` with `api-key-secret` | built through `PGVectorStore.withSecret` / `PineconeVectorStore.withSecret` |
| V7.4 | an `EmbeddingFactory` bean and a script with `knowledge` | indexing went through the bean |
| V7.5 | a real pgvector round trip in a container | add, search, delete work *(`@Tag("integration")`, run in the extended pass)* |

## V8: LoomHost (library, `ai-agent4j-loom`)

Each check runs on the persistence matrix: `file` and `jdbc` (H2), except where noted. These tests use no Spring.

| ID | Given | Then |
|---|---|---|
| V8.1 | `run(RunRequest.of("Main", {topic}))` on `three-steps.loom` | `Completed`; 3 model calls; variables `research`, `draft`, `review` |
| V8.2 | `approve.loom` | `Suspended(HUMAN)` with step id and prompt; no exception; RunRecord `SUSPENDED` |
| V8.3 | an operator cancel during step 2 | `Stopped` |
| V8.4 | step 2's model throws `ProviderException` | `LoomRunFailedException` whose `runId()` equals the record's id |
| V8.5 | `start` in each of completed / suspended / stopped / failed | the executor's `shutdown()` ran exactly once every time |
| V8.6 | `start` | returns before the first model call (the scripted client waits on a latch); runs on the given `Executor` |
| V8.7 | a script with an `http` tool using `invoker.token`; credentials returning `Bearer u1` | the stand-in saw `Authorization: Bearer u1` |
| V8.8 | credentials that do not provide `token` | `MissingInvokerCredentialException` before the first step; 0 model calls |
| V8.9 | a run with inputs and invoker credentials | the store holds script, workflow, inputs, origin; the canary credential appears nowhere in the store |
| V8.10 | `approve.loom`: suspend; `answer(id, step, "yes", "asha")` | the run resumes and completes; `Writer` was called once in total; the webhook stand-in got 1 post; audit has `answered` with `by=asha` |
| V8.11 | V8.10 with the host closed after the suspension and a new host opened on the same store before the answer | same result as V8.10 |
| V8.12 | `answer` with a wrong step id; on a completed run; twice | refused: "not waiting at <step>"; "not suspended"; "already answered by asha" |
| V8.13 | choices `yes/no`; answer `Ye`; answer `maybe` | recorded as `yes`; refused listing the choices |
| V8.14 | `sleepy.loom`: suspend; clock to the wake time; a trigger tick | completes; the first `delegate` not called again |
| V8.15 | a rate-limit suspension (scripted 429, reset 09:30) | `Suspended(RATE_LIMIT, 09:30)`; resumes at 09:30 |
| V8.16 | a question with a `QuestionListener` | notified once with run id, step id, prompt and choices; none on the replay after the answer |
| V8.17 | a user `HumanInterface` | used instead of the suspending one |
| V8.18 | persistence `file`: a host run | `RunSpec.read` and `RunTravel.timeline` read `<dir>/runs/<id>` (unit test); `weave resume` and `weave timeline` open it *(gate-checked: G7)* |
| V8.19 | `scheduled.loom`; clock 06:59 → 07:00 | `Digest` started once with the library's `<schedule>@<slot>` run id |
| V8.20 | two hosts with different owners on one H2 store, both polling at 07:00 | `Digest` ran once in total |
| V8.21 | the host restarted 3 times, and a run made in between | the schedule trigger exists once in the store |
| V8.22 | a `ResumeRun` trigger for a run that needs credentials | outcome "needs the person's credentials"; nothing ran |
| V8.23 | an `AgentTask` trigger | the agent ran the task; no run record was made; the store still holds one schedule |
| V8.24 | `jar/main.loom` with an import, loaded from `target/test-scripts.jar` on a `URLClassLoader` | the imported agents are present; a run completes; no temporary file is created for the `.loot` |
| V8.25 | `lib/agents.loom` importing `../main.loom` | the library's circular-import error naming the script |
| V8.26 | `LoomLoader.load(String)` and `load(path, ScriptSource.fileSystem())` on every `.loom` in the repository | identical `LoomScript`s |
| V8.27 | `createSchema(ds)` twice | creates the host's tables (journal, triggers, ledger, levels, runs, answers) once; the second call is harmless |
| V8.28 | persistence `none` with `approve.loom`; with `allowNonDurable` | construction fails naming persistence; with the flag, a suspension fails the run saying it cannot resume |
| V8.29 | every existing `ai-agent4j-loom` test | passes unchanged *(gate-checked: G9)* |
| V8.30 | a script with an unknown agent and an unregistered task; `host.validate()` | one `LoomLoadException` naming both; nothing is written to the store |

## V9: Loom in Spring (R9)

| ID | Given | Then |
|---|---|---|
| V9.1 | `llm4j.loom.script=classpath:scripts/three-steps.loom` | a `LoomRuntime`; `run` completes; Loom tools = the Requirement 6 tools |
| V9.2 | the script and its import loaded from the test jar; a `.loot` in the jar | works as V8.24 through Spring `Resource`s |
| V9.3 | an `LLMClientFactory` bean; none, with the key only in the secret store; none, with the key only in the `Environment`; neither | the bean is used; the store key is used; the environment key is used; start-up fails naming `GEMINI_API_KEY` |
| V9.4 | `broken.loom` | fails once listing all three errors with line numbers; with `validate=lenient`, starts with three WARN lines |
| V9.5 | `trifecta.loom` | fails; the failure text is the report's Markdown and names the trifecta rule |
| V9.6 | `trifecta-accepted.loom`; `fail-on=none` on `trifecta.loom`; `fail-on=medium` vs `high` on a MEDIUM-worst script | starts; starts with a WARN per finding and one "gate is off"; fails / starts |
| V9.7 | the properties metadata | no property names an audit rule id |
| V9.8 | `approve.loom` with persistence `none`; with `allow-non-durable=true`; `ladder.loom` with `none` and the flag | fails naming `llm4j.loom.persistence` and the line; starts and a suspension fails the run; fails |
| V9.9 | `executor=platform`; `application`; `virtual-threads` on JDK 21 (`@EnabledForJreRange(min = JAVA_21)`) | the run's thread is a platform thread named `llm4j-loom-*`; an `applicationTaskExecutor` thread; a virtual thread |
| V9.10 | `max-concurrent-runs=2` and three blocked runs | the third `start` throws `TooManyRuns`; after one ends, `start` succeeds |
| V9.11 | context closed with one durable and one non-durable run blocked, `shutdown-grace=2s` | both are wrapped up; after the grace the durable run is suspended (record `SUSPENDED`), the other cancelled; one INFO line per run |
| V9.12 | a `LoomExecutorCustomizer` recording what was already set | it runs after every starter setter and before `initialize()` |
| V9.13 | `loom.budget.tokens=500`, a `PriceTable`, `PromptRegistry`, `AuditLogger`, `Clock` beans | each reached the executor (checked through a customizer); the run stops at 500 tokens |
| V9.14 | `loom.context.windows-files` giving `gemini-2.5-flash` 8 000 tokens | that window is used for the model; others come from the catalogue |
| V9.15 | v1 overloads `run("Main", map)` and `start("Main", map)` | behave as V8.1 and V8.6 |

## V10: Persistence, resume, people (R10)

| ID | Given | Then |
|---|---|---|
| V10.1 | `persistence=file` | after a run: `runs/<id>/journal.json` and the spec file exist; `triggers/` exists |
| V10.2 | `persistence=jdbc`, `initialize-schema=embedded` on H2 | the five tables exist; `never` on an empty database fails naming the missing table |
| V10.3 | two `DataSource` beans and `datasource=loomDataSource` | tables only in that one |
| V10.4 | `approve.loom` through the Spring `LoomRuntime`: suspend, `answer`, trigger tick | as V8.10, and again after a context restart between suspend and answer |
| V10.5 | a question | a `HumanQuestionEvent` is published once and `QuestionListener` beans are told; none after the answer |
| V10.6 | a user `HumanInterface` bean | used instead |
| V10.7 | a run started with credentials, then suspended and resumed with new credentials | the second set is used; the first is not stored |

## V11: Triggers (R11)

| ID | Given | Then |
|---|---|---|
| V11.1 | `triggers.enabled=true`, `poll-interval=50ms` | a runner thread polls; on close it stops within 1 s, before running executors are shut down |
| V11.2 | `triggers.enabled=true` with `persistence=none` | fails naming `llm4j.loom.persistence` |
| V11.3 | `scheduled.loom` under the mutable clock | `Digest` starts at 07:00 once; a `TriggerFiredEvent` is published |
| V11.4 | two contexts on one H2 store with different owners | `Digest` ran once in total |

## V12: Autonomy and observation (R12)

| ID | Given | Then |
|---|---|---|
| V12.1 | `ladder.loom` with `persistence=file`, then `jdbc` | file then JDBC ledger and level store are set; a decision's record survives a context restart |
| V12.2 | user `Ledger` and `LevelStore` beans | used instead |
| V12.3 | `observe.enabled=true`, `port=0`, `name=svc`, `rules-file=…` with a recording `ObserveProvider` on the test class path | `open` called once with those options |
| V12.4 | two runs | `attach` called twice, each before that executor's `initialize()`, with the script's path |
| V12.5 | `bind=0.0.0.0` without `LOOM_INSPECT_ADMIN_PASSWORD`; with it | fails naming the secret; starts |
| V12.6 | context close | the session is closed once |
| V12.7 | the real loom-inspect on the class path | the page answers on the chosen port and lists a running run *(gate-checked: G7)* |

## V13: Metadata and documentation (R13)

| ID | Given | Then |
|---|---|---|
| V13.1 | the configuration metadata | every property has a non-empty description; every enumeration property has hints matching the accepted values |
| V13.2 | the starter README | each capability section has a YAML block that binds to the properties classes, a "Bean" line naming a type that exists, and a "Without Spring" Java block that compiles (extracted by the test); the deferred list is present |
| V13.3 | `docs/API_COMPATIBILITY.md` | lists `LoomHost`, `RunRequest`, `RunOutcome`, `RunRecord`, `ScriptSource` and the new overloads |

## V14: Quality (R14)

| ID | Check |
|---|---|
| V14.1 | No default-build test opens a non-loopback connection (a JUnit extension fails the test that tries) |
| V14.2 | Coverage thresholds met *(gate-checked: G3)* |
| V14.3 | JDK 17 and 21 × Spring Boot baseline and latest pass *(gate-checked: G8)* |

---

## Live checks

`-Plive`, each with a spend cap of 0.25; skipped without keys.

| ID | Check |
|---|---|
| L1 | Each provider client answers a one-line prompt through the primary bean |
| L2 | `three-steps.loom` completes against `gemini-2.5-flash` through the Spring `LoomRuntime` |
| L3 | `approve.loom` suspends, is answered and completes with `persistence=jdbc` on H2 |

---

# Completion

Gates run in this order at one commit by `scripts/verify-spring-starter.sh [Gn]`. Each writes `evidence/Gn-*.txt` with the commit hash at the top
(evidence from another commit is void). The script reports; it fixes nothing.

| Gate | What | Pass rule |
|---|---|---|
| G0 | Clean tree; every task in `tasks.md` ticked | none unticked |
| G1 | Build of `ai-agent4j`, `-tools`, `-addons`, Loom, `loom-inspect*`, the starter, three times: plain, plain, random order; and once with `-Pextended` | all succeed; plain runs have equal test counts |
| G2 | Traceability: `verify_spec.py … SB` (its `MODULES` extended with the starter), reading the reports of G1 including the extended pass | every check has a passing tagged test; no unknown ids; nothing `@Disabled`; every requirement in the table below |
| G3 | Coverage: JaCoCo `check` | starter and `loom.host` line ≥ 0.85, branch ≥ 0.80; branch ≥ 0.90 for `CredentialProperty`, the audit gate, `DurabilityGuard`, the module guard |
| G4 | Dependency hygiene: `mvn dependency:tree` of each library module and of a throw-away consumer of the starter | V0.6, V1.1: no Spring in any library; the consumer has no Loom unless it added it |
| G5 | Sabotage (list below) applied in a scratch worktree, starter and host tests run | every change makes its named test fail |
| G6 | Plain-Java equivalence: V0.7 | green |
| G7 | Real process: the host run from `java` with a stand-in model server, then `kill -9` while suspended, restart, `answer`, one webhook post on the stand-in; `weave timeline` and `weave resume` read the file-persisted run; loom-inspect lists the run | transcript as expected (V8.18, V12.7) |
| G8 | CI matrix: JDK 17/21 × Spring Boot baseline/latest | 4 green runs linked in evidence (V14.3) |
| G9 | Regression: full reactor `mvn verify` before the work and at this commit; the CLI tests and `RepositoryScriptsTest` unchanged | nothing that passed before fails; no existing test edited except for the additive overloads |
| G10 | Safety: V2.10, V8.9, V9.5 and the secrets sweep, plus a `/security-review` of the diff | no canary anywhere; findings fixed or explained in `evidence/G10-safety.md` |

### Sabotage list (G5)

| # | Change | Must fail |
|---|---|---|
| S1 | drop `@ConditionalOnMissingBean` from the primary `LLMClient` | V0.3 |
| S2 | create a default `ollama` client when none is configured | V0.1, V3.7 |
| S3 | let the literal `api-key` win over `api-key-secret` | V2.8 |
| S4 | resolve `api-key-secret` once at start-up | V2.6 |
| S5 | put the budget outside the masking client | V4.8 |
| S6 | put the budget inside routing | V4.6 |
| S7 | skip `kind.check` before `create` | V6.2 |
| S8 | let a later tool overwrite an earlier one of the same name | V6.6 |
| S9 | remove the durability guard | V8.28, V9.8 |
| S10 | ignore `fail-on` | V9.5 |
| S11 | write invoker credentials into the run record | V8.9 |
| S12 | accept an answer for a step that is not waiting | V8.12 |
| S13 | skip `shutdown()` after a failed `start` | V8.5 |
| S14 | attach observe after `initialize()` | V12.4 |
| S15 | give run executors no trigger store (schedules and wake-ups never stored) | V8.14, V8.19 |
| S16 | use the system clock instead of the injected one in the host | V8.14, V8.19 |
| S17 | copy a jar `.loot` to a temp file | V8.24 |
| S18 | close a user-supplied `SecretStore` on shutdown | V2.5 |
| S19 | make `ReActAgent` a singleton bean | V5.6, V5.7 |
| S20 | log the literal `api-key` at DEBUG | V2.10 |
| S21 | let a resume trigger run a run that needs credentials with none | V8.22 |

---

## Requirement to check

| Requirement | Checks |
|---|---|
| R1 Packaging and guards | V1.1–V1.7, V0.6 |
| R2 Secret stores | V2.1–V2.10 |
| R3 Clients, routing, retries | V3.1–V3.15 |
| R4 Budgets, privacy, audit | V4.1–V4.11 |
| R5 Agents | V5.1–V5.13 |
| R6 Tools and tasks | V6.1–V6.10 |
| R7 RAG beans | V7.1–V7.5 |
| R8 Loom_Host (library) | V8.1–V8.30 |
| R9 Loom in Spring | V9.1–V9.15 |
| R10 Persistence, resume, people | V10.1–V10.7 |
| R11 Triggers | V11.1–V11.4 |
| R12 Autonomy and observation | V12.1–V12.7 |
| R13 Metadata and documentation | V13.1–V13.3 |
| R14 Quality | V14.1–V14.3, V0.1–V0.7, L1–L3, G1–G10 |
