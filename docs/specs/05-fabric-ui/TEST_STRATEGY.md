# Phase 5 test strategy: the Fabric page

Status: **draft for review**. Requirements `R1`…`R24` are in [DESIGN.md](DESIGN.md) §6; the traceability matrix is in [VERIFICATION.md](VERIFICATION.md).

## 1. Principles

- **Tests first** for each sub-phase (5a–5e), failing for the right reason. The browser tests are written against the simulator scenarios before the renderers exist.
- **The server tests are Java, the page tests are a real browser.** What a route returns, what the catalog fills and what the simulator emits are checked in JUnit with AssertJ as in use. What the page
  draws and does is checked in Chromium (Playwright via node:test, as the VS Code panel's test is, skipped with a clear message when Playwright or a browser is missing), against a real Fabric server
  fed by the real simulator scenarios. No model, no network beyond localhost, no real time (the simulator takes a clock and a speed).
- **The data is the oracle.** A page test does not assert pixel positions. It asserts what a person would read (words, numbers, which rows, in which order, which element is focused) and that the
  numbers equal the ones in the API answer for the same run.
- **Old behaviour is pinned.** The existing page tests, the existing routes' answers and the Studio simulator scenario run unmodified; added view keys are checked not to disturb old consumers.
- **Run text is hostile.** Every renderer is tested with markup, very long names, emoji, right-to-left text and control characters in tool names, agent names, error text and incident titles.
- Tests live beside the code: routes and views in `loom-inspect-core/src/test/.../http|state`, the catalog and config in `loom-fabric/src/test`, the simulator in `loom-inspect/src/test/.../sim`,
  and the browser tests in `loom-inspect-core/src/test/ui` (node:test, one file per page), with fixtures made by the simulator, not by hand.

## 2. Levels

| Level | What | Examples | Requirements |
|---|---|---|---|
| **View** | Streams in, `view()` out: `points` and `events` bounded and ordered; `series` unchanged; the catalog fill and `windowSource` | `ContextHistoryTest`, `WindowCatalogTest` | R1, R5 |
| **Routes** | `/api/tools`, `/api/security`, summaries' `security`: shape, ordering, bounds, one client's, the gate, 405 for other methods, embedded and Fabric give the same answer | `ToolsRouteTest`, `SecurityRouteTest`, `RunSummariesTest` | R2, R3, R6, R24 |
| **Config** | The `windows` section: save, refuse, audit, persist, load an old store; the structured and text forms of the eight behaviours | `WindowsConfigTest`, `FabricConfigPhase5Test` | R4, R15, R16 |
| **Simulator** | Each scenario run on a fake clock; the records it emits give the view its table row says; the names are selectable; Studio is unchanged | `ScenarioTest` (one case per scenario), `ScenarioLiveTest`, `SimulateTest` unchanged | R22 |
| **Page: run** | Per scenario: the banner, cards, gauges, timeline, table, security panel and incidents, against the API answer | `run.page.test.js` | R7 to R11, R14 |
| **Page: fleet** | Counts, filters, order stability while a row is open, links to runs, empty states | `fleet.page.test.js` | R12, R13 |
| **Page: config** | Each behaviour's inputs and defaults, Save through the real route, the server's refusal shown, reason required, windows table | `config.page.test.js` | R15, R16 |
| **Safety** | Hostile text in every field renders as text; the static check (no `innerHTML`, `eval`, `document.write`) over the new code | `hostile.page.test.js`, `InspectServerTest#theScriptNeverBuildsHtmlFromRunText` | R20 |
| **Accessibility** | Contrast computed over the stylesheet's tokens for both themes; roles and names; keyboard-only walk through each page; focus visible; a word besides every colour | `a11y.page.test.js`, `ContrastTest` (node) | R17, R19 |
| **Responsive** | At 360, 768 and 1360 px: no horizontal page scroll; tab row wraps; tables scroll in their box | `responsive.page.test.js` | R18 |
| **Health** | No console error or failed request in any scenario; request count per minute with a fleet page open | `health.page.test.js` | R21 |
| **Scale** | 500 tools and 200 agent rows: render time, interaction latency and DOM size within the stated bound | `scale.page.test.js` | R23 |
| **Calm** | Tab order across polls while runs start and end; selection unchanged; a row keeps its place and the "order changed" chip appears and clears; Pause stops every redraw and counts what changed; Live redraws | `calm.page.test.js` | R26 to R28, R35 |
| **Time (server)** | The kept records are bounded and drop the oldest; the timeline marks; the view at a point equals the live view captured then (a capture test over a scenario) and at the end equals the live view of a finished run; 404 before the history; a restored run has history; a 20,000-call soak | `RunHistoryTest`, `TimelineRouteTest`, `ReplayEqualsLiveTest` | R29 to R31, R33 |
| **Time (page)** | The time bar moves the whole run page; the banner and Back to live; controls hidden; markers reachable by keyboard; previous and next incident; nothing changes in a snapshot while polls go on | `time.page.test.js` | R32 |
| **Build checks** | pom and package diff (no dependency), the page is four static files plus fonts | build | R24 |

## 3. Points to cover

- Context points are per agent and per call; two agents interleaved do not mix; a restart (resumed run) does not duplicate points; events beyond 60 drop the oldest.
- An agent whose model is not in the catalog, a catalog entry for a model the agent does not use, a catalog entry changed while a run is open (the next view uses it), a process window and a catalog window together (process wins).
- A failing tool in a run that has ended, a tool that is only a task, a tool and a task with the same name, a tool never run (`unused`), a tool with only no-result calls.
- The order of the table is stable between polls when nothing changed, and an open row stays open and in place when others change state.
- `BROKEN` for a delegate, for a task, with and without a category; `DIVERTED` only (a finished run); neither.
- Security: an agent with no profile, an assumed tool, an accepted agent with a reason containing markup and quotes, a run with no tool calls, a fleet where nothing is notable.
- Configuration: a rule the server refuses (the message appears, nothing is lost from the form), `*` acceptance (the warning appears), removing the last rule of an agent, two admins saving (the existing conflict handling).
- Time: ages ("21 s ago") follow the server's clock, not the browser's, so a skewed laptop shows the same words.
- Themes: switching theme re-draws the gauge, timeline and histogram with the right tokens; the choice survives a reload.

## 4. Not tested here

- Real providers, real long runs and real collectors (owed, as in earlier phases; see VERIFICATION §4).
- Pixel-exact appearance. Screenshots are taken for the documentation and reviewed by eye; they are not golden images.
- Browsers other than Chromium in CI (Firefox and Safari are checked by hand, and listed as owed).

## 5. Quality gates

| Gate | Threshold | How checked |
|---|---|---|
| Existing loom-inspect-core, loom-inspect, loom-fabric tests | pass, unmodified | `mvn verify` |
| New Java classes | line ≥ 90 %, branch ≥ 80 % | JaCoCo |
| Page scripts | every renderer exercised by a browser test; coverage from Chromium's own JS coverage ≥ 85 % of the new functions | Playwright coverage |
| Contrast | every text and graphical token pair ≥ 4.5:1 (3:1 large and graphical) in both themes | `ContrastTest` |
| Dependencies | none added to the product | pom and package diff |
| Console | zero errors, zero failed requests across every scenario | `health.page.test.js` |
