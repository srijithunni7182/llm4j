# Phase 5 verification plan: the Fabric page

Status: **draft for review**. Run top to bottom at the end of sub-phase 5e; the relevant rows after each sub-phase. Results in §7.

## 1. Acceptance criteria

| # | Criterion | Evidence |
|---|---|---|
| A1 | From a run's page I can see how full each agent's context is, how it got there and when it was compacted | `run.page.test.js` (context), `ContextHistoryTest` |
| A2 | From one page I can see which tools and tasks fail consistently, across every run, with the reason and which agents use them | `fleet.page.test.js`, `ToolsRouteTest` |
| A3 | When a run broke, the page says which task or delegate broke it and why, and links to the step | `run.page.test.js` (banner) |
| A4 | I can see where the lethal trifecta was exercised or accepted, any suspected injection and any refused access, in a run and across runs, and what the view cannot tell me | `run.page.test.js` (security), `fleet.page.test.js` |
| A5 | I can set every new threshold, accept a trifecta with a written reason, and set a model's context window, from the Configuration page | `config.page.test.js`, `FabricConfigPhase5Test` |
| A6 | A fleet's gauges are not blank when a process was not given a window | `WindowCatalogTest`, `no-window` scenario |
| A7 | Each situation can be produced without a provider | `ScenarioTest` (thirteen scenarios) |
| A8 | The page is readable in both themes, works at phone width and by keyboard, and never builds HTML from run text | the accessibility, responsive and safety tests |
| A9 | The existing pages, routes and simulator scenario behave as before | the existing tests, unmodified |
| A10 | Documented, with screenshots | docs review (§5) |

## 2. Traceability: requirement → tests

| Req | Tests | Status |
|---|---|---|
| R1 | `ContextHistoryTest` (points, events, bounds, two agents, a 20,000-call soak) | ✅ 5a |
| R2 | `ToolsRouteTest`, `FabricFleetRoutesTest` | ✅ 5a |
| R3 | `SecurityRouteTest`, `FabricFleetRoutesTest` | ✅ 5a |
| R4 | `WindowsConfigTest`, `WindowBookTest` | ✅ 5a |
| R5 | `WindowCatalogTest` | ✅ 5a |
| R6 | `RunSummariesTest`, `FabricFleetRoutesTest` | ✅ 5a |
| R7 | `run.page.test.js` (banner and diverted line) | ✅ 5b |
| R8 | `run.page.test.js` (cards, gauges, no-window) | ✅ 5b |
| R9 | `run.page.test.js` (timeline, markers, table equivalent) | ✅ 5b |
| R10 | `run.page.test.js` (tools and tasks table) | ✅ 5b |
| R11 | `run.page.test.js` (security panel) | ✅ 5b |
| R12 | `fleet.page.test.js` (Tools & tasks) | ✅ 5c |
| R13 | `fleet.page.test.js` (Security) | ✅ 5c |
| R14 | `run.page.test.js` (incidents) | ✅ 5b |
| R15 | `config.page.test.js`, `FabricConfigPhase5Test` | ✅ 5d |
| R16 | `config.page.test.js` (windows), `WindowsConfigTest` | ✅ 5d |
| R17 | `ContrastTest`, `a11y.page.test.js` | ⬜ |
| R18 | `responsive.page.test.js` | ⬜ |
| R19 | `a11y.page.test.js` | ⬜ |
| R20 | `hostile.page.test.js`, `InspectServerTest` | ⬜ |
| R21 | `health.page.test.js` | ⬜ |
| R22 | `ScenarioTest` (13 scenarios, names, help), `ScenarioLiveTest` (through a real Fabric), `SimulateTest` unchanged | ✅ 5a |
| R23 | `scale.page.test.js` | ⬜ |
| R24 | `ToolsRouteTest` (405, gate), no dependency added (5a part); the static-files part is checked at 5e | ◐ 5a |
| R25 | `run.page.test.js` (Fabric name, title, mark and favicon; embedded unchanged), `InspectServerTest` (the mark is served) | ✅ 5c |

## 3. Quality gates

As [TEST_STRATEGY.md §5](TEST_STRATEGY.md#5-quality-gates). Results in §7.

## 4. Manual and live checks

1. Open the page in Chrome, Firefox and Safari, in both themes, at phone width, and walk each page with the keyboard only.
2. A screen reader pass (VoiceOver or NVDA) over the run page, the Tools & tasks page and the Security page.
3. A real Fabric with real processes (not the simulator) running the `examples`: look at each page.
4. A real provider run with a small window and a flaky tool: watch the gauge, the timeline, the table and the incidents move.
5. A real planted-page run: the security panel and the fleet Security page; search every visible text and the page source for the planted text.

## 5. Documentation checks

Per the [rule](../README.md#documents-to-update-at-the-end-of-every-sub-phase): `docs/OBSERVABILITY.md` (what the page looks like, with the new screenshots), `wiki/Fabric-Server.md`,
`wiki/Observability.md`, `wiki/Observability-Incidents-and-Rules.md` (the Configuration editor), `docs/loom-inspect/DESIGN.md` (§11o), `llms.txt`, `src/examples/fabric-sim/README.md` (the scenarios).
Stack-level pages are owed once at the end of the whole work.

## 6. Exit checklist

- [ ] Decisions in DESIGN.md §9 recorded
- [ ] Acceptance criteria A1–A10 have evidence
- [ ] Every requirement R1–R25 has a passing test
- [ ] Quality gates in §3 pass
- [ ] Manual checks done or listed as owed with the reason
- [ ] Docs complete; §7 has a row for every sub-phase
- [ ] DESIGN.md reflects what was built
- [ ] Status table in `docs/specs/README.md` updated

## 7. Results

| Date | Sub-phase | Result | Notes |
|---|---|---|---|
| 2026-10-14 | 5a | Pass | Tests first for the view additions, the window catalog and its file, the `windows` configuration section, the two fleet routes (embedded and Fabric), the summaries and the thirteen scenarios (and a live one through a real Fabric): 77 new tests. Findings while building: `trifecta` and `trifecta-accepted` are two scenarios (the design said one with and without); an acceptance saved after an incident was raised does not lower it (rules are normally in place before the run), noted in the docs; a short run exited before its last events reached the server, so the simulator waits for delivery; the scenario path did not hand the executor the secret store that holds the server key. The scenarios are thirteen, not twelve |
| 2026-10-14 | 5b | Pass | The run page: `RunViewForPageTest` (view shape the page reads), the fixture server (`sim/Fixtures`) and 13 browser tests (Playwright, Chromium) over eleven scenarios: banner and diverted line, Context and Tasks cards, hatched bar without a window, timeline with markers and table equivalent, tools and tasks table with expansion, Security panel (trifecta, accepted, injected page, refused access) and the new incident kinds. Full inspect-core and inspect suites pass (199 Java tests in the two modules). Findings from screenshots: the timeline was drawn in a small viewBox and sat narrow in a wide panel (now 960 wide); security card values inherited upper-casing |
| 2026-10-14 | 5c | Pass | The fleet pages: Tools & tasks (counts by health, three filters, table over every run with a last-ten strip, expansion with a link to the run it last failed in, order held while a row is open) and Security (counts, agents that matter with reasons, open findings with links, the fixed statement), with tab badges. 7 new browser tests (`fleet.page.test.js`) and 2 route tests; all 21 browser tests and the inspect-core, inspect and fabric suites pass. Findings: fleet rows had no last-ten outcomes (a merged summary has none; the registry now joins each run's last ten, oldest run first) and the page computed runs-that-ran from a field fleet rows lack (now uses `ran`); `/api/runs` gained `fleet.toolsFailing` for the badge so a fleet page costs one extra poll. One end-to-end OTLP test (`FabricEndToEndTest`) failed once under full-suite load and passes alone and on re-run: a timing-sensitive wait, not touched by this phase. Also R25: Fabric is branded Fabric with its own mark |
| 2026-10-14 | 5d | Pass | The Configuration page: the rule editor now offers fourteen behaviours (the eight new ones with their own inputs, defaults beside each, an accepted trifecta needing a reason, the server's refusals and the `*` warning shown as written) and a context-windows card (table by model, set / edit / remove, save, discard, text form) saved through the same route and audit as prices. 6 browser tests (`config.page.test.js`) run against a real Fabric started from its CLI (new `startFabric` helper); all 27 browser tests and the inspect-core and fabric Java tests pass. Findings: the Configuration tab sits behind Fabric's sign-in, so the test signs in through the page; the editor reads the options after the configuration loads |

### Documentation record (per sub-phase)

| Sub-phase | Docs | Samples tested | Javadoc | llms.txt | Stack-level pages | Specs |
|---|---|---|---|---|---|---|
| 5a | ✅ wiki Fabric-Server (windows section, fleet routes), loom-inspect DESIGN §11o, fabric-sim README (scenarios) | ✅ scenarios run in tests | ✅ | n/a | deferred to the end | ✅ traceability, results |
| 5b | ✅ loom-inspect DESIGN §11o, specs | ✅ browser tests run the scenarios | ✅ | n/a | deferred to the end | ✅ traceability, results |
| 5c | ✅ loom-inspect DESIGN §11o, specs | ✅ browser tests run the scenarios | ✅ | n/a | deferred to the end | ✅ traceability, results |
| 5d | ✅ loom-inspect DESIGN §11o, wiki Fabric-Server | ✅ browser tests against a real Fabric | ✅ | n/a | deferred to the end | ✅ traceability, results |

### Owed to the stack-level pages (updated once, at the end of the whole work)

Root `README.md`, `wiki/Home.md`, `docs/README.md`, `docs/THE_STACK.md`: the observability line should now mention the context gauge, the tools and tasks view and the security view.
