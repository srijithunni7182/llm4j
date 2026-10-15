# Verification Plan: Spring Boot Starter

The starter is done when every check below passes in an automated test, the gates under *Completion* pass at one commit,
and their evidence is committed under `evidence/`. Each test carries its check id as `@Tag("SB-V3.1")`, so
`scripts/verify_spec.py .kiro/specs/spring-boot-starter SB` proves that every check has a passing test and that no test
has an unknown id. Checks marked *(gate-checked: Gn)* are proved by a gate script, not a unit test.

## Fixtures and stand-ins

Unless a check says otherwise:

| Stand-in for | What |
|---|---|
| Context | `ApplicationContextRunner` (or `WebApplicationContextRunner` / `ReactiveWebApplicationContextRunner`) with the starter's auto-configurations and only the properties the check names |
| Models | `RecordingScriptedClient`: answers per call from a list, records every request (messages, model, tools); "no model call" and "called again" are assertions on its count |
| Loom models | a `LLMClientFactory` bean returning `RecordingScriptedClient`s per model name; `MockModels` where only the shape matters |
| Provider HTTP | `MockWebServer`, one per provider; its request count proves "nothing was sent" |
| Effects | a `webhook` tool pointed at a second `MockWebServer`; its request count proves "sent once" |
| Secrets | `InMemorySecretStore` holding the canary `sk-canary-7f3a9c41` under each name the check uses |
| Database | H2 in PostgreSQL mode, a fresh database per test (`jdbc:h2:mem:<uuid>;MODE=PostgreSQL`) |
| Files | `@TempDir` |
| Time | `MutableClock` at **2026-10-15T09:00:00Z** and a recording `Sleeper`; nothing sleeps for real beyond a 50 ms trigger poll |
| MCP | `EchoMcpServer`: a stdio MCP server in the test sources, run as `java -cp <test classpath>`, exposing `echo` and `secret_echo` |
| Observe | `RecordingObserveProvider` registered through `META-INF/services` in the test sources |
| People | the starter's `answer(...)` API, or a scripted `HumanInterface` where the check says so |
| Users | Spring Security test users `operator` (`LOOM_OPERATOR`) and `viewer` (no authority) |

Scripts used by many checks live in `src/test/resources/scripts/`:

| Script | Shape |
|---|---|
| `three-steps.loom` | `research → draft → review`, each a `delegate` to its own agent on model `gemini-2.5-flash` |
| `approve.loom` | `delegate` to `Writer`, then `human_prompt "Publish? (yes/no)" -> ok`, then on `yes` a webhook `Slack` post |
| `sleepy.loom` | `delegate`, `sleep 1h`, `delegate` |
| `trifecta.loom` | one agent with a `http` read of untrusted content, a private `sql` tool and an outward `webhook`, no `approve:` |
| `trifecta-accepted.loom` | as above plus `trifecta: accepted "reviewed 2026-10-01"` |
| `scheduled.loom` | `schedule Daily { cron: "0 7 * * *" run: Digest() }` and workflow `Digest` |
| `jar/main.loom` + `jar/lib/agents.loom` | `main` imports `lib/agents.loom`; packaged into `target/test-scripts.jar` by the test build |
| `broken.loom` | three independent errors: unknown agent, unknown tool, unknown task |
| `tasks.loom` | `run Refund(amount = "12.50") -> verdict` |
| `ladder.loom` | a `decision` with a `ladder` |

---

## V0: Zero-magic guarantees (ZM1–ZM7)

| ID | Given | Then |
|---|---|---|
| V0.1 | empty context (no `llm4j.*` property) | beans of llm4j types: exactly `ToolRegistry` (empty) and the properties classes; no `LLMClient`, `LLMClients`, `ReActAgent.Builder`, `SecretStore`, `Budget`, `LoomRuntime`, `LoomRuntimes`, `TriggerRunner`, `ConversationStore`, `VectorStore` |
| V0.2 | for each capability C in Req 2–24: C's minimum property set, then the same set minus C's enabling property | C's bean present, then absent (one parameterized case per capability; the case list is checked against the auto-configuration classes so a new capability without a case fails) |
| V0.3 | for each bean type the starter defines (generated list from the auto-configuration classes): a user bean of that type | the injected bean is the user's instance; `getBeansOfType` returns exactly 1 |
| V0.4 | every property in V0.2 that does not start something (all but `mcp.servers`, `loom.triggers.enabled`, `observe.enabled`, `channels`) set together | provider MockWebServers received 0 requests; no child process exists (`ProcessHandle.current().children()` empty); no new listening port (port scan of 8787 and the configured ports refused) |
| V0.5 | the three side-effect properties set one at a time | exactly that one effect happens (one child process / runner thread named `llm4j-trigger-runner` / one listening port) |
| V0.6 | `mvn dependency:tree` of `ai-agent4j`, `ai-agent4j-addons`, `ai-agent4j-tools`, `ai-agent4j-loom`, `loom-inspect*`, `loom-fabric`, `eval4j`, `eval4j-report` | no `org.springframework` artifact in any scope *(gate-checked: G5)* |
| V0.7 | the demo app with the starter dependency removed and its 3 `@Bean` methods from the README's plain-Java section added | its integration test passes unchanged *(gate-checked: G6)* |

## V1: Modules and dependencies (R1)

| ID | Given | Then |
|---|---|---|
| V1.1 | the four artifacts after `mvn install` | `llm4j-spring-boot-starter` and `-starter-loom` jars contain no classes; autoconfigure's POM marks `ai-agent4j-loom`, `ai-agent4j-tools`, `ai-agent4j-addons`, `loom-inspect`, Actuator, Micrometer, MVC, WebFlux, JDBC and Security as `optional` |
| V1.2 | a consumer project depending only on `llm4j-spring-boot-starter` | its resolved tree has no `ai-agent4j-loom`, `loom-inspect`, `spring-webmvc`, `micrometer-core`; with `-starter-loom` it has `ai-agent4j-loom` and `ai-agent4j-tools` *(gate-checked: G5)* |
| V1.3 | `llm4j.loom.scripts.default.location=classpath:scripts/three-steps.loom`, `FilteredClassLoader("io.github.llm4j.loom")` | start-up fails; `FailureAnalysis` description contains `llm4j.loom` and action contains `llm4j-spring-boot-starter-loom` |
| V1.4 | `llm4j.rag.store=pgvector` without addons; `llm4j.observe.enabled=true` without loom-inspect; `llm4j.tools.X.kind=webhook` without tools | each fails naming `ai-agent4j-addons`, `loom-inspect`, `ai-agent4j-tools` respectively |
| V1.5 | v1 properties `llm4j.provider=ollama`, `llm4j.model=llama3`, `llm4j.loom.script=classpath:scripts/three-steps.loom` | `LLMClients.names()` = `[default]`; `LoomRuntimes.names()` = `[default]`; same beans as the long form |
| V1.6 | `llm4j.provider=ollama` and `llm4j.clients.default.provider=gemini` | fails; message names both keys |
| V1.7 | the Jackson version on the starter's test class path | equals `${jackson.version}` of the parent POM |

## V2: Secret stores (R2)

| ID | Given | Then |
|---|---|---|
| V2.1 | `secrets.store=env`, property `GEMINI_API_KEY=sk-canary-7f3a9c41` | `SecretStore.contains("GEMINI_API_KEY")`; value read through the store equals the canary |
| V2.2 | `store=encrypted-file`, `file=<tmp>/k.store` created with master key from env var `LLM4J_MASTER_KEY` | store opens and returns the canary; with `master-key-file` instead, same |
| V2.3 | both `master-key-env` and `master-key-file` set; neither set | fails naming both / fails naming `llm4j.secrets.master-key-env` |
| V2.4 | `store=chained`, `chain=[encrypted-file, env]`, the name in both with different values | the encrypted-file value wins |
| V2.5 | the starter created the store; the user supplied it | on context close the first is closed (`close()` recorded), the second is not |
| V2.6 | `clients.default.api-key-secret=ANTHROPIC_API_KEY`, store value changed between call 1 and call 2 | MockWebServer saw `x-api-key: v1` then `x-api-key: v2` |
| V2.7 | the secret is bound to host `api.anthropic.com`, client `base-url` is the MockWebServer | the call fails with `AuthenticationException`; MockWebServer received 0 requests |
| V2.8 | `api-key` and `api-key-secret` both set | fails naming `llm4j.clients.default.api-key` and `llm4j.clients.default.api-key-secret` |
| V2.9 | `api-key-secret` set, no secret store configured | fails naming `llm4j.secrets.store` |
| V2.10 | canary set as literal `api-key`, Actuator on, then: start-up log, `/actuator/env`, `/actuator/configprops`, the properties' `toString()`, a forced provider error | the canary occurs in none of them (captured output and response bodies grepped) |

## V3: Named and primary clients (R3)

| ID | Given | Then |
|---|---|---|
| V3.1 | one client per provider (gemini, anthropic, ollama, sarvam), each `base-url` a MockWebServer | each client's `chat` hits only its own server, on the same path the provider's own unit tests expect (Gemini `…:generateContent`, Anthropic `/v1/messages`, Ollama `/api/chat`, Sarvam's chat path) |
| V3.2 | clients `default`, `cheap`, `strong` declared in that order | `LLMClients.names()` = `[default, cheap, strong]`; `getBeansOfType(LLMClient)` has 1 bean |
| V3.3 | `primary=strong` | the `LLMClient` bean's request reaches `strong`'s server |
| V3.4 | `primary=missing` | fails; message lists `default, cheap, strong` |
| V3.5 | `routing.strategy=fallback`, `routing.clients=[cheap, strong]`, tiers FAST_CHEAP and REASONING; `cheap` returns 503 three times | the primary returns `strong`'s answer; `cheap` received 1 request (retries set to 0), `strong` 1 |
| V3.6 | `routing.strategy=cost-aware` with a request carrying `ComplexityHint` low, then high | low → `cheap`, high → `strong` |
| V3.7 | no client configured | no `LLMClient`, no `LLMClients` |
| V3.8 | `provider=gemini` without a key | fails; action contains `llm4j.clients.default.api-key-secret` |
| V3.9 | `provider=openai` | fails; message lists `gemini, anthropic, ollama, sarvam` |
| V3.10 | `anthropic` with `effort: medium` | request body contains the effort setting as `AnthropicProvider(config, "medium")` sends it |
| V3.11 | two `LLMClientCustomizer` beans at `@Order(2)` and `@Order(1)`, each wrapping with a tag | the primary is `customizer2(customizer1(decorated))` (call order recorded) |

## V4: Retries and rate limits (R4)

| ID | Given | Then |
|---|---|---|
| V4.1 | `retry.max-retries=2`, `initial-backoff=1s`; server returns 500, 500, 200 | 3 requests; recorded sleeps 1 s and 2 s (± configured jitter) |
| V4.2 | `retry.enabled=false`; server returns 500 | 1 request; `ProviderException` |
| V4.3 | `inline-wait-threshold=30s`; 429 `Retry-After: 5`, then 200 | 2 requests; recorded sleep 5 s ≤ s ≤ 5.5 s |
| V4.4 | same, `Retry-After: 3600` | 1 request; `RateLimitException` with resetAt **2026-10-15T10:00:00Z** |
| V4.5 | no retry properties | the built `RetryPolicy` equals `RetryPolicy.defaultPolicy()` field by field |
| V4.6 | `Clock` and `Sleeper` beans | the retry path used them (V4.3's sleep is on the recording sleeper; resetAt computed from the mutable clock) |

## V5: Budgets and prices (R5)

| ID | Given | Then |
|---|---|---|
| V5.1 | `pricing.models.claude-sonnet-5-5.input-per-million=3`, `output-per-million=15`; one call using 1 000 000 in / 100 000 out | `Budget.spent().cost()` = 4.50 |
| V5.2 | `pricing.file=classpath:prices.yaml` (same prices) | same as V5.1 |
| V5.3 | `budget.tokens=1000`; scripted calls of 400 tokens each | calls 1–2 succeed, call 3 refused with `BudgetExceeded` before reaching the client (scripted count 2) |
| V5.4 | `budget.cost="1.00"` and no prices | fails naming `llm4j.pricing` |
| V5.5 | `budget.window=hour`, exhausted at 09:20 | refused until 09:59:59; at 10:00:00 (clock advanced) accepted |
| V5.6 | a `BudgetListener` bean, `warn-at=0.5`, spend crosses 50% | listener received exactly one warning event |
| V5.7 | `budget.scope=each` with two clients | each client has its own `Budget`; exhausting one leaves the other accepting |
| V5.8 | routing with failover (V3.5 shape) under a budget | budget `calls` = 1 for the logical call |
| V5.9 | Loom script run with `loom.budget.tokens=500` and a `PriceTable` bean | the executor's `setPriceTable` and `setBudgetOverrides(500, null, null)` were applied (recorded through a customizer at `@Order(LOWEST)`); the run stops at 500 tokens |

## V6: Privacy and fairness (R6)

| ID | Given | Then |
|---|---|---|
| V6.1 | `privacy.mask-pii=true`; prompt `Mail jane.doe@example.com or call +1 415 555 0100` | MockWebServer body contains neither the address nor the number; contains the detector's placeholders |
| V6.2 | V6.1 under a budget with a recording `TokenEstimator` | the estimator saw the masked text |
| V6.3 | `privacy.types=[EMAIL]` | the phone number reaches the server; the email does not |
| V6.4 | V6.1 with Micrometer | `llm4j.privacy.masked{type=EMAIL}` = 1, `{type=PHONE}` = 1; one `PiiMaskedEvent` whose fields contain no masked value |
| V6.5 | `fairness.monitor=rule-based` | every builder from `Agents` has the `RuleBasedBiasMonitor`; a user `BiasMonitor` bean replaces it |

## V7: Audit logging (R7)

| ID | Given | Then |
|---|---|---|
| V7.1 | `audit.file=<tmp>/audit.jsonl`; an agent run with one tool call, then a Loom run | the file has the agent's tool-call event and the Loom run's events, with the same session ids the runs report |
| V7.2 | a script with its own `audit { logger: file path: "<tmp>/script.jsonl" }` | the script's events go to `script.jsonl`, not `audit.jsonl` |
| V7.3 | a user `AuditLogger` bean | it receives the events; no `FileAuditLogger` is created |

## V8: Agents (R8)

| ID | Given | Then |
|---|---|---|
| V8.1 | beans: 2 `AgentEventListener`, 2 `RunHook`, `ToolHook`s at `@Order(2)`/`@Order(1)`, an `ApprovalCallback`, an `EffectClassifier`; a scripted run calling one tool marked `requiresApproval` | both listeners got thought/action/observation; run hooks called; tool hooks called in order 1 then 2; approval callback asked once with the tool name |
| V8.2 | `agent.tools=[calculator]` with tools `calculator`, `echo` registered | the request's tool list (native) or prompt (text) names only `calculator` |
| V8.3 | `agent.tools=[nope]` | fails; message lists `calculator, echo` |
| V8.4 | `agents.triage.client=cheap`, defaults `client=default`, `max-iterations=8`; `agents.triage.max-iterations` unset | `Agents.create("triage")` calls `cheap` and stops after 8 iterations |
| V8.5 | `Agents.create("ghost")` | throws; message lists configured names |
| V8.6 | `agent.system-prompt-id=triage` with `prompts.location=classpath:prompts/` containing `triage.md` | the system message equals `triage.md`'s body |
| V8.7 | a `ReActAgentBuilderCustomizer` setting `maxIterations(2)` and `agent.max-iterations=8` | built agent stops after 2 (customizer last) |
| V8.8 | two `getObject()` calls on `ObjectProvider<ReActAgent.Builder>`; two `build()`s | four distinct objects; an exchange on agent A is absent from agent B's next request |
| V8.9 | the context | no bean of type `ReActAgent` exists |
| V8.10 | `tool-calling=native` with an ollama model whose client says `supportsToolCalling=false` | fails at build with the library's message (no silent fallback) |

## V9: Context windows and compaction (R9)

| ID | Given | Then |
|---|---|---|
| V9.1 | `agent.context.window=2000`, `compaction=drop-oldest`; a conversation of 30 exchanges | the 30th request's estimated tokens ≤ 2000; the oldest exchanges are absent; an `onCompaction` event fired |
| V9.2 | `compaction=summarize`, `compaction-client=cheap` | the summary request went to `cheap`; the main request to the primary |
| V9.3 | `compaction=trim-tool-results`, `compaction-options.max-chars=200`; a 5 000-char tool result | the next request carries ≤ 200 chars of it |
| V9.4 | a `ContextPolicy` bean and `compaction=drop-oldest` | the bean's policy is used |
| V9.5 | `loom.context.windows-files=[classpath:windows.yaml]` giving model `gemini-2.5-flash` 8 000 tokens | the executor's `WindowLayers.find("gemini-2.5-flash")` = 8 000 from that file; other models come from the catalogue |

## V10: Memory (R10)

| ID | Given | Then |
|---|---|---|
| V10.1 | `memory.conversations=file`, `dir=<tmp>`; context A: `AgentSessions.agent("s-1").run("My name is Asha")`; close; context B on the same dir: `agent("s-1").run("What is my name?")` | B's request contains the first exchange |
| V10.2 | `agent("s-2")` in B | its request has no exchange from `s-1` |
| V10.3 | `max-history=4` and 10 exchanges | the request carries the last 4 |
| V10.4 | no `ConversationStore` | no `AgentSessions` bean |
| V10.5 | `memory.semantic.*` set, `tool=false`, then `tool=true` | `SemanticMemoryService` on builders in both; the memory tool in the registry only in the second |

## V11: Prompts, skills, personas (R11)

| ID | Given | Then |
|---|---|---|
| V11.1 | `prompts.location=classpath:prompts/` with `.md` files; then `file:<tmp>/prompts.yaml` | `MarkdownFolderPromptRegistry`, then `FileSystemPromptRegistry`; each returns the expected template |
| V11.2 | `skills.locations=[classpath:skills/, file:<tmp>/skills/]`, `agent.skills=[refunds]` | the `refunds` skill is on built agents; other skills are not |
| V11.3 | `skills.locations=[https://example.com/skills]` without `allow-remote` | fails naming `llm4j.skills.allow-remote`; no HTTP request made |
| V11.4 | `personas.location=classpath:personas.yaml`, `agent.persona=support` | the system message contains the persona's text before the system prompt |
| V11.5 | a Loom run with the `PromptRegistry` bean; a script with its own `prompts:` folder | the first resolves `prompt: triage` from the bean; the second from its folder |

## V12: Tools (R12)

| ID | Given | Then |
|---|---|---|
| V12.1 | `tools.Slack.kind=webhook`, `options.url-secret=SLACK_WEBHOOK` (store value = MockWebServer URL), `options.format=slack` | `ToolRegistry.getTool("Slack").execute({text: "hi"})` → server got one POST with Slack's JSON body |
| V12.2 | `tools.Shell.kind=shell` with an option `check` rejects (a program not on the allow-list) | fails; message is `check`'s message, prefixed with `llm4j.tools.Shell` |
| V12.3 | one case per kind (`webhook`, `email`, `http`, `file`, `shell`, `sql` on H2) with valid options | each tool executes once against its stand-in and returns text |
| V12.4 | a `ToolKind` bean named `pager` and `tools.Page.kind=pager` | the bean's kind created the tool |
| V12.5 | `tools.builtin=[calculator, current-time]` | exactly those two built-ins registered; with no `builtin` none |
| V12.6 | `tools.builtin=[nope]` | fails; message lists the built-in names |
| V12.7 | `mcp.servers.echo` with `tools=[echo]` | registry has `echo.echo`, not `echo.secret_echo`; calling it returns the echoed text |
| V12.8 | context close after V12.7 | the server process has exited within 5 s |
| V12.9 | a `Tool` bean named `Slack` and `tools.Slack.kind=webhook` | fails; message names `Tool bean 'slackTool'` and `llm4j.tools.Slack` |
| V12.10 | a tool from each source (bean, built-in, kind, MCP, `.loot`) | each is in the core registry (except `.loot`) and in the script's Loom registry |
| V12.11 | an `EffectContext` bean with a durable journal; the webhook tool called twice with identical arguments under the same step key | MockWebServer received 1 request |

## V13: RAG (R13)

| ID | Given | Then |
|---|---|---|
| V13.1 | `rag.embedding=gemini` (MockWebServer), `rag.store=memory` | `EmbeddingProvider` and `InMemoryVectorStore` beans; add + search returns the added id first |
| V13.2 | `rag.store=file`, `rag.file.path=<tmp>/v.json` | `FileVectorStore`; the file exists after add |
| V13.3 | `rag.store=pgvector`, `spring.datasource.url=jdbc:postgresql://db:5432/app`, `username=app`, `password=pw`, no `rag.pgvector.url` | the store was constructed with those three values (constructor arguments captured; no connection attempted) |
| V13.4 | `rag.pgvector.password-secret=PG_PASSWORD` | built through `PGVectorStore.withSecret` with `SecretRef.of(store, "PG_PASSWORD")` |
| V13.5 | `rag.store=pinecone`, `api-key-secret=PINECONE_KEY` | built through `PineconeVectorStore.withSecret` |
| V13.6 | embedding and store present; `rag.chunking.size=800`, `overlap=100` | a prototype `RAGAgent` builder exists; its chunker splits a 2 000-char document into 3 chunks |
| V13.7 | an `EmbeddingFactory` bean and a script with a `knowledge` block | the executor indexed through the bean (bean recorded the call) |
| V13.8 | real pgvector round trip in a container | add, search, delete work *(live-adjacent: `@Tag("integration")`, run in G1's extended pass)* |

## V14: Tasks (R14)

| ID | Given | Then |
|---|---|---|
| V14.1 | a `Task` bean `Refund` (a Spring service returning `{approved: true}`), `tasks.loom` | the run's `verdict.approved` = `"true"`; the bean was called once |
| V14.2 | resume of V14.1 after completion (durable) | the bean is not called again |
| V14.3 | two `Task` beans named `Refund` | fails naming both bean names |
| V14.4 | `tasks.discover=false` with a task in `META-INF/services` | the discovered task is absent; with `true` present |
| V14.5 | a script naming task `NotABean` | start-up validation fails naming `NotABean` |

## V15: Scripts and runtimes (R15)

| ID | Given | Then |
|---|---|---|
| V15.1 | `loom.scripts.a` and `loom.scripts.b` | `LoomRuntimes.names()` = `[a, b]`; no single `LoomRuntime` bean (neither is `default`); with only `a`, the `LoomRuntime` bean is `a` |
| V15.2 | `location=classpath:jar/main.loom` loaded from `target/test-scripts.jar` on a `URLClassLoader` | the script has the agents from `lib/agents.loom`; a run completes |
| V15.3 | `lib/agents.loom` importing `../main.loom` | fails with the library's circular-dependency message naming both jar URLs |
| V15.4 | `.loot` inside the jar with `Echo = …EchoTool` and `Echo.reach = none` | `Echo` registered; `setToolReach({Echo=none})` applied; no file created under `java.io.tmpdir` during start-up (directory listing before/after) |
| V15.5 | `broken.loom` | fails once, listing all three errors with their line numbers |
| V15.6 | `broken.loom` with `loom.validate=lenient` | starts; three WARN log lines |
| V15.7 | a script on model `gemini-2.5-flash`, the key only in the secret store | validation passes and the run uses the store's key; with neither store nor environment, start-up fails naming `GEMINI_API_KEY` |
| V15.8 | `ScriptSource.fileSystem()` and `LoomLoader.load(String)` on every `.loom` in the repository | identical `LoomScript`s (library regression, in ai-agent4j-loom) |

## V16: Running workflows (R16)

| ID | Given | Then |
|---|---|---|
| V16.1 | `run(RunRequest.of("Main", {topic: "x"}))` on `three-steps.loom` | `RunOutcome.Completed`; `variables` has `research`, `draft`, `review`; 3 model calls |
| V16.2 | a scripted `human_prompt` that suspends (durable) | `RunOutcome.Suspended` with reason `HUMAN`, stepId of the prompt, prompt text; no exception |
| V16.3 | an operator `cancel` during step 2 | `RunOutcome.Stopped` |
| V16.4 | step 2's model throws `ProviderException` with no `on_failure` | `LoomRunFailedException` whose `runId()` equals the outcome's id and whose cause is the provider error |
| V16.5 | `start` in each of completed / suspended / stopped / failed | the executor's `shutdown()` was called exactly once in every case (recorded through a customizer-installed spy) |
| V16.6 | a script with an `http` tool using `invoker.token`; `RunRequest.as(credentials returning "Bearer u1")` | MockWebServer saw `Authorization: Bearer u1` |
| V16.7 | same, credentials that do not `provide("token")` | `MissingInvokerCredentialException` before the first step; 0 model calls |
| V16.8 | `RunRequest.withRunId("r-42")` / none | outcome id `r-42` / a UUID; `LiveRuns.shared().all()` labels include the run while it runs |
| V16.9 | `executor=virtual-threads` (JDK 21 only, `@EnabledForJreRange(min = JAVA_21)`), `platform`, `application` | the run's thread is virtual / a platform thread named `llm4j-loom-*` / an `applicationTaskExecutor` thread |
| V16.10 | `start` | returns before the first model call (the scripted client blocks on a latch the test releases after `start` returns) |
| V16.11 | `max-concurrent-runs=2`, three blocked runs | the third `start` throws `TooManyRuns`; after one finishes, a new `start` succeeds |
| V16.12 | a `LoomExecutorCustomizer` that records the call order of setters it sees | it runs after every starter setter and before `initialize()` |
| V16.13 | context close with one durable and one non-durable run blocked mid-step, `shutdown-grace=2s` (mutable clock) | `wrapUp` sent to both; after the grace the durable run is suspended by operator (journal entry present, RunRecord `SUSPENDED`), the non-durable run cancelled; one INFO line per run |
| V16.14 | v1 overloads `run("Main", map)` and `start("Main", map)` | behave as V16.1 / V16.10 |

## V17: Durable runs (R17)

Every V17 check runs on the **persistence matrix**: `file` and `jdbc` (H2).

| ID | Given | Then |
|---|---|---|
| V17.1 | `persistence=file` | after one run: `<dir>/runs/<id>/journal.json` and `run.json` exist; `<dir>/triggers/` exists |
| V17.2 | `persistence=jdbc`, `initialize-schema=embedded` on H2 | tables of `JdbcRunJournal`, `JdbcTriggerStore`, `JdbcLedger`, `JdbcLevelStore` and `llm4j_loom_runs` exist |
| V17.3 | `initialize-schema=never` on an empty H2 | start-up fails naming the missing table and `initialize-schema` |
| V17.4 | `persistence.datasource=loomDataSource` with two `DataSource` beans | tables created only in `loomDataSource` |
| V17.5 | a run with inputs and invoker credentials | `RunRecord` has script, workflow, inputs, origin; the credential value (canary) is nowhere in the store (file grep / table dump) |
| V17.6 | `approve.loom`: run → `Suspended(HUMAN)`; `answer(id, step, "yes", "asha")`; trigger fires | run completes; `Writer` was called once in total (replayed on resume); Slack MockWebServer got 1 request; audit has `answered` with `by=asha` |
| V17.7 | V17.6 with the context closed after the suspension and a new context opened on the same store before answering | same result as V17.6 |
| V17.8 | `answer` with a wrong step id; on a completed run; twice | refused with "not waiting at <step>"; "not suspended"; second is "already answered by asha" |
| V17.9 | `human_prompt` with choices `yes/no`; answer `Ye` | recorded as `yes` (`Answers.match`); answer `maybe` refused listing the choices |
| V17.10 | `sleepy.loom`: run → `Suspended(SLEEP, resumeAt 10:00:00Z)`; clock to 10:00:00; trigger tick | run completes; the first `delegate` not called again |
| V17.11 | a rate-limit suspension (scripted 429 with reset 09:30) | `Suspended(RATE_LIMIT, 09:30)`; resumes at 09:30 |
| V17.12 | `persistence=none` with `approve.loom` | start-up fails naming `llm4j.loom.persistence` and the construct and line |
| V17.13 | V17.12 with `allow-non-durable=true` | starts; the run ends `LoomRunFailedException` whose message says it cannot resume and names `llm4j.loom.persistence` |
| V17.14 | `runs()` after 3 runs (completed, suspended, failed) | 3 records with those statuses, suspension reason and resume time on the suspended one |
| V17.15 | `travel(id)` then rewind to a checkpoint of a finished run | the library's `RunTravel` result (a new generation in the journal) |
| V17.16 | a journal written by the starter (`file`) | `weave timeline <dir>/runs/<id>` reads it *(gate-checked: G7)* |

## V18: People in the loop (R18)

| ID | Given | Then |
|---|---|---|
| V18.1 | durable, no `HumanInterface` bean, `approve.loom` | the executor's interface is `SuspendingHumanInterface`; outcome `Suspended(HUMAN)` |
| V18.2 | a `HumanQuestionListener` bean and an `@EventListener(HumanQuestionEvent)` | both got `(runId, stepId, "Publish? (yes/no)", [yes, no])` once |
| V18.3 | on resume after an answer | the interface returned the journaled answer; no new question event |
| V18.4 | `human.ask-via=command` (the library's command channel on a temp store) | questions go to the channel; an answer recorded through the channel resumes the run; the listener thread stops on close |
| V18.5 | a user `HumanInterface` bean | it is used instead |

## V19: Triggers (R19)

| ID | Given | Then |
|---|---|---|
| V19.1 | `triggers.enabled=true`, `poll-interval=50ms` | a runner thread polls; on close it stops within 1 s |
| V19.2 | `triggers.enabled=true` with `persistence=none` | fails naming `llm4j.loom.persistence` |
| V19.3 | `scheduled.loom`; clock 06:59 → 07:00 | `Digest` started once, with a run id of the library's `<schedule>@<slot>` form for the 07:00 slot; `TriggerFiredEvent` published |
| V19.4 | two contexts (replicas) on one H2 store with different `owner`s, both polling at 07:00 | `Digest` ran once in total |
| V19.5 | context restarted 3 times | the schedule trigger exists once in the store (by id) |
| V19.6 | a `ResumeRun` trigger for a run whose script needs invoker credentials | outcome `HUMAN` with "needs the person's credentials"; RunRecord says so; nothing ran |
| V19.7 | an `AgentTask` trigger | the agent ran the task; no schedule was registered in memory by that executor |
| V19.8 | `triggers.endpoint.enabled=true`, secret `LOOM_TRIGGER_TOKEN=tok`; POST with `Authorization: Bearer tok` / wrong / none / GET | 200 with the tick count / 401 / 401 / 405 |
| V19.9 | endpoint enabled with neither the secret nor a `BearerVerifier` bean | start-up fails naming `LOOM_TRIGGER_TOKEN` |
| V19.10 | V19.8 on WebFlux | same results |
| V19.11 | a trigger due at 09:00 and the runner stopped until 09:05, `overdue-threshold=2m` | `loom.triggers.overdue` = 1 and the `loom` health is `DOWN` with `overdue: 1`; after the tick, 0 and `UP` |

## V20: Earned autonomy (R20)

| ID | Given | Then |
|---|---|---|
| V20.1 | `ladder.loom`, `persistence=file` then `jdbc` | `FileLedger`/`FileLevelStore` then `JdbcLedger`/`JdbcLevelStore` set on the executor; a decision's record survives a context restart |
| V20.2 | user `Ledger` and `LevelStore` beans | used instead |
| V20.3 | `ladder.loom` with `persistence=none` (even with `allow-non-durable=true`) | start-up fails naming `llm4j.loom.persistence` and the decision |

## V21: Security gate (R21)

| ID | Given | Then |
|---|---|---|
| V21.1 | `trifecta.loom`, default `fail-on` | start-up fails; the failure text is `AuditReport.markdown()` and contains the trifecta rule id |
| V21.2 | `trifecta-accepted.loom` | starts |
| V21.3 | `trifecta.loom` with `fail-on=none` | starts; one WARN per finding plus one WARN "audit gate is off" |
| V21.4 | `fail-on=medium` with a script whose worst finding is MEDIUM; `fail-on=high` with the same | fails / starts |
| V21.5 | the properties metadata | contains no property that names an audit rule id (no per-rule silencing) |
| V21.6 | a `.loot` with `Fetch.reach = fetches` for a tool the script uses | the audit received the declared reach (its finding for `Fetch` matches the reach, compared with `SecurityAudit.audit(script, name, reach)` called directly) |

## V22: Observe (R22)

| ID | Given | Then |
|---|---|---|
| V22.1 | `observe.enabled=true`, `port=0`, `name=svc`, `detail=metadata`, `rules-file=classpath:svc.inspect` | `RecordingObserveProvider.open` called once with those options |
| V22.2 | two runs | `attach` called twice, each before that executor's `initialize()` (order recorded), with the script's path |
| V22.3 | `bind=0.0.0.0` without `LOOM_INSPECT_ADMIN_PASSWORD` in the store | fails naming the secret; with it, starts |
| V22.4 | `observe.fabric.url=https://fabric.test:9090`, `ca`, `outbox` | `FabricOptions` passed with those values and `keySecret=LOOM_FABRIC_CLIENT_KEY` |
| V22.5 | context close | session closed once, within `flush-timeout` |
| V22.6 | the real loom-inspect on the class path, `port=0` | the page answers on the chosen port and lists a running run *(gate-checked: G7)* |

## V23: Metrics, health, endpoint (R23)

| ID | Given | Then |
|---|---|---|
| V23.1 | `SimpleMeterRegistry`; an agent run with 2 model calls (usage 100/20 each) and 1 tool call | `llm4j.model.calls{client=default,provider=anthropic,model=…,outcome=success}` = 2; `llm4j.model.tokens{type=input}` = 200, `{type=output}` = 40; `llm4j.tool.calls{tool=calculator,outcome=success}` = 1; latency timer count 2 |
| V23.2 | a Loom run: completed, then one suspended | `loom.runs.started` = 2; `loom.runs.finished{status=completed}` = 1; `loom.suspensions{reason=human}` = 1; `loom.runs.active` back to 0 |
| V23.3 | every meter after V23.1–V23.2 with the prompt `CANARY-PROMPT-91` and user id `u-77` | no tag value contains either, or the secret canary |
| V23.4 | health with a client whose secret is missing from the store | `llm4j` `DOWN`, detail `{default: {provider: anthropic, keyPresent: false}}`; 0 provider requests |
| V23.5 | `loom` health with a valid script, reachable store, running runner | `UP`; with the H2 database shut down, `DOWN` with the store named |
| V23.6 | `GET /actuator/loom`, `/actuator/loom/runs`, `/actuator/loom/audit/default` | scripts, runs, audit JSON (`AuditReport.json()`); `POST`/`DELETE` → 405 |
| V23.7 | an `ObservationRegistry` with a recording handler | one observation per model call and one per run, named `llm4j.model.call` and `loom.run` |

## V24: Run-control web API (R24)

Each check runs on MVC and WebFlux.

| ID | Given | Then |
|---|---|---|
| V24.1 | `loom.web.enabled=true`, Spring Security present; anonymous `GET /llm4j/loom/runs` | 401 |
| V24.2 | `viewer`: `GET /runs` / `POST /runs/{id}/cancel` | 200 / 403 |
| V24.3 | `operator`: start, answer, steer, wrap-up, pause, resume, cancel | 2xx each, with the effect seen on the run (status, journal entry, scripted client receiving the steer text) |
| V24.4 | `operator` answers a question | the journal's answer entry and the audit event have origin/by `operator` |
| V24.5 | Spring Security absent, `web.enabled=true` | fails naming `llm4j.loom.web.allow-unsecured` |
| V24.6 | V24.5 with `allow-unsecured=true` | starts; WARN logged at every start |
| V24.7 | responses for a run started with invoker credentials and the canary as an input | no response body contains the canary credential; prompt text absent unless `include-text=true` (input values are application data and appear only under `include-text`) |
| V24.8 | the application defines its own `SecurityFilterChain` matching `/llm4j/loom/**` | the starter's chain is not created; the application's rules apply |
| V24.9 | `web.enabled` unset | no handler mapped at `/llm4j/loom/**` (404) |

## V25: Testing support (R25)

| ID | Given | Then |
|---|---|---|
| V25.1 | `@SpringBootTest @AutoConfigureLlm4jMock` with three real clients and a Loom script configured | `LLMClient`, every `LLMClients` entry and `LLMClientFactory` are mocks; all provider MockWebServers received 0 requests |
| V25.2 | `@Llm4jTest` | the context contains llm4j beans and no web, data or security auto-configuration |
| V25.3 | `@AutoConfigureLlm4jLive` without the provider key | test reported skipped, not failed |
| V25.4 | `@AutoConfigureLlm4jLive`, `test.max-spend="0.01"`, fake prices, scripted usage costing 0.004 per call | calls 1–2 succeed; call 3 throws `SpendGuard.SpendStopped` |
| V25.5 | `test.replay.mode=record` against MockWebServer, then `replay` with the server stopped | the replay run returns the recorded answers; 0 requests |
| V25.6 | `FakeJudge` bean in a judge test | the eval4j assertion uses it; results written under `target/eval4j/runs/` |
| V25.7 | a `MutableClock` bean and `sleepy.loom` under the mock annotation | the test advances the clock 1 h and the run completes without real waiting (test time < 2 s) |

## V26: Metadata and documentation (R26)

| ID | Given | Then |
|---|---|---|
| V26.1 | `spring-configuration-metadata.json` (merged with additional metadata) | every property reachable from the properties classes has a non-empty description; every enumeration-valued property has value hints matching the accepted values |
| V26.2 | the starter README | for each capability section: a YAML block that binds without error to the properties classes (parsed by a test), a "Bean" line naming a type that exists, and a "Without Spring" Java block that compiles (extracted and compiled by the test) |
| V26.3 | `docs/API_COMPATIBILITY.md` | lists `ScriptSource`, the new `LoomLoader`/`LootLoader`/`LoomRun` overloads and every public starter type |
| V26.4 | the demo app (`-Pextended`) | its test runs a durable approval end to end over the web API with mock models *(gate-checked: G6)* |

## V27: Quality (R27)

| ID | Check |
|---|---|
| V27.1 | No default-build test opens a non-loopback connection (a `ProxySelector`/`SocketFactory` guard installed by a JUnit extension fails the test that tries) |
| V27.2 | Coverage thresholds met *(gate-checked: G4)* |
| V27.3 | JDK 17 and 21 × Spring Boot baseline and latest pass *(gate-checked: G8)* |

---

## Live checks

Not in the default build (`-Plive`, each `@AutoConfigureLlm4jLive` with `test.max-spend="0.25"`); skipped without keys.

| ID | Check |
|---|---|
| L1 | Each provider client (`gemini`, `anthropic`, `ollama` local) answers a one-line prompt through the primary bean |
| L2 | `three-steps.loom` completes against `gemini-2.5-flash` |
| L3 | `approve.loom` suspends, is answered, resumes and completes, with `persistence=jdbc` on H2 |
| L4 | Masking (V6.1) against the real Anthropic API: the provider's echo of the prompt (asked to repeat it) contains no PII |

---

# Completion

Gates run in this order at one commit, by `scripts/verify-spring-starter.sh [Gn]`. Each writes `evidence/Gn-*.txt` (or `.md`)
with the commit hash at the top; evidence from another commit is void. The script only reports; it fixes nothing.

| Gate | What | Command (in the script) | Pass rule |
|---|---|---|---|
| G0 | Clean tree; every task in `tasks.md` ticked | `git status --porcelain`; grep `- [ ]` | empty; none unticked |
| G1 | Clean build of the library modules the work touched and the starter, three times: plain, plain, random order; plus once with `-Pextended` | `mvn -B -pl src/ai-agent4j,src/ai-agent4j-tools,src/ai-agent4j-addons,src/loom/ai-agent4j-loom,src/loom/loom-inspect-core,src/loom/loom-inspect,src/eval4j,src/spring -amd clean install` (+ `-Dsurefire.runOrder=random`) | all succeed; the two plain runs have equal test counts |
| G2 | Traceability | `python3 scripts/verify_spec.py .kiro/specs/spring-boot-starter SB` (its `MODULES` list extended with `src/spring/*`), reading the surefire reports of G1 including the `-Pextended` pass, so `@Tag("integration")` checks such as V13.8 count | every V-check has a passing tagged test; no unknown ids; nothing `@Disabled`; every requirement in the matrix below |
| G3 | Sabotage (list below): each change applied to the real source in a scratch worktree, the starter tests run | `scripts/sabotage_spring_starter.py` | every change makes its named test fail |
| G4 | Coverage | JaCoCo `check` in the autoconfigure module | line ≥ 0.85, branch ≥ 0.80 overall; branch ≥ 0.90 for `CredentialProperty`, `LoomWebSecurityGuard`, `LoomSecurityGate`, `DurabilityGuard`, `Llm4jModuleGuard` |
| G5 | Dependency hygiene | `mvn dependency:tree` per library module and for a throw-away consumer of each starter | V0.6, V1.2 |
| G6 | Demo app | `mvn -Pextended -pl src/examples/spring-boot-starter-demo verify`, then the same with the starter removed and the README's plain-Java beans added | both green (V0.7, V26.4) |
| G7 | Real process | demo app started with `java -jar`, a stand-in model server (MockWebServer jar) and the real loom-inspect: start a durable approval over HTTP; `kill -9` the app while suspended; restart; answer over HTTP; check one Slack post on the stand-in webhook; `weave timeline` reads the run directory; the observe page lists the run | transcript as expected (V17.16, V22.6) |
| G8 | Compatibility matrix | the CI job `spring-starter-matrix` on JDK 17/21 × Boot baseline/latest | 4 green runs, linked in evidence |
| G9 | Regression | full `mvn verify` of the reactor at the commit before the work and at this commit | no test that passed before fails now; no existing test removed or edited except for the additive library overloads |
| G10 | Safety | V2.10, V17.5, V23.3, V24.7 (the secrets sweep) plus a `/security-review` of the diff | no canary anywhere; findings fixed or explained in `evidence/G10-safety.md` |

### Sabotage list (G3)

Each change must make the named test fail. A change that no test catches is a missing test, not a pass.

| # | Change made in the source | Must fail |
|---|---|---|
| S1 | Drop `@ConditionalOnMissingBean` from the primary `LLMClient` | V0.3 |
| S2 | Create the `LLMClient` when `llm4j.clients` is empty, using `ollama` as a default | V0.1, V3.7 |
| S3 | Accept `api-key` when `api-key-secret` is also set (literal wins) | V2.8 |
| S4 | Resolve `api-key-secret` once at start-up into a literal | V2.6 |
| S5 | Put the budget outside the masking client | V6.2 |
| S6 | Put the budget inside routing (per tier) | V5.8 |
| S7 | Skip `kind.check(...)` before `create` | V12.2 |
| S8 | Let the second of two same-named tools overwrite the first | V12.9 |
| S9 | Remove the durability guard | V17.12, V20.3 |
| S10 | Ignore `fail-on` (always pass the audit) | V21.1 |
| S11 | Store invoker credentials in the RunRecord | V17.5 |
| S12 | Accept an answer for a step that is not waiting | V17.8 |
| S13 | Do not call `shutdown()` after a failed `start` | V16.5 |
| S14 | Attach observe after `initialize()` | V22.2 |
| S15 | Register the web chain with `permitAll()` for POST | V24.2 |
| S16 | Start the web API without Spring Security when `allow-unsecured` is unset | V24.5 |
| S17 | Tag `llm4j.model.calls` with the prompt's first 20 characters | V23.3 |
| S18 | Register the schedule triggers in each run's executor as well | V19.5, V19.7 |
| S19 | Use `LocalDateTime.now()` instead of the `Clock` bean in the trigger runner | V17.10, V19.3 |
| S20 | Copy a jar `.loot` to a temporary file (the v1 behaviour) | V15.4 |
| S21 | Let `@AutoConfigureLlm4jMock` replace only the primary client | V25.1 |
| S22 | Close a user-supplied `SecretStore` on shutdown | V2.5 |
| S23 | Make the `ReActAgent` bean a singleton | V8.8, V8.9 |
| S24 | Copy a literal `api-key` into a log line at DEBUG | V2.10 |

---

## Requirement to check

| Requirement | Checks |
|---|---|
| ZM1–ZM7 Zero magic | V0.1–V0.7 |
| R1 Modules and dependencies | V1.1–V1.7, V0.6 |
| R2 Secret stores | V2.1–V2.10 |
| R3 Named and primary clients | V3.1–V3.11 |
| R4 Retries and rate limits | V4.1–V4.6 |
| R5 Budgets and prices | V5.1–V5.9 |
| R6 Privacy and fairness | V6.1–V6.5 |
| R7 Audit logging | V7.1–V7.3 |
| R8 Agents | V8.1–V8.10 |
| R9 Context windows and compaction | V9.1–V9.5 |
| R10 Memory | V10.1–V10.5 |
| R11 Prompts, skills, personas | V11.1–V11.5, V8.6 |
| R12 Tools | V12.1–V12.11 |
| R13 RAG | V13.1–V13.8 |
| R14 Tasks | V14.1–V14.5 |
| R15 Scripts and runtimes | V15.1–V15.8 |
| R16 Running workflows | V16.1–V16.14 |
| R17 Durable runs | V17.1–V17.16 |
| R18 People in the loop | V18.1–V18.5 |
| R19 Schedules and triggers | V19.1–V19.11 |
| R20 Earned autonomy | V20.1–V20.3 |
| R21 Security gate | V21.1–V21.6 |
| R22 Observe | V22.1–V22.6 |
| R23 Metrics, health, endpoint | V23.1–V23.7, V19.11 |
| R24 Run-control web API | V24.1–V24.9 |
| R25 Testing support | V25.1–V25.7, L1–L4 |
| R26 Metadata and documentation | V26.1–V26.4, V0.7 |
| R27 Quality | V27.1–V27.3, G1–G10 |
