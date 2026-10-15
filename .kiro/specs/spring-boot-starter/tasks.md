# Implementation Plan: Spring Boot Starter

## Overview

Six phases, each shippable and each leaving `mvn verify` green. Phase 1 fixes what v1 already does wrong; phases 2–6 add
capabilities in order of risk reduction. Library changes (marked **[lib]**) land in their own module first, with tests,
before the starter uses them.

---

## Tasks

<!-- PHASE 1: foundation and v1 fixes -->

- [ ] 1. Module split
  - [ ] 1.1 Move `src/llm4j-spring-boot-starter` to `src/spring/llm4j-spring-boot-autoconfigure`; add POM-only
        `llm4j-spring-boot-starter`, `llm4j-spring-boot-starter-loom`; register all in the root `pom.xml`
    - _Requirements: 1.1, 1.2_
  - [ ] 1.2 Split v1 `Llm4jAutoConfiguration` into `ClientsAutoConfiguration`, `ToolsAutoConfiguration`,
        `AgentsAutoConfiguration`, `LoomAutoConfiguration` with explicit ordering; update the `.imports` file
    - _Requirements: 1.2_
  - [ ] 1.3 `Llm4jModuleGuard` (`EnvironmentPostProcessor`) and `Llm4jFailureAnalyzer`
    - _Requirements: 1.4, ZM5_
  - [ ] 1.4 `Llm4jLegacyProperties`: map v1 keys to `clients.default` / `loom.scripts.default`; fail when both set
    - _Requirements: 1.5_

- [ ] 2. Secrets
  - [ ] 2.1 `SecretsAutoConfiguration`: env, encrypted-file (master key from env or file), chained; close only what we made
    - _Requirements: 2.1–2.4_
  - [ ] 2.2 `CredentialProperty.ref(...)`, used by clients; sanitizing function; `toString()` masking
    - _Requirements: 2.5–2.7_

- [ ] 3. Loom run API fixes
  - [ ] 3.1 **[lib]** `LoomRun.start(executor, label, workflow, inputs, origin, invoker, Executor)` + completion callback;
        tests in ai-agent4j-loom (status per outcome, invoker reaches tools, callback always runs)
    - _Requirements: 16.2, 16.3_
  - [ ] 3.2 `RunRequest`, `RunOutcome`, `LoomRunFailedException`; `run`/`start` rewritten on them; v1 overloads kept
    - _Requirements: 16.1–16.4_
  - [ ] 3.3 `ExecutorAssembler` (single ordered setter pipeline, Design §4.1)
    - _Requirements: 16.7_
  - [ ] 3.4 Executor shut down on every outcome of `start`; `TaskExecutor` selection; `max-concurrent-runs` semaphore
    - _Requirements: 16.2, 16.5, 16.6_
  - [ ] 3.5 `LoomRuntimes` `SmartLifecycle` graceful shutdown (wrap up → grace → suspend/cancel)
    - _Requirements: 16.8_

- [ ] 4. Scripts in jars
  - [ ] 4.1 **[lib]** `ScriptSource`, `LoomLoader.load(location, source)`, `LootLoader` Reader overloads; existing
        signatures delegate; `API_COMPATIBILITY.md`
    - _Requirements: 15.2, 15.3, 26.4_
  - [ ] 4.2 `ResourceScriptSource` (Spring `Resource.createRelative`, cycle detection by URL); `base-dir` property
    - _Requirements: 15.2_
  - [ ] 4.3 Multiple scripts: `LoomRuntimes` bean, `LoomRuntime` bean for `default`/the only one
    - _Requirements: 15.1_
  - [ ] 4.4 Start-up validation (strict/lenient) with all problems listed
    - _Requirements: 15.4_

- [ ] 5. Security gate
  - [ ] 5.1 `SecurityAudit` at start-up, `fail-on`, Markdown in the failure; WARN when off
    - _Requirements: 21.1–21.3_
  - [ ] 5.2 Durability guard (suspending constructs × persistence none)
    - _Requirements: 17.6, 20.2_

<!-- PHASE 2: durable runs -->

- [ ] 6. Persistence
  - [ ] 6.1 `RunJournals` (file/jdbc), `TriggerStore` bean (file/jdbc), `RunStore` (file `run.json`, jdbc `llm4j_loom_runs`)
    - _Requirements: 17.1, 17.3_
  - [ ] 6.2 `Llm4jSchemaInitializer` calling library `createTable` methods; `initialize-schema` modes
    - _Requirements: 17.2_
  - [ ] 6.3 Ledger/LevelStore wiring for autonomy
    - _Requirements: 20.1_

- [ ] 7. Resume and answer
  - [ ] 7.1 `resume(runId, invoker)` / `resumeAsync`; RunStore status transitions
    - _Requirements: 17.4_
  - [ ] 7.2 `answer(runId, stepId, text, by)` with state checks and `Answers.match` for choices
    - _Requirements: 17.5_
  - [ ] 7.3 Verify the journal key/kind `human_prompt` and `approve:` read on replay; if they differ, **[lib]**
        `HumanAnswers.record(journal, stepId, answer)` used by CLI and starter
    - _Requirements: 17.5_
  - [ ] 7.4 `runs()`, `run(id)`, `travel(id)`
    - _Requirements: 17.7_

- [ ] 8. People in the loop
  - [ ] 8.1 `SuspendingHumanInterface`, `HumanQuestionEvent`, `HumanQuestionListener`
    - _Requirements: 18.1, 18.2, 18.4_
  - [ ] 8.2 Channels (`ask-via`) with listener lifecycle
    - _Requirements: 18.3_

- [ ] 9. Triggers
  - [ ] 9.1 `LoomTriggerTarget` (resume, start workflow by script name, agent task); invoker-needed runs → `human`
    - _Requirements: 19.2_
  - [ ] 9.2 Verify schedule registration vs. resume store in the library; if needed **[lib]** `setRegisterSchedules`;
        register schedules once per script at start-up
    - _Requirements: 19.3_
  - [ ] 9.3 `TriggerRunner` lifecycle (owner, stale-after, poll); fired events as `ApplicationEvent`s
    - _Requirements: 19.1, 19.5_
  - [ ] 9.4 `TriggerEndpoint` adapters (MVC, WebFlux), token or `BearerVerifier` required
    - _Requirements: 19.4_

<!-- PHASE 3: client safety -->

- [ ] 10. Clients
  - [ ] 10.1 `llm4j.clients.<name>`, `ClientFactory`, `LLMClients`, `llm4j.primary`
    - _Requirements: 3.1, 3.2, 3.4–3.6_
  - [ ] 10.2 `RetryPolicies.from`, `Clock`/`Sleeper` beans
    - _Requirements: 4.1–4.3_
  - [ ] 10.3 Routing (`fallback`, `cost-aware`) over tiers
    - _Requirements: 3.3_
  - [ ] 10.4 `LLMClientCustomizer`
    - _Requirements: 3.7_
- [ ] 11. Budgets: `PriceTable` (file / inline), `Budget` bean, `BudgetedLLMClient`, listeners, Loom `setPriceTable` and overrides
  - _Requirements: 5.1–5.5_
- [ ] 12. Privacy and fairness: `MaskingLLMClient` outermost with metrics/event; `BiasMonitor`
  - _Requirements: 6.1–6.3_
- [ ] 13. Audit logger for agents and executors
  - _Requirements: 7.1, 7.2_

<!-- PHASE 4: agents and tools -->

- [ ] 14. Agents
  - [ ] 14.1 `AgentBuilderAssembler`, collected beans, extended `llm4j.agent.*`
    - _Requirements: 8.1, 8.2, 8.5_
  - [ ] 14.2 Named agents, `Agents` bean
    - _Requirements: 8.3_
  - [ ] 14.3 `ReActAgentBuilderCustomizer`
    - _Requirements: 8.4_
  - [ ] 14.4 Context windows and compaction (agents and Loom `WindowLayers`)
    - _Requirements: 9.1–9.4_
- [ ] 15. Memory: `ConversationStore`, `AgentSessions`, semantic memory
  - _Requirements: 10.1–10.3_
- [ ] 16. Prompts, skills, personas (Spring resources; remote skills opt-in); Loom `setPromptRegistry`
  - _Requirements: 11.1–11.4_
- [ ] 17. Tools
  - [ ] 17.1 `ToolCatalog` with duplicate detection; fills core and Loom registries
    - _Requirements: 12.6_
  - [ ] 17.2 Generic kinds with `check` then `create`; `ToolKind` beans; `*-secret` options; `EffectContext`
    - _Requirements: 12.1–12.3, 12.7_
  - [ ] 17.3 Built-ins
    - _Requirements: 12.4_
  - [ ] 17.4 MCP lifecycle; Loom validation moved to `SmartLifecycle.start` when MCP is configured
    - _Requirements: 12.5_
- [ ] 18. Tasks: `TaskRegistry` from `Task` beans (+ discovered), duplicates fail
  - _Requirements: 14.1–14.3_
- [ ] 19. RAG: embeddings, stores (pgvector from `spring.datasource.*`), `RAGAgent` builder, Loom `EmbeddingFactory`
  - _Requirements: 13.1–13.4_

<!-- PHASE 5: operations -->

- [ ] 20. Observe: session lifecycle, attach per executor, Fabric options, loopback guard
  - _Requirements: 22.1–22.4_
- [ ] 21. Metrics: agent listener, Loom trace listener, trigger gauges; tag review (no free text)
  - _Requirements: 23.1_
- [ ] 22. Health indicators and read-only `loom` endpoint; Observation wrapping
  - _Requirements: 23.2–23.4, 21.4_
- [ ] 23. Run-control web API with `LoomWebSecurityGuard`
  - _Requirements: 24.1–24.4_

<!-- PHASE 6: testing support, docs, gates -->

- [ ] 24. `llm4j-spring-boot-starter-test`: `@AutoConfigureLlm4jMock`, `@AutoConfigureLlm4jLive` (SpendGuard, skip without
      keys), `@Llm4jTest`, replay, `FakeJudge`, `MutableClock`
  - _Requirements: 25.1–25.5_
- [ ] 25. Configuration metadata with hints for every enumeration
  - _Requirements: 26.1_
- [ ] 26. README per capability (YAML · bean · plain Java); root README row; `docs/API_COMPATIBILITY.md`
  - _Requirements: 26.2, 26.4_
- [ ] 27. Example app `src/examples/spring-boot-starter-demo` (in `-Pextended`): durable workflow, approval over the web
      API, mock models
  - _Requirements: 26.3_
- [ ] 28. JaCoCo rules for the autoconfigure module; CI matrix for Spring Boot baseline and latest
  - _Requirements: 27.1–27.3_

---

## Definition of done (every task)

- `mvn verify` green for the changed modules (and `mvn -pl src/loom/ai-agent4j-loom -DskipTests install` before the
  starter when a **[lib]** change is involved, per the steward skill).
- Tests named in `test-strategy.md` for the requirement exist and pass.
- The README section for the capability shows YAML, the bean, and the plain-Java equivalent.
- No new llm4j library module depends on Spring (checked by `NoSpringInLibrariesTest`, task 28).
