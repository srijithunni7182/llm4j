# Phase 19 verification plan: evaluation that works

Status: **not started** (specs in draft). Run §0 first (it decides which requirements of Part B are still needed), then top to bottom at the end of each sub-phase (proposed: **19a** inventory and the bundle, R1, R2, R4, R6; **19b** rubric dimensions, judge samples, verdicts, R3, R7, R10; **19c** versions, splits, consistency and resolution, R8, R9, R11; **19d** judge validation, protocols, efficiency, R12 to R14; **19e** classes, metamorphic, security corpus, probes, R15 to R18). Results in §7.
Findings: F20 to F26 and F55 to F65 of the [register](../FIELD_FINDINGS.md). Strategy: [TEST_STRATEGY.md](TEST_STRATEGY.md).

## 0. Inventory and reproduction

### 0.1 Reproduce the field findings (Part A)

| # | Finding | Procedure | If the finding is real, you see | Result |
|---|---|---|---|---|
| P1 | F06, F25 | A workflow ending in `note` with no `return`; `weave eval` with a counting scripted judge ([Phase 17 P9](../17-honest-results/VERIFICATION.md)). Then open the report. | an empty output reaches the judge; the report has no outputs, no per-dimension scores, no per-agent view | ⬜ |
| P2 | F20 | Search the eval4j user guide and the Loom guide for `breakdowns` and `declareAgent`. | not found | ⬜ |
| P3 | F21 | Count the registrations and classes a project needs to produce the By-agent dashboard from `weave eval` (none: it cannot) and from eval4j (list them). | no single command path | ⬜ |
| P4 | F22 | A scenario with two dimensions and three rubric lines; run with a scripted judge; read the per-dimension counts. | each line filed under both dimensions | ⬜ |
| P5 | F23 | A scripted judge that varies; run the same case twice. | a different score each time and one sample per line, with no spread shown | ⬜ |
| P6 | F24 | Read the dashboard's cost for a run with a judge. | the judge's cost is absent | ⬜ |

### 0.2 Inventory the capabilities of Part B

For each row, find out whether the stack already has it (search the code **and** run the command), record the answer with evidence (a file and a line, or a command and its output), and mark the requirement **met, narrowed or needed**. The first grep in the specs session over-counted ("baseline" and "repetitions" matched in many files for other reasons), so this is done by hand.

| # | Capability | Where to look | Present? (evidence) | Requirement |
|---|---|---|---|---|
| I1 | Repeated runs per case; pass@1 and pass^N | `weave eval --help`, eval4j `EvalRun` | ⬜ | R11 |
| I2 | Confidence intervals; minimum detectable change | eval4j `Comparison`, `PromptComparison` | ⬜ (a significance test exists in `Comparison`) | R11 |
| I3 | Unstable-case detection | eval4j-report analysers | ⬜ | R11 |
| I4 | Dataset `version` recorded and enforced | dataset loader, `EvalRun` | ⬜ | R8 |
| I5 | Baseline with approver and rationale; previous-baseline comparison | `REPORTING-AND-BASELINES.md`, `FileSystemScoreHistory` | ⬜ | R8 |
| I6 | Splits; held-out guard; generalisation gap | dataset schema | ⬜ | R9 |
| I7 | Accepted equivalents, rejected answers, unmatched rate | dataset schema, matchers | ⬜ | R10 |
| I8 | Judge validation (kappa), audit sampling, bias notes | `LlmJudge*`, `LLM-AS-JUDGE.md` | ⬜ | R12 |
| I9 | Cost per success, p95, runaway and graceful-termination rates | `SpendGuard`, report analysers | ⬜ | R13 |
| I10 | Named run protocols; a scheduled drift check | `weave gate`, docs | ⬜ | R14 |
| I11 | Case classes and slice-size warnings | dataset `--check` | ⬜ | R15 |
| I12 | Metamorphic case derivation | `DatasetSynthesizer` | ⬜ | R16 |
| I13 | An injection-payload corpus runner and the controls-held check | `weave audit`, `weave test` fault injection | ⬜ | R17 |
| I14 | Routing confusion matrix; handoff context probe; loop induction; eviction probe; turn vs session metrics | `weave test`, `CONVERSATIONS.md`, context specs 01 and 03 | ⬜ | R18 |

The result of §0.2 is written into §7 and into the register's evidence column for F55 to F65 (from **I** to **R** or **met**), and the DESIGN's Part B is edited in the same pull request to remove what is met and narrow what is partial.

## 1. Acceptance criteria

| # | Criterion | Evidence |
|---|---|---|
| A1 | One command, `weave eval`, produces the bundle the dashboard reads, with outputs, per-line reasoning, per-dimension and per-agent results, agent and judge cost, and the configuration fingerprint | `EvalWritesTheBundleTest`, `HtmlReportIsRenderedFromTheBundleTest`, P1, M1 |
| A2 | An empty output is never judged | `EmptyOutputIsAnErrorCaseTest`, `EvalNeverJudgesEmptyOutputTest` |
| A3 | A rubric line can belong to one dimension; plain lines keep their counting and `--check` says so | `RubricLineDimensionTest`, P4 |
| A4 | The By-agent view is on by default for agent-tagged cases and the recipe is in the guides and executed by a test | `ByAgentIsOnWhenCasesAreAgentTaggedTest`, the docs test, P2 |
| A5 | A project can generate the opt-in real-evaluation class | `EmitTestCompilesAndRunsHermeticTest`, P3 |
| A6 | The judge's cost is shown and bounded with the agents' | `JudgeCostIsSeparateAndInTheTotalTest`, `MaxCostBoundsTheTotalTest`, P6 |
| A7 | Judge samples give a median and a spread; an unstable line does not gate | `JudgeSamplesMedianAndSpreadTest`, `UnstableLineIsNotCountedForAGateTest`, P5 |
| A8 | Every run records the dataset version; cross-version comparison is refused without a recorded reason; a baseline has an approver and a reason; the previous baseline is shown too | `DatasetVersionRecordedTest`, `CrossVersionComparisonRefusedTest`, `BaselineNeedsApproverAndReasonTest`, `CompareAgainstCurrentAndPreviousBaselineTest` |
| A9 | Held-out cases run only at a release gate, are not printed in development reports, and the generalisation gap is reported | `HeldOutOnlyRunsAtAReleaseGateTest`, `HeldOutTextNotPrintedInDevelopmentReportTest`, `GeneralisationGapTest` |
| A10 | The outcome verdict set exists, unmatched outputs are routed to a human review file, and a high unmatched rate warns | `VerdictSetTest`, `UnmatchedRoutedToReviewFileTest`, `UnmatchedAbove10PercentWarnsTest` |
| A11 | A report states pass@1 and pass^N, intervals, unstable cases and its own resolution | `Pass1AndPassHatNTest`, `UnstableCasesTest`, `ResolutionStatementTest`, `WilsonIntervalTest` |
| A12 | A judge cannot gate a dimension until validated against humans; the validation computes inter-human agreement first | `JudgeValidationBranchesTest`, `KappaTest`, `RequireValidatedJudgeGateTest` |
| A13 | Cost per successful task, p95, runaway and graceful-termination rates are on the same page as quality | `CostPerSuccessTest`, `QualityAndCostOnOnePageTest` |
| A14 | Run protocols exist and the drift check runs at a pinned configuration on a schedule | `ProtocolsParseAndRunTest`, `DriftProtocolPinsConfigurationTest`, M3 |
| A15 | Datasets are checked for the three case classes and for slices that are too small | `CaseClassShareWarnsBelowTenPercentTest`, `SliceBelowTenCasesWarnsTest` |
| A16 | Metamorphic cases can be derived and assert relations between runs | `MetamorphicMechanicalRelationsTest`, `RelationalAssertionTest` |
| A17 | A security corpus runs against a workflow and the system-level controls are checked for every payload that fooled the model; real tools are refused | `SecurityRunnerFindsAControlFailureTest`, `SecurityRunnerReportsControlsHeldTest`, `SecurityRunnerRefusesRealToolsTest` |
| A18 | Routing, handoff, loop and eviction probes exist where `weave test` lacked them | `RoutingConfusionMatrixTest`, `HandoffContextProbeTest`, `LoopInductionBoundsTest`, `EvictionProbeTest` |
| A19 | A report opens with the "Read this first" strip | `ReadThisFirstStripTest`, M1 |
| A20 | Existing datasets, scripts and reports keep working; every change is in the release notes and `API_COMPATIBILITY.md` | existing tests unmodified, [DESIGN §6](DESIGN.md) |
| A21 | Each guard is noticed when removed | PIT score not lower; by-hand removals named in §7 |

Criteria for requirements that §0.2 marks **met** are replaced by "confirmed present, with the test that proves it", and criteria for requirements it marks **needed** keep their rows.

## 2. Traceability: requirement → tests

| Req | Tests | Status |
|---|---|---|
| R1, R2 | `EvalWritesTheBundleTest`, `HtmlReportIsRenderedFromTheBundleTest`, `EmptyOutputIsAnErrorCaseTest` | ⬜ |
| R3 | `RubricLineDimensionTest` | ⬜ |
| R4 | `ByAgentIsOnWhenCasesAreAgentTaggedTest`, `EvalInitWritesReportConfigTest`, docs test | ⬜ |
| R5 | `EmitTestCompilesAndRunsHermeticTest` | ⬜ |
| R6 | `JudgeCostIsSeparateAndInTheTotalTest`, `MaxCostBoundsTheTotalTest` | ⬜ |
| R7 | `JudgeSamplesMedianAndSpreadTest`, `UnstableLineIsNotCountedForAGateTest` | ⬜ |
| R8 | `DatasetVersionRecordedTest`, `CrossVersionComparisonRefusedTest`, `BaselineNeedsApproverAndReasonTest`, `CompareAgainstCurrentAndPreviousBaselineTest` | ⬜ |
| R9 | `SplitsSelectTest`, `HeldOutOnlyRunsAtAReleaseGateTest`, `HeldOutTextNotPrintedInDevelopmentReportTest`, `GeneralisationGapTest`, `NoHeldOutWarningTest` | ⬜ |
| R10 | `VerdictSetTest`, `StructuredCompareIsPreferredTest`, `UnmatchedRoutedToReviewFileTest`, `MergeAcceptedEquivalentBumpsCaseVersionTest`, `UnmatchedAbove10PercentWarnsTest` | ⬜ |
| R11 | `Pass1AndPassHatNTest`, `ConsistencyAtNTest`, `UnstableCasesTest`, `ResolutionStatementTest`, `WilsonIntervalTest` | ⬜ |
| R12 | `JudgeValidationBranchesTest`, `KappaTest`, `RequireValidatedJudgeGateTest`, `JudgeAuditSamplerTest`, `BiasFooterListsWhatWasDoneTest` | ⬜ |
| R13 | `CostPerSuccessTest`, `P95AndRunawayRateTest`, `GracefulTerminationRateTest`, `QualityAndCostOnOnePageTest` | ⬜ |
| R14 | `ProtocolsParseAndRunTest`, `DriftProtocolPinsConfigurationTest`, `GateTakesAProtocolTest` | ⬜ |
| R15 | `CaseClassShareWarnsBelowTenPercentTest`, `SliceBelowTenCasesWarnsTest`, `CompositionBySourceReportedTest` | ⬜ |
| R16 | `MetamorphicMechanicalRelationsTest`, `MetamorphicParaphraseMarksSyntheticTest`, `RelationalAssertionTest` | ⬜ |
| R17 | `SecurityRunnerFindsAControlFailureTest`, `SecurityRunnerReportsControlsHeldTest`, `SecurityRunnerRefusesRealToolsTest`, `NinetyNinePercentWordingTest` | ⬜ |
| R18 | `RoutingConfusionMatrixTest`, `HandoffContextProbeTest`, `LoopInductionBoundsTest`, `EvictionProbeTest`, `TurnVersusSessionMetricsTest` | ⬜ |
| Strip | `ReadThisFirstStripTest` | ⬜ |

## 3. Quality gates

- [ ] `mvn verify` green on `eval4j`, `eval4j-report`, `ai-agent4j-loom` and any module touched, coverage gates held, none lowered; the dashboard's `node --test` suite green.
- [ ] PIT: the Loom `mutation` profile and eval4j's, with R8 to R12 and R17 added; score not lower.
- [ ] The docs tests that execute samples are green; the recipe sample (R4) is executed.
- [ ] `make format-check` and `make release-check` green.
- [ ] No new dependency, or its justification is in the pull request. (Statistics are small and are written, not imported, unless a licence-compatible, small library is justified.)
- [ ] `SecurityDocTest` green; the corpus runner is described in `docs/security/`.
- [ ] A hermetic run of the full report path takes under 30 seconds in the default build.

## 4. Manual checks (need the owner; live ones need a key from the environment and a spend cap)

| # | Check | Pass |
|---|---|---|
| M1 | On the owner's `article-flow` (or the field replay fixture), `weave eval` with a real, capped judge: the dashboard shows outputs, reasoning, the By-agent table, the judge's cost and the "Read this first" strip, **in one command** | ⬜ |
| M2 | Validate a real judge: score 40 real outputs by hand (or with a colleague), run `weave eval judge validate`, read the result; it changes what the owner does about the judge | ⬜ |
| M3 | Schedule the drift protocol in CI for a week; read what it says on the second run | ⬜ |
| M4 | Run the security corpus on a workflow with a real tool; confirm that a payload that fooled the model is reported with the controls result, and that the wording is something the owner would put in a report | ⬜ |
| M5 | Give the report to someone who did not build it; ask what they would conclude and what they would not; compare with the strip's own statement | ⬜ |

## 5. Documentation owed

eval4j user guide and wiki, the Loom guide ("Evaluating a Workflow"), `docs/guide/` chapters 2, 3, 5, 8 and the evaluation chapter, the generated command pages, `docs/API_COMPATIBILITY.md` (bundle and dataset fields), `docs/security/`, the workflow and the new evaluation skill ([Phase 23](../23-eval-design-skill/DESIGN.md)), `llms.txt`, release notes. Stack-level pages once at the end of the program.

## 6. Exit checklist

- [ ] §0.1 reproduced and §0.2 inventoried, recorded with dates and build ids; the register's evidence column updated for F55 to F65
- [ ] DESIGN Part B edited to match the inventory (met removed, partial narrowed) in the same pull request
- [ ] Every row in §2 ✅ or partial with a reason; every A-criterion has evidence
- [ ] M1 to M5 done and recorded in §7
- [ ] DESIGN §8 questions answered; decisions D8, D12 and D13 recorded in the program page
- [ ] Docs in §5 updated; roadmap status updated

## 7. Results

*(empty until 19a)*
