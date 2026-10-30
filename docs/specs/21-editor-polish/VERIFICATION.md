# Phase 21 verification plan: editor polish

Status: **not started** (specs in draft). Run §0 first, then top to bottom at the end of each sub-phase (proposed: **21a** the Review panel and the Outline's states, R1, R2; **21b** progress in the Outline and `loom/next`, R3, R6; **21c** `set` grouping, R4; **21d** the graph model and the parallel lanes, R5). Results in §7.
Findings: F37 to F42 of the [register](../FIELD_FINDINGS.md). Strategy: [TEST_STRATEGY.md](TEST_STRATEGY.md). Dependencies: R3 and R6 need [Phase 20](../20-guided-path-and-skills/DESIGN.md)'s `weave next --format json`.

## 0. Reproduce first

All six are visual or behavioural findings from the owner's VS Code. Reproduce each **by hand on the current published extension (1.1.0 or later)** and save a screenshot or a screen recording to the pull request.

| # | Finding | Procedure | If the finding is real, you see | Result |
|---|---|---|---|---|
| P1 | F37 | Open a project with a `.loom` file and run *Loom: Review Workflow*; click a finding in the panel. | the editor opens or moves to the `.loom` file | ⬜ |
| P2 | F38 | Open a folder that has `.loom` files; close all editors (or open a non-Loom file); open *Loom: Workflow Outline*. | an empty tree with no message (the owner's screenshot) | ✅ seen 2026-10-29 (owner) |
| P3 | F38 | Same folder with a `.loom` file active. | the tree shows the file's steps (control) | ⬜ |
| P4 | F39 | Look for any indication of project progress in the Outline. | none | ⬜ |
| P5 | F40 | Open the Flow view for a script with six consecutive `set`s (the owner's `article-flow`). | six note boxes in a row | ✅ seen 2026-10-29 (owner, screenshot) |
| P6 | F41 | Open the Flow view for a script with `in parallel` of three agents, each with an `on_failure`. | one "in parallel: 3 branches" box; the failure handlers hang under later steps, not under the branches | ✅ seen 2026-10-29 (owner, screenshot) |
| P7 | F41 | `weave graph article.loom --format json` for the same script; inspect the `parallel` node. | only `agents` and `branches`; the body statements and their `on_failure` handlers are not nodes | ⬜ (code read 2026-10-29: `NodeDescriber.parallel`) |
| P8 | F42 | Look for a single action that starts a capped real run. | none; *Run With Live Page…* asks differently | ⬜ |

## 1. Acceptance criteria

| # | Criterion | Evidence |
|---|---|---|
| A1 | Clicking a finding or a graph step selects it and never moves the editor; the source opens only by a deliberate action; the setting restores the old behaviour | `reviewClickSelectsOnly.browser.test.js`, `reviewGotoIsDeliberate.browser.test.js`, `graphClickSelectsOnly.browser.test.js`, `openSourceOnClickSetting.test.ts`, P1 |
| A2 | The Outline is never blank: each of the four workspace states shows something, and an error is a node with the fix | `outlineStates.test.ts`, `outlineWelcomeContexts.test.ts`, P2, M1 |
| A3 | The screenshot's state (a folder with workflows, no Loom editor active) shows the project's workflows and progress | `outlineStates.test.ts` (the fixture), M1 |
| A4 | The Outline's progress is `weave next`'s, with states, reasons, the primary step and click actions; it refreshes sensibly and degrades cleanly | `outlineProgressNodes.test.ts`, `outlineProgressRefresh.test.ts`, `outlineProgressDegraded.test.ts`, `EngineNextMethodTest`, P4 |
| A5 | Consecutive `set`s are one expandable node; each member keeps its id, line, breakpoint and review link; the setting turns it off | `groupsConsecutiveSets`, `setGroupKeepsMemberIds`, `breakpointOnAGroupedSetTest`, `reviewStepLinkToAGroupedSetTest`, `groupSetsSetting.test.ts`, P5 |
| A6 | The graph model gives a `parallel` node its lanes with the branches' steps and their `on_failure` handlers, additively | `ParallelNodeHasLanesTest`, `LaneNodesCarryOnFailureTest`, `ParallelKeepsOldAttributesTest`, `GraphJsonGoldenTest`, P7 |
| A7 | The renderer draws a fork, lanes, a join and dashed failure edges from the right step, without overlap at 200% zoom in all themes; Fabric shows the same | `drawsForkLanesJoin`, `drawsFailureAsDashedSideEdge`, `lanesDoNotOverlapAt200PercentZoom`, the Fabric page test, P6, M2 |
| A8 | An older engine's graph (no lanes) still draws | `oldGraphWithoutLanesStillDraws` |
| A9 | One action starts the first capped real run, cap first, and records that it was read | `runOnceWithCapAsksCapFirst.test.ts`, `runOnceNeverRunsWithoutACap.test.ts`, `markRunAsReadRecordsConfirmation.test.ts`, P8, M3 |
| A10 | Everything new is keyboard-reachable and has accessible names | `outlineAndPanelHaveAccessibleNames.test.ts`, `highContrastAndZoom.browser.test.js` |
| A11 | Existing behaviour is unchanged except as listed; every change is in the extension changelog | existing extension tests unmodified; the table in [DESIGN §4](DESIGN.md) |
| A12 | Each guard is noticed when removed | by-hand removals named in §7 |

## 2. Traceability: requirement → tests

| Req | Tests | Status |
|---|---|---|
| R1 | `reviewClickSelectsOnly.browser.test.js`, `reviewGotoIsDeliberate.browser.test.js`, `openSourceOnClickSetting.test.ts`, `graphClickSelectsOnly.browser.test.js` | ⬜ |
| R2 | `outlineStates.test.ts`, `outlineWelcomeContexts.test.ts`, `outlineRefreshOnFileEvents.test.ts` | ⬜ |
| R3 | `outlineProgressNodes.test.ts`, `outlineProgressRefresh.test.ts`, `outlineProgressDegraded.test.ts`, `EngineNextMethodTest` | ⬜ |
| R4 | `groupsConsecutiveSets`, `setGroupKeepsMemberIds`, `setGroupExpandsOnClick`, `singleSetIsCompact`, `groupSetsSetting.test.ts`, `breakpointOnAGroupedSetTest`, `reviewStepLinkToAGroupedSetTest` | ⬜ |
| R5 | `ParallelNodeHasLanesTest`, `LaneNodesCarryOnFailureTest`, `ParallelKeepsOldAttributesTest`, `GraphJsonGoldenTest`, `MermaidLanesGoldenTest`, `drawsForkLanesJoin`, `drawsFailureAsDashedSideEdge`, `oneBranchParallelIsAStep`, `nestedParallelDepthLimit`, `lanesDoNotOverlapAt200PercentZoom`, `oldGraphWithoutLanesStillDraws` | ⬜ |
| R6 | `runOnceWithCapAsksCapFirst.test.ts`, `runOnceTypesTheCommandWithTheCap.test.ts`, `runOnceNeverRunsWithoutACap.test.ts`, `markRunAsReadRecordsConfirmation.test.ts` | ⬜ |
| a11y | `outlineAndPanelHaveAccessibleNames.test.ts`, `highContrastAndZoom.browser.test.js` | ⬜ |

## 3. Quality gates

- [ ] `npm test` unit and webview subsets green (the VS Code-download integration tests run in CI or are listed as not run); `tsc` clean.
- [ ] `graph-render` tests green; `scripts/sync-graph-render.sh` run and `scripts/check-graph-render-sync.sh` green.
- [ ] `mvn verify` green on `ai-agent4j-loom` (graph model and engine method), coverage gates held, none lowered.
- [ ] The extension's `docs`, `manifest` and `welcomeView` tests green (new settings documented with defaults in the README).
- [ ] `make format-check` and `make release-check` green; the extension version is bumped with a changelog section (the publishing pipeline's check fails otherwise).
- [ ] No new dependency.

## 4. Manual checks (need the owner and VS Code; the extension's CI publishes only after these)

| # | Check | Pass |
|---|---|---|
| M1 | Install the built `.vsix`. Open the owner's `article-flow` folder with no editor open: the Outline shows progress and the workflows, not a blank; open a file: its steps appear; close the folder: the right message | ⬜ |
| M2 | The Flow view of `article-flow`: the six `set`s are one node that expands; the three parallel agents are lanes with a fork and a join; the editor's and the proofreader's failure paths hang from their steps; readable in dark, light and high contrast, and at 200% zoom | ⬜ |
| M3 | *Run Once With a Cap…*: the cap is the first question; the typed command carries it; after the run *Mark this run as read* records it and the stage turns done | ⬜ |
| M4 | Review panel: reading ten findings in a row does not move the editor once; *Go to source* does; the old behaviour returns with the setting | ⬜ |
| M5 | Fabric: the same graph renders with the new lanes on the live page | ⬜ |
| M6 | Retake the README and developer-guide screenshots; read each with fresh eyes against what the text says | ⬜ |

## 5. Documentation owed

The extension README and CHANGELOG; developer guide screenshots and journey; `docs/serve/PROTOCOL.md`; `docs/API_COMPATIBILITY.md`; the graph chapter of `LOOM_GUIDE.md`; the wiki's *Loom in VS Code* and Fabric pages; `weave man graph`; `llms.txt` if the protocol is listed. Stack-level pages once at the end of the program.

## 6. Exit checklist

- [ ] §0 recorded (P1 to P8) with screenshots in the pull request
- [ ] Every row in §2 ✅ or partial with a reason; every A-criterion has evidence
- [ ] M1 to M6 done and recorded in §7
- [ ] DESIGN §6 questions answered; decision D10 recorded in the program page
- [ ] Docs in §5 updated; the extension version bumped with its changelog; roadmap status updated

## 7. Results

### 21a: R1 and R2 (done in code; P1/P2 and M1/M4 need the owner's VS Code)

- R1: a click or Enter on a finding or a step only selects; **Go to source** and Ctrl/Cmd+Enter open the file; `loom.review.openSourceOnClick` restores the old behaviour. The host decides (`shouldOpenSource`), so the page needs no knowledge of the setting. The graph panel already never moved the editor (`media/panel.js`), so nothing changed there. Tests: `reviewPresentation.test.ts` (parse and decision), `review.browser.test.js` (click is not deliberate, Enter selects, Ctrl+Enter and the button are deliberate). Two existing browser tests were changed on purpose (click no longer asks to reveal; Enter no longer reveals).
- R2: `outlineModel.ts` (pure) and the rewritten `WorkflowOutlineProvider`: no folder, folder with no `.loom` (welcome shows), the blank case (folder view plus hint), a Loom file active (This file first, folder collapsed), a 200-file limit with a "N more not shown" row, an error row with the fix, a debounced refresh on file events. Tests: `outlineStates.test.ts` (9) and the integration test for the owner's screenshot state. The old `VG.3` outline test was changed on purpose to the new tree. Guard: disabling the active-file branch failed the tests.
- Whole extension suite: 712 tests, 0 failed, 0 skipped.
- Not done here: the symbols come from a line scan, not from the engine (DESIGN §6 question 1 proposed the engine when it is up); the folder view reads at most 1,000 files and says "N more" from what it found, so beyond 1,000 the count is a lower bound. No screenshot was taken, and M1 (install the `.vsix` and look) needs VS Code.

*(empty until 21a)*
