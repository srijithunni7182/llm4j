# Phase 20 verification plan: the guided path and the skills

Status: **not started** (specs in draft). Run §0 first, then top to bottom at the end of each sub-phase (proposed: **20a** `weave next` stages and the schema fields, R2, R3.1; **20b** `weave preflight`, R1; **20c** the skills' rules and conventions, R3.2 to R3.9, §3.4; **20d** the dry run and the editor hand-over). Results in §7.
Findings: F28 to F36 of the [register](../FIELD_FINDINGS.md). Strategy: [TEST_STRATEGY.md](TEST_STRATEGY.md).

## 0. Reproduce first

Several of these findings are about assistant behaviour and cannot be reproduced by a unit test; they are reproduced by **reading the transcripts and reports the owner has** and by running the tools as they are today.

| # | Finding | Procedure | If the finding is real, you see | Result |
|---|---|---|---|---|
| P1 | F28 | Run `weave next` on the owner's `article-flow` project and on a fresh `weave init pipeline`; compare with the skill's list of stages. | two different lists; no evaluation-design or preflight stage; no read of recorded results | ⬜ |
| P2 | F29 | Read the field report §3.6 and the project's `eval/golden` files; count cases by who chose them. | cases the person did not choose | ⬜ |
| P3 | F30, F31 | Run the field session's four findings (F01, F03, F04, F09, plus the model 404) against the current build with the fake provider of Phase 17; for each, ask: would a single live call have shown it? | each shows up in one capped call per agent | ⬜ |
| P4 | F32, F33 | Read the four skills' key and selection text. | `.env` offered alone or first; no selection warning; no deny rules | ⬜ |
| P5 | F34 to F36 | Search the four skills for `weave explain`, `--gate-only`, "planner", "README" and "run each command". | the steps are absent or not enforced | ⬜ |
| P6 | F28 | Count `weave` command closing hints that mention the next stage and compare with the stage list. | hints that disagree with the skill | ⬜ |

## 1. Acceptance criteria

| # | Criterion | Evidence |
|---|---|---|
| A1 | `weave preflight` finds, with one capped call per agent, an unavailable model, a clipped answer, a shared prompt that did not reach the model (and says whether the request or the model is at fault), a grounded agent with no sources, an unknown price or window, and a budget that will bite first | `PreflightModelAvailabilityTest`, `PreflightWholeAnswerTest`, `PreflightCanaryTest`, `PreflightGroundedSourcesTest`, `PreflightPriceAndWindowTest`, `PreflightBudgetIsRealTest`, P3 |
| A2 | The preflight never says "pass" for a check it could not run, never writes the canary or an answer to a file, asks before spending, and stays within its cap | `PreflightSkippedIsNeverPassTest`, `PreflightDoesNotStoreAnswersTest`, `PreflightAsksBeforeSpendingTest`, `PreflightCostIsBoundedByTheCapTest` |
| A3 | `weave next` computes ten stages from files and recorded results, marks stale and skipped stages, and agrees with every command's closing hint | `NextStageStatesTest`, `NextStalenessTest`, `NextSkippedWithReasonTest`, `NextAgreesWithHintsTest`, P1, P6 |
| A4 | `weave next --format json` is stable and documented, and the skills' static roadmap equals it | `NextJsonShapeTest`, `StaticRoadmapMatchesNextTest` |
| A5 | A dataset shows who authored and who reviewed each case; unreviewed drafts are counted in `--check` and the report | `DatasetAuthorFieldsTest`, `EvalCheckReportsUnreviewedShareTest`, `ReadThisFirstShowsUnreviewedShareTest` |
| A6 | The skills carry the rules R3.2 to R3.9 as short imperatives, a shared conventions file and one checklist format; a rule cannot be removed unnoticed | `SkillRulesPresentTest`, `ConventionsIdenticalAcrossSkillsTest`, `ChecklistFormatTest`, the rule-removal checks |
| A7 | The first key question is "run it yourself or deploy it?", both routes are offered, and nothing tells a person to put a key in a committed file | `KeysQuestionFirstTest`, `NoKeyInExampleFileInstructionTest`, P4 |
| A8 | The selection warning is given once, the deny-rule snippet is correct, and it is written only with consent (skills, `init` and the extension) | `SelectionWarningPresentTest`, `DenyRulesSnippetTest`, `DenyRulesWrittenOnlyOnConsentTest` |
| A9 | A person can confirm a real run was read, and `weave next` records it | `RealRunConfirmationRecordedTest` |
| A10 | A generated README marks each command tested or untested | `ReadmeCommandsMarkedTestedTest`, `GenerateGateOnlyMentionedTest` |
| A11 | The four skills' descriptions send each request to one best skill and hand over to each other | `SkillDescriptionsDoNotOverlapTest`, `HandOversPresentTest` |
| A12 | **A fresh coding agent following the skills keeps the process the field session broke** (F28 to F36) | the dry run (TEST_STRATEGY §5), recorded in §7, M1 |
| A13 | Existing projects are not blocked or nagged; installed skills upgrade in place and keep what the person wrote | `NextSuggestsOnceNotBlocksTest`, `weave guide --install-skill` tests unmodified |
| A14 | Each guard is noticed when removed | PIT score not lower; by-hand removals (the canary check, the consent check, the "skipped is never pass" rule) named in §7 |

## 2. Traceability: requirement → tests

| Req | Tests | Status |
|---|---|---|
| R1.1 C1 to C7 | `PreflightKeyResolvesTest`, `PreflightModelAvailabilityTest`, `PreflightWholeAnswerTest`, `PreflightCanaryTest`, `PreflightGroundedSourcesTest`, `PreflightPriceAndWindowTest`, `PreflightBudgetIsRealTest` | ⬜ |
| R1.2 | `PreflightOutputAndRecordTest`, `PreflightMockModeTest` | ⬜ |
| R1.3 | `PreflightAsksBeforeSpendingTest`, `PreflightListsWhatItWillSendTest`, `PreflightSkippedIsNeverPassTest`, `PreflightDoesNotStoreAnswersTest` | ⬜ |
| R1.4 | `PreflightCostIsBoundedByTheCapTest`, `PreflightPrintsWhatItCostTest` | ⬜ |
| R2.1 | `NextStageStatesTest`, `NextStalenessTest`, `NextSkippedWithReasonTest`, `NextSuggestsOnceNotBlocksTest` | ⬜ |
| R2.2, R2.3 | `NextJsonShapeTest`, `NextAgreesWithHintsTest`, `StaticRoadmapMatchesNextTest` | ⬜ |
| R3.1 | `DatasetAuthorFieldsTest`, `EvalCheckReportsUnreviewedShareTest`, `ReadThisFirstShowsUnreviewedShareTest`, `SkillAsksFivePerAgentTest` | ⬜ |
| R3.2 to R3.5 | `SkillRulesPresentTest`, `RealRunConfirmationRecordedTest`, `ReadmeCommandsMarkedTestedTest`, `GenerateGateOnlyMentionedTest` | ⬜ |
| R3.6 | `KeysQuestionFirstTest`, `NoKeyInExampleFileInstructionTest` | ⬜ |
| R3.7 | `SelectionWarningPresentTest`, `DenyRulesSnippetTest`, `DenyRulesWrittenOnlyOnConsentTest` | ⬜ |
| R3.8, R3.9 | `ConventionsIdenticalAcrossSkillsTest`, `ChecklistFormatTest`, `SkippedStepRecordedTest` | ⬜ |
| §3.4 | `SkillDescriptionsDoNotOverlapTest`, `HandOversPresentTest` | ⬜ |

## 3. Quality gates

- [ ] `mvn verify` green on `ai-agent4j-loom` (and `eval4j` for the dataset fields), coverage gates held, none lowered.
- [ ] PIT: the staleness rules and the preflight decision logic added to the `mutation` profile; score not lower.
- [ ] `make format-check`, `make release-check`, `make skill-package` green.
- [ ] The preflight's hermetic tests open no socket except loopback.
- [ ] `SecurityDocTest` green; `docs/security/` describes the preflight's sending and what it stores; the repository secret scan finds nothing in the skills.
- [ ] No new dependency.

## 4. Manual checks (need the owner, a fresh coding-agent session, and a small spend cap)

| # | Check | Pass |
|---|---|---|
| M1 | **The dry run**: a fresh session on a fresh project, only the skills installed, the owner playing the developer, against the checklist of TEST_STRATEGY §5. Transcript kept | ⬜ |
| M2 | **Live preflight** on the owner's `article-flow` with the owner's key and a 0.05 cap: it finds (or confirms absent, after Phase 17's fixes) each of F01, F03, F04, F09 and the model 404; the table reads as the owner would want; the cost is as stated | ⬜ |
| M3 | A newcomer (not the owner) follows only `weave next` from an empty folder to a read real run, without the skills; note where `next` is unclear | ⬜ |
| M4 | The deny-rule offer in the extension and in `init`: the lines are shown first; nothing is written on *Not now* | ⬜ |
| M5 | An old project (no `EVAL_DESIGN.md`, no preflight) is suggested the new stages once and not blocked | ⬜ |

## 5. Documentation owed

The four skills (and their `conventions.md`); `docs/guide/` README and chapters 6, 7, 9, 11; the generated pages for `weave next`, `preflight`, `init`, `guide`; the Loom guide; the extension README; `docs/security/`; `docs/specs/16-first-run` (the spine's stages); `llms.txt`; release notes. Stack-level pages once at the end of the program.

## 6. Exit checklist

- [ ] §0 recorded with dates and build ids
- [ ] Every row in §2 ✅ or partial with a reason; every A-criterion has evidence
- [ ] M1 to M5 done and recorded in §7; the dry run's transcript and outcome are kept
- [ ] DESIGN §6 questions answered; decision D7 recorded in the program page
- [ ] Docs in §5 updated; roadmap status updated

## 7. Results

### 20a: `weave preflight` and `weave next` (done)

- Tests: `PreflightRunnerTest` (11), `PreflightCommandTest` (6), `NextKnowsThePreflightTest` (7), `NextCommandTest` (8, fresh starter now 7 steps).
- Guard removed once by hand: C4 (`answer.stripLeading().startsWith(canary)` replaced by `true`) made `aModelThatIgnoresTheCanaryFailsTheSharedPromptCheck` fail; restored.
- Not run: the live preflight against a real provider (manual check, needs the owner's key and cap); PIT.
- Still open in Phase 20: the skill rules R3.x and the shared `conventions.md` with its drift test.
