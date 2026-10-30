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

### 21b: R3 and R6 (done in code; M3 needs VS Code)

- Engine: `JourneyStages` (the ten stages, each done / failing / not-started / skipped / stale, from files and recorded results; a failing stage is the primary step before any not-started one), `weave next --format json` gains `stages`, `primary`, `projectRoot`, `realRun`; `loom/next` answers it uncached; `weave eval` records its last finished run in `.loom/eval.json` (`EvalRecord`) so a mock run does not count and a run for an older script or dataset is stale. Dataset reading sits in `loom/eval/DatasetFacts` because `ModuleBoundaryTest` allows eval4j only there (my first version broke that guard; I moved the code rather than widen it). Tests: `JourneyStagesTest` (11), `ServeNextTest` (3), two `HeldOutGuardTest` cases. Guard: treating a mock evaluation as real failed `JourneyStagesTest`.
- Extension: `outlineModel` + `progressModel` (rows, icons plus words for each state, the primary marked, a click types the command and never presses Enter, a 3 s throttle that keeps the last answer and its age), the provider asks `loom/next` and says progress needs the engine when there is none; **Loom: Run Once With a Cap…** (`runOnceWithCap`: cap first with the review's worst-case estimate or "no estimate yet", then inputs, validated because they reach a shell, typed not run). Tests: `outlineProgress.test.ts` (8), `runOnceWithCap.test.ts` (9), one provider integration test. Two older outline tests changed on purpose (a progress row is now first).
- Loom module `mvn verify`: 3,700 tests, 0 failed, 2 skipped (same two as before), coverage gates met. Extension suite: 731 tests, 0 failed, 0 skipped.
- Not done: the stage-5 "review" half (only the audit is read, `weave review` is not run for it); stage 6 is "test files exist", not "tests pass"; the status-bar item (DESIGN §6.4) and the refresh on `eval/`, `.loom/` and terminal-command-ended events (the Outline refreshes on saves, file events and editor changes, and on its next ask after 3 s); the "Mark this run as read" offer is the stage-8 row, not a separate prompt when the run ends.

### 21c and 21d: R4 and R5 (done in code; M2 and M5 need VS Code and Fabric)

- Model: a `parallel` node gains `attrs.lanes` and its branches become nodes laid out after the main path, so no existing step is renumbered (`ParallelLanesTest`, 9); a `set` node gains `attrs.value` (`SetValueAttrTest`, 4); the schema allows both; Mermaid draws `subgraph` lanes; goldens regenerated and reviewed (`parallel_lanes` added). Four older tests that pinned the old one-node shape were changed on purpose (`GraphBuilderTest` x2, `GraphAttrsTest`, `GraphDocTest` through `LOOM_GUIDE.md`).
- Knock-on effects found by the full verify, not by my first targeted runs, and fixed: LC08 suggested parallelising steps already inside a `parallel` block; step coverage and `TestSynth` treated lane steps as never reached (a run does not name them), which made `TestSynth` keep probing and record ordered routes through a parallel block that depend on thread timing (boardroom failed 5 of 6 runs; 0 of 14 at `HEAD`); `eval4j-report`'s `WorkflowGraphParityTest` lists five workflows whose shape moved on purpose. A hidden compile error (pattern `switch` is not Java 17) was masked for two runs by my own output filter.
- Editor: Flow draws a fork bar, one lane per branch with its name, the failure handler under its step on a dashed edge, and a join into the main line; consecutive sets are one card ("sets 3 variables") that opens to the list; `loom.graph.groupSets`. Looked at screenshots of the real golden output (`content_factory`, `parallel_lanes`) in Flow and Outline. Tests: `parallelLanes.unit` (9), `setGroups.unit` (8), 7 new browser tests including no overlap in light, dark and high contrast at 100% and 200% (the 200% is `document.body.style.zoom`, not a real browser zoom), a controller test for the setting.
- Shared renderer (Fabric, eval report): the layered layout already draws lanes as fan-out and fan-in; added grouping of runs of sets (`data-members`), tests in `lanes.test.js` (10); copies synced and checked. **The graph-render tests were already failing on a clean checkout of `HEAD` (19 of 73) and no CI job runs them**; I repaired the stale table and they pass (91).
- Verification: Loom module 3,719 tests, 0 failed, 2 skipped (same two), gates met; `eval4j-report` 68, 0 failed; extension 757, 0 failed, 0 skipped; graph-render 91.
- Not done: Fabric's live page was not looked at (M5); the Outline tree view in the editor was not retaken as a screenshot for the README (M6); nested parallel blocks are stacked in the outer lane, not drawn as nested lanes (DESIGN §6.3); a block with one branch is drawn as a lane, not a "parallel (1)" step; `weave graph` text and `weave explain` do not list lanes; the `.vsix` was not built or installed.

### 21 leftovers (done in code; M-checks stay with the owner)

- **Status-bar item and refresh on evaluation files (R3):** `progressStatus` (text such as `Loom 4/10 · The script checks clean`, a tooltip, and the next step's command as the click) and `isProgressFile` (`eval/`, `EVAL_DESIGN.md`, `*.test.yaml`, `.loom`) are pure and tested in `outlineProgress.test.ts`; the Outline provider watches those files and drives the item. Not done: refresh on terminal-command-ended events; the "Mark as read" prompt has no spec in this folder, so it was not built.
- **Nested `parallel` blocks (R5):** the editor already drew a lane inside a lane (the innermost lane wins); `nestedLanes.unit.test.js` and a fixture pin it. Mermaid declared the inner nodes twice; it now nests the inner block as a `subgraph` (`NestedLanesMermaidTest`).
- **`weave graph` text lanes:** `weave graph` has two formats, JSON (lanes in `attrs.lanes`) and Mermaid (subgraphs). There is no text format, so nothing to add; the decision is in the release notes.
- Extension suite: 769 tests, 0 failed, 0 skipped.
