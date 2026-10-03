# eval4j — Spec: Quality Dashboard v2

Status: **Draft for review (rev 3)** · Scope: `eval4j` `report` package · Supersedes the earlier "Run Explorer" draft
Mockup: https://claude.ai/artifact/BTpq8amVUQWzqC9UEqqbXv (private; static, sample data, no product code)

## 1. Why v1 is not good enough

v1 lists every evaluation and adds a heatmap. It answers "what happened in each test" but not the
question a team actually has: **"where is this agent strong, where is it weak, and how much do we trust that?"**
Shipping is a business decision. The report supplies the evidence; **it does not issue a verdict.**

| Problem in v1 | v2 answer |
|---|---|
| Tests are the first thing you see, as a flat list | The first screen has **no tests**. It shows quality **dimensions** |
| Metrics have free-form names (`Faithfulness`, `Tone`, ...) with no grouping | Every metric belongs to a **dimension** (Correctness, Relevancy, Grounding, Retrieval, Efficiency, Safety & Tone) |
| Scores are shown without any notion of "good enough" | Every dimension has a **goal**; every view shows **reached vs goal**. Goals inform; they do not gate |
| A pass/fail "release" call is a business judgement, and dimensions matter unequally | No verdict. Stakeholders set **priorities** per dimension and the summary re-weights |
| LLM judging is too expensive to run on every build | **Cost-aware runs**: change-aware judging, reuse, carry-over, budgets. The report shows what was fresh, reused or carried over, and what it cost |
| The judge is a string in a tooltip | A first-class **Models** panel: judge identity, settings, cost and **reliability** |
| One level of detail, everything at once | A drill-down: **Dimension → Metric → Test → Case** |

## 2. Principles

1. **Summary first, detail on demand.** The overview never lists tests. Tests appear one click down.
2. **Always show the gap, never the verdict.** Wherever a number appears, its goal is next to it. The report never says "ready" or "not ready"; that decision belongs to people.
3. **Trust is part of the result.** A score is shown with who judged it, how reliable that judge is, and how fresh the evidence is.
4. **Respect the budget.** Judging costs money. The report is designed for cheap per-build runs plus occasional full runs, and is honest about which numbers come from which.
5. **Static, local, free.** Self-contained files, no server, no network, no telemetry.
6. **Readable without JavaScript.** Every view is real HTML; JS only adds navigation transitions, filters, the drawer and tooltips.
7. **Additive.** No breaking change to existing report JSON, `EvalRecord`, history files or the public API.

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
- **Run summary hero (no verdict):** a weighted pass rate with its goal tick, a plain-language line ("Furthest from goal: Retrieval (−16), Grounding (−9). Meeting goal: Correctness"), change vs the previous run, and the sentence "This report informs the release decision. It does not make it."
- **Priorities (the stakeholder lens):** each dimension row in the gap list carries a priority control: *Critical* (×3), *Important* (×2), *Nice to have* (×1), *Not a priority* (×0). Changing it re-weights the summary and fades the dimension on the radar and cards. Priorities never alter a result or a CI gate. Defaults come from project config; a viewer's choices are remembered locally and can be shared as a link/preset.
- **Radar:** pass rate per dimension (filled) against goal (dashed). Axis starts at 50% and says so.
- **Dimension donuts (the main body):** one card per dimension.
  - Ring = passed (blue) / failed (red) share. Centre = pass rate. **Black tick = goal.**
  - Status pill with icon and text: *Meets goal* (gap ≥ 0), *Below goal* (0 to −10 pts), *Well below goal* (worse than −10), or *Not a priority* (muted, when the viewer ignored it).
  - Goal, gap, average score, failing tests, a 10-run sparkline, and an **evidence bar** (fresh / reused / carried over, see 5a).
  - Clicking the card opens the dimension. Clicking the **red arc** opens it pre-filtered to failing tests.
- **How this run was produced:** run profile, evidence mix, judge spend vs budget, money saved by reuse (see 5a).
- **Models in this run:** see 4.4.

### 4.2 Dimension view
- Header: large donut, description, tiles (pass rate, target, gap, average score, status).
- **Metric cards:** passed/failed bar, average score, pass threshold, "LLM-judged" or "Measured" badge. Selecting one narrows everything below.
- **Where scores landed:** histogram of scores 0 to 1, stacked passed/failed, the pass line marked for a single metric. Beside it: **Should be / Reached / Gap** and "N more evaluations must move into the pass zone".
- **Tests:** one row per test case, one cell per metric (score with ✓ / ✕, never colour alone; a clock marks carried-over results), result pill. Failing first. Filters: All / Failing / Passing, plus search. Row opens the case drawer.

### 4.3 Case drawer
Customer input, agent reply, expected output, retrieved chunks, a chip per dimension (pass / n failed), then **every evaluation of this test**, failures first: score bar with threshold tick, the judge's reasoning, and one of: "Judged this run by *model* · 3 samples · cost", "Reused: output unchanged since run #N, verdict still holds, cost $0", "Carried over from run #N (2 days ago), not re-judged in this run" or "Measured by the eval4j tracer".

### 4.4 Models panel
**Judge:** model name, provider, temperature, samples and aggregation, rubric id/version, judge calls and cost **in this run**, verdicts reused, latency (mean and p95).
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

### 5.2 Goals
- A goal is a **pass-rate aim per dimension** (default 90%). It is context for reading the numbers, not a gate.
- Configured per project (`eval4j.goals.properties`) and overridable in code; the report records the goals it used so old reports stay correct.
- Per-metric pass **thresholds** are unchanged; they decide whether one evaluation passes.
- Build-breaking is a separate, optional feature (the existing `@EvalBaseline` regression gate). The dashboard never fails a build.

### 5.2a Priorities
- Four levels with weights: Critical 3, Important 2, Nice to have 1, Not a priority 0. Weighted pass rate = Σ(weight × dimension pass rate) / Σ(weight); the weighted goal is computed the same way.
- Project config can set default priorities (`eval4j.priorities.properties`) so a team shares one starting lens; viewers can override locally.
- Priorities are presentation only. They are not written into results, history or gates, so two stakeholders can read one report differently without changing the data.

### 5.3 Measured metrics
Efficiency metrics are measurements, not judge scores. They carry a raw value and a budget; the report shows the raw value (3.2 s, 2,140 tokens, 5 steps) and a normalised 0 to 1 score for the histogram where 0.5 means exactly on budget.

## 5a. Cost-aware runs

LLM-judged evaluation is too expensive to run on every build. The system and the report are designed around that.

### Run profiles
| Profile | Judges | Typical use |
|---|---|---|
| **Fast** | Nothing new: deterministic assertions, measured metrics, cached verdicts only | Every commit |
| **Build (change-aware)** | Only cases whose agent output, prompt, rubric or judge changed; everything else reuses the cached verdict | Every build / PR |
| **Sample** | A stratified percentage of cases (per dimension), chosen deterministically from a seed | Cost-capped checks |
| **Full** | Every case | Nightly, weekly or before a release |

### Evidence sources
Every result carries where it came from, and the report always shows it:
- **Fresh:** judged or measured in this run.
- **Reused:** evaluated this run, but the verdict came from the judge cache because input, output, rubric and judge are unchanged. Valid and free.
- **Carried over:** not evaluated this run; the latest known result from an earlier run (named, with its age). Marked with a clock; treated as true of that run, not of this build.

### Honesty rules
- A dimension with carried-over evidence says so on its card (evidence bar) and in its test list.
- A configurable staleness limit (default 7 days) flags carried-over evidence that is too old.
- For **sampled** runs, pass rates are shown with a margin of error (Wilson interval) and a "N of M cases judged" note, so a thin sample is never mistaken for a full result.
- If a budget stops judging early, the unjudged cases are shown as **Not evaluated**, never as passed or failed, and coverage is stated ("212 of 306 evaluated").

### Cost transparency
"How this run was produced" shows: run profile and cadence ("full judged run: nightly, last #144 two days ago, next tonight 02:00"), evidence mix, **judge spend vs budget**, **saved by reuse**, and what a full re-judge would cost. Judge cost per dimension and per case is visible in the drawer. Pricing is supplied by the user (`eval4j.pricing.properties`); without it, calls and tokens are shown and cost is omitted.

### Composite view
The report for a cheap run is assembled from the latest known result per case: fresh and reused results from this run plus carried-over results from the last run that judged them. The history store keeps per-case verdicts with their run id and timestamp to make this possible.

## 6. Data model (additive)

| Type | New fields |
|---|---|
| `EvalRecord` | `dimension`, `kind` (`JUDGED`/`MEASURED`), `measuredValue`, `budget`, `unit`, `judge` (see below), `source` (`FRESH`/`REUSED`/`CARRIED`/`NOT_EVALUATED`), `evaluatedInRun`, `evaluatedAt`, `costUsd`, `judgeCalls` |
| Judge descriptor | `provider`, `model`, `temperature`, `samples`, `aggregation`, `rubricId`, `rubricVersion` |
| Judge run stats | calls, cache hits, latency mean/p95, tokens in/out, cost, failures, retries, sample-agreement |
| `RunInfo` | `goals`, `profile` (`FAST`/`BUILD`/`SAMPLE`/`FULL`), `coverage` (evaluated / total), `judgeBudgetUsd`, `agent` descriptor (model, provider, prompt id, tools), `judge` stats, calibration summary, last full judged run id and time |
| `HistoryEntry` | per-dimension pass rate and counts (sparklines, "vs previous run"), run profile, spend, and per-case latest verdict with its run id (drives carry-over) |

Old reports load unchanged. Missing fields hide the related UI rather than showing empty boxes.
Judge and agent descriptors are supplied through the existing builders (`judgeIdentifier` today becomes a descriptor).

## 7. Visual and interaction rules

- **Donut caveat, handled:** a two-slice donut is weak for comparing values, so every donut carries the exact percentage in its centre, the target tick on the ring, and the gap in text. Exact numbers never rely on reading an arc.
- **Colour:** blue = passed, red = failed (a colour-blind-safe pair, validated for light and dark), status pills use the fixed status palette **with an icon and a label**; evidence uses neutral tones plus a hatch pattern (carried over), so it never competes with pass/fail. No red/green pairing.
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
| Q1 | The overview contains no per-test rows and **no pass/fail or ready/not-ready verdict** |
| Q2 | Each dimension card shows pass rate, goal tick, gap, status (icon + text), a 10-run trend and an evidence bar |
| Q3 | Clicking a dimension shows its metrics, histogram and tests; clicking the red arc pre-filters to failing tests |
| Q4 | Selecting a metric narrows histogram and test list consistently |
| Q5 | The case drawer shows input, output, expected, context and every evaluation with reasoning, judge and evidence source |
| Q6 | The Models panel shows judge identity, settings, this-run usage and reliability; warns when judge and agent share a model family |
| Q7 | A metric with no mapping appears under "Other", never dropped |
| Q8 | Old report JSON and history load and render with the new UI minus the missing parts |
| Q9 | Complete and readable with JavaScript disabled (priorities then show the configured defaults) |
| Q10 | No network requests, all dynamic text escaped, passes the colour-validator in both themes |
| Q11 | 10,000 evaluations render in under 5 s and stay under 10 MB |
| Q12 | The eval4j icon renders complete in the top bar, hero, footer and favicon in both themes, with no external request |
| Q13 | Gradient text meets 4.5:1 contrast in light and dark; chart colours are unaffected by the brand palette |
| Q14 | Changing a priority re-weights the summary and radar immediately and changes no result, history or gate |
| Q15 | Setting every dimension to "Not a priority" shows an explanatory empty state, not a number |
| Q16 | A Fast or Build run labels carried-over and reused evidence per dimension and per case, with source run and age |
| Q17 | Judge spend, budget, saved-by-reuse and full-run estimate are shown; a budget stop yields "Not evaluated", never a false pass |
| Q18 | Sampled runs show a margin of error on pass rates and the number of cases judged |
| Q19 | Re-running a Build run with no agent change makes zero judge calls |

## 10. Phasing

| Phase | Contents |
|---|---|
| 1 | Data model: dimension + kind, goals, evidence source and run provenance, judge/agent descriptors, per-dimension history |
| 2 | Cost-aware runs: change-aware judging via the existing cache, run profiles, budget cap, coverage and "Not evaluated" |
| 3 | Overview: run summary, priorities, radar, dimension donuts with evidence bars, "How this run was produced", Models panel |
| 4 | Dimension view, metric filter, histogram, test list, case drawer, accessibility pass |
| 5 | Composite (carry-over) view, sampling with margin of error, judge reliability, independence check, CLI and summary updates |

Run comparison, stability/flakiness and per-case cost analytics from the earlier draft come after this.

## 11. Open questions

1. **Taxonomy:** are the six dimensions right, or do you want different names (for example "Accuracy" instead of "Correctness")?
2. **Priority presets:** should a team be able to publish named presets ("Customer-facing", "Internal tool") that stakeholders pick from, or is per-dimension control enough?
3. **Carry-over limits:** default staleness limit of 7 days, and should a carried-over result ever count towards the weighted pass rate, or be shown but excluded until refreshed?
4. **Sampling:** is a seeded, per-dimension stratified sample the right default, and what default percentage for the Sample profile?
5. **Efficiency:** are latency, tokens and steps the right measured metrics, and who supplies budgets?
6. **Pricing:** user-supplied only (proposed), or ship a default table that will go stale?
7. **Fonts:** embed Plex in the file (about 100 KB) or use the system font stack?
8. **Anything else on the overview** you want visible at a glance?
