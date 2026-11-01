# Phase 27 verification plan: approval at the boundary

Status: **not started** (spec draft). Run §0 first, then top to bottom at the end of each sub-phase. Proposed sub-phases: **27a** `use` (R1); **27b** `approve` on `use` and `run` (R2); **27c** the review changes and the lens default (R3 to R7); **27d** starter 07, the guide, the skills and the tutorial (R8). Results in §7.
Evidence: E14 to E18 of the [program page](../DISCIPLINE.md). Strategy: [TEST_STRATEGY.md](TEST_STRATEGY.md).

## 0. Reproduce first

| # | Evidence | Procedure | If it is real, you see | Result |
|---|---|---|---|---|
| P1 | E14 | `weave review --explain LA02`. | the only advice is the agent's `approve:` list | ⬜ |
| P2 | E15 | An agent with `approve: [Send]`, `max_iterations: 20`, in a loop of 2; `weave review --lens +oversight`. | no LH03, although up to 40 approvals are possible | ⬜ |
| P3 | E16 | `weave review` with no lens option on any script with a `human_prompt` compared to `"yes"`. | no LH04 (the lens is off) | ⬜ |
| P4 | E17 | Try `use Notify(text = "x")` and `call Notify(...)` in a script. | neither calls a tool; starter 07 asks twice | ⬜ |
| P5 | E18 | Search the language for a script-level approval on `run`. | only `requiresApproval` in Java | ⬜ |
| P6 | R6 | Inventory how a project marks a workflow as scheduled, triggered or run by the daemon. | the sources that settle open question 2 | ⬜ |

## 1. Acceptance criteria

| # | Criterion | Evidence |
|---|---|---|
| A1 | `use Tool(...)` calls a tool with no model, through exactly the same controls, journal and effect protocol as an agent's call | `UseToolCallParityTest`, `UseNetPolicyRefusalsTest`, `UseEffectProtocolNoRepeatAfterCrashTest` |
| A2 | `approve` on `use` and `run` asks once, shows the exact arguments with secrets named and PII masked, never runs on a no or a timeout, and never asks twice after a resume | R2 tests |
| A3 | LA02 offers the boundary first; LH02 covers steps; LH03 counts every question and names its sources | R3 to R5 fixtures |
| A4 | LH05 fires only for an approval mid-loop on an outward or changing tool in an unattended workflow | LH05 fixtures |
| A5 | The Human oversight lens is on by default and can be turned off | `OversightLensOnByDefaultTest`, `LensesMinusOversightRestoresTest` |
| A6 | Starter 07 asks once, at the edge, with no model carrying out the send; its tests and diagram pass | `StartersTest` |
| A7 | The guide, skills and tutorial teach the three shapes and keep `approve:` for interactive assistants; earned autonomy is presented as optional | §5 ticked, `TutorialExamplesTest`, `GuideTutorialPageTest`, `verify-skill-package.sh` |
| A8 | Every existing script parses and runs as before | `RepositoryScriptsTest`, existing tests unmodified |
| A9 | Every guard is noticed when removed | PIT score not lower; by-hand removals in §7 |

## 2. Traceability: requirement → tests

| Req | Tests | Status |
|---|---|---|
| R1 | `UseParsesTest`, `UseKeywordIsContextualTest`, `UseArgumentsCheckedAgainstToolParametersTest`, `UseToolCallParityTest`, `UseNetPolicyRefusalsTest`, `UseEffectProtocolNoRepeatAfterCrashTest`, `UseRetryRefusedOnNonIdempotentChangingToolTest`, `UseScriptedInWeaveTestTest`, `UseMockedInEvalMockTest`, `UseGraphNodeTest`, `GraphRenderKindsInSyncTest`, `UsesToolAssertionTest`, `UseIsASinkTest` (with phase 26) | ⬜ |
| R2 | the R2 tests of the strategy | ⬜ |
| R3 | LA02 rule-page golden text and fixture | ⬜ |
| R4 | LH03 fixtures | ⬜ |
| R5 | LH02 fixtures | ⬜ |
| R6 | LH05 fixtures | ⬜ |
| R7 | `OversightLensOnByDefaultTest`, `LensesMinusOversightRestoresTest` | ⬜ |
| R8 | `StartersTest`, `LoomGuideBlocksParseTest`, `TutorialExamplesTest`, `GuideTutorialPageTest`, `verify-skill-package.sh`, CTK `use_tool`, `approve_step` | ⬜ |

## 3. Quality gates

- [ ] `mvn verify` green on `ai-agent4j-loom`, `ai-agent4j-tools` (its `NetPolicy` and `Redactor` gates unchanged), `eval4j`, `loom-inspect*` and `loom-fabric` (after installing the Loom jar).
- [ ] PIT: the Loom `mutation` profile with `UseRunner`, approval on a step and LH03's counting; score not lower.
- [ ] `RepositoryScriptsTest` green; CTK scenarios added.
- [ ] Loom syntax everywhere it belongs (guide, prompt, `docs/guide/06`, tutorial, starters, workflow-guide skill, VS Code grammar and snippets, CTK).
- [ ] `bash scripts/sync-graph-render.sh` and `bash scripts/check-graph-render-sync.sh` (`use` kind).
- [ ] `bash scripts/make-starter-diagrams.sh`; `StartersTest` green.
- [ ] `bash scripts/make-tutorial-html.sh`; `GuideTutorialPageTest` green.
- [ ] `weave rules docs core`; `weave rules test core` green.
- [ ] `SecurityDocTest` green; `secure-by-construction.md` updated.
- [ ] `bash scripts/verify-skill-package.sh` green.
- [ ] `make format-check`, `make release-check` green. No new dependency.

## 4. Manual checks

| # | Check | Pass |
|---|---|---|
| M1 | Starter 07 against a real webhook receiver you control (no key in the repository): one question on the console, then on Telegram; the message posted is exactly what was approved | ⬜ |
| M2 | Kill the run after the yes and before the post; resume; the post happens once | ⬜ |
| M3 | `weave review` on the owner's own scheduled workflow with the new defaults: read every new LH finding and decide whether each is right | ⬜ |
| M4 | Read tutorial chapters 6 and 8 as a newcomer | ⬜ |

## 5. Documentation owed

`LOOM_GUIDE.md` ⬜ · `LOOM_PROMPT.md` ⬜ · `docs/guide/06` ⬜, `09-go-live` ⬜, `13-earned-autonomy` ⬜ · tutorial ch. 6 ⬜, 8 ⬜, 9 ⬜, 11 ⬜, 14 ⬜, README ⬜, HTML regenerated ⬜ · starter 07 and starters README ⬜ · `docs/review/rules/` ⬜ · phase 9 DESIGN R33 note ⬜ · `secure-by-construction.md` ⬜ · skills (workflow guide ⬜, harness design ⬜, deployer ⬜) · VS Code grammar, snippets, CHANGELOG ⬜ · graph-render ⬜ · eval4j README ⬜ · `API_COMPATIBILITY.md` ⬜ · `llms.txt` ⬜ · release notes ⬜.

## 6. Exit checklist

- [ ] §0 recorded with dates and build ids
- [ ] Every row in §2 ✅, or partial with a reason; every A-criterion has evidence
- [ ] M1 to M4 done and recorded in §7
- [ ] DESIGN §7 questions answered
- [ ] Docs in §5 updated; roadmap status updated

## 7. Results

*(empty until 27a)*
