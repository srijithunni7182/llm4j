# Phase 21 design: editor polish (an Outline that is never blank, panels that do not jump, a graph that shows the shape)

Status: **draft for review**. Layer: the VS Code extension (`src/loom/vscode-loom`), the shared graph renderer (`src/loom/graph-render`, also used by the Fabric page), the Loom graph model (`ai-agent4j-loom`, `GraphBuilder`, `NodeDescriber`) and the engine protocol (`weave serve`). Part of the [field findings program](../FIELD_FINDINGS.md); read its §2 and decision D10 in §5 first.
Documents: [TEST_STRATEGY.md](TEST_STRATEGY.md), [VERIFICATION.md](VERIFICATION.md). Builds on [Phase 10](../10-editor-experience/DESIGN.md) (the review panel and the semantic layer), [Phase 16](../16-first-run/DESIGN.md) (the welcome view) and [Phase 20](../20-guided-path-and-skills/DESIGN.md) (`weave next --format json`).
Findings owned: **F37 to F42**. All six are **O** (observed by the owner in VS Code) or **I**; none was exercised by the reporter, so §0 of the verification plan reproduces each.

## 1. Problem

The editor is where a developer spends the session, and the field session found six rough edges there. Each is small. Together they decide whether the extension feels like a finished tool.

1. **Clicking a finding in the Review panel opens the `.loom` file** and moves the editor, which is distracting when you are reading a list of findings. (F37)
2. **The *Loom: Workflow Outline* view is blank** in the common case: a folder with workflows, no `.loom` file open. The welcome message shows only when the folder has no `.loom` file at all; the tree returns nothing unless the active editor is a Loom file. The view then shows nothing and says nothing. (F38; the owner's screenshot)
3. **The Outline could show how far along the project is**, the way `weave next` already knows. (F39)
4. **The graph draws every `set` as a box of its own**, so a line of six initialisations becomes six boxes that bury the steps that matter. (F40; the owner's screenshot)
5. **Parallel work is one grey "in parallel" box**, not lanes, and a branch's `on_failure` path is not drawn against its branch. The model emits one node with only the agent names and a branch count (`NodeDescriber.parallel`), so the renderer cannot draw what it is not given. (F41)
6. **The first real spend is not one click.** (F42, the reporter's wish)

## 2. Goals and non-goals

Goals:

- No view is ever blank without saying why and what to do (R2).
- Reading findings does not move the editor; going to the source is a deliberate act (R1).
- The Outline shows progress from the same source as the CLI and the skills (R3).
- The graph's shape is the shape of the workflow: sets are quiet, parallel is lanes, failure paths belong to their step (R4, R5).
- The first capped real run is one visible, cap-first action (R6).

Non-goals:

- Not a redesign of the graph's three views (Outline, Focus, Flow) or of the review panel's layouts.
- Not new graph kinds beyond what R5 needs; the renderer's other shapes are untouched.
- Not a new data source: progress comes from `weave next` ([Phase 20](../20-guided-path-and-skills/DESIGN.md)), not from the extension inspecting files itself (D10).

## 3. Requirements

**R1 The Review panel does not jump (F37).** A click, or Enter, on a finding **selects** it: the finding's detail, its step in the graph and its line in the panel are highlighted, and **the editor does not move**. Opening the source is a deliberate action: an inline **Go to source** button on the row, the row's context menu, and **Ctrl+Enter / Cmd+Enter**. The setting `loom.review.openSourceOnClick` (default `false`) restores the old behaviour for people who liked it. Moving the editor's cursor to a line still selects the finding on that line (the existing two-way link is unchanged). The same holds for the graph: clicking a step selects it and shows its details; **Go to line** is the explicit action.

**R2 The Outline is never blank (F38).** The view has four states and says which it is in:

| State | What the view shows |
|---|---|
| **No folder open** | A message: "Open a folder to see its workflows" with an *Open Folder* button |
| **A folder with no `.loom` file** | The existing welcome content (try a demo, generate, open an example, check setup, set up a coding agent) |
| **A folder with `.loom` files and no Loom file in the active editor** (the blank case) | The **project view**: the project's progress (R3) above, then **Workflows in this folder**: each `.loom` file with the workflows, agents and schedules it defines, one click to open and reveal; and a one-line hint "Open a `.loom` file to see its steps" |
| **A Loom file active** | The project's progress above, then **This file**: the file's agents, workflows, schedules and routing (as today), then **Workflows in this folder** collapsed |

The `viewsWelcome` contexts are changed to match (`loom.hasLoom` false and no folder; `loom.hasLoom` true is no longer "nothing to show"). An error (no Java, the engine unavailable) is shown **in the view** as one node with the fix, never as an empty tree. The tree refreshes when a `.loom` file is created, saved, deleted or renamed, and when the active editor changes, with a short debounce.

**R3 The Outline shows progress (F39, D10).** The first group of the view is **Project progress**: the ten stages of `weave next` ([Phase 20 R2.1](../20-guided-path-and-skills/DESIGN.md)) as nodes with an icon for their state (done, failing, not started, skipped, stale), a description (the reason for failing, stale or skipped), and the **primary next step** marked and expanded. A click runs the stage's command the way the other commands do (typed into an integrated terminal, so output stays on screen) or opens the file it concerns (`EVAL_DESIGN.md`, the dataset folder, the eval report). The data comes from the engine (`weave serve` method `loom/next`, returning the same JSON as `weave next --format json`), so no process is started per refresh. It refreshes on save, on a file change under the project's `eval/`, `.loom/` and `target/eval4j` folders, and after a terminal command from this extension ends; never more often than once per three seconds; and it shows the **last known** result with its age while a refresh is running. Without Java or without the engine it shows the existing degraded-mode node ([Phase 10](../10-editor-experience/DESIGN.md)) and nothing else.

**R4 `set` is quiet (F40).** In the graph, consecutive `set` statements in the same lane are drawn as **one** small node, "sets 6 variables", that **expands on click** to the list (name and value, truncated), and a single `set` is drawn as a compact node, not a full box. The grouping is a **renderer** concern: the model keeps one node per statement with its own id and line, and the group keeps its members' ids, so selection, breakpoints, the review panel's step links, the run overlay and `weave why` still address each `set` ([Phase 13](../13-time-travel/DESIGN.md)). The change goes through `scripts/sync-graph-render.sh` so the Fabric page gets it too, and `scripts/check-graph-render-sync.sh` stays green. A setting `loom.graph.groupSets` (default `true`) turns grouping off.

**R5 Parallel is lanes, and failure belongs to its step (F41).**
*Model.* Today `NodeDescriber.parallel` emits one node with `agents` and `branches` only; the statements inside the `in parallel` body are not nodes, so their `on_failure` handlers are not in the graph. The graph model gains, for a `parallel` node, **its branches as lanes**: each body statement becomes a node (with its own id, line and, for a `delegate`, its `on_failure` handler nodes) inside a `lane` of the parallel node. The additive attributes are `lanes: [{ label, nodes: [ids] }]`; a consumer that does not know them still sees the existing `agents` and `branches`. `docs/serve/PROTOCOL.md` and the graph JSON schema document the addition, and `docs/API_COMPATIBILITY.md` records it as an additive change.
*Renderer.* Draws a **fork bar**, one **lane** per branch labelled with its agent or step, a **join bar**, and each step's `on_failure` handler as a **dashed side edge** from that step in its lane (the same treatment outside parallel). A parallel body with a single branch is drawn as a normal step with a "parallel (1)" tag. A lane with many steps scrolls with the graph; nothing overlaps at 200% zoom or in the three themes.
*Consumers.* Fabric (it uses the same renderer), the review panel's graph, the run overlay, and `weave graph --format mermaid` (which draws `subgraph` lanes) all read the new attributes; `weave graph` text and the explain output list the lanes.

**R6 One click to the first capped real run (F42).** A command and a button, **Loom: Run Once With a Cap…**, shown in the Outline's progress group when the next step is the real run: it asks for the **cap first** (a number with the currency or token unit and a default from the script's budget), shows the price source and the worst-case estimate ([Phase 18 R7](../18-install-and-identity/DESIGN.md), [Phase 17 R2](../17-honest-results/DESIGN.md)), asks for the input if the workflow takes parameters, and on confirmation types `weave run <script> --max-cost <cap> …` into a terminal. It never runs without the cap shown, and it is the same command as *Run With Live Page…* with the cap made the first question. When the run ends, the Outline offers **Mark this run as read** (which records the confirmation of [Phase 20 R3.3](../20-guided-path-and-skills/DESIGN.md)).

## 4. Compatibility

| Change | Existing users |
|---|---|
| R1 | Clicking no longer moves the editor; `loom.review.openSourceOnClick` restores it. Release note and changelog line. |
| R2, R3 | A view that was blank is no longer; new nodes. No setting needed. |
| R4 | Graphs look different for scripts with consecutive `set`s; `loom.graph.groupSets` turns it off. |
| R5 | Graph JSON gains attributes (additive); graphs of scripts with `in parallel` look different; Fabric's page changes with the shared renderer. |
| R6 | A new command; nothing runs unless chosen. |

## 5. Accessibility and themes

Every new node and control has a label for screen readers; the parallel lanes and the progress icons carry text as well as colour; the Review panel's keyboard route (Enter selects, Ctrl+Enter opens the source) is documented in the panel's help; all of it holds in light, dark and high-contrast themes and at 200% zoom (the existing browser tests for the panel and the graph are extended).

## 6. Open questions

1. R2: should *Workflows in this folder* read every `.loom` file or only those below the workspace root's first levels? Proposed: all, with a limit of 200 files and a "show more" node; the symbols come from the engine, not a regex, when the engine is up.
2. R4: a single `set` between two steps (a counter in a loop): compact node or a badge on the previous step? Proposed: compact node; a badge hides the step in a loop where it matters.
3. R5: nested parallel (a parallel inside a lane): draw nested lanes. Proposed: yes, with a depth limit of 3 before it collapses to a node with a link.
4. R3: the Outline is a tree view and a tree cannot show a progress bar; is a small status-bar item ("Loom: 6 of 10") worth adding? Proposed: yes, off by default (`loom.status.progress`).

## 7. Documentation affected

The extension README and CHANGELOG (each change a line; a screenshot of the new Outline and of parallel lanes retaken with `scripts/take-developer-guide-screenshots.js` and `docs/images/vscode-loom/README.md`'s generator), the developer guide's journey and screenshots, `docs/serve/PROTOCOL.md` (`loom/next`, the lane attributes), `docs/API_COMPATIBILITY.md`, the graph chapter of `LOOM_GUIDE.md`, the wiki's *Loom in VS Code* and Fabric pages, `weave man graph`.
