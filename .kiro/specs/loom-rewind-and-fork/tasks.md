# Implementation Plan

Order: the identity rule and the generation model first (they carry the risk), then the language, then the executor, then the
operator commands and fork, so each step is testable alone. Tests are written with each task. See [verification.md](verification.md).

- [x] 1. **Test support** — journal matrix (memory, file, JDBC/H2), `FaultJournal` with "before/after the Nth write", recording effect tool, scripted models/people, golden journals recorded from the baseline commit.
- [x] 2. **Core contract** — in `ai-agent4j`: `EffectContext.identityStep()` (default `currentStep()`) and `simulate()` (default false); `EffectTool` builds keys from `identityStep()` and honours `simulate()`. Tests V4.8 (effects part); existing implementers unchanged.
- [x] 3. **Generation model** (`io.github.llm4j.loom.runtime`)
  - [x] 3.1 `Boundaries` (the `__boundaries` entry: read, append, validate) and `Generations.current(parent, key, i)`. V3.2, V3.3.
  - [x] 3.2 `OverlayJournal`. V5.10 (part).
  - [x] 3.3 Run-directory format marker and its check. V3.7.
- [x] 4. **Language**
  - [x] 4.1 AST `CheckpointStmt`, `RewindStmt`; parser with contextual words. V1.1, V9.1.
  - [x] 4.2 Validator checks and the effect-reach analysis. V1.2, V2.3, V2.6, V2.9, V4.10.
- [x] 5. **Executor**
  - [x] 5.1 `runBlock` with generations; step ids with suffix; boundary-first resume. V3.2, V3.8.
  - [x] 5.2 `checkpoint`: journal entry, snapshot, initial values. V1.3–V1.5.
  - [x] 5.3 `rewind`: decision, journaled "no rewind", counts, caps, carried values, variables restore, `RewindSignal`. V2.1–V2.5, V2.8, V2.10, V2.11, V3.4, V3.5.
  - [x] 5.4 Identity rule for effects, approvals, human answers; usage keys with the generation; `restoreSpend`. V4.1, V4.2, V4.6, V4.7, V3.6, V6.1–V6.3.
  - [x] 5.5 Effects policies: `ask first` scan, `if blocked`, the pause-and-ask path, `keep`, `repeat`. V4.3–V4.5, V4.9.
  - [x] 5.6 `rewind` inside `on_failure` (`_error`). V2.7.
  - [x] 5.7 `--stop-at`, `RunStopped`, exit code 5. V5.9.
  - [x] 5.8 Trace, audit, console marks, `_generation`. V7.1–V7.3.
- [x] 6. **Simulate** — run mode in `run.json`, `RunEffectContext.simulate()`, `SimulatingTool` for non-`Effectful` tools by class. V4.8, V8.5.
- [x] 7. **Operator commands**
  - [x] 7.1 `RunTravel`: timeline model, rewind, reset, fork (materialized), prefix signature. V5.1–V5.8.
  - [x] 7.2 picocli commands `timeline`, `rewind`, `reset`, `fork`, `--stop-at`; lock handling, `--reason`, `--force`, `--resume` and triggers. V5.2, V5.11, V5.12.
  - [x] 7.3 Ephemeral fork entry point for code. V5.10.
- [x] 8. **Safety suites** — hostile-model cases, fork-never-writes-parent with fault injection, secrets sweep. V8.1–V8.6.
- [x] 9. **Property and performance tests** — random scripts with crashes (V9.5), performance bounds (V9.2).
- [x] 10. **Docs and tooling** — guide section, prompt, READMEs, VS Code grammar and hover; checks. V9.3. (No sample.)
- [x] 11. **Quality** — JaCoCo rules, the sabotage driver for V9.7, regression run. V9.4, V9.6, V9.7.
- [x] 12. **Completion** — extend the verification scripts for G1, G2, G4, G6, G8; run G0–G9; write `evidence/` and `SIGN-OFF.md`.
