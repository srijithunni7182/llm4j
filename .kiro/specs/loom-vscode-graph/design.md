# Design Document: Loom Workflow Graph

## Overview

```
 .loom file(s)
      │  LoomParser (existing)
      ▼
 GraphBuilder  ───────────────►  WorkflowGraph model  ──► JSON / Mermaid
 (loom module, new)                      ▲                       │
      ▲                                  │                       ▼
      │ delegates                        │              `weave graph` (WeaveCLI)
 eval4j-report WorkflowGraph             │                       │ stdout JSON
                                         │                       ▼
                                         └──────── vscode-loom: GraphCommand → Graph_Panel (webview)
```

## Key constraints found in the code

1. **Module direction.** `eval4j-report` depends on `ai-agent4j-loom`. The existing `WorkflowGraph`
   (in `eval4j-report`) therefore cannot be called from `weave.jar`. The builder moves into the loom
   module and the report class delegates to it.
2. **Imports are flattened.** `LoomLoader.loadRecursive` merges every imported script into one
   `LoomScript` and drops where each definition came from. The graph needs provenance, so it cannot
   use `LoomLoader.load()` as is.
3. **Line numbers are partial.** `AgentDef`, `CheckpointStmt`, `RewindStmt`, `DecideStmt` and a few
   others have `setLine`; most statements (`delegate`, `run`, `alt`, `loop`, `call`, …) do not. The
   parser has the token line at the point it creates each statement (`keyword.getLine()`), so adding it
   is mechanical.
4. **`WorkflowGraph` today** handles `delegate`, `run`, `handoff`, `alt`, `loop`, `human_prompt`,
   `checkpoint`, `parallel`; everything else becomes a generic `statement` node and handler bodies
   (`on_failure` etc.) are not drawn. `StatementWalker.nested` lists the handler lists.

## Components

### 1. AST: source lines

Add `int line` (default 0 = unknown) to the `Statement` interface via a small default-method pair
(`getLine()` returns 0, overridden), or a `SourceLocated` mix-in, and set it in `LoomParser` where each
statement is built. Also record `WorkflowDef.line`. No behaviour change; existing constructors keep
working.

### 2. `io.github.llm4j.loom.graph` (new package)

```java
record GraphNode(String id, String kind, String label, String agent, Integer bound,
                 SourceRef source, CallLink call, boolean unresolved,
                 Map<String, Object> attrs) {}       // only attributes that are set
record AgentInfo(String name, String model, Double temperature, String persona,
                 List<String> tools, List<String> mcp, List<String> skills,
                 List<String> knowledge, List<String> approve, boolean approveAll,
                 Map<String, Object> budget, Integer maxIterations, SourceRef source) {}
record GraphEdge(String from, String to, String label) {}
record SourceRef(String file, int line) {}
record CallLink(String workflow, String file) {}          // file null if unresolved
record WorkflowGraph(String name, String file, List<String> params,
                     List<GraphNode> nodes, List<GraphEdge> edges) {}
record ImportFile(String path, List<String> imports) {}
record Diagnostic(String severity, String file, int line, String message) {}
record GraphResult(int version, String entry, List<ImportFile> files,
                   List<WorkflowGraph> workflows, List<AgentInfo> agents,
                   Map<String, Object> runBudget, List<Diagnostic> diagnostics) {}
```

`GraphBuilder.build(WorkflowDef, String file) -> WorkflowGraph` is the existing `WorkflowGraph.block`
logic, extended to handle every statement kind and the handler lists, with the same pre-order
`n1, n2, …` numbering so eval reports do not change. Handler blocks are laid out after the main
block; their edges are labelled `failure` / `exhausted` / `violation`.

`ImportClosureLoader` loads the entry file and its imports **without merging**: it returns
`Map<Path, LoomScript>` plus the import edges and diagnostics. It reuses `Lexer`/`LoomParser` and the
same path resolution as `LoomLoader` (`path.getParent().resolve(raw).normalize()`). Cycles are
detected with a visiting set; the offending import is skipped and a diagnostic emitted. A parse error
in one file yields a diagnostic with the file and line and the file is skipped.

`CallResolver` indexes workflows by name across the closure; a `call` node gets `CallLink` from the
index, or `unresolved = true`. On a duplicate name the definition that comes first in run order wins,
which is what `HarnessExecutor` runs (`LoomLoader` merges imports before the importing file's own
definitions and the harness takes the first match); the other is reported as a warning. Callees are never
inlined, so recursion is safe.

Implemented as small single-purpose classes in `io.github.llm4j.loom.graph`: `NodeDescriber` (statement to
node and attributes), `GraphBuilder` (control flow), `ImportClosureLoader` (files), `CallResolver` (call
links), `AgentCatalog` (agent settings), `GraphService` (orchestration), `GraphJson` and `MermaidRenderer`
(output). Facts that shaped them: a `run` statement keeps the kind `task` the report already used; a loop's
exit edge is labelled `done`; handler blocks are numbered after the main path so existing ids do not move;
`ImportClosureLoader` returns files in run order, plus discovery order for display.

`MermaidRenderer` turns a `WorkflowGraph` into `flowchart TD` text (shapes per kind, labelled edges).

### 3. `weave graph`

New `GraphCommand` in `WeaveCLI` (picocli, same style as `CheckCommand`):

```
weave graph <file> [--format json|mermaid] [--workflow NAME]
```

JSON written with Jackson (already a dependency). Stdout carries only the result; exit 0 if any graph
was produced, 2 if not. It does not touch `WeaveEnv.models()` or secrets.

Example JSON (abridged):

```json
{ "version": 1, "entry": "/p/parent.loom",
  "files": [{"path":"/p/parent.loom","imports":["/p/child.loom"]}, {"path":"/p/child.loom","imports":[]}],
  "workflows": [{
    "name":"ParentWorkflow","file":"/p/parent.loom","params":[],
    "nodes":[{"id":"start","kind":"start","label":"Start"},
             {"id":"n1","kind":"call","label":"call ChildWorkflow",
              "source":{"file":"/p/parent.loom","line":9},
              "call":{"workflow":"ChildWorkflow","file":"/p/child.loom"}},
             {"id":"end","kind":"end","label":"End"}],
    "edges":[{"from":"start","to":"n1"},{"from":"n1","to":"end"}]}],
  "diagnostics":[] }
```

### 4. eval4j-report

`WorkflowGraph.of(WorkflowDef)` calls `GraphBuilder` and maps `GraphNode` to `WorkflowTrace.Node`,
dropping `source` and `call` and keeping `attrs` (see section 6). For every statement the old code
handled, ids, kinds, labels and bounds are unchanged. New kinds and handler nodes appear only for scripts
that use those statements. Before this change they came out as a generic `statement` node, which the
trace schema's `kind` list does not even contain (see section 6, schema). If a report golden file for a
script that uses only the old kinds changes, that is a defect in the mapping, not an accepted diff.

Facts about the report today that shape the design:
- `dashboard.js` does not draw the graph. It uses node labels only to print the expected and actual path
  as text rows (`traceView`), plus an agent-lane timeline.
- `LoomTrace` infers `actualPath` and each event's `node` by mapping a delegation to the next statement
  that delegates to the same agent. The overlay is only as exact as that inference, which is why the card
  says so (Requirement 10.12).
- `HtmlRenderer` inlines `dashboard.css` and `dashboard.js` into one HTML file, so the renderer must be a
  plain JS file with no imports, no build step and no network access.

### 5. vscode-loom

Wireframes, node shapes, states and keyboard behaviour are specified in `ui.md`; `mockup.html` is the
interactive reference.

New files:

| File | Role |
|---|---|
| `src/commands/showGraph.ts` | registers the command, resolves java/jar, spawns `weave graph`, 30 s timeout, parses JSON |
| `src/views/GraphPanel.ts` | owns the `WebviewPanel`, message protocol, refresh, watchers |
| `src/graph/model.ts` | TypeScript types mirroring the JSON, `parseGraph()` validating `version` |
| `media/graph-render.js` | generated copy of the Shared_Renderer (section 6): layout, shapes, glyphs, Chip text, overlay |
| `media/graph.js`, `media/graph.css` | panel glue only: toolbar, messages, selection, details card, theme variables; calls `graph-render.js` |
| `media/loom-mark-128.png`, `media/loom-logo-320.png` | Logo_Assets (see `ui.md` section 10) |
| `scripts/make-logo-assets.sh` | regenerates the Logo_Assets from `loom_logo.png` with ImageMagick |

**Rendering.** A small bundled layout (layering by longest path, one column per branch, loop
back-edges routed on the right) drawn as SVG. This avoids a runtime dependency and any network access.
If layout quality proves insufficient the fallback is bundling `dagre` (MIT) in `media/`, not loading it
from a CDN. Mermaid output remains available for docs and "copy as Mermaid".

**Message protocol** (typed, validated on both sides):

- host → webview: `graph {result, selected}`, `stale {error}`, `highlight {file, line}`
- webview → host: `ready`, `openSource {file, line, beside}`, `selectWorkflow {name}`,
  `copyMermaid {name}`

The webview never receives file contents, only the graph JSON; `openSource` is accepted only for files
that appear in `result.files`.

**Refresh.** `onDidSaveTextDocument` for any path in the closure triggers a debounced re-run.
`onDidChangeTextEditorSelection` (active entry file) sends `highlight` when the cursor's line matches a
node's `source.line`. A new run aborts the previous child process (`AbortController` / `kill`).
State kept across refreshes: selected workflow name, transform (zoom/pan), collapsed block ids.

**Content-security policy and the renderer.** The webview may not run inline styles, so the renderer's
markup carries no `style` attributes (it uses classes), `View` takes `embedCss: false`, and the page loads
the same rules from `graph-render.css`, generated from the renderer by `sync-graph-render.sh`. Selecting a
step toggles a class instead of redrawing, so a double-click still reaches the same element.

**Testing without VS Code.** The behaviour (refresh, debounce, stale state, cursor sync, safe source
opening) lives in `GraphPanelController`, which depends only on small interfaces (`PanelHost`, `Scheduler`,
`GraphRunner`). `GraphPanel.ts` and `showGraph.ts` are a thin edge over the VS Code API and are tested with
a stand-in `vscode` module, the real `weave.jar` and real processes; the page is tested in a browser with a
stand-in `acquireVsCodeApi`. Opening the result in a real VS Code is a manual check (M1 to M15).

**Security.** `webview.options.localResourceRoots = [extension media dir]`; CSP
`default-src 'none'; img-src ${cspSource}; style-src ${cspSource}; script-src 'nonce-…'`. All label text is inserted with
`textContent`, never `innerHTML`, because labels come from script content.

**Settings.** `loom.graph.javaPath` (default `java`), `loom.graph.timeoutMs` (30000),
`loom.graph.autoRefresh` (true).

### 6. Shared renderer and the eval report

**One drawing module.** `graph-render.js` is plain JavaScript (no imports, no build) with a small API:

```js
LoomGraph.layout(graph, opts)            // -> {nodes: [{id,x,y,w,h,...}], edges: [{d, label, lx, ly}], bounds}
LoomGraph.chips(node)                    // -> [{t, v}] from node.attrs, formatted per ui.md 3.2
LoomGraph.render(svgEl, graph, {
  mode: 'panel' | 'report',
  overlay: { states: {id: 'ok'|'missed'|'unexpected'|'taken'|'none'}, visits: {id: n}, traversed: [[a,b]] },
  collapsed: Set, selected: id,
  onSelect(id), onOpen(id)               // the host decides what these do
})
```

Colours come from CSS custom properties (`--k-agent`, `--surface`, `--edge`, `--sel`, …). Each host defines
them: the panel maps them to VS Code theme variables, the report to its own tokens (`--pass`, `--fail`,
`--ink`, `--line`, `--surface`). The module holds all glyphs, shapes and Chip formatting, so Java never
formats a chip: JSON carries raw `attrs`.

**Where the source lives.** The canonical file is `loom/graph-render/graph-render.js`, with its tests
(`node --test`). `scripts/sync-graph-render.sh` copies it to
`loom/vscode-loom/media/graph-render.js` and
`eval4j-report/src/main/resources/io/github/llm4j/evalreport/render/graph-render.js`, and
`scripts/check-graph-render-sync.sh` fails when a copy differs from the canonical file. It runs in CI,
and `eval4j-report` also has a unit test that compares its copy with the canonical file's hash.

**Data into the report.**
1. `WorkflowTrace.Node` (eval4j, engine neutral) gets an optional `Map<String,Object> attrs`. A
   secondary 5-argument constructor keeps every existing caller compiling.
2. `WorkflowGraph` in `eval4j-report` maps `GraphNode.attrs` through. `LoomTrace` serialises it with the
   rest of the node.
3. `trace.schema.json` (both copies, `spec/schema/` and `src/test/resources/schema/`) extends the node
   `kind` enum with every kind the builder produces (`call`, `foreach`, `guardrail`, `rewind`, `decide`,
   `observe`, `note`, `broadcast`, `run`, `unknown`) and the `statement` kind the old code already emitted,
   and adds an optional `attrs` object. `additionalProperties: true` on nodes already allows this, so
   old traces validate unchanged. The `kind` list stays closed so a typo is still caught.
4. The card draws a graph when it scrolls into view (an `IntersectionObserver`), and every graph when the page
   is printed, so a report with dozens of workflows opens quickly. `graph-card.js` computes the Run_Overlay from `expectedPath`, `actualPath` and `events`:
   `states` by set membership, `visits` by counting `actualPath`, `traversed` from consecutive pairs that
   are edges. It then calls `LoomGraph.render(..., {mode: 'report', overlay})` inside a new
   `graphCard(w)` in `traceView`, above the existing path rows.
5. Per-node details come from `events` filtered by `event.node`, with duration from the first start and
   last end event for that node, and spend from `w.spend` filtered by the node's agent (labelled "per agent",
   since `SpendLine` has no node id).

**Not done here.** Recording node ids in the run journal, so the path is exact instead of inferred. That is
a larger Loom change; the overlay will pick it up unchanged when it exists.

## Error handling

| Situation | Behaviour |
|---|---|
| java or jar missing | notification with the fix, no panel |
| entry file unparseable (exit 2) | notification with the parser message and line |
| import missing / syntax error | graph shown for what parsed; diagnostics strip lists the file |
| import cycle | cycle skipped; warning in diagnostics |
| call to unknown workflow | node drawn dashed, "unresolved" tooltip |
| refresh failure | last good graph kept, "stale" banner |
| report: no nodes / over 500 nodes / empty path | see `ui.md` 11.4 |
| > 300 nodes | blocks collapsed by default |

## Testing strategy

- **Java unit**: one test per statement kind; alt/loop edge shapes; handler edges; id stability;
  import closure (simple, diamond, cycle, missing file, syntax error); call resolution (found,
  unresolved, duplicate, recursive).
- **Golden**: JSON and Mermaid for `samples/boardroom`, `content_factory`, `digest`, and the
  `imports/parent.loom` fixtures.
- **Report regression**: all existing `eval4j-report` tests unchanged and green.
- **CLI**: exit codes, `--workflow`, stdout contains only JSON, no network or key access.
- **Shared renderer** (`node --test`): layout has no overlapping boxes and routes every
  edge; message protocol rejects `openSource` for files outside the closure; `@vscode/test-electron`
  smoke test opens the panel on a sample.

## Risks and open points

- Adding `line` to AST types touches many parser sites; the change is mechanical but wide. Mitigation:
  default 0, tests assert lines only where set.
- JVM start (~0.5–1 s) per refresh. Mitigation: debounce and cancellation; a later optimisation is a
  long-lived `weave graph --serve` process, deliberately out of scope here.
- Layout quality for large, deeply nested workflows; mitigated by collapsing and the dagre fallback.
- Duplicate workflow names across imports: the graph follows the harness (first in run order wins) and
  warns. A test loads the same script through `LoomLoader` to keep the two in step.
