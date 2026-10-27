# Phase 10 verification plan: the editor experience

Status: **10a done** (`weave serve`, the engine client, check and graph through the engine). **10b done in code and automated tests; waiting for the manual check in VS Code** (the Review panel in Split, squiggles, status bar, CodeLens, the run notice). **10b2 done in code and tests** (Outline and List layouts, the switch, context kept). 10c to 10e not started. Run top to bottom at the end of sub-phase 10e; the relevant rows after each sub-phase. Results in §7.

## 1. Acceptance criteria

| # | Criterion | Evidence |
|---|---|---|
| A1 | From a `.loom` file I can open a review with one click (or one palette command) and see the graph and the findings together, without typing a `weave` command | `review.browser.test.js`, `extension.integration.test.ts` |
| A2 | Clicking a finding selects its node and reveals its source line; clicking a node shows its findings and cost; moving the cursor highlights the node | `review.browser.test.js`, `reviewModel.unit.test.js` |
| A3 | I can see cost (typical and worst) on each model step, in the summary, in a CodeLens and in the status bar | `review.browser.test.js`, `reviewCodeLens.test.ts` |
| A4 | A SAFE fix applies with one click; Fix all safe applies all as one undo step; a REVIEW fix shows a diff first | `reviewCodeActions.test.ts`, `applyFix.integration.test.ts` |
| A5 | Findings appear as squiggles and Problems entries with the rule id, and Explain opens the rule's page | `reviewDiagnostics.test.ts` |
| A6 | Feedback is fast: warm instant tier p95 < 150 ms, full review p95 < 1 s, completion and hover p95 < 100 ms, cold start < 4 s | `latency.test.ts` (§7, 10e: 45 ms, 54 ms, 13 ms, 883 ms) |
| A7 | One engine process serves the window; a crash restarts it; stale results are never shown as current | `engineClient.test.ts`, `ServeRobustnessTest` |
| A8 | Go to definition, references, rename (all-or-nothing), typed completion (`intent.` offers the classifier's fields) and hover work from the Java parser's index | `SymbolsTest`, `semantic*.test.ts`, `renameAtomic.test.ts` |
| A9 | With no Java, a dead engine, or a version mismatch the extension degrades with one clear message and keeps working | `degraded.test.ts` |
| A10 | The panel is keyboard-operable, readable in light, dark and high-contrast, and never builds HTML from script text | `a11y.browser.test.js`, `hostile.browser.test.js` |
| A11 | `weave serve` runs nothing, writes nothing and reads only the workspace | `ServeIsStaticTest`, `ServeWorkspaceBoundaryTest` |
| A12 | Existing graph, Problems, outline, snippets and guide commands behave as before | existing tests, unmodified |
| A13 | No network use, no telemetry | `noNetwork.test.ts` |
| A14 | I can triage by severity and lens: fix one, fix all safe in a group, accept (with a reason and a scope shown by count), dismiss locally, or accept all existing as a baseline, each in one or two clicks and each undoable | `triage.browser.test.js`, `triageActions.test.ts` |
| A15 | Ignoring is never silent or lossy: handled findings stay counted and restorable, dismiss never reaches CI or the repository, serious findings need their guardrails | `dismissLocal.test.ts`, `triageActions.test.ts` |
| A16 | The whole triage flow works from the keyboard and with a screen reader | `triageKeyboard.browser.test.js`, `a11y.browser.test.js` |
| A17 | Open findings never stop Run, debug or any command in the editor; I see a non-modal heads-up once and a standing status-bar count | `runNotBlocked.test.ts`, `runNoticeOnce.test.ts` |
| A18 | The panel opens in Split and offers Outline and List in the same panel, and I can switch between them without losing my place, selection or filters | `layoutSwitch.browser.test.js`, `layoutContext.unit.test.js` |
| A19 | All layouts show the same findings, counts and actions; layout changes nothing about what is reviewed | `layoutSameModel.unit.test.js` |
| A20 | Documented, with screenshots (the three layouts, light and dark) | docs review (§5) |

## 2. Traceability: requirement → tests

| Req | Tests | Status |
|---|---|---|
| R1 | `manifest.test.ts` (command in the palette, title bar, editor and Explorer menus), `review.e2e.browser.test.js` | ✅ 10b (the click itself: manual) |
| R2 | `review.browser.test.js` (graph left, findings right; stacked when narrow, no sideways scroll) | ✅ 10b |
| R3 | `review.browser.test.js`, `reviewModel.unit.test.js` (finding to step to line; step to its findings; cursor to finding) | ✅ 10b |
| R4 | `review.browser.test.js` (badge: number, glyph, word; cost on the step; tokens when unpriced), `reviewModel.unit.test.js` | ✅ 10b (contrast in the dark and high-contrast themes checked in the page test; the rest of `a11y.browser.test.js` comes in 10e) |
| R5 | `reviewModel.unit.test.js`, `review.browser.test.js` | ✅ 10b |
| R6 | `reviewCodeActions.test.ts`, `applyFix.integration.test.ts` | ✅ 10c |
| R7 | `reviewModel.unit.test.js`, `review.browser.test.js` (text, lens, severity, rule, group by, fold, not evaluated counted and listed) | 🟨 10b: "Enable" for an optional lens and "Show suppressed" come with 10c |
| R8 | `review.browser.test.js`, `explainRule` in `ServeProtocolTest` | ✅ 10c |
| R9 | `reviewController.test.ts`, `review.browser.test.js` (stale chip, previous result stays while running or after an error) | ✅ 10b |
| R10 | `reviewController.test.ts` (save, type with 200 ms debounce, manual; the quick review replaces the validity lens and marks the rest stale), `ServeEngineTest` (instant tier) | ✅ 10b |
| R11 | `engineClient.test.ts` (withdraw on newer, stale version dropped) | ✅ 10a |
| R12 | `latency.test.ts` | ⬜ 10e |
| R13 | `ServeRobustnessTest`, `ServeEngineTest`, `ServeStdioTest`, `engineClient.test.ts`, [PROTOCOL.md](../../serve/PROTOCOL.md) | ✅ 10a |
| R14 | `ServeEngineTest` (hit, edit, buffer, imported file, review.properties, prompts, options, memory bound) | ✅ 10a |
| R15 | `ServeIsStaticTest`, `ServeEngineTest` (outside folders, `..`, links, price file) | ✅ 10a |
| R16 | `ServeRobustnessTest`, `engineClient.test.ts`, `engine.integration.test.ts` (real process killed) | ✅ 10a |
| R17 | `applyFix.integration.test.ts`, `ServeIsStaticTest` (server writes nothing: ✅ 10a) | ✅ 10c (editor applies) |
| R18 | `ServeStdioTest` (real process) | ✅ 10a |
| R19 | `degraded.test.ts` (the Java check: missing, too old, unreadable, fine) | ✅ 10e |
| R20 | `SymbolsTest` | ✅ 10d |
| R21 | `SymbolsRangesTest` | ✅ 10d |
| R22 | `semanticQueries.test.ts`, `semanticAnswers.test.ts` (each feature steps aside by itself when there is no index), `degraded.test.ts` | ✅ 10d, 10e |
| R23 | `renameAtomic.test.ts` | ✅ 10d |
| R24 | `semanticCompletion.test.ts` (every suggestion passes `check`) | ✅ 10d |
| R25 | `parserParity.test.ts` | ✅ 10d |
| R26 | `latency.test.ts` (table in §7, 10e) | ✅ 10e |
| R27 | `SymbolsRecoveryTest` | ✅ 10d |
| R28 | `engineClient.test.ts`, `engineRouting.test.ts` (providers take the interface; `FakeEngine`) | ✅ 10a |
| R29 | `review.browser.test.js` (hostile text, 10,000-character title, nothing from outside), `hostile.browser.test.js` (every field, every layout, 2,000 findings), `a11y.browser.test.js` (names, contrast in three themes, focus, 200 % zoom, forced colours, reduced motion), `manifest.test.ts` | ✅ 10e |
| R30 | `review.browser.test.js` (page state holds layout, group, filter, folds), `ReviewPanel.revive` for a reloaded window | 🟨 10b: reload restore is wired and manual-checked |
| R31 | `noNetwork.test.ts` (the settings and their defaults), `degraded.test.ts` (limits reach the engine) | ✅ 10e |
| R32 | `noNetwork.test.ts`, `review.browser.test.js` (nothing loaded from outside) | ✅ 10e |
| R33 | `degraded.test.ts` | ✅ 10e |
| R34 | `triage.browser.test.js` (grouping) | ✅ 10c |
| R35 | `triage.browser.test.js` (actions by availability) | ✅ 10c |
| R36 | `triageActions.test.ts`, `triage.browser.test.js` | ✅ 10c |
| R37 | `triageActions.test.ts` (undo), `triage.browser.test.js` (Handled) | ✅ 10c |
| R38 | `triageActions.test.ts` (accept targets) | ✅ 10c |
| R39 | `triageActions.test.ts` (baseline) | ✅ 10c |
| R40 | `dismissLocal.test.ts` | ✅ 10c |
| R41 | `triageKeyboard.browser.test.js`, `a11y.browser.test.js` | ✅ 10c |
| R42 | `runNotBlocked.test.ts`, `reviewPresentation.test.ts` (once per unchanged script, mute) | ✅ 10b |
| R43 | `reviewPresentation.test.ts` (status text and tone), `manifest.test.ts` (no `when` on review state), `runNotBlocked.test.ts` | ✅ 10b |
| R44 | `layoutSwitch.browser.test.js` (opens in Split, switch, restore, the `loom.review.layout` setting that never overrides a saved choice), `layoutContext.unit.test.js`, `manifest.test.ts` | ✅ 10b2 |
| R45 | `layoutContext.unit.test.js`, `layoutSwitch.browser.test.js` (through all three and back: group, filter, lens filter, selected finding and step, folds, scroll) | ✅ 10b2 (multi-selection is kept in the saved state; the selection itself arrives with 10c) |
| R46 | `layoutSwitch.browser.test.js` (re-run, group, filter, workflow and the strip: once and visible in each layout; re-run sends the same message) | 🟨 10b2: Accept all existing… and Fix all safe are in the toolbar and hidden until 10c |
| R47 | `layoutSwitch.browser.test.js` (the pane toggle only in Split) | ✅ 10b2 |
| R48 | `layoutSwitch.browser.test.js` (Split stacks, Outline folds its right column, List drops the minimap, no sideways scroll; wide widths show all) | ✅ 10b2 |
| R49 | `layoutContext.unit.test.js` (one `view(model, ui)` with no layout argument gives one result), `layoutSwitch.browser.test.js` (same findings, order, counts and strip in each layout; a filter shows the same findings in each) | ✅ 10b2 |

## 3. Quality gates

- All existing extension and Loom tests pass unmodified.
- `npm run compile` clean; no `any` in new TypeScript outside protocol parsing (typed at the boundary).
- Static check: no `innerHTML`, `eval`, `document.write` in `media/review*.js` (extends the existing check).
- VSIX builds with `scripts/build-vsix.sh` and `verify-vsix.sh`; size recorded and within the budget agreed in DESIGN §10.2.
- Latency tests (A6) pass on the reference CI machine class.

## 4. Manual checks

1. Open each example script: the panel renders, every finding links to a real line.
2. Type a half-written `delegate` and keep typing: no flicker to empty, no stale squiggle on text that moved.
3. Kill the `java` process by hand: one message, recovery within seconds.
4. Rename an agent used in two imported files; undo it with one Ctrl+Z.
5. Use only the keyboard to open the panel, move through findings, apply a fix.
6. High-contrast theme and a 200 % zoom pass.
7. Uninstall Java (or rename it): limited mode message is clear and links to the fix.

## 5. Documentation owed

Extension README and CHANGELOG; `docs/serve/PROTOCOL.md`; wiki "Loom in VS Code"; LOOM_GUIDE section on the editor; screenshots (`scripts/take-graph-screenshots.js` extended); `llms.txt`; the workflow skill's "validate" stage mentions the panel.

## 6. Exit checklist

- [x] Every row in §2 is ✅ except R7 ("Enable lens" and "Show suppressed" are partial) and R30 (reload restore is wired; checked by hand); every A-criterion has automated evidence; the manual checks of §4 are owed (A1, A12 and the visual ones)
- [x] Latency table (§7, 10e) filled and within targets
- [x] Docs in §5 updated (the stack-level pages wait for the end of all phases, as decided on 2026-10-14)
- [x] DESIGN §10 questions: built to the recommendations and listed in `09-review-engine/OPEN_ITEMS.md` (D10) for your confirmation

## 7. Results

*(per sub-phase; the latency table, the parity delta and the VSIX size are in 10d and 10e)*

### 10a (2026-10-23)

- Built (Java): `weave serve --stdio` (`serve/` package: `Framing`, `Server`, `Workspace`, `ResultCache`, `RpcError`; `cli/CliEngine`, `cli/ServeCommand`); `Buffers`, a thread-local seam that lets `ScriptSource.fileSystem()` and `ImportClosureLoader` read an editor's unsaved text and say which files an answer was made from (with no buffers in place nothing changes). The methods are the commands themselves, called in process with their output captured (`WeaveCLI.check`, `GraphCommand.graph`, `ReviewCommand.review`), so the editor sees exactly what the commands print. Methods: `loom/check`, `loom/graph`, `loom/review` (tiers `instant` = validity lens, `full`), `loom/explainRule`, `loom/cancel`, `loom/shutdown`. `symbols`, `applyFix`, `cost` come in 10c and 10d.
- Built (extension): `src/engine/` with the framing codec, `ProcessEngineClient` (handshake, request ids, withdraw-on-newer with version numbers, timeouts, restart with growing waits, give up after repeated failures, protocol mismatch), `EngineHost` and the one status line (`statusFor`), `FakeEngine`, `EngineCheckRunner` / `EngineGraphRunner` (the engine first, the process as the fallback), `registerEngine` (VS Code glue, commands `loom.restartEngine` and `loom.showEngineLog`), the setting `loom.javaPath` (the older `loom.graph.javaPath` still honoured). `TimeoutScheduler` moved to `graph/timeoutScheduler.ts` so engine code does not import `vscode`.
- Behaviour unchanged: the existing check and graph tests pass unmodified; the one change to a test helper is `inspect` in `fakeVscode.ts`. Engine and command give identical JSON for the extension's fixtures (`engine.integration.test.ts`).
- Latency (this machine, JVM already running its first request): cold start to first check answer 735 ms; warm check p95 5 ms (median 3 ms). The full-review and completion budgets are measured in 10b and 10d.
- Test counts: Java module 2,832 tests, 0 failures. Extension: 331 tests, 283 pass, 42 skipped (browser tests without a browser), 6 fail, all six failing before this phase began (baseline in this environment): the logo script path (`src/scripts/make-logo-assets.sh` is not where the test looks), the editor scanner's `|` in `no-agent-router/router.loom` (the TypeScript parser does not know `choose kind: a | b`: a data point for the parser-parity work in 10d), the renderer-copy sync check (path), and a real-graph path test. One more pre-existing failure (the README did not list the `agent-grounded` snippet) was fixed in passing.
- VSIX size: the bundled `weave.jar` is the CLI jar, 25,235,050 bytes; the new code adds well under 100 KB to it. The VSIX itself was not built here (`scripts/build-vsix.sh` needs the packaging toolchain); the budget in DESIGN §10.2 is still to be agreed.
- Not yet: the review panel and everything the user can see (10b onward). **Nothing from 10a needs a manual check in VS Code beyond this**: open a `.loom` file, confirm Problems still appear as before (now through the engine), run "Loom: Show Engine Log" and see the engine's lines, kill the `java` process by hand and see the next check still work after a moment.

### 10b (2026-10-23)

- Built: `media/review-model.js` (the view model every layout will share: findings in the engine order, tied to steps by the engine's node id or by line, counts per step, the summary, grouping, filtering, the lens rail), `media/review.js` and `review.css` (the page, from the mockup's tokens, text only), `src/review/` (`ReviewController` with stale/running/error, tiers and debounce; `EngineReviewSource` with a process fallback; `presentation.ts` for squiggles, status bar and CodeLens; `RunNotice`; `ReviewPanel`; `vscodeReview.ts` as the thin glue), the command **Loom: Review Workflow**, the settings `loom.review.on|lenses|minSeverity|codeLens|statusBar|failOn`. Fixtures are real engine output (`scripts/regen-editor-fixtures.sh`), including a 240-finding script.
- Not in 10b by design: Fix, Accept, Dismiss and Explain buttons (10c; there is no dead button), the Outline and List layouts and the layout switch (10b2), definition/rename/completion (10d), degraded-mode polish, a11y pass and latency gates (10e). The "Fix all safe" button exists in the page and is hidden until 10c.
- Tests: Java unchanged this step. Extension: 412 tests, 364 pass, 42 skipped, 6 fail (the same six that failed before 10a: logo script path, the `|` scanner case, renderer-copy sync, one graph path test). New: `reviewModel.unit.test.js` (21), `review.browser.test.js` (26, in a real browser), `review.e2e.browser.test.js` (2: the real engine to the real page), `reviewController.test.ts` (13), `reviewPresentation.test.ts` (16), `runNotBlocked.test.ts` (3), four in `manifest.test.ts`.
- VSIX: built with `vsce package`: **23,757,178 bytes (22.66 MB)**, 496 files, of which `bin/weave.jar` is 24.07 MB uncompressed. DESIGN §10.2 (size budget) is still to be agreed; the extension's own code adds well under 1 MB.
- Performance seen: 300 findings group, filter and render in under 2 s in the page test (model alone: a few ms).
- **Manual check owed (VS Code):** see the list in the 10b report: open `main.loom`, run the command, click through finding to line and step to findings, edit and see *stale*, save and see it clear, Problems entries with source `loom-review`, status bar, CodeLens, Run with open findings starts at once and mentions the review once, dark and light themes, narrow the panel.

### 10b2 (2026-10-23)

- Built: the layout switch **Split | Outline | List** and the two new layouts, from boards B and C of the mockup. The page now draws from one `LoomReview.view(model, ui)` that takes no layout, and the three renderers only arrange it; the filter, grouping, summary strip, lens rail and counts are shared and appear once. The page state (`LoomReview.restoreUi`) is read defensively. New: `layoutContext.unit.test.js` (7), `layoutSwitch.browser.test.js` (22), a manifest row for `loom.review.layout`.
- Outline: the steps on the left with their counts, the chosen step in the centre (its cost, calls, model and share of the run, and its findings), the workflow summary, lenses and handled counts on the right. List: a table (severity, rule, lens, finding, where), a map of the steps with a dot per finding, and the focused finding explained. In both, the checkboxes, bulk bar and action buttons arrive with 10c.
- Not in the mockup but in the layouts: the selected finding's detail is the same text in all three (`detailOf`), so a finding cannot read differently.
- Extension suite after this step: see the 10c entry.

### 10c (2026-10-23)

- Built: triage in the review panel and editor. Fix / Fix all safe (one WorkspaceEdit, one undo; the server only returns text), Accept (writes `review.properties`), Baseline (writes `review.baseline.json`), local Dismiss with undo, Explain (`explainRule`), diff preview, lightbulb code actions, layout-independent action sets computed by the host (`actionsFor`), trifecta refused with the reason (parked: D9). Run notice shown once per unchanged script and never blocks Run.
- **Stall hardening of `weave serve`** (asked for in this phase): dedicated writer (a client that stops reading cannot block workers), 4 workers + queue of 64 (beyond that `busy` at once), timeouts interrupt the worker, newer same-file request replaces the older, `loom/ping` answered by the reader, server exits (status 3) if workers are wedged. Extension: after a request timeout it pings; no pong in 2 s kills and restarts the engine. Tests: `ServeRobustnessTest` (+5), `engineClient.test.ts` (+2). Documented in `docs/serve/PROTOCOL.md`.
- Tests: Java module 2,858, 0 failures. Extension: 509 pass, 6 fail (the same six that failed before phase 10).
- Still partial: R7 "Enable lens" and "Show suppressed" buttons.
- **Manual check owed (VS Code):** to be given once, after 10e, with the VSIX.

### 10d (2026-10-23)

- Built (Java): `weave symbols` and `loom/symbols` (package `symbols`): definitions of agents, workflows, tools, classifiers, personas, providers, decisions, mcp/knowledge/routing blocks, checkpoints and prompt files; uses with their role; variables with types (a classifier's record with its enum labels including `unsure`, an agent's `output_schema`, a delegate's `expecting`, a decision's choices, `unknown` otherwise); workflow scopes; `extent` for outlines. Ranges are UTF-16, tested with emoji, tabs and CRLF. Error recovery: a stray character, an unterminated string, a broken or unclosed block are set aside and the rest is read again. `buffers` lets the editor send every other changed file so an import being edited is read as it is. Two lexer fixes the index needed (and any error message that quotes a column benefits from): columns on the first line were 0-based while the others were 1-based, and a token after a multi-line string was one column off; a string token's line is now the line it starts on.
- Built (extension): `src/semantic/` (pure: `queries`, `completion`, `hover`, `answers`; glue: `vscodeSemantic`). Definition, references, rename (prepare, all-or-nothing, prompt files renamed with their id after a confirmation), completion (agents after `to`, checkpoints after `rewind to`, workflows, classifiers, decisions, tools, `intent.` fields with types, enum values after `==`, variables in conditions and `{placeholders}`), hover (agent model/tools/schema, workflow signature, classifier record, variable type and origin, a field's type, the allowed values of an enum comparison with a warning for a value it never has), outline (blocks, checkpoints, variables) and signature help at `call Name(`. Definition, hover and completion go through the language client's middleware so the older answer is the fallback; the setting `loom.semantic.enable` turns the new answers off.
- R25: `parserParity.test.ts` compares the frozen TypeScript parser with the Java index on **326 scripts**; 9 differences are committed in `test/fixtures/parity/parser-delta.json`, all the TypeScript parser reading the words "agent changes" in a ladder as an agent (Java is right). The TypeScript scanner's `|` rejection (pre-existing failure) is fixed in passing; its diagnostics are not shown to users, so nothing visible changed.
- R24 (strong form): `semantic.integration.test.ts` takes every suggestion in nine contexts from the real engine, puts it into a statement, and asks `loom/check`: none adds an error (more than 25 suggestions checked).
- Tests: Java module +40 (`SymbolsTest`, `SymbolsRangesTest`, `SymbolsRecoveryTest`, `SymbolsParityTest` over every repository script, `ServeSymbolsTest`, stall tests in `ServeRobustnessTest`). Extension: 564 pass, 5 fail (the pre-existing five: logo script path ×2, renderer-copy sync ×2, one real-graph path test).
- Not done in 10d: CodeLens reference counts, semantic tokens and folding (the table in DESIGN §5.2 lists them; neither has a requirement or a test row), and hover's "typical cost per call" (the cost model is in the review; wired in 10e with the latency gates). Renaming a versioned prompt (`name@v2`) or an import is refused with the reason, since it is a folder or file rename.
- **Manual check owed (VS Code):** F12 on an agent in a delegate, on an imported agent, on a variable; Shift+F12 references; F2 rename of an agent used in two files and of a variable used in `{placeholders}`; try renaming to a keyword and to a taken name (refused, with the reason); `intent.` completion and `== ` values; hover over an agent, a variable and a field; Outline view; Ctrl+Shift+Space inside `call Name(`; switch `loom.semantic.enable` off and see the older behaviour return.

### 10e (2026-10-23)

- **Degraded modes (R33).** `degraded.test.ts`: no Java and a Java older than 17 each stop the engine and say so in one status-bar line that opens the fix steps (new command **Loom: Java Requirements**; the Java check runs beside the start, so a working Java never waits for it, and an answer about an old start is ignored); an engine that keeps dying ends as *unavailable (using commands)*; a protocol mismatch says both versions; a slow engine is pinged after a timeout and restarted only if it does not answer (the stall hardening of 10c). Only status lines are produced: there is no popup to repeat.
- **Settings (R31).** `loom.serve.memoryMb` (64) and `loom.serve.timeoutMs` (10000) reach the engine as `--memory-mb` and `--timeout-ms`, and the extension waits two seconds longer than the engine's own limit so the engine's answer arrives first; changing them restarts the engine.
- **No network, no telemetry (R32).** `noNetwork.test.ts` scans every module for a way to open a connection and every page script for one, pins the one policy (`default-src 'none'`) of both pages, lists the only four modules that start processes (Java), and checks the manifest for telemetry packages and settings.
- **Accessibility, zoom and hostile text (R29, A10, A16).** `a11y.browser.test.js` (20 tests) and `hostile.browser.test.js` (6) in a real browser. They found and I fixed: pressed buttons and filled buttons under 4.5:1 contrast in light and dark (a darker fill token), expand arrows drawn on the browser's default light-grey button in a dark theme (1.3:1), the finding header and the legend running off the side at 360 px, the summary strip's cost note overlapping the time cell, and no border on buttons in forced-colour (Windows high contrast) mode; reduced motion is honoured. Hostile text (markup, handlers, scripts, bidirectional controls, 3,000-character words) in every field of a finding, a step and a file name is text only in all three layouts; 2,000 findings draw in under 4 s and stay responsive.
- **Latency (A6, R26), this machine, real engine, warm, a different text each request (no cache hits):**

| Gate | Budget | p50 | p95 |
|---|---|---|---|
| Cold start to first answer | 4,000 ms | 883 ms | |
| Instant tier (validity lens) | 150 ms | 30.5 ms | 44.5 ms |
| Full review (240 findings) | 1,000 ms | 38.9 ms | 53.5 ms |
| Completion (index and answer) | 100 ms | 6.6 ms | 13.2 ms |
| Hover (index and answer) | 100 ms | 5.7 ms | 8.6 ms |
| Rename plan across 20 files | 1,000 ms | 16 ms | 32 ms |

- **Docs (§5).** Extension README (Review screenshots, Editing, When something is limited, settings), CHANGELOG, `docs/serve/PROTOCOL.md`, wiki page *Loom in VS Code* (linked from the wiki home), LOOM_GUIDE "In the editor", `llms.txt`, the workflow skill's review section, and five screenshots (`scripts/take-review-screenshots.js`: Split light and dark, Outline, List light and dark) in `docs/images/vscode-loom/`.
- **VSIX.** Built with `vsce package`: **22.78 MB (23,881,485 bytes)**, 522 files, of which `bin/weave.jar` is 24.1 MB uncompressed (the jar is almost all dependencies). The bundled jar was started the way the extension starts it and answered `loom/symbols`, `loom/review` and `loom/ping`.
- Tests: Java module 2,898, 0 failures. Extension: 611 pass, 5 fail (the five that failed before phase 10 began: the logo script and the renderer-copy check look for scripts one folder too high, and one real-graph path test); no test skipped.
- **Manual check owed (VS Code):** the list in the final report of this phase (one VSIX, one checklist).
