# eval4j — Spec: Quality Dashboard v2

Status: **Draft for review** · Scope: `eval4j` `report` package · Supersedes the earlier "Run Explorer" draft
Mockup: https://claude.ai/artifact/BTpq8amVUQWzqC9UEqqbXv (private; static, sample data, no product code)

## 1. Why v1 is not good enough

v1 lists every evaluation and adds a heatmap. It answers "what happened in each test" but not the
question a team actually has: **"is this agent good enough to ship, and where is it weak?"**

| Problem in v1 | v2 answer |
|---|---|
| Tests are the first thing you see, as a flat list | The first screen has **no tests**. It shows quality **dimensions** |
| Metrics have free-form names (`Faithfulness`, `Tone`, ...) with no grouping | Every metric belongs to a **dimension** (Correctness, Relevancy, Grounding, Retrieval, Efficiency, Safety & Tone) |
| Scores are shown without any notion of "good enough" | Every dimension has a **target**; every view shows **reached vs should be** |
| The judge is a string in a tooltip | A first-class **Models** panel: judge identity, settings, cost and **reliability** |
| One level of detail, everything at once | A drill-down: **Dimension → Metric → Test → Case** |

## 2. Principles

1. **Summary first, detail on demand.** The overview never lists tests. Tests appear one click down.
2. **Always show the gap.** Wherever a number appears, the number it should have reached is next to it.
3. **Trust is part of the result.** A score is shown with who judged it and how reliable that judge is.
4. **Static, local, free.** Self-contained files, no server, no network, no telemetry.
5. **Readable without JavaScript.** Every view is real HTML; JS only adds navigation transitions, filters, the drawer and tooltips.
6. **Additive.** No breaking change to existing report JSON, `EvalRecord`, history files or the public API.

## 3. Information architecture

```
Overview ──► Dimension ──► (Metric filter) ──► Test list ──► Case drawer
 (no tests)   donut, tiles    narrows chart      rows = tests    input/output/expected/
 verdict,     metric cards    and test list      cells = metrics  context/each evaluation
 radar,       histogram                                          with judge + reasoning
 6 donuts,
 models
```

Deep links use plain tokens (`#grounding`, `#overview`); all view state beyond that stays in the page.
Without JS, each dimension is a `<section>` reachable by `:target`.

## 4. Screens (see mockup for the visual)

### 4.1 Overview
- **Release readiness hero:** overall pass rate (large), verdict sentence ("Not ready: 4 of 6 dimensions below target"), change vs previous run, a meter with the **target tick**, and a **"gap to target, largest first"** list.
- **Radar:** pass rate per dimension (filled) against target (dashed). Axis starts at 50% and says so.
- **Dimension donuts (the main body):** one card per dimension.
  - Ring = passed (blue) / failed (red) share. Centre = pass rate.
  - **Black tick on the ring = target.** The blue arc should reach the tick.
  - Status pill with icon and text: *On target* (gap ≥ 0), *Below target* (0 to −10 pts), *At risk* (worse than −10 pts).
  - Target, gap, average score, failing tests, and a 10-run sparkline with the target dashed.
  - Clicking the card opens the dimension. Clicking the **red arc** opens it pre-filtered to failing tests.
- **Models in this run:** see 4.4.

### 4.2 Dimension view
- Header: large donut, description, tiles (pass rate, target, gap, average score, status).
- **Metric cards:** passed/failed bar, average score, pass threshold, "LLM-judged" or "Measured" badge. Selecting one narrows everything below.
- **Where scores landed:** histogram of scores 0 to 1, stacked passed/failed, the pass line marked for a single metric. Beside it: **Should be / Reached / Gap** and "N more evaluations must move into the pass zone".
- **Tests:** one row per test case, one cell per metric (score with ✓ / ✕, never colour alone), result pill. Failing first. Filters: All / Failing / Passing, plus search. Row opens the case drawer.

### 4.3 Case drawer
Customer input, agent reply, expected output, retrieved chunks, a chip per dimension (pass / n failed), then **every evaluation of this test**, failures first: score bar with threshold tick, the judge's reasoning, and "Judged by *model* · 3 samples" or "Measured by the eval4j tracer".

### 4.4 Models panel
**Judge:** model name, provider, temperature, samples per evaluation and aggregation, rubric id/version, judge calls, cache-hit rate, latency (mean and p95), cost.
**Reliability ("how far to trust these scores"):**
- *Self-consistency:* share of multi-sample evaluations whose samples agree within 0.1.
- *Agreement with people:* Cohen's κ against a labelled calibration set, when one is supplied (eval4j already ships calibration studies).
- *Judge failures:* calls that errored, were retried, or stayed unresolved.
- *Independence check:* a warning if judge and agent are the same model family (self-preference bias).

**Agent under test:** model, provider, prompt id/version, tools, test-case and evaluation counts, judged vs measured split.

## 5. Dimensions, metrics and targets

### 5.1 Taxonomy
Each metric has exactly one dimension. Built-ins get a default; users can override.

| Dimension | Built-in metrics mapped by default |
|---|---|
| Correctness | Answer correctness, task completion, tool-call accuracy, custom `LlmJudgeCondition` rubrics tagged correctness |
| Relevancy | Answer relevancy, topic adherence |
| Grounding | Faithfulness, hallucination, citation accuracy |
| Retrieval | Contextual precision, recall, relevancy (RAG judges) |
| Efficiency | Latency, token budget, steps/tool calls (measured, not judged) |
| Safety & Tone | Toxicity, PII leakage, brand tone, conversation retention/completeness where tagged |
| Other | Any metric with no mapping, so nothing is hidden |

### 5.2 Targets
- A target is a **pass-rate goal per dimension** (default 90%). Overall target is the mean of dimension targets, or an explicit value.
- Configured per project (`eval4j.targets.properties`) and overridable in code; the report records the targets it used so old reports stay correct.
- Per-metric pass **thresholds** are unchanged; they decide whether one evaluation passes. The target decides whether a dimension is good enough.

### 5.3 Measured metrics
Efficiency metrics are measurements, not judge scores. They carry a raw value and a budget; the report shows the raw value (3.2 s, 2,140 tokens, 5 steps) and a normalised 0 to 1 score for the histogram where 0.5 means exactly on budget.

## 6. Data model (additive)

| Type | New fields |
|---|---|
| `EvalRecord` | `dimension`, `kind` (`JUDGED`/`MEASURED`), `measuredValue`, `budget`, `unit`, `judge` (see below) |
| Judge descriptor | `provider`, `model`, `temperature`, `samples`, `aggregation`, `rubricId`, `rubricVersion` |
| Judge run stats | calls, cache hits, latency mean/p95, tokens in/out, cost, failures, retries, sample-agreement |
| `RunInfo` | `targets`, `agent` descriptor (model, provider, prompt id, tools), `judge` stats, calibration summary |
| `HistoryEntry` | per-dimension pass rate and counts (drives sparklines and "vs previous run") |

Old reports load unchanged. Missing fields hide the related UI rather than showing empty boxes.
Judge and agent descriptors are supplied through the existing builders (`judgeIdentifier` today becomes a descriptor).

## 7. Visual and interaction rules

- **Donut caveat, handled:** a two-slice donut is weak for comparing values, so every donut carries the exact percentage in its centre, the target tick on the ring, and the gap in text. Exact numbers never rely on reading an arc.
- **Colour:** blue = passed, red = failed (a colour-blind-safe pair, validated for light and dark), status pills use the fixed status palette **with an icon and a label**. No red/green pairing.
- **Never colour alone:** ✓ / ✕ icons in cells, "▼" on radar axes below target, pills carry text.
- **Both themes** follow the OS and have a toggle; tokens only, no literal colours in components.
- **Tooltips** on arcs, histogram bars and radar points; **keyboard:** cards and rows focusable, Enter opens, Esc closes the drawer, `/` focuses search.
- **Responsive:** one column at phone width; tables scroll inside their own container; the drawer becomes full screen.
- **Type:** IBM Plex Sans with IBM Plex Mono for model names and identifiers, with system fallbacks. Fonts are **not** fetched by the report itself: they are embedded or fall back to the system stack, because the report must make no network requests.

## 7a. Branding

The report is an llm4j product and looks like one.

| Element | Rule |
|---|---|
| **eval4j icon** | The existing scales-of-justice mark from `eval4j_logo.svg` (hexagonal pans, circuit nodes), inlined as an SVG symbol so it costs no request. Used in the top bar (about 44 px), the hero card, the footer and as the page favicon (data URI). Stroke gradient follows the logo: cyan, violet, pink |
| **Wordmark** | `eval4j` with the `4j` in the brand gradient, followed by a quiet "by **llm4j**" credit |
| **Gradient** | llm4j neon (`#22d3ee` to `#a78bfa` to `#f472b6`) in dark mode; deeper equivalents (`#0891b2`, `#7c3aed`, `#db2777`) in light mode so gradient text keeps contrast |
| **Where the brand appears** | A 2 px gradient hairline under the top bar; the hero card's soft violet/cyan glow (the logo's background glow); gradient eyebrow text; the drawer header rule; the footer lockup |
| **Where it does not** | **Chart colours.** Passed/failed stay on the validated blue/red pair, so the neon palette never affects data readability or colour-blind safety |
| **Footer lockup** | Icon, "eval4j", the llm4j tagline ("AI agents, written the Java way"), a one-line privacy note (self-contained, no account, no telemetry) and the ecosystem chips: ai-agent4j, **eval4j**, loom, engram, tantrik |
| **Project identity** | The breadcrumb shows a project name (`eval4j.report.project`, default: the Maven artifact id). An optional project logo may sit beside it; the llm4j mark and credit always remain |
| **Both themes** | Brand tokens are defined for light and dark; the dark theme matches the logo's near-black `#05060f` ground |

## 8. Output files

Unchanged from v1 (`eval4j-report.html/json/csv`, `eval4j-junit.xml`, `eval4j-summary.md`, history). `eval4j-summary.md` gains a per-dimension table with target and gap. `EvalReportCli` can re-render everything from JSON.

## 9. Acceptance criteria

| ID | Criterion |
|---|---|
| Q1 | The overview contains no per-test rows |
| Q2 | Each dimension card shows pass rate, target tick, gap, status (icon + text) and a 10-run trend |
| Q3 | Clicking a dimension shows its metrics, histogram and tests; clicking the red arc pre-filters to failing tests |
| Q4 | Selecting a metric narrows histogram and test list consistently |
| Q5 | The case drawer shows input, output, expected, context and every evaluation with reasoning and judge |
| Q6 | The Models panel shows judge identity, settings, usage and reliability; warns when judge and agent share a model family |
| Q7 | A metric with no mapping appears under "Other", never dropped |
| Q8 | Old report JSON and history load and render with the new UI minus the missing parts |
| Q9 | Complete and readable with JavaScript disabled |
| Q10 | No network requests, all dynamic text escaped, passes the colour-validator in both themes |
| Q12 | The eval4j icon renders complete in the top bar, hero, footer and favicon in both themes, with no external request |
| Q13 | Gradient text meets 4.5:1 contrast against its surface in light and dark; chart colours are unaffected by the brand palette |
| Q11 | 10,000 evaluations render in under 5 s and stay under 10 MB |

## 10. Phasing

| Phase | Contents |
|---|---|
| 1 | Data model: dimension + kind on records, targets, judge/agent descriptors, history per dimension |
| 2 | Overview: hero, radar, dimension donuts, Models panel |
| 3 | Dimension view, metric filter, histogram, test list |
| 4 | Case drawer, keyboard and accessibility pass |
| 5 | Judge reliability (self-consistency, calibration), independence check, CLI and summary updates |

Run comparison, stability/flakiness and cost tracking from the earlier draft are deferred until this lands.

## 11. Open questions

1. **Taxonomy:** are these six dimensions right, or do you want different names (for example "Accuracy" instead of "Correctness")?
2. **Targets:** one pass-rate target per dimension, or also a minimum average score?
3. **Overall verdict:** "ready" only when every dimension is on target, or weighted (for example Safety must be 100%)?
4. **Efficiency:** are latency, tokens and steps the right measured metrics, and who supplies budgets?
5. **Judge reliability:** is the κ calibration panel worth showing only when a calibration set exists?
6. **Fonts:** embed Plex in the file (about 100 KB) or use the system font stack?
7. **Anything else on the overview** you want visible at a glance?
