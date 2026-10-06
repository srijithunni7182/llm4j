# Requirements Document

## Introduction

Loom Workflow Graph shows a `.loom` script as a diagram inside VS Code. A developer opens a `.loom`
file, runs **Loom: Show Workflow Graph**, and sees each workflow as a flowchart: steps, branches, loops,
parallel rounds, and calls into other workflows — including workflows defined in imported files.
Clicking a node jumps to the source line. The diagram updates when the file is saved.

It spans four places:

1. **ai-agent4j-loom** gains a graph builder and a `weave graph` command that prints the graph as JSON
   or Mermaid. The builder lives here because the VS Code extension bundles `weave.jar`.
2. **eval4j-report** keeps producing its report graph, but from the shared builder, so there is one
   definition of "the graph of a workflow".
3. **vscode-loom** adds a webview panel that runs `weave graph` and draws the result.
4. **eval4j-report** also *draws* the graph, with the run laid over it (path taken, missed and unexpected
   steps, retries). The panel and the report use one drawing module, so a node looks the same in both.

Decisions already taken:
- **One source of truth.** The graph is built from the real parser's AST, not from regexes in
  TypeScript, so it cannot drift from the grammar.
- **Imports are followed.** Workflows reached through `import` and `call` appear in the graph, tagged
  with the file they come from.

All changes are additive. Existing `weave` commands, the eval report and the Outline view behave as today.

---

## Glossary

- **Graph_Builder**: The loom-module class that turns a parsed script into a Workflow_Graph.
- **Workflow_Graph**: Nodes and edges for one workflow, plus the Call_Links it makes.
- **Node**: One statement (or `start` / `end`), with an id, kind, label, source file and line.
- **Edge**: A control-flow link between two nodes, optionally labelled (`then`, `else`, `again`, …).
- **Call_Link**: A `call` node pointing at the workflow it calls, which may live in another file.
- **Source_Ref**: The file path and 1-based line a node came from.
- **Import_Closure**: The entry file plus every file reachable through `import`, transitively.
- **Graph_Panel**: The VS Code webview that draws a Workflow_Graph.
- **Graph_Command**: `weave graph <file>`.
- **Outline_View**: The existing "Workflow Outline" tree.
- **Chip**: A small text badge on a node showing one attribute set on its statement (`retry 3 · 2s`).
- **Details_Card**: The popover that shows an agent's settings for a focused or hovered node.
- **Shared_Renderer**: The single plain-JavaScript module that lays out and draws a graph. It is used by
  the Graph_Panel and by the eval report.
- **Run_Overlay**: The state of each node and edge in one run: taken, missed, unexpected, not visited,
  and how many times a node was visited.
- **Report_Graph**: The graph card in the eval report's trace view.
- **Logo_Assets**: The Loom mark and logo images derived from `loom/ai-agent4j-loom/loom_logo.png`.

---

## Requirements

### Requirement 1: Graph model and builder

**User Story:** As a developer, I want one trusted description of a workflow's control flow, so every
tool that draws it agrees.

#### Acceptance Criteria

1. THE Graph_Builder SHALL produce, for every workflow in a script, a `start` node, one node per
   statement in pre-order, and an `end` node.
2. THE Graph_Builder SHALL emit edges for control flow: sequence; `alt` with `then` and `else` edges;
   `loop` and `foreach` with a body edge, an `again` back-edge and an exit edge; `parallel` as a single
   node standing for its round.
3. THE Graph_Builder SHALL cover every statement type the parser produces, including `call`,
   `foreach`, `guardrail`, `rewind`, `decide`, `observe`, `note` and `broadcast`. A statement type
   it does not know SHALL appear as a generic node, never be dropped.
4. THE Graph_Builder SHALL include nodes for `on_failure`, `on_exhausted` and `on_violation` handlers,
   joined by labelled edges (`failure`, `exhausted`, `violation`).
5. THE node ids for a given workflow SHALL be stable across runs for an unchanged script.
6. EACH Node SHALL carry a Source_Ref when the statement has a line, and SHALL omit it otherwise.
7. EACH Node SHALL carry the attributes set on its statement in `attrs`: `retry`, `backoffMs`,
   `timeoutMs`, `expecting`, `budget` (tokens, calls, cost, perCall, warnAt, window, whenExhausted),
   `maxIterations`, `parallel`, `variable`, `args`, rewind `atMost`, `effects` and `carrying`, checkpoint
   `startingWith`, guardrail `type`, decide `decision` and its `level`. Attributes that are not set SHALL
   be absent, not null.
8. THE graph result SHALL include, for every agent named by a node, the agent's `model`, `temperature`,
   `persona`, tool, MCP, skill and knowledge names, `approve` list or `approveAll`, `budget`,
   `maxIterations` and source line, and the script-level run budget.

### Requirement 2: Imports and calls

**User Story:** As a developer, I want to see workflows from imported files, so I can follow a pipeline
that spans several `.loom` files.

#### Acceptance Criteria

1. THE Graph_Command SHALL load the Import_Closure of the entry file, resolving each `import` path
   relative to the importing file, as `LoomLoader` does.
2. EACH workflow in the output SHALL record the file that defines it.
3. EACH `call` node SHALL record the called workflow's name and, when the callee is defined in the
   Import_Closure, a Call_Link naming the callee's file.
4. WHEN a `call` targets a workflow that is not defined anywhere in the Import_Closure, THE node SHALL
   be marked `unresolved` and the graph SHALL still be produced.
5. WHEN imports form a cycle, THE Graph_Command SHALL stop following the cycle, report the files
   involved as a diagnostic, and still return the graph built so far. It SHALL NOT loop forever.
6. WHEN an imported file is missing or has a syntax error, THE Graph_Command SHALL report it as a
   diagnostic naming that file and line, and SHALL still return the graphs of the files that did parse.
7. WHEN `call` chains form a cycle (recursion), THE graph SHALL show the call once per site and SHALL
   NOT expand the callee inline.
8. THE Graph_Command SHALL list each file in the Import_Closure with the files it imports, so the panel
   can draw the import tree.

### Requirement 3: `weave graph` command

**User Story:** As a developer or tool author, I want the graph from the command line, so I can use it
in CI, docs and the extension.

#### Acceptance Criteria

1. `weave graph <file>` SHALL print JSON on stdout by default (schema version 1).
2. `weave graph <file> --format mermaid` SHALL print a Mermaid `flowchart` for each workflow.
3. `--workflow <name>` SHALL limit output to one workflow; an unknown name SHALL exit 2 with a message.
4. THE command SHALL NOT call a model, embedding, MCP server or secret store, and SHALL NOT need API
   keys or a `.loot` file.
5. THE command SHALL exit 0 when a graph was produced, even with diagnostics, and 2 when none could be
   (entry file missing or unparseable). Diagnostics SHALL be inside the JSON, not mixed into stdout text.
6. THE JSON SHALL contain: `version`, `entry`, `files[]` (path, imports), `workflows[]` (name, file,
   params, nodes, edges), `diagnostics[]` (severity, file, line, message).
7. Paths in the JSON SHALL be absolute and normalised.

### Requirement 4: Eval report reuse

**User Story:** As a maintainer, I want one graph builder, so the report and the editor never disagree.

#### Acceptance Criteria

1. `eval4j-report`'s `WorkflowGraph` SHALL build its nodes and edges from the shared Graph_Builder.
2. THE existing eval-report output SHALL be unchanged for existing scripts: same node ids, kinds,
   labels, edges and bounds, with these exceptions: a loop's exit edge is labelled `done`; a statement
   that used to be a generic `statement` node gets its own kind; and handler blocks and the bodies of
   `for each` and `guardrail` are now nodes. Existing `eval4j-report` tests SHALL pass, with the one
   assertion on the loop exit label updated.
3. ai-agent4j-loom SHALL NOT depend on eval4j-report or eval4j.
4. WHEN a script uses statements the report's graph did not know before (for example `call`, `foreach`,
   `guardrail`), THE report's graph SHALL show them as their own node kinds, not as a generic
   `statement`. See Requirement 10.9 for the schema change this needs.

### Requirement 5: Graph command in VS Code

**User Story:** As a developer, I want to open the graph from the editor.

#### Acceptance Criteria

1. THE extension SHALL contribute `Loom: Show Workflow Graph`, available from the command palette, the
   editor title bar and the editor context menu when the active file is `.loom`.
2. THE command SHALL run the bundled `weave.jar graph <file> --format json` with the same Java
   handling as `Loom: Run Workflow`, and open the Graph_Panel beside the editor.
3. WHEN `java` is not on the PATH or `weave.jar` is missing, THE extension SHALL show an actionable
   error and SHALL NOT open an empty panel.
4. THE command SHALL time out after 30 s and say so.
5. WHEN the file has several workflows, THE panel SHALL show a selector; the workflow containing the
   cursor SHALL be selected first.

### Requirement 6: Graph panel

**User Story:** As a developer, I want a clear, readable diagram.

#### Acceptance Criteria

1. THE Graph_Panel SHALL draw nodes top to bottom with a distinct shape and colour per kind
   (agent steps, tasks, alt, loop, parallel, call, human prompt, checkpoint, start/end), and a legend.
2. THE Graph_Panel SHALL show edge labels and loop bounds (`max 5`).
3. THE Graph_Panel SHALL pan, zoom and fit-to-view, and SHALL be usable with only the keyboard for
   workflow selection.
4. THE Graph_Panel SHALL use VS Code theme colours and remain legible in light, dark and high-contrast
   themes.
5. THE Graph_Panel SHALL load no remote resources. All scripts and styles are bundled with the
   extension and the webview uses a strict Content-Security-Policy with a nonce.
6. THE Graph_Panel SHALL show diagnostics from the command (unresolved calls, cycles, bad imports) in
   a collapsible strip.
7. WHEN a graph has more than 300 nodes, THE panel SHALL collapse nested blocks by default and offer
   expand/collapse per block.
8. THE panel SHALL draw every primitive and attribute as specified in `ui.md` section 3: shape, title,
   subtitle, Chips and extra edges per primitive, with at most three Chips per node and `+n` overflow.
9. THE panel SHALL show a Details_Card with the agent's settings (model, tools, approval rules, budget)
   on hover or keyboard focus of a node that names an agent, and SHALL mark nodes whose agent needs
   approval.
10. THE panel SHALL show the Loom logo in its toolbar, in its tab icon, and in its loading and empty
    states, using bundled Logo_Assets as specified in `ui.md` section 10. The logo SHALL be used unaltered
    and SHALL stay legible and correctly framed in light, dark and high-contrast themes.

### Requirement 7: Navigation and imports in the panel

**User Story:** As a developer, I want to jump between the diagram and the code.

#### Acceptance Criteria

1. Clicking a node with a Source_Ref SHALL open that file at that line, in the editor beside the panel.
2. Clicking a `call` node with a Call_Link SHALL switch the panel to the callee workflow (with a back
   button), and Ctrl/Cmd-click SHALL open the callee's source.
3. THE panel SHALL show which file each workflow comes from and list the Import_Closure as a tree;
   workflows from files other than the entry file SHALL be visibly marked.
4. WHEN the cursor moves in the editor to a line that has a node, THE panel SHALL highlight that node.

### Requirement 8: Refresh

**User Story:** As a developer, I want the diagram to follow my edits.

#### Acceptance Criteria

1. WHEN the entry file or any file in the Import_Closure is saved, THE panel SHALL refresh, keeping the
   selected workflow, zoom and scroll position where the workflow still exists.
2. Refreshes SHALL be debounced (300 ms) and a refresh in flight SHALL be cancelled by a newer one.
3. WHEN a refresh fails, THE panel SHALL keep showing the last good graph with a "stale" banner and the
   error.
4. Closing the panel SHALL stop all watchers and kill any running `weave` process.

### Requirement 9: Quality and packaging

#### Acceptance Criteria

1. THE Graph_Builder and Graph_Command SHALL have unit tests for every statement kind, imports,
   cycles, missing files and unresolved calls.
2. A golden-file test SHALL pin the JSON and Mermaid output for the sample scripts under
   `loom/ai-agent4j-loom/samples/`.
3. THE extension SHALL have tests for the message protocol between the host and the webview and for
   the JSON-to-layout step, and a smoke test that opens the panel for a sample file.
4. THE extension README SHALL document the command and its settings; the Loom docs SHALL document
   `weave graph`.
5. `.vsix` packaging SHALL include the webview assets, the Logo_Assets and the rebuilt `weave.jar`.
6. THE extension manifest SHALL declare the Loom mark as the extension icon.
7. A script SHALL regenerate the Logo_Assets from `loom_logo.png`.

### Requirement 10: Graph in the eval report

**User Story:** As someone reading an eval report, I want to see the workflow as a diagram with what
happened in this run drawn on it, so I can tell at a glance where a run left the expected path.

#### Acceptance Criteria

1. THE eval report SHALL draw the graph of every `WORKFLOW` trace as a card in the trace view, above the
   existing path rows, which stay as they are.
2. THE Report_Graph SHALL be drawn by the Shared_Renderer, so that a node with the same kind and
   attributes has the same shape, glyph, title, subtitle and Chips as in the Graph_Panel.
3. THE Report_Graph SHALL apply a Run_Overlay computed from the trace's `expectedPath`, `actualPath` and
   `events`: nodes on both paths are *taken as expected*, on the expected path only *missed*, on the actual
   path only *unexpected*, and on neither *not visited*. Edges between consecutive nodes of the actual
   path are drawn as *traversed*.
4. WHEN a node appears more than once in `actualPath` (loop iterations, retried steps), THE node SHALL
   show a visit count (`×3`).
5. WHEN a trace has no `expectedPath`, THE overlay SHALL show only *taken* and *not visited*.
6. Overlay state SHALL be carried by a glyph and text as well as colour, using the report's own colour
   tokens, and SHALL stay readable in the report's light and dark themes.
7. SELECTING a node SHALL show its details beside the graph: kind, title, attributes, agent, the events
   mapped to it (time, type, text), its duration when start and end events exist, and the spend of its
   agent, labelled as per agent.
8. THE report SHALL remain one self-contained HTML file. THE Shared_Renderer SHALL be inlined by
   `HtmlRenderer` like `dashboard.js`, SHALL need no build step in `eval4j-report`, and SHALL make no
   network request.
9. THE report's `trace.schema.json` SHALL accept every node kind the Graph_Builder produces and an
   optional `attrs` object on a node. Both copies of the schema SHALL stay identical, and every trace that
   validated before SHALL still validate.
10. THE `WorkflowTrace.Node` record SHALL gain an optional `attrs` map without breaking existing callers.
11. Large graphs SHALL behave as in Requirement 6.7 (collapse above 300 nodes). The report SHALL draw at
    most 500 nodes per trace; a graph over that SHALL show the path rows with a note instead of a diagram.
    The trace schema accepts up to 2,000 nodes so that such a trace is still valid.
12a. THE report SHALL draw a graph when it scrolls into view, and every graph when the page is printed,
    so a report with many workflows opens quickly.
12. THE Report_Graph SHALL say, in a one-line note, that the path is inferred from the order of
    delegations, because the Loom bridge infers it today.
13. THE Report_Graph SHALL NOT show the Loom logo in this version: eval4j is engine neutral, and its
    report carries its own identity.
14. THE Shared_Renderer file SHALL have one canonical source. Copies used by the extension and by the
    report SHALL be generated from it, and a check SHALL fail the build when a copy differs.
