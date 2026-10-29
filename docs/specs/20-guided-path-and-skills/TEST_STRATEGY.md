# Phase 20 test strategy: the guided path and the skills

How each requirement of [DESIGN.md](DESIGN.md) is tested, under the rules of [TESTING_STRATEGY.md](../../TESTING_STRATEGY.md). The phase has two kinds of subject: **code** (`weave preflight`, `weave next`, dataset fields) that is tested like any command, and **process text** (the skills' rules) that is tested by what a machine can check plus a recorded dry run with a person (the same device as
[Phase 23 §5](../23-eval-design-skill/TEST_STRATEGY.md)).

## 1. Principle: a preflight that cannot lie

A check that says "pass" when it did not check is worse than none: it is the F01 problem (a rule meant to apply, quietly not applied) in a new place. So the preflight's tests are mostly about its **honesty**: a check it could not run is `skipped` with a reason; a canary the model ignored is reported as ignored, not as "reaches the model"; a cap that stopped it is exit 3, not a pass. Each check is tested **against a fake provider that is wrong in exactly the way the check is meant to catch**.

## 2. Test levels

| Level | What it covers |
|---|---|
| **Unit** | Stage state computation for `weave next` (R2) from a table of project shapes; the staleness rules (script hash, dataset version); the dataset field parsing (R3.1); the preflight check logic with injected clients |
| **Command** | `weave preflight` and `weave next` through `WeaveCLI.execute(...)` against a scripted/recording provider (the `RecordingModelServer` of [Phase 17](../17-honest-results/TEST_STRATEGY.md)) and a temporary project |
| **Fake provider** | A provider that: drops the system prompt (to prove C4 reports "ignored or not carried"), answers with a finish reason `length` (C3), returns no sources for a grounded call (C5), refuses a model name (C2), returns no usage (cost estimated), or exceeds the cap (exit 3) |
| **Docs and skills** | Structure tests of the four skills: the roadmap, the checklist format and the rules are present; the static roadmap copy equals `weave next`'s stage list (drift guard); the shared `conventions.md` is identical across the four skills; descriptions do not overlap badly |
| **Transcript protocol** | A recorded dry run with a person for the process rules ([§5](#5-the-part-a-machine-cannot-check-a-protocol-with-a-person)) |
| **Live** (opt-in, capped) | One real preflight against one provider with the owner's key |

## 3. Fixtures

- **`ProjectShapes`**: temporary projects in each state of the roadmap (only a script; script plus `EVAL_DESIGN.md`; dataset with unreviewed cases; checked script; passed tests; stale preflight after a script edit; an eval bundle for an old dataset version; deployed).
- **`WrongProvider`**: the fake provider above, configurable per check.
- **`DraftedDataset`**: a dataset with some cases `author: assistant, reviewed: false` and some reviewed by a person.
- **`ReadmeProject`**: a generated project whose README lists four commands, one of which cannot run offline, for R3.5.
- **`SkillSet`**: the four real skill folders.

## 4. Per requirement

| Requirement | Tests (written first) |
|---|---|
| **R1.1 C1** | `PreflightKeyResolvesTest`: no key bound to the host is `fail` with the `weave secrets` fix; no call is made |
| **R1.1 C2** | `PreflightModelAvailabilityTest`: a refused model name is reported with the provider's alternatives when the list call exists, and with "could not list models" otherwise |
| **R1.1 C3** | `PreflightWholeAnswerTest`: a `length` finish reason is `warn` naming the effective limit; a whole answer is `pass`; the real prompts are what was sent (asserted on the recorded request) |
| **R1.1 C4** | `PreflightCanaryTest`: the provider that drops the system prompt gives "the request did not carry it"; the provider that carries it but answers without the canary gives "the model did not follow it"; the provider that obeys passes; the canary is random per run and absent from every file written; guard test: removing the canary check fails it |
| **R1.1 C5** | `PreflightGroundedSourcesTest`: sources present, absent with searches 0, absent with searches above 0 |
| **R1.1 C6, C7** | `PreflightPriceAndWindowTest`, `PreflightBudgetIsRealTest` (the worst case against the cap) |
| **R1.2** | `PreflightOutputAndRecordTest` (table, JSON, exit codes 0, 1 and 3, `preflight.json` content, no key or prompt or answer beyond the excerpt), `PreflightMockModeTest` (no socket) |
| **R1.3** | `PreflightAsksBeforeSpendingTest` (`--yes` skips; without it and without a terminal it refuses, with a clear message), `PreflightListsWhatItWillSendTest`, `PreflightSkippedIsNeverPassTest`, `PreflightDoesNotStoreAnswersTest` |
| **R1.4** | `PreflightCostIsBoundedByTheCapTest`, `PreflightPrintsWhatItCostTest` |
| **R2.1** | `NextStageStatesTest` (table over `ProjectShapes`), `NextStalenessTest` (editing the script makes the preflight and the real run stale; a new dataset version makes the evaluation stale), `NextSkippedWithReasonTest`, `NextSuggestsOnceNotBlocksTest` (an old project is not nagged) |
| **R2.2** | `NextJsonShapeTest` (stable keys, a documented schema, golden file) |
| **R2.3** | `NextAgreesWithHintsTest` (extends the Phase 16 test), `StaticRoadmapMatchesNextTest` (the skills' fallback list equals `weave next`'s stage ids and order) |
| **R3.1** | `DatasetAuthorFieldsTest` (parse, default to reviewed and `source: unknown`), `EvalCheckReportsUnreviewedShareTest`, `ReadThisFirstShowsUnreviewedShareTest` (with Phase 19), `SkillAsksFivePerAgentTest` (the questions are present in the dataset chapter and the skill refers to it) |
| **R3.2** to **R3.5** | `SkillRulesPresentTest` (each rule's sentence is present in the workflow skill and, where DESIGN says, in the others), `RealRunConfirmationRecordedTest` (`weave next --confirm real-run`), `ReadmeCommandsMarkedTestedTest`, `GenerateGateOnlyMentionedTest` |
| **R3.6** | `KeysQuestionFirstTest` (the first key-related sentence in the skills is the question), `NoKeyInExampleFileInstructionTest` (no skill or generated README tells a person to put a key in a committed file) |
| **R3.7** | `DenyRulesSnippetTest` (the snippet is valid JSON for the agent's settings and lists the named files), `DenyRulesWrittenOnlyOnConsentTest` (extension and `init`), `SelectionWarningPresentTest` |
| **R3.8** | `ConventionsIdenticalAcrossSkillsTest`, `ChecklistFormatTest` |
| **R3.9** | `SkippedStepRecordedTest` |
| **Hand-overs, descriptions** | `SkillDescriptionsDoNotOverlapTest` (a table of 20 representative requests each maps to exactly one best skill by trigger phrases), `HandOversPresentTest` |

## 5. The part a machine cannot check: a protocol with a person

The rules R3.1 to R3.9 are about behaviour. The recorded **dry run** repeats the field session's start on a fresh project with only the skills installed and a person playing the developer, on a script of the owner's choosing, with a fresh coding-agent session. It is judged against a checklist built from F28 to F36 (VERIFICATION §4 M1):
the roadmap shown first; the checklist after each stage; "is the planner a model?" asked; five questions per agent asked and no scenario invented; one real answer read before the dataset; `weave explain` read back; every README command run or marked untested; "run it yourself or deploy it?" asked first and both key routes offered; the editor-selection warning given.
The transcript and the checklist outcome are kept in `VERIFICATION.md` §7 and the dry run is repeated whenever the process text changes.

## 6. Mutation-style checks for prose

For each rule R3.1 to R3.9 a test removes its sentence from a copy of the skill and confirms the presence test fails, so a later edit cannot quietly drop a rule. For the preflight's checks, the usual PIT profile applies to the decision logic (C3 to C5, the staleness rules).

## 7. Deliberately not tested

- That a model **obeys** a canary in general (C4 reports what happened with this model; the hermetic tests prove only that the request carried the text and that the report says the right thing in each case).
- That a coding agent follows the skills every time (the dry run samples it).
- The quality of a real model's answer in the preflight (it checks plumbing).
