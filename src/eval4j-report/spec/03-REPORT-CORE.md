# 03. The `eval4j-report` module: reading, analysing, comparing

Status: **Draft for review** · Module: `eval4j-report` (new) · Package root: `io.github.llm4j.evalreport` · Input contract: [01-RUN-FORMAT.md](01-RUN-FORMAT.md) · Output: [04-DASHBOARD-UI.md](04-DASHBOARD-UI.md)

The module turns run bundles into an **analysed report model** and hands that model to renderers. All numbers shown anywhere are computed here, once, so the interactive edition, the static edition, the Markdown summary and the CLI never disagree.

## 1. Module

| Item | Value |
|---|---|
| Maven | `io.github.srijithunni7182:eval4j-report`, version aligned with the repo (`5.x`), `jar`, Java 17 |
| Runtime dependencies | `jackson-databind`, `jackson-dataformat-yaml` (configuration). **Nothing else required.** `eval4j` is declared `<optional>true</optional>` and is used only by `spi.ReportRunExportListener` (D2, §11). No `ai-agent4j`, no JUnit at runtime |
| Test dependencies | JUnit, AssertJ, `json-schema-validator` (Networknt) for contract tests, a headless-browser test harness for the interactive edition (07) |
| Resources | `dashboard.css`, `dashboard.js`, `static.css`, brand SVG symbols, JSON Schemas (copied from `spec/schema` at build time), default configuration |
| Jars | the library jar; a **CLI jar** with classifier `cli` (shaded, `Main-Class` set) built in a profile so the plain artifact stays small |
| Service file | `META-INF/services/io.github.llm4j.eval.export.RunExportListener` naming `io.github.llm4j.evalreport.spi.ReportRunExportListener`. `ServiceLoader` only reads it inside a JVM that already has eval4j (and therefore the interface) on the classpath |
| Repo conventions | own `pom.xml` (no parent), Spotless, source and javadoc jars, Central publishing profile, entry in the root aggregator, `docs/VERSION_MATRIX.md`, `Jenkinsfile` stage (08) |

## 2. Package layout

```
io.github.llm4j.evalreport
  EvalReport                 public façade: render(...), compare(...), load(...)
  format/                    reading the contract
    RunBundleReader            streams one bundle; strict and lenient modes
    BundleMerger               merges bundles of one groupId (FMT §7)
    RunStore                   a directory of bundles: list, load, index, prune
    LegacyV1Importer           v1 eval4j-report.json → bundle
    model/                     Run, Evaluation, Metric, Scenario, TestOutcome, Trace, Optimization, … (own records)
    SchemaVersion, Warnings
  config/                    ReportConfig (+ loader): dimensions, families, goals, priorities, branding, compare, retention
  analysis/                  pure functions over the model
    Classify                   resolve family / facet / dimension for an evaluation
    Rollup                     counts, rates, averages, histograms per scope
    Coverage                   declared vs evaluated, dimension states
    Evidence                   fresh / reused / carried mix, cost, saved-by-reuse
    Goals                      goals, gaps, status
    Priorities                 weights, weighted pass rate
    Trends                     history series per branch
    Baselines                  baseline selection (D7)
    Noise                      judge noise band
    Compare                    run-vs-run engine
    Reliability                judge trust panel, independence check
  model/                     the analysed, renderer-facing model (immutable records)
    ReportModel, Overview, FamilyView, FacetView, DimensionView, MetricView, CaseView,
    CompareModel, CoverageModel, CostModel, ModelsModel, TraceView, DataNotes
  render/
    interactive/              InteractiveRenderer, DataEmbed, templates
    stat/                     StaticRenderer
    svg/                      Donut, Dumbbell, Scatter, Histogram, Radar, PathGraph, Gantt, Bar, Sparkline
    MarkdownSummary, JUnitXml, Csv, Escape
  cli/                       Main, commands (05)
  spi/                       ReportRunExportListener
```

| Req | Statement |
|---|---|
| RPT-01 | `analysis/` and `model/` MUST be pure and deterministic: the same inputs and configuration give byte-identical models (no clock, no randomness). The only clock use is `generatedAt`, injected. |
| RPT-02 | `render/` MUST NOT recompute any number; it formats the model. |
| RPT-03 | `format/model` classes are the module's own records. They MUST NOT import eval4j or ai-agent4j types. |

## 3. Reading bundles

### 3.1 `RunBundleReader`

```java
RunBundleReader.open(Path bundleDir, ReadMode mode)   // STRICT or LENIENT
    .run()                       // header (RunMeta)
    .evaluations()               // Stream<Evaluation>, lazily read, closeable
    .scenarios() .tests() .traces() .optimizations()   // likewise
    .warnings()                  // ReadWarning list, filled while streaming
```

| Req | Statement |
|---|---|
| RPT-10 | Reading MUST stream `.jsonl` (and `.jsonl.gz`) line by line; memory is bounded by the largest line, not the file (FMT-26). |
| RPT-11 | In `LENIENT` mode (default) a bad line is skipped with a warning (line number, reason); a truncated last line is tolerated (FMT-06). In `STRICT` mode any schema violation fails with a message naming file, line and field. The CLI exposes `--strict`. |
| RPT-12 | The reader checks `schemaVersion` against FMT-19/20 and fails fast with a clear message for a newer major. |
| RPT-13 | The reader validates enum values leniently (FMT-21): an unknown `kind`, `source`, outcome or event type is kept as a string and rendered as-is. |
| RPT-14 | `RunStore.list(root)` reads `index.jsonl` when present and consistent, else scans `runs/*/run.json`; it never loads evaluations to list runs. |
| RPT-15 | Out-of-range scores are clamped with a warning; `passed = null` on an `EVALUATED` line is treated as `ERROR` with a warning. |

### 3.2 Merging and legacy

`BundleMerger` implements FMT §7. `LegacyV1Importer` implements FMT §10 and is exposed as `import-legacy` in the CLI; it also lets the interactive report open an old `eval4j-report.json` directly.

## 4. Configuration (what the report adds to the facts)

`ReportConfig` is loaded from `eval4j-report.yaml` (or `.json`), then overridden by CLI options (D3). Full schema and example in [05](05-CLI-AND-CONFIG.md). It supplies, per dimension and family: display name, blurb, **goal**, default **priority**, ordering; and for the whole report: project name and optional logo, status thresholds, the noise-band default, baseline policy, retention, detail limits, and how carried evidence counts.

Defaults shipped in the jar (overridable): display names and one-line descriptions for the built-in dimensions `correctness`, `relevancy`, `grounding`, `retrieval`, `efficiency`, `safety`, `reasoning`, `orchestration`, `prompting` and the families `prompts`, `agents`, `conversations`, `retrieval`, `workflows` with their facets; every goal `90`; every priority `IMPORTANT`. An unknown id is shown with its id title-cased and the default goal.

| Req | Statement |
|---|---|
| RPT-20 | Configuration MUST NOT change any stored result, only how results are presented and weighted (FMT-15). |
| RPT-21 | Unknown configuration keys are a warning, not an error. Invalid values (a goal outside 0..100) are an error with the key path. |
| RPT-22 | The configuration that produced a report is embedded in it (as inert data) so a report is self-describing. |

## 5. The analysed model: algorithms

Throughout, an evaluation is **counted** when `status = EVALUATED`. `NOT_EVALUATED` and `ERROR` evaluations are tracked separately and never count as passed or failed. `REUSED` and `CARRIED` evaluations are counted (they carry a real verdict) and are always labelled by source.

### 5.1 Classification

For each evaluation, resolve in order: the evaluation's own `family` / `facet` / `dimension` if non-null; else the metric's (from `run.metrics`); else defaults (`family = agents`, `facet = answers`); the dimension has **no default**: unresolved goes to the dimension id `other` ("Other"). Resolution is done once at load; the model never holds an unresolved evaluation.

### 5.2 Rates and averages

For any set `S` of evaluations:

```
passed(S)   = |{e ∈ S : counted, e.passed}|
failed(S)   = |{e ∈ S : counted, !e.passed}|
rate(S)     = 100 * passed / (passed + failed)            undefined if the denominator is 0
avgScore(S) = mean of e.score over counted e with a non-null score
```

Rates are percentages with full precision in the model; renderers round for display (one rule, shown in 04 §10).

### 5.3 Rollups

Rollups exist for: the run; each family; each (family, facet); each dimension (overall); each dimension **within** a family or facet (scoped); each metric; each case. Every rollup holds `passed`, `failed`, `notEvaluated`, `errors`, `avgScore`, the `bySource` counts, `histogram[10]` (`bucket = min(9, floor(score * 10))`, split passed/failed) and, where a goal applies, `goal`, `gap`, `status`.

### 5.4 Goals, gap and status

```
goal(dimension)  = config.dimensions[id].goal      else config.defaultGoal (90)
goal(family)     = config.families[id].goal        else mean(goal of its dimensions present) rounded to 1 dp, else defaultGoal
gap              = rate − goal                      (percentage points, signed)
status           = MEETS_GOAL        if gap ≥ 0
                   BELOW_GOAL        if −warnGap < gap < 0
                   WELL_BELOW_GOAL   if gap ≤ −warnGap          (warnGap = 10 by default, configurable)
                   NO_RESULTS        if rate is undefined
```

A goal is context, not a gate: no status ever changes an exit code (CLI-08).

### 5.5 Priorities and the weighted pass rate

Priority levels and weights: `CRITICAL` 3, `IMPORTANT` 2, `NICE_TO_HAVE` 1, `NONE` 0 (the UI label "Not a priority").

```
weightedRate = Σ ( w_d × rate_d ) / Σ w_d      over dimensions d with a defined rate and w_d > 0
weightedGoal = Σ ( w_d × goal_d ) / Σ w_d      over the same dimensions
```

Both are undefined when `Σ w_d = 0` (the UI shows an explanatory empty state, Q15). The interactive edition recomputes these in the browser when a viewer changes priorities, using the same formula; the model supplies each dimension's `rate`, `goal` and default weight so the browser needs no other data. Test vectors for this formula are shared by Java and JavaScript tests (07).

### 5.6 Coverage and dimension states

For each dimension `d` in the **union** of: dimensions declared by `scenarios.jsonl`, dimensions of counted evaluations, and dimensions with a configured goal:

```
declared(d)    = scenarios that list d                          (by caseId)
evaluatedCases = distinct caseId with ≥1 counted evaluation in d
```

| State | Rule |
|---|---|
| `COVERED` | `declared(d)` non-empty and every declared case is in `evaluatedCases` |
| `PARTLY_EVALUATED` | some, not all, declared cases are in `evaluatedCases`; the missing scenarios are listed |
| `NOT_EVALUATED` | `declared(d)` non-empty, `evaluatedCases` empty |
| `DECLARED_NO_SCENARIOS` | a goal is configured, no scenario declares `d`, no evaluations |
| `UNDECLARED` | counted evaluations exist but no scenario declares `d` (and a dataset was loaded) |
| `NO_DATASET` | the run has no `scenarios.jsonl`; declared-ness is unknown, so no coverage claim is made |

`NOT_EVALUATED` and `NO_RESULTS` dimensions are shown as empty cards, **excluded from the weighted pass rate**, with the causes and fixes in the product spec §5.1.

### 5.7 Evidence, cost and saved-by-reuse

```
mix(S)             = counts of FRESH / REUSED / CARRIED among counted evaluations in S
freshJudged        = counted evaluations, kind in {JUDGE, PAIRWISE}, source = FRESH
avgCostPerFresh    = Σ costUsd over freshJudged / |freshJudged|        (only if every term has a cost)
judgedTotal        = counted evaluations with kind in {JUDGE, PAIRWISE}
estimatedFullCost  = avgCostPerFresh × judgedTotal                      (omitted when avgCostPerFresh is unknown)
savedByReuse       = max(0, estimatedFullCost − Σ costUsd of this run's evaluations)
```

The Cost-and-evidence page shows the profile, the mix, `judge spend vs budget` (`profile.judgeBudgetUsd`), `savedByReuse`, `estimatedFullCost`, and the age and run of carried evidence (the most common `evaluatedInRun` and the oldest `evaluatedAt`).

### 5.8 Judge reliability and the independence check

From `env.judges[].stats`: self-consistency, agreement with people (if `extensions.calibration` is present: `kappa`, `labelledCases`), `failures / calls`. **Independence check:** for each judge and each agent, derive a *model family* = the lower-cased model name up to the first digit, `-`, `:` or `/` (`gemini-2.5-pro` → `gemini`, `llama3.3:70b` → `llama`, `claude-…` → `claude`, `gpt-4o` → `gpt`). If a judge's provider and family equal an agent's, warn ("the judge and the agent come from the same model family"). It is a heuristic and is labelled as one.

### 5.9 Trends

For the candidate's branch, the last `trendRuns` (default 40) runs with `status ∈ {COMPLETE, PARTIAL}` in the run store, oldest first, ending with the candidate. Per run and per dimension: `rate`, counted from that run's bundle (cached in `index.jsonl` extensions as `dimRates` to avoid re-reading; recomputed if absent). A run with no counted evaluations in a dimension contributes a gap, not zero. Sparklines draw the dimension's series; the goal is a dashed line.

## 6. Baseline selection (D7)

**Decision (product owner):** the default baseline is the **previous run on the same branch**.

Inputs: candidate run `C` (the run being reported, a merged group counts as one run); the run store; config `compare.baseline` (default `sameBranch`) and `compare.defaultBranch` (default: `main`, else `master`).

Eligible runs `E` = runs in the store with the same `project.name` (or both absent), `runId ≠ C.runId`, `startedAt < C.startedAt`, and `status ∈ {COMPLETE, PARTIAL}`; a `COMPLETE` run is preferred over a `PARTIAL` one at the same position.

```
baseline policy  sameBranch  (default)
  1. B = newest run in E whose source.branch equals C.source.branch      (both non-null)
  2. else B = newest run in E on the default branch                       → labelled "baseline from <default branch>"
  3. else none                                                            → compare shows "no baseline yet"
policy  main        : newest run on the default branch (what a pull-request reviewer often wants)
policy  lastFull    : newest run in E whose profile.name = FULL
policy  pinned:<id> : exactly that run
policy  <runId>     : exactly that run (CLI --baseline)
```

| Req | Statement |
|---|---|
| RPT-30 | With no explicit choice, the baseline MUST be selected by `sameBranch` as above, and the report MUST state which rule picked it ("Previous run on branch `feature/x`", "Baseline from `main`: no earlier run on this branch"). |
| RPT-31 | If `C.source.branch` is null (detached HEAD), step 1 is skipped and the report says why. |
| RPT-32 | A baseline whose `profile.name` differs from the candidate's, or whose `configHash` differs, is **allowed** and flagged in the comparison header ("baseline was a FULL run; compared cases may be carried over on either side"). |
| RPT-33 | The interactive edition lists every retained run in the baseline picker; the choice is a view state, never a stored result. |

## 7. Run comparison

### 7.1 Matching and classification

Let `Base` and `Cmp` be the evaluations of the baseline and the candidate, keyed by `key` (any status). For repeated keys, FMT §3.1's occurrence index makes keys unique within a run; a collision is a warning and the later `seq` wins. A line is **counted** when its `status` is `EVALUATED` (5. above).

| Class | Rule |
|---|---|
| `WORSE` | key on both sides, both counted, `Base.passed ∧ ¬Cmp.passed` |
| `BETTER` | key on both sides, both counted, `¬Base.passed ∧ Cmp.passed` |
| `SAME` | key on both sides, both counted, `passed` equal |
| `NEW` | key only in the candidate, and counted there |
| `REMOVED` | key only in the baseline, and counted there |
| `NOT_COMPARABLE` | key on both sides but at least one side is `NOT_EVALUATED` or `ERROR` |

A key present on only one side and **not** counted there (for example a `NOT_EVALUATED` line with no baseline counterpart) belongs to no class; it still appears in that run's `notEvaluated` total.

`scoreDelta = Cmp.score − Base.score` where both scores exist. Cases present on only one side are **listed separately and excluded from every delta** (so a dataset revision never looks like a quality change).

### 7.2 Judge noise

For a `JUDGE` evaluation pair classified `WORSE` or `BETTER`:

```
band(metric) = judge.stats.noiseBand            if the candidate's judge reports one
             = config.compare.noiseBand         otherwise  (default 0.07)
withinNoise  = kind = JUDGE ∧ |scoreDelta| ≤ band(metric)
```

A `withinNoise` change is counted in its own bucket and shown with a "within noise" mark; it is never described as a regression or an improvement. If both sides have ≥ 2 `samples`, the per-pair band MAY widen to the pooled standard deviation of the samples (a refinement, off by default, `compare.pooledNoise`). `ASSERTION`, `MEASURED` and `PAIRWISE` results have no noise band.

### 7.3 Aggregates

For each family, facet and dimension the model holds, over **matched comparable keys only**: `baseRate`, `candRate` (computed as in 5.2 on the matched sets), `delta = candRate − baseRate`, counts of `WORSE`, `BETTER`, `withinNoise`, and the goal for the dumbbell tick. It also holds the overall figures over all counted evaluations of each run (so the headline can say "pass rate 84.1% → 84.7% overall; 71 of 624 evaluations changed"), and counts of `NEW`, `REMOVED`, `NOT_COMPARABLE`.

### 7.4 Environment difference

For each field of `env` (agents, judges, datasets, versions) and `source`/`profile`, a row `{field, baseline, candidate, changed}` with `changed = baseline ≠ candidate`. Lists (agents, judges, datasets) are compared by `id` then by their descriptive fields. `configHash` mismatch adds a warning row.

### 7.5 Changed cases and answer diff

Changed cases are `WORSE ∪ BETTER`, sorted by `|scoreDelta|` descending (nulls last). Each carries both sides' `reason`, `score`, `display`, `source`, and both `actualOutput`s. The **word-level diff** of the two outputs is produced in the browser (interactive) or omitted (static: both texts side by side, no diff); the Markdown summary lists the top N cases without text. If the two `actualOutput`s are equal the model sets `answerUnchanged = true`.

### 7.6 Scatter data

For every `JUDGE` pair: `(baseScore, candScore, class, key)`. Capped at 5,000 points (a seeded sample of `SAME` points is dropped first; all changed points are kept).

| Req | Statement |
|---|---|
| RPT-35 | Matching MUST be by `key` only; names and display text are never used. |
| RPT-36 | `NEW`, `REMOVED` and `NOT_COMPARABLE` MUST NOT contribute to any delta or count of better/worse. |
| RPT-37 | The comparison MUST lead with the environment difference and the headline counts; no verdict wording (product spec §4.9). |
| RPT-38 | A comparison between runs of different datasets (`env.datasets[].hash` differ) MUST say so and report how many cases were matched. |
| RPT-39 | Comparison is a pure function of two merged runs and the config (RPT-01). |

## 8. Detail limits and size control

The interactive edition embeds per-case detail as inert JSON blocks. To bound file size:

| Config key | Default | Meaning |
|---|---|---|
| `detail.maxCases` | 3000 | Max cases with full text embedded. |
| `detail.order` | failing, changed vs baseline, lowest scores, then the rest | Which cases keep their text when over the limit. |
| `detail.maxTextChars` | 4000 | Per text field in the embedded copy (the bundle keeps 20,000). |
| `detail.maxTraces` | 300 | Traces embedded. |
| `report.maxBytes` | 25 MB | If exceeded, detail is dropped by `detail.order` until it fits, and a data note says so. |

Cases over the limit appear in lists with scores and reasons, and a note "full text not embedded; see the bundle". `eval4j-report render --full-detail` lifts the limits.

## 9. Warnings ("data notes")

Everything the reader or analysis could not take at face value is collected as a `DataNote{severity, code, message, where}` and shown in a collapsed "Data notes" panel in both editions and in the CLI summary. Codes (stable): `TRUNCATED_LINE`, `BAD_LINE`, `SCORE_CLAMPED`, `KEY_COLLISION`, `UNKNOWN_ENUM`, `METRIC_UNDEFINED`, `RUN_NOT_FINISHED`, `CONFIG_HASH_DIFFERS`, `BASELINE_FALLBACK`, `DATASET_DIFFERS`, `GROUP_MISMATCH`, `DETAIL_TRIMMED`, `NO_SCENARIOS`, `PRICING_MISSING`.

## 10. Performance and limits

| Req | Statement |
|---|---|
| RPT-40 | Reading and analysing 100,000 evaluations (without traces) MUST take under 10 s and under 512 MB of heap on a typical CI machine; 10,000 evaluations under 2 s. |
| RPT-41 | Rendering the interactive edition for 10,000 evaluations MUST take under 5 s and produce at most 25 MB (RPT detail limits). |
| RPT-42 | The static edition has no detail limit on failing evaluations but trims passing ones to a count; its size MUST stay under 5 MB for 10,000 evaluations. |
| RPT-43 | The module MUST be usable from several threads rendering different runs (no shared mutable state). |

## 11. Embedding and the listener

```java
public final class EvalReport {
    public static ReportModel load(Path exportRoot, ReportConfig config, LoadOptions options);
    public static CompareModel compare(RunData baseline, RunData candidate, ReportConfig config);
    public static RenderResult render(ReportModel model, RenderOptions options, Path outDir);
}
```

`ReportRunExportListener` is the only class that touches eval4j: it implements eval4j's `RunExportListener` (02 §11) and calls `EvalReport` with the paths it is given. `eval4j` is an **optional** dependency of `eval4j-report`, so the class compiles normally, is excluded from the CLI's shaded jar, and is never loaded unless eval4j is present. Because the interface passes only paths and ids (EXP-39), report and eval4j versions stay independent; the minimum compatible eval4j version is recorded in the module's docs and checked at listener start (an incompatible `schemaVersion` in the bundle is reported by the reader, FMT-20).

## 12. Requirements for tests

| Req | Statement |
|---|---|
| RPT-50 | Every example bundle in `spec/examples` loads without warnings and yields the documented counts. |
| RPT-51 | Analysis functions have golden-file tests (a model serialised to JSON and compared) for each example. |
| RPT-52 | The weighted-rate and rate formulas have shared test vectors executed in both Java and the browser script. |
| RPT-53 | A property test: shuffling the line order of `evaluations.jsonl` does not change any rollup or comparison. |
| RPT-54 | A property test: duplicating a bundle as baseline and candidate yields zero changed cases. |

## 13. Open questions

- **Q-R1.** Whether to split the listener into a micro-artifact if the optional-dependency approach causes trouble with some build tools.
- **Q-R2.** Whether `RunStore` should write per-run `dimRates` into `index.jsonl` (cheap trends) at export time (eval4j) or lazily on first report (report side).
- **Q-R3.** Pooled-noise refinement on by default once enough runs use `samples > 1`.
