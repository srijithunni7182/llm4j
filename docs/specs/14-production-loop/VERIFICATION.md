# Phase 14 verification plan: the production loop

Status: **14a to 14d done** (capture; regress; gate); 14e (Fabric) not started. Run top to bottom at the end of sub-phase 14e; the relevant rows after each sub-phase. Results in §7.

## 1. Acceptance criteria

| # | Criterion | Evidence |
|---|---|---|
| A1 | One command turns a real run (or a Fabric incident's run) into a test that reproduces what production did, with no model | `CaptureCaseValidTest`, `CapturePassesTest` |
| A2 | No personal data, secret or credential from a run reaches a file, report, log or response unmasked | `MaskingNoRawOnDiskTest`, `SecretsNeverCapturedTest`, `RegressHtmlSafetyTest`, `FabricCaptureRouteTest` |
| A3 | A captured case never silently changes behaviour: masking that would change the route fails the capture, naming the field | `MaskingKeepsRouteTest` |
| A4 | A captured case is `observed` until a person approves it; only approved cases fail a gate | `ApprovalStatesTest`, `GateStagesTest` |
| A5 | I can try a candidate script, prompt or model against a sample of real history and see what changes, unsafe changes first, with no effects and no change to the store | `RunComparatorTest`, `UnsafeFlipTest`, `RegressReadOnlyTest` |
| A6 | The report is honest: it states the population, sample and seed, separates recorded from live comparisons, counts what needs a model, and never extrapolates a non-random sample | `ReportWordingTest`, `NeedsModelCountedTest`, `RecordedVsLiveSeparatedTest` |
| A7 | Approved cases are always checked, whatever the sample | `ApprovedAlwaysIncludedTest` |
| A8 | Spend is bounded: live comparisons and judged comparisons need a cap and stop cleanly at it | `RegressLiveCapTest`, `JudgeCostGateTest` |
| A9 | CI can enforce it: one command, stages, skipped stages visible, git refs, PR-ready markdown, templates | `GateStagesTest`, `GateSkipVisibleTest`, `GateGitRefsTest`, `GateWorkflowTemplateTest` |
| A10 | From a Fabric incident I can create a test and try a change, within existing permissions and audit; when the journal is elsewhere I am told the command to run | `FabricCapturePermissionTest`, `FabricCaptureAuditTest`, `fabric-capture.page.test.js` |
| A11 | Capture requires explicit, recorded consent and respects policy | `ConsentTest`, `CapturePolicyTest` |
| A12 | 200 recorded-model runs regress in under 30 s | `RegressSpeedTest` |
| A13 | Existing test, eval, replay, Fabric behaviour is unchanged | existing tests, unmodified |
| A14 | Documented | docs review (§5) |

## 2. Traceability: requirement → tests

| Req | Tests | Status |
|---|---|---|
| R1 | `CaptureTest.aRunBecomesAValidCaseThatPassesAgainstTheScriptThatRanIt`, `CapturePassesTest` (150 generated workflows: capture, then run the case: it passes) | ✅ 14a |
| R2 | `CaptureTest.whatTheCollaboratorsSaidIsWhatTheCaseScriptsAndNothingIsInvented` (answers, classifier labels and confidence, people; failures as `fail:`; tasks; calls made inside an agent are not journaled, so tools are not scripted) | ✅ 14a |
| R3 | `LoopStateTest.aCaseIsObservedUntilAPersonApprovesItAndTheRecordSaysWhoAndWhen`, `CaptureCommandTest.approvingAndRetiring...` | ✅ 14a |
| R4 | `CaptureTest.expectNoneLeavesOnlyHowTheRunEnded` | ✅ 14a |
| R5 | `CaptureCommandTest.asGoldenAddsAMaskedEntryToTheEvaluationDatasetOnceAndItLoads` | ✅ 14d |
| R6 | `MaskerTest`, `CaptureTest.personalDataAndSecretsNeverReachTheFileAndTheCaseStillPasses`, `CaptureCommandTest.noSeededValueReachesAFileTheOutputOrTheErrorStream` | ✅ 14a |
| R7 | `CaptureTest.maskingThatWouldChangeTheRouteFailsNamingTheField` | ✅ 14a |
| R8 | `MaskerTest.secretsAreReplacedWhateverTheShape...` (ten shapes), the hostile run in `CaptureCommandTest` | ✅ 14a |
| R9 | `CaptureInjectionTagTest` | 🟦 14e (the flag comes from Fabric; a journal does not carry it) |
| R10 | `CaptureTest.capturingTheSameRunAgainChangesNothingAndAHandEditedFileIsNeverOverwritten` | ✅ 14a |
| R11 | `CaptureTest.aRunThatWaitedForAPersonIsCapturedUpToWhereItStopped` | ✅ 14a |
| R12 | `LoopStateTest.consentIsAskedOnceAndRecordedWhereTheRepositoryKeepsIt`, `...aPolicyRestrictsWorkflowsFieldsAndAge`, `CaptureCommandTest.firstUseNeedsTheAgreementAndItIsRecordedOnce` | ✅ 14a |
| R13 | `LoopStateTest.failedDivertedUnsure...`, `CaptureCommandTest.aSelectionCapturesEachRunOfTheClassAsItsOwnCase` (`--list`) | ✅ 14a (slow and costly cannot be picked: the journal keeps neither; said, and D11 asks) |
| R14 | `CaptureCommandTest.aSelectionCapturesEachRunOfTheClassAsItsOwnCase` (`--select`, `--limit`) | ✅ 14a |
| R15 | `LoopStateTest.aCaseThatNoLongerPassesCannotBeApprovedUnlessItsExpectationIsAmendedInTheSameStep` | ✅ 14a |
| R16 | `TestCommandTest` (the count), `GateCommandTest.onlyApprovedCapturedCasesCanFailTheGate` | ✅ 14d |
| R17 | `TestCommandTest.requireApprovedFailsWhileACapturedCaseIsStillObserved` | ✅ 14a |
| R18 | `RegressTest.takingThePersonOutOfThePathIsAnUnsafeFlip...`, `...aCandidateThatAddsAnEffectToThePathIsAnUnsafeFlipNamingTheTool` (a ladder rung climb is not detected: ladders are not replayed by observe-replay) | 🟨 14b |
| R19 | `RegressTest.aChangedPromptIsCountedAsNeedingAModelAndNeverGuessed`, `RegressLiveTest.liveModelsAreAskedOnlyWhereTheQuestionChanged...` (recorded and live comparisons are counted apart) | ✅ 14c |
| R20 | `SamplerTest` (5), `RegressTest.theReportSaysWhatTheSampleIsAndNeverMoreThanItSupports` | ✅ 14b |
| R21 | `RegressLiveTest.approvedCapturedCasesAreAlwaysRunAndAFlipOnOneIsReported...` (a sample of 1, the approved case still runs and its break is listed as `[approved]` and fails every `--fail-on` but `none`) | ✅ 14c |
| R22 | `RegressTest.theStoreIsOnlyReadAndNoTextIsInTheReport...` (fingerprint before and after), `RegressCommandTest`; there is no `--effects` flag; effects are described by observe-replay and never run | ✅ 14b |
| R23 | `RegressTest.theStoreIsOnlyReadAndNoTextIsInTheReportUnlessAskedAndThenMasked` | ✅ 14b |
| R24 | `RegressCommandTest.whatSpendsMoneyNeedsACapAndSaysSoBeforeAnythingRuns`, `RegressLiveTest.aJudgeSpendsFromTheSameCapAndNeverRunsWithoutOne` (rubric and classifier judges; a verdict that is neither SAME nor DIFFERENT is not taken) | ✅ 14c |
| R25 | `RegressLiveTest.costIsLabelledEstimatedWhenItComesFromTheScriptsAndMeasuredWhenItWasSpent` | ✅ 14c (the estimate is the review's cost model on the two scripts, not on each changed call) |
| R26 | `RegressTest.theRecordedVersionIsFoundInGitHistory...`, `...aRunOfAnotherVersionOrOfAnotherWorkflowIsLeftOutAndCounted` | ✅ 14b |
| R27 | `RegressLiveTest.twoHundredRecordedRunsAreComparedInWellUnderThirtySeconds` | ✅ 14c |
| R28 | `RegressCommandTest` (JSON version 1 with per-run outcomes) | ✅ 14b |
| R29 | `RegressTest.theHtmlIsOneFileWithNothingRemoteAndEverythingEscaped` | ✅ 14b |
| R30 | `RegressCommandTest` (0, 1, 2, and 4 when the cap stops it, with the partial report) | ✅ 14c |
| R31 | `GateCommandTest.eachStageSaysPassFailOrSkipped...`, `...aFailingStageIsNamedWithTheOneCommandThatReproducesIt` | ✅ 14d |
| R32 | `GateCommandTest.eachStageSaysPassFailOrSkippedAndASkipIsVisibleNotSilent` (`--require`) | ✅ 14d |
| R33 | `GateCommandTest.pullRequestRefsMakeTheBaseTheBaseline...` | ✅ 14d |
| R34 | `GateCommandTest.formatsAreForPeople...`, `...theWorkflowTemplatesParseAndNameRealCommandsAndOptions` | ✅ 14d (the templates are parsed and their options checked; running them is a manual check) |
| R35 | `GateCommandTest.pullRequestRefs...` (the store fingerprint and the working copy are unchanged) | ✅ 14d |
| R36 | `FabricCaptureRouteTest` | ⬜ 14e |
| R37 | `FabricCaptureRemoteFallbackTest` | ⬜ 14e |
| R38 | `FabricCaptureNoRepoWriteTest` | ⬜ 14e |
| R39 | `FabricCapturePermissionTest`, `FabricCaptureAuditTest` | ⬜ 14e |
| R40 | `fabric-capture.page.test.js` (incident ↔ test link) | ⬜ 14e |
| R41 | `LoopStateTest.theStoreIsReadWholeAndLeftExactlyAsItWas`, `...aStoreBeingWrittenWhileItIsReadStillGivesWholeRuns` | ✅ 14a |
| R42 | `LoopStateTest.theLoopPackageIsUsedByNothingBelowIt` | ✅ 14a |

## 3. Quality gates

- All existing tests pass unmodified.
- Line coverage ≥ 90 % in `loop/`; mutation score ≥ 75 % on `Masker`, `RunComparator`, `UnsafeFlip`, `Sampler`.
- The hostile-journal test greps every output surface for every seeded value and must find none; it runs on every build.
- `scripts/verify-loop.sh` (new): capture round-trip property over the corpus, regress against the synthetic store, gate on a sample PR.
- No new runtime dependency.

## 4. Manual checks

1. Capture an incident from the Fabric simulator end to end; open the case, read it for anything private; approve it; break the script; watch the gate fail with an understandable message.
2. Regress a prompt change over a real (non-sensitive) store; read the report with someone unfamiliar and ask what they conclude; check they do not over-read the percentages.
3. Review the consent text with someone responsible for data protection; record their feedback.
4. Run the GitHub Actions template in a scratch repository.
5. Capture a suspended run, fix the script, and confirm the case now passes.

## 5. Documentation owed

Wiki "From an incident to a regression test" and "Checking a change against real history"; privacy and retention page (what is captured, masking, consent, what to do before committing); LOOM_GUIDE sections; the loom-deployer skill's incident chapter gains the capture step; the workflow skill's best-practices stage; Fabric docs; `llms.txt`; the guide chapter 9/10 cross-links.

## 6. Exit checklist

- [ ] Every row in §2 ✅ and every A-criterion has evidence
- [ ] Hostile-journal test green; privacy page reviewed
- [ ] Docs in §5 updated; roadmap status updated
- [ ] DESIGN §11 questions answered and recorded

## 7. Results

*(per sub-phase: capture round-trip pass rate over the corpus, masking test inventory, regress timings, sample/strata tables, gate demo)*

### 14a (2026-10-24)

- Built (`loop/`): `Masker` (personal data of the stack's detector plus a phone pattern with spaces, secrets of many shapes via `SecretShapes.scrub`, literal terms; same-shape stand-ins, stable within a case), `StoreReader` and `RunRecord` (read-only, whole-file reads, a store fingerprint), `Capture` (replay without a model, scripted collaborators from the journal, the case built and masked in memory and run through `wtest.CaseProbe` before anything is written; refuses when the script changed since the run, when masking changes the route (naming the field), or when the case does not reproduce the run), `Consent`, `Policy`, `Approval`, `Candidates`; `weave capture` (`--list`, `--select`, `--dry-run`, `--approve`, `--retire`, `--amend`, `--mask-term`, `--mask-keep`, `--expect`, `--note`, `--tag`, `--name`, `--to`, `--yes`); `weave test` counts the cases that wait for a person and `--require-approved` fails while any does. Test files may carry a `captured:` block.
- Found building it: the hostile seed caught three real gaps in the first masker: a Google key one character longer than the review rule's pattern, a password containing `&`, and a phone number with a space after the area code (its area code survived). `ObserveReplay.Result` now says which statement each step id is (`statements`), which is how a journal entry becomes the answer of one agent.
- Tests: Java module +37 (`MaskerTest` 6, `CaptureTest` 8, `LoopStateTest` 10, `CapturePassesTest` 1 over 150 generated workflows, `CaptureCommandTest` 7, `TestCommandTest` +2).
- Not in 14a: injection tagging (R9, with Fabric in 14e), picking slow or costly runs (the journal does not keep time or cost: D11 asks), parallel or broadcast steps (a case cannot be taken from such a run yet: refused, said), calls an agent made to its tools (not journaled; the scripted agent does not call them), `--as golden` (14d).

### 14b (2026-10-24)

- Built (`loop/`): `Sampler` (random, recent, stratified, failures-first; deterministic per seed; at least one run from every stratum, the rest by size; the sample records the population, the strategy, the seed and each stratum's size), `Baselines` (a named file, a git revision, or by default the version each run recorded, recovered from git history by fingerprint), `Regress` (each sampled run replayed from its journal with the candidate through `WhatIf`; the recorded answers of people are given in order; a run that cannot be compared is counted and never guessed; each run gets a time limit and the replay stops at the next statement when cancelled), `RegressReport` (text, JSON version 1, Markdown, JUnit, a single HTML file), `weave regress` (`--baseline`, `--baseline-git`, `--store`, `--since`, `--limit`, `--sample`, `--seed`, `--prompt`, `--model`, `--judge`, `--fail-on`, `--format`, `--report`, `--show-text`).
- Found building it: comparing steps by their id is wrong when the candidate's statements shift (a removed statement renumbers the ones after it, so phase 13's trajectory diff, written for one script, said a step was the same when it was a different statement); regress compares the route by what each step does, in each script's own words, and not by the wording of a condition (a reworded condition that sends every run the same way is not a changed route). A candidate that asks a different agent than the run did is a changed route, not "needs a model". The first version also lost the people's recorded answers and reported every run with a person as suspended.
- Tests: Java module +25 (`SamplerTest` 5, `RegressTest` 11, `RegressCommandTest` 4 and the earlier 14a ones).
- Not in 14b: live models and judges (14c), cost estimates and `--fail-on cost-up` (14c), approved cases always included (14c), the speed check (14c).

### 14c (2026-10-24)

- Built: `--models live` (one `Spent` counter for the whole command, runs compared one at a time, none after the cap; the model's measured cost is summed per call), the report keeps *compared with recorded answers* and *compared with live models* apart and says what was spent, exit 4 with a partial report at the cap, `Judges` (a rubric judge and a classifier judge, both counted against the cap), approved captured cases always run against the candidate (copies pointed at it), `Costing` (the review's estimate of a typical run of each workflow, labelled an estimate) and `--fail-on cost-up:PERCENT`. `ObserveReplay.Result` carries the measured cost of a live replay.
- Tests: Java module +7 (`RegressLiveTest` 6, `RegressCommandTest` +1).
- 200 recorded runs: the whole test, including building the 200-run store, takes about 2 s on this machine (target 30 s for the comparison).
- Not in 14c: the estimate is per workflow from the scripts, not per changed call (R25 asks for the recorded token ratio of each changed call; that needs the old and new question of each call, which the replay does not hand out).

### 14d (2026-10-24)

- Built: `weave gate` (stages check, review, test, regress, eval from `gate.properties`; each passes, fails or is skipped with a reason and the command that reproduces it; `--require`; `--base-ref` / `--candidate-ref`; text, Markdown, JUnit and SARIF; `--print-workflow github|jenkins`), `GateConfig` (an unknown key is an error), `Workflows`, `weave capture --as golden`, `weave test --ignore-observed`. `weave test` now finds the cases `weave capture` writes in `tests/captured/` (they were not found by default).
- Found building it: a gate that stopped at the first sloppy fixture (a warning under `--strict`) showed why the check stage uses `--strict` only because the team chose the gate; the test fixtures in this file are written clean for that reason. The regress stage must read the base revision from the repository the script lives in, not from the copy extracted for the candidate (which has no history).
- Tests: Java module +8 (`GateCommandTest` 7, `CaptureCommandTest` +1).
- Decision for you (DESIGN §11 question 1): CI needs history. Built: a directory (`regress.store`). Not built: `weave history export --masked` (a portable masked archive of recent runs). It is listed as D14.

