# Verification

A phase is done when every criterion below passes and the exit checklist is ticked.

## Acceptance criteria

| # | Criterion | Requirement | How |
|---|---|---|---|
| V1.1 | `halt "x {v}"` parses; `halt` with no text is an error with its line | R1.1, R1.7 | parser test |
| V1.2 | A halt ends the run with status `halted`, reason in journal, trace, audit and the last console line | R1.2, R1.5 | scripted workflow |
| V1.3 | A halt inside a step with `retry 2`, inside `on_failure`, inside a `rewind` scope and inside a loop with `on_exhausted` is **not** retried, does not run the handler, does not rewind | R1.3 | one test per construct |
| V1.4 | A halt in one `parallel` branch stops the others at their next step; a halt inside a `call` ends the caller | R1.4 | scripted |
| V1.5 | The reason is secret-scrubbed and PII-masked like a `note` | R1.5 | guard test |
| V1.6 | A halted run resumed reports the same halt and runs nothing; `weave reset` clears it | R1.6 | journal test |
| V1.7 | A statement after `halt` in the same block gives a warning | R1.7 | check test |
| V1.8 | The graph shows a terminal halt node (no edge to the next statement) | R1.8 | graph golden |
| V2.1 | `set x = "{a}"`, number, `true`, ref and nested object literal parse | R2.1 | parser test |
| V2.2 | `return { verdict: "PASSED" }` binds the caller's `-> r`; `{r.verdict}` and `alt (r.verdict == "PASSED")` work | R2.2, R2.4 | scripted |
| V2.3 | A workflow with no `return` and no `result` returns nothing, as today | R2.3 | scripted |
| V2.4 | A number stays a number: `alt (r.score > 3)` compares numerically | R2.4 | scripted |
| V2.5 | `set _x = …` is an error; a variable named `set` or `return` still works | R2.5, R2.7 | parser test |
| V2.6 | Unread `set` targets warn as an unread `delegate` result does | R2.6 | lint test |
| V3.1 | `x is ok` and `x is not ok` parse in `alt`, `loop until` and `rewind when` | R3.1, R3.6 | parser test |
| V3.2 | A task's `okOutcomes()` default is `{ok}`; `RunCommand`-style `{passed}` is honoured | R3.2 | unit test |
| V3.3 | `x is ok` is true for an ok outcome and **false** for another outcome, an unexpected word, a missing variable and a step that never ran | R3.3 | table test |
| V3.4 | `x is not ok` is the exact negation in every row of V3.3 | R3.4 | same table |
| V3.5 | In a simulated run, a simulated step is ok; `weave eval --mock` of a script that loops `until (x is ok)` reaches the end | R3.5 | eval --mock |
| V3.6 | A real run never produces a simulated step, so the clause cannot weaken it | R3.5 | property test over the task runner |
| V3.7 | `x is ok` on a `delegate` variable is a load error naming the line | R3.7 | check test |
| V3.8 | `x.outcome == "passed"` is unchanged, including false in a simulated run | R3.8 | regression test |
| V4.1 | Every reader of a script (explain, graph, VS Code, audit, eval4j) handles the statements | R4.1 | one test each |
| V4.3 | `haltedWith` and `didNotHalt` assert correctly | R4.3 | eval4j test |
| V4.5 | Fabric lists a halted run with status and reason | R4.5 | fabric test |
| V4.6 | `weave run` exits 6 on a halt, and the other codes are unchanged | design §4 | CLI test |
| V5.1 | `Gate`, `Halt`, `Verdict` are gone from nirmaan-yantra; its `weave check` has no new errors | R5.1 | build |
| V5.2 | Its trajectory tests pass with the same assertions and call counts (smooth run = 7 model calls) | R5.2 | mvn test |
| V5.3 | Every check that was fail-closed is still fail-closed (the `Gate` unit-test table is ported to a `weave eval` scenario) | R5.3 | ported tests |
| V7.1 | A correct script with variables consumed only by `run` arguments has no "never used" warning | design §7 | lint test |

## Quality gates

- Coverage thresholds of the module are unchanged and met; the new parser and evaluator code has branch coverage on every row of V3.3.
- Mutation check on `ConditionEvaluator.isOk`: flipping the simulated clause or the fail-closed default must fail a test.
- `scripts/sync-graph-render.sh --check` and `scripts/check-graph-render-sync.sh` pass.

## Exit checklist

- [ ] Every criterion above is green.
- [ ] `LOOM_GUIDE.md`, `llms.txt` and the VS Code README describe the statements and the exit code, with samples tested in a test.
- [ ] nirmaan-yantra migrated and its README updated (task count, no `Gate`/`Halt`/`Verdict`).
- [ ] The result of this file is recorded below.

## Result

Built 2026-10-16. `WorkflowControlTest` (15 tests) covers V1.1 to V1.8 (halt through retry, on_failure, on_exhausted, call, parallel and a resumed journal; the unreachable-statement warning), V2.1 to V2.7 (typed values, an object result read by a caller, the missing-variable error, the reserved-name error, return in parallel), V3.1 to V3.8 (the table of outcomes for `is ok` and `is not ok`, a step that never ran, a loop `until (x is ok)`, a simulated step, the `is ok` on a non-run variable error) and V7.1. Not yet done: V4.1 for VS Code's own tests, V4.3 (eval4j assertions), the Fabric page's own label and colour for a halted run, and V5.1 to V5.3 are met by the migration in `src/examples/nirmaan-yantra` (its tests are run there).
