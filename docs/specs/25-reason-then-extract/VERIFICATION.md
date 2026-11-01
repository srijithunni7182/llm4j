# Phase 25 verification plan: reason, then extract

Status: **not started** (spec draft; needs phase 24's extractor). Run §0 first, then top to bottom at the end of each sub-phase. Proposed sub-phases: **25a** LP10, the facts and the rewording of LR05 and LP02 (R1, R2); **25b** the split fix (R3); **25c** the examples, the docs and the tutorial (R4, R5). Results in §7.
Evidence: E7 to E9 of the [program page](../DISCIPLINE.md). Strategy: [TEST_STRATEGY.md](TEST_STRATEGY.md).

## 0. Reproduce first

| # | Evidence | Procedure | If it is real, you see | Result |
|---|---|---|---|---|
| P1 | E7 | An agent with tools and a nested `output_schema` under `weave test` with a capturing client. | its task ends with "CRITICAL: You MUST respond in valid JSON format only…" | ⬜ |
| P2 | E8 | `weave review` on a script that branches on `r.score` from an agent with no schema; `--explain LR05`. | the only advice is to give the agent an `output_schema` | ⬜ |
| P3 | E9 | List every agent in the repository's `.loom` files with an `output_schema` and tools or grounding. | 17 agents in the five files of DESIGN R4 | ⬜ |

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

## 2. Traceability: requirement → tests

| Req | Tests | Status |
|---|---|---|
| R1 | LP10 fixture table, `Lp10MessageNamesTheReasonAndTheSchemaTest`, `Lp10SettingsTest`, `Lp10UsesExpectingOfAnyDelegateTest`, `ReasonsFactsTest`, `SchemaFactsTest`, `RoundsDetectionTest` | ⬜ |
| R2 | `Lr05AndLp02RecommendationsPassLp10Test`, rule-page golden text | ⬜ |
| R3 | `SplitFixDiffTest`, `SplitKeepsLaterUsesOfTheVariableTest`, `SplitNotOfferedForProseAndFieldsTest`, `SplitNotOfferedForBroadcastTest`, `SplitListsTestCasesToUpdateTest`, `SplitNeedsYesTest`, `SplitAddsNoHighFindingTest`, `SplitPicksCheapestModelOfSameProviderTest` | ⬜ |
| R4 | `ExamplesAreCleanOfLp10Test`, example suites, `InspectSimExpectedTracesTest` | ⬜ |
| R5 | `LoomGuideBlocksParseTest`, `TutorialExamplesTest`, `GuideTutorialPageTest` | ⬜ |

## 3. Quality gates

- [ ] `mvn verify` green on `ai-agent4j-loom`, `loom-inspect`, `loom-fabric` (after installing the Loom jar) and every example module built with `-Pextended` that was migrated (`make test-extended` for those).
- [ ] PIT: the Loom `mutation` profile with the new facts; score not lower.
- [ ] `weave rules test core` green; `weave rules docs core` regenerated.
- [ ] `RepositoryScriptsTest` green.
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

`LOOM_GUIDE.md` ⬜ · `LOOM_PROMPT.md` ⬜ · `docs/guide/06` ⬜ · tutorial ch. 5 ⬜, 14 ⬜, README ⬜, HTML regenerated ⬜ · `docs/review/rules/` ⬜ · skills (workflow guide ⬜, harness design ⬜, eval design ⬜) · each migrated example's README ⬜ · loom-inspect sim docs ⬜ · release notes ⬜ · `API_COMPATIBILITY.md` (rule packs) ⬜ · `llms.txt` ⬜.

## 6. Exit checklist

- [ ] §0 recorded with dates and build ids
- [ ] Every row in §2 ✅, or partial with a reason; every A-criterion has evidence
- [ ] The list of migrated and not-migrated agents recorded in §7, with the reason for each one left alone
- [ ] M1 to M3 done and recorded
- [ ] DESIGN §7 questions answered
- [ ] Docs in §5 updated; roadmap status updated

## 7. Results

*(empty until 25a)*
