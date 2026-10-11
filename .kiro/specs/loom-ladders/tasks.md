# Implementation Plan

**Prerequisite:** [loom-earned-autonomy](../loom-earned-autonomy/tasks.md) is done and signed off (ledger, levels, identity, blindness, replay, commands).

Order: the pure language first (it can be tested alone), then the generalised ledger, then the engine, then compatibility (the `trust` desugaring is
the proof the language is enough), then tooling. Tests are written with each task. The acceptance test (task 9) decides whether the design is done.

- [x] 1. **Expression language** (`io.github.llm4j.loom.autonomy.expr`)
  - [x] 1.1 `Expr`, `Value` (incl. `Undefined`, `Ratio`), the lexer/parser for §1.1 expressions. R3.
  - [x] 1.2 Type checker: names, collection methods, measures, durations/percentages. R3.8.
  - [x] 1.3 Interpreter: three-valued logic reduced to `false` at the top; collection methods; horizon analysis. R3.1–R3.7.
  - [x] 1.4 `distance` (by type) and the Wilson interval as the only Java primitives. R8.2.
  - [x] 1.5 Property tests: always terminates; never throws; undefined never makes a climb true.
- [x] 2. **Language integration**
  - [x] 2.1 AST: `LadderDef`, `RungDef`, `MeasureDef`; `DecisionDef` gains `ladder`, `ceiling`, `proposes`. Parser with contextual words. R1, R2, R6.
  - [x] 2.2 `ScriptValidator`: R1.4, name resolution, `slip to` direction, parameters, recursion among measures, binding conflicts with `trust`. R6.4.
  - [x] 2.3 Imports of ladders and the `loom/autonomy` resource. R1.6, R8.
  - [x] 2.4 Repository-wide load test extended to the new words. R10.4.
- [x] 3. **Generalised ledger**
  - [x] 3.1 `Case` fields (proposal value, response, timing, served model, outcomes with values); `Cases.fold` for old and new records. R4.1, R10.2.
  - [x] 3.2 Records `RESPONDED`, `OUTCOME(name, value)`, `EPOCH`; contract test extended and run against memory, file and JDBC. R4.5.
  - [x] 3.3 Artifact storage (journal reference, hash, head) and retention stripping. §3.1.
  - [x] 3.4 Served-model capture in the provider adapters; `unreported` default. R4.4.
  - [x] 3.5 `weave autonomy outcome --value`, and the workflow statement to record an outcome. R4.3.
- [x] 4. **Engine**
  - [x] 4.1 Rung modes in `Engine`: `blind`, `confirm`, `autonomous`, `sample`, `escalate`. R5.
  - [x] 4.2 Evaluate slip and climb after each response and outcome; ceiling; approval and automatic climbing. R6.1, §4.
  - [x] 4.3 `decide` for artifact decisions; `result`, `result_proposal`, `result_level`. R6.5.
  - [x] 4.4 Epochs: identity change, served-model change, `when the agent changes:` default and warning. R7.1–R7.3.
  - [x] 4.5 Rules fingerprint and re-evaluation at load; recorded rule texts. R7.4, R7.5.
- [~] 5. **Compatibility** (the `trust` block is untouched; **not** desugared)
  - [ ] 5.1 Desugar `trust` to a ladder; golden test against the old engine over the existing ledgers. R10.1, R10.3.
  - [ ] 5.2 Remove the old rule evaluator once 5.1 passes.
- [x] 6. **Standard library**
  - [x] 6.1 Write `loom/autonomy` in Loom; test each measure. R8.1.
  - [x] 6.2 Generated `dangerous(c)` from the decision's declarations.
- [x] 7. **Commands and audit**
  - [x] 7.1 `weave autonomy status` shows each term. R7.6.
  - [x] 7.2 `weave ladder describe` with golden files; `weave ladder test`. R11.2, R11.3.
  - [x] 7.3 `weave audit` rules. R9.5.
  - [x] 7.4 `weave check` warnings (R4.4, R5.3, R7.2) and messages with suggestions. R11.1.
- [~] 8. **Editor support** (words, hover text and the scanner; no snippet) — grammar and LSP in `vscode-loom`: highlighting, completion of rungs, measures and collection methods. R11.4.
- [x] 9. **Acceptance test** — the refund, script-author and blog-publisher ladders as test resources, each driven through climb, slip, ceiling,
  epoch change (model and served model) and ladder edit, with no per-ladder Java; the refund ladder compared with the desugared `trust` block. R12.
- [x] 10. **Docs** — LOOM_GUIDE "Earned Autonomy" section rewritten around ladders, measures and `history`; the `trust` block documented as sugar;
  worked examples for the three agents; migration notes.
- [ ] 11. **Verification and sign-off** — a `verification.md` mapping each acceptance criterion to a test, and the safety checks of loom-earned-autonomy
  G3 (sabotage) and G9 (safety) re-run against the new engine.


`[x]` done, `[~]` done in part (see [evidence/IMPLEMENTATION-NOTES.md](evidence/IMPLEMENTATION-NOTES.md)). Task 5.1 and 5.2 (desugaring `trust`) are not done. Task 11 is in [evidence/VERIFICATION-RESULTS.md](evidence/VERIFICATION-RESULTS.md).
