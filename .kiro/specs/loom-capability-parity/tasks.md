# Implementation Plan: Loom Capability Parity (P0 + P1)

## Overview

Two phases, each shippable on its own:

1. **P0, honesty.** A validation pass that collects every problem at load time, rejects syntax the runtime
   doesn't honour, fixes skills and routing strategy, adds `weave check`, and corrects the docs.
2. **P1, capability.** Tool declarations and built-ins, knowledge bases that retrieve, tool approvals,
   `max_iterations`, and agents writing to the script's audit log.

Defaults (decided): unsupported syntax is an error (`--lenient` makes it a warning); secrets only from
`env.NAME`; retrieval in context by default; approvals keyed by tool and arguments.

---

## Tasks

<!-- PHASE 1: P0 -->

- [x] 1. Validation pass
  - [x] 1.1 Line numbers on `AgentDef`, `KnowledgeDef`, `RoutingPolicyDef`, `McpServerDef`, `GuardrailStmt`, `MemoryConfig`
    - _Requirements: 1.1–1.4_
  - [x] 1.2 `ScriptValidator`, `Problem`, `LoomLoadException` (lists every error with its line); env lookup injectable
    - _Requirements: 1, 3.2, 3.3_
  - [x] 1.3 Checks: agent memory, guardrail types, unknown tool/knowledge/routing/mcp names
    - _Requirements: 1.1–1.4_
  - [x] 1.4 `setLenient` downgrades only unsupported-syntax problems
    - _Requirements: 1.6_
  - [x] 1.5 MCP start-up failure is a load error
    - _Requirements: 1.5_

- [x] 2. Honour what's written
  - [x] 2.1 Skills appended with persona or system_template; unloadable skill is a load error
    - _Requirements: 2.1, 2.3_
  - [x] 2.2 Routing `strategy`: cost_aware | fallback; unknown is an error
    - _Requirements: 2.2_

- [x] 3. `weave check` (and `weave run --lenient`)
  - _Requirements: 3.1–3.3_

- [x] 4. Migrate repository scripts (tier2-test.loom, tantrik-console agents.loom, any found by `weave check`)
  - _Requirements: 8.2_

- [x] 5. Docs, P0 part: remove the `approve` workflow-step claim and the inline knowledge/memory example;
      "Not supported yet" note
  - _Requirements: 2.4, 8.4_

- [x] 6. Checkpoint: Loom, GetViral and Engram suites green

<!-- PHASE 2: P1 -->

- [x] 7. Tool declarations
  - [x] 7.1 Parser: contextual `tool Name { use: … }`, option values incl. `env.NAME`; `ToolDef`
    - _Requirements: 4.1_
  - [x] 7.2 `ToolKind`, `ToolFactory` with duckduckgo, serpapi, google_search, openapi, calculator, datetime, current_time, class; `addToolKind`
    - _Requirements: 4.2_
  - [x] 7.3 Validation: secrets only from env, missing env/required/unknown option/kind
    - _Requirements: 4.3, 4.4_
  - [x] 7.4 Built-in names; resolution order; `NamedTool`
    - _Requirements: 4.5–4.8_

- [x] 8. Knowledge bases
  - [x] 8.1 Parser: source/path, chunk_size, overlap, embedding, store, top_k, mode; `type:` deprecated
    - _Requirements: 5.1_
  - [x] 8.2 `EmbeddingFactory`, `DefaultEmbeddingFactory` (gemini; onnx/djl via addons)
    - _Requirements: 5.3_
  - [x] 8.3 `KnowledgeIndexer`: file listing, text extraction, chunking, stores, manifest, incremental update
    - _Requirements: 5.2, 5.4, 5.7, 5.8_
  - [x] 8.4 `Retriever`: context mode in `executeDelegate` (not on replay); tool mode `search_<kb>`
    - _Requirements: 5.5, 5.6_

- [x] 9. Approvals
  - [x] 9.1 Parser `approve: [..] | all`; validation (names among the agent's tools, human interface present)
    - _Requirements: 6.1, 6.6_
  - [x] 9.2 `ApprovalTool`, `ApprovalGate` (journaled, keyed by tool+args hash, durable), audit with masked args
    - _Requirements: 6.2–6.5, 6.7_

- [x] 10. Agent settings: `max_iterations`; agents use the executor's audit logger and session id
  - _Requirements: 7.1, 7.2_

- [x] 11. Docs, P1 part: LOOM_GUIDE "Tools, Knowledge and Approvals"; READMEs; LOOM_PROMPT; VS Code
      grammar and hovers; parse-checked examples
  - _Requirements: 8.3_

- [ ]* 12. Live check (needs a key): a real Gemini-embedded knowledge base answers a question from a document

- [x] 13. Final checkpoint: all suites green; README scorecard updated

## Notes

- Tasks marked `*` are optional or need your API key.
- No new runtime dependencies: HTML is stripped with a regex; PDF waits for P2.
- `.loot` and Java-registered tools keep working exactly as before.
