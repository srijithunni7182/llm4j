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
| P7 | F20, F25 | Run a two-agent workflow through `weave eval` and read the trace and the eval4j scenario model: does each step record **which agent** produced it, with its tokens, cost and finish reason? Can the dashboard's By-agent table be filled from that without an `agent:` tag? (Decides how R4 is built.) | the agent is known only from tags | ⬜ |

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
| A4 | Every report has the By-agent view, with no tag or configuration, taken from the run's trace; the recipe is in the guides and executed by a test | `ByAgentViewIsAlwaysPresentTest`, `ByAgentColumnsTest`, the docs test, P2 |
| A4a | Every report has the nine sections of the report contract, in order, in the bundle, the HTML and the terminal; an empty section says why | `ReportContractStructureTest`, `EmptySectionStillSaysWhyTest`, M1 |
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
| R4 | `ByAgentViewIsAlwaysPresentTest`, `ByAgentColumnsTest`, `EvalInitWritesReportConfigTest`, docs test | ⬜ |
| R4.1 | `ReportContractStructureTest`, `EmptySectionStillSaysWhyTest` | ⬜ |
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

### 19a, Part A (R1, R2, R4, R4.1, R6), 2026-10-29

**Reproduced first.** F25: `weave eval --report` and `--json` held no outputs (`ScenarioResult` had no input or output field) and the dashboard bundle was never written; F24: no judge cost anywhere (the judge's client was not metered). P7 resolved: the run's trace **does** record which agent made each call (`RunTracing` per-agent), but not the cost (spend is only recorded under a budget), so tokens come from the trace and cost from the spend report when there is one, else `unknown`. F20: the dashboard's By-agent table already exists and is on by default; it needs `agent:` tags, which `weave eval` now writes itself.
Built: `AgentFacts` and the extended `ScenarioResult` (input, output, tags, dimensions, per-agent facts); `HarnessExecutor.usageByAgent`; `EvalSections` (the nine sections, one builder, rendered by the terminal, the page and the JSON); `JudgeMeter`; `EvalBundleWriter` (drives `eval4j`'s `EvalRun`: scenarios, evaluations with outputs and reasons, a case per agent for By-agent); the wiring in `weave eval`. Not built yet in Part A: R3 (a rubric line with its own dimension), R5 (`--emit-test`), R7 (judge samples).
Tests first: `ReportContractTest` (12), `EvalKeepsWhatWasJudgedTest` (6), `EvalWritesTheBundleTest` (7).
**Two existing tests had to change, and why.** `EvalCommandTest.agentAndWorkflowFiltersPick...` asserted the word `Helper` was absent after `--workflow Main`, a proxy for "the Helper cases did not run"; the report now names every agent that took part (Main delegates to Helper), so it asserts `Helper · h-1` is absent. `EvalCommandTest.anAnswerThatMustNeverContain...` asserted the answer appears nowhere; passing cases now show their outputs and a passing case had the same reply, so the assertion is on the failing case's block, which now shows `(withheld...)` instead of the output. The second one exposed a real hole: printing outputs would have repeated exactly what an `expected_output_not_contains` case guards against (a card number, a secret); outputs of such failing cases are now withheld everywhere (terminal, page, JSON, bundle).
Guard removed by hand once: dropping the `by-agent` section from `EvalSections.of` fails the contract tests (the terminal, page and JSON order tests and the by-agent tests). Verified: `mvn verify` on `ai-agent4j-loom`, 3,605 tests, 0 failures, 2 skips by name (`ImportClosureLoaderTest` unicode path on this file system; `LiveCostAccuracyTest` needs a live key). Not run: PIT; the dashboard rendering of the bundle (`eval4j-report` was not run on it, the files were checked directly); a real-provider evaluation.
Interpretation to confirm: R4.1 says the HTML, the JSON *bundle* and the terminal carry the same sections. The eval4j bundle has its own schema (scenarios, evaluations, traces); the nine sections are in `--report`, `--json` and the terminal, and the bundle carries the data the dashboard builds its own views from.

### 0.2 The inventory, done by hand (2026-10-29)

Method: read the class lists of `eval4j`, `eval4j-report` and `ai-agent4j-loom`; ran `weave eval --help`; searched with the pattern named in the row and **read the hits** (not counted them). Evidence is a file or a command.

| # | Capability | Present? (evidence) | Requirement now |
|---|---|---|---|
| I1 | Repeated runs per case; pass@1, pass^N | **No.** `weave eval --help` has no repetitions option; nothing in `eval4j`, `eval4j-report` or Loom computes pass^N. (A judge can sample: `LlmJudgeCondition.Builder.samples(int)`, and the bundle's `Evaluation.samples(List<Double>)` exists.) | R11 **needed** |
| I2 | Confidence intervals; smallest detectable change | **Partly.** A 95% Wilson interval exists twice: `eval4j/.../compare/PromptComparison.Interval` (used by the optimizer's `Comparison`) and `loom/autonomy/expr/Wilson.java`. `SPEC-deepeval-parity.md` §: "No statistical significance testing in v1". No "smallest change this run can detect" statement. | R11 **narrowed**: reuse Wilson, add the resolution sentence |
| I3 | Unstable-case detection | **No.** | R11 **needed** |
| I4 | Dataset `version` recorded and enforced | **No.** `EvalScenario` has `name, input, expected*, context, id, dimensions, tags, rubric, expect, inputs, min/max words`; no version, and `dataset.yaml` is not versioned. | R8 **needed** |
| I5 | Baseline with approver and reason; previous-baseline comparison | **Partly.** `eval4j/.../report/BaselineGate` + `EvalBaseline` keep a JSON baseline (`suite#test#metric` to score) and report regressions beyond a threshold (JUnit path). No approver, no reason, no previous baseline, nothing in `weave eval`. `eval4j-report` also has `analysis/Baselines`. | R8 **narrowed**: add approver and reason, the previous baseline, and `weave eval` use |
| I6 | Splits; held-out guard; generalisation gap | **No in datasets.** `eval/optimize/Split` and `DataSplit` split data for the prompt optimizer and warn about held-out leakage (`Split.java:127`), but a scenario has no split field. Scenarios do have free `tags`, so `split:heldout` can be carried as a tag without a schema change. | R9 **needed**; stored as a tag convention plus the guard (D12 b) |
| I7 | Accepted equivalents, rejected answers, unmatched rate | **No.** | R10 **needed** |
| I8 | Judge validation (kappa), audit sampling, bias notes | **Partly, only as test code.** `eval4j/src/test/.../CalibrationRunner.kappa` computes unweighted Cohen's kappa for RAG chunk labels in a verification study (`docs/verification-results/2026-09-29/calibration.md`: labels by the module author, not independent humans). `PairwiseJudge` cancels position bias. No command, no weighted kappa for rubric scores, no human reference-set format, no audit sampler. | R12 **narrowed**: reuse the formula, add the command and the formats |
| I9 | Cost per success, p95 cost, runaway and graceful-termination rates | **No.** `JudgeStats` has judge latency p95 only; `SpendGuard` exists for tests. | R13 **needed** |
| I10 | Named run protocols; a scheduled drift check | **No.** (`weave gate` text says "run it nightly".) | R14 **needed** |
| I11 | Case classes and slice-size warnings | **No.** (tags can carry `class:unanswerable`). | R15 **needed** |
| I12 | Metamorphic case derivation | **No.** `DatasetSynthesizer` has five question rewrites (`Evolution`: reasoning, multi-context, concretizing, constrained, comparative), which change difficulty, not meaning-preserving relations. | R16 **needed** |
| I13 | Injection-payload corpus runner and the controls-held check | **No.** `weave audit` checks a script statically; `weave test` injects faults, not payloads. | R17 **needed** |
| I14 | Routing confusion matrix; handoff probe; loop induction; eviction probe; turn vs session metrics | **Partly.** `weave test` scripts models and failures and asserts route and calls; the optimizer docs mention a confusion matrix of its own verdicts, not of routing. | R18 **needed**, scoped to what `weave test` lacks |

Also found (changes Part A): the dashboard's **By agent** table already exists and is on by default (`ReportConfig.defaults()` has `breakdownKeys = ["agent"]`, `Analyzer.breakdowns`), built from scenario **tags** `agent:<Name>`; a row per agent, a column per quality dimension. So F20 was mostly "not documented and needs tags", not "missing". What does not exist: any `weave eval` path to the bundle (it writes its own HTML page from `EvalReport`), the agent's cost, cut answers and fallbacks next to the dimensions, and the nine-section structure.

*(empty until 19a)*
