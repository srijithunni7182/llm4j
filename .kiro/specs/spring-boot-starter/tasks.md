# Implementation Plan: Spring Boot Starter

## Overview

Four phases. Phase 1 settles the two unknowns and builds `LoomHost` in the library (the only real new behaviour). Phases 2–4 are wiring.
Each phase leaves `mvn verify` green. Library work is marked **[lib]** and lands, with tests, before the starter uses it
(`mvn -pl src/loom/ai-agent4j-loom -DskipTests install` first, per the steward skill).

---

## Tasks

<!-- PHASE 1: LoomHost (library) -->

- [x] 1. Resolve the unknowns first
  - [x] 1.1 Read how `weave answer` / `Answers` / `ApprovalGate` store and replay a person's answer. Result: answers live in a question store, not the journal; the host keeps its own `AnswerBook`
    - _Requirements: 8.4_
  - [x] 1.2 Check whether `HarnessExecutor.initialize()` registers `schedule` triggers on every run. Result: it does, idempotently; no flag needed
    - _Requirements: 8.6_
- [x] 2. Loading from jars **[lib]**
  - [x] 2.1 `ScriptSource`, `LoomLoader.load(location, source)`, `LootLoader` Reader overloads; path-based methods delegate; regression test over every repository `.loom`
    - _Requirements: 8.8_
  - [x] 2.2 (dropped: the host writes `run.json` with `weave`'s keys itself; no CLI change)
    - _Requirements: 8.3_
- [x] 3. `LoomHost` **[lib]**
  - [x] 3.1 Types: `RunRequest`, `RunOutcome`, `RunRecord`, `LoomRunFailedException`, `QuestionListener`, `Persistence` (`none`, `file`, `jdbc`)
    - _Requirements: 8.1, 8.2_
  - [x] 3.2 Executor assembly (single ordered method) and `run` / `start` (executor shut down on every outcome) / invoker credentials
    - _Requirements: 8.1, 8.2, 8.7_
  - [x] 3.3 File and JDBC `RunStore`; `createSchema`; weave-compatible layout; credentials never stored
    - _Requirements: 8.3, 8.7_
  - [x] 3.4 `resume`, `resumeAsync`, `answer`, `runs`; suspending human interface and `QuestionListener`
    - _Requirements: 8.2, 8.4, 8.5_
  - [x] 3.5 `triggerTarget`, `startTriggers`/`stopTriggers`, one-time schedule registration
    - _Requirements: 8.6_
  - [x] 3.6 Durability guard (`DurabilityGuard.check`) usable by hosts and the starter
    - _Requirements: 8.7, 9.5_
  - [x] 3.7 `docs/API_COMPATIBILITY.md`; a short "Run workflows from your application" section in `LOOM_GUIDE.md` (its blocks are parse-tested)
    - _Requirements: 8.9, 13.2_

<!-- PHASE 2: clients and safety -->

- [x] 4. Starter skeleton
  - [x] 4.1 Move v1 to `src/spring/llm4j-spring-boot-starter`; split into the auto-configuration classes of design §1; update root `pom.xml`
    - _Requirements: 1.1, 1.2_
  - [x] 4.2 Module guard `EnvironmentPostProcessor`, `Llm4jConfigurationException`, `FailureAnalyzer`; v1 shorthand mapping
    - _Requirements: 1.3, 1.4_
  - [x] 4.3 Secrets: stores, `CredentialProperty`, sanitizing, `toString` masking
    - _Requirements: 2.1–2.4_
- [x] 5. Clients: `llm4j.clients`, `LLMClients`, primary, routing, `RetryPolicies`, customizers
  - _Requirements: 3.1–3.5_
- [x] 6. Decorators: `PriceTable`, `Budget` + `BudgetedLLMClient`, `MaskingLLMClient` outermost, `FileAuditLogger`
  - _Requirements: 4.1–4.4_

<!-- PHASE 3: agents, tools, rag -->

- [x] 7. Agents: builder assembly with collected beans, context/compaction, `ConversationStore`, `PromptRegistry`, skills, customizers
  - _Requirements: 5.1–5.5_
- [x] 8. Tools: `ToolCatalog` (beans, built-ins, kinds with `check`, `-secret` options, duplicates), `TaskRegistry`
  - _Requirements: 6.1–6.4_
- [x] 9. RAG: embeddings, stores (pgvector from `spring.datasource.*`), `EmbeddingFactory` to Loom
  - _Requirements: 7.1–7.3_

<!-- PHASE 4: Loom in Spring -->

- [x] 10. `LoomRuntime` over `LoomHost`: resource script/loot loading, models, validation, security gate, durability guard, executor choice, concurrency bound
  - _Requirements: 9.1–9.8_
- [x] 11. Persistence wiring (`file`/`jdbc`, schema initializer), question events, invoker pass-through
  - _Requirements: 10.1–10.5_
- [x] 12. Lifecycle: graceful shutdown, trigger runner `SmartLifecycle`, fired events
  - _Requirements: 9.6, 11.1, 11.2_
- [x] 13. Autonomy stores and the loom-inspect session
  - _Requirements: 12.1, 12.2_
- [x] 14. Metadata (descriptions, hints), README per capability with the deferred list, root README row
  - _Requirements: 13.1, 13.2_
- [ ] 15. Verification tooling: fixtures, `@Tag("SB-V…")` on every test, `scripts/verify_spec.py` `MODULES` extended, `scripts/verify-spring-starter.sh`
        (gates G0–G9), `scripts/sabotage_spring_starter.py`, run the gates at one commit and commit the evidence
  - _Requirements: 14.1–14.3_

---

## Definition of done (every task)

- `mvn verify` green for the changed modules; library changes installed before the starter builds against them.
- The tests named in `verification.md` for the requirement exist, are tagged, and pass.
- The README section shows YAML, the bean, and the plain-Java equivalent.
- No library module depends on Spring (gate G4).
