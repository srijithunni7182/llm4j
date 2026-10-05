# Implementation Plan: Loom Workflow Graph

## Overview

Three phases, each shippable on its own:

1. **Core (Java).** Source lines, graph model/builder, import closure, `weave graph`. Usable from the
   CLI after this phase.
2. **Report reuse.** `eval4j-report` switches to the shared builder with no output change.
3. **VS Code.** Command, panel, navigation, refresh, packaging and docs.

---

## Tasks

- [ ] 0. UI design sign-off
  - [ ] 0.1 Review `ui.md` and `mockup.html`; record changes before building the panel
    - _Requirements: 6.1–6.7, 7.1–7.4_

<!-- PHASE 1: ai-agent4j-loom -->

- [ ] 1. Source lines in the AST
  - [ ] 1.1 Add `getLine()`/`setLine()` to statements (default 0) and `WorkflowDef.line`
    - _Requirements: 1.6_
  - [ ] 1.2 Set lines in `LoomParser` where each statement and workflow is built
    - _Requirements: 1.6_
  - [ ] 1.3 Parser tests: lines present for every statement kind; existing parser tests unchanged
    - _Requirements: 1.6, 9.1_

- [ ] 2. Graph model and builder (`io.github.llm4j.loom.graph`)
  - [ ] 2.1 Records: `GraphNode`, `GraphEdge`, `SourceRef`, `CallLink`, `WorkflowGraph`, `ImportFile`,
        `Diagnostic`, `GraphResult`
    - _Requirements: 1.1, 3.6_
  - [ ] 2.2 `GraphBuilder`: port `block()` from `eval4j-report`'s `WorkflowGraph`; keep `n1…` pre-order ids
    - _Requirements: 1.1, 1.2, 1.5_
  - [ ] 2.3 Cover all statement kinds (`call`, `foreach`, `guardrail`, `rewind`, `decide`, `observe`,
        `note`, `broadcast`) with a generic fallback node
    - _Requirements: 1.3_
  - [ ] 2.4 Handler blocks (`on_failure`, `on_exhausted`, `on_violation`) using `StatementWalker.nested`
    - _Requirements: 1.4_
  - [ ] 2.5 `attrs` per node and `AgentInfo` per agent, from the AST (retry, backoff, timeout, expecting,
        budget, max, parallel, rewind and checkpoint settings, decision level, agent settings)
    - _Requirements: 1.7, 1.8_
  - [ ] 2.6 Unit tests per kind, alt/loop edge shape, id stability, attrs present only when set
    - _Requirements: 1.1–1.8, 9.1_
    - _Requirements: 1.1–1.6, 9.1_

- [ ] 3. Import closure
  - [ ] 3.1 `ImportClosureLoader`: load entry + imports without merging, same path resolution as
        `LoomLoader`, returns scripts, import edges, diagnostics
    - _Requirements: 2.1, 2.8_
  - [ ] 3.2 Cycle detection and skip; missing-file and syntax-error diagnostics with file and line
    - _Requirements: 2.5, 2.6_
  - [ ] 3.3 Tests: simple, diamond, cycle (`circular_a/b.loom`), missing file, syntax error
    - _Requirements: 2.1, 2.5, 2.6, 9.1_

- [ ] 4. Call resolution
  - [ ] 4.1 `CallResolver`: index workflows by name; set `CallLink` or `unresolved`
    - _Requirements: 2.2, 2.3, 2.4_
  - [ ] 4.2 Never inline callees; recursion test
    - _Requirements: 2.7_
  - [ ] 4.3 Duplicate names: match the runtime's rule (verify in `LoomScript.merge`), warn
    - _Requirements: 2.3_

- [ ] 5. `weave graph`
  - [ ] 5.1 `MermaidRenderer` (shapes per kind, labelled edges, escaped labels)
    - _Requirements: 3.2_
  - [ ] 5.2 `GraphCommand` in `WeaveCLI`: `--format`, `--workflow`, JSON via Jackson, exit codes,
        no model/secret access
    - _Requirements: 3.1–3.7_
  - [ ] 5.3 Golden tests for samples (`boardroom`, `content_factory`, `digest`, `imports/parent`)
    - _Requirements: 9.2_
  - [ ] 5.4 CLI tests: exit codes, stdout is pure JSON, unknown `--workflow` exits 2
    - _Requirements: 3.3, 3.5, 9.1_

- [ ] 6. **Checkpoint — core**: `mvn -pl loom/ai-agent4j-loom test` green; manual `weave graph` on
      `samples/content_factory/main.loom`

<!-- PHASE 2: eval4j-report -->

- [ ] 7. Report reuse
  - [ ] 7.1 `WorkflowGraph.of` delegates to `GraphBuilder`, maps to `WorkflowTrace.Node/Edge`
    - _Requirements: 4.1_
  - [ ] 7.2 Run all `eval4j-report` tests unmodified; compare report output for existing scripts
    - _Requirements: 4.2_
  - [ ] 7.3 Confirm no dependency from loom to eval4j/eval4j-report (`mvn dependency:tree`)
    - _Requirements: 4.3_

- [ ] 8. **Checkpoint — full build**: `mvn -q verify` for the affected modules

<!-- PHASE 3: vscode-loom -->

- [ ] 9. Extension plumbing
  - [ ] 9.1 `package.json`: command, menus (title bar, context), settings, activation on command
    - _Requirements: 5.1_
  - [ ] 9.2 `src/graph/model.ts` + `parseGraph()` with version check
    - _Requirements: 3.6, 9.3_
  - [ ] 9.3 `src/commands/showGraph.ts`: java/jar resolution (share helper with `runWorkflow.ts`),
        spawn, 30 s timeout, error messages
    - _Requirements: 5.2, 5.3, 5.4_

- [ ] 10. Layout and webview (follow `ui.md`)
  - [ ] 10.1 `src/graph/layout.ts` (layering, branch columns, back-edge routing) + tests
        (no overlaps, all edges routed)
    - _Requirements: 6.1, 6.2, 9.3_
  - [ ] 10.2 `media/graph.js` / `graph.css`: SVG render, shapes and colours per kind, legend,
        theme variables, pan/zoom/fit, keyboard selector
    - _Requirements: 6.1–6.4_
  - [ ] 10.3 `GraphPanel.ts`: webview with CSP + nonce, `localResourceRoots`, typed messages
    - _Requirements: 6.5, 5.5_
  - [ ] 10.4 Diagnostics strip; collapse blocks above 300 nodes
    - _Requirements: 6.6, 6.7_
  - [ ] 10.5 Primitive shapes, glyphs and Chips per `ui.md` section 3; `+n` overflow; Details_Card
        for agents; approval glyph
    - _Requirements: 6.8, 6.9_
  - [ ] 10.6 Logo_Assets: `scripts/make-logo-assets.sh`, toolbar mark, tab `iconPath`, loading and
        empty states with reduced-motion handling, high-contrast tile outline
    - _Requirements: 6.10, 9.7_

- [ ] 11. Navigation and imports
  - [ ] 11.1 `openSource` (validated against `files`), open beside
    - _Requirements: 7.1_
  - [ ] 11.2 Call-node drill-down with back button; Ctrl/Cmd-click opens callee source
    - _Requirements: 7.2_
  - [ ] 11.3 Import tree and "from file X" marking
    - _Requirements: 7.3_
  - [ ] 11.4 Cursor → node highlight
    - _Requirements: 7.4_

- [ ] 12. Refresh
  - [ ] 12.1 Save watcher over the Import_Closure, 300 ms debounce, cancel in-flight run
    - _Requirements: 8.1, 8.2_
  - [ ] 12.2 Preserve selection/zoom/collapse; stale banner on failure
    - _Requirements: 8.1, 8.3_
  - [ ] 12.3 Dispose watchers and kill process on close
    - _Requirements: 8.4_

- [ ] 13. Quality and ship
  - [ ] 13.1 Message-protocol tests (including rejecting `openSource` outside the closure)
    - _Requirements: 9.3_
  - [ ] 13.2 `@vscode/test-electron` smoke test on a sample with imports
    - _Requirements: 9.3_
  - [ ] 13.3 Rebuild `weave.jar` into `bin/`; include `media/**` (with Logo_Assets) in `files`; set
        the manifest `icon`; package `.vsix`
    - _Requirements: 9.5_
  - [ ] 13.4 Docs: extension README, `weave graph` in the Loom docs
    - _Requirements: 9.4_

- [ ] 14. **Final checkpoint** (see `verification.md` gates G0–G6): open `examples/tantrik-console/loom-scripts/sdlc/autonomous-dev-cycle.loom`
      and `loom/ai-agent4j-loom/samples/boardroom/main.loom` in the extension; graph, imports, click-through
      and refresh work.
