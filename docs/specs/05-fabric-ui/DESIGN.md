# Phase 5 design: the Fabric page (context, tools and tasks, security, thresholds)

Status: **draft for review (decisions in §9 taken as recommended, per the standing instruction)**. Layer: the page shared by the embedded add-on and the Fabric server
(`loom-inspect-core/.../ui`), the server routes (`loom-inspect-core` and `loom-fabric`), and the simulator (`loom-inspect/.../sim`).
Companion documents: [TEST_STRATEGY.md](TEST_STRATEGY.md), [VERIFICATION.md](VERIFICATION.md), [roadmap](../README.md). Builds on [Phase 4](../04-inspect-and-incidents/DESIGN.md)
(run state, incidents, rules, tool health, task health, security) which already computes everything this phase draws.

**The mockup** ([Fabric Phase 5 Mockup](https://claude.ai/artifact/5wRYUaLamn8zWwyY5sz8Gf), five screens; private to the author) was reviewed before this spec. §3 states what it
showed and what changed in review; the spec, not the mockup, is what is built.

## 1. Problem

Phase 4 made a run's cost, performance, stability, quality and security *knowable*: the run view carries per-agent context fill and compactions, per-tool and per-task health
(success rate, latency percentiles, failures by category), the new incidents (`BROKEN`, `DIVERTED`, `TRIFECTA`, `INJECTION`, `ACCESS`, and the five of 4c) and a `security`
section. The page shows almost none of it: it has no context gauge, no compaction history, no tools table, no security view, and its Configuration page edits only the older
rules. Someone watching a fleet of workflows cannot yet see which tool is failing across runs, which agent is about to run out of window, or which agent used the lethal trifecta.

## 2. Goals and non-goals

Goals:

- The **run page** shows the context gauge and compaction timeline, the tools-and-tasks table, the security panel, the cause when a run broke, and the new incidents.
- A **Tools & tasks** page across every run the server knows, failing first: the tools-centric view asked for in phase 4.
- A **Security** page across runs: where the trifecta was exercised or accepted, suspected injections, refused access.
- The **Configuration** page edits the new thresholds (context, truncation, compaction, tool success rate, latency spike, access refused, injection scan, accepted trifecta with its
  reason) and a **context-window catalog** beside the price table.
- **Simulator scenarios** that produce each of these situations without a provider, used as the test fixtures for the page.
- The same page for the embedded add-on and for Fabric (one code base), read-only except where admin rules already allow edits.

Non-goals (stated so they are not mistaken for omissions):

- No new records or detectors: if the page needs a number, 5a adds it to the view, not a new detector. (One exception, the compaction events list, is a view addition.)
- No blocking, pausing or acting on security findings from the page (observe only, as in 4g). Existing pause, suspend and stop controls are untouched.
- No live "what would this threshold have caught" preview on the Configuration page (decision 1): it needs a replay engine over history and is a follow-up.
- No charting library: the page draws with inline SVG and the DOM, as it does today, and never builds HTML from run text.
- No new runtime dependency, no build step for the page.

## 3. What the mockup showed, and what review changed

| Screen | Showed | Outcome of review |
|---|---|---|
| 1 Run page | A red cause banner (`BROKEN`); cards for Context and Tasks; per-agent context gauges with the 85 % and 95 % marks; a context-over-calls line with a marker per compaction; a tools-and-tasks table with health and last failure; incidents incl. `BROKEN`, `DIVERTED`, `CONTEXT` | Kept. The banner links to the step in the workflow graph |
| 2 Tools & tasks | Counts by health; filters (health, kind, process); one row per tool or task with success, last ten outcomes, p50/p95, health and why; an expanded row (failures by category, who calls it, latency histogram, the run it last failed in) | Kept. This is the tools-centric view asked for in phase 4 |
| 3 Configuration | The inspect-rules editor with the new thresholds; a live preview of what a threshold would have caught | Thresholds kept. **Preview cut** (decision 1) |
| 4 Simulator | Eight scenarios and what each should show | Kept as the fixture set; the table is §7 |
| 5 Security | Four posture cards; a per-agent matrix (can / used for the three legs, verdict); findings with evidence; guards in force; "what this cannot tell you" | Kept, plus the `accepted` state with its reason (added in 4g), and a fleet Security page |

Review answers (decisions 2 to 5): "Tools & tasks" and "Security" are top-level tabs beside Processes and Configuration and also panels on each run; an agent without a known window shows
a hatched bar and a hint, never an invented percentage; tasks share the tools table, told apart by a kind label and a filter; the catalog of context windows is built (5a, 5d) so the hatched bar is the exception.

## 4. Data the page needs (5a)

Everything but three things is already in `RunState.view()`. The additions, each backward compatible (new keys; nothing renamed or removed):

1. **Context history.** `agents[].context` gains `points` (the last 120 model calls: `{n, used, fraction}`; `n` is the agent's call number) and `events` (the last 60 compactions:
   `{n, strategy, outcome, before, after}` with `n` the call number it happened after). The existing `series` is kept as it is. Bounded; numbers only.
2. **Fleet tools.** `GET /api/tools[?client=]` returns the roll-up (`RunRegistry.toolRollup`, tools and tasks, `kind`, failing first) as `{rows, counts}`: each row is `ToolSummary.toMap()`
   plus `lastRun` (client and trace of the run it last failed in, when there is one) and `agents`; `counts` is `{failing, degraded, healthy, unused}`. In the embedded page and in Fabric.
3. **Fleet security.** `GET /api/security[?client=]` returns `{counts, agents, incidents}`: the roll-up counts (`RunRegistry.securityRollup`), one row per (run, agent) whose verdict is `exercised`,
   `accepted` or `armed`, and the open `TRIFECTA`, `INJECTION` and `ACCESS` incidents with the run they belong to. Bounded (200 agent rows, 100 incidents, newest first).
4. **Context windows, centrally.** A Configuration section `windows` (model to usable window in tokens; same store, validation and audit as prices). When a run's context record has no
   window, `RunState` fills `window`, `usable` and `fraction` from the catalog, using the model of the agent (the snapshot already carries it), and the `CONTEXT` incident uses the filled
   fraction. A window the process reported always wins. The view says where it came from (`windowSource`: `process` or `catalog`).
5. **Tab badges.** `GET /api/runs` summaries gain `security` (open trifecta, injection and access incidents) so tabs can show a count.

## 5. The page (5b, 5c, 5d)

One page, as today: `index.html`, `app.js`, `app.css`, no build step, text only through `textContent`, drawing in inline SVG. New renderers, each a pure function from the view (or API
answer) to DOM, so a browser test can drive them with fixture data.

### 5.1 Run page (5b)

- **Cause banner.** When the run's status is failed and its `BROKEN` incident exists: "The run stopped: the task X failed", the category, attempts and step, "no on_failure to take over".
  Click scrolls to and highlights the step in the workflow graph. A run that finished but had `DIVERTED` incidents shows a quieter line ("1 step went through on_failure").
- **Cards.** The five cards become: Tokens, Cost, Context (the fullest agent's fraction, with the agent named and the compaction count), Tool calls, Tasks (count, failed, diverted).
  Colours follow severity, never alone: every state also has a word.
- **Context and compaction panel.** One row per agent: name, a bar (fraction of the usable window; marks at the warn and high thresholds in force for that agent), the numbers,
  the compaction count and the last compaction (strategy, tokens freed). Without a window: a hatched bar and "no window known: set one on the Configuration page or in a `.windows` file".
  Beside the rows, the timeline of the selected agent (default: the fullest): `points` as a line, `events` as markers with a tooltip (strategy, outcome, before and after, messages dropped),
  the threshold lines drawn. Keyboard-reachable markers; the same facts in a table under a disclosure for screen readers.
- **Tools and tasks table.** Columns: name, kind, calls, success (bar and "n of m"), p50 / p95, health word, last failure (category and age). Worst health first, then calls. Filter chips:
  all, tools, tasks. A click or Enter expands a row: failures by category (bars), calls per agent, the last ten outcomes, a latency histogram, and the incidents it raised.
  A tool and a task with one name are two rows.
- **Security panel** (its own tab on the run, as in the mockup): four posture cards (trifecta, injection, access, personal data); the per-agent matrix with the three legs, each shown
  as *used* (tools named), *can* (declared, not used) or none, and the verdict word (`none`, `one`, `two`, `armed`, `exercised`, `accepted`; an `accepted` row shows the reason);
  findings (the three incident kinds) with evidence; the guards in force and counts; and the fixed statement of what the view cannot tell you. A leg that rests on an assumed tool is
  marked "assumed". An agent without the script's profile shows the used legs only and says the "can" side is unknown.
- **Incidents panel.** The new kinds get a label and their evidence formatted like the others; `BROKEN` first when open. Tabs show the count of open incidents as today.

### 5.2 Fleet pages (5c)

- **Tools & tasks.** The mockup's page: counts by health, filters (health, kind, process), the table of §5.1 over every run, `last 10` strip, the "why" text from the verdict reason, an expanded
  row with a link to the run it last failed in. Refreshes with the page's existing poll; sorting is stable, so a row does not jump under the pointer while it is open.
- **Security.** The posture counts across runs; the (run, agent) rows that are `exercised`, `accepted` or `armed` with their verdict and reason; the open security incidents with a link to
  the run. Same words and the same "cannot tell you" statement as the run panel.
- **Badges.** Tools & tasks shows the failing count; Security shows the open security incidents; both in the tab, as the processes tab does today.

### 5.3 Configuration page (5d)

The existing rules editor (agent, behaviour, threshold, Add rule, a table with defaults, Save and Discard, Edit as text) gains these behaviours, each with the inputs the rule needs
and the default shown beside the value:

| Behaviour | Inputs | Default |
|---|---|---|
| Context nearly full | warn %, high % | 85, 95 |
| Truncated answers | times | 1 |
| Compaction thrash | n in m calls | 4 in 10 |
| Tool success rate | below %, over calls | 70, 10 |
| Latency spike | factor | 5× p50 |
| Access refused | n in span | 3 in 60 s |
| Injection scan | on / off | on |
| Accepted trifecta | agent, **reason (required)** | not accepted |

The editor refuses what the file refuses and shows the same message (the server's check is the one source; the page does not re-implement the grammar). Accepting a trifecta needs a
non-empty reason and, for `*`, shows the warning the check gives. Below the rules: the **context windows** table (model, usable window; add, edit, remove; same Save and audit as prices).
The price table is unchanged.

### 5.4 Cross-cutting

- Light and dark themes (the existing toggle); every colour pair meets 4.5:1 (3:1 for large text); state is never colour alone.
- Works from 360 px wide (tables scroll in their own box; nothing makes the page scroll sideways); keyboard operable (tabs, filter chips, row expansion, markers); roles and labels on
  tables, tabs, the gauge (`role="meter"`) and the timeline (an accessible table equivalent).
- Never builds HTML from run text; long names truncate with the full text in `title`; a hostile tool name or error text is shown as text (a test).
- Empty states say what is missing and why ("no tools have run yet", "no run has declared what its agents can reach").
- No console errors; no request storm: one poll for the run view as today plus one for the active fleet page, at the existing interval.

## 6. Requirements (testable)

| # | Requirement |
|---|---|
| R1 | `agents[].context` has `points` (at most 120, `{n, used, fraction}`) and `events` (at most 60, `{n, strategy, outcome, before, after}`); `series` is unchanged; a soak of 20,000 model calls keeps both bounded |
| R2 | `GET /api/tools` returns `{rows, counts}` over every run (or one client's), tools and tasks with `kind`, failing first, each row with `lastRun` when it has failed; same answer embedded and in Fabric; gated like the other `/api/` routes |
| R3 | `GET /api/security` returns `{counts, agents, incidents}` as in §4; bounded; one client's when asked |
| R4 | The `windows` configuration section is saved, validated (positive whole tokens, model name rules), audited and persisted like prices; a bad value is refused with its line; old stores load |
| R5 | A run whose agent reported no window gets `window`, `usable`, `fraction` and `windowSource: catalog` from the catalog by the agent's model; a process-reported window wins and says `process`; the `CONTEXT` incident uses the filled fraction; a model not in the catalog changes nothing |
| R6 | `/api/runs` summaries carry `security` (open security incidents); older consumers are unaffected |
| R7 | The run page shows the cause banner for a failed run with a `BROKEN` incident (words: the kind, name, category, attempts, step) and the quieter diverted line; neither appears for a run without them |
| R8 | The Context card and panel show, per agent, the fraction, numbers, compaction count and last compaction; threshold marks are the ones in force for the agent; an agent without a window shows the hatched bar and the hint, never a percentage |
| R9 | The timeline draws `points` and `events` for the selected agent with the thresholds; every marker is keyboard reachable and has its facts in a tooltip and in the equivalent table |
| R10 | The tools-and-tasks table lists tools and tasks (kind shown), worst health first; filters work; a row expands to failures by category, calls per agent, last ten outcomes, latency histogram and incidents; a tool and a task of one name are two rows |
| R11 | The Security panel shows the four cards, the per-agent matrix (used, can, none, assumed marks, verdict word, accepted reason), the findings with evidence, the guards, and the fixed "cannot tell you" statement; without a profile it says the "can" side is unknown |
| R12 | The Tools & tasks page shows counts by health, the three filters, the table over every run, a link to the run each failing row last failed in, and a stable order while a row is open |
| R13 | The Security page shows the counts, the (run, agent) rows that are `exercised`, `accepted` or `armed` with the reason, and the open security incidents with links; empty states say why |
| R14 | The incidents panel shows `BROKEN`, `DIVERTED`, `TRIFECTA`, `INJECTION` and `ACCESS` with a label, severity word and evidence; `BROKEN` first |
| R15 | The Configuration page edits the eight behaviours of §5.3 with their inputs and defaults; Save writes through the existing structured route; the server's refusal is shown verbatim; an accepted trifecta needs a reason; `*` shows the check's warning |
| R16 | The Configuration page edits the context-window catalog (add, edit, remove, save, discard) with the same audit as prices |
| R17 | Themes: both themes meet 4.5:1 for text and 3:1 for large text and graphical objects (checked by computation over the stylesheet's tokens); every state has a word besides its colour |
| R18 | At 360 px the page has no horizontal page scroll; tables scroll inside their own box; the tab row wraps |
| R19 | Keyboard: tabs, filter chips, row expansion, markers and the configuration controls are reachable and operable without a pointer; focus is visible; roles and labels as in §5.4 |
| R20 | The page never builds HTML from run text (the existing static check, extended to the new code); a tool name, an error text or an agent name containing markup is shown as text |
| R21 | No console error or failed request in any scenario of §7; one extra poll at most while a fleet page is open |
| R22 | Each scenario of §7 (thirteen) runs without a provider and produces the view its table row says; the scenarios are selectable by name from `fabric-sim.sh` and the existing Studio scenario is unchanged |
| R23 | With 500 tools and 200 agent rows the fleet pages render within a fixed budget and stay responsive (virtualised or paged beyond a bound; the bound is stated in the page) |
| R24 | No new dependency; the page remains four static files plus fonts and the two marks; the server answers the new routes read-only (405 for other methods) |
| R25 | Against a Fabric server the page is branded Fabric: the name in the header, the page title, the favicon and a mark of its own (`/fabric-logo.svg`, a woven grid); an embedded page keeps loom·inspect and the Loom mark. The brand is decided by the same probe that turns on the Processes view |

## 7. Simulator scenarios (5a, used as page fixtures)

`fabric-sim.sh SCENARIO=name` (several allowed, comma separated; default as today). Each runs a process that reports through the real link, no provider.

| Scenario | What happens | What the page shows |
|---|---|---|
| `context-fills` | An agent's prompt grows over 30 calls; a window is known; compaction fires three times | Gauge past 85 %, a timeline with three drops, a `CONTEXT` incident that closes after a compaction |
| `compaction-thrash` | Compaction runs often and frees little | `THRASH` incident, barely visible drops |
| `no-window` | An agent with no known window | The hatched bar and hint; after a catalog entry is added, a real gauge |
| `flaky-tool` | One tool fails most calls (rate limits, timeouts), one always works | A failing row and a healthy row; a tool-rate incident |
| `task-diverted` | A task throws; its `on_failure` takes over; the workflow completes | Finished run, "1 step went through on_failure", a `DIVERTED` incident |
| `task-broken` | A task fails after retries with no `on_failure` | The cause banner, a `BROKEN` incident that stays, a failing task row |
| `delegate-broken` | A delegate's model keeps failing | The same banner naming the delegate |
| `latency-spike` | A tool becomes ten times slower | A `SPIKE` incident; the p95 column jumps |
| `truncated-answers` | Replies stop at the token limit | A `TRUNCATED` incident |
| `trifecta` | One agent reads a page, looks up a customer and sends mail | `TRIFECTA` high |
| `trifecta-accepted` | The same, with the trifecta accepted in writing in the rules file | `TRIFECTA` as info with the reason, the verdict `accepted` |
| `injected-page` | A page carries a planted instruction; the agent's next call is unusual | `INJECTION` high; the evidence has group names only; the planted text appears nowhere |
| `access-refused` | A tool refuses a role three times; an agent calls a tool it does not have | An `ACCESS` warning and an info line; counts in the security panel |

## 8. Sub-phases

| Step | Delivers | Requirements |
|---|---|---|
| 5a | View additions (context points and events), `/api/tools`, `/api/security`, summaries' `security`, the `windows` section and the catalog fill; the simulator scenarios and their tests | R1 to R6, R22, R24 |
| 5b | The run page: banner, cards, context panel and timeline, tools and tasks table, security panel, incidents | R7 to R11, R14, R20 |
| 5c | The two fleet pages and the tab badges | R12, R13, R23 |
| 5d | The Configuration page: the eight behaviours and the windows catalog | R15, R16 |
| 5e | Cross-cutting checks (themes, narrow width, keyboard, console), screenshots, documentation, verification run | R17 to R21, all |

## 9. Decisions

| # | Question | Decision |
|---|---|---|
| 1 | The live "what would this threshold have caught" preview on Configuration? | Cut. It needs a replay engine over history and a second code path for the detectors. It is listed under "Not built" and can follow when there is data to judge it by |
| 2 | Where do the new views live? | Top-level tabs **Tools & tasks** and **Security** beside Processes and Configuration (across runs), and the same content as panels on each run |
| 3 | An agent with no known window | A hatched bar and a hint. A percentage is never invented. The catalog (decision 5) makes this the exception |
| 4 | Tasks in the tools table? | Yes, one table, a kind label and a filter; a tool and a task with one name are two rows |
| 5 | A context-window catalog on the server? | Yes (5a, 5d). Without it a fleet's gauges are blank whenever the process was not given a `.windows` file, which is the common case |
| 6 | One page for embedded and Fabric? | Yes, one code base. Pages that need the whole server (Tools & tasks, Security) work over the registry either way; Processes and Configuration appear only where they already do |
| 7 | How is the page tested? | Java tests for the routes, the view and the catalog; a browser test (node:test with Playwright, skipped when not installed, as the VS Code panel's is) against a real Fabric running the simulator scenarios, in both themes and at 360 px; computed contrast over the tokens; no charting or test library added to the product |
| 8 | Where do the security "cannot tell you" words come from? | One constant in the page's script, the same text as the wiki page, with a test that the wiki still says it |
| 9 | Fleet security rows: all agents or only the notable? | Only `exercised`, `accepted` and `armed` (and open incidents), so a fleet of quiet agents does not drown the page; the counts still include everyone |
| 10 | What does the page do about a hostile run? | Shows text as text and never executes or fetches anything named in run data; a link is made only to a run page on the same server |

## 10. Not built

- The live threshold preview (decision 1).
- Charts beyond the gauge, the compaction timeline and the latency histogram; no per-minute fleet history (the server keeps run state, not a time series).
- Acting on a finding from the page (pause or stop already exist and are unchanged; blocking is a script policy).
- Per-user views, saved filters and alert routing.
