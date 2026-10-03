# eval4j — Spec: Quality Dashboard v2

Status: **Draft for review (rev 6)** · Scope: `eval4j` `report` package · Supersedes the earlier "Run Explorer" draft
Mockups (private, sample data, no product code): interactive https://claude.ai/artifact/BTpq8amVUQWzqC9UEqqbXv · static edition https://claude.ai/artifact/1s1Ufc3nAnn6qV1rLZQ2zM

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

Two independent axes classify every evaluation:

- **Test family (what is under test)** is the left-hand navigation.
- **Quality dimension (what quality is measured)** is the donuts, goals and priorities already specified.

A family page shows only that family's evaluations, sliced by dimension. The overview shows everything.

```
Left navigation                         Views
─────────────────                       ──────────────────────────────────────────
Overview                                run summary · family tiles · dimension donuts
TEST FAMILIES
  Prompts                               A/B comparisons · optimizer rounds · prompt diff
  Agents                                family page: facets + dimensions
    Reasoning                           step outcomes · steps vs budget · trace viewer
    Tool use                            tool-call accuracy, order, success
    Answers & speed                     answer quality, latency, tokens
  Conversations                         memory, completeness, role adherence
  Retrieval (RAG)                       precision, recall, relevancy
  Workflows (Loom)                      family page: facets + dimensions
    Trajectory                          path graph · timeline · checks · spend · event log
    Orchestration                       branches, loops, rewinds, approvals
    Outputs & structure                 typed outputs
    Guardrails                          PII and secret guards
INSIGHTS
  Dataset coverage · Cost and evidence · Judges and models
```

Drill-down inside any family: **dimension, then metric, then test list, then case drawer.** A case drawer adapts to its family: customer/agent/context for agents, scenario and both prompt outputs for prompts, turns for conversations, and the run summary with a link into the trajectory view for workflows.

Navigation shows each item's pass rate with an icon, so the left panel is itself a scorecard. On narrow screens it becomes an off-canvas menu. Deep links are plain tokens: `#agents-reasoning`, `#workflows-trajectory`, `#coverage`.
Without JS, every view is a `<section>` reachable by `:target` (see the static edition, 8a).

### 3a. Family and facet classification
Each metric carries a **family** and a **facet**; the dimension stays separate.

| Family | Facets | Example metrics |
|---|---|---|
| Prompts | A/B comparisons, optimization | pairwise "B does not lose" per comparison, optimizer best validation score |
| Agents | Reasoning, Tool use, Answers & speed | plan coherence, tool choice, error recovery, step efficiency, tool order, correctness, relevancy, faithfulness, latency, tokens |
| Conversations | Multi-turn | knowledge retention, conversation completeness, role adherence |
| Retrieval (RAG) | Retrieval | contextual precision, recall, relevancy |
| Workflows (Loom) | Trajectory, Orchestration, Outputs & structure, Guardrails | tool order across agents, required agents invoked, correct branch, loop within bound, rewinds within cap, approval requested, typed output complete, PII guard held, spend within budget |

Built-in metrics map to a family and facet by default; a metric can override both. An unmapped metric defaults to Agents / Answers. Families and facets are open: Loom's earned-autonomy replay (`ReplayReport`) is a natural later family.

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
- Header: large donut, description, tiles (pass rate, goal, gap, average score, status).
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

### 4.5 Prompts view
- **A/B comparisons:** one card per comparison (for example `support-agent v3 vs v2`): stacked bar of B wins / ties / A wins, counts, and how many verdicts flipped when the order was swapped (so they are treated as ties). Built on `PromptComparison` / `PairwiseJudge`, which already judge both orders.
- **Optimizer run:** best validation score by round against the goal line, accepted rounds filled and rejected rounds hollow, stop reason (for example "goal reached in round 8"), LLM calls and cost against the budget, and the **prompt diff** (`LineDiff`) of what the accepted rewrite changed, plus the train-versus-validation gap from the overfitting guard. Built on `OptimizationReport`.
- Prompt results also feed the **Prompt quality** dimension and the baseline gate like any other metric.

### 4.6 Agents: Reasoning view
- **Reasoning summary:** traces, average steps, repeat rate, tool-error rate, unknown-tool count.
- **What happened at each step:** the counts of `AgentResult.StepOutcome` across all traces: `EXECUTED`, `UNKNOWN_TOOL`, `DUPLICATE_BLOCKED` (loop detection), `EXECUTION_ERROR`, `REJECTED_BY_HUMAN`, `APPROVAL_UNAVAILABLE`, `BUDGET_EXHAUSTED`, each with a plain-language meaning.
- **Steps per trace** histogram against the step budget.
- **Traces table:** one row per agentic test with steps, plan coherence, tool choice, error recovery and flags. A row opens the drawer with the **step-by-step trace**: thought, action and input, observation, outcome chip; failed steps are highlighted and numbered, never colour alone.
- Metrics: plan coherence, tool choice and error recovery are judged from the trace (`includeTrajectory`); tool order and tool success are deterministic (`usesToolsInOrder`, `usesToolSuccessfully`); steps to resolution is measured.

### 4.7 Workflows (Loom): Trajectory view
Pick a workflow run (runs with failed checks are marked). Then:
- **Path graph:** the workflow's control flow (`delegate`, `alt`, `loop until`, `handoff`) with the **expected path dashed** and the **path taken solid**. A wrong or failed node is outlined red with an icon; badges show guard held/leaked, rewound ×N, approved / no approval, and loop count against its bound.
- **Timeline:** a swim lane per agent (and the harness), with bars for each agent working and markers for guard, checkpoint, rewind, budget and suspend events.
- **Checks for this run:** every trajectory assertion for the run with the reason, failures first.
- **Spend by agent:** model and cost per agent against the run budget (from `SpendReport`).
- **Event log:** the run's trace events, filterable by type: `delegate_start/end/replayed`, `thought`, `action`, `observation`, `tool`, `budget`, `approval`, `guard`, `checkpoint`, `rewind`, `decision`, `suspended`.
- The family page also shows **Orchestration**, **Outputs & structure** and **Guardrails** as dimension-and-test views, and the case drawer links a run into this view.

### 4.8 Getting Loom data into eval4j
eval4j depends on `ai-agent4j` only and must not depend on Loom. Loom already emits what the view needs: `TraceEvent` (type, agent, hierarchical step id, text, data, time) through `TraceListener`, per-step spend through `SpendReport`, and the run journal. The design:
1. eval4j defines a small neutral **trace model** (`WorkflowTrace`: events with type, agent, step id, time, data; plus spend lines). It is serializable into the report JSON.
2. A thin bridge in the Loom module (or an `eval4j-loom` artifact) subscribes with `HarnessExecutor.addTraceListener` and records a `WorkflowTrace` against the current test; no change to Loom's runtime.
3. Trajectory assertions (`usesToolsInOrder`-style, branch, loop bound, rewinds, approvals, guards) are plain assertions over the trace and are recorded as deterministic evaluations in the Workflows family, so they cost nothing to run on every build.
4. Single-agent traces come straight from `AgentResult.getSteps()`.

### 4.9 Compare runs
Run comparison is a first-class view in the left navigation (Insights). It answers "what changed between these two runs, and is the change real?" and, like everything else, passes no verdict.

- **Pickers:** baseline and candidate. Defaults: candidate is this run; baseline is the previous run on the same branch. Other choices: any retained run, the last **full judged** run, the pinned baseline (`@EvalBaseline`), or the last run on `main`.
- **Headline:** "Compared with #147, 71 of 624 evaluations changed result: 32 now fail, 39 now pass. 6 moved by less than the judge's own noise, so treat them as unproven. Changed between these runs: commit, agent prompt, golden dataset."
- **What changed between these runs:** a settings table of both runs with differences flagged: commit, agent prompt and version, agent model, judge model, judge rubric version, golden dataset revision, run profile. This is the first thing a reader needs to explain a change.
- **Movement by test family / quality dimension:** a dumbbell per row (hollow dot = baseline, filled dot = this run, tick = goal, arrow shows direction), baseline and candidate pass rates, the change in points, and the counts of cases that got worse and better. Sorted by size of movement.
- **Every judged evaluation:** a scatter of baseline score against this run's score. Marks below the diagonal scored lower, above scored higher; shapes differ for now-fails, now-passes and unchanged (never colour alone).
- **Cases that changed:** largest movement first, filterable (all, now failing, now passing, within noise). Each row opens to show **both judge verdicts and, for agent cases, both answers with a word-level diff** (struck-through words in the baseline, underlined words in this run), or "the answer is unchanged; the difference is in the judge's score or the retrieved context".
- **Noise awareness:** a change in a judged score smaller than the judge's own variability is flagged "within noise" and counted separately. The band comes from the measured self-consistency of multi-sample judging; without it a conservative default is used and labelled as such.
- **Different datasets:** cases present in only one run are listed separately ("new in this run", "removed") and excluded from the deltas, so a dataset revision never looks like a quality change.
- **Different run profiles:** comparing a Build run to a Full run shows which compared cases were carried over or reused on each side, so stale evidence is never presented as a change.
- Also written as `eval4j-compare.html` and as a short Markdown summary for pull requests (`-Deval4j.compare.baseline=main`), and a static-edition section.

## 5. Dimensions, metrics and goals

### 5.1 Dimensions come from the golden dataset

The report never uses a fixed list. **Every dimension the golden dataset declares is shown**, whether or not anything was evaluated for it, so a gap in testing is as visible as a gap in quality.

**Declaring dimensions.** A scenario states the dimensions it tests (additive, optional field):

```yaml
- name: gdpr-data-export
  input: "I am in the EU. Send me all the data you hold on me."
  expectedOutput: "Request an export under Settings, Privacy. We deliver it within 30 days."
  dimensions: [compliance, correctness, grounding]
```

`EvalScenario` gains `dimensions` (a list of ids; old constructors still work). A scenario with no `dimensions` is attributed to whichever dimensions its recorded metrics belong to, so existing datasets keep working.

**Dimension definitions.** Names, descriptions and goals live in project config (`eval4j.dimensions.yaml`), not in every scenario. eval4j ships defaults for the common set; teams add their own (Compliance, Multilingual, Accessibility, ...). Unknown ids still render, using the id as the name.

**The dimension list of a report is the union of:**
1. dimensions declared by the golden dataset(s) loaded in the run;
2. dimensions of any metric that actually recorded a result;
3. dimensions with a configured goal.

**Metric to dimension.** Each metric belongs to one dimension. Built-ins have defaults (below); a metric can be tagged explicitly. A metric with no dimension goes under "Other", so nothing is dropped.

| Default dimension | Built-in metrics mapped by default |
|---|---|
| Correctness | Answer correctness, task completion, tool-call accuracy |
| Relevancy | Answer relevancy, topic adherence |
| Grounding | Faithfulness, hallucination, citation accuracy |
| Retrieval | Contextual precision, recall, relevancy (RAG judges) |
| Efficiency | Latency, token budget, steps/tool calls (measured, not judged) |
| Safety & Tone | Toxicity, PII leakage, brand tone |

**Binding results to scenarios.** The extension reads the `EvalScenario` argument of a `@ParameterizedTest` automatically (via a JUnit invocation interceptor), so no test code changes. Tests that build scenarios some other way call `EvalRecorder.forScenario(...)` once.

**Dimension states** (shown on the card, in the coverage panel and in the gap list):

| State | Meaning | Shown as |
|---|---|---|
| Covered | Every scenario that declares it was evaluated | normal card |
| Partly evaluated | Some declared scenarios were not evaluated (skipped, budget, error) | normal card plus a warning naming the missing scenarios |
| Not evaluated | Declared by N scenarios but no result at all | dashed empty ring, "No results", excluded from the weighted pass rate, with causes and a fix |
| Declared, no scenarios | Has a goal but no scenario declares it | card with "No scenario tests this" |
| Undeclared | A metric recorded results for a dimension no scenario declares | normal card tagged "not in the dataset" |

**Golden dataset coverage panel.** One table on the overview: dimension, scenarios that declare it (n of N), evaluated this run (n of N), metrics wired, status. It names the dataset file, scenario count and revision. Rows open the dimension.

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
| `EvalRecord` | `scenarioId`, `dimension`, `kind` (`JUDGED`/`MEASURED`), `measuredValue`, `budget`, `unit`, `judge` (see below), `source` (`FRESH`/`REUSED`/`CARRIED`/`NOT_EVALUATED`), `evaluatedInRun`, `evaluatedAt`, `costUsd`, `judgeCalls` |
| Judge descriptor | `provider`, `model`, `temperature`, `samples`, `aggregation`, `rubricId`, `rubricVersion` |
| Judge run stats | calls, cache hits, latency mean/p95, tokens in/out, cost, failures, retries, sample-agreement |
| `EvalScenario` | `dimensions` (list of ids), optional `tags` |
| `EvalRecord` (more) | `family`, `facet`, `trace` (agent steps or `WorkflowTrace`, size-bounded) |
| `WorkflowTrace` | events (type, agent, step id, text, data, time), spend lines, workflow name and expected path |
| Prompt results | per-comparison pairwise outcomes; optimizer rounds (index, action, best score, cost) and diff |
| `RunInfo` | `dataset` (path, revision/hash, scenario count, per-dimension declared scenario ids), `goals`, `profile` (`FAST`/`BUILD`/`SAMPLE`/`FULL`), `coverage` (evaluated / total), `judgeBudgetUsd`, `agent` descriptor (model, provider, prompt id, tools), `judge` stats, calibration summary, last full judged run id and time |
| Run store | per-run JSON retained under `runs/<runId>.json` (default 40 runs, size-capped) with a lightweight index; per-case records keyed by a **stable case id** (hash of scenario id, metric and dimension) |
| `RunInfo.env` | commit, branch, agent prompt id/version, agent model, judge model, rubric version, dataset revision/hash, run profile, config hash (the "what changed" table) |
| Judge noise | per-metric estimate from multi-sample agreement, used for the within-noise band |
| `HistoryEntry` | per-dimension pass rate and counts (sparklines, "vs previous run"), run profile, spend, and per-case latest verdict with its run id (drives carry-over) |

Old reports load unchanged. Missing fields hide the related UI rather than showing empty boxes.
Judge and agent descriptors are supplied through the existing builders (`judgeIdentifier` today becomes a descriptor).

## 7. Visual and interaction rules

- **Donut caveat, handled:** a two-slice donut is weak for comparing values, so every donut carries the exact percentage in its centre, the goal tick on the ring, and the gap in text. Exact numbers never rely on reading an arc.
- **Colour:** blue = passed, red = failed (a colour-blind-safe pair, validated for light and dark), status pills use the fixed status palette **with an icon and a label**; evidence uses neutral tones plus a hatch pattern (carried over), so it never competes with pass/fail. No red/green pairing.
- **Never colour alone:** ✓ / ✕ icons in cells, "▼" on radar axes below goal, pills carry text.
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

Unchanged from v1 (`eval4j-report.html/json/csv`, `eval4j-junit.xml`, `eval4j-summary.md`, history). `eval4j-summary.md` gains a per-dimension table with goal and gap. `EvalReportCli` can re-render everything from JSON.

## 8a. Static edition (for Jenkins and other locked-down viewers)

Jenkins' default Content-Security-Policy blocks inline scripts and inline styles, and the interactive report relies on both. So every run also writes a **static edition**: the same data, simpler, and safe under that policy.

| | Interactive edition | Static edition |
|---|---|---|
| Files | `eval4j-report.html` (one self-contained file) | `eval4j-static.html` + `eval4j-static.css` |
| JavaScript | enhances: drill-down, filters, priorities, drawer, trace and graph pickers | none |
| Styling | inline `<style>` | linked stylesheet, no `style=""` attributes (SVG uses presentation attributes), so `style-src 'self'` accepts it |
| Navigation | left panel, in-page views | left panel of anchor links to sections on one page |
| Drill-down | click a donut | one section per family: dimension table, then `<details>` of failing evaluations |
| Charts | interactive SVG | static SVG: donuts with goal tick, bars, and the workflow path graph for runs with failed checks |
| Priorities | adjustable | configured defaults only |

The static edition keeps the whole classification: overview, every family, coverage, cost and evidence, judges and models, and the same "no verdict" wording. It is complete on its own and prints cleanly. The mockup of it is linked above.

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
| Q20 | Every dimension declared by the loaded golden dataset appears on the overview, in the radar, the gap list and the coverage panel, even with zero results |
| Q21 | A declared dimension with no results shows "No results", is excluded from the weighted pass rate, and explains causes and fixes |
| Q22 | A dimension evaluated on only some of its declared scenarios says so and names the missing ones |
| Q23 | A dimension added to a scenario's `dimensions` list appears in the next report with no code change; removing it from every scenario removes the card |
| Q24 | Datasets without `dimensions` still produce a report (dimensions inferred from recorded metrics) |
| Q34 | Any two retained runs can be compared; the default baseline is the previous run on the same branch |
| Q35 | The comparison shows what differed between the runs (commit, prompt, models, judge, rubric, dataset, profile) before it shows any result difference |
| Q36 | Movement is shown per family and per dimension with baseline, candidate, change and counts of cases that got worse and better |
| Q37 | A judged change smaller than the judge noise is flagged and counted separately, never presented as a regression or improvement |
| Q38 | Opening a changed case shows both verdicts and, for agent cases, both answers with a word diff |
| Q39 | Cases present in only one run are listed separately and excluded from deltas |
| Q40 | `eval4j-compare.html` and a PR-sized Markdown summary can be produced from two stored runs with the CLI, with no LLM call |
| Q25 | A metric with no dimension appears under "Other" and a recorded but undeclared dimension is flagged "not in the dataset" |
| Q26 | The left navigation lists every test family with its pass rate and a status icon, and works as an off-canvas menu on small screens |
| Q27 | Selecting a family or facet shows only its evaluations, sliced by dimension, with its own tiles and donuts |
| Q28 | The Prompts view shows A/B outcomes with order-flip counts, optimizer score by round against its goal, and the prompt diff |
| Q29 | The Reasoning view shows step-outcome counts, steps against budget, and a readable step-by-step trace for each agentic test |
| Q30 | The Trajectory view shows expected versus taken path, a per-agent timeline, the run's checks, spend by agent and a filterable event log |
| Q31 | Trajectory checks are deterministic assertions and make no LLM call |
| Q32 | The static edition has no `<script>` and no inline `style` attributes, renders under a default Jenkins CSP, and contains every family section |
| Q33 | eval4j gains no dependency on Loom |

## 10. Phasing

Run comparison is pulled forward. It depends only on phase 1 (stable case ids and a retained per-run store), it is the feature teams use every day, and the same store later powers carry-over.

| Phase | Contents |
|---|---|
| 1 | Data model: `EvalScenario.dimensions`, dimension registry and dataset descriptor, scenario binding (invocation interceptor), family and facet on metrics, dimension and kind on records, goals, evidence source and run provenance, judge/agent descriptors, **stable case ids, run environment snapshot, retained per-run store** |
| 2 | **Run comparison**: compare view, settings diff, movement by family and dimension, scatter, changed cases with word diff, judge-noise band, `eval4j-compare.html`, PR Markdown summary, CLI. Works on the current report pages before the new shell exists |
| 3 | Cost-aware runs: change-aware judging via the existing cache, run profiles, budget cap, coverage and "Not evaluated" (reuses the phase 1 store) |
| 4 | Shell and overview: left navigation, run summary, priorities, family tiles, dimension donuts with empty states, coverage, evidence, Models panel |
| 5 | Family pages and the dimension, metric, test, drawer drill-down; Agents (answers, tools), Conversations, Retrieval |
| 6 | **Static edition** (renders the same model as phases 4 and 5, including comparison) |
| 7 | Prompts view (A/B, optimizer, diff) and Agents / Reasoning view (step outcomes, trace viewer) |
| 8 | Loom: `WorkflowTrace` model, bridge, trajectory assertions, Workflows family and Trajectory view (graph, timeline, event log, spend) |
| 9 | Composite carry-over view, sampling with margin of error, judge reliability, independence check, summary updates |

Stability/flakiness and per-case cost analytics come after this.

## 11. Open questions

1. **Default baseline:** previous run on the same branch (proposed), or the last run on `main`? For pull requests, comparing to `main` is usually what a reviewer wants.
2. **Noise band:** estimate from multi-sample agreement (needs `samples(n)` > 1), or a fixed default (proposed ±0.07) until measured?
3. **Retention:** per-run JSON for 40 runs can reach tens of MB with large outputs. Cap by count, by size, or both?

4. **Workflow graph source:** Loom scripts are the source of the workflow graph. Should the report parse the `.loom` file for the graph, or should the bridge record the graph (nodes and edges) alongside the trace? Recording is simpler and does not need a Loom parser in eval4j.
5. **Families:** are Prompts, Agents, Conversations, Retrieval and Workflows the right top-level split? Loom's earned-autonomy replay could be a sixth.
6. **Static edition CSS:** one extra `.css` file is needed under Jenkins' default policy. Is a two-file output acceptable, with the interactive file staying single-file?
7. **Declaring dimensions:** is a per-scenario `dimensions:` list the right shape, or should datasets also carry a header block (name, description, goal per dimension)? The current YAML is a bare list, so a header would be a format change.
8. **Defaults:** are the six shipped default dimensions and their names right (for example "Accuracy" instead of "Correctness")?
9. **Priority presets:** should a team be able to publish named presets ("Customer-facing", "Internal tool") that stakeholders pick from, or is per-dimension control enough?
10. **Carry-over limits:** default staleness limit of 7 days, and should a carried-over result ever count towards the weighted pass rate, or be shown but excluded until refreshed?
11. **Sampling:** is a seeded, per-dimension stratified sample the right default, and what default percentage for the Sample profile?
12. **Efficiency:** are latency, tokens and steps the right measured metrics, and who supplies budgets?
13. **Pricing:** user-supplied only (proposed), or ship a default table that will go stale?
14. **Fonts:** embed Plex in the file (about 100 KB) or use the system font stack?
15. **Anything else on the overview** you want visible at a glance?


## 12. What "substantive" means: the release bar

The goal is a report with real depth, not a thin skin over a score. Every view that ships must meet all of these, and a phase is not done until it does.

1. **Real evidence, not just scores.** Every number drills down to the underlying case: input, output, expected, retrieved context, the judge's reasoning, and (for agents and workflows) the trace.
2. **Context for every number.** A goal and a gap beside it; a comparison with the previous run beside it; the evidence source (fresh, reused, carried over) and its age.
3. **Honest about uncertainty.** Judge noise, sampling margin of error, coverage ("17 of 18 scenarios evaluated"), stale evidence and "not evaluated" are shown, never hidden. No verdict is issued.
4. **Explains change, not just reports it.** Comparison leads with what differed between runs, then what moved.
5. **Complete in both editions.** The interactive and static editions carry the same content; interactivity only changes how it is explored.
6. **Proven on real evals.** Each phase is demonstrated on evaluations this repository already has (the support-agent style golden dataset, `examples/getviral` for Loom, the optimizer and comparison tests), not on invented data.
7. **Tested.** Report generation is covered by unit and snapshot tests on the Java side, with the same hostile-text, size and old-format-compatibility tests as v1; UI behaviour is covered by a headless-browser test where scripts are involved.
8. **Accessible and private.** Never colour alone, keyboard navigable, both themes, no network request, all dynamic text escaped.

**Parity floor against hosted tools.** Before the first public release the report must match the everyday core of hosted dashboards: test history and pass-rate trends, run-to-run regression comparison against a baseline, per-case drill-down with inputs and outputs, metric and status filtering, and shareable reports. It then goes beyond with what is specific to eval4j: dimensions from the golden dataset, cost-aware evidence, family classification, and Loom trajectories. Deliberately out of scope because they need a server: team comments, production tracing, alerting and dataset editing UIs.
