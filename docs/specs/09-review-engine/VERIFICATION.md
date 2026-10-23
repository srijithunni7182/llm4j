# Phase 9 verification plan: the review engine

Status: **in progress**: 9a to 9d complete; 9e–9i not started. Run top to bottom at the end of sub-phase 9i; the relevant rows after each sub-phase. Results in §7.

## 1. Acceptance criteria

| # | Criterion | Evidence |
|---|---|---|
| A1 | One command reviews a script across security, validity, cost, reliability and prompts and prints findings most-severe first with a summary card | `ReviewCommandTest`, `ReportGoldenTest` |
| A2 | Everything `weave check` and `weave audit` report appears in `weave review`, and both commands behave exactly as before | `ReviewSupersetOfAuditTest`, `AuditUnchangedTest`, `CheckUnchangedTest` |
| A3 | Every workflow shows a typical and a worst-case cost with its assumptions; an unbounded loop is reported as unbounded, never as a number | `CostModelTest`, `CostUnboundedTest` |
| A4 | The estimate is honest: on recorded runs, typical is within 2x of actual for ≥ 80 % of workflows and worst is never below actual | `CorpusCostAccuracyTest` ✅; `LiveCostAccuracyTest` ◐ (see §7) |
| A5 | Every finding says what to do; machine-applicable fixes exist for the mechanical ones, and `--fix` applies them atomically and idempotently | `FixApplierTest`, `FixAtomicityTest`, `FixIdempotentTest` |
| A6 | A fixed script passes `weave check` and has no new HIGH finding | `FixApplierTest` (verification case) |
| A7 | Each default-on rule has precision ≥ 0.9 on the corpus; heuristic rules are off by default and labelled | `ReviewCorpusTest` |
| A8 | A project can tune and suppress rules; a suppression without a reason is itself a finding, and suppressed findings are listed | `SuppressionTest`, `ReviewCommandTest` |
| A9 | CI can consume it: JSON, SARIF, exit codes, `--fail-on`, `--fix --dry-run` | `SarifSchemaTest`, `ReviewCommandTest` |
| A10 | Every rule has a generated explanation page | `ExplainEveryRuleTest` |
| A11 | A warm review of a 300-line script takes under 1 s | `ReviewPerformanceTest` |
| A12 | Review never runs a model, a tool or a task, never loads user classes, never touches the network | `ReviewIsStaticTest` |
| A13 | Nine lenses run by default and four optional lenses are off until enabled by `--lens` or `review.properties`; the report says which ran and which are off | `LensSelectionTest`, `OptionalLensesOffByDefaultTest` |
| A14 | A rule that needs a fact the project has not declared says it was not evaluated; it never reports a pass | `InertRuleNoteTest`, `DeclaredFactsTest` |
| A15 | The report estimates time (critical path, with human waits shown apart) and effects (counts per kind, unbounded when unbounded) alongside cost | `TimeEstimateTest`, `EffectsEstimateTest`, `EffectsUnboundedTest` |
| A16 | Testability findings use the project's own test files and a fresh coverage report, never run tests, and ignore a stale report with a note | `ReviewInputsTest`, `CoverageReportStaleTest`, `ReviewNeverRunsTestsTest` |
| A17 | Every rule of every lens, including the optional ones, has an explain page, a firing and a non-firing corpus script, and meets the precision gate or is marked heuristic | `ReviewCorpusTest`, `ExplainEveryRuleTest` |
| A18 | Every finding can be fixed, accepted (with a reason, a scope shown by count, optional expiry, kept in the repository), dismissed (local only) or baselined, and an ignore survives edits elsewhere without hiding a different finding | `FingerprintStabilityTest`, `AcceptScopesTest`, `AcceptDoesNotHideOtherFindingTest`, `BaselineTest` |
| A19 | Serious findings cannot be ignored casually: per-entry reasons, count confirmation, accepted highs stay visible, strict mode counts them, trifecta acceptance is written in the script | `AcceptGuardrailsTest`, `TrifectaAcceptWritesScriptTest` |
| A20 | Dismissing never changes what CI or a teammate sees; a baseline makes CI count only new findings | `DismissDoesNotAffectCiTest`, `BaselineNewOnlyFailsTest` |
| A21 | A team can see which rules are noisy in practice | `NoiseReportTest` |
| A22 | Open review findings never stop a run, resume, package, serve or deploy from the CLI, the extension or the skills; the developer is told in one stderr line and the output, exit code and journal are unchanged | `RunNeverBlockedByFindingsTest`, `RunUnchangedWithNoticeTest`, `NoticeStderrOnlyTest` |
| A23 | The notice is accurate and quiet: counts only open findings, once per process, configurable, silent when nothing is open, absent on non-starting commands | `NoticeCountsOpenOnlyTest`, `NoticeOncePerProcessTest`, `NoticeThresholdTest` |
| A24 | The notice and the review engine can never slow or fail a command beyond a stated cap | `NoticeCacheAndTimeCapTest`, `ReviewCrashDoesNotFailRunTest` |
| A25 | Only commands chosen for enforcement exit non-zero on findings | `OnlyExplicitCommandsFailTest` |
| A26 | The skills report findings and carry on, never withholding a run or deploy | `SkillAdvisoryTextTest` |
| A27 | Adding or changing a rule is a file change in a rule pack, with no Java change, release or extension rebuild; a rule cannot load without its examples | `PackExamplesRequiredTest`, `RulesNewScaffoldTest` |
| A28 | There is no new language: a rule is a document over a closed vocabulary and a published fact model, it always terminates, and what it cannot say needs a new fact or a Java rule | `RuleVocabularyTest`, `RuleTerminationTest`, `RuleSchemaRejectsExpressionsTest` |
| A29 | Packs are safe: inert, bounded, pinned and never downloaded; a project cannot silently redefine a core rule | `PackSandboxTest`, `PackNoNetworkTest`, `PackPinHashTest`, `PackPrefixTest` |
| A30 | A rule can be debugged and a pack upgrade previewed: `explain --at` shows each condition's value, and `rules diff` shows the findings an upgrade would change on my script | `RulesExplainAtTest`, `RulesDiffTest` |
| A31 | Rules moved from Java to data change nothing: identical findings on the corpus, and handled findings survive an upgrade | `RuleParityTest`, `PackUpgradeKeepsHandledTest` |
| A32 | Java rules and fact providers still work beside packs | `JavaRuleAlongsidePackTest`, `JavaFactProviderTest` |
| A33 | Documented, including a guide for writing a rule | docs review (§5) |

## 2. Traceability: requirement → tests

| Req | Tests | Status |
|---|---|---|
| R1 | `FindingModelTest.aFindingNeedsARuleIdAndAFixHint`, `CorePackTest.everyRuleAnswersWhyAndWhatToDo…` | ✅ 9a |
| R2 | `FindingModelTest.aLocationKnowsWhen…`, `DataRuleSourceTest.stepFindingsAreTiedToTheirGraphNode` | ✅ 9a |
| R3 | `AdapterTest`, `ValidityClassTest` (19 real messages classified), `ReviewSupersetOfAuditTest`; `weave check`/`audit` output unchanged | ◐ 9b: messages classified by table; the exhaustive message census stays open |
| R4 | `FixApplierTest` | ⬜ 9e |
| R5 | `ReportFormatsTest.theJsonIsVersionedAndCarriesEveryFieldTheEditorNeeds`, `hostileTextRoundTripsThroughJsonUnchanged` | ✅ 9a |
| R6 | `ReviewCommandTest` (exit codes, `--fail-on`, bad input = 2) | ✅ 9a |
| R7 | `ReviewSupersetOfAuditTest` | ✅ 9a |
| R8 | `SarifTest` (shape, levels, rules, fixes, suppressions), `ReviewCommandTest.sarifOutput…` | ✅ 9b |
| R9 | `ReviewCommandTest` (cost line and footer in the text card); the other card lines (security, next step) come with 9e | ◐ 9c cost part; card complete in 9e |
| R10 | `CostModelTest` (calls, loops, for each, retries, alt, tools, broadcast, call, rewind), `ReviewCommandTest` (JSON `cost` block, assumptions listed) | ✅ 9c (runtime-equivalence of the token count: `CostModelTest.tokensAreCounted…`) |
| R11 | `CostModelTest.aBudgetCapsTheWorstCase…`, `CostRulesTest` (LC04) | ✅ 9c script-level budgets; agent- and step-level budgets not yet |
| R12 | `CostModelTest.aLoopWithNoMax…`, `ReviewCommandTest.anUnboundedLoop…` | ✅ 9c |
| R13 | `CostModelTest.moneyComesFromThePriceTable…`, `ReviewCommandTest.theTextReport…Tokens…` | ✅ 9c |
| R14 | `ReviewCommandTest` (footer names the price table and the assumptions used) | ✅ 9c |
| R15 | `CostModelTest.theSameInputsAlwaysGiveTheSameNumbers`, `ReviewIsStaticTest` | ✅ 9c |
| R16 | `CalibrationTest` (5), `CalibrationEndToEndTest` (a real mocked-provider run), `ReviewCommandTest.calibrate…` | ✅ 9c (workflow and model level; the journal has no per-step usage that maps to a script line) |
| R17 | `CorpusCostAccuracyTest` (10 recorded runs, 10 of 10 within 2x), `LiveCostAccuracyTest` (7 of 10 and 4 of 10 live); worst never below actual in all | ◐ 9d: met on recorded runs, not met live (table in §7) |
| R18 | `ReviewCorpusTest` (per-rule precision and recall gate 0.9 on 44 labelled scripts) | ✅ 9d for the 18 data rules |
| R19 | `ReviewCommandTest` (`--explain` for every rule) | ✅ 9a |
| R20 | `JavaRuleTest.theEngineGivesTheSameFindingsWhateverTheClockOrEnvironment`, `ReviewIsStaticTest` (no clock, env, network or process in review sources) | ✅ 9b |
| R21 | `JavaRuleTest` (jar via ServiceLoader, throwing rule becomes a note, listed in the catalog) | ✅ 9b |
| R22 | `ReviewEngineTest` (overlap folding by line and node; no pair registered until a specific rule exists beside an audit rule) | ◐ 9b mechanism; first real pair lands with the rule (9d) |
| R23 | `ReviewEngineTest.findingsAreRankedBySeverityThenLensThenLineThenRule` | ✅ 9a |
| R24 | `FixAtomicityTest` | ⬜ 9e |
| R25 | `FixApplierTest` (verification) | ⬜ 9e |
| R26 | `FixOverlapTest` | ⬜ 9e |
| R27 | `FixIdempotentTest` | ⬜ 9e |
| R28 | `FixDryRunTest` | ⬜ 9e |
| R29 | `FixPreservesFormattingTest` | ⬜ 9e |
| R30 | `ReviewCorpusTest`, `CorpusCostAccuracyTest` | ◐ 9d: 44 labelled scripts and 10 recorded runs; review time is measured in 9i |
| R31 | `SuppressionTest` (name, line, node; reason required, LV08; listed in every format) | ✅ 9b |
| R32 | `ReviewSettingsTest.aMisspeltKeyIsAnErrorThatNamesTheNearestKey` | ✅ 9a |
| R33 | `LensSelectionTest`, `OptionalLensesOffByDefaultTest` | ⬜ 9f |
| R34 | `ReviewInputsTest`, `CoverageReportStaleTest`, `ReviewNeverRunsTestsTest` | ⬜ 9g |
| R35 | `TimeEstimateTest`, `CriticalPathTest` | ⬜ 9g |
| R36 | `EffectsEstimateTest`, `EffectsUnboundedTest` | ⬜ 9g |
| R37 | `HeuristicSwitchTest`, `ReviewCorpusTest` (precision per rule) | ⬜ 9f |
| R38 | `InertRuleNoteTest`, `DeclaredFactsTest` | ⬜ 9f |
| R39 | `OptionalLensesOffByDefaultTest`, `Lg*Test`, `Lm*Test`, `Lh*Test`, `Lb*Test` | ⬜ 9h |
| R40 | `CrossLensDedupTest` | ⬜ 9h |
| R41 | `FingerprintStabilityTest`, `FingerprintAnchorOrderTest`, `AcceptDoesNotHideOtherFindingTest` | ⬜ 9e |
| R42 | `AcceptScopesTest`, `AcceptExpiryTest` | ⬜ 9e |
| R43 | `AcceptScopesTest` (counts, no lens-wide accept) | ⬜ 9e |
| R44 | `AcceptGuardrailsTest`, `TrifectaAcceptWritesScriptTest` | ⬜ 9e |
| R45 | `DismissDoesNotAffectCiTest` | ⬜ 10c |
| R46 | `BaselineTest`, `BaselineNewOnlyFailsTest`, `BaselineHighExcludedTest` | ⬜ 9e |
| R47 | `NoiseReportTest` | ⬜ 9e |
| R48 | `FixGroupSafeOnlyTest`, `FixGroupReviewPreviewTest`, `FixRereviewTest` | ⬜ 9e |
| R49 | `RunNeverBlockedByFindingsTest`, `OnlyExplicitCommandsFailTest` | ⬜ 9e |
| R50 | `NoticeStderrOnlyTest`, `NoticeOncePerProcessTest`, `NoticeNotOnNonStartingCommandsTest` | ⬜ 9e |
| R51 | `NoticeCountsOpenOnlyTest`, `NoticeThresholdTest` | ⬜ 9e |
| R52 | `NoticeCacheAndTimeCapTest` | ⬜ 9e |
| R53 | `RunUnchangedWithNoticeTest`, `NoticeNonTerminalPlainTest` | ⬜ 9e |
| R54 | `ReviewCrashDoesNotFailRunTest` | ⬜ 9e |
| R55 | `FabricReviewAttributeTest` | ⬜ 9h |
| R56 | `SkillAdvisoryTextTest` | ⬜ 9i |
| R57 | `CorePackTest`, `ReviewCorpusTest`: 18 core rules are data; LA, LV and the cost-estimate rules stay Java by design | ✅ 9d |
| R58 | `RuleParserTest.thereIsNoWayToWriteAnExpressionOrACall` | ✅ 9a |
| R59 | `FactBuilderTest.everyFactInTheCatalogIsSet…`, `RuleValidatorTest`; 8 facts added in 9d | ✅ 9a, 9d |
| R60 | `RuleEvaluatorTest`, `DataRuleSourceTest`, `FactBuilderTest`; Java providers `PromptFacts`, `ConditionFacts` | ✅ 9a, 9d |
| R61 | `RuleParserTest`, `RuleEvaluatorTest.templatesFillFacts…` | ✅ 9a |
| R62 | `RuleEvaluatorTest` (vocabulary, relations, regex step limit), `RuleParserTest.conditionsAreBoundedInDepthAndSize` | ✅ 9a |
| R63 | `PackSetTest` (pins by major/minor/exact, hash, unlisted folder, missing folder, duplicates) | ✅ 9b |
| R64 | `ReviewIsStaticTest`, `PackSetTest.aPinnedPackThatIsNotThereIsAnErrorAndNothingIsDownloaded` | ✅ 9b |
| R65 | `PackLoaderTest`, `PackSetTest` (folder-bound reads; limits per pack) | ✅ 9b |
| R66 | `PackLoaderTest`, `PackSetTest` (prefixes, pack name vs folder, cross-pack duplicate ids) | ✅ 9b |
| R67 | `FixTemplateOpsTest`, `FixTemplateVerifiedTest`, `FixTemplateParityTest` | ⬜ 9e |
| R68 | `PackLoaderTest`, `PackTesterTest`, `CorePackTest.everyRuleCarriesAtLeastOneFiringAndOneQuietExample` (18 rules, 44 example scripts) | ✅ 9d |
| R69 | `RuleDocsGeneratedTest` | ⬜ 9i |
| R70 | `RulesToolTest` (list, facts, check, test, new, add with diff and re-pin, explain --at) | ✅ 9b |
| R71 | JSON/SARIF carry `source` (pack name@version); `RulesToolTest.addingANewVersion…`; handled-survives-upgrade needs fingerprints (9e) | ◐ 9b provenance done; PackUpgradeKeepsHandled in 9e |
| R72 | Rules were written as data from the start (no Java twin), so there is no parity to test; the corpus is the guard | ✅ 9d (not applicable) |
| R73 | `JavaRuleTest.aJavaRuleRunsBesideTheCoreRules…` | ✅ 9b (fact-provider half: `FactBuilderTest` custom provider) |
| R74 | `RulesDiffTest`, `PackSemverTest` | ⬜ 9i |

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
5. Enable all thirteen lenses on three real scripts and judge the noise: for each finding ask "would I act on this?" and record the rate per lens in §7 (a lens below 50 % moves to optional or loses its heuristic rules).
6. Compare the time estimate on one real workflow with a real traced run, and the effects estimate with the effects Fabric recorded (live check, owed).

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

### 9a (2026-10-22)

- Built: finding model, settings (`review.properties`), engine, adapters for audit (LA01–LA20) and validity (LV00), text/markdown/JSON reports, `weave review`, fact model v1 with six fact providers, data-rule parser, validator, three-valued evaluator, pack loader and tester, and a bundled core pack of six rules (LR01, LR03, LD02, LY02, LM01 optional, LG04 optional) with 17 example scripts.
- Tests: all review tests and the full loom module (2,400 tests) pass. The one failure on the first full run, `RepositoryScriptsTest`, was real: the bundled examples used a literal webhook URL; fixed to `env.HOOK_URL`.
- Review is a superset of audit on every bundled example (`ReviewSupersetOfAuditTest`); `weave check` and `weave audit` untouched apart from extracting `WeaveCLI.validate`.
- Not yet measured: coverage gate, corpus precision (needs 9d), performance (A11).


### 9b (2026-10-23)

- Built: project packs from `review-rules/<name>/` pinned in `packs =` by version and sha256 (a pack that is unlisted, missing, changed or duplicating another's rule is refused with every problem listed); `weave rules list | facts | check | test | new | add | explain --at`; `weave check` messages classified into LV01–LV07 and LV09 (LV00 only for what no class claims); suppressions (`RULE.suppress = place "reason"`, LV08 when the reason is missing, listed in every format); overlap folding in the engine; SARIF 2.1.0 (`--format sarif`); Java rules from a named jar (`--rules-jar`, ServiceLoader), beside pack rules.
- Tests added: `PackSetTest` (12), `RulesToolTest` (14), `ValidityClassTest` (3), `SuppressionTest` (10), `SarifTest` (7), `JavaRuleTest` (6), `ReviewIsStaticTest` (1), plus engine and command tests.
- Limits, stated plainly: the classifier is a pattern table over message text, verified on real messages from `weave check`, not on every message the validator can write, so some rare messages will still be `LV00`. No overlap pair is registered yet because the specific rule (LR07) comes with 9d. `rules diff` and pack semver policy are 9i. The notice and `--explain` for third-party packs only appear when the script is given.


### 9c (2026-10-23)

- Built: the cost estimator (`review/cost`): typical and worst case per workflow and per model step, from the script alone, with nine documented assumptions (`assume.*` in `review.properties` or `--assume`), prices from `--prices`, an unbounded loop reported as unbounded, a script budget capping the worst case, unpriced models named; token counts equal to the runtime guard's (pinned by a test); the report's `cost` block (text, Markdown, JSON); `--explain cost`; cost rules LC01 and LC02 as data in the core pack (now 1.1.0; examples can now carry `settings:`), LC04, LC05 and LC06 in Java because they need the estimate; `--calibrate RUN_DIR` comparing the estimate with the usage a real run journals.
- Tests added: `CostModelTest` (17), `CostRulesTest` (7), `CalibrationTest` (5), `CalibrationEndToEndTest` (1) plus command tests.
- Decisions and limits, stated plainly:
  - A typical `alt` is the average of its two ways (taken equally often); the worst case is the dearer way. The spec's wording ("most expensive branch weighted by share") was ambiguous; this is the reading that is documented in `--explain cost`.
  - The AST has no per-agent output cap (`max_tokens` belongs to memory), so every answer is `assume.output.tokens` (600).
  - Rewind is costed safely: the worst case repeats the whole workflow once more per allowed rewind, which can overstate it but never understate it.
  - Budgets at agent and step level are not applied to the cap yet; only the script-level budget is.
  - LC03, LC07, LC08, LC09 and LC10 are not built: LC03 needs a per-member view of broadcasts, LC07 is a text heuristic (9f), LC08 to LC10 need the time estimate (9g).
  - LC01 needs `data.top-models` declared; without it the rule is reported as not evaluated rather than guessing which models are expensive.
  - The 80 % within 2x accuracy target (A4) needs the recorded-run corpus from 9d and is not yet measured.


### 9d (2026-10-23)

- Built: eleven more data rules (LR02, LR04, LR05, LR07, LR08, LR09, LP01, LP03, LP04, LP06, LP07) and the facts they read (core pack 1.3.0); `escalate:` in rule documents; the labelled corpus runner; the recorded-run accuracy test; and a gated live test (`LiveCostAccuracyTest`, `LLM4J_LIVE=1`) that runs the same cases against a real provider.
- **The live check changed the estimator.** Running the cases against OpenAI's gpt-4.1-nano and gpt-4.1-mini (about 22,000 tokens in all, a few cents) showed three things the mock runs had hidden:
  1. **Loom's default memory is a shared transcript.** Every delegate's task and answer is appended to one transcript, and all of it is put in front of the next delegate (`TranscriptAccumulationEngine`). A step's input grows with every step before it, so a loop costs more each round. The first estimator ignored this and understated loops. The estimator now walks the workflow in order, keeps the transcript (bounded only by `memory { max_tokens }` or a `context` block), and treats parallel and broadcast branches as starting from the same transcript. LC02 now says this (a loop or `for each` with nothing bounding the transcript).
  2. **Legacy bare-name substitution rewrites prompts.** Loom replaces a variable's bare name anywhere in the literal text of a prompt, even inside a word (`PayloadText.bareNames`). A variable called `r` replaced every "r" in "Explain … three short paragraphs" with an earlier answer and multiplied one prompt by thousands of tokens. This was found by an estimate that came out 4x too low. It is now rule LP07, and the labelled corpus found five more scripts of ours with the same hazard.
  3. **Answers are shorter than assumed.** Both models answered "three short paragraphs" in about 200 to 250 tokens, so the default answer length is now 350 (was 600), a variable that holds an answer counts as an answer, and an input of the workflow counts 100 (was 200). The worst-case figures (2,000 for an answer and for a variable) are unchanged and were never below an actual run.
- **A4 (typical within 2x for at least 80 %) is met on the recorded mock runs and not met live.** Live: 7 of 10 with gpt-4.1-nano, 4 of 10 with gpt-4.1-mini, with most misses between 2.0 and 2.9 and always on the high side (the estimate is cautious). The outliers are `for-each` (5 to 9 times too high: the cases hand a terse model the items "one", "two" and it replies in a sentence) and `parallel-three`. The worst case was never below an actual run in any live or recorded case. What to do with a workflow whose typical is off: `weave review --calibrate RUN_DIR`, then set `assume.output.tokens` and the loop and item counts from it.
- Honest limits: the corpus was written and labelled by the same people who wrote the rules, so 1.00 means "agrees with what we meant", not "found in the wild"; the mock runs use answer lengths set to what the live runs showed (about 1,000 characters); only OpenAI models were measured live.

**Rule precision and recall on the labelled corpus**

| Rule | Found right | Wrong | Missed | Precision | Recall |
|---|---|---|---|---|---|
| LC01 | 1 | 0 | 0 | 1.00 | 1.00 |
| LC02 | 8 | 0 | 0 | 1.00 | 1.00 |
| LD02 | 1 | 0 | 0 | 1.00 | 1.00 |
| LG04 | 1 | 0 | 0 | 1.00 | 1.00 |
| LM01 | 1 | 0 | 0 | 1.00 | 1.00 |
| LP01 | 2 | 0 | 0 | 1.00 | 1.00 |
| LP03 | 1 | 0 | 0 | 1.00 | 1.00 |
| LP04 | 1 | 0 | 0 | 1.00 | 1.00 |
| LP06 | 1 | 0 | 0 | 1.00 | 1.00 |
| LP07 | 1 | 0 | 0 | 1.00 | 1.00 |
| LR01 | 8 | 0 | 0 | 1.00 | 1.00 |
| LR02 | 6 | 0 | 0 | 1.00 | 1.00 |
| LR03 | 1 | 0 | 0 | 1.00 | 1.00 |
| LR04 | 1 | 0 | 0 | 1.00 | 1.00 |
| LR05 | 1 | 0 | 0 | 1.00 | 1.00 |
| LR07 | 1 | 0 | 0 | 1.00 | 1.00 |
| LR08 | 2 | 0 | 0 | 1.00 | 1.00 |
| LR09 | 1 | 0 | 0 | 1.00 | 1.00 |
| LY02 | 2 | 0 | 0 | 1.00 | 1.00 |

Scripts: 48. Mismatches: 0


**Estimate against recorded runs (mock provider, real executor)**

| Case | Estimated typical | Actual | Ratio | Estimated worst | Worst >= actual |
|---|---|---|---|---|---|
| single | 474 | 377 | 1.26 | 4024 | true |
| chain | 1691 | 1230 | 1.37 | 12091 | true |
| loop-stops-at-typical | 2878 | 1478 | 1.95 | 60452 | true |
| loop-hits-max | 2878 | 1724 | 1.67 | 24178 | true |
| for-each | 7172 | 4145 | 1.73 | 5135792 | true |
| alt-taken | 1079 | 1223 | 0.88 | 12084 | true |
| broadcast | 948 | 754 | 1.26 | 12086 | true |
| call-twice | 1436 | 872 | 1.65 | 12086 | true |
| parallel-three | 1434 | 1182 | 1.21 | 24202 | true |
| long-prompt | 728 | 630 | 1.16 | 4278 | true |

Within 2x: 10 of 10.


**Estimate against a real provider: gpt-4.1-nano**

Provider model: gpt-4.1-nano

| Case | Estimated typical | Actual | Ratio | Estimated worst | Worst >= actual |
|---|---|---|---|---|---|
| single | 474 | 342 | 1.39 | 4024 | true |
| chain | 1691 | 1162 | 1.46 | 12091 | true |
| loop-stops-at-typical | 2878 | 4261 | 0.68 | 60452 | true |
| loop-hits-max | 2878 | 1417 | 2.03 | 24178 | true |
| for-each | 7172 | 801 | 8.95 | 5135792 | true |
| alt-taken | 1079 | 1141 | 0.95 | 12084 | true |
| broadcast | 948 | 529 | 1.79 | 12086 | true |
| call-twice | 1436 | 902 | 1.59 | 12086 | true |
| parallel-three | 1434 | 521 | 2.75 | 24202 | true |
| long-prompt | 728 | 436 | 1.67 | 4278 | true |

Within 2x: 7 of 10. Tokens spent: 11512.


**Estimate against a real provider: gpt-4.1-mini**

Provider model: gpt-4.1-mini

| Case | Estimated typical | Actual | Ratio | Estimated worst | Worst >= actual |
|---|---|---|---|---|---|
| single | 474 | 265 | 1.79 | 4024 | true |
| chain | 1691 | 807 | 2.10 | 12091 | true |
| loop-stops-at-typical | 2878 | 3413 | 0.84 | 60452 | true |
| loop-hits-max | 2878 | 1335 | 2.16 | 24178 | true |
| for-each | 7172 | 1379 | 5.20 | 5135792 | true |
| alt-taken | 1079 | 1022 | 1.06 | 12084 | true |
| broadcast | 948 | 432 | 2.19 | 12086 | true |
| call-twice | 1436 | 717 | 2.00 | 12086 | true |
| parallel-three | 1434 | 500 | 2.87 | 24202 | true |
| long-prompt | 728 | 417 | 1.75 | 4278 | true |

Within 2x: 4 of 10. Tokens spent: 10287.


**Noise on the repository's own examples (unlabelled; reported, not gated)**

Repository examples reviewed: 14 (skipped 0)

| Rule | Findings |
|---|---|
| LC02 | 10 |
| LM01 | 6 |
| LP07 | 2 |
| LR01 | 7 |
| LR02 | 8 |
| LR03 | 1 |
| LR07 | 2 |
