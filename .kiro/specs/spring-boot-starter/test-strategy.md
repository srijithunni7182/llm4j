# Test Strategy: Spring Boot Starter

## Principles

- **Hermetic by default.** Every default-build test uses `ApplicationContextRunner` (or `WebApplicationContextRunner`)
  with scripted or mock models. Nothing contacts a provider, an MCP server on the network, or a real database.
  JDBC tests run on H2 in PostgreSQL mode. MCP tests start a tiny stdio server written in Java on the test class path.
- **Each zero-magic rule has a test** (below), so a later change that breaks the promise fails CI.
- **Time is controlled.** Triggers, sleeps, windows and grace periods use a `MutableClock` and the library's
  `Sleeper`, never `Thread.sleep`.
- **Live tests** (`@Tag("live")`) only in `-Plive`, each behind `@AutoConfigureLlm4jLive` with a spend cap.

## Zero-magic guarantees

| Rule | Test |
|---|---|
| ZM1 configured, not guessed | `NothingConfiguredTest`: an empty context has only the inert beans (empty `ToolRegistry`, properties); one test per capability asserts its bean is absent without its property |
| ZM2 yours wins | `UserBeanWinsTest`: parameterized over every bean type the starter defines; the user's instance is the one injected and ours is not created |
| ZM3 plain Java | `PlainJavaEquivalenceTest`: for clients, agents and Loom, the bean's observable configuration equals one built by the README's plain-Java snippet (compared through public getters / behaviour on a scripted client) |
| ZM4 no surprise side effects | `StartupSideEffectsTest`: a context with every non-side-effect property set makes zero HTTP requests (MockWebServer counts), starts no process, opens no port |
| ZM5 fail fast, name the fix | `MisconfigurationMessagesTest`: one case per failure path, asserting the property name appears in the `FailureAnalysis` action |
| ZM6 secure by default | `SecretsNeverLeakTest` (logs captured, `/actuator/env`, `/actuator/configprops`, exception messages, `toString`), `WebApiSecurityTest` |
| ZM7 Spring never leaks | `NoSpringInLibrariesTest`: reads each library module's resolved dependency tree (via the Maven dependency plugin output in `target/`) and fails on `org.springframework` |

## By requirement

| Req | Tests |
|---|---|
| 1 | module guard per optional integration (`FilteredClassLoader`); legacy-property mapping; both forms set → fail |
| 2 | each store kind; master key from env/file; chained order; `SecretRef` fetched per request (store value changed between two calls is seen); host binding refused for another host; literal + secret both set → fail |
| 3 | each provider builds offline; `LLMClients` order and lookup; primary selection; routing strategy chosen and tiers passed (scripted clients that fail/succeed); customizer order |
| 4 | each retry field reaches `RetryPolicy`; `enabled=false` → no retry; clock/sleeper beans used (a 429 from MockWebServer waits on the test sleeper) |
| 5 | budget refuses after the cap (scripted client with token usage); cost cap without prices → fail; `Budget` bean reflects spend; listeners called; Loom executor received prices and overrides |
| 6 | masking outermost: the provider (MockWebServer) and the budget estimator see placeholders; masked counter by type; no value in event |
| 7 | file audit logger receives agent and Loom events; script `audit` block wins |
| 8 | each collected bean reaches the agent (observable through a scripted run: hooks called in order, approval callback asked, listener events); named agents fall back to defaults; unknown name lists names; never a singleton agent |
| 9 | window/compaction from properties; `summarize` uses the named client; bean wins; Loom `WindowLayers` precedence |
| 10 | `AgentSessions` restores history across two contexts sharing a file store; semantic tool only when enabled |
| 11 | prompts from `classpath:` folder; remote skills refused without `allow-remote`; Loom gets the registry unless the script sets a prompts folder |
| 12 | each kind created and `check` failure surfaces; secret options; duplicate names from two sources fail naming both; MCP tools registered with prefix and allow-list, process stopped on close |
| 13 | each store and embedding kind is created; pgvector takes its URL, user and password from `spring.datasource.*` when unset (asserted without connecting); a real pgvector round trip is `@Tag("integration")` only |
| 14 | `Task` beans run from a `run` step; duplicates fail; discovered tasks off |
| 15 | jar script with relative import (test jar built in `target/` and put on a `URLClassLoader`); `.loot` from jar not copied (no temp file created); validation lists every error; lenient mode warns |
| 16 | `RunOutcome` for completed/suspended/stopped; failure throws with run id; invoker reaches an `http` tool (MockWebServer sees the header) and is missing → fails before first step; executor `shutdown` on every `start` outcome; concurrency limit; graceful shutdown wraps up then cancels/suspends |
| 17 | file and JDBC: suspend → `answer` → resume replays without a second model call (scripted client call count) and without repeating a side effect (webhook MockWebServer hit once); restart between suspend and resume (new context, same store); wrong step / not waiting refused; non-durable guard |
| 18 | question event published with choices; channel human interface used when `ask-via` set |
| 19 | trigger fires a resume at the clock's time; two runners on one JDBC store fire once; schedule registered once across restarts; endpoint refuses without/with wrong token |
| 20 | ledger/levels persisted across contexts; autonomy + `none` → fail |
| 21 | a trifecta script fails start-up with the Markdown report; `trifecta: accepted` passes; `fail-on=none` logs WARN |
| 22 | session opened once, executors attached before initialize (fake `ObserveProvider` via `ServiceLoader` on the test class path), non-loopback without admin secret → fail |
| 23 | meters and tags after a scripted run (no free-text tag values); health up/down cases; `loom` endpoint read-only |
| 24 | MVC and WebFlux: unauthenticated 401, wrong authority 403, operator 200, principal recorded as origin in the journal; no Spring Security → fail unless `allow-unsecured` |
| 25 | mock annotation replaces every client and `LLMClientFactory`; live annotation skips without keys and stops at the spend cap (fake prices + scripted usage); replay record → replay with zero provider calls |
| 26 | metadata contains a description for every property (`ConfigurationMetadataTest` walks the properties classes) |

## Coverage

JaCoCo in `llm4j-spring-boot-autoconfigure`: package line ≥ 0.85, branch ≥ 0.80; class branch ≥ 0.90 for
`CredentialProperty`, `LoomWebSecurityGuard`, `LoomSecurityGate`, `DurabilityGuard`, `Llm4jModuleGuard`.

## Compatibility matrix

CI job `spring-starter-matrix`: JDK 17 and 21 × Spring Boot 3.2.x (baseline) and latest 3.x, running the starter modules
only (`-pl src/spring -amd`), so the rest of the reactor is not built four times.
