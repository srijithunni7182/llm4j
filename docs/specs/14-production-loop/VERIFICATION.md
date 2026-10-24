# Phase 14 verification plan: the production loop

Status: **14a done** (capture, masking, consent, policy, approval); 14b to 14e not started. Run top to bottom at the end of sub-phase 14e; the relevant rows after each sub-phase. Results in §7.

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
| R5 | `CaptureAsGoldenTest` | ⬜ 14d |
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
| R16 | `TestCommandTest.aCapturedCaseRunsLikeAnyOtherAndTheSummary...` (the count); only approved cases fail a gate: `GateCountsApprovedOnlyTest` | 🟨 14a (count) / 14d (gate) |
| R17 | `TestCommandTest.requireApprovedFailsWhileACapturedCaseIsStillObserved` | ✅ 14a |
| R18 | `UnsafeFlipTest` | ⬜ 14b |
| R19 | `NeedsModelCountedTest`, `RecordedVsLiveSeparatedTest` | ⬜ 14b/14c |
| R20 | `SamplerTest`, `StratifiedAllocationTest`, `ReportWordingTest` | ⬜ 14b |
| R21 | `ApprovedAlwaysIncludedTest` | ⬜ 14c |
| R22 | `RegressReadOnlyTest`, `RegressNoEffectsTest` | ⬜ 14b |
| R23 | `RegressNoTextByDefaultTest` | ⬜ 14b |
| R24 | `JudgeCostGateTest` | ⬜ 14c |
| R25 | `CostLabelsTest` | ⬜ 14c |
| R26 | `BaselineFromHashTest`, `BaselineGitTest`, `BaselineMissingTest` | ⬜ 14b |
| R27 | `RegressSpeedTest` | ⬜ 14c |
| R28 | `RegressJsonSchemaTest` | ⬜ 14b |
| R29 | `RegressHtmlSafetyTest` | ⬜ 14b |
| R30 | `FailOnTest`, `RegressExitCodesTest` | ⬜ 14b |
| R31 | `GateStagesTest` | ⬜ 14d |
| R32 | `GateSkipVisibleTest` | ⬜ 14d |
| R33 | `GateGitRefsTest` | ⬜ 14d |
| R34 | `GateFormatsTest`, `GateWorkflowTemplateTest` | ⬜ 14d |
| R35 | `GateReadOnlyTest` | ⬜ 14d |
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

