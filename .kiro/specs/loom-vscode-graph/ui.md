# UI Specification: Loom Workflow Graph

Wireframes, states and interaction rules for the Graph_Panel. `mockup.html` in this folder is the
interactive version of these wireframes (example data, not real output). Requirements 5–8 refer here.

## 1. Placement

| Entry point | Where |
|---|---|
| Command palette | `Loom: Show Workflow Graph` |
| Editor title bar | graph icon, only when the active file is `.loom` |
| Editor context menu | `Show Workflow Graph` |
| Keybinding | none by default (users can bind the command) |

The panel opens in the column beside the editor (`ViewColumn.Beside`) and reuses one panel per entry file.

## 2. Default layout

```
┌─ Editor: main.loom ─────────────────┐┌─ ◈ Loom Graph · main.loom ──────────────────────────┐
│  4  workflow GenerateContent(topic) ││ [◈] Loom Graph · [GenerateContent(topic) ▾]  ⊖ ⊕ ⤢ ⧉ │
│  5    delegate "Research…" to Resea ││ ─────────────────────────────────────────────────── │
│  7    parallel {                    ││ ⚠ 1 warning ▸ (collapsible diagnostics strip)        │
│ ▌12   human_prompt "Review…"        ││                                                      │
│ 14    alt (approval == "yes") {     ││                    ( Start )                         │
│ 17      call ReviseContent(…)       ││                        │                             │
│                                     ││              ┌─────────────────┐                     │
│                                     ││              │ delegate        │                     │
│                                     ││              │ Researcher      │                     │
│                                     ││              └─────────────────┘                     │
│                                     ││                        ⋮                             │
│                                     ││                      ( End )                         │
│                                     ││ ─────────────────────────────────────────────────── │
│                                     ││ Files: ▾ main.loom (entry)  └ primitives.loom        │
│                                     ││ Selected: human_prompt · main.loom:12  [Go to source]│
└─────────────────────────────────────┘└──────────────────────────────────────────────────────┘
```

Regions, top to bottom: **toolbar**, **banners** (stale, diagnostics), **canvas**, **footer** with the
import tree (left) and the selected-node details (right). The canvas takes all spare height.

## 3. Primitive catalogue

Every Loom statement is one node. The same rules draw the node in the Graph_Panel and in the eval report
(section 11), because both use the Shared_Renderer. A node has a **shape** (what it is), a **title** (what it does), an
optional **subtitle** (who or what it uses), **chips** (attributes set on it) and **edges** (control flow).
Kind is carried by shape and glyph as well as colour. The JSON carries every attribute below in
`node.attrs` (design.md), so the panel never parses source text.

### 3.1 Statements

| Primitive | Source form | Shape and glyph | Title | Subtitle | Chips (attributes) | Extra edges |
|---|---|---|---|---|---|---|
| `delegate` | `delegate "…" to Agent -> var` | rectangle, blue, agent glyph | `delegate` | `Agent · model` | retry, timeout, budget, expecting, `→ var` | `failure` to its `on_failure` block |
| `run` (kind `task`) | `run Task(a=…) -> var` | rectangle, teal, code glyph | `run Task` | `n args` | retry, timeout, `→ var` (no budget: a task spends no tokens) | `failure` |
| `handoff` | `handoff "…" to Agent` | rectangle, blue, arrow-out glyph, no outgoing edge to the next step | `handoff` | `Agent · model` | none | none (control leaves the workflow) |
| `broadcast` | `broadcast "…" to A, B -> var` | rectangle, blue, fan-out glyph | `broadcast` | `A, B, C` (first 3, then `+n`) | budget, `→ var` | none |
| `parallel` | `parallel { … }` | rectangle with double top bar, green | `in parallel` | agents, one per line (first 4, then `+n`) | `n branches` | none (one node for the whole round) |
| `alt` | `alt (cond) { } else { }` | hexagon, amber | condition + `?` | none | none | `then`, `else` |
| `loop` | `loop until (cond) max N { }` | rounded rectangle, violet, ↻ glyph | `loop until cond` | none | `max N`, budget | `again` back-edge, `exhausted` to `on_exhausted` block |
| `foreach` | `for each x in a.b { }` | rounded rectangle, violet, list glyph | `for each x in a.b` | none | `parallel` (∥ glyph, when `parallel for each`), budget | `again`, `exhausted` |
| `human_prompt` | `human_prompt "…" -> var` | rectangle, orange, person glyph | `ask a person` | first 40 chars of the message | `→ var` | none |
| `checkpoint` | `checkpoint name starting with k = v` | flag-shaped tab, grey, flag glyph | `checkpoint name` | none | `n values` (the `starting with` pairs) | none |
| `rewind` | `rewind to name when (c) at most N times carrying … ` | rectangle, grey, rewind glyph | `rewind to name` | condition | `≤ N times`, effects chip (`ask first` / `keep` / `repeat`), `carrying n` | dashed `rewind` edge back to its checkpoint; `still fails`, `blocked` to their blocks |
| `call` | `call Wf(a=…) -> var` | rectangle with double side borders, magenta, ↗ glyph | `call Wf` | file chip when the callee is in another file | `n args`, `→ var` | none (callee is opened by drill-down, never inlined) |
| `guardrail` | `guardrail(PII) { } on_violation { }` | rectangle with shield glyph, teal outline, body drawn below | `guardrail PII` | none | none | `violation` to its block |
| `decide` | `decide Name -> var` | rectangle, magenta outline, scale glyph | `decide Name` | none | level chip `WATCH` / `SUGGEST` / `ACT` when the decision declares it | none |
| `observe` | `observe "label" expr` | small rectangle, grey, eye glyph | `observe label` | expression | none | none |
| `note` | `note "…"` | small rectangle, dashed grey outline, note glyph | `note` | first 40 chars | none | none |
| unknown | any new statement kind | dashed grey rectangle | the kind name | none | none | none |

### 3.2 Attribute chips

Chips sit in a row at the bottom of the node, in the order given for the primitive in the table above
(for a delegate: timeout, retry, budget, expecting, variable). At most three are drawn; more collapse into
`+n` (the details area lists all).

| Attribute | Chip text | Notes |
|---|---|---|
| `retry N` with `backoff D` | `retry 3 · 2s` | backoff shown after the dot when set; `retry 3` alone otherwise |
| `timeout D` | `timeout 30s` | durations print as `500ms`, `2s`, `3m`, `1h` |
| `budget` | first set limit: `5k tok`, `20 calls`, `$0.50`; `+` when more limits exist | tooltip lists tokens, calls, cost, per call, warn at, window (`/ min`, `/ hour`, `/ day`) and when exhausted (`stop` / `suspend` / `ask`) |
| `expecting <schema>` | `expects {score, notes}` or `expects list<Item>` | tooltip shows the schema outline |
| `max N` | `max 3` | loops only; a loop with no `max` shows `no max` in the warning colour |
| `parallel` | `∥ parallel` | `for each` only |
| `→ var` | `→ research_data` | name of the variable the result binds to; last in priority |
| rewind effects | `ask first`, `keep`, `repeat` | `ask first` is the default and is drawn muted |
| rewind `at most N` | `≤ 2 times` | |

Chip colours are neutral, with the budget chip in the amber semantic colour and `no max` in the warning
colour. Chips are text, so they are readable without colour.

### 3.3 Agent details

A node that names an agent shows `Agent · model` in its subtitle. Hovering or focusing a node opens a
**details card** with the agent's settings, read from the agent definition:

| Agent attribute | Card row |
|---|---|
| `model`, `temperature` | `gpt-4 · temp 0.2` |
| `persona` | persona name |
| `tools`, `mcp_servers`, `skills`, `knowledge` | counts with names on expand |
| `approve` / `approve_all` | `Needs approval: send_email, delete_row` (also a hand glyph on the node) |
| `budget` | same format as the budget chip tooltip |
| `max_iterations` | `max 8 iterations` |
| `memory`, `voice`, `guard` | on/off with the configured type |
| source | `primitives.loom:2` link that opens the definition |

### 3.4 Workflow and script level

| Attribute | Where it shows |
|---|---|
| workflow name and parameters | toolbar title `GenerateContent(topic)`, parameters as chips |
| defining file | file chip next to the title when the workflow is not in the entry file |
| run budget (`budget { }` at script level) | toolbar chip, same format as node budget chips |
| `schedule`, `routing`, `provider`, `persona`, `tool`, `knowledge` definitions | not drawn as nodes; listed in the footer under the file they come from, each opening its source line |

### 3.5 Unresolved and error forms

| Case | Drawing |
|---|---|
| `call` to a workflow not found | dashed red outline, `?` chip, tooltip "No workflow named X in this file or its imports" |
| `delegate` / `handoff` to an agent not defined in the closure | agent name in the warning colour with `?` chip |
| `rewind` to a checkpoint that does not exist | dashed red edge ending in a `?` |

## 4. Edges

- Solid arrow for sequence. Label chips: `then`, `else`, `again`, `failure`, `exhausted`, `violation`.
- Loop back-edges route down the right side of the loop body and return to the loop node.
- Handler blocks (`on_failure` …) hang to the right of their owner with a dashed edge.

## 5. Drill-down into a call

```
┌ Loom Graph · main.loom ────────────────────────────────────┐
│ ‹ Back   GenerateContent  ›  ReviseContent  [primitives.loom] │
```

- Click a `call` node: select it. Double-click or `Enter`: open the callee in the panel.
- `Ctrl/Cmd`+click: open the callee's source in the editor instead.
- Breadcrumb segments are clickable; `Back` returns to the previous workflow with zoom and selection kept.
- The editor shows the file the selected workflow comes from (opens `primitives.loom` on drill-down).

## 6. States

| State | What the user sees |
|---|---|
| Loading | centred Loom logo with a slow glow pulse and "Reading main.loom…"; toolbar disabled |
| Normal | as section 2 |
| Stale | amber banner "Showing the last good graph. main.loom:14 syntax error — expected `{`" with **Open file** |
| Diagnostics | strip "⚠ 2 warnings" expands to a list; each row opens its file and line |
| Unresolved call | dashed red call node with `?`; tooltip "No workflow named ReviseContent in this file or its imports" |
| Large graph (>300 nodes) | alt/loop/parallel blocks collapsed to one node with a `+ 14 steps` chip; click to expand |
| No workflows | Loom logo and "This file defines agents but no workflows" with **Go to line 1** |
| Java missing | no panel; notification "Java 17 or newer is needed. Set `loom.graph.javaPath`." with **Open Settings** |
| Entry unparseable | no panel; notification with message and **Go to error** |

## 7. Interaction rules

| Action | Result |
|---|---|
| Click node | select; editor reveals and highlights its line; details row updates |
| Click empty canvas | clear selection |
| Click a line in the editor | select the node for that line, pan it into view |
| Drag canvas | pan |
| Mouse wheel + `Ctrl/Cmd` | zoom about the pointer |
| Toolbar ⊖ ⊕ / Fit | zoom out / in / fit the whole graph |
| Workflow selector | switch workflow; the workflow containing the cursor is selected on open |
| Copy Mermaid | copies `flowchart TD` text for the selected workflow; toast "Copied" |
| Save of any file in the import closure | refresh after 300 ms; selection, zoom and collapsed blocks kept |

## 8. Keyboard and accessibility

- Toolbar controls are real buttons with labels; the workflow selector is a native `<select>`.
- Nodes are focusable (`tabindex=0`, `role="button"`, `aria-label="delegate Researcher, main.loom line 5"`).
  Arrow keys move to the next/previous node along edges; `Enter` selects; `Shift+Enter` opens callee.
- Visible focus ring on nodes and controls, at least 3:1 contrast.
- Colours come from VS Code theme variables (`--vscode-*`) with fixed fallbacks; checked in light, dark
  and high-contrast themes. In high contrast, outlines are solid 2 px and fills are removed.
- Animations (pan to node) are skipped when `prefers-reduced-motion` is set.

## 9. Content rules

- Text in nodes comes from script content and is inserted as text only.
- Numbers (`max 3`, line numbers) use tabular figures.
- Error and banner copy says what happened and what to do, with no apology.

## 10. Loom logo

The Loom logo (`src/loom/ai-agent4j-loom/loom_logo.png`, a 1024 px neon knot with the wordmark on a dark
ground) appears in everything the extension opens.

| Place | Asset | Size | Rule |
|---|---|---|---|
| Panel toolbar, left of the title | `media/loom-mark-128.png` (emblem only, cropped from the logo) | 28 px | sits on a 36 px rounded dark tile so it reads on light themes |
| Panel tab | same file via `WebviewPanel.iconPath` | tab size | one file for light and dark |
| Loading and empty states | `media/loom-logo-320.png` (emblem and wordmark) | 160 px | centred above the message; glow pulse while loading |
| Marketplace and extensions list | `package.json` `"icon"` → `media/loom-mark-128.png` | 128 px | |
| Diagnostics, banners, toasts | none | | the logo is not repeated on small UI |

Rules:
- Use the logo as supplied: no recolouring, stretching or added effects. Minimum 20 px; keep 4 px clear space.
- The tile is `#1a1d26`, the logo's own background, so there is no visible edge in dark themes.
  In high-contrast themes the tile gets a 1 px `--vscode-contrastBorder` outline.
- The pulse in the loading state is skipped when `prefers-reduced-motion` is set.
- The image has `alt="Loom"`; in the toolbar it is decorative next to the visible title, so `alt=""`.
- Assets are bundled in the `.vsix` and referenced through `webview.asWebviewUri`, never from a URL.
- The activity-bar icon stays a monochrome codicon (VS Code requires a single-colour SVG there). A
  monochrome knot SVG for it is a follow-up and is not part of this change.
- Derived files are produced by `scripts/make-logo-assets.sh` (ImageMagick `convert`), so they can be
  regenerated if the logo changes. Samples of the output are in `assets/`.

## 11. Eval report variant

The eval report draws the same graph (Shared_Renderer) inside its trace view. It is a static page, so
there is no editor, no drill-down and no Mermaid copy. The existing path rows, timeline, spend table and
event log stay where they are.

```
┌─ Trace: GenerateContent · workflow ─────────────────────────────────────────────────────┐
│ Workflow graph                                  ⊖ ⊕ ⤢    Legend ▾                         │
│ ┌────────────────────────────────────────────────────┐ ┌─────────────────────────────┐ │
│ │                    ( Start )   ✓                    │ │ Selected: delegate           │ │
│ │                        │                            │ │ Researcher · gpt-4           │ │
│ │              ┌─────────────────┐ ✓ ×1               │ │ retry 3 · timeout 90s        │ │
│ │              │ delegate        │                    │ │ 4.2 s · 3 events             │ │
│ │              │ Researcher      │                    │ │ Agent spend: 1,420 tok       │ │
│ │              └─────────────────┘                    │ │ (per agent, not per node)    │ │
│ │                        ⋮                            │ │ 0.00 s  delegate_start  …    │ │
│ │       ┌──────────┐        ┌──────────┐              │ │ 4.20 s  delegate_end    …    │ │
│ │       │ note ✗ missed     │ call  ! unexpected      │ └─────────────────────────────┘ │
│ └────────────────────────────────────────────────────┘                                  │
│ Path is inferred from the order of delegations.                                           │
│ Expected path  Start → delegate → …        Path taken  Start → delegate → …              │
└──────────────────────────────────────────────────────────────────────────────────────────┘
```

### 11.1 Overlay states

| State | Meaning | Mark (glyph and text) | Drawing |
|---|---|---|---|
| taken as expected | on `actualPath` and `expectedPath` | `✓` badge, tooltip "taken as expected" | normal fill; the report's pass colour on the badge |
| missed | on `expectedPath` only | `✗ missed` chip | dashed outline, muted fill; fail colour on the chip |
| unexpected | on `actualPath` only | `! unexpected` chip | solid outline in the warning colour |
| taken (no expected path) | on `actualPath`, trace has no `expectedPath` | `●` badge | normal fill |
| not visited | on neither path | none | 45 % opacity |
| visit count | node appears n > 1 times in `actualPath` | `×n` chip | next to the badge |
| traversed edge | consecutive pair in `actualPath` that is an edge | none | thicker line in the report's ink colour |
| untraversed edge | any other edge | none | thin, 45 % opacity |

Overlay marks use the report's tokens (`--pass`, `--fail`, `--good`, `--ink`, `--line`, `--surface`), so
they follow the report's light and dark themes. Marks always have text or a glyph, never colour alone.

### 11.2 Details area

Clicking or focusing a node fills the details area (right of the graph, below it at narrow widths) with:
kind and title, attributes as Chips, agent and model, the events mapped to that node (time, type, text),
duration when both start and end events exist, and the agent's spend labelled "per agent". An empty
selection shows "Select a node to see what happened there."

### 11.3 Not in the report

No drill-down into called workflows (the call node shows its callee name), no source navigation, no Mermaid
copy, no diagnostics strip, no Loom logo (eval4j is engine neutral). Pan, zoom, Fit, Legend and keyboard
focus work as in the panel.

### 11.4 Fallbacks

| Case | Drawing |
|---|---|
| trace has no graph nodes | no card; the path rows show as today |
| graph over 500 nodes | a note "Graph too large to draw (n nodes)" and the path rows |
| `actualPath` empty | steps on the expected path show as *missed*, all others as *not visited*, with the note "No steps were recorded." |
| event that could not be placed on a node | counted in a line "n events could not be placed on a step" |
