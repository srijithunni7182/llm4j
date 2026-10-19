# Phase 7 verification: classification

Companion to [DESIGN.md](DESIGN.md) and [TEST_STRATEGY.md](TEST_STRATEGY.md). The phase is done when every box below is ticked and the result
is recorded in §6.

## 1. Sub-phases

| # | Sub-phase | Delivers |
|---|---|---|
| 7a | Contract and chat backend | `io.github.llm4j.classify` types, `LlmClassifier`, `ScriptedClassifier`, `ClassifierContract` |
| 7b | eval4j | `llmJudged(...).classifier(...)`, cache/telemetry integration, `CalibrationRunner` mode |
| 7c | Jev provider | `JevProvider`, `JevWire`, `jev` as a provider name, resolver; blocked on DESIGN Q1 |
| 7d | Loom | `classifier` block, `classify` statement, checks, execution, trace event, budget, audit, graph |
| 7e | Tooling and docs | VS Code grammar/completion/diagnostics, wiki page, Loom guide chapter, `llms.txt`, examples |

## 2. Acceptance criteria

| # | Criterion | Requirement | Test |
|---|---|---|---|
| A1 | Both backends pass the same `ClassifierContract` suite | R1, R2 | `ClassifierContract` subclasses for `LlmClassifier`, `JevProvider` |
| A2 | Invalid replies (unknown label, missing/extra question, bad probability, truncated) never become values | R1, R2, R4 | unit + property tests |
| A3 | Injection corpus does not change answers; the state is delimited | R3 | security tests |
| A4 | `LlmClassifier` answers are never `calibrated`; `JevProvider` answers are | R5 | contract tests |
| A5 | The Jev key never appears in exceptions, logs, events or traces | R6 | canary-key test |
| A6 | A judge backed by a classifier passes/fails at the threshold and keeps `samples`, cache, dimensions and telemetry | R10 | eval4j tests |
| A7 | `.judge(LLMClient)` and every existing judge test behave exactly as before | R11 | full existing eval4j suite |
| A8 | `classify` routes an `alt` on label and confidence; the three confidence actions work at the boundaries | §5.2, §5.3 | Loom execution tests |
| A9 | `unsure` appears only from a confidence rule | R7 | Loom tests |
| A10 | `weave check` rejects: unknown classifier, unbound `on`, duplicate or fewer than two labels, a declared `unsure`, an `alt` on a label never produced, a `confidence` line with no available confidence | R8 | validator tests |
| A11 | A classify step is charged to the budget and a breach halts | R9 | budget tests |
| A12 | The trace and call telemetry carry backend, model, tokens, latency, outcome; no state text at the default detail level | R12 | trace tests |
| A13 | A script or test with no classifier gives byte-identical output to the previous release | R11 | regression test |
| A14 | `weave audit` lists a hosted classifier backend as a data flow; the graph marks `uncalibrated` | §5.5 | audit and graph tests |
| A15 | `Classifier.using(chat)` and `Classifier.using(jev)` can be compared on a dataset with `CalibrationRunner`, giving agreement, calibration curve, cost and latency | §7.3 | eval4j calibration tests |
| A16 | All documentation samples execute in tests | docs | docs examples test |

## 3. Traceability

Filled in as tests are written: one row per requirement, `R1` … `R12`, naming the test classes that cover it. A requirement with no row blocks
the phase.

| Req | Tests |
|---|---|
| R1–R12 | _to be listed when the tests are written_ |

## 4. Quality gates

- The repo's own build, formatting, and static checks pass on every changed module.
- Line and branch coverage on the new packages meets the repository's existing thresholds; mutation testing (PIT) is run on `classify` and its
  validators like the other core packages.
- `weave check` and `weave audit` run clean on all new example scripts.
- No new runtime dependency in ai-agent4j (Jev goes through `HttpClientWrapper`).
- Public types have Javadoc; the wiki code samples are executed (A16).

## 5. Manual and live checks (owed, opt-in)

- [ ] A recorded session against the real Jev API replaces the fixtures (Q1), and the contract suite still passes.
- [ ] `Classifier.using(jev)` vs `Classifier.using(chat)` on a real intent set and a real judging dataset: results recorded in §6.
- [ ] An end-to-end triage workflow in Loom with Jev, run once with `--observe` to see the call events in Fabric.
- [ ] The VS Code extension on the new syntax: highlighting, completion, diagnostics.

## 6. Documentation owed

Per the spec method, at the end of each sub-phase: the wiki page (`src/ai-agent4j/wiki/Classification.md`), its executed samples, Javadoc, module
READMEs and hub links, `llms.txt`, and the Loom guide chapter. Once, at the end of the whole phase: the stack-level pages (root `README.md`,
`wiki/Home.md`, `docs/README.md`, `docs/THE_STACK.md`) and the `llm4j-workflow-guide` skill (so it can advise on `classify` and on a
classifier-backed judge).

## 7. Result

_Not started._
