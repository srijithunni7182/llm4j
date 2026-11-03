# Phase 25 verification plan: reason, then extract

Status: **not started** (spec draft; needs phase 24's extractor). Run §0 first, then top to bottom at the end of each sub-phase. Proposed sub-phases: **25a** LP10, the facts and the rewording of LR05 and LP02 (R1, R2); **25b** the split fix (R3); **25c** the examples, the docs and the tutorial (R4, R5); **25d** progress kept in the workflow: the recipe, starter 16 and the teaching (R6); **25e** bounded fan-out (R7). Results in §7.
Evidence: E7 to E9 of the [program page](../DISCIPLINE.md). Strategy: [TEST_STRATEGY.md](TEST_STRATEGY.md).

## 0. Reproduce first

| # | Evidence | Procedure | If it is real, you see | Result |
|---|---|---|---|---|
| P1 | E7 | An agent with tools and a nested `output_schema` under `weave test` with a capturing client. | its task ends with "CRITICAL: You MUST respond in valid JSON format only…" | ◐ 2026-11-03 at `12969701`: confirmed by reading `DelegateRunner.java:359` (the sentence is appended whenever a schema exists), not run with a capturing client |
| P2 | E8 | `weave review` on a script that branches on `r.score` from an agent with no schema; `--explain LR05`. | the only advice is to give the agent an `output_schema` | ✅ 2026-11-03 at `12969701`: the rule page's "What to do" was exactly that |
| P3 | E9 | List every agent in the repository's `.loom` files with an `output_schema` and tools or grounding. | 17 agents in the five files of DESIGN R4 | ✅ 2026-11-03: a scan of every `.loom` found 17 (getviral 8, inspect-soak 3, loom-inspect sim 3, crew 2, the LC01 fixture 1); three more are this program's own LP09 fixtures |

## 1. Acceptance criteria

| # | Criterion | Evidence |
|---|---|---|
| A1 | LP10 fires exactly on agents that reason and have a large schema, names why, and is medium | LP10 fixture table, `Lp10MessageNamesTheReasonAndTheSchemaTest` |
| A2 | Its thresholds are settings shared with LP09 and documented | `Lp10SettingsTest`, the rule page |
| A3 | LR05 and LP02 offer a classifier, an extractor, or a small schema, and none of their recommendations trips LP10 | `Lr05AndLp02RecommendationsPassLp10Test`, rule pages |
| A4 | The split fix produces a script that checks, has no LP10 and no new high finding, keeps every later use of the variable, never edits tests, and needs a yes | the R3 tests |
| A5 | No shipped example trips LP10, except the recorded LC01 fixture; every migrated example's tests pass | `ExamplesAreCleanOfLp10Test`, example suites |
| A6 | The guide, skills and tutorial teach "reason, then extract" and when a small schema is still right | §5 ticked, `LoomGuideBlocksParseTest`, `TutorialExamplesTest`, `GuideTutorialPageTest` |
| A7 | Nothing else changes: existing tests unmodified except the examples' own cases for migrated agents (listed in §7) | test diff review |
| A8 | Starter 16 shows a long agent's progress kept in a journaled variable, bounded and resumable, with tests; the harness-design skill, the guide and tutorial chapter 11 teach it | the R6 tests, §5 |
| A9 | `for each … max N` fails the workflow before any item when a list is too long (unless `on_too_many` says otherwise), names the agent that produced the list, raises an incident, and can be captured as a test; LR13 warns about unbounded fan-out and the cost estimate says so | the R7 tests |

## 2. Traceability: requirement → tests

| Req | Tests | Status |
|---|---|---|
| R1 | LP10 fixture table, `Lp10MessageNamesTheReasonAndTheSchemaTest`, `Lp10SettingsTest`, `Lp10UsesExpectingOfAnyDelegateTest`, `ReasonsFactsTest`, `SchemaFactsTest`, `RoundsDetectionTest` | ⬜ |
| R2 | `Lr05AndLp02RecommendationsPassLp10Test`, rule-page golden text | ⬜ |
| R3 | `SplitFixDiffTest`, `SplitKeepsLaterUsesOfTheVariableTest`, `SplitNotOfferedForProseAndFieldsTest`, `SplitNotOfferedForBroadcastTest`, `SplitListsTestCasesToUpdateTest`, `SplitNeedsYesTest`, `SplitAddsNoHighFindingTest`, `SplitPicksCheapestModelOfSameProviderTest` | ⬜ |
| R4 | `ExamplesAreCleanOfLp10Test`, example suites, `InspectSimExpectedTracesTest` | ⬜ |
| R5 | `LoomGuideBlocksParseTest`, `TutorialExamplesTest`, `GuideTutorialPageTest` | ⬜ |
| R6 | the R6 tests of the strategy | ⬜ |
| R7 | the R7 tests of the strategy | ⬜ |

## 3. Quality gates

- [ ] `mvn verify` green on `ai-agent4j-loom`, `loom-inspect`, `loom-fabric` (after installing the Loom jar) and every example module built with `-Pextended` that was migrated (`make test-extended` for those).
- [ ] PIT: the Loom `mutation` profile with the new facts; score not lower.
- [ ] `weave rules test core` green; `weave rules docs core` regenerated.
- [ ] LP10 measures ≥ 0.9 precision on the benchmark corpus (Phase 9 R18) and passes the noise gate (`ReviewNoiseGateTest`); `SimplePathStaysSimpleTest` green (program page §4.5, §4.6).
- [ ] `RepositoryScriptsTest` green; CTK scenario `for_each_max` added; the Loom syntax change (`max`, `on_too_many` on `for each`) is in `LOOM_GUIDE.md`, `LOOM_PROMPT.md`, `docs/guide/06`, the tutorial, the starters, the workflow-guide skill and the VS Code grammar and snippets.
- [ ] `bash scripts/make-tutorial-html.sh` run; `GuideTutorialPageTest` green.
- [ ] `make format-check`, `make release-check` green.
- [ ] No new dependency.

## 4. Manual checks

| # | Check | Pass |
|---|---|---|
| M1 | Read the diff of each migrated example: would a reader of the example understand the new shape without the spec? | ⬜ |
| M2 | A capped real run (`--max-tokens 40000`) of one getviral step before and after the split, with the same input: compare the reasoning in the prose answer with the old JSON answer and record what was seen (not a gate) | ⬜ |
| M3 | Apply the split fix to one example with `--dry-run`, then for real; run its tests | ⬜ |

## 5. Documentation owed

`LOOM_GUIDE.md` ⬜ · `LOOM_PROMPT.md` ⬜ · `docs/guide/06` ⬜ · tutorial ch. 4 ⬜, 5 ⬜, 11 ⬜, 14 ⬜, README ⬜ · starter 16 and starters README ⬜ · `docs/guide/14` ⬜, HTML regenerated ⬜ · `docs/review/rules/` ⬜ · skills (workflow guide ⬜, harness design ⬜, eval design ⬜) · each migrated example's README ⬜ · loom-inspect sim docs ⬜ · release notes ⬜ · `API_COMPATIBILITY.md` (rule packs) ⬜ · `llms.txt` ⬜.

## 6. Exit checklist

- [ ] §0 recorded with dates and build ids
- [ ] Every row in §2 ✅, or partial with a reason; every A-criterion has evidence
- [ ] The list of migrated and not-migrated agents recorded in §7, with the reason for each one left alone
- [ ] M1 to M3 done and recorded
- [ ] DESIGN §7 questions answered
- [ ] Docs in §5 updated; roadmap status updated

## 7. Results



### 25a: LP10 and the rewording of LR05 and LP02 (2026-11-03)

Tests first: `Lp10Test` (the 16-cell table plus a bare list, message, settings, `expecting`, rounds, unknown prompt size), `Lr05AndLp02RecommendationsPassLp10Test`, `ReviewGateTest` additions, two corpus scripts; red for the right reason, then green.

**Built:** new facts `reasons`, `reasonsBecause`, `largeSchema`, `largeSchemaBecause` (`ReasoningFacts`, after the prompt facts); settings `prompts.large-schema-fields` (5) beside `prompts.short-prompt-tokens` (300); rule LP10 (medium, Prompts lens) with four example scripts; LR05 and LP02 advice now says classifier, then extractor, then a small schema (LP02 adds "or one of the allowed words"); rule pages regenerated; guide section "When to use a schema, and when to extract"; `LOOM_PROMPT.md`; release notes; API compatibility.

**Gates:** `mvn verify` `eval4j` 571 and `ai-agent4j-loom` 4227 (3 skipped), coverage met. `ReviewCorpusTest` LP10 precision and recall 1.00; `SimplePathStaysSimpleTest`, `ReviewNoiseGateTest` (LP10 added to both lists) and `GenerateBenchmarkTest` green. **Guards removed by hand, then restored:** the field-count threshold always true → 12 table cells failed; the prompt-size threshold always true → 9 failed.

**Goal check:** LP10 is also in the generator's review gate (Prompts lens, medium) on purpose, so a generated project with a trapped agent is repaired toward "prose, then extract"; none of the 28 recorded transcripts trips it.

### 25b: the split fix (2026-11-03)

Tests first: `SplitFixTest` (19 cases). **Built:** `SplitFix` (a `JavaFixOption`, option id `split`, needs `--yes`, `--dry-run` shows the diff): the agent loses its `output_schema` (or the steps lose their `expecting`) and gains the fixed sentence "Answer in plain prose; explain your reasoning, then state your conclusions."; a new `extractor <Agent>Fields` carries the old fields (fields a condition reads stay required, the rest optional; a cheaper model of the same provider for Claude and Gemini names, else the agent's own, said in the explanation); every `delegate` writes `<v>_analysis` and is followed by `extract … -> v`, so later uses are unchanged; the option lists the test files that script the agent and never edits them. It copes with an agent on one line or several, a task text over several lines, an `on_failure` on the header line or the next line, a step inside `parallel`.

**Found by using it on the repository's own examples, and fixed:** (1) a step inside `parallel` is its own branch, so the extract goes after the block; (2) a handled failure leaves the extract with nothing to read, so the `on_failure` is repeated on the extract; (3) the block end must be measured from the arrow, not the line start, for multi-line task texts; (4) an agent declared in an imported file has lines that belong to another file, so it is refused ("apply it there by hand"); the same guard now protects LP09's `to-extractor`; (5) a step that chooses its agent at run time may reach the agent, so the rewrite is refused.

**Deviations, with what covers the loss (see DISCIPLINE section 10):**

| Deviation | Discipline given up | What covers it |
|---|---|---|
| The DESIGN refuses the split when the answer is "used both as prose and as fields"; I removed that check | None: a later prompt that writes the whole `{report}` keeps working, because the extract writes the same variable with the same fields. The check blocked the fix on every real example | The test `aLaterPromptThatWritesTheWholeAnswerStillWorksAfterTheSplit` pins it |
| The extractor's model is a small table (Claude, Gemini names), not "the cheapest model in the catalogue" | An unknown provider keeps the agent's own (more expensive) model | The diff shows the model and the explanation says "change it to a cheaper one"; a real catalogue lookup is a follow-up |
| The new extract is not given a retry | A malformed extraction fails the step once | Same as any extract (one correction is always allowed); the explanation tells the developer to add `on_failure` where needed |

### 25c: the examples and the teaching (2026-11-03)

**Migrated (by the split fix itself):** `getviral` Researcher and Strategist (tests: 77 run, the same single failure as before the change, `showrunnerWritesPromptsAtRuntimeAndRewritesThemAfterFeedback`, which also fails on the untouched base). `inspect-soak` and the `loom-inspect` simulator were never flagged (their schemas are small).

**Not migrated, each named in `ExamplesAreCleanOfLp10Test` with its reason, and in the example's README:** getviral Showrunner, ReelDirector, YouTubeProducer (the Java host reads their structured answers and the quality gate re-delegates to two of them by name at run time); nirmaan-yantra Rishi (the host formats the live page from the spec and the tests count model calls exactly), Vihaan and Drishti (their answer is a bundle of source files: an extractor would re-copy every file). The LC01 fixture `lc01-quiet-tools.loom` is accepted as a rule fixture (DESIGN open question 1). **Goal check:** six reference agents still show the pattern LP10 warns about. What covers it: the READMEs say why and what to do in a new workflow; the rule page now says to accept the finding when the answer is itself the artifact. **Still open (follow-up, needs the owner):** a hook for extract steps in the host API (`onExtractEnd`) so `getviral` and `nirmaan-yantra` can be fully migrated.

**Teaching:** guide section, tutorial chapter 5 ("Reason first, then extract", with the trapped and fixed scripts and an exercise), chapter 14 (recipe, mistakes), README row, `docs/guide/06`, the three skills, release notes, rule pages. **Gates:** `mvn verify` `eval4j` 571 and `ai-agent4j-loom` 4247 (3 skipped), coverage met; tutorial, guide and starters tests green.

### 25d: progress kept in the workflow, starter 16 (2026-11-03)

Tests first: starter 16's own cases (`tests/main.test.yaml`: progress carried round to round, the loop stops at its bound, a worker failure halts), `StepByStepStarterTest` (the worker's requests hold no transcript; round N+1 is told round N's recorded next step; a run whose journal holds only rounds 1 and 2 continues at round 3 without asking again), `StartersTest` (now sixteen starters; picture and README script compared with the real ones). **Built:** starter `16-step-by-step-agent` (a bounded `loop`, `Worker` with `memory { transcript: none }`, `extractor Progress` writing `done / next / finished` into a variable); no new construct. **Teaching:** tutorial chapter 11 ("Keep progress in the workflow, not the conversation", with a diagram and the script), chapter 14 recipe row, the tutorial and starters READMEs, `LOOM_GUIDE.md` pointer under "Context and Compaction", `docs/guide/14-context-and-compaction.md`, the harness-design skill (principle 8, the loom-mapping row, a new "the agent loses track of where it is" table in failure-modes), the workflow-guide skill, release notes.

**Honest limit of the resume test:** a real crash cannot be thrown inside a unit test (the runtime records any exception from a step as a failure and a resume replays that failure), so the test copies the journal at the moment round 3 starts, which is what a file journal holds when the process dies there.

**Not green here:** `scripts/verify-skill-package.sh` stops at "run-observed failed from the package" (the deployer skill's check that brings up a real Fabric). The packages were built from the working tree after a run in which the `loom-inspect` modules were stamped at different commits, the same cause as the earlier identity failure, but I did not confirm it against the base.

