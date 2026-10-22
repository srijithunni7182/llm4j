# Phase 13 test strategy: time-travel debugging

Status: **draft for review**. Requirements `R1`…`R44` are in [DESIGN.md](DESIGN.md); the traceability matrix is in [VERIFICATION.md](VERIFICATION.md).

## 1. Principles

- **One invariant carries the phase: observe-replay equals the real run.** For every workflow in the corpus, a real (scripted, phase 11) run produces a journal and a step trace; observe-replay over that journal must produce the same step sequence and the same decisions. This is a property test over the corpus and over generated random workflows (§3), and it is the first test written.
- **Nothing is ever written to the original.** Every command that takes a run is tested by hashing the run directory before and after.
- **Nothing has an effect.** Instrumented collaborators fail the test if a model, tool, task with reach, sleep or network is touched during `why`, `back`, the overlay, or a simulated `whatif`/`step`.
- **Old behaviour is pinned.** `weave run`, `resume`, `rewind`, `fork`, `timeline`, `replay` outputs and journals are compared byte-for-byte before and after the `DECISION_POINT` event and `StepGate` seams exist.
- **The stepper is tested through its transcript.** A scripted session (`--replay`) drives it; no TTY is needed. DAP is tested with a protocol-level client.
- **Hostile text is data.** Variable values, conditions' string literals and agent replies containing escape sequences, markup and very long lines render safely in text, md and JSON.

## 2. Levels

| Level | What | Examples | Requirements |
|---|---|---|---|
| **Observe-replay** | Equals the real run (corpus + generated workflows); stops cleanly at the real stop point; diverged-script and pruned-journal cases; tasks with `reach: none` re-executed, others not; non-deterministic task detected by `--verify` | `ObserveReplayEqualsRunTest`, `ObserveReplayRandomWorkflowsTest`, `DivergenceTest`, `VerifyNondeterministicTaskTest` | R1 to R4, R43 |
| **Decision events** | Each kind (R5) emitted with condition text, operands, provenance, result; absent when not requested; masked values; truncation | `DecisionEventTest` (one case per kind), `DecisionMaskedTest`, `DecisionOffByDefaultTest` | R5, R6, R44 |
| **Why** | Golden outputs for: straight line, `alt`, nested `alt`, loops with exits by condition/max/on_exhausted, `for each`, `intentclassify` with `unsure`, retry with failure categories, `on_failure`, suspended, failed (`BROKEN`), rewind generations, decide; `why <step>` for ran and not-ran steps | `WhyTest`, `WhyNotRanTest`, `WhySuspendedTest`, `WhyFailedTest`, `WhyRewindTest` | R7 to R10 |
| **Determinism / environment** | `why` twice identical; works with no secrets/network/models; 5,000-step run under 2 s | `WhyDeterministicTest`, `WhyOfflineTest`, `WhyPerformanceTest` | R12, R13 |
| **Output formats** | JSON schema v1 valid and stable; md renders; text golden | `WhyJsonSchemaTest`, `WhyFormatsTest` | R11 |
| **Whatif** | Each change kind; recorded-model reuse only when inputs unchanged; changed inputs stop at `needs a model`; scripted answers; live path under cap with a fake model; trajectory/effect diffs including unsafe flips; `--save` opens in `why`; original untouched | `WhatIfTest`, `RecordedModelsReuseTest`, `NeedsModelStopsTest`, `WhatIfLiveCapTest`, `EffectDeltaTest`, `OriginalUntouchedTest` | R25 to R33 |
| **Overlay** | Counts equal the real runs; aggregation over several runs; never-reached list; Mermaid styling; JSON schema | `OverlayCountsTest`, `OverlayAggregateTest`, `OverlayMermaidTest`, `OverlayJsonSchemaTest` | R34 to R37 |
| **Stepper** | Next/into/out/continue, breakpoints (line, node, agent, conditional), `set`, `answer`, scripted model, `vars` with provenance and masking, `where`, cost; effects simulated; `--effects real` requires confirmation; session record/replay | `StepperTest`, `BreakpointTest`, `StepperEffectsTest`, `VarsViewTest`, `SessionReplayTest` | R14 to R22 |
| **Step back** | `back` re-derives state exactly (equals the state recorded going forward); overlay edits re-applied; real effects not repeated | `StepBackTest`, `StepBackEditsTest`, `StepBackRealEffectTest` | R23, R24 |
| **Seams unchanged** | `weave run` identical with `StepGate` and decision events present | `RunUnchangedTest`, `RunUnchangedJournalTest` | R15, R44 |
| **DAP** | initialize/launch/setBreakpoints/threads/stack/scopes/variables/next/stepIn/stepOut/continue/stepBack/evaluate/setVariable against a scripted client; parallel branches as threads | `DapProtocolTest`, `DapStepBackTest`, `DapParallelThreadsTest` | R38 to R42 |
| **Extension** | Debugger contribution, launch-config provider, CodeLens (fake VS Code) | `debugConfig.test.ts` | R41 |
| **Safety** | No write to run dir; no effect instrumented; secrets never in output | `OriginalUntouchedTest`, `NoEffectsDuringWhyTest`, `NoSecretsInOutputTest` | R12, R17, R25 |

## 3. Generated workflows

A small generator builds random valid Loom workflows from a grammar of the statement kinds (bounded depth and size), with scripted models answering from a seeded stream. For each: run → journal → observe-replay → compare (R1). 500 seeds on every build, 10,000 nightly. A failing seed is printed and saved as a fixture.

## 4. Fault injection

Truncated journal (last entry missing); journal entry of an unexpected kind; script edited after the run; run directory read-only; journal in a database (`JdbcRunJournal`) and in files (`FileRunJournal`) — both backends; concurrent `weave resume` while `why` runs (a lock is respected); a `--at` that names a step that never ran; a `--set` of a variable that does not exist; a candidate script that does not check; model change requested with `recorded` models; `Ctrl+C` during a live whatif (partial report, cap honoured).

## 5. What is deliberately not tested

- Fidelity of *real model* behaviour under a changed prompt (that is evaluation, not debugging).
- Terminal UX details beyond the text commands' outputs.
- Performance of the Java debugging of tools and models (out of scope).
