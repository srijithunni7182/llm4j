# Implementation Plan

**Prerequisite:** the tasks of [loom-rewind-and-fork](../loom-rewind-and-fork/tasks.md) are done and signed off (forks, generations, simulate,
the identity rule). Task 1 below is then only the `EffectContext` check it needs.

Order: the pure pieces first (statistics, identity), then storage, then the language and the executor, then the
ladder, replay and commands, so each step can be tested alone. Tests are written with each task, not after.
See [verification.md](verification.md).

- [ ] 1. **Prerequisite check** — confirm `EffectContext.simulate()`, `identityStep()`, `OverlayJournal` and `weave fork` from the rewind work are in place; V7.6 passes against them. No new core contract is added here.
- [ ] 2. **Pure pieces** (`io.github.llm4j.loom.autonomy`)
  - [ ] 2.1 `AgreementStats` (Wilson bound, unsafe rate, coverage, malformed). V4.5.
  - [ ] 2.2 `AgentIdentity` over the AST. V6.1.
  - [ ] 2.3 `Rule`, `DemoteRule` and a `LadderEngine` with injected `Clock`: evaluate, explain what is missing, demotions. V3.3–V3.8, V10.4.
- [ ] 3. **Storage**
  - [ ] 3.1 `LedgerRecord`, `Case` (fold), `Ledger` and `LevelStore` interfaces; memory implementations.
  - [ ] 3.2 `FileLedger`/`FileLevelStore` (locked line append, torn-line handling, atomic level file, compare-and-set).
  - [ ] 3.3 `JdbcLedger`/`JdbcLevelStore`.
  - [ ] 3.4 Contract test class run against all three; `FaultLedger`. V2.2–V2.4, V3.12.
  - [ ] 3.5 Masking and retention in the writer. V2.6, V2.7. Reserved paths. V2.8.
- [ ] 4. **Language**
  - [ ] 4.1 AST: `DecisionDef`, `AutonomyDef`, `DecideStatement`; parser with contextual words. V1.1, V1.7, V1.8.
  - [ ] 4.2 `ScriptValidator` checks (R1.3, R1.4, design §1.3). V1.2, V1.3, V1.9.
  - [ ] 4.3 The repository-wide script test and rename test. V1.7.
- [ ] 5. **Executor**
  - [ ] 5.1 `decide`: case id, level read and journaled, propose with evidence capture, branch on level, ask, bind, trace and audit. V1.4–V1.6, V2.1, V2.3, V2.5, V3.2, V3.10.
  - [ ] 5.2 Blindness: separate question builder, hidden journal key, redacted trace and audit. V4.1, V4.2.
  - [ ] 5.3 Limits, audit sampling, freeze, ceiling. V3.5, V3.6, V3.8, V3.11.
  - [ ] 5.4 Malformed proposals and failure paths (design §8). V8.2, V8.4.
- [ ] 6. **Ladder in operation**
  - [ ] 6.1 Promotion proposals through the durable approval path; `auto`. V3.9, V3.12.
  - [ ] 6.2 Demotion on every `Decided`/`Outcome`. V3.7, V5.5.
  - [ ] 6.3 Epochs and `on_change` (shadow, keep; replay after task 7). V6.2, V6.4–V6.6.
  - [ ] 6.4 Notification hook through the effect journal. V5.7.
- [ ] 7. **Replay** (on the rewind work's ephemeral fork)
  - [ ] 7.1 Journal contents of a case: `#decide-task`, `#decide-evidence`, `#decide-proposal`, `#level`; the decide step as a nameable boundary. V2.5.
  - [ ] 7.2 `ReplayTools`: evidence-backed reads, `replay: allow`, `--live-reads`, `--no-memory`. V7.5, V7.6.
  - [ ] 7.3 The per-case replay: overlay fork at the decide step, simulate, null human/ledger/levels, `stop_at`, read the proposal; prefix-drift check. V7.1–V7.4, V7.8.
  - [ ] 7.4 Selection, seed, `--repeat`, skip reasons, retention-aware journal lookup. V7.7, V7.9, V2.7.
  - [ ] 7.5 Report model, Markdown and JSON, masking. V7.12.
  - [ ] 7.6 Durable replay log, `--resume`, budgets. V7.10.
  - [ ] 7.7 `--policy`. V7.11. The by-hand equivalence check. V7.15.
  - [ ] 7.8 `on_change: replay` wired into epochs. V6.3, V7.16.
  - [ ] 7.9 Rewound cases: generation folding and `superseded`. V2.9.
  - [ ] 7.10 Scale and memory. V7.14.
- [ ] 8. **Commands** — `weave autonomy status|history|promote|demote|freeze|unfreeze|outcome`, `weave replay`, JSON output, exit codes. V5.1–V5.8, V7.12.
- [ ] 9. **Safety suites** — hostile-model suite for decisions, fail-closed fault injection, secrets sweep. V8.1–V8.6.
- [ ] 10. **Docs and tooling** — guide section, prompt, READMEs, VS Code grammar and hover; checks for them. V9.1–V9.4. No sample.
- [ ] 11. **Quality** — JaCoCo rules, property tests, the sabotage driver for V10.5, regression run. V10.1–V10.5.
- [ ] 12. **Completion** — scripts for G1, G2, G4, G6, G8 (extend the generic-tools scripts), run G0–G9, write `evidence/` and `SIGN-OFF.md`.
- [ ] 13. *(optional, needs accounts)* Live checks L1–L2.
