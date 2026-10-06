# Implementation Plan: Loom Workflow Graph

## Overview

Three phases, each shippable on its own:

1. **Core (Java).** Source lines, graph model/builder, import closure, `weave graph`. Usable from the
   CLI after this phase.
2. **Report reuse and report graph.** `eval4j-report` switches to the shared builder with no output change,
   then draws the graph with a run overlay using the shared renderer.
3. **VS Code.** Command, panel, navigation, refresh, packaging and docs.

---

## Tasks

- [x] 0. UI design sign-off
  - [x] 0.1 Review `ui.md` and `mockup.html`; record changes before building the panel
    - _Requirements: 6.1–6.7, 7.1–7.4_

<!-- PHASE 1: ai-agent4j-loom -->

- [x] 1. Source lines in the AST
  - [x] 1.1 Add `getLine()`/`setLine()` to statements (default 0) and `WorkflowDef.line`
    - _Requirements: 1.6_
  - [x] 1.2 Set lines in `LoomParser` where each statement and workflow is built
    - _Requirements: 1.6_
  - [x] 1.3 Parser tests: lines present for every statement kind; existing parser tests unchanged
    - _Requirements: 1.6, 9.1_

- [x] 2. Graph model and builder (`io.github.llm4j.loom.graph`)
  - [x] 2.1 Records: `GraphNode`, `GraphEdge`, `SourceRef`, `CallLink`, `WorkflowGraph`, `ImportFile`,
        `Diagnostic`, `GraphResult`
    - _Requirements: 1.1, 3.6_
  - [x] 2.2 `GraphBuilder`: port `block()` from `eval4j-report`'s `WorkflowGraph`; keep `n1…` pre-order ids
    - _Requirements: 1.1, 1.2, 1.5_
  - [x] 2.3 Cover all statement kinds (`call`, `foreach`, `guardrail`, `rewind`, `decide`, `observe`,
        `note`, `broadcast`) with a generic fallback node
    - _Requirements: 1.3_
  - [x] 2.4 Handler blocks (`on_failure`, `on_exhausted`, `on_violation`) using `StatementWalker.nested`
    - _Requirements: 1.4_
  - [x] 2.5 `attrs` per node and `AgentInfo` per agent, from the AST (retry, backoff, timeout, expecting,
        budget, max, parallel, rewind and checkpoint settings, decision level, agent settings)
    - _Requirements: 1.7, 1.8_
  - [x] 2.6 Unit tests per kind, alt/loop edge shape, id stability, attrs present only when set
    - _Requirements: 1.1–1.8, 9.1_
    - _Requirements: 1.1–1.6, 9.1_

- [x] 3. Import closure
  - [x] 3.1 `ImportClosureLoader`: load entry + imports without merging, same path resolution as
        `LoomLoader`, returns scripts, import edges, diagnostics
    - _Requirements: 2.1, 2.8_
  - [x] 3.2 Cycle detection and skip; missing-file and syntax-error diagnostics with file and line
    - _Requirements: 2.5, 2.6_
  - [x] 3.3 Tests: simple, diamond, cycle (`circular_a/b.loom`), missing file, syntax error
    - _Requirements: 2.1, 2.5, 2.6, 9.1_

- [x] 4. Call resolution
  - [x] 4.1 `CallResolver`: index workflows by name; set `CallLink` or `unresolved`
    - _Requirements: 2.2, 2.3, 2.4_
  - [x] 4.2 Never inline callees; recursion test
    - _Requirements: 2.7_
  - [x] 4.3 Duplicate names: match the runtime's rule (first in run order), warn
    - _Requirements: 2.3_

- [x] 5. `weave graph`
  - [x] 5.1 `MermaidRenderer` (shapes per kind, labelled edges, escaped labels)
    - _Requirements: 3.2_
  - [x] 5.2 `GraphCommand` in `WeaveCLI`: `--format`, `--workflow`, JSON via Jackson, exit codes,
        no model/secret access
    - _Requirements: 3.1–3.7_
  - [x] 5.3 Golden tests for samples (`boardroom`, `content_factory`, `digest`, `imports/parent`)
    - _Requirements: 9.2_
  - [x] 5.4 CLI tests: exit codes, stdout is pure JSON, unknown `--workflow` exits 2
    - _Requirements: 3.3, 3.5, 9.1_

- [x] 6. **Checkpoint — core**: `mvn -pl loom/ai-agent4j-loom test` green; manual `weave graph` on
      `samples/content_factory/main.loom`

<!-- PHASE 2: eval4j-report -->

- [x] 7. Report reuse and report graph
  - [x] 7.1 `WorkflowGraph.of` delegates to `GraphBuilder`, maps to `WorkflowTrace.Node/Edge`
    - _Requirements: 4.1, 4.4_
  - [x] 7.2 Run all `eval4j-report` tests unmodified; compare report output for existing scripts
    - _Requirements: 4.2_
  - [x] 7.3 Confirm no dependency from loom to eval4j/eval4j-report (`mvn dependency:tree`)
    - _Requirements: 4.3_
  - [x] 7.4 `trace.schema.json` (both copies): extend node `kind` with all builder kinds and `statement`,
        add optional `attrs`; schema tests for old traces (still valid), new kinds (valid) and a typo kind
        (invalid)
    - _Requirements: 10.9_
  - [x] 7.5 `WorkflowTrace.Node` gains optional `attrs` with a 5-argument constructor kept; `WorkflowGraph`
        and `LoomTrace` carry `attrs` into the trace JSON
    - _Requirements: 10.10_
  - [x] 7.6 Extract the Shared_Renderer to `loom/graph-render/graph-render.js` (layout, shapes, glyphs,
        Chip text, overlay support) with `node --test` tests; `sync-graph-render.sh` and
        `check-graph-render-sync.sh`; the extension (task 10) uses the generated copy
    - _Requirements: 10.2, 10.14, 6.1, 6.2, 6.8_
  - [x] 7.7 `dashboard.js`: `graphCard(w)` in `traceView` with Run_Overlay (`states`, `visits`,
        `traversed`), legend, details area, fallbacks (`ui.md` 11.1–11.4); map the renderer's CSS variables
        to the report's tokens in `dashboard.css`
    - _Requirements: 10.1, 10.3–10.7, 10.11, 10.12_
  - [x] 7.8 `HtmlRenderer` inlines `graph-render.js`; test that the output is one file with no
        `http(s)://` resource and no `src`/`href` to another file
    - _Requirements: 10.8_
  - [x] 7.9 Report fixtures and tests: all taken, one missed, one unexpected, loop visited 3 times, no
        expected path, 500+ nodes, empty path; one report built from a real `content_factory` eval run
    - _Requirements: 10.3–10.5, 10.11_

- [x] 8. **Checkpoint — full build**: `mvn -q verify` for the affected modules

<!-- PHASE 3: vscode-loom -->

- [x] 9. Extension plumbing
  - [x] 9.1 `package.json`: command, menus (title bar, context), settings, activation on command
    - _Requirements: 5.1_
  - [x] 9.2 `src/graph/model.ts` + `parseGraph()` with version check
    - _Requirements: 3.6, 9.3_
  - [x] 9.3 `src/commands/showGraph.ts`: java/jar resolution (share helper with `runWorkflow.ts`),
        spawn, 30 s timeout, error messages
    - _Requirements: 5.2, 5.3, 5.4_

- [x] 10. Layout and webview (follow `ui.md`)
  - [x] 10.1 Use the generated `media/graph-render.js` from task 7.6 (layering, branch columns,
        back-edge routing); the extension adds no layout of its own
    - _Requirements: 6.1, 6.2, 9.3_
  - [x] 10.2 `media/graph.js` / `graph.css`: panel glue around the renderer: toolbar, pan/zoom/fit,
        legend, theme variables mapped to VS Code colours, keyboard selector
    - _Requirements: 6.1–6.4_
  - [x] 10.3 `GraphPanel.ts`: webview with CSP + nonce, `localResourceRoots`, typed messages
    - _Requirements: 6.5, 5.5_
  - [x] 10.4 Diagnostics strip; collapse blocks above 300 nodes
    - _Requirements: 6.6, 6.7_
  - [x] 10.5 Primitive shapes, glyphs and Chips per `ui.md` section 3; `+n` overflow; Details_Card
        for agents; approval glyph
    - _Requirements: 6.8, 6.9_
  - [x] 10.6 Logo_Assets: `scripts/make-logo-assets.sh`, toolbar mark, tab `iconPath`, loading and
        empty states with reduced-motion handling, high-contrast tile outline
    - _Requirements: 6.10, 9.7_

- [x] 11. Navigation and imports
  - [x] 11.1 `openSource` (validated against `files`), open beside
    - _Requirements: 7.1_
  - [x] 11.2 Call-node drill-down with back button; Ctrl/Cmd-click opens callee source
    - _Requirements: 7.2_
  - [x] 11.3 Import tree and "from file X" marking
    - _Requirements: 7.3_
  - [x] 11.4 Cursor → node highlight
    - _Requirements: 7.4_

- [x] 12. Refresh
  - [x] 12.1 Save watcher over the Import_Closure, 300 ms debounce, cancel in-flight run
    - _Requirements: 8.1, 8.2_
  - [x] 12.2 Preserve selection/zoom/collapse; stale banner on failure
    - _Requirements: 8.1, 8.3_
  - [x] 12.3 Dispose watchers and kill process on close
    - _Requirements: 8.4_

- [x] 13. Quality and ship
  - [x] 13.1 Message-protocol tests (including rejecting `openSource` outside the closure)
    - _Requirements: 9.3_
  - [x] 13.2 Extension integration test: the real `weave.jar` and real processes against a stand-in for the
        `vscode` module, plus browser tests of the page. (A test inside a real VS Code needs a download that is
        not available in the build environment; it stays a manual check, M1 to M15.)
    - _Requirements: 9.3_
  - [x] 13.3 Rebuild `weave.jar` into `bin/`; include `media/**` (with Logo_Assets) in `files`; set
        the manifest `icon`; package `.vsix`
    - _Requirements: 9.5_
  - [x] 13.4 Docs: extension README, `weave graph` in the Loom docs
    - _Requirements: 9.4_

- [ ] 14. **Final checkpoint** (see `verification.md` gates G0–G6): open `examples/tantrik-console/loom-scripts/sdlc/autonomous-dev-cycle.loom`
      and `loom/ai-agent4j-loom/samples/boardroom/main.loom` in the extension; graph, imports, click-through
      and refresh work.
