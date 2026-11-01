# Phase 28 test strategy: evaluation first

How each requirement of [DESIGN.md](DESIGN.md) is tested: hermetic, deterministic, no model, no network. Tests are written first.

## 1. Principle: one lookup, one fingerprint, many readers

The phase adds several readers of the same facts: LT06, the gate, the notice and `--requirements` all ask "which cases exist for this node?", and `--changed` asks "what determines this node's answers?". Each fact is computed in one place, and the tests assert that **every reader agrees** with `weave eval` on the same fixture. Disagreement between two commands about whether a dataset exists is the bug this design rules out.

## 2. Reusable devices

| Device | Used for |
|---|---|
| **Dataset fixture trees**: temporary projects with `eval/golden/` in each shape (none; agent files in mixed case; `.yml`; workflow file only; extractor and evaluator files complete, partial and scaffold-only) | R1, R2, R4, R5 |
| **Reader-agreement test**: for each fixture tree, `weave eval --check`, LT06, the gate and the notice must agree on which nodes have cases | R1, R2, R4 |
| **Fingerprint fixtures**: pairs of scripts that differ in exactly one input to a node's fingerprint (prompt text, shared prompt, model, temperature, schema, instructions, a tool's reach, a policy version, a case) and pairs that differ in something that must not count (a comment, whitespace in the script, another node) | R6 |
| **Captured stdout** for `weave run`, `weave deploy` and the generated README | R2 |
| **Golden Markdown and HTML** for `--requirements` | R5 |
| **Fake clock and fake git identity** for approvals | R5 |

## 3. Per requirement

| Requirement | Tests (written first) |
|---|---|
| **R1** | `TestabilityLensOnByDefaultTest`, `LensesMinusTestabilityRestoresTest`, `Lt06UsesEvalLookupTest` (reader agreement), `Lt06IsMediumAndNotHeuristicTest`, `Lt06CoveredByWorkflowExpectTest` |
| **R2** | `RunPrintsNoEvaluationNoticeTest`, `QuietHidesNoticeTest`, `NoNoticeWhenAnyCaseExistsTest`, `DeployPrintsNoticeAndReadmeCarriesItTest`, `RunStartEventCarriesEvaluatedFlagTest`, `ObservePageShowsBannerTest` (loom-inspect page test), `NextListsDatasetTest`, `SkippedOnPurposeNoticeTest` |
| **R3** | `GuideStageTwoTitleTest`, skill text test (phase 20's checkable rules extended) |
| **R4** | `GateRejectsUntestedExtractorTest`, `GateRejectsEvaluatorRuleWithoutViolatedCaseTest`, `GateRejectsEvaluatorRuleWithoutCleanCaseTest`, `GateAcceptsNearMissAsCleanTest`, `GateChecksStructureTest` (unknown field or rule, duplicate id, malformed expectation), `ScaffoldDoesNotSatisfyGateTest`, `GateHasNoSkipFlagTest` (every build-time command applies it: CLI, `weave serve`, `weave deploy`), `HostLoadDoesNotReadGoldenAndWarnsOnceTest` (a packaged host with no `eval/golden/` loads the script), `FixConvertsAgentCasesToExtractorCasesTest` and `FixNamesMissingCasesTest` (with phases 24 and 25) |
| **R5** | `RequirementsMarkdownGoldenTest`, `RequirementsHtmlGoldenTest`, `RequirementsListsWhatHasNoCasesTest`, `RequirementsShowsIdentityTest`, `RequirementsApproveRecordsIdentityTest`, `RequirementsChangedSinceApprovalTest`, `RequirementsMasksPiiTest`, `RequirementsNeedsNoKeyTest` |
| **R6** | `FingerprintChangesForEachInputTest` (one row per fingerprint fixture pair), `FingerprintIgnoresIrrelevantChangesTest`, `ChangedRunsOnlyChangedNodesAndRoutesTest`, `ChangedWithNoReferenceRunsEverythingAndSaysSoTest`, `ChangedNeedsACapAndAsksTest`, `UnreadablePromptCountsAsChangedTest` (fail closed), `ReportWhatChangedSectionTest`, `OldRecordWithoutFingerprintsIsNoReferenceTest` |
| **R10 (D37)** | `EvaluatorFalseAlarmRateReportedTest`, `FalseAlarmAboveLimitFailsRunTest`, `FalseAlarmLimitPerEvaluatorTest`, `RequirementsShowsFalseAlarmLimitTest` |
| **R11 (D38)** | `ByAgentAndNodeSectionTest` |
| **R1 (D35)** | `Lt06SeverityFollowsLt01Test`, `Lt06GroupedPerScriptTest`, precision on the benchmark corpus for LT01 and LT06, `ReviewNoiseGateTest` |
| **R7** | `LoomGuideBlocksParseTest`, `TutorialExamplesTest`, `GuideTutorialPageTest`, `verify-skill-package.sh` |
| **R8** | `CiTemplateTest` (the file parses; the steps are present; the scheduled job is commented out; a missing key skips with a notice) |

## 4. Fault injection

A golden file that is not valid YAML (the gate reports the file and line, not "no cases"); a case that names a field the extractor does not have; an evaluator file for a policy whose version changed (cases still apply by rule id; a rule that no longer exists is reported); a baseline written by an older version without fingerprints; two nodes with the same name in different imported files.

## 5. Mutation testing

The gate's per-rule counting, the fingerprint's inputs and `--changed`'s selection join the Loom `mutation` profile (and eval4j's, where the code lives there). Guards removed once by hand and the failing tests recorded in `VERIFICATION.md` §7: the clean-case requirement (R4), the prompt part of the fingerprint (R6), and the fail-closed rule for an unreadable prompt (R6).

## 6. Deliberately not tested

- Whether a person's sign-off is meaningful: `--approve` records who and when; it is a review aid, as baseline approvals are.
- The CI system's own behaviour; only that the template is well formed and safe by default.
