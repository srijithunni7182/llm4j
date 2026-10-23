# Phase 13 verification plan: time-travel debugging

Status: **13a and 13b done**. Run top to bottom at the end of sub-phase 13f; the relevant rows after each sub-phase. Results in §7.

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
| R1 | `ObserveReplayEqualsRunTest` (corpus of 12 scripts, every statement kind that has a result) | ✅ 13a |
| R2 | `DivergenceTest` (a hole, an unreached entry, a different kind of step, nothing invented) | ✅ 13a |
| R3 | `WhyCommandTest` (a changed script is refused, `--script` explains against the old one, a run with no fingerprint is told so) | ✅ 13a |
| R4 | `VerifyNondeterministicTaskTest` | ⬜ later in 13 (replay uses the journaled task result; `--verify` is not built) |
| R5 | `DecisionEventTest`, `WhyTest`, `ObserveReplayEqualsRunTest.decisionsAreFoundForEveryKindOfChoice` (alt, loop, for each, classify, rewind, decide, failure taken over, guard; per-attempt retry detail is not in the journal, see §7) | 🟨 13a |
| R6 | `DecisionEventTest` (provenance, masking, truncation with the length kept), `WhyTest` | ✅ 13a |
| R7 | `WhyTest`, `WhyCommandTest` | ✅ 13a |
| R8 | `WhyNotRanTest` | ✅ 13a |
| R9 | `WhyStatesTest` (suspended, failed: the failure is now noted in the journal, §7) | ✅ 13a |
| R10 | `WhyStatesTest.aRewindIsSeenAsOneAttemptAfterAnotherWithTheValuesThatCausedIt`, `WhyTest` | ✅ 13a (an operator's rewind is followed as it stands, not re-derived) |
| R11 | `WhyCommandTest.jsonIsVersionedAndCarriesTheSameChoices`, `markdownIsATableOfTheChoices` | ✅ 13a |
| R12 | `WhyCommandTest` (same bytes twice in every format; nothing written to the run directory; no model or person reachable) | ✅ 13a |
| R13 | `WhyStatesTest.aRunOfFiveThousandStepsIsExplainedInUnderTwoSeconds` (2,503 steps in about 50 ms) | ✅ 13a |
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
| R25 | `WhatIfTest`, `WhatIfCommandTest` (the run directory's hash is the same before and after; nothing in it is written) | ✅ 13b |
| R26 | `WhatIfTest` (a recorded answer is reused only when agent, model, prompts and task text are the same; a change that flows into a question stops the candidate with `needs a model` and why; `scripted` reuses nothing) | ✅ 13b |
| R27 | `WhatIfTest` (first difference with the values that changed, steps only in one trajectory, result, effects with a new one marked unsafe) | ✅ 13b (cost deltas come with the estimator in 13c) |
| R28 | `WhatIfCandidateScriptTest` | ⬜ 13c |
| R29 | `WhatIfPromptVersionTest` | ⬜ 13c |
| R30 | `WhatIfLiveCapTest` | ⬜ 13c |
| R31 | `WhatIfCommandTest` (effects are never performed: `--effects real` is refused with the reason) | ✅ 13b |
| R32 | `WhatIfCommandTest.theCandidateCanBeSavedAndExplainedWithWhy`, `aSavedCandidateWithASetVariableExplainsItselfWithThatValue` | ✅ 13b |
| R33 | `WhatIfCommandTest.aChangedScriptIsRefused`, `DivergenceTest` (a diverged candidate says so) | 🟨 13b (the `--script old.loom` offer arrives with candidate scripts in 13c) |
| R34 | `OverlayCountsTest` | ⬜ 13d |
| R35 | `OverlayAggregateTest` | ⬜ 13d |
| R36 | `OverlayMermaidTest` | ⬜ 13d |
| R37 | `OverlayJsonSchemaTest` | ⬜ 13d |
| R38 | `DapProtocolTest` | ⬜ 13f |
| R39 | `DapCapabilitiesTest` | ⬜ 13f |
| R40 | `DapLiveConfirmTest` | ⬜ 13f |
| R41 | `debugConfig.test.ts` | ⬜ 13f |
| R42 | `DapCostInConsoleTest` | ⬜ 13f |
| R43 | `ObserveReplayEqualsRunTest` (the one component) | ✅ 13a |
| R44 | `DecisionEventTest.aRunWithNoListenerIsExactlyWhatItWas` (same journal, same trace), the whole Java suite | ✅ 13a |

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

*(13a opens with the journal audit; then per sub-phase.)*

### 13a (2026-10-23)

**Journal audit (DESIGN §11.1): can every statement be re-derived from the journal?**

| Statement | In the journal | Needed to replay |
|---|---|---|
| `delegate`, `broadcast`, `handoff` | the reply under the step id (`delegate`, `broadcast`, `handoff`); `failed` with the message when `on_failure` took over | yes, present |
| `intentclassify` | `classify`: the whole answer record (labels, confidences, backend) | yes, present |
| `run` (task) | `task`: the result; `failed`; for a task that changes things, `#effect:` keys | yes, present |
| `human_prompt`, approvals | `human` and a `#asked` marker | yes, present |
| `sleep`, `halt` | `sleep` (the wake time), `halt` (the reason) | yes, present |
| `decide` | `#decide-proposal`, `#level`, `#decide-result` | yes, present |
| `checkpoint`, `rewind` | `#checkpoint`; the boundaries entry `#boundaries` (who rewound, why, what was carried) | present |
| `alt`, `loop`, `for each`, `set`, `note`, `return`, `call`, `parallel`, `guardrail` | nothing: pure, derived by replay | nothing needed |
| **A step that fails the run** | **nothing** (it is run again on resume, by design) | **gap** |
| Each retry's attempt, error and backoff | nothing (only the final outcome) | not recoverable |

The one gap that matters is the first: without a record, a failed run's journal looked the same as a run that had only been stopped there, so `why` could not say "failed at STEP because X". Following the spec ("add records only for gaps"), a step that fails the run now leaves one compact entry, `<step>#failure` (kind, name, category, attempts, error), under a marker no step reads, so resuming is unchanged. This is the only change to what a run writes; it appears only in the journals of runs that failed. Per-attempt retry detail is not recoverable from a journal and is not shown: `why` shows the final outcome. An operator's `weave rewind` is followed as it stands (it cannot be re-derived from the script); a script's own rewind is re-derived with its condition.

- Built: `DecisionPoint` and the `setDecisionListener` seam in `HarnessExecutor` (nothing is computed without a listener); provenance (which statement last set each variable); `ObserveReplay` (the interpreter over a copy of the journal, with models, classifiers, tools and tasks that stop the replay, people who suspend it, and time that does not pass); `Why` and `WhyRenderer` (text, Markdown, JSON v1, control characters shown as escapes); `weave why`; the script fingerprint in `run.json`.
- Found building it: the lexer's columns and a for-each item's step id (`Main/s1/e0/b0`) needed small fixes in the resolver; a parallel block's branches do not announce themselves, so they are read from the journal.
- Tests: Java module 2,945 tests; 13a added `ObserveReplayEqualsRunTest` (5), `WhyTest` (10), `WhyNotRanTest` (7), `DivergenceTest` (5), `WhyStatesTest` (4), `WhyCommandTest` (11), `DecisionEventTest` (5).
- Not in 13a: `--verify` (R4), the random-workflow property test (TEST_STRATEGY §3), `weave why` from the editor and Fabric (their phases), docs (written once 13f is done).

### 13b (2026-10-23)

- Built: `weave whatif` (variable, classifier or agent answer, person's answer, injected failure, tool result; recorded or scripted models; `--until`; `--save`; text, Markdown, JSON v1). The seam it needed: an `AskHook` that is told, for each delegate, classifier and task, what the step asks (agent or classifier, task text, the model and prompts it is asked with) and may supply the answer for a step that has none recorded; with no hook nothing is computed. The original run is replayed once to learn what each step asked, the journal is copied and cut back to the step, and the script runs forward over the copy; a variable set by a what-if is kept in the candidate's journal (`STEP#set`) so a saved candidate explains itself with `weave why`.
- Found building it: the journal does not record what a model was asked, so "unchanged question" is decided by replaying the original and comparing what each step would ask now with what it asked then; a for-each item's body steps resolve to the graph through `e<i>` ids; failures can be injected with the scripted-test categories (`rate_limit`, `timeout`, `server_error`, `refused`, `quota`, `invalid_output`, `tool_error`).
- Tests: Java module 2,965; `WhatIfTest` (11), `WhatIfCommandTest` (9).
- Not in 13b: a candidate script, prompt or model, and live models under a spend cap, with the cost estimate (13c); cost deltas; `--effects real` (refused: nothing is ever performed).
