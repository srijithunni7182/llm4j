# Implementation Plan

Order: the language and the executor semantics that carry risk (halt versus failure), then `is ok`, then tooling, then the proof.
Tests are written with each task. See [verification.md](verification.md).

- [x] 1. **Test support**: a scripted workflow harness that asserts the run's final status, the journal entries and the trace for a script; golden `weave check` outputs for the new errors and warnings.
- [x] 2. **Lexer and parser**: `halt`, `set`, `return` (contextual), object literals, `is ok` / `is not ok` in conditions. V1.1, V2.1, V2.7, V3.1.
- [x] 3. **Lint fix**: `WorkflowLint` reads `run` arguments (`Arg`), and knows `SetStmt`. Removes today's false "never used" / "never runs" warnings. V7.1.
- [x] 4. **`halt`**
  - [x] 4.1 `RunHalted`, `executeStatement`, journal entry, trace, audit. V1.2, V1.5.
  - [x] 4.2 Let it through `retry`, `on_failure`, `rewind`, `on_exhausted`, `parallel`, `call`. V1.3, V1.4.
  - [x] 4.3 Resume replays a halt; `weave reset` clears it. V1.6.
  - [x] 4.4 Exit code 6 and `weave run` output. V4.6.
- [x] 5. **`set` and `return`**: values (text, number, bool, ref, object), `ReturnSignal`, caller binding, typed paths. V2.2 to V2.6.
- [x] 6. **`is ok`**
  - [x] 6.1 `Task.okOutcomes()`; `TaskRunner` records `_task` (hidden). V3.2.
  - [x] 6.2 `ConditionEvaluator.isOk`; simulated clause; fail-closed cases. V3.3 to V3.6.
  - [x] 6.3 Validator rule for non-`run` targets. V3.7.
- [x] 7. **Tooling**: check rules; explain sentences; graph nodes (JSON and Mermaid); renderer sync; VS Code grammar, snippets, viewmodel. V4.1, V4.2, V1.7, V1.8.
- [ ] 8. **eval4j**: `haltedWith`, `didNotHalt`. V4.3. (Not done: `LoomTrace` does not carry the halt yet.)
- [x] 9. **Observability**: `run_end.status = halted` is understood by loom-inspect (shown as stopped). V4.5. (A distinct colour and label on the Fabric page: not done.)
- [x] 10. **Docs**: `LOOM_GUIDE.md` (statements, conditions, exit codes), `llms.txt`, the guide's chapter 6 ("a mandatory step" patterns), the VS Code README. Per `docs/specs/README.md`.
- [x] 11. **The proof**: migrate `src/examples/nirmaan-yantra`: delete `Gate`, `Halt`, `Verdict`, their registrations and tests; use the constructs; keep every trajectory assertion. V5.1 to V5.3.
