# Phase 13 verification plan: time-travel debugging

Status: **not started**. Run top to bottom at the end of sub-phase 13f; the relevant rows after each sub-phase. Results in §7.

## 1. Acceptance criteria

| # | Criterion | Evidence |
|---|---|---|
| A1 | For any finished, paused or failed run I can ask why it went where it did and get the condition, the values with where they came from, and the branch not taken, with no model, no keys and no network | `WhyTest`, `WhyOfflineTest`, `NoEffectsDuringWhyTest` |
| A2 | The explanation is exact: re-deriving a run from its journal reproduces the run's steps and decisions | `ObserveReplayEqualsRunTest`, `ObserveReplayRandomWorkflowsTest` |
| A3 | I can ask "what if the classifier had said X / the variable were Y / the tool had failed" for a past run, free when only logic changes, and be told when a model would be needed instead of being given a guess | `WhatIfTest`, `RecordedModelsReuseTest`, `NeedsModelStopsTest` |
| A4 | I can compare a changed script, prompt or model against one past run with a capped spend and see the trajectory, result, cost and effect differences, unsafe ones marked | `WhatIfCandidateScriptTest`, `WhatIfLiveCapTest`, `EffectDeltaTest` |
| A5 | I can step through a workflow, set breakpoints, read and edit variables, and step backwards, with effects simulated by default | `StepperTest`, `BreakpointTest`, `StepBackTest`, `StepperEffectsTest` |
| A6 | In VS Code, F5 on a `.loom` debugs it with breakpoints, variables and step controls | `DapProtocolTest`, `debugConfig.test.ts`, manual §4 |
| A7 | I can see the path a run took on the graph, and which nodes a set of runs never reached | `OverlayCountsTest`, `OverlayAggregateTest` |
| A8 | None of this changes or writes to the original run, and none of it has an effect | `OriginalUntouchedTest`, `NoEffectsDuringWhyTest` |
| A9 | Existing run, resume, rewind, fork, timeline, replay behaviour and journals are byte-identical | `RunUnchangedTest`, `RunUnchangedJournalTest` |
| A10 | Output is stable and machine-readable (JSON v1) for Fabric and the editor | `WhyJsonSchemaTest`, `OverlayJsonSchemaTest` |
| A11 | `why` on a 5,000-step run takes under 2 s | `WhyPerformanceTest` |
| A12 | Documented | docs review (§5) |

## 2. Traceability: requirement → tests

| Req | Tests | Status |
|---|---|---|
| R1 | `ObserveReplayEqualsRunTest`, `ObserveReplayRandomWorkflowsTest` | ⬜ 13a |
| R2 | `DivergenceTest` | ⬜ 13a |
| R3 | `ScriptDriftWhyTest` | ⬜ 13a |
| R4 | `VerifyNondeterministicTaskTest` | ⬜ 13a |
| R5 | `DecisionEventTest` | ⬜ 13a |
| R6 | `DecisionProvenanceTest`, `DecisionMaskedTest` | ⬜ 13a |
| R7 | `WhyTest` | ⬜ 13a |
| R8 | `WhyNotRanTest` | ⬜ 13a |
| R9 | `WhySuspendedTest`, `WhyFailedTest` | ⬜ 13a |
| R10 | `WhyRewindTest` | ⬜ 13a |
| R11 | `WhyJsonSchemaTest`, `WhyFormatsTest` | ⬜ 13a |
| R12 | `WhyDeterministicTest`, `WhyOfflineTest` | ⬜ 13a |
| R13 | `WhyPerformanceTest` | ⬜ 13a |
| R14 | `StepperEffectsTest` | ⬜ 13e |
| R15 | `RunUnchangedTest` | ⬜ 13e |
| R16 | `StepperJournalResumeTest` | ⬜ 13e |
| R17 | `VarsViewTest`, `NoSecretsInOutputTest` | ⬜ 13e |
| R18 | `BreakpointTest` | ⬜ 13e |
| R19 | `StepperWritesNothingTest` | ⬜ 13e |
| R20 | `SessionReplayTest` | ⬜ 13e |
| R21 | `VarsViewTest` | ⬜ 13e |
| R22 | `WhereViewTest` | ⬜ 13e |
| R23 | `StepBackTest`, `StepBackRealEffectTest` | ⬜ 13e |
| R24 | `StepBackEditsTest` | ⬜ 13e |
| R25 | `WhatIfTest`, `OriginalUntouchedTest` | ⬜ 13b |
| R26 | `RecordedModelsReuseTest`, `NeedsModelStopsTest` | ⬜ 13b |
| R27 | `TrajectoryDiffTest`, `EffectDeltaTest` | ⬜ 13b |
| R28 | `WhatIfCandidateScriptTest` | ⬜ 13c |
| R29 | `WhatIfPromptVersionTest` | ⬜ 13c |
| R30 | `WhatIfLiveCapTest` | ⬜ 13c |
| R31 | `WhatIfEffectsPolicyTest` | ⬜ 13b |
| R32 | `WhatIfSaveTest` | ⬜ 13b |
| R33 | `WhatIfDivergedScriptTest` | ⬜ 13b |
| R34 | `OverlayCountsTest` | ⬜ 13d |
| R35 | `OverlayAggregateTest` | ⬜ 13d |
| R36 | `OverlayMermaidTest` | ⬜ 13d |
| R37 | `OverlayJsonSchemaTest` | ⬜ 13d |
| R38 | `DapProtocolTest` | ⬜ 13f |
| R39 | `DapCapabilitiesTest` | ⬜ 13f |
| R40 | `DapLiveConfirmTest` | ⬜ 13f |
| R41 | `debugConfig.test.ts` | ⬜ 13f |
| R42 | `DapCostInConsoleTest` | ⬜ 13f |
| R43 | `ObserveReplayEqualsRunTest` (single component) | ⬜ 13a |
| R44 | `RunUnchangedTest`, `DecisionOffByDefaultTest` | ⬜ 13a |

## 3. Quality gates

- All existing tests pass unmodified.
- Line coverage ≥ 90 % in `travel/`, `debug/`, `whatif/`; mutation score ≥ 70 % on `ObserveReplay`, `Provenance`, `TrajectoryDiff`.
- The random-workflow property test runs 500 seeds in the build and 10,000 nightly with zero failures.
- `scripts/verify-travel.sh` (new): corpus equality, original-untouched hashes, no-effects instrumentation.
- No new runtime dependency (DAP implemented over JSON-RPC already available, or a small hand-written framing; decided in 13f).

## 4. Manual checks

1. Run `ticket-triage` with a scripted classifier, then `weave why` and read it as someone who has not seen the script.
2. `weave whatif --at <classifier step> --answer Intent=…` and check the trajectory diff is what you would predict.
3. Debug in VS Code with a breakpoint in a loop; step back across an `alt`.
4. Break the script after a run; confirm `why` refuses with the drift message and works with `--script`.
5. Open the overlay of ten runs in the graph panel; confirm the never-reached node list.

## 5. Documentation owed

Wiki "Debugging a workflow"; LOOM_GUIDE sections for `why`, `step`, `whatif`, the overlay; the guide chapter 15 (steer, stop, supervise) cross-links; the loom-deployer skill mentions `why` for incidents; `docs/serve/PROTOCOL.md` gains the reserved `loom/debug.*` methods; `llms.txt`.

## 6. Exit checklist

- [ ] Every row in §2 ✅ and every A-criterion has evidence
- [ ] Journal audit result (DESIGN §11.1) recorded in §7, with any added records justified
- [ ] Docs in §5 updated; roadmap status updated
- [ ] DESIGN §11 questions answered and recorded

## 7. Results

*(13a opens with the journal audit: statement kind × journaled? × needed for observe-replay?; then per sub-phase: property-test seeds run, timings, overlay accuracy)*
