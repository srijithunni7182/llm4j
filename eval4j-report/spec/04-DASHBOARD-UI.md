# 04. The dashboard: two editions, one model

Status: **Draft for review** · Module: `eval4j-report` (`render/`) · Visual reference: [`mockups/`](mockups/README.md) · Behaviour reference: [`eval4j/SPEC-dashboard-v2.md`](../../eval4j/SPEC-dashboard-v2.md)

This document specifies how the analysed model ([03](03-REPORT-CORE.md)) becomes pages. It does not repeat the product spec's behaviour; it fixes the structure, data contract, components, charts, accessibility and the static edition so the mockups can be implemented faithfully.

## 1. Two editions

| | Interactive edition | Static edition |
|---|---|---|
| Output | `index.html`, **one self-contained file** | `static/index.html` + `static/eval4j-static.css` |
| Script | one small hand-written script, inline | **none** |
| Styling | inline `<style>` | linked stylesheet; **no `style=""` attributes**; SVG uses presentation attributes only |
| Why | the full experience | works under strict policies such as Jenkins' default CSP (`style-src 'self'`, no inline script) |
| Navigation | left panel, in-page views, URL hash | left panel of anchor links to sections of one page |
| Drill-down | donut → dimension → metric → tests → case drawer | one section per family with a dimension table and expandable (`<details>`) failing lists |
| Priorities | adjustable per viewer | configured defaults only |
| Comparison | any retained run, pickers, word diff | previous run (policy 03 §6), side-by-side text, no diff |

| Req | Statement |
|---|---|
| UI-01 | Both editions render from the **same `ReportModel`** and carry the same content: every family, the coverage, cost and evidence, judges and models, comparison, and the "no verdict" wording. The interactive edition adds exploration; it adds no information. |
| UI-02 | **No network request** by either edition: no external font, script, stylesheet, image, analytics or beacon. Verified by test (07). |
| UI-03 | The interactive edition MUST remain **complete without JavaScript** at the level of its overview: the overview, family tiles and dimension donuts, the gap list, coverage and cost pages are server-rendered HTML. Script adds drill-down, filters, priorities, pickers, the drawer, trace and graph views. Without script, a notice links to the static edition. |
| UI-04 | The static edition MUST contain no `<script>`, no `style="…"` attribute, no `<link>` other than its own stylesheet, and no `javascript:` URL. Verified by test. |

## 2. Views and the data each needs

The names are routes (the URL hash token in the interactive edition, the section id in the static edition). The mockups show each one.

| Route | View | Model it renders | Interactive only |
|---|---|---|---|
| `overview` | Run summary, family tiles, radar by family, dimension donuts, gap list | `Overview` | priorities, "show all dimensions" |
| `<family>` e.g. `agents` | Family page: header donut and tiles, facet cards, dimension donuts scoped to the family | `FamilyView` | |
| `<family>-<facet>` e.g. `agents-reasoning` | Facet page + bespoke panel (below) | `FacetView` | trace drawer |
| `<dimension>` | Dimension page: metric cards, histogram, tests table | `DimensionView` | metric filter, search, filters |
| `<family>-<facet>-<dimension>` | The same, scoped | `DimensionView` | |
| `compare` | Compare runs | `CompareModel` | pickers, word diff, filters |
| `coverage` | Golden dataset coverage | `CoverageModel` | |
| `cost` | Cost and evidence | `CostModel` | |
| `models` | Judges and models | `ModelsModel` | |
| (drawer) | Case detail over any list | `CaseView` | |

**Bespoke panels** (rendered inside a facet or family page):

| Where | Panel | Model |
|---|---|---|
| `prompts` | A/B comparison cards; optimizer run (best score by round vs goal, rounds, diff, overfitting check, budget) | `PromptsPanel` from `PAIRWISE` evaluations and `optimizations.jsonl` |
| `agents-reasoning` | Reasoning summary tiles; step-outcome counts; steps-per-trace histogram vs `stepBudget`; traces table | `ReasoningPanel` from `AGENT_STEPS` traces |
| `workflows` / `workflows-trajectory` | Run picker; path graph; timeline; checks; spend by agent; event log | `TrajectoryPanel` from `WORKFLOW` traces |

| Req | Statement |
|---|---|
| UI-05 | Deep links are plain tokens (`[a-z0-9-]`) only; all other state (filters, selected run, priorities) lives in the page, never in the URL. |
| UI-06 | An unknown or empty route falls back to `overview`. A route whose data is absent (no traces, no optimizer run) shows a designed empty state that says what to do, not a blank panel. |
| UI-07 | A **family appears in the navigation only if the run has evaluations in it**; a **dimension appears whenever the dataset declares it** (03 §5.6), including with no results. |

## 3. Layout and navigation

- **Shell.** Left navigation (272 px, sticky) and a content column. A sticky top bar carries the breadcrumb, the run-profile chip, a "vs #N" chip that opens the comparison, and the theme toggle. At ≤ 980 px the navigation becomes an off-canvas menu opened by a button, with a scrim; Esc and scrim click close it.
- **Navigation content.** Brand lockup and project/run line; **Overview**; **Test families** (Prompts, Agents → Reasoning, Tool use, Answers & speed; Conversations; Retrieval (RAG); Workflows (Loom) → Trajectory, Orchestration, Outputs & structure, Guardrails); **Insights** (Compare runs, Dataset coverage, Cost and evidence, Judges and models). Each family and facet shows its pass rate and a status icon. Facets show only when they have evaluations.
- **Breadcrumb.** Run → family → facet → dimension, each a link.
- **Case drawer.** Right-hand panel (640 px, full width on phones), focus moves into it and returns on close; Esc closes; content adapts to the case's family (customer / agent reply / expected / retrieved context / reasoning trace for agents; scenario and both prompt outputs for prompts; turns for conversations; run summary plus a link to the trajectory for workflows).

| Req | Statement |
|---|---|
| UI-10 | Every interactive element is reachable and operable by keyboard; focus is visible; the drawer and menu trap and restore focus. `/` focuses the search where one exists. |
| UI-11 | Status and result are never conveyed by colour alone: icons (✓ ✕ ▲ ●) and text accompany every colour. |
| UI-12 | Both themes (system and toggle) are fully designed; colours come only from tokens (§7). |
| UI-13 | No horizontal page scroll at 360 px; wide tables scroll inside their own container. |
| UI-14 | **Fonts.** The default is the system font stack; the report requests no font from the network. An optional embedded font may be configured (`branding.font`) as a data URI in the file (adds ~100 KB). The mockups use IBM Plex only as a design reference. |

## 4. Data embedding (interactive edition)

The page carries its data as inert JSON and a small renderer.

```html
<script type="application/json" id="eval4j-model">{ …overview, families, dimensions, compare… }</script>
<script type="application/json" id="eval4j-case-c_36002d9a396666c3">{ …case detail… }</script>   <!-- one per embedded case -->
<script>/* the dashboard script */</script>
```

| Req | Statement |
|---|---|
| UI-20 | Embedded JSON MUST be escaped so it cannot close its element or start markup: `<` → `<`, `>` → `>`, `&` → `&`, U+2028/U+2029 → ` `/` `. Verified with hostile fixtures (07). |
| UI-21 | The page MUST parse case blocks **lazily** (on open), so a large report stays responsive. The summary model is parsed once. |
| UI-22 | The embedded model includes everything the script needs to recompute priorities (per-dimension `rate`, `goal`, default weight) and nothing the static edition lacks (UI-01). |
| UI-23 | The embedded configuration (RPT-22) is part of the model and shown under Data notes. |
| UI-24 | Detail limits follow 03 §8; a trimmed report says so on the pages affected. |
| UI-25 | All text the script inserts is inserted as text or escaped HTML (`textContent`, or an `esc()` over every interpolated value). No `innerHTML` with unescaped data. A lint test greps the script for unescaped interpolation patterns. |

## 5. Script architecture (no build tooling)

One hand-written file, `dashboard.js`, in plain ES2017 (no modules, no transpiling, no framework), kept under **90 KB** unminified. It is organised in sections, each a small function group, in this order: utilities (`esc`, formatters, `el`), state and routing, model access, charts (SVG strings), views (one render function per route), drawer and traces, comparison, event wiring. There is one global state object and one `render()` per route. Charts are produced as SVG strings by pure functions that take model slices, which makes them unit-testable in Node (07) with no DOM.

| Req | Statement |
|---|---|
| UI-30 | The script has no dependency and no build step; the repo's Maven build copies it as a resource. |
| UI-31 | Chart functions are pure (input → string) and are also covered by Node-based tests that compare output with golden SVG. |
| UI-32 | The script's priority formula (03 §5.5) is tested against the shared vectors. |
| UI-33 | Rendering a view for 3,000 embedded cases takes under 200 ms (list virtualisation is not required; lists are capped to 20 rows with "Show more"). |
| UI-34 | The script degrades safely: if the model fails to parse, a visible error panel names the problem and points to the static edition. |

## 6. Components

| Component | Content and rules |
|---|---|
| **Run summary (hero)** | Weighted pass rate, goal tick, neutral sentence ("Furthest from goal: …"), change vs the baseline run, the "informs, does not decide" line; below it the gap list (6 shown, "Show all N"), each row: name, bar with goal tick, `rate / goal`, signed gap, priority control (cycles Critical → Important → Nice to have → Not a priority). A declared-but-unevaluated dimension row says "Not evaluated this run". |
| **Family tile** | Icon, name, evaluations and test cases, status pill, pass rate, meter with goal tick, facet chips with their rates. Opens the family page. |
| **Dimension card** | Name, counts, status pill, donut (pass/fail, centre pass rate, goal tick), goal/gap/average/failing tests, sparkline of the last runs on the branch (goal dashed), **evidence bar** (fresh / reused / carried-over with legend). Empty variant: dashed ring, "No results", declared-by count, "Not counted in the weighted pass rate". Partial variant: "N of M scenarios". |
| **Donut** | Passed arc from 12 o'clock, failed arc after it, 3 px gaps; the black goal tick crosses the ring at `goal%`; centre shows the pass rate. Clicking the failed arc opens the dimension filtered to failing tests. |
| **Radar (by family)** | One axis per family present; actual (filled) vs goal (dashed); axis starts at 50 % and says so; low-axis markers carry ▼. |
| **Metric card** | Name, kind badge (LLM-judged / LLM A/B / Assertion / Measured), stacked pass/fail bar, average, pass rule text. Selecting narrows the histogram and the tests list. |
| **Histogram** | 10 bins, stacked passed/failed, the pass-line for a single judged metric; beside it Should be / Reached / Gap and "N more evaluations must move into the pass zone". |
| **Tests table** | One row per case, one cell per metric (score or display text, ✓/✕, clock icon when carried over), result pill; failing first; All/Failing/Passing; search. Row opens the drawer. |
| **Evidence panel** | Run profile and cadence (last full judged run, next scheduled if configured), the evidence mix bar, judge spend vs budget, saved by reuse, estimated full-run cost. |
| **Models panel** | Judge identity and settings, run usage, reliability (self-consistency, agreement with people if present, failures), independence check; agent descriptor. |
| **Coverage table** | Dimension, scenarios that declare it, evaluated, metrics wired, status pill; rows open the dimension. |
| **Compare view** | See §8. |

## 7. Visual system

### 7.1 Tokens

Defined as CSS custom properties on `:root`, redefined for dark under `@media (prefers-color-scheme: dark)` guarded by `:root:not([data-theme="light"])` and again under `:root[data-theme="dark"]`. Components reference tokens only.

| Token | Light | Dark | Use |
|---|---|---|---|
| `--bg` / `--surface` / `--surface-2` | `#f3f5f9` / `#ffffff` / `#f0f2f7` | `#090c13` / `#121722` / `#192030` | page, card, inset |
| `--ink` / `--ink-2` / `--ink-3` | `#0e1320` / `#454d60` / `#697187` | `#f1f3f8` / `#b3bac9` / `#8a92a5` | text |
| `--line` / `--grid` | `#e0e4ec` / `#e6e9f0` | `#242b3a` / `#212838` | borders, gridlines |
| `--pass` / `--fail` | `#2a78d6` / `#d03b3b` | `#3987e5` / `#e66767` | data: passed, failed |
| `--good` `--warn` `--crit` | `#0ca30c` `#fab219` `#d03b3b` | same, `--crit` `#e66767` | status icons and tints |
| `--b1` `--b2` `--b3` | `#0891b2` `#7c3aed` `#db2777` | `#22d3ee` `#a78bfa` `#f472b6` | brand gradient |

**Data colours are deliberately independent of the brand** (blue/red, a validated colour-blind-safe pair in both themes); the brand gradient is used only for the top-bar hairline, hero glow, eyebrows, the nav active marker and the footer lockup.

### 7.2 Type and spacing

System stack (`system-ui, -apple-system, "Segoe UI", sans-serif`; monospace for ids and model names). Scale 11 / 12 / 13 / 14 / 15 / 16 / 18 / 26 / 38 / 64 px. Cards radius 14–16 px, 1 px border, subtle shadow in light only. `tabular-nums` for all numbers in columns.

### 7.3 Charts: construction rules

All charts are inline SVG built from the model, with explicit fills and a `role="img"` and an `aria-label` that states the value. Marks are thin; gridlines recessive; tooltips are SVG `<title>` plus, in the interactive edition, a single positioned tooltip element.

| Chart | Rule |
|---|---|
| Donut | `r = (size − stroke)/2 − 8`; pass arc `dasharray = (pr·C − gap, C)`; failed arc starts at `pr·C`; goal tick from `r − stroke/2 − 6` to `r + stroke/2 + 6` at angle `goal·3.6° − 90°`, drawn with a surface-coloured halo. |
| Dumbbell | Hollow dot baseline, filled dot candidate, connecting line with arrow head (blue up, red down), goal tick; axis 0–100. |
| Scatter | Baseline score (x) vs candidate score (y), diagonal, triangle = now fails, square = now passes, small dot = same; changed marks drawn last. |
| Histogram | 10 bins; stacked passed/failed; pass-line for one metric. |
| Radar | n axes, rings at 50…100 by 10, goal polygon dashed, actual polygon filled. |
| Path graph | Nodes and edges from `workflow.graph`; expected path dashed grey, actual solid blue, wrong/failed nodes red with ✕; badges for guard, rewind ×N, approval, loop ×N / bound. Layout: a deterministic left-to-right layered layout computed in the renderer from the graph (rank = longest path from start; branch edges routed orthogonally). |
| Gantt | Lane per agent plus Harness and Human; bars from `delegate_start`/`delegate_end` pairs; diamond markers for `guard`, `checkpoint`, `rewind`, `budget`, `suspended`, `decision`. |
| Sparkline | Dimension rate over the last runs on the branch, goal dashed, endpoint emphasised. |

| Req | Statement |
|---|---|
| UI-40 | The same chart functions serve both editions; the static edition passes `static = true`, which drops interactivity attributes (`data-tip`) and uses presentation attributes only. |
| UI-41 | The categorical palette is validated for colour-blind separation in both themes with the project's palette validator (07). |
| UI-42 | The graph layout is deterministic: the same graph gives the same coordinates. |

## 8. The Compare view

Content and order (product spec §4.9; data from `CompareModel`, 03 §7):

1. **Pickers** (interactive): baseline (default per 03 §6) and the candidate; the page names the rule that chose the default baseline.
2. **Headline** and counts: changed, now failing, now passing, net, within noise, unchanged, new, removed, not comparable. No verdict.
3. **What changed between these runs**: the environment table with `CHANGED` flags.
4. **Movement by family / by dimension**: dumbbell rows sorted by `|delta|`, with baseline, candidate, delta and the better/worse counts, on matched evaluations; a note states the matched count.
5. **Scatter** of judged scores.
6. **Cases that changed**: filter chips (all, now failing, now passing, within noise); rows expand to both reasons, both answers (interactive: word diff; static: side by side), and "Open the full case".
7. A data note when datasets, profiles or config hashes differ.

## 9. Escaping and safety

Every value from a bundle or configuration is untrusted.

| Context | Rule |
|---|---|
| HTML text | escape `& < > " '` |
| HTML attribute | the same, always double-quoted |
| Embedded JSON | UI-20 |
| SVG text and `<title>` | HTML-text escaping |
| URLs | none from data are used as `href`/`src` (CI build links are shown as text, copyable) |
| CSS | no data reaches CSS |
| Markdown output | escape `\ | < > \`` and newlines in table cells |
| CSV output | RFC 4180 quoting; a cell starting with `= + - @ TAB CR` is prefixed with `'` |
| XML output | escape the five entities and strip characters illegal in XML 1.0 |

| Req | Statement |
|---|---|
| UI-50 | A hostile-string fixture (script tags, quotes, `</script>`, `<!--`, ` `, RTL overrides, 1 MB text, control characters) rendered in every field MUST produce no executable markup and a still-parseable document in both editions (07). |
| UI-51 | The interactive edition declares no inline event-handler attributes; events are attached by the script. |

## 10. Number formatting

One rule everywhere (and in the Markdown): percentages as integers in cards and tables (`87%`), one decimal for deltas (`+5.6 pts`), scores to two decimals (`0.86`), money to cents (`$0.59`), durations as `1.2 s` / `2m 41s`, tokens with thousands separators. Rounding is half-up on the model's full-precision values; a tie is never rounded toward a goal. A value whose rounded form equals the goal while its gap is negative is shown with one decimal ("89.6%") so a near miss is never displayed as "90% of 90%".

## 11. The static edition in detail

- **Files.** `static/index.html` links `eval4j-static.css`. Nothing else.
- **Structure.** Brand lockup and left anchor navigation; then one `<section id="…">` per route in this order: `overview`, one per family (with its facets as sub-headings: e.g. Agents → *Reasoning: what happened at each step*; Workflows → *Trajectory: runs with failed checks*, each a `<details>` holding the path graph and its failed checks), `compare`, `coverage`, `cost`, `models`.
- **Per family.** Header donut and tiles; facet table; the bespoke panel in table form (A/B table with a stacked bar; step-outcome table; failing-run graphs); a dimension table (rate, bar, goal, gap, status); a `<details>` of failing evaluations (capped at 40 with a count, `static.maxFailing` configurable).
- **Overview.** KPI row, family table with bars, dimension cards with donuts and evidence line.
- **Compare.** Environment table, family and dimension movement tables with dumbbells, a `<details>` of changed cases (largest 30).
- **Print.** A print stylesheet hides the navigation and expands `<details>` where practical.

The mockup `static-edition.html` is the visual reference.

## 12. Requirements for tests

| Req | Statement |
|---|---|
| UI-60 | Snapshot tests for the static edition and for each server-rendered part of the interactive edition (golden HTML) built from the example bundles. |
| UI-61 | Headless-browser tests (07) load the interactive edition from the example bundles and assert: navigation to every route, priority changes recompute the summary, comparison filters, drawer open/close and focus, keyboard operation, no console errors, no network requests. |
| UI-62 | A test confirms UI-02, UI-04, UI-20 and UI-50 on every generated page. |
| UI-63 | Accessibility checks (an automated rule engine plus a keyboard script) run on the overview, a family page, a dimension page, the compare page and the drawer, in both themes. |
