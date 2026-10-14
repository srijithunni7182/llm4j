# Phase 5 verification plan: the Fabric page

Status: **built; manual and live checks owed (§4)**. Run top to bottom at the end of sub-phase 5e; the relevant rows after each sub-phase. Results in §7.

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
| A12 | A coding agent with only the skills and the jars can take a finished workflow to a running, observed one (Fabric up, the workflow reporting to it) and can explain the pages to the developer | `DeployerScriptsTest`, `verify-skill-package.sh`, `DeployerSkillTest` |
| A11 | I can read a page without it moving, and go back to see how a run looked at an earlier point | `calm.page.test.js`, `time.page.test.js`, `ReplayEqualsLiveTest` |

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
| R17 | `contrast.test.js` (token pairs, both themes), `a11y.page.test.js` (as drawn, every page, both themes) | ✅ 5e |
| R18 | `responsive.page.test.js` (360, 768, 1360 px; tables scroll in their box; Configuration at 360) | ✅ 5e |
| R19 | `a11y.page.test.js` (keyboard walk, focus visible, meter, markers, headers, a word beside every colour) | ✅ 5e |
| R20 | `hostile.page.test.js` (markup, long names, bidi, emoji, control characters in every run and fleet view), `InspectServerTest` (static check) | ✅ 5e |
| R21 | `health.page.test.js` (all scenarios, all views, no error; request counts) | ✅ 5e |
| R22 | `ScenarioTest` (13 scenarios, names, help), `ScenarioLiveTest` (through a real Fabric), `SimulateTest` unchanged | ✅ 5a |
| R23 | `scale.page.test.js` (500 tools, 200 agents) | ✅ 5e |
| R24 | `ToolsRouteTest` (405, gate), no dependency added (5a part); the static-files part by `PageFilesTest` (four files, two marks, fonts; no outside address), no pom changed since the phase began | ✅ 5e |
| R25 | `run.page.test.js` (Fabric name, title, mark and favicon; embedded unchanged), `InspectServerTest` (the mark is served) | ✅ 5c |
| R26 | `calm.page.test.js` (tab order, selection) | ✅ 5f-1 |
| R27 | `calm.page.test.js` (held order, Re-sort) | ✅ 5f-1 |
| R28 | `calm.page.test.js` (Live / Paused) | ✅ 5f-1 |
| R29 | `TimelineRouteTest` | ✅ 5f-2 |
| R30 | `TimelineRouteTest`, `RunHistoryTest` | ✅ 5f-2 |
| R31 | `ReplayEqualsLiveTest` (nine real scenarios), `RunHistoryTest` | ✅ 5f-2 |
| R32 | `time.page.test.js` | ✅ 5f-2 |
| R33 | `RunHistoryTest` (bound, 60,000-record soak), `FabricServerTest` (restored run has history) | ✅ 5f-2 |
| R35 | `calm.page.test.js` (tab cap, Runs list, filter, start times, a finished run leaves the row) | ✅ 5f-1 |
| R34 | `FabricSimScriptTest` (the preset, the README), `fabric-sim` README | ✅ 5f-1 |
| R36 | `GuideChapter14Test` | ✅ 5f-3a |
| R37 | `GuideChapter14Test`, `GuideDeployTest`, `JavaQuickReferenceTest` | ✅ 5f-3a |
| R38 | `SkillDriftTest`, `SkillKitTest` | ✅ 5f-3a |
| R39 | `GuideChapter14Test` (snippets), `SkillDriftTest` | ✅ 5f-3a |
| R40 | `DeployerSkillTest` | ✅ 5f-3 |
| R41 | `DeployerScriptsTest` (six: a real Fabric, idempotent start, health wait, the key stored and never printed, an unsafe bind refused, stop twice) | ✅ 5f-3 |
| R42 | `SkillDriftTest` (page elements named exist), `DeployerSkillTest` | ✅ 5f-3 |
| R43 | `DeployerScriptsTest`, `DeployerSkillTest` (safety rules) | ✅ 5f-3 |
| R44 | `GuideCommandTest`, `verify-skill-package.sh` | ✅ 5f-3 |
| R45 | `DeployerSkillTest` (weave commands, options and guide pages), `DeployerFabricDriftTest` (Fabric commands and options), `SkillKitTest` (the workflow skill) | ✅ 5f-3 |
| R46 | docs review | ✅ 5f-3 |

## 3. Quality gates

As [TEST_STRATEGY.md §5](TEST_STRATEGY.md#5-quality-gates). Results in §7.

## 4. Manual and live checks

0. Give the deployer skill to a coding agent with a real finished workflow and watch it: does it ask before the things it must ask about, does it stop at a refusal, does the developer end up able to find one thing on each page? Run the `.ps1` scripts on Windows.
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

- [x] Decisions in DESIGN.md §9 recorded
- [x] Acceptance criteria A1–A10 have evidence
- [x] Every requirement R1–R46 has a passing test
- [x] Quality gates in §3 pass
- [x] Manual checks listed as owed with the reason: browsers other than Chromium, a screen reader, a real Fabric with real processes, a real provider run with a flaky tool and a small window, a real planted-page run (none of these can be done in this environment)
- [x] Docs complete; §7 has a row for every sub-phase
- [x] DESIGN.md reflects what was built
- [x] Status table in `docs/specs/README.md` updated

## 7. Results

| Date | Sub-phase | Result | Notes |
|---|---|---|---|
| 2026-10-14 | 5a | Pass | Tests first for the view additions, the window catalog and its file, the `windows` configuration section, the two fleet routes (embedded and Fabric), the summaries and the thirteen scenarios (and a live one through a real Fabric): 77 new tests. Findings while building: `trifecta` and `trifecta-accepted` are two scenarios (the design said one with and without); an acceptance saved after an incident was raised does not lower it (rules are normally in place before the run), noted in the docs; a short run exited before its last events reached the server, so the simulator waits for delivery; the scenario path did not hand the executor the secret store that holds the server key. The scenarios are thirteen, not twelve |
| 2026-10-14 | 5b | Pass | The run page: `RunViewForPageTest` (view shape the page reads), the fixture server (`sim/Fixtures`) and 13 browser tests (Playwright, Chromium) over eleven scenarios: banner and diverted line, Context and Tasks cards, hatched bar without a window, timeline with markers and table equivalent, tools and tasks table with expansion, Security panel (trifecta, accepted, injected page, refused access) and the new incident kinds. Full inspect-core and inspect suites pass (199 Java tests in the two modules). Findings from screenshots: the timeline was drawn in a small viewBox and sat narrow in a wide panel (now 960 wide); security card values inherited upper-casing |
| 2026-10-14 | 5c | Pass | The fleet pages: Tools & tasks (counts by health, three filters, table over every run with a last-ten strip, expansion with a link to the run it last failed in, order held while a row is open) and Security (counts, agents that matter with reasons, open findings with links, the fixed statement), with tab badges. 7 new browser tests (`fleet.page.test.js`) and 2 route tests; all 21 browser tests and the inspect-core, inspect and fabric suites pass. Findings: fleet rows had no last-ten outcomes (a merged summary has none; the registry now joins each run's last ten, oldest run first) and the page computed runs-that-ran from a field fleet rows lack (now uses `ran`); `/api/runs` gained `fleet.toolsFailing` for the badge so a fleet page costs one extra poll. One end-to-end OTLP test (`FabricEndToEndTest`) failed once under full-suite load and passes alone and on re-run: a timing-sensitive wait, not touched by this phase. Also R25: Fabric is branded Fabric with its own mark |
| 2026-10-14 | 5d | Pass | The Configuration page: the rule editor now offers fourteen behaviours (the eight new ones with their own inputs, defaults beside each, an accepted trifecta needing a reason, the server's refusals and the `*` warning shown as written) and a context-windows card (table by model, set / edit / remove, save, discard, text form) saved through the same route and audit as prices. 6 browser tests (`config.page.test.js`) run against a real Fabric started from its CLI (new `startFabric` helper); all 27 browser tests and the inspect-core and fabric Java tests pass. Findings: the Configuration tab sits behind Fabric's sign-in, so the test signs in through the page; the editor reads the options after the configuration loads |
| 2026-10-14 | 5e | Pass | The cross-cutting checks, all as tests: contrast of every token pair in both themes and of every visible text as drawn on every page; a keyboard-only walk, visible focus, roles and names, a word beside every colour; 360, 768 and 1360 px; hostile text (markup, 400-character names, right-to-left, emoji, control characters) through every run and fleet view; no console or page error and no failed request over all eleven scenarios; request rate on a fleet page; 500 tools and 200 agents. Findings fixed: two token pairs fell short (dark `line-strong` on raised surfaces 2.99:1, light `rung-suggest` 4.28:1 and `rung-watch` 3.82:1), faint timestamps on tinted rows (3.85:1), a hostile or long name widened the page by thousands of pixels (text now wraps; a name is cut in its cell with the whole in its title), the Configuration page overflowed at 360 px, and the fleet table drew all 500 rows (it now draws the first 200 and says so). Screenshots for the wiki were taken from the scenarios. The page is still four static files, two marks and the fonts, and no pom changed in the phase |
| 2026-10-14 | 5f-1 | Pass | Calm: tabs keep the order they first appeared in, a new run is appended and never takes the selection, at most eight tabs (selected, live, just finished), a **Runs** list (filter, status chips, grouping, start times) holds the rest, held row order with an "order changed / Re-sort" chip on the run's and the fleet's tools tables, a **Live / Paused** control that freezes every redraw and counts what changed from the cheap run list, and `fabric-sim.sh calm`. 8 browser tests (`calm.page.test.js`), 2 script tests; all 55 browser tests pass. Findings: the existing tests assumed a tab per run (the helper now picks a finished run from the Runs list); a selected run stays in the tab row while another page is open; a finished run is shown for a minute only if it was seen live; the old `.run` row style clashed with the list's rows (renamed) |
| 2026-10-14 | 5f-2 | Pass | Time: each run keeps the records it was built from (at most 50,000; the oldest drop and a view then says `partial`), `GET /api/runs/{trace}/timeline` (at most 300 marks: start, end, incidents opened and closed, failures, compactions, changes of state) and `GET /api/runs/{trace}?at=SEQ` (the view rebuilt by the same code as the live view, `live: false`, `asOf`, no controls; 404 with where the history starts), and a time bar on the run page (slider, a marker for each mark, previous and next incident, a banner that says it is a snapshot with Back to live). 7 + 4 + 2 + 1 Java tests, 9 browser tests (`time.page.test.js`); all 64 browser tests and the Java suites pass. Findings: a past view uses today's rules, prices and windows (the records are the past, the settings are now) and says so in the docs; the soak test takes about 8 s so it is the slow one; no junit-params in the build, so the scenario checks loop |
| 2026-10-14 | 5f-3a | Pass | The workflow skill and the guide, brought up to date: a new chapter 14 (context and compaction: when, how, the windows, your own strategy, the page and its three signals), chapters 6 (failing steps: `retry`, `on_failure`, what a failed run names; the context block), 7 (the trifecta live, `reach`, accepting it in writing, injection and refused access), 9 (watching a run, the hand-over to the deployer skill), 10 (do, don't, checklist), 12 (Java: windows and your own strategy, an agent in Java, statistics, watching from a host), the guide's map, `SKILL.md` (description, stage 14, three things a real workflow needs, when to hand over, honest edges) and `llms.txt`. `GuideChapter14Test` (4 tests: listing and the jar, the chapter's content, what the others say, three Loom blocks pass `weave check`), the existing guide, skill and link tests updated and passing. The Java reference block was compiled with `javac` against the built jars here (the module's `GuideCompilesTest` needs the network for its test dependencies and runs in CI). Found: the page list held chapter 6 twice (fixed); `note` is a reserved word for a result name |
| 2026-10-14 | 5f-3b, 5f-3c | Pass | The deployer skill `llm4j-loom-deployer`: `SKILL.md`, eight chapters (`weave guide deploy`, `d1` to `d8`: shape, security, rules and windows and prices, its own page, Fabric, reporting to Fabric, reading the pages, operating), launch scripts for macOS and Linux and for Windows (`fabric-up`, `fabric-status`, `fabric-down`, `run-observed`), `weave guide --install-skill` installing both skills (`--skill` for one), the package (`dist/llm4j-loom-deployer.zip` with the jars in `bin/`) and its check from an empty folder. Tests: `DeployerSkillTest` (12), `DeployerFabricDriftTest` (2), `DeployerScriptsTest` (6, real Fabric and a real workflow), the guide and skill tests updated; `verify-skill-package.sh` brings up a Fabric from the package, reports a workflow to it and stops both. Full suites pass (Loom 1,872, inspect-core 333, fabric 56, inspect 209). Findings while building the scripts: `java -version` prints the `JAVA_TOOL_OPTIONS` line first (version parsing), a `tr | head` pipe under `pipefail` killed the script silently, an empty argument array expanded to an empty argument, and the package check's `grep -q` under `pipefail` failed on a SIGPIPE. **Owed:** the `.ps1` scripts have not been run (no Windows host here); a coding agent driving the skill against a live developer has not been tried |

### Documentation record (per sub-phase)

| Sub-phase | Docs | Samples tested | Javadoc | llms.txt | Stack-level pages | Specs |
|---|---|---|---|---|---|---|
| 5a | ✅ wiki Fabric-Server (windows section, fleet routes), loom-inspect DESIGN §11o, fabric-sim README (scenarios) | ✅ scenarios run in tests | ✅ | n/a | deferred to the end | ✅ traceability, results |
| 5b | ✅ loom-inspect DESIGN §11o, specs | ✅ browser tests run the scenarios | ✅ | n/a | deferred to the end | ✅ traceability, results |
| 5c | ✅ loom-inspect DESIGN §11o, specs | ✅ browser tests run the scenarios | ✅ | n/a | deferred to the end | ✅ traceability, results |
| 5d | ✅ loom-inspect DESIGN §11o, wiki Fabric-Server | ✅ browser tests against a real Fabric | ✅ | n/a | deferred to the end | ✅ traceability, results |
| 5e | ✅ wiki Fabric-Server (screenshots, what the pages show), specs | ✅ browser tests over the scenarios | ✅ | n/a | deferred to the end | ✅ traceability, results |
| 5f-1 | ✅ loom-inspect DESIGN §11o, wiki Fabric-Server, fabric-sim README | ✅ browser tests | ✅ | n/a | deferred to the end | ✅ traceability, results |
| 5f-2 | ✅ loom-inspect DESIGN §11o, wiki Fabric-Server | ✅ browser tests | ✅ | n/a | deferred to the end | ✅ traceability, results |
| 5f-3a | ✅ guide chapters, SKILL.md, llms.txt | ✅ Loom blocks pass `weave check`; Java compiles | ✅ | ✅ | deferred to the end | ✅ traceability, results |
| 5f-3b/c | ✅ docs/deploy, SKILL.md, PUBLISHING_SKILL.md, wiki Deployer-Skill, OBSERVABILITY_DEPLOYMENT | ✅ scripts run against a real Fabric | ✅ | ✅ | deferred to the end | ✅ traceability, results |

### Owed to the stack-level pages (updated once, at the end of the whole work)

Root `README.md`, `wiki/Home.md`, `docs/README.md`, `docs/THE_STACK.md`: the observability line should now mention the context gauge, the tools and tasks view and the security view.
