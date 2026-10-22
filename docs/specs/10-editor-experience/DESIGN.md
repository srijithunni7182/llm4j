# Phase 10 design: the editor experience (review panel, semantic language features, `weave serve`)

Status: **draft for review**. Layer: the VS Code extension (`src/loom/vscode-loom`) and one new CLI mode, `weave serve` (`ai-agent4j-loom`). Companion documents:
[TEST_STRATEGY.md](TEST_STRATEGY.md), [VERIFICATION.md](VERIFICATION.md), [program overview](../LOOM_DX.md). Builds on [Phase 9](../09-review-engine/DESIGN.md) (findings, fixes, cost) and on the extension as it is:
`src/check/{runner,model,controller}.ts` (Problems from `weave check --format json`), `src/graph/*` and `src/views/GraphPanel.ts` (the graph panel over `weave graph --format json`),
`src/lsp/*` (a TypeScript language server with hover, definition and completion, and its own parser `parseDocument.ts`), `media/graph-render.js`, and the bundled `bin/weave.jar` that the extension runs as `java -jar`.

## 1. Problem

A developer should not have to remember `weave` commands, and should not wait for them. Today:

1. **Feedback is a process start away.** Every `check` and `graph` is a fresh `java -jar` (JVM start, class load, parse). That is acceptable for a command and not for typing.
2. **Review is not in the editor.** Phase 9 produces findings, costs and fixes; nothing shows them where the developer is looking, next to the graph, or applies a fix with a click.
3. **Two parsers.** The TypeScript language server parses the document itself (`parseDocument.ts`) for hover, definition and completion, while the Java parser is the truth. They can disagree, and every new language feature
   must be written twice. Features that need real semantics (what fields does `intent` have? who calls this agent? what does renaming a workflow touch?) are out of reach for a second, partial parser.
4. **The graph and the problems are separate places.** A problem in the Problems panel does not light up the node in the graph; a node in the graph does not list what is wrong with it.

## 2. Goals and non-goals

Goals:

- **A Loom: Review panel** that shows the summary card, the findings grouped by lens and the workflow graph together, linked in both directions (finding ↔ node ↔ source line), with Fix buttons.
- **Findings everywhere they belong**: squiggles in the editor and entries in the Problems panel (source `loom-review`), quick fixes (the lightbulb), a status-bar item, a CodeLens above each workflow with its cost range.
- **A semantic layer from the Java side**: definitions, references, rename, completion of `binding.field` with real types, hover with schema and cost, document symbols. Computed by the same parser that runs the script.
- **`weave serve`**: one long-lived process per workspace, answering requests over stdio from a cache keyed by file content, so warm feedback takes tens of milliseconds, not seconds.
- **No command to remember**: Run, Review, Graph, Explain, Test (phase 11), Generate (phase 12), Debug (phase 13) are all in the command palette, the editor title bar and the panel.
- **Graceful degradation**: with no Java found, or `weave serve` down, the extension falls back to what it does today (TypeScript parser, snippets, syntax colours) and says why in one place.

Non-goals:

- Not a second implementation of the review rules in TypeScript. The extension renders what the engine returns.
- Not a visual editor: the graph stays a view of the script (program non-goal).
- Not support for other editors in this phase. `weave serve` speaks a documented protocol (§4), so an IntelliJ or Neovim client is possible later; building them is a follow-up.
- Not a change to the grammar or to how scripts run.

## 3. The experience

### 3.1 Entry points (R1)

| Where | What |
|---|---|
| Command palette, editor title bar, editor context menu, `.loom` file explorer | **Loom: Review Workflow** (opens the Review panel for the active script) |
| Status bar (right) | `Loom ⚠ 2 high · $0.004/run`; click opens the panel; tooltip lists lens counts. Green `Loom ✓` when none at or above the configured level |
| CodeLens above `workflow X(...)` | `Review: 2 high, 1 medium  ·  ~$0.004 typical / $0.06 worst  ·  Graph  ·  Run  ·  Test` (each a clickable) |
| Problems panel | Findings with `source: loom-review`, code = rule id (click opens the rule's explanation), plus today's `weave check` problems unchanged |
| Lightbulb on a finding | The finding's fix (**SAFE** fixes first; **REVIEW** fixes labelled "Review: ...") and "Explain LR03", "Suppress with reason..." |

### 3.2 The Review panel (R2 to R9)

```
+--------------------------------------------------------------+---------------------------------------+
| TicketTriage · main.loom                  [Re-run] [Fix safe] |  Summary                              |
|                                                              |  2 high   3 medium   1 low   0 info   |
|   graph (existing renderer)                                  |  Cost  typical $0.004  worst $0.06    |
|   [start]-->(intentclassify Intent)--+--(delegate Replier)   |        bounded by: 3 loops, 2 retries |
|                  ! LR05              |        ! LA05 ! LC01  |  Security  trifecta present (LA05)    |
|                                      +--(return)             |  ---------------------------------    |
|   nodes tinted by worst finding; cost badge per step;        |  SECURITY (1)                         |
|   selected node outlined                                     |   HIGH  LA05 Replier reads customer…  |
|                                                              |         why / fix / [Fix] [Explain]   |
|                                                              |  RELIABILITY (2)  COST (2)  VALIDITY… |
+--------------------------------------------------------------+---------------------------------------+
| Assumptions (collapsed): loop 3x, foreach 5 items, output 600 tokens · prices.properties 2026-09-30    |
+--------------------------------------------------------------------------------------------------------+
```

- **R2** One panel (a webview, reusing `media/graph-render.js` and the existing `GraphPanel` plumbing) shows the graph on the left and the review on the right. On narrow widths it stacks (graph above), with no horizontal page scroll.
- **R3** **Linked both ways.** Clicking a finding selects its node in the graph, scrolls it into view and reveals the source line in the editor beside the panel. Clicking a node filters the list to that node's findings and shows its cost.
  Clicking a source line selection in the editor (cursor move) highlights the node and findings for that line (debounced).
- **R4** **Nodes carry state**: a tint by worst finding severity (never colour alone: also a glyph and a text label in the tooltip and a legend), and a cost badge on model-calling steps (typical, with worst on hover).
  A finding with no node id (a script-level finding) appears in a "Whole script" group and lights the workflow header.
- **R5** **Summary card** always visible: counts by severity, the cost range with its bound ("bounded by 3 loops, 2 retries" or "unbounded: LR03"), the time range with waits shown apart, the worst-case effects, the trifecta state, and which lenses ran and which are off.
- **R6** **Fix buttons.** A finding with a `SAFE` fix shows **Fix**; with a `REVIEW` fix shows **Review fix...**, which opens a diff preview (VS Code's own diff editor) and applies on confirmation. **Fix all safe** applies every SAFE fix as one workspace edit,
  so a single Undo reverts it (R24 of phase 9 gives atomicity; here the edit is also one undo step).
- **R7** **Filters**: by lens, by severity, text; a lens group per lens with a count, **optional lenses shown as collapsed, disabled groups with an "Enable" action** (which writes `lenses =` to `review.properties`); "Show suppressed" and "Show not evaluated" toggles (suppressions with their reasons, and rules skipped for undeclared facts, are visible, not hidden). State is remembered per workspace.
- **R8** **Explain**: a finding's rule id opens the rule's page in a side view (rendered from `weave review --explain`), with its firing example and near-miss.
- **R9** **Stale-aware.** The panel shows a "stale" chip as soon as the document changes, re-runs on save (setting `loom.review.on = save | type | manual`, default `save`), and never shows findings for text that no longer exists without saying so.
  While running it shows a progress indication and keeps the previous result greyed; it never clears to empty.

### 3.3 Live feedback while typing (R10 to R12)

- **R10** Two tiers. *Instant* (every change, debounced 200 ms): validity findings and the cheap structural rules, from `weave serve`. *On save*: the full set including cost and security. A setting runs the full set on type for those who want it.
- **R11** A request in flight is cancelled when a newer one for the same file arrives; a result for an older document version is discarded (version numbers on every request).
- **R12** Budget: warm instant tier p95 under 150 ms for a 300-line script; full review p95 under 1 s; cold start of `weave serve` under 4 s. The panel and status bar show "starting Loom engine..." during cold start; nothing blocks typing.

### 3.4 Triage: fix, accept, dismiss (R34 to R41)

Noise is handled by making each decision one click, safe, and durable where it should be (the actions and their guardrails are defined in [phase 9 §11.1](../09-review-engine/DESIGN.md)). The panel is a triage board:

```
Group by [ severity | lens | rule | file ]                              Fix all safe (5 of 8)   Accept all…   Dismiss all
▾ HIGH  (2)                                                              Fix all safe (1 of 2)   Accept all…   Dismiss all
    LA05  Replier reads customer data and can send mail with no approval          [Fix] [Accept…] [Dismiss] [Explain]
    LD02  Tool 'Mailer' (your Java) can repeat a send on resume                    [Review fix…] [Accept…] [Dismiss] [Explain]
▾ MEDIUM (6)  ▸ by lens: Reliability (3) · Cost (2) · Prompts (1)
▸ Handled (11)   accepted 4 · dismissed 5 · baselined 2    [Show] [Restore all dismissed]
```

- **R34** **Two-level grouping, switchable.** The list is grouped by severity and, within it, by lens (default); the developer can switch the primary grouping to lens, rule or file. Each group header shows its count and carries group actions (below). Optional lenses that are off appear as collapsed, disabled groups with an Enable action (R7).
- **R35** **Per-finding actions** depend on what exists: **Fix** (SAFE fix), **Review fix…** (REVIEW fix, opens the diff), **Accept…** (durable, with reason), **Dismiss** (local), **Explain**. A finding with no fix shows Accept and Explain only, never a disabled Fix button without a reason ("No automatic fix: see the hint").
- **R36** **Group actions** act on the group on screen and always state the scope and number first: **Fix all safe (n of m)** applies only the SAFE fixes in the group (the rest are listed as needing review); **Accept all…** asks for the scope (this rule here / in this script / in the project), a reason (quick-pick presets: *false positive*, *intended here*, *handled elsewhere*, *will fix later, until…*, or free text) and, for serious findings, applies the guardrails of R44; **Dismiss all** hides the group for this session or workspace. Nothing is applied until the dialog (or, for Fix all safe, the preview) is confirmed, except Dismiss, which is undoable instantly.
- **R37** **Feedback and undo.** After an action the finding leaves the list immediately, a toast says what happened with an **Undo** ("Fixed 5 · Undo", "Accepted 3 for LR05 in this script · Undo"). Fixes are one workspace edit (one Ctrl+Z); accepts and baselines are edits to project files and are undone by the same toast or by the editor's undo. Handled findings move to a **Handled** section with counts, where each can be restored, so nothing silently disappears.
- **R38** **Accept writes the right thing.** Accept writes `review.properties` (with the reason, who, when, optional `until`), except trifecta acceptance, which writes `trifecta: accepted "reason"` in the script (phase 9 R44). The edit is shown in the editor's normal diff and goes through version control like any other change; the extension never commits.
- **R39** **Baseline from the panel.** **Accept all existing…** (shown prominently when a script has more than a configured number of findings the first time it is reviewed) writes `review.baseline.json` after showing what it will include and exclude (phase 9 R46), and the panel then shows only new findings with the baselined group collapsed.
- **R40** **Dismiss is local.** It is stored in editor workspace storage, never in the repository, never changes the engine's result, the Problems panel entries for errors (validity errors cannot be dismissed), or CI; the status bar says "n dismissed" so a hidden count is always visible.
- **R41** **Keyboard first.** Every action has a keyboard path in the list (arrow keys to move, `F` fix, `A` accept, `D` dismiss, `E` explain, `Enter` reveal in the editor), shown in a help line, and group actions are reachable from the group header by keyboard. Screen readers announce "high, durability, fix available" and the result of every action.

### 3.5 Never in the way (R42, R43)

- **R42** **Run is never blocked.** The editor's Run Workflow, the Run CodeLens and the debugger launch start the workflow immediately whatever the review says (phase 9 R49). When open findings exist, a **non-modal** information message offers *Review* and *Don't show again for this workspace*; it never asks for confirmation to proceed, never disables Run, and never appears twice for the same unchanged script. The terminal shows the CLI's one-line notice as usual.
- **R43** **The status bar is the standing indicator**, not a gate: it shows the open counts (`Loom ⚠ 2 high · 3 medium`), turns neutral when everything is handled, and a click opens the panel. Colours never carry meaning alone (R4). No command in the extension's palette is conditional on a clean review.

## 4. `weave serve` (R13 to R19)

A long-lived process: `java -jar weave.jar serve --stdio`, started once per VS Code window by the extension.

### 4.1 Protocol

JSON-RPC 2.0 over stdio with LSP-style `Content-Length` framing (so existing client libraries can read it; it is **not** the Language Server Protocol itself, because it carries Loom-specific methods).
Methods (all take `{file, text?, version}`; `text` overrides disk so unsaved buffers are reviewed):

| Method | Returns | Used by |
|---|---|---|
| `loom/check` | the check JSON (version 1, as `weave check --format json`) | Problems |
| `loom/review` | the review JSON (phase 9, version 1), with `lenses` and `tier` options | panel, status bar, CodeLens |
| `loom/graph` | the graph JSON (as `weave graph --format json`) | panel, outline |
| `loom/symbols` | the semantic index (§5.1) | LSP features |
| `loom/applyFix` | the verified edits for one finding id or all SAFE (not written; the editor applies them) | quick fixes |
| `loom/explainRule` | the rule's page (markdown) | Explain |
| `loom/cost` | the cost block alone (cheap) | CodeLens, hover |
| `loom/cancel` | cancels a request id | typing |
| `loom/shutdown` | clean exit | window close |
| *(reserved)* `loom/test`, `loom/generate`, `loom/debug.*` | phases 11 to 13 | later |

- **R13** The protocol is documented (`docs/serve/PROTOCOL.md`), versioned (`initialize` returns `{protocol: 1, weave: "<version>"}`), and **additive**: unknown fields are ignored; unknown methods return a typed error. The extension refuses a server whose major protocol differs and says to reinstall.
- **R14** **Cache.** Parsed scripts, import closures, graphs and review results are cached by (path, content hash, settings hash, price-file hash). A change to an imported file or prompt invalidates exactly the dependents (dependency graph kept by the closure loader). Memory is bounded (default 64 MB, LRU).
- **R15** **No side effects.** `serve` runs no model, tool, task or workflow, loads no user class, writes no file, makes no network call. It reads the workspace files named in requests and their imports only. A request for a path outside the opened workspace roots is refused.
- **R16** **Robustness.** A crash in a rule becomes a note on that request, not a server exit; a request exceeding its time limit (default 10 s) is abandoned with a typed timeout; the server survives malformed JSON (error reply) and a closed stdin (clean exit). The extension restarts a dead server with backoff and shows one non-modal message.
- **R17** **One writer.** Applying fixes is done by the editor through a `WorkspaceEdit`; the server only computes edits (R24 of phase 9 verification happens server-side, on the buffer the client sent). This keeps undo, dirty buffers and file watchers correct.
- **R18** `weave serve` is also usable from a terminal for other tools (`weave serve --stdio` documented); a `--socket <path>` mode is out of scope.
- **R19** The Java process is found as today (`loom.graph.javaPath`, renamed `loom.javaPath` with the old key honoured). If Java is below the supported version or missing, the extension runs in degraded mode (§7).

## 5. The semantic layer (R20 to R27)

### 5.1 `loom/symbols` (and `weave symbols --format json`)

The Java side emits a semantic index of one script and its import closure:

```json
{ "version": 1,
  "definitions": [ {"kind":"agent","name":"Replier","file":"main.loom","range":{...}}, {"kind":"workflow",...}, {"kind":"tool",...},
                   {"kind":"task",...}, {"kind":"classifier",...}, {"kind":"prompt","name":"replier","file":"prompts/replier.md"},
                   {"kind":"checkpoint",...}, {"kind":"persona",...}, {"kind":"provider",...} ],
  "references":  [ {"to":"agent:Replier","range":{...},"role":"delegate-target"} , ... ],
  "bindings":    [ {"name":"intent","range":{...},"scope":"workflow:Main","producedBy":"node:n3",
                    "type":{"kind":"object","fields":{"kind":{"enum":["refund","order_status","complaint","other","unsure"]},"urgent":"yesno","tone":{"enum":[...]}}}} ],
  "scopes":      [ {"workflow":"Main","params":["message"],"range":{...}} ] }
```

- **R20** The index is produced by the real parser/validator (the same objects `weave check` uses), so it can never disagree with the runtime. Types come from `output_schema`, `intentclassifier` questions (including the reserved `unsure`), task result shapes, `return {...}` and `human_prompt` (string).
  A binding whose type cannot be known is `unknown`, and nothing completes from it.
- **R21** Ranges are 0-based line/column in UTF-16 code units (VS Code's convention) and are tested with emoji, CRLF and tabs.

### 5.2 Language features built on the index

| Feature | Behaviour | Replaces |
|---|---|---|
| Go to definition / peek | agent, workflow, tool, task, classifier, prompt file, checkpoint, import, persona, provider | today's definition for agents/workflows only |
| Find references / CodeLens counts | everything defined, with its role (`delegate` target, `call`, `rewind to`, ...) | new |
| Rename | any definition; edits every reference including imports and prompt-file names where the name is the file name (asks before renaming a file) | new |
| Completion | `intent.` offers the fields of the type, enum values after `==`, `delegate ... to ` offers agents, `rewind to` offers checkpoints, `run ` offers tasks; with the field's type shown | partly today |
| Hover | on an agent: model, tools, reach, budget, **typical cost per call**; on a binding: its type; on an enum compare: the values; on a rule squiggle: the rule's `why` | partly today |
| Document / workspace symbols, outline | workflows, agents, tools, tasks | the existing outline tree |
| Signature help | workflow parameters at a `call` | new |
| Folding, semantic tokens | from the real token stream | syntax grammar only |

- **R22** Each feature degrades individually: if `loom/symbols` is unavailable, the existing TypeScript implementations answer (§7).
- **R23** A rename is **all or nothing**: if any reference cannot be edited (a file is read-only, an import is unresolved), nothing changes and the editor says which.
- **R24** Completion never suggests something that fails `check`: suggestions are filtered to names in scope with the right kind.
- **R25** The TypeScript parser (`parseDocument.ts`) is **kept as a fallback and for the first keystrokes after open** (so completion works before the server is warm) and is frozen: no new language features are added to it. A test compares its answers with the Java index on the corpus and the delta is tracked, so retiring it later is a data-backed decision.
- **R26** Latency: completion p95 under 100 ms warm; hover under 100 ms; rename of a 20-file project under 1 s.
- **R27** The index of an unsaved buffer reflects the buffer (version-checked) and tolerates a half-typed construct: it returns what parsed (error-recovering parse of the enclosing workflow), never an empty answer for a syntax error elsewhere in the file.

## 6. The extension architecture

```
src/
  engine/              EngineClient (spawn weave serve, framing, request/cancel/version, restart), EngineProcess, protocol types, fake for tests
  review/              ReviewController (debounce, tiers, staleness), ReviewModel (parses review JSON), ReviewPanel (webview host),
                       ReviewDiagnostics (Problems + squiggles), ReviewCodeActions, ReviewCodeLens, ReviewStatusBar, applyFix.ts
  semantic/            SymbolsClient, providers: definition, references, rename, completion, hover, documentSymbols, signatureHelp (thin: ask the engine)
  graph/ views/ check/ lsp/   existing; graph and check route through EngineClient when available, the process runners remain the fallback
media/
  review.js, review.css        the right-hand pane; graph-render.js shared with the graph panel; tokens for both themes
  review-model.js              pure functions: group, filter, link, summarise (browser-testable)
```

- **R28** All engine access goes through `EngineClient`; one place handles the process, framing, versions, timeouts and restarts. Providers and controllers take an `EngineClient` interface, so tests use a fake with canned JSON.
- **R29** The webview follows the existing rules: text is set with `textContent`, no HTML built from script text, CSP nonce, no remote resources, theme tokens for light/dark/high-contrast, keyboard operable (list with roving focus, graph nodes focusable), screen-reader labels with severity words.
- **R30** The panel's state (selected node, filters) is kept across hide/show (`retainContextWhenHidden` off; state serialised), and restored after window reload.
- **R31** Settings (all in the `loom.` namespace): `loom.javaPath`, `loom.review.on`, `loom.review.lenses`, `loom.review.minSeverity`, `loom.review.codeLens`, `loom.review.statusBar`, `loom.review.failOn` (colours the status bar), `loom.serve.memoryMb`, `loom.serve.timeoutMs`. Old `loom.graph.*` keys keep working.
- **R32** Telemetry: none. The extension makes no network calls (existing property, pinned by a test).

## 7. Degraded modes (R33)

- **R33** Exactly one place tells the developer what is limited and why, never repeated popups:

| Situation | What still works | What the status bar says |
|---|---|---|
| No Java / too old | Syntax colours, snippets, TypeScript hover/definition/completion, guide | `Loom: Java 17+ not found (limited mode)`, click opens the fix steps |
| `weave serve` fails to start or crashes repeatedly | As above plus one-shot `check`/`graph` via process | `Loom: engine unavailable (using commands)` |
| Engine slow (> timeout) | Last result, marked stale | `Loom: reviewing…` then `stale` chip |
| Protocol mismatch | As limited mode | `Loom: reinstall the extension (engine v2, extension v1)` |

## 8. Commands added

`loom.review` (Review Workflow), `loom.review.fixAll` (Apply Safe Fixes), `loom.review.explain`, `loom.review.accept` (scope and reason, writes `review.properties` or the script's trifecta statement), `loom.review.dismiss`, `loom.review.restore`, `loom.review.baseline`, `loom.restartEngine`, `loom.showEngineLog`.
Reserved ids for later phases: `loom.test`, `loom.generate`, `loom.debug`. Each command has a palette title, a `when` clause and appears in the manifest test.

## 9. Sub-phases

| | Delivers | Tests |
|---|---|---|
| 10a | `weave serve` (protocol, cache, `check`/`graph`/`review` methods, cancel, shutdown), `EngineClient`, fake, restarts; the existing check/graph go through it | `ServeProtocolTest`, `ServeCacheTest`, `ServeRobustnessTest`, `engineClient.test.ts` |
| 10b | Review panel: findings + graph linked, summary card, filters, status bar, CodeLens, Problems merge | `reviewModel.unit.test.js`, `review.browser.test.js`, `reviewDiagnostics.test.ts` |
| 10c | Triage in the editor (R34 to R41): grouping, per-finding and group actions, Fix all safe with preview, Accept with scopes and reasons, Dismiss, Handled section, undo toasts, baseline from the panel, keyboard map; quick fixes | `reviewCodeActions.test.ts`, `applyFix.integration.test.ts`, `triage.browser.test.js`, `triageActions.test.ts`, `dismissLocal.test.ts` |
| 10d | `weave symbols`, semantic providers (definition, references, rename, completion, hover, symbols), TS parser frozen with comparison test | `SymbolsTest`, `semantic*.test.ts`, `renameAtomic.test.ts` |
| 10e | Degraded modes, settings, accessibility pass, performance gates, docs | `degraded.test.ts`, `a11y.browser.test.js`, `latency.test.ts` |

## 10. Decisions to confirm (open questions)

1. **`weave serve` vs a Java LSP.** This spec uses a Loom-specific JSON-RPC server plus thin TypeScript providers. Alternative: a full Java language server (lsp4j) that any editor can use directly. Recommendation: start with `serve` (smaller, fits the extension's structure, reuses the Phase 9 JSON), and put the LSP-conformant facade (lsp4j) in a later phase once the semantic methods are stable, since `loom/symbols` maps cleanly onto it.
2. **Bundling.** The extension already bundles `weave.jar` (size grows with the review engine). Confirm the VSIX size budget (current size to be recorded in 10a).
3. **Where `review.properties` suppression writes.** Beside the script. Confirm.
4. **Run on type default.** Instant tier only (R10). Confirm that full review on type is opt-in.
5. **IntelliJ.** Out of scope here; confirm it should be the next client after this phase.
