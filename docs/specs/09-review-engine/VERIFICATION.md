# Phase 9 verification plan: the review engine

Status: **not started**. Run top to bottom at the end of sub-phase 9f; the relevant rows after each sub-phase. Results in §7.

## 1. Acceptance criteria

| # | Criterion | Evidence |
|---|---|---|
| A1 | One command reviews a script across security, validity, cost, reliability and prompts and prints findings most-severe first with a summary card | `ReviewCommandTest`, `ReportGoldenTest` |
| A2 | Everything `weave check` and `weave audit` report appears in `weave review`, and both commands behave exactly as before | `ReviewSupersetOfAuditTest`, `AuditUnchangedTest`, `CheckUnchangedTest` |
| A3 | Every workflow shows a typical and a worst-case cost with its assumptions; an unbounded loop is reported as unbounded, never as a number | `CostModelTest`, `CostUnboundedTest` |
| A4 | The estimate is honest: on the corpus's recorded runs, typical is within 2x of actual for ≥ 80 % of workflows and worst is never below actual | `CorpusCostAccuracyTest` |
| A5 | Every finding says what to do; machine-applicable fixes exist for the mechanical ones, and `--fix` applies them atomically and idempotently | `FixApplierTest`, `FixAtomicityTest`, `FixIdempotentTest` |
| A6 | A fixed script passes `weave check` and has no new HIGH finding | `FixApplierTest` (verification case) |
| A7 | Each default-on rule has precision ≥ 0.9 on the corpus; heuristic rules are off by default and labelled | `ReviewCorpusTest` |
| A8 | A project can tune and suppress rules; a suppression without a reason is itself a finding, and suppressed findings are listed | `SuppressionTest`, `ReviewCommandTest` |
| A9 | CI can consume it: JSON, SARIF, exit codes, `--fail-on`, `--fix --dry-run` | `SarifSchemaTest`, `ReviewCommandTest` |
| A10 | Every rule has a generated explanation page | `ExplainEveryRuleTest` |
| A11 | A warm review of a 300-line script takes under 1 s | `ReviewPerformanceTest` |
| A12 | Review never runs a model, a tool or a task, never loads user classes, never touches the network | `ReviewIsStaticTest` |
| A13 | Documented | docs review (§5) |

## 2. Traceability: requirement → tests

| Req | Tests | Status |
|---|---|---|
| R1 | `FindingModelTest`, one test per rule | ⬜ 9a |
| R2 | `FindingModelTest`, `NodeIdLinkTest` (ids equal `weave graph` ids) | ⬜ 9a |
| R3 | `AdapterTest`, `LvClassificationTest`, `AuditUnchangedTest`, `CheckUnchangedTest` | ⬜ 9a/9b |
| R4 | `FixApplierTest` | ⬜ 9e |
| R5 | `FindingModelTest` (JSON round trip, version), `ReportGoldenTest` | ⬜ 9a |
| R6 | `ReviewCommandTest` (exit codes) | ⬜ 9a |
| R7 | `ReviewSupersetOfAuditTest` | ⬜ 9a |
| R8 | `SarifSchemaTest` | ⬜ 9b |
| R9 | `ReportGoldenTest` | ⬜ 9c |
| R10 | `CostModelTest`, `EstimatorMatchesRuntimeTest` | ⬜ 9c |
| R11 | `CostBudgetCapTest`, `Lc04Test` | ⬜ 9c |
| R12 | `CostUnboundedTest`, `Lr03LoopMaxTest` | ⬜ 9c |
| R13 | `CostUnpricedTest` | ⬜ 9c |
| R14 | `ReportGoldenTest` (footer) | ⬜ 9c |
| R15 | `ReviewIsStaticTest`, `CostDeterministicTest` | ⬜ 9c |
| R16 | `CalibrationTest` | ⬜ 9c |
| R17 | `CorpusCostAccuracyTest` | ⬜ 9c |
| R18 | `ReviewCorpusTest` | ⬜ 9d |
| R19 | `ExplainEveryRuleTest` | ⬜ 9a/9d |
| R20 | `RulePurityTest` (rules run twice with different clocks/env give the same findings) | ⬜ 9a |
| R21 | `RulePluginTest`, `ThrowingRuleTest` | ⬜ 9b |
| R22 | `DedupTest` | ⬜ 9b |
| R23 | `RankingTest` | ⬜ 9a |
| R24 | `FixAtomicityTest` | ⬜ 9e |
| R25 | `FixApplierTest` (verification) | ⬜ 9e |
| R26 | `FixOverlapTest` | ⬜ 9e |
| R27 | `FixIdempotentTest` | ⬜ 9e |
| R28 | `FixDryRunTest` | ⬜ 9e |
| R29 | `FixPreservesFormattingTest` | ⬜ 9e |
| R30 | `ReviewCorpusTest`, `CorpusCostAccuracyTest` | ⬜ 9a/9c |
| R31 | `SuppressionTest` | ⬜ 9b |
| R32 | `ReviewRulesFileTest` | ⬜ 9a |

## 3. Quality gates

- All existing tests pass unmodified (the existing check/audit/graph/eval suites).
- New code: line coverage ≥ 90 % in `review/`, mutation score target ≥ 70 % on `cost/` and `fix/` (PIT is already used in the repo).
- `scripts/` gate `verify-review.sh` (new) runs the corpus, the precision/recall table and the calibration table and fails below the thresholds in A4 and A7.
- No new runtime dependency (pom diff).

## 4. Manual checks

1. Run `weave review` on three real scripts of the repo's examples; read every finding as a developer would. Each is understandable without opening the guide.
2. Apply `--fix` on a flawed script, read the diff, run the workflow in `--mock` mode: behaviour matches what the finding titles said.
3. Compare the typical cost on one real workflow against its Fabric run page after a real, capped run (live check, owed).
4. Open the explain page of five rules and confirm the example fires and the near-miss does not.

## 5. Documentation owed

Wiki page "Reviewing a workflow"; `LOOM_GUIDE.md` section on `weave review` and `review.properties`; `llms.txt`; the `llm4j-workflow-guide` skill's validate stage points at `weave review`; rule pages generated into `docs/review/rules/`; stack-level pages once at the end of the program.

## 6. Exit checklist

- [ ] Every row in §2 is ✅
- [ ] Every A-criterion has passing evidence
- [ ] The corpus table (§7) is filled in and meets the thresholds
- [ ] Docs in §5 updated; roadmap status table updated
- [ ] Open questions in DESIGN §13 answered and recorded

## 7. Results

*(filled in at the end of each sub-phase: corpus precision/recall per rule, estimator error table, review time, fix verification counts)*
