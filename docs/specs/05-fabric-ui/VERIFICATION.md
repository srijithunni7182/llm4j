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
| A7 | Each situation can be produced without a provider | `ScenarioTest` (twelve scenarios) |
| A8 | The page is readable in both themes, works at phone width and by keyboard, and never builds HTML from run text | the accessibility, responsive and safety tests |
| A9 | The existing pages, routes and simulator scenario behave as before | the existing tests, unmodified |
| A10 | Documented, with screenshots | docs review (§5) |

## 2. Traceability: requirement → tests

| Req | Tests | Status |
|---|---|---|
| R1 | `ContextHistoryTest` | ⬜ |
| R2 | `ToolsRouteTest` | ⬜ |
| R3 | `SecurityRouteTest` | ⬜ |
| R4 | `WindowsConfigTest` | ⬜ |
| R5 | `WindowCatalogTest` | ⬜ |
| R6 | `RunSummariesTest` | ⬜ |
| R7 | `run.page.test.js` (banner and diverted line) | ⬜ |
| R8 | `run.page.test.js` (cards, gauges, no-window) | ⬜ |
| R9 | `run.page.test.js` (timeline, markers, table equivalent) | ⬜ |
| R10 | `run.page.test.js` (tools and tasks table) | ⬜ |
| R11 | `run.page.test.js` (security panel) | ⬜ |
| R12 | `fleet.page.test.js` (Tools & tasks) | ⬜ |
| R13 | `fleet.page.test.js` (Security) | ⬜ |
| R14 | `run.page.test.js` (incidents) | ⬜ |
| R15 | `config.page.test.js`, `FabricConfigPhase5Test` | ⬜ |
| R16 | `config.page.test.js` (windows), `WindowsConfigTest` | ⬜ |
| R17 | `ContrastTest`, `a11y.page.test.js` | ⬜ |
| R18 | `responsive.page.test.js` | ⬜ |
| R19 | `a11y.page.test.js` | ⬜ |
| R20 | `hostile.page.test.js`, `InspectServerTest` | ⬜ |
| R21 | `health.page.test.js` | ⬜ |
| R22 | `ScenarioTest`, `StudioUnchangedTest` | ⬜ |
| R23 | `scale.page.test.js` | ⬜ |
| R24 | build checks, `ToolsRouteTest` (405) | ⬜ |

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
- [ ] Every requirement R1–R24 has a passing test
- [ ] Quality gates in §3 pass
- [ ] Manual checks done or listed as owed with the reason
- [ ] Docs complete; §7 has a row for every sub-phase
- [ ] DESIGN.md reflects what was built
- [ ] Status table in `docs/specs/README.md` updated

## 7. Results

| Date | Sub-phase | Result | Notes |
|---|---|---|---|
| | | | |

### Documentation record (per sub-phase)

| Sub-phase | Docs | Samples tested | Javadoc | llms.txt | Stack-level pages | Specs |
|---|---|---|---|---|---|---|
| | | | | | | |

### Owed to the stack-level pages (updated once, at the end of the whole work)

Root `README.md`, `wiki/Home.md`, `docs/README.md`, `docs/THE_STACK.md`: the observability line should now mention the context gauge, the tools and tasks view and the security view.
