# Phase 28 verification plan: evaluation first

Status: **28a to 28e built (2026-11-04); manual checks M1 to M4 owed**. Run §0 first, then top to bottom at the end of each sub-phase. Proposed sub-phases: **28a** louder (R1 to R3); **28b** the gate (R4); **28c** `--requirements` (R5); **28d** `--changed`, the CI template and the teaching (R6 to R8); **28e** the judge, LR12 and the confidence teaching (R12 to R14). The stack-level pages (R9) are phase 29's. R1 to R3, R5 and R6 do not depend on phases 24 and 26 and may be built first. Results in §7.
Evidence: E12, E13 of the [program page](../DISCIPLINE.md). Strategy: [TEST_STRATEGY.md](TEST_STRATEGY.md).

## 0. Reproduce first

| # | Evidence | Procedure | If it is real, you see | Result |
|---|---|---|---|---|
| P1 | E12 | `weave review` on a starter with its tests folder removed. | no LT01 (the lens is off); `Lens.java` marks testability optional; Phase 9 R33 says why | ✅ 2026-11-04: no LT finding by default; `--lens +testability` showed LT01 |
| P2 | E13 | `weave guide`. | stage 2 is "Build a golden dataset (optional)" | ✅ 2026-11-04: stage 2 read "Build a golden dataset (optional)" |
| P3 | R1 | `weave review --lens +testability` with `heuristics = off` on a script with no dataset. | no LT06 (it is heuristic) | ✅ 2026-11-04: LT01 only, no LT06 |
| P4 | R2 | `weave run` and `weave deploy` on a script with no dataset. | nothing says that nothing measured the agents | ✅ 2026-11-04: `weave run` printed only the run lines; no notice anywhere (checked by reading the code paths: nothing looked for a dataset) |

## 1. Acceptance criteria

| # | Criterion | Evidence |
|---|---|---|
| A1 | The Testability lens is on by default; LT06 is exact, medium, and agrees with `weave eval` | R1 tests, reader agreement |
| A2 | A script with no cases says so on run, deploy, the live page and `weave next`, once, quietly removable | R2 tests |
| A3 | The guide and the skill call the dataset the requirements; skipping stays possible and recorded | R3 tests, §5 |
| A4 | `weave check` fails on an extractor with no case and on an evaluator missing a breaking or a clean case for any rule of any policy it checks; no flag skips it; a scaffold never satisfies it | R4 tests |
| A5 | `weave eval --requirements` renders a page a non-technical reviewer can read, shows the dataset's identity, lists what has no requirement, and records approvals of an exact identity | R5 tests |
| A6 | `weave eval --changed` runs only what changed (and its routes), says what changed, needs a cap, and fails closed when it cannot tell | R6 tests |
| A7 | The CI template runs the three costs safely by default | `CiTemplateTest` |
| A8 | The tutorial and guide teach "the dataset is the requirements", cases for extractors and evaluators, and the three costs; examples load | `TutorialExamplesTest`, `GuideTutorialPageTest`, §5 |
| A9 | Every guard is noticed when removed | PIT score not lower; by-hand removals in §7 |
| A10 | An evaluator that flags more than its limit of clean and near-miss cases fails `weave eval`; internal nodes have their own rows in the report; LT06 is grouped, its severity follows LT01, and LT01 and LT06 meet the precision bar and the noise gate | the R10, R11 and D35 tests |
| A11 | `weave eval` judges with a different model by default, says which and why, makes self-judging explicit and cautioned, and never refuses for want of a second model; LR12 warns when an agent decides its own loop is done (and at low for an evaluator on the checked agent's model); the skills teach that self-reported confidence is not calibration | the R12 to R14 tests |

## 2. Traceability: requirement → tests

| Req | Tests | Status |
|---|---|---|
| R1 | `TestabilityLensOnByDefaultTest`, `LensesMinusTestabilityRestoresTest`, `Lt06UsesEvalLookupTest`, `Lt06IsMediumAndNotHeuristicTest`, `Lt06CoveredByWorkflowExpectTest` | ⬜ |
| R2 | the R2 tests of the strategy | ⬜ |
| R3 | `GuideStageTwoTitleTest`, skill text test | ⬜ |
| R4 | the R4 tests of the strategy | ⬜ |
| R5 | the R5 tests of the strategy | ⬜ |
| R6 | the R6 tests of the strategy | ⬜ |
| R7 | `LoomGuideBlocksParseTest`, `TutorialExamplesTest`, `GuideTutorialPageTest`, `verify-skill-package.sh` | ⬜ |
| R8 | `CiTemplateTest` | ⬜ |
| R10 | the R10 tests of the strategy | ⬜ |
| R11 | `ByAgentAndNodeSectionTest` | ⬜ |
| R12 | the R12 tests of the strategy | ⬜ |
| R13 | the R13 tests of the strategy | ⬜ |
| R14 | skill text checks | ⬜ |

## 3. Quality gates

- [ ] `mvn verify` green on `ai-agent4j-loom`, `eval4j`, `eval4j-report`, `loom-inspect*` and `loom-fabric` (after installing the Loom jar); coverage gates held, none lowered.
- [ ] PIT: the gate, the fingerprint and `--changed` added to the mutation profiles; scores not lower.
- [ ] Every starter and example that uses an extractor or an evaluator passes `weave check` with real cases (`StartersTest`, `RepositoryScriptsTest`).
- [ ] `bash scripts/make-tutorial-html.sh`; `GuideTutorialPageTest` green.
- [ ] `weave rules docs core`; `weave rules test core` green.
- [ ] `bash scripts/verify-skill-package.sh` green for the workflow guide, eval design and harness design.
- [ ] `make format-check`, `make release-check` green. No new dependency.

## 4. Manual checks

| # | Check | Pass |
|---|---|---|
| M1 | Run `weave eval --requirements` on starter 17 and give the page to someone who is not a developer: can they say what the workflow must and must never do? | ⬜ |
| M2 | Change one word of starter 17's policy, then of one agent's prompt; `weave eval --changed --max-tokens 30000 --mock` lists exactly the nodes expected; then a capped real run of the changed nodes only | ⬜ |
| M3 | Follow the workflow-guide skill from a blank folder to a workflow with an extractor and an evaluator: the gate is explained before it fires, and the cases are written with the user | ⬜ |
| M4 | Read tutorial chapter 10 as a newcomer | ⬜ |

## 5. Documentation owed

`LOOM_GUIDE.md` ⬜ · `LOOM_PROMPT.md` ⬜ · `docs/guide/02` ⬜, `05-test-agents-with-caps` ⬜, `09-go-live` ⬜ · tutorial ch. 1 ⬜, 5 ⬜, 9 ⬜, 10 ⬜, 14 ⬜, README ⬜, HTML regenerated ⬜ · `docs/review/rules/` ⬜ · phase 9 DESIGN R33 note ⬜ · `docs/TESTING_STRATEGY.md` ⬜ · eval4j README ⬜ · command reference pages ⬜ · skills (workflow guide ⬜, eval design ⬜, harness design ⬜) · starters and starters README ⬜ · `API_COMPATIBILITY.md` ⬜ · `llms.txt` ⬜ · release notes ⬜.

## 6. Exit checklist

- [ ] §0 recorded with dates and build ids
- [ ] Every row in §2 ✅, or partial with a reason; every A-criterion has evidence
- [ ] M1 to M4 done and recorded in §7
- [ ] DESIGN §7 questions answered
- [ ] Docs in §5 updated; roadmap status updated

## 7. Results

### 28a: louder (2026-11-04)

**Built.** The Testability lens is on by default (`Lens.TESTABILITY` no longer optional; docs and tests that counted nine lenses now count ten). New `eval.GoldenDatasets` is the one lookup of which agents and nodes have golden cases (file name matched without regard to case, `.yaml` or `.yml`, empty files do not count, a workflow case that names an agent in an `expect` line covers it); LT06 uses it, is no longer heuristic, is one finding per script naming every agent, extractor and evaluator without cases, and takes LT01's severity (medium when the script can write, send or ask a person, else low). The "No evaluation" line is printed after `weave run` (error stream; `--quiet` hides it) and by `weave deploy`, which also writes it into the generated README under "Before you rely on this"; when the README says `Evaluation: skipped` the line says so instead. The run start event carries `evaluated: false` (set by the host that ran the check, never read from the dataset by the page), `RunState` exposes it and the live page shows a dismissible banner. `weave next`'s stage is titled "Requirements (golden dataset)". `weave guide` stage 2 and the guide chapter, the README table, the workflow-guide skill row and its "ask once" text say the dataset is the requirements and that skipping is loud and recorded. Every one of the 17 starters now carries `eval/golden/workflow.yaml`.

**Tests (written first, red then green).** `TestabilityLensTest` (lens default and `-testability`, LT06 exact, grouped, severity, agreement with `weave eval` on file naming, empty file, expect-line cover), `NoEvaluationNoticeTest` (run, `--quiet`, any case, scaffold with no cases, nothing to measure, skipped on purpose, deploy output and README), `RunStartEvaluatedFlagTest`, `RunStateTest` and `TraceMapperTest` for the flag, `StartersTest` (every starter has accepted golden cases), `ReviewNoiseGateTest` now runs the Java testability source too and counts LT01 and LT06.

**Deviation (noise gate, D35).** The gate caught LT06 firing on 17 of 17 starters, so the starters were given golden cases instead of demoting the rule. The cases are one end-to-end case each, written from each starter's README; they are teaching examples, not a measured dataset. Discipline lost: none; the starters now show the practice. What covers a weak case: `weave eval --check` validates them and the starters README says they are a start.

**Not done in 28a.** LT01 and LT06 precision was measured only on the 17 starters and the existing review corpus (which has no LT cases); no labelled LT corpus was built. `weave eval --check` reading of extractor and evaluator case files is 28b.


### 28b: the gate (2026-11-04)

**Built.** `eval.NodeCasesGate` returns the problems of the golden cases of the extractors and evaluators a script uses: an extractor needs a case that names fields (`expected_fields` and/or `expected_missing`); an evaluator, for every rule of every policy it checks (from `check … using` and `policy … checked by`), needs a case that breaks it and a clean one (a near miss counts as clean). It also checks structure: unique ids, a text, fields that exist (with "did you mean"), a required field cannot be `expected_missing`, rules that exist, `violated | clean` only, and a YAML file that cannot be read is reported by name and line, not as "no cases". `weave check` reports these as errors (so `weave serve` and `weave deploy`, which call it, do too) and `weave eval --check` repeats them; no flag skips the gate. `eval4j` `EvalDataset.load(dir, skip)` leaves the node files out so they are not read as agent cases; `GoldenDatasets` knows the node files and counts them for LT06 and the notice. `weave eval <script> --init` writes commented files with no cases for each extractor and evaluator. `LoomLoader` never reads `eval/golden`; it logs once at WARNING (the `weave` command turns it off). LP09 and LP10 fixes name the file and the command, and say when the old agent's cases must be rewritten. Starters 08, 16 and 17 carry their cases; tutorial blocks that declare an extractor or an evaluator are now `loom-parse` (a block alone cannot have cases); chapter 10 has "Cases for an extractor" and "Cases for an evaluator".

**Tests (written first).** `NodeCasesGateTest` (18), `CheckGateCliTest` (gate in `check`, no skip flag, scaffold never satisfies it, one case does, `eval --check`), `GateNoticeOnLoadTest`, `GoldenCasesNoteTest`, `StartersTest` (golden cases accepted).

**Guard removed by hand.** With `if (hasBreak && hasClean) continue;` changed to `if (hasBreak) continue;`, no test failed at first: two assertions used `allSatisfy` on a list that was empty. They now also say `hasSize(2)`; with the guard removed `aRuleWithABreakingCaseButNoCleanCaseIsRejected` fails, and with it restored all 18 pass. The prompt-fingerprint guards belong to 28d.

**Deviations.** (1) The message says `weave eval <script> --init`, not `weave eval X --init`: that is the command that exists. (2) The LP09/LP10 fixes do not rewrite the old agent's cases into extractor cases; they say so and name the file, and the gate then names what is wrong with them. Discipline lost: an automatic conversion. What covers it: the gate fails right after the fix, and says which file and what is wrong. (3) Under `weave eval`, extractor and evaluator cases are **not run against a model yet** (only checked for structure); that comes with R10 and R11.

**Not done in 28b.** `HostLoadDoesNotReadGoldenAndWarnsOnceTest` is `GateNoticeOnLoadTest`; the mutation profile does not yet include the gate (28d, with the fingerprints).

### 28c: `--requirements` (2026-11-04)

**Built.** `weave eval <script> --requirements [--format md|html]` prints the dataset as plain sentences (`RequirementsPage`): per workflow and agent each case as "When someone gives *…*, the answer must mention …, must never contain …, must be at least N words; a judge must confirm that it *…*; the run must have: *…*"; per extractor the input text, the fields expected and those that must be missing; per policy rule its text, the examples that break it and the ones that must not count (near misses marked); the identity read (dataset version and hash, each policy's version and hash); the approval line; and "No requirement yet" listing every agent and node with no case. Personal data is masked by `Masker`; the HTML form escapes everything. It reads files only. `--approve --approver <name> --why "<text>"` records the exact identity (`RequirementsApprovals`, `eval/requirements-approvals.yaml`); a later change to a case or a policy makes the page say "changed since approved by …". `--approver` defaults to git `user.email`. The man page, the generated command reference, the guide, tutorial chapter 10, chapter 9 of the guide, the workflow-guide skill (show the page and ask for sign-off at go-live), `llms.txt`, release notes and API notes are updated.

**Tests (written first).** `RequirementsPageTest` (15: sentences, extractor, policy rule, identity, missing list, masking, html escaping, unknown format, approval, change after approval for a case and for a policy, approver and reason required) and `RequirementsCliTest` (6).

**Not done.** M1 (give the page to someone who is not a developer) is owed to you. The golden Markdown/HTML files of the strategy are assertions on content rather than whole-file goldens, so wording can change without a test failing on every sentence. The page shows each case's check types but not the cases' `dimensions`.

### 28d: continuous and cheap (2026-11-04)

**Built.** `NodeFingerprints` (per node, per part: prompt including the shared prompt, model, temperature, schema, tools and effect, an evaluator's settings and policies, the node's cases); `weave eval --changed [--since baseline|last|file]` selects changed agents, the workflows that reach a changed node (delegate, extract, check, call, transitively) and the changed nodes' own cases, prints what changed, needs a cap, and records fingerprints after every complete real run and with every saved baseline; an unreadable prompt or a missing reference counts as changed. `NodeCaseRunner` (public facade in `execution`) runs one extractor or evaluator case with the same prompt a workflow uses (the extractor request is now built in one place, shared with `ExtractRunner`); `NodeCasesEval` turns node cases into report rows, computes each evaluator's false-alarm rate with a Wilson 95% interval and fails the run above `false_alarm_limit`; the section is "By agent and node". `--requirements` shows the limit beside each policy. `weave init` writes the CI file (four templates; a test parses it). Docs: tutorial chapter 10 (three costs, `--changed`, false alarm, CI), LOOM_GUIDE, TESTING_STRATEGY, man page, skills (eval-design E4 and E6, harness-design stage 9), release notes, API notes.

**Tests (written first).** `NodeFingerprintsTest` (17), `ChangedEvalTest` (5), `NodeCasesEvalTest` (11), `CiTemplateTest` (5), `RequirementsPageTest` (+1).

**Guards removed by hand.** (1) The prompt part of an agent's fingerprint replaced by a constant: 4 tests failed. (2) An unreadable prompt given a stable value instead of a fresh one (fail closed): `anUnreadablePromptFileCountsAsChangedEveryTime` failed. (3) `over = rate > limit` replaced by `false`: 2 tests failed. All restored.

**Mutation (PIT).** Added `NodeCasesGate` and `NodeFingerprints` to the Loom `mutation` profile (tests: their own plus `ChangedEvalTest`). First run: gate 69 of 82 killed (84%), fingerprints 30 of 52 (58%); I added tests for `workflowsReaching`, prompt files, enum and list schemas, evaluator settings, empty expectations, a map instead of a list, near-miss suggestions and nested checks. Second run: gate 75 of 82 (91%), fingerprints 47 of 52 (90%). What still survives is in `caseCount`/`cases` (used by CLI tests that are not in the profile) and four equivalent mutants in `load`/`casesHash`/`hash`.

**Deviations.** (1) `InitCommandTest` forbade the text `{{` in any written file; GitHub's `${{ secrets.KEY }}` contains it, so the test now forbids an unreplaced `{{word}}` placeholder, which is what it was for. (2) Latency is not shown per node (the agent facts carry calls, tokens and cost, not time). (3) An evaluator case counts `samples` calls per case, an estimate, because the evaluator reports tokens but not call counts; node-case cost is not priced. (4) Under `--agent` or `--workflow` or `--split` node cases and the fingerprint record are skipped, so a partial run never claims it measured everything. (5) Baselines record fingerprints in a separate `eval/baseline-fingerprints.json` (the latest baseline only), not inside each baseline file.

**Not done.** R7's remaining items are 28e's judge teaching; `llm4j-workflow-guide` stage text for the three costs is only partly there (the go-live sign-off and the gate are); the CI file targets GitHub Actions (DESIGN open question 3: proposed answer taken). M2 (change a word of starter 17's policy and a prompt, then a capped real `--changed` run) is owed to you; it spends money, so I have not run it.

### 28e: the judge, LR12 and the confidence teaching (2026-11-04)

**Built.** `eval.JudgeChoice`: the judge is `--judge`, else `judge:` in `dataset.yaml`, else `judge` in a project-root `eval.properties`, else the cheapest other model the project can reach (a model the script names whose key is set), preferring another provider; `--judge same` is deliberate and cautioned; with one reachable model it judges itself with a caution and never refuses. The confirmation line before a real run, *Read this first* ("Chosen: …") and *Cautions* ("Judge: self-judging …") say which and why. Review rules: **LR12** (medium) a loop whose condition reads the answer of an agent that also produces the work the loop iterates on (the same agent binds another variable that is read, or is handed its own answer again); **LR14** (low) a `check` whose evaluator runs on the same model as the agent that wrote the text. Both are structural (`SelfGradingFacts`), have examples, corpus cases and docs pages, and are in the noise gate. Starter 17's evaluator moved to a stronger model so that the flagship starter does not trip its own rule. Teaching: the Loom guide ("Who judges", "Output Schema Enforcement" confidence note), tutorial chapter 10, the calibration reference of the eval-design skill, workflow-guide stage 6, release notes and API notes.

**Tests (written first).** `JudgeChoiceTest` (14: the order, reachability, same, one model, tie-breaking, "gemini is not mini", the two settings files), `JudgeInReportTest`, `EvalCommandTest` (the confirmation line follows the new default and `--judge same`), the LR12 and LR14 examples and corpus cases (`ReviewCorpusTest` precision and recall gate, `RulesToolTest`), `ReviewNoiseGateTest` with LR12 and LR14.

**Deviations.** (1) LR14 is its own id: the design put the evaluator-on-the-same-model case under LR12, but a rule is about one kind of step (as with LH06 in phase 27). (2) LR12 does not read `rewind ... when` conditions and has no `add-reviewer` fix. Discipline lost: the loop-via-rewind form and the one-click rewrite. What covers it: the rule page and LOOM_GUIDE name the three ways to end a loop. (3) "Cheapest" is a rule of thumb from the model name (flash, haiku, mini, lite, nano are cheap; pro, opus, sol are dear) because no price list is known without `--prices`; it is shown with the choice and any setting overrides it. (4) The quiet example for "a task decides" is a person deciding: a task cannot be declared in a script. (5) eval4j has no default-judge factory (its judge is already an explicit parameter), so nothing changed there and `DefaultJudgeFactoryOrderTest` does not exist.

**Not done / owed.** `scripts/verify-skill-package.sh` prints `run-observed failed from the package` here, and prints the same on the previous commit, so it is not caused by this phase (I did not find why: it needs the observe add-on). Manual checks M1, M3 and M4 are yours; M2 is the live run you have asked me to do after phase 29.

### M2: the capped live `--changed` check (2026-11-04, delegated by the owner, run after phase 29)

Model: OpenAI `gpt-4o-mini`, key from the environment (never printed). A temporary copy of starter 17 outside the repository, every model name set to that one model (so the judge was the same model, and the report said so in *Cautions*). Caps stated before the runs: run 1 `--max-tokens 30000 --max-calls 30`, run 2 `--max-tokens 20000 --max-calls 20`; both finished far inside them (about 11 calls each; no price table was given, so the report says cost unknown; the evaluator rows show 1,030 tokens for its 4 calls).

- **Run 1, the reference (a full real `weave eval`).** It recorded the fingerprints. 3 of 6 passed. Real findings: the evaluator's **false-alarm rate was 16.7%** (1 of 6 clean and near-miss answers flagged; limit 10%), so the run failed on it, the same near-miss over-blocking the phase 26 live check found, now measured by the gate that was built for it. And all the workflow case's `expect:` lines failed because **the account of the run that they are judged against left out the `extract`, `check`, `classify` and policy steps** (a bug of phases 24 to 28 that no scripted test could show).
- **The fix (written first).** `EvalRunner.account` now names those steps; `EvalRunnerTest.theAccountNamesTheNewerStepsSoAnExpectLineAboutThemCanBeConfirmed`.
- **Run 2, after changing one word of the policy and one word of Support's prompt.** `weave eval --changed --mock` listed exactly `Compliance: policies` and `Support: prompt` (not Editor). The real `--changed` run ran only the cases that reach them: 5 of 6 passed (the three `expect:` lines now confirmed). A third `--changed` call after it said "Nothing to run".
- **What it does not prove.** One small model judging itself, one starter, one change of each kind. The remaining failure is the workflow rubric line "No reply promises a refund" scored 0 by the judge on a reply that did not promise one; a small model grading itself is exactly the case R12 cautions about, and I did not tune it.
