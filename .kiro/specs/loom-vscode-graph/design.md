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
index, or `unresolved = true`. On a duplicate name it picks the file nearest the caller (same file,
then first import in order) and emits a warning. Callees are never inlined, so recursion is safe.

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

`WorkflowGraph.of(WorkflowDef)` calls `GraphBuilder` and maps `GraphNode` to `WorkflowTrace.Node`
(dropping `source`/`call`), preserving ids, kinds, labels and bounds. Handler nodes and new node kinds
are the one intended change: they only appear for scripts that use those statements, and the
report's HTML already renders unknown kinds generically. If a report golden file for an existing script
changes, that is a defect in the mapping, not an accepted diff — `WorkflowGraph.of` filters to the
node kinds it produced before.

### 5. vscode-loom

Wireframes, node shapes, states and keyboard behaviour are specified in `ui.md`; `mockup.html` is the
interactive reference.

New files:

| File | Role |
|---|---|
| `src/commands/showGraph.ts` | registers the command, resolves java/jar, spawns `weave graph`, 30 s timeout, parses JSON |
| `src/views/GraphPanel.ts` | owns the `WebviewPanel`, message protocol, refresh, watchers |
| `src/graph/model.ts` | TypeScript types mirroring the JSON, `parseGraph()` validating `version` |
| `src/graph/layout.ts` | pure function: workflow → positioned boxes and routed edges (layered top-to-bottom; back-edges drawn on the side) |
| `media/graph.js`, `media/graph.css` | webview renderer (SVG), pan/zoom, selection, legend, chips, details card |
| `media/loom-mark-128.jpg`, `media/loom-logo-320.jpg` | Logo_Assets (see `ui.md` section 10) |
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

**Security.** `webview.options.localResourceRoots = [extension media dir]`; CSP
`default-src 'none'; img-src ${cspSource}; style-src ${cspSource}; script-src 'nonce-…'`. All label text is inserted with
`textContent`, never `innerHTML`, because labels come from script content.

**Settings.** `loom.graph.javaPath` (default `java`), `loom.graph.timeoutMs` (30000),
`loom.graph.autoRefresh` (true).

## Error handling

| Situation | Behaviour |
|---|---|
| java or jar missing | notification with the fix, no panel |
| entry file unparseable (exit 2) | notification with the parser message and line |
| import missing / syntax error | graph shown for what parsed; diagnostics strip lists the file |
| import cycle | cycle skipped; warning in diagnostics |
| call to unknown workflow | node drawn dashed, "unresolved" tooltip |
| refresh failure | last good graph kept, "stale" banner |
| > 300 nodes | blocks collapsed by default |

## Testing strategy

- **Java unit**: one test per statement kind; alt/loop edge shapes; handler edges; id stability;
  import closure (simple, diamond, cycle, missing file, syntax error); call resolution (found,
  unresolved, duplicate, recursive).
- **Golden**: JSON and Mermaid for `samples/boardroom`, `content_factory`, `digest`, and the
  `imports/parent.loom` fixtures.
- **Report regression**: all existing `eval4j-report` tests unchanged and green.
- **CLI**: exit codes, `--workflow`, stdout contains only JSON, no network or key access.
- **Extension**: `parseGraph` rejects wrong `version`; `layout` has no overlapping boxes and routes every
  edge; message protocol rejects `openSource` for files outside the closure; `@vscode/test-electron`
  smoke test opens the panel on a sample.

## Risks and open points

- Adding `line` to AST types touches many parser sites; the change is mechanical but wide. Mitigation:
  default 0, tests assert lines only where set.
- JVM start (~0.5–1 s) per refresh. Mitigation: debounce and cancellation; a later optimisation is a
  long-lived `weave graph --serve` process, deliberately out of scope here.
- Layout quality for large, deeply nested workflows; mitigated by collapsing and the dagre fallback.
- Duplicate workflow names across imports follow the loader's merge order today; the graph picks
  nearest-first and warns, which may differ from runtime. Confirm with the runtime's rule in task 4.3.
