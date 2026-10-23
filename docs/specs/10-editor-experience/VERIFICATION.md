# Phase 10 verification plan: the editor experience

Status: **10a done** (`weave serve`, the engine client, check and graph through the engine). 10b to 10e not started. Run top to bottom at the end of sub-phase 10e; the relevant rows after each sub-phase. Results in §7.

## 1. Acceptance criteria

| # | Criterion | Evidence |
|---|---|---|
| A1 | From a `.loom` file I can open a review with one click (or one palette command) and see the graph and the findings together, without typing a `weave` command | `review.browser.test.js`, `extension.integration.test.ts` |
| A2 | Clicking a finding selects its node and reveals its source line; clicking a node shows its findings and cost; moving the cursor highlights the node | `review.browser.test.js`, `reviewModel.unit.test.js` |
| A3 | I can see cost (typical and worst) on each model step, in the summary, in a CodeLens and in the status bar | `review.browser.test.js`, `reviewCodeLens.test.ts` |
| A4 | A SAFE fix applies with one click; Fix all safe applies all as one undo step; a REVIEW fix shows a diff first | `reviewCodeActions.test.ts`, `applyFix.integration.test.ts` |
| A5 | Findings appear as squiggles and Problems entries with the rule id, and Explain opens the rule's page | `reviewDiagnostics.test.ts` |
| A6 | Feedback is fast: warm instant tier p95 < 150 ms, full review p95 < 1 s, completion and hover p95 < 100 ms, cold start < 4 s | `latency.test.ts` |
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
| R1 | `manifest.test.ts`, `extension.integration.test.ts` | ⬜ 10b |
| R2 | `review.browser.test.js` | ⬜ 10b |
| R3 | `review.browser.test.js`, `reviewModel.unit.test.js` | ⬜ 10b |
| R4 | `review.browser.test.js` (tint + glyph + label), `a11y.browser.test.js` | ⬜ 10b |
| R5 | `reviewModel.unit.test.js` | ⬜ 10b |
| R6 | `reviewCodeActions.test.ts`, `applyFix.integration.test.ts` | ⬜ 10c |
| R7 | `reviewModel.unit.test.js` | ⬜ 10b |
| R8 | `review.browser.test.js`, `explainRule` in `ServeProtocolTest` | ⬜ 10c |
| R9 | `reviewController.test.ts` (stale, keep previous result) | ⬜ 10b |
| R10 | `reviewController.test.ts` (tiers, debounce) | ⬜ 10b |
| R11 | `engineClient.test.ts` (withdraw on newer, stale version dropped) | ✅ 10a |
| R12 | `latency.test.ts` | ⬜ 10e |
| R13 | `ServeRobustnessTest`, `ServeEngineTest`, `ServeStdioTest`, `engineClient.test.ts`, [PROTOCOL.md](../../serve/PROTOCOL.md) | ✅ 10a |
| R14 | `ServeEngineTest` (hit, edit, buffer, imported file, review.properties, prompts, options, memory bound) | ✅ 10a |
| R15 | `ServeIsStaticTest`, `ServeEngineTest` (outside folders, `..`, links, price file) | ✅ 10a |
| R16 | `ServeRobustnessTest`, `engineClient.test.ts`, `engine.integration.test.ts` (real process killed) | ✅ 10a |
| R17 | `applyFix.integration.test.ts`, `ServeIsStaticTest` (server writes nothing: ✅ 10a) | ⬜ 10c (editor applies) |
| R18 | `ServeStdioTest` (real process) | ✅ 10a |
| R19 | `degraded.test.ts` | ⬜ 10e |
| R20 | `SymbolsTest` | ⬜ 10d |
| R21 | `SymbolsRangesTest` | ⬜ 10d |
| R22 | `semantic*.test.ts`, `degraded.test.ts` | ⬜ 10d |
| R23 | `renameAtomic.test.ts` | ⬜ 10d |
| R24 | `semanticCompletion.test.ts` (every suggestion passes `check`) | ⬜ 10d |
| R25 | `parserParity.test.ts` | ⬜ 10d |
| R26 | `latency.test.ts` | ⬜ 10e |
| R27 | `SymbolsRecoveryTest` | ⬜ 10d |
| R28 | `engineClient.test.ts`, `engineRouting.test.ts` (providers take the interface; `FakeEngine`) | ✅ 10a |
| R29 | `a11y.browser.test.js`, `hostile.browser.test.js` | ⬜ 10b/10e |
| R30 | `reviewPanelState.test.ts` | ⬜ 10b |
| R31 | `manifest.test.ts` | ⬜ 10e |
| R32 | `noNetwork.test.ts` | ⬜ 10e |
| R33 | `degraded.test.ts` | ⬜ 10e |
| R34 | `triage.browser.test.js` (grouping) | ⬜ 10c |
| R35 | `triage.browser.test.js` (actions by availability) | ⬜ 10c |
| R36 | `triageActions.test.ts`, `triage.browser.test.js` | ⬜ 10c |
| R37 | `triageActions.test.ts` (undo), `triage.browser.test.js` (Handled) | ⬜ 10c |
| R38 | `triageActions.test.ts` (accept targets) | ⬜ 10c |
| R39 | `triageActions.test.ts` (baseline) | ⬜ 10c |
| R40 | `dismissLocal.test.ts` | ⬜ 10c |
| R41 | `triageKeyboard.browser.test.js`, `a11y.browser.test.js` | ⬜ 10c |
| R42 | `runNotBlocked.test.ts`, `runNoticeOnce.test.ts` | ⬜ 10b |
| R43 | `reviewStatusBar.test.ts`, `manifest.test.ts` | ⬜ 10b |
| R44 | `layoutSwitch.browser.test.js`, `manifest.test.ts` | ⬜ 10b2 |
| R45 | `layoutContext.unit.test.js` | ⬜ 10b2 |
| R46 | `layoutSharedControls.browser.test.js` | ⬜ 10b2 |
| R47 | `layoutSwitch.browser.test.js` (pane toggle only in Split) | ⬜ 10b2 |
| R48 | `layoutNarrow.browser.test.js` | ⬜ 10b2 |
| R49 | `layoutSameModel.unit.test.js` | ⬜ 10b2 |

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

- [ ] Every row in §2 is ✅ and every A-criterion has evidence
- [ ] Latency table (§7) filled and within targets
- [ ] Docs in §5 updated; roadmap status updated
- [ ] DESIGN §10 questions answered and recorded

## 7. Results

*(per sub-phase: latency table, parity delta between the TypeScript parser and the Java index, VSIX size)*

### 10a (2026-10-23)

- Built (Java): `weave serve --stdio` (`serve/` package: `Framing`, `Server`, `Workspace`, `ResultCache`, `RpcError`; `cli/CliEngine`, `cli/ServeCommand`); `Buffers`, a thread-local seam that lets `ScriptSource.fileSystem()` and `ImportClosureLoader` read an editor's unsaved text and say which files an answer was made from (with no buffers in place nothing changes). The methods are the commands themselves, called in process with their output captured (`WeaveCLI.check`, `GraphCommand.graph`, `ReviewCommand.review`), so the editor sees exactly what the commands print. Methods: `loom/check`, `loom/graph`, `loom/review` (tiers `instant` = validity lens, `full`), `loom/explainRule`, `loom/cancel`, `loom/shutdown`. `symbols`, `applyFix`, `cost` come in 10c and 10d.
- Built (extension): `src/engine/` with the framing codec, `ProcessEngineClient` (handshake, request ids, withdraw-on-newer with version numbers, timeouts, restart with growing waits, give up after repeated failures, protocol mismatch), `EngineHost` and the one status line (`statusFor`), `FakeEngine`, `EngineCheckRunner` / `EngineGraphRunner` (the engine first, the process as the fallback), `registerEngine` (VS Code glue, commands `loom.restartEngine` and `loom.showEngineLog`), the setting `loom.javaPath` (the older `loom.graph.javaPath` still honoured). `TimeoutScheduler` moved to `graph/timeoutScheduler.ts` so engine code does not import `vscode`.
- Behaviour unchanged: the existing check and graph tests pass unmodified; the one change to a test helper is `inspect` in `fakeVscode.ts`. Engine and command give identical JSON for the extension's fixtures (`engine.integration.test.ts`).
- Latency (this machine, JVM already running its first request): cold start to first check answer 735 ms; warm check p95 5 ms (median 3 ms). The full-review and completion budgets are measured in 10b and 10d.
- Test counts: Java module 2,832 tests, 0 failures. Extension: 331 tests, 283 pass, 42 skipped (browser tests without a browser), 6 fail, all six failing before this phase began (baseline in this environment): the logo script path (`src/scripts/make-logo-assets.sh` is not where the test looks), the editor scanner's `|` in `no-agent-router/router.loom` (the TypeScript parser does not know `choose kind: a | b`: a data point for the parser-parity work in 10d), the renderer-copy sync check (path), and a real-graph path test. One more pre-existing failure (the README did not list the `agent-grounded` snippet) was fixed in passing.
- VSIX size: the bundled `weave.jar` is the CLI jar, 25,235,050 bytes; the new code adds well under 100 KB to it. The VSIX itself was not built here (`scripts/build-vsix.sh` needs the packaging toolchain); the budget in DESIGN §10.2 is still to be agreed.
- Not yet: the review panel and everything the user can see (10b onward). **Nothing from 10a needs a manual check in VS Code beyond this**: open a `.loom` file, confirm Problems still appear as before (now through the engine), run "Loom: Show Engine Log" and see the engine's lines, kill the `java` process by hand and see the next check still work after a moment.
