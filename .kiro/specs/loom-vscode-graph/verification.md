# Verification Plan: Loom Workflow Graph

## Purpose

This plan defines when the Loom workflow graph is **done**: the checks that prove every acceptance
criterion in `requirements.md` and every rule in `ui.md`, the exact values they must produce, and the gate
each phase in `tasks.md` must pass before the next begins.

A check passes only when its assertion holds as written. The graph is deterministic for a given script,
so "looks about right" is a failure. Evidence for each gate is saved in `evidence/` (section 9).

---

## 1. Fixtures

Existing scripts are reused. New ones live in `src/loom/ai-agent4j-loom/src/test/resources/graph/`.

| Fixture | Content | Used for |
|---|---|---|
| `imports/parent.loom` → `child.loom` | one `call ChildWorkflow()` | simple import and call link |
| `imports/circular_a.loom` ↔ `circular_b.loom` | each imports the other | cycle handling |
| `samples/content_factory/main.loom` + `primitives.loom` | delegate, parallel, human_prompt, alt, note | golden output, real-world import |
| `samples/boardroom/main.loom` + `members.loom` | multi-file with agents | golden output |
| `samples/digest/digest.loom` + `core.loom` | multi-file | golden output |
| `graph/all_statements.loom` | one workflow using every statement kind and every attribute in `ui.md` section 3 | node kinds and `attrs` |
| `graph/handlers.loom` | `on_failure`, `on_exhausted`, `on_violation` blocks | handler edges |
| `graph/diamond/` | `a` imports `b` and `c`; both import `d` | diamond imports, `d` listed once |
| `graph/missing_import.loom` | imports a file that does not exist | diagnostic, partial graph |
| `graph/syntax_error_import/` | entry is valid, imported file has an error at line 5 | diagnostic with file and line |
| `graph/unresolved_call.loom` | `call Nowhere()` | unresolved node |
| `graph/recursive.loom` | workflow `A` calls `A` | no inline expansion |
| `graph/duplicate_names/` | `Shared` defined in two imported files | first-in-run-order rule and warning |
| `graph/large_400.loom` | generated: 400 statements in nested blocks | collapse, performance |
| `report/` fixtures (`src/eval4j-report/src/test/resources/graph/`) | trace JSONs: `all-taken`, `one-missed`, `one-unexpected`, `loop-x3`, `no-expected`, `big-501`, `empty-path`, `old-kinds-only` | report graph and overlay |
| `graph/hostile_labels.loom` | notes and agent names containing `<img src=x onerror=alert(1)>`, quotes, `</script>`, 5,000-character strings | escaping, truncation |

JSON fixtures for the extension (`vscode-loom/test/fixtures/`) are produced by `weave graph` from the
scripts above and committed, so extension tests never need Java.

---

## 2. Verification Matrix

IDs are `V<requirement>.<n>`. The tests that implement them are named in each heading.

### Requirement 1: Graph model and builder
*Tests: `GraphBuilderTest`, `GraphAttrsTest`, `ParserLinesTest`*

| ID | Scenario | Pass condition |
|---|---|---|
| V1.1 | `content_factory` `GenerateContent` | nodes are exactly `start, n1…n6, end` (8); edges are exactly 8: `start→n1, n1→n2, n2→n3, n3→n4, n4→n5 [then], n4→n6 [else], n5→end, n6→end` |
| V1.2a | `alt` with both branches | two labelled edges `then` and `else` out of the alt node; both branch exits join the next node |
| V1.2b | `loop` with a 2-statement body | edges: loop→first body node, last body node→loop `[again]`, loop→next `[done]`; no other edges |
| V1.2c | `for each` and `parallel for each` | same shape as `loop`; `attrs.parallel` is `true` only for the second |
| V1.2d | `parallel` with 3 delegates | **one** node, `agent` set is the 3 targets; no child nodes |
| V1.3 | `all_statements.loom` | every statement kind yields a node whose `kind` equals the name in `ui.md` 3.1 (a `run` statement is kind `task`, the name the report already used); an injected unknown `Statement` subclass yields `kind == "unknown"` and is not dropped |
| V1.4 | `handlers.loom` | handler nodes exist; edges from owner are labelled `failure`, `exhausted`, `violation`; handler blocks do not appear in the main path |
| V1.5 | build the same script 100 times, in 8 threads | identical JSON every time (byte for byte) |
| V1.6a | every statement type has a line | `source.line` equals the line in the file for each node in `all_statements.loom` |
| V1.6b | a hand-built AST statement with no line | node has no `source` key (absent, not `0` or null) |
| V1.7 | `all_statements.loom` | `attrs` has exactly the keys set in the script: `retry`, `backoffMs`, `timeoutMs`, `expecting`, `budget{tokens,calls,cost,perCall,warnAt,window,whenExhausted}`, `text`, `condition`, `agents`, `branches`, `task`, `item`, `collection`, `parallel`, `variable`, `args`, `name`, `startingWith`, `target`, `atMost`, `effects`, `carrying`, `type`, `decision`, `level` (a loop's maximum is the node's `bound`, not an attribute); a statement with none set has no `attrs` key; no value is `null` |
| V1.8 | `content_factory` | `agents` lists `Researcher` and `Copywriter` with `model`, `tools`, `approve`, `budget`, `maxIterations`, and `source.line` equal to the `agent` line; the script-level run budget is present when declared and absent otherwise |

### Requirement 2: Imports and calls
*Tests: `ImportClosureLoaderTest`, `CallResolverTest`, `GraphImportsCliTest`*

| ID | Scenario | Pass condition |
|---|---|---|
| V2.1 | `imports/parent.loom` | `files` has 2 entries; `parent` imports `[child]`; paths are resolved relative to the importing file |
| V2.1b | `diamond/` | `d` appears once in `files`; both `b` and `c` list it in `imports` |
| V2.2 | any multi-file script | every workflow has `file`; no workflow has a `file` outside `files` |
| V2.3 | `imports/parent.loom` | the `call` node has `call == {workflow: "ChildWorkflow", file: <child>}` and no `unresolved` |
| V2.4 | `unresolved_call.loom` | node has `unresolved: true`, no `call.file`; exit code 0; JSON is complete |
| V2.5 | `circular_a.loom` | terminates in under 2 s; one diagnostic naming both files; graphs for both files are present; exit 0 |
| V2.6a | `missing_import.loom` | diagnostic with severity `error`, the missing path and the line of the `import`; entry's workflows still present |
| V2.6b | `syntax_error_import/` | diagnostic names the imported file and line 5; entry's graph present; the imported file's workflows absent |
| V2.7 | `recursive.loom` | `A` has one `call` node pointing to `A`; node count is 3 (`start, n1, end`); output is finite |
| V2.8 | `diamond/` | `files[].imports` forms the tree used by the panel and matches the real `import` lines |
| V2.9 | `duplicate_names/` | the call links to the definition that comes first in run order (imports before the importing file's own definitions), which is the one `HarnessExecutor` runs; one `warning` diagnostic names both files; a test loads the script with `LoomLoader` and shows the same definition wins |

### Requirement 3: `weave graph` command
*Tests: `GraphCommandTest`, `GraphCliNoSecretsTest`*

| ID | Scenario | Pass condition |
|---|---|---|
| V3.1 | `weave graph samples/content_factory/main.loom` | stdout parses as JSON; `version == 1`; **nothing** else on stdout |
| V3.2 | `--format mermaid` | output starts with `flowchart TD`; every node id appears; every edge appears with its label; the text renders with the Mermaid CLI without error (CI job) |
| V3.3 | `--workflow ReviseContent` / `--workflow Nope` | one workflow only / exit 2 and a message listing valid names |
| V3.4 | run with no environment variables, no network, no `.loot` | succeeds; a `WeaveEnv` spy records **zero** calls to `models()`, `secrets()`, MCP and embedding factories |
| V3.5a | entry file missing | exit 2 |
| V3.5b | entry has a syntax error | exit 2, message with file and line, no partial JSON on stdout |
| V3.5c | partial success (V2.6a) | exit 0 |
| V3.6 | JSON schema | validates against `graph-result.schema.json` (committed); required keys present; unknown keys rejected in the test schema |
| V3.7 | any run | every path is absolute and normalised (no `..`, no relative paths) |
| V3.8 | golden files for the three samples and `imports/parent` | JSON and Mermaid match committed files exactly; regenerating changes nothing |

### Requirement 4: Eval report reuse
*Tests: existing `eval4j-report` suite, `WorkflowGraphParityTest`*

| ID | Scenario | Pass condition |
|---|---|---|
| V4.1 | `WorkflowGraph.of` | calls `GraphBuilder` (verified by a code-structure test: no private copy of `block()` remains) |
| V4.2a | every existing `eval4j-report` test | all pass. Exactly one assertion changes, on purpose: `LoomBridgeTest.graphHasStableNodeIdsAndControlFlowEdges` expects the loop's exit edge as `n3->n6:done` (it was unlabelled), because the report's renderer needs the label to tell an exit from the body. No other existing assertion changes |
| V4.2b | parity: for every `.loom` under `src/test/resources`, `samples/` and `src/examples/`, old output (from the pre-change commit, stored as golden) vs new `WorkflowGraph` output | node ids, kinds, labels, bounds and edges are identical for every statement kind the old code handled; differences are only handler nodes, new-kind nodes in place of the old generic `statement`, and the label `done` on loop exits; workflows whose ids move because a `for each` or `guardrail` body now has nodes are listed explicitly in the test |
| V4.3 | `mvn dependency:tree` for `ai-agent4j-loom` | does not contain `eval4j-report`; `eval4j` itself is a dependency only since `loom-weave-eval`, and only `io.github.llm4j.loom.eval` and `EvalCommand` use it (`ModuleBoundaryTest`) |
| V4.4 | script using `call`, `foreach`, `guardrail` through `WorkflowGraph.of` | those nodes have kinds `call`, `foreach`, `guardrail`, not `statement`; the resulting trace validates against `trace.schema.json` |

### Requirement 5: Command in VS Code
*Tests: `showGraph.test.ts`, `@vscode/test-electron` smoke*

| ID | Scenario | Pass condition |
|---|---|---|
| V5.1 | extension manifest | command `loom.showGraph` declared; present in `commandPalette`, `editor/title` and `editor/context` menus, each with `when: resourceLangId == loom` |
| V5.2 | integration test on `content_factory/main.loom` with the real `weave.jar` and a stand-in for the `vscode` module | panel opens in `ViewColumn.Beside`; after the `java -version` check, a spy shows exactly one graph process: `java -jar …/weave.jar graph <file> --format json` |
| V5.3a | `java` not on PATH | error notification with a **Open Settings** action; **no** panel created |
| V5.3b | `weave.jar` missing | error notification; no panel created |
| V5.4 | fake process that never exits | killed at 30 s (fake timers); message says it timed out; no panel left open |
| V5.5 | file with 2 workflows, cursor inside the second | selector lists both; the second is selected |

### Requirement 6: Panel
*Tests: `graph-render.test.js` (shared with Requirement 10), `render.test.ts` (Playwright on the webview HTML), accessibility scan, manual review (section 5)*

| ID | Scenario | Pass condition |
|---|---|---|
| V6.1 | render `all_statements` JSON | each kind has the shape, glyph and kind class in `ui.md` 3.1 (asserted on the SVG: `polygon` for alt, double side paths for call, double top bars for parallel, dashed outline for note and unknown) |
| V6.2 | node with `max 3` | chip text is exactly `max 3`; edge labels `then`, `else`, `again` present |
| V6.3 | keyboard only | Tab reaches the selector, toolbar buttons and the first node; ArrowDown/Up move along nodes; Enter selects; focus ring has contrast ≥ 3:1 |
| V6.4 | themes | screenshots in light, dark and high-contrast show no text below 4.5:1 contrast (axe + custom check on node text against fill) |
| V6.5 | CSP | the HTML has `default-src 'none'`, a nonce on every script, no `unsafe-inline`; with the network blocked, the panel renders fully; zero requests leave `vscode-webview` origin |
| V6.6 | diagnostics | strip shows counts; expanding lists each row; clicking a row sends `openSource` with that file and line |
| V6.7 | `large_400.loom` | blocks are collapsed on load; the visible node count is below 300; expanding one block adds exactly its children |
| V6.8 | every row of `ui.md` 3.1 and 3.2 | table-driven test: given the JSON node, the rendered title, subtitle, chips (≤ 3, then `+n`), and extra edges equal the table's values |
| V6.9 | hover and focus on a node with an agent | details card shows model, temperature, persona, tools, approval, budget, max iterations, source; a node whose agent lists `approve` shows the hand glyph; Escape closes the card |
| V6.10 | logo | toolbar shows the mark in a 36 px tile with a 28 px image; tab `iconPath` is set; loading and empty states show the 160 px logo; the image is the bundled file via `asWebviewUri` (no `http`); pulse is off under `prefers-reduced-motion`; tile has a 1 px outline in high contrast |

### Requirement 7: Navigation and imports in the panel
*Tests: `navigation.test.ts`, smoke*

| ID | Scenario | Pass condition |
|---|---|---|
| V7.1 | click a node with `source` | host receives `openSource{file,line,beside:true}`; the editor reveals that line |
| V7.2a | double-click a `call` node with a link | panel shows the callee; breadcrumb is `Caller › Callee`; Back restores the caller with zoom and selection unchanged |
| V7.2b | Ctrl/Cmd-click the same node | editor opens the callee file at its definition; panel does not change |
| V7.3 | multi-file | files list shows the import tree; workflows from non-entry files carry the file chip |
| V7.4 | move the editor cursor to a line with a node | that node is highlighted within 200 ms; moving to a line with no node clears it |

### Requirement 8: Refresh
*Tests: `refresh.test.ts`, smoke*

| ID | Scenario | Pass condition |
|---|---|---|
| V8.1 | save the entry file, then an imported file | panel refreshes both times; selected workflow, zoom and scroll are unchanged when the workflow still exists |
| V8.2 | 10 saves within 250 ms | exactly **one** `weave` run starts; if a run is in flight when a newer save arrives, the old process is killed |
| V8.3 | introduce a syntax error, save | last good graph remains; stale banner shows the error and **Open file**; fixing the error removes it |
| V8.4 | close the panel mid-run | process is killed; no file watcher remains (`dispose` called on all); no further `weave` starts on later saves |

### Requirement 9: Quality and packaging
| ID | Scenario | Pass condition |
|---|---|---|
| V9.1 | coverage | `io.github.llm4j.loom.graph` line coverage ≥ 90 %, branch ≥ 80 % (JaCoCo); `graph-render.js` and `model.ts` ≥ 90 % |
| V9.2 | golden files | present for the four fixtures in V3.8; CI fails on a diff |
| V9.3 | extension tests | message-protocol, layout and smoke suites run in CI (`npm test`) |
| V9.4 | docs | extension README documents the command and the three settings; Loom docs document `weave graph` with an example; a doc test runs the README's example command and compares output |
| V9.5 | `.vsix` | `vsce ls` lists `media/graph.js`, `media/graph.css`, both logo files and `bin/weave.jar`; installing the `.vsix` into a clean VS Code and running the command works |
| V9.6 | manifest | `"icon"` points to a file that exists in the package |
| V9.7 | `scripts/make-logo-assets.sh` | regenerates both logo files; outputs are 128×128 and 320×320; running it twice produces identical files |

### Requirement 10: Graph in the eval report
*Tests: `TraceSchemaTest`, `WorkflowTraceNodeTest`, `graph-render.test.js` (`node --test`), `report-graph.test.js` (Playwright on the generated HTML), `ReportSingleFileTest`, `GraphRenderSyncTest`*

| ID | Scenario | Pass condition |
|---|---|---|
| V10.1 | report generated from `report/all-taken` | the trace view contains one graph card above the path rows; the path rows are present and unchanged (same text as before this change) |
| V10.2 | the same node JSON drawn by the panel and by the report | SVG shape element type, glyph path, title, subtitle and Chip texts are identical (compared by a test that renders both with the shared module and diffs a normalised DOM) |
| V10.3a | `all-taken` | every node on the path has the `✓` mark; all edges between consecutive path nodes have class `traversed`; no `missed` or `unexpected` marks |
| V10.3b | `one-missed` (expected `start,n1,n2,end`; actual `start,n1,end`) | `n2` has the `✗ missed` chip and a dashed outline; edges `n1→n2` and `n2→end` are not traversed |
| V10.3c | `one-unexpected` | the extra node has the `! unexpected` chip |
| V10.4 | `loop-x3` (loop node three times in `actualPath`) | the loop node shows `×3`; a node visited once shows no count |
| V10.5 | `no-expected` | nodes show `●` or not-visited only; no `✓`, `✗` or `!` anywhere |
| V10.6 | themes | in the report's light and dark themes, overlay text meets 4.5:1 contrast; each state is identifiable with colours removed (greyscale screenshot check: glyph and text present) |
| V10.7 | click a node with 3 mapped events | details area lists exactly those 3 events with time, type and text; shows duration when a start and end event exist; spend line says "per agent" |
| V10.8 | generated report HTML | one file; contains `graph-render.js` inline; no `http://` or `https://` URL in any `src`, `href` or `url()` (the existing allowlisted `$id` strings in data are excluded by the test); opening it with the network blocked renders the graph |
| V10.9a | every `old-kinds-only` trace stored before this change | validates against the new schema |
| V10.9b | trace with each new kind, and `statement` | validates; a trace with kind `delegat` (typo) fails |
| V10.9c | `spec/schema/trace.schema.json` vs `src/test/resources/schema/trace.schema.json` | byte-identical |
| V10.10 | compile every existing caller of `new WorkflowTrace.Node(id, kind, label, agent, bound)` | compiles without edits; `attrs` is empty |
| V10.11a | `big-501` | no diagram; note "Graph too large to draw (501 nodes)"; path rows present |
| V10.11b | 400-node graph in the report | collapsed on load; same behaviour as V6.7 |
| V10.12 | any Loom trace card | the one-line note "Path is inferred from the order of delegations." is present |
| V10.13 | rendered report | no Loom logo image or data URI for it anywhere in the HTML |
| V10.14 | edit one copy of `graph-render.js` | `check-graph-render-sync.sh` and `GraphRenderSyncTest` both fail; restored, both pass |
| V10.15 | `empty-path` / no graph nodes | empty path: steps on the expected path show as missed, the others as not visited, plus "No steps were recorded."; no nodes: no card and no error in the console |
| V10.16 | `trace_with_unplaced_events` | the line "n events could not be placed on a step" shows the right n |

---

## 3. Cross-Cutting Checks

### 3.1 Security
*Tests: `hostile_labels.test.ts`, `openSource.test.ts`*

| ID | Scenario | Pass condition |
|---|---|---|
| VS.1 | `hostile_labels.loom` rendered | no element with `onerror` or injected tag exists in the DOM; text appears literally; no script runs (a global flag stays unset) |
| VS.2 | webview sends `openSource` for `/etc/passwd`, a `..` path, and a file in the workspace but not in `files` | host refuses all three and logs one warning; no editor opens |
| VS.3 | malformed JSON, wrong `version`, 20 MB output | `parseGraph` rejects with a clear message; the host does not crash; output above 8 MB is refused before parsing |
| VS.4 | `weave graph` on a script containing a secret-looking value | the value never appears in stdout or stderr |
| VS.5 | `localResourceRoots` | contains only the extension's `media` directory |

### 3.2 Performance
*Tests: `GraphPerfTest`, `layout.perf.test.ts`; numbers from a CI runner, 3 runs, worst of three*

| ID | Scenario | Budget |
|---|---|---|
| VP.1 | `weave graph` on `content_factory` (cold JVM) | ≤ 3.0 s end to end |
| VP.2 | `GraphBuilder` on `large_400.loom` in-process | ≤ 200 ms |
| VP.3 | `LoomGraph.layout` on 400 nodes / 1,000 nodes (the same module in panel and report) | ≤ 300 ms / ≤ 1.0 s |
| VP.7 | report with 20 workflow traces of 100 nodes each | opens and draws the first graph in ≤ 1.0 s; the HTML grows by no more than 120 KB over the same report without graphs, excluding data |
| VP.4 | first paint after JSON arrives, 400 nodes (collapsed) | ≤ 500 ms |
| VP.5 | pan and zoom on 400 nodes | median frame ≤ 20 ms |
| VP.6 | refresh after save (JVM start included) | ≤ 3.5 s from save to updated graph, including the 300 ms debounce |

A budget breach is a failure, not a warning.

### 3.3 Robustness

| ID | Scenario | Pass condition |
|---|---|---|
| VR.1 | script path with spaces, Unicode, and Windows separators | works; paths in JSON round-trip |
| VR.2 | empty `.loom` file; file with only agents | exit 0, `workflows: []`; panel shows the "no workflows" empty state with the logo |
| VR.3 | workflow with zero statements | graph is `start → end` |
| VR.4 | 5,000-character label | node title cut to the `ui.md` limit with `…`; full text in tooltip and details |
| VR.5 | `weave graph` run twice in parallel on the same file | both succeed with identical output |
| VR.6 | read-only filesystem | succeeds; the command writes no files |

---

## 4. Regression

| ID | Check | Pass condition |
|---|---|---|
| VG.1 | `mvn -q verify` for `ai-agent4j-loom`, `eval4j-report`, `eval4j` | green, no test skipped or edited to pass |
| VG.2 | existing `weave check`, `weave run`, `weave audit` tests | unchanged and green |
| VG.3 | Outline view and `Loom: Run Workflow` | unchanged behaviour (existing tests plus the smoke test opens both) |
| VG.4 | parser | every `.loom` in the repo still parses; AST equality tests are unchanged apart from added lines |
| VG.5 | sabotage | each of these deliberately breaks the build, and the named check fails: remove the `then`/`else` label (V1.2a); drop handler nodes (V1.4); skip cycle detection (V2.5); log a secret (VS.4); put `innerHTML` back (VS.1); remove the debounce (V8.2); make the report ignore `expectedPath` (V10.3b); drop `×n` (V10.4); let one renderer copy drift (V10.14). Each is reverted after |

---

## 5. Manual UI Acceptance

Run by a person on the packaged `.vsix`, comparing against `mockup.html`. Each line is pass or fail with a
screenshot saved to `evidence/`. Do it in light, dark and high-contrast themes.

| ID | Step | Expected |
|---|---|---|
| M1 | Open `content_factory/main.loom`, run **Loom: Show Workflow Graph** from the palette, the title bar and the context menu | panel opens beside the editor each time; Loom mark in the toolbar and tab |
| M2 | Compare with the mockup's Normal state | same regions: toolbar, diagnostics strip, canvas, files, selected details; same node shapes, colours and chips |
| M3 | Click each node | editor highlights the matching line; details row matches `ui.md` |
| M4 | Double-click the call node (use the edited sample from the mockup) | callee opens, breadcrumb and Back work, editor switches to the imported file |
| M5 | Hover the agent node | details card appears with the right values; Escape closes it |
| M6 | Edit and save the entry file, then the imported file | graph updates; zoom and selection stay |
| M7 | Introduce a syntax error | stale banner with the error; graph kept; fix clears it |
| M8 | Rename the called workflow | unresolved call drawing and error diagnostic, as in the mockup |
| M9 | Open `src/examples/tantrik-console/loom-scripts/sdlc/autonomous-dev-cycle.loom` | readable at Fit; no overlapping nodes or labels; every node kind is recognisable from the legend |
| M10 | Open `large_400.loom` | collapsed blocks; expand and collapse work; pan and zoom stay smooth |
| M11 | Narrow the panel to about 400 px | toolbar wraps without overlap; no horizontal scrollbar on the page; graph still pannable |
| M12 | Keyboard only | every control reachable; focus always visible |
| M13 | Close the panel, edit and save | nothing runs (check Task Manager / `ps` for stray `java`) |
| M14 | Loading state (add a 3 s delay with `LOOM_GRAPH_DELAY_MS`) | centred logo with pulse, then graph; pulse stops with reduced motion on |
| M15 | Run an eval of `content_factory` with a failing step and a retry; open the generated report with the network off | the trace shows the graph card with ✓, ✗ missed and `×n` marks that match what happened; click nodes for details; the note about the inferred path is visible; the same node looks the same as in the panel; light and dark themes both readable |

A reviewer other than the author runs M1–M15. Any failure blocks the gate.

---

## 6. Phase Gates

| Gate | After | Must pass | Evidence |
|---|---|---|---|
| **G0** | UI sign-off (task 0) | `ui.md` and `mockup.html` reviewed; open comments resolved | `evidence/G0-signoff.md` |
| **G1** | core, tasks 1–6 | V1.\*, V2.\*, V3.\*, V9.1, V9.2, VS.4, VP.1, VP.2, VR.1–VR.3, VR.5, VR.6 | `evidence/G1-core.txt` (test output, coverage) |
| **G2** | report reuse and report graph, tasks 7–8 | V4.\*, V10.\*, VP.7, VG.1, VG.2, VG.4, M15 | `evidence/G2-regression.txt`, `evidence/G2-report-screens/` |
| **G3** | extension logic, tasks 9–12 | V5.\*, V6.1–V6.10, V7.\*, V8.\*, VS.1–VS.3, VS.5, VP.3–VP.6, VR.4 | `evidence/G3-extension.txt`, screenshots in 3 themes |
| **G4** | packaging, task 13 | V9.3–V9.7, VG.3 | `evidence/G4-package.txt`, `vsce ls` output |
| **G5** | acceptance, task 14 | all of section 5 (M1–M15) by a second person; VG.5 sabotage run | `evidence/G5-manual.md`, `evidence/G5-sabotage.md` |
| **G6** | release | every check in sections 2–4 green on one commit; traceability (section 7) has no gaps | `evidence/G6-final.txt` |

A gate that fails is fixed in the code, never by editing the check. If a check is wrong, change it in a
separate commit with the reason, and re-run the gate from the start.

---

## 7. Traceability

Every acceptance criterion maps to at least one check. G6 verifies this with a script that parses
`requirements.md` and this file.

| Requirement | Checks |
|---|---|
| 1.1–1.8 | V1.1–V1.8 |
| 2.1–2.9 | V2.1–V2.9 |
| 3.1–3.7 | V3.1–V3.8 |
| 4.1–4.4 | V4.1–V4.4 |
| 5.1–5.5 | V5.1–V5.5 |
| 6.1–6.10 | V6.1–V6.10, M2, M9, M11, M12, M14 |
| 7.1–7.4 | V7.1–V7.4, M3, M4 |
| 8.1–8.4 | V8.1–V8.4, M6, M7, M13 |
| 9.1–9.7 | V9.1–V9.7 |
| 10.1–10.14 | V10.1–V10.16, VP.7, M15 |
| `ui.md` sections 3–10 | V6.1, V6.8–V6.10, M2–M5, M8 |
| `ui.md` section 11 | V10.1–V10.7, V10.11, V10.15, V10.16, M15 |

---

## 8. Definition of Done

The feature is done when:
1. Gates G0 to G6 have passed on one commit, with evidence saved.
2. The sabotage run (VG.5) has been done and each deliberate break was caught by its named check.
3. A person who did not write the code has completed M1–M15 and signed `evidence/G5-manual.md`.
4. The `.vsix` installed in a clean VS Code opens the graph for the three sample scripts.
5. No check was skipped, disabled or loosened to get green.

## 9. Evidence Layout

```
.kiro/specs/loom-vscode-graph/evidence/
  G0-signoff.md        G1-core.txt          G2-regression.txt
  G2-report-screens/{light,dark}/*.png
  G3-extension.txt     G3-screens/{light,dark,hc}/*.png
  G4-package.txt       G5-manual.md         G5-sabotage.md
  G6-final.txt         traceability.txt
```

## 10. Status at hand-over

| Gate | State | Evidence |
|---|---|---|
| G1 core | passed (graph package: 98.0 % lines, 86.6 % branches) | `evidence/G1-core.txt` |
| G2 report and regression | passed | `evidence/G2-regression.txt` |
| G3 extension logic | passed (fake `vscode` with the real `weave.jar`) | `evidence/G3-extension.txt` |
| G4 package | passed (`.vsix` built, contents checked, bundled jar run, Mermaid parsed) | `evidence/G4-package.txt` |
| G5 sabotage (VG.5) | all 14 breaks caught | `evidence/G5-sabotage.md` |
| G6 traceability | 71 of 71 criteria, 114 of 114 check IDs named by a test or script | `evidence/G6-final.txt`, `evidence/traceability.txt` |

Not done, and needs a person: **M1–M15 and task 14** (G5-manual, a second person on a real VS Code), and the
`.vsix` smoke test in a clean VS Code (Definition of Done 3 and 4). VS Code could not be downloaded in the build
environment, so no `@vscode/test-electron` run was possible. G0 (sign-off) is the user's.
