# Phase 19 test strategy: evaluation that works

How each requirement of [DESIGN.md](DESIGN.md) is tested, under the rules of [TESTING_STRATEGY.md](../../TESTING_STRATEGY.md): hermetic by default; a real model or real key is `live`, opt-in and capped.

## 1. The principle: evaluate the evaluator

An evaluation tool is a measuring instrument. The tests of this phase therefore check **the instrument**, with the three tools a metrologist uses: **known inputs** (a dataset whose right answers we wrote and a fake model whose behaviour we scripted), **injected faults** (an empty output, a judge that disagrees with itself, a dataset that changed), and **a check that the instrument says what it cannot see** (its resolution, its unmatched rate, its unvalidated judge).
A report test never asserts "the quality is 87%". It asserts that a scripted model that is right 90 of 100 times produces a report whose pass@1 is 0.90, whose interval contains 0.90, and whose resolution statement matches the arithmetic.

## 2. Test levels

| Level | What it covers |
|---|---|
| **Unit** | The statistics (pass@1, pass^N, consistency@N, Wilson intervals, minimum detectable change, quadratic-weighted kappa, the unstable-case rule), the verdict matcher, the version and baseline rules, the splits guard, the composition checks |
| **Property** | pass^N ≤ pass@1 always; the kappa of identical ratings is 1 and of independent ratings is near 0; adding accepted equivalents never lowers the unmatched count; the confidence interval narrows as runs grow |
| **Dataset** | Parsing and `--check` of the new fields (mixed old and new datasets), including the legacy rubric strings |
| **Command** | `weave eval` and its subcommands through `WeaveCLI.execute(...)` with a **scripted model** (the existing `MockModels`/scripted clients), a **scripted judge** and a temporary project folder |
| **Bundle** | The written bundle is read back by the real eval4j-report loader and by the dashboard's data layer (`dashboard.js` tests under `node --test` with a bundle fixture) |
| **Golden report** | `summary.md` and `evaluations.csv` of a fixed run compared with a stored golden file; the "Read this first" strip is part of it |
| **Docs** | The recipe samples (By-agent, the dataset fields) are executed by tests, as `ContextDocsExamplesTest` does for other pages |
| **Live** (opt-in) | One real capped run on a small dataset: bundle written, outputs present, judge cost separate; one judge validation on a small real reference set |

## 3. Fixtures

- **`ScriptedWorld`**: a model that answers case *i* correctly with a configured probability from a seeded generator, so repeated runs vary reproducibly; and a scripted judge with a configured agreement rate and bias, for R7 and R12.
- **`ReferenceSetFixture`**: 40 outputs with scores from three scripted "humans" with set agreement levels (high, moderate, poor), for R12's three branches (rubric under-specified; judge unusable; judge usable).
- **`DatasetFixture`**: a dataset with all three splits, the three case classes, accepted equivalents, an unmatched case, a case that is unstable by construction, a slice of 4 cases, and versions 1 and 2 for R8.
- **`SecurityCorpusFixture`**: a tiny corpus (a payload per class and placement) and a **deliberately weak workflow** (a fake tool that takes whatever arguments the model proposes), so the runner finds a control failure and the test proves it is reported, then a fixed workflow where controls hold.

## 4. Per requirement

| Requirement | Tests (written first) |
|---|---|
| **R1, R2** | `EvalWritesTheBundleTest`: outputs, per-line reasoning, dimensions, agent tag, agent and judge cost, fingerprint and dataset version are in the bundle and are read back by the loader; `HtmlReportIsRenderedFromTheBundleTest`; `EmptyOutputIsAnErrorCaseTest` (with Phase 17) |
| **R3** | `RubricLineDimensionTest`: a line with `dimension:` counts once under it; a plain line keeps the old counting; `--check` says so once per case; mixed lists parse |
| **R4** | `ByAgentIsOnWhenCasesAreAgentTaggedTest`, `EvalInitWritesReportConfigTest`, the recipe's docs test |
| **R5** | `EmitTestCompilesAndRunsHermeticTest`: the emitted class compiles against eval4j and, with the switch off, runs zero real tests; with a scripted client it produces a bundle |
| **R6** | `JudgeCostIsSeparateAndInTheTotalTest`, `MaxCostBoundsTheTotalTest` |
| **R7** | `JudgeSamplesMedianAndSpreadTest`; `UnstableLineIsNotCountedForAGateTest` |
| **R8** | `DatasetVersionRecordedTest`, `CrossVersionComparisonRefusedTest` (and allowed with a recorded reason), `BaselineNeedsApproverAndReasonTest`, `CompareAgainstCurrentAndPreviousBaselineTest` |
| **R9** | `SplitsSelectTest`, `HeldOutOnlyRunsAtAReleaseGateTest` (guard test: removing the guard fails it), `HeldOutTextNotPrintedInDevelopmentReportTest`, `GeneralisationGapTest` (the 10-point message), `NoHeldOutWarningTest` |
| **R10** | `VerdictSetTest` (exact, acceptable, rejected, unmatched), `StructuredCompareIsPreferredTest`, `UnmatchedRoutedToReviewFileTest`, `MergeAcceptedEquivalentBumpsCaseVersionTest`, `UnmatchedAbove10PercentWarnsTest` |
| **R11** | `Pass1AndPassHatNTest` (property tests above), `ConsistencyAtNTest`, `UnstableCasesTest`, `ResolutionStatementTest` (the arithmetic of runs and detectable change), `WilsonIntervalTest` |
| **R12** | `JudgeValidationBranchesTest` (poor inter-human agreement stops; poor judge agreement flags; good passes), `KappaTest` (known values), `RequireValidatedJudgeGateTest`, `JudgeAuditSamplerTest`, `BiasFooterListsWhatWasDoneTest` |
| **R13** | `CostPerSuccessTest`, `P95AndRunawayRateTest`, `GracefulTerminationRateTest`, `QualityAndCostOnOnePageTest` |
| **R14** | `ProtocolsParseAndRunTest`, `DriftProtocolPinsConfigurationTest`, `GateTakesAProtocolTest`; the sample workflow file is linted |
| **R15** | `CaseClassShareWarnsBelowTenPercentTest`, `SliceBelowTenCasesWarnsTest`, `CompositionBySourceReportedTest` |
| **R16** | `MetamorphicMechanicalRelationsTest` (order, irrelevant addition, removal are deterministic and need no model), `MetamorphicParaphraseMarksSyntheticTest`, `RelationalAssertionTest` (a system that changes its answer when evidence is re-ordered is caught) |
| **R17** | `SecurityRunnerFindsAControlFailureTest`, `SecurityRunnerReportsControlsHeldTest`, `SecurityRunnerRefusesRealToolsTest`, `NinetyNinePercentWordingTest` (the report's honesty line is present) |
| **R18** | `RoutingConfusionMatrixTest`, `HandoffContextProbeTest`, `LoopInductionBoundsTest`, `EvictionProbeTest`, `TurnVersusSessionMetricsTest` (only for what the inventory finds missing) |
| **"Read this first"** | `ReadThisFirstStripTest` (golden file) |

## 5. Fault injection

An empty output; a judge that returns JSON with no score; a judge that always says 5; a judge that disagrees with itself across samples; a reference set with three identical raters; a dataset edited between two runs; a held-out file requested in a development run; a corpus payload that is empty; an interrupted run (the bundle must be readable and say it is partial); a budget hit mid-run (the report says how many runs completed of how many planned).

## 6. Mutation testing

The decision logic joins the Loom `mutation` profile and eval4j's: the splits guard (R9), the version-comparison refusal (R8), the verdict matcher (R10), the unmatched-rate warning, the kappa thresholds (R12), the gate's validated-judge requirement, the controls-held check (R17). Each guard is also removed once by hand and the failing test is named in `VERIFICATION.md` §7.

## 7. Deliberately not tested

- That a real judge agrees with real humans (that is what R12 measures in use; a test can only prove the arithmetic and the gating).
- That the starter security corpus is sufficient (it is a start; the report says so).
- Statistical claims beyond the standard formulas (the tests check the formulas against published values, not against a model).
- The dashboard's visual layout (checked by the extension's and the report's existing browser tests where they exist, and by the manual checks).
