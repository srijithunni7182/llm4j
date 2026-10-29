# Phase 21 test strategy: editor polish

How each requirement of [DESIGN.md](DESIGN.md) is tested. The extension already has a layered test setup that this phase uses as it is: pure TypeScript modules tested with `node --test` (the VS Code API faked behind small interfaces), browser tests of the webviews (`*.browser.test.js`), the graph renderer's own tests (`src/loom/graph-render/test`), and Java tests for the graph model. The rules of [TESTING_STRATEGY.md](../../TESTING_STRATEGY.md) apply.

## 1. Principle: test the state the user saw, not the code that produced it

All six findings are about **what a person sees in a given state**: a blank view, a jump, a wall of boxes. So the tests build the **state** (a folder with `.loom` files and no active editor; a script with six consecutive `set`s; a parallel with three branches, one with an `on_failure`) and assert on the **result a person would read**: the nodes the tree returns, the messages the panel posts, the shapes and text the renderer draws. Where the field finding came from a screenshot, the screenshot's situation is a fixture.

## 2. Test levels

| Level | What it covers |
|---|---|
| **Unit (TypeScript)** | The Outline's state machine (R2: which of four states, from a plain description of the workspace), the progress mapping (R3: `weave next` JSON to nodes, icons, descriptions, the primary step), the refresh debounce (R3, with an injected clock), the cap dialog's logic (R6) |
| **Extension wiring** | The `TreeDataProvider` with the VS Code API faked: the nodes returned in each state, the `viewsWelcome` contexts in `package.json`, the commands registered, the setting keys and defaults |
| **Webview (browser)** | The Review panel: a click posts a `select` message and **never** a `reveal` message; the inline button, the context menu and Ctrl/Cmd+Enter post `goto`; the setting flips the behaviour. The graph panel: set groups expand and collapse; lanes draw; selection and keyboard work; zoom 200% and the three themes |
| **Renderer** | `graph-render` tests with graph JSON fixtures: grouping of consecutive `set`s (including that members keep their ids), lanes with fork and join bars, dashed failure edges from the right step, nested parallel and the depth limit, a one-branch parallel; layout stays non-overlapping (the existing overlap check) |
| **Java (graph model)** | `GraphBuilder`/`NodeDescriber`: a parallel node has lanes with the body's nodes and their `on_failure` handlers; ids and lines are stable; the old attributes (`agents`, `branches`) are unchanged; `weave graph --format json` and `mermaid` output matches golden files |
| **Protocol** | `loom/next` returns the JSON of `weave next --format json`; the engine client handles it; a protocol-version mismatch degrades with the existing message |
| **Sync** | `scripts/check-graph-render-sync.sh` stays green (the extension's copy and Fabric's copy are the same) |
| **Screenshots** | The scripted screenshots for the README and developer guide are regenerated after the change and diffed by a human (not a pixel test) |

## 3. Fixtures

- **`WorkspaceStates`**: a description of the workspace for R2: no folder; a folder with no `.loom` file; `.loom` files and no active editor (the screenshot); a Loom file active; a Loom file active with a syntax error; the engine down; no Java.
- **`NextJson`**: `weave next --format json` samples: a new project, a mid-journey project (dataset reviewed 60%, preflight stale), a finished project, a project with a skipped stage with a reason.
- **`GraphFixtures`**: the screenshot's graph (the owner's `article-flow` Main: six consecutive `set`s, a parallel of three agents, `on_failure` handlers on the editor and proofreader), plus small ones for each renderer rule; generated from real scripts by `weave graph --format json` so they cannot drift from the model.
- **`ReviewPanelFixture`**: findings with step links and lines.

## 4. Per requirement

| Requirement | Tests (written first) |
|---|---|
| **R1** | `reviewClickSelectsOnly.browser.test.js` (no `reveal`), `reviewGotoIsDeliberate.browser.test.js` (button, context menu, Ctrl/Cmd+Enter), `openSourceOnClickSetting.test.ts`, `graphClickSelectsOnly.browser.test.js`; the existing review-panel two-way-link tests unchanged |
| **R2** | `outlineStates.test.ts` (each of the four states and the error node; **the screenshot state returns the project view, never an empty list**), `outlineWelcomeContexts.test.ts` (the `when` clauses cover every state exactly once; no state shows nothing), `outlineRefreshOnFileEvents.test.ts` |
| **R3** | `outlineProgressNodes.test.ts` (each stage state's icon and description; the primary step marked; click commands), `outlineProgressRefresh.test.ts` (debounce, last-known with age, no more than one refresh per three seconds), `outlineProgressDegraded.test.ts`; Java `EngineNextMethodTest` (`loom/next` equals the CLI JSON) |
| **R4** | `graph-render` tests `groupsConsecutiveSets`, `setGroupKeepsMemberIds`, `setGroupExpandsOnClick`, `singleSetIsCompact`; `groupSetsSetting.test.ts`; `breakpointOnAGroupedSetTest` (a breakpoint on a member line still binds); `reviewStepLinkToAGroupedSetTest` |
| **R5** | Java `ParallelNodeHasLanesTest`, `LaneNodesCarryOnFailureTest`, `ParallelKeepsOldAttributesTest`, `GraphJsonGoldenTest`, `MermaidLanesGoldenTest`; renderer `drawsForkLanesJoin`, `drawsFailureAsDashedSideEdge`, `oneBranchParallelIsAStep`, `nestedParallelDepthLimit`, `lanesDoNotOverlapAt200PercentZoom`; Fabric page test uses the shared renderer |
| **R6** | `runOnceWithCapAsksCapFirst.test.ts`, `runOnceTypesTheCommandWithTheCap.test.ts`, `runOnceNeverRunsWithoutACap.test.ts`, `markRunAsReadRecordsConfirmation.test.ts` |
| **Accessibility** | `outlineAndPanelHaveAccessibleNames.test.ts`, `highContrastAndZoom.browser.test.js` for the new nodes and lanes |

## 5. Fault injection

The engine returns an error for `loom/next`; returns JSON with an unknown stage id (shown as an unknown stage, not dropped); the workspace has 500 `.loom` files (the 200 limit and "show more"); a `.loom` file is deleted while the view is open; the graph JSON from an older engine has no `lanes` (the renderer draws the old single node); a very long `set` value (truncated); a parallel body with 40 branches (the layout scrolls); two refreshes arrive at once.

## 6. Mutation-style checks

The R2 state selection and the R1 "never reveal on click" rule are the guards. Each is removed once by hand and the failing test named in `VERIFICATION.md` §7; the grouping rule's boundary (two `set`s with a step between are two groups) has a test that fails if the boundary moves.

## 7. Deliberately not tested

- Pixel-exact appearance of the graph or the tree (checked by people, with the regenerated screenshots).
- VS Code's own tree-view rendering, welcome-view rendering and terminal behaviour beyond the contexts and commands this extension supplies.
- Performance beyond the stated limits (200 files, 40 branches); the existing latency tests stand.
