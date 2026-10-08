# 08. Delivery plan

Status: **Draft for review** · Phases follow the product spec's priorities (comparison early) and the dependency structure created by the bundle contract.

## 1. The shape of the work

The bundle format is the seam. Once the schemas and the example bundles exist (phase 0), **eval4j and eval4j-report can be built in parallel**, because the report develops against the example bundles and eval4j develops against the schemas. They meet in the dogfood gates.

```
 P0 foundations ──┬──► P1 export (eval4j) ─────────────┐
                  │                                     ├─► dogfood: real bundles ─► P4 cost-aware (eval4j + report)
                  └──► P2 report core + compare ──► P3 dashboard core + shell ─► P7 static edition
                                                          │
                                                          ├──► P5 prompts + reasoning views (needs P1 traces/optimizer export)
                                                          └──► P6 Loom (needs P1 trace model)          ──► P8 carry-over + polish + release
```

Track A (eval4j): P1 → P4 (eval4j half) → P5 (export half) → P6 (model + assertions) → P8 (carry-over).
Track B (eval4j-report): P2 → P3 → P7 → P5/P6 views → P8.
Track C (Loom bridge, a package of `eval4j-report`): P6 only, after the trace model exists.

## 2. Releases

| Release | Contents | Notes |
|---|---|---|
| **5.1** (eval4j + eval4j-report together) | P0–P3 and P7: export, comparison, interactive dashboard, static edition; v1 report writers **deprecated** | The first release a user can adopt end to end. |
| **5.2** | P4, P5: cost-aware runs, prompts and reasoning views | |
| **5.3** | P6: Loom bridge and trajectory view | Loom bridge first published (in `eval4j-report`). |
| **5.4** | P8: carry-over, reliability, presets | |
| **6.0** | removal of the deprecated v1 report code from eval4j | Only after one full release cycle with the migration guide. |

Each release passes the release bar (product spec §12) and the test gates in [07](07-TEST-STRATEGY.md). A feature that is not complete (real evidence, context, honesty about uncertainty, both editions, dogfooded, tested) does not ship behind a flag; it waits.

## 3. Work items

Sizes are relative: **S** a few days, **M** about a week, **L** one to two weeks for one engineer. They are for sequencing, not commitments.

### Phase 0: foundations

| WI | Scope | Acceptance | Size |
|---|---|---|---|
| WI-001 | Create the `eval4j-report` module skeleton: `pom.xml` (no parent; Jackson + YAML; Spotless; source/javadoc jars; publishing profile; `cli` shade profile), package layout (03 §2), `README.md`; add to the root aggregator pom, `docs/VERSION_MATRIX.md`, `docs/PUBLISHING.md`, the `Jenkinsfile` (build, tests, quality stages), `llms.txt`, `CONTRIBUTING.md`. | Module builds in CI with an empty test; appears in the matrix and root build. | S |
| WI-002 | Contract pipeline: copy `spec/schema` into both modules' test resources at build time with the equality check (TST-01); schema validation tests for every example (TST-02); `expected.json` loader scaffolding. | TST-01, TST-02 green in both modules. | S |

### Phase 1: eval4j export (Track A)

| WI | Scope | Requirements | Size |
|---|---|---|---|
| WI-101 | `export` package core: `Evaluation`, `MetricRef`, `CaseKey`, `Hashes`, `RunBundleLayout`, `ExportConfig`, `RunWriter` (append-only, atomic finish, retention, size cap, `index.jsonl`, shutdown hook). | EXP-02, 05, 34–37, 40–43; FMT-01..08 | L |
| WI-102 | `EvalRecorder` refactor: `record(Evaluation)` and the fluent builder; legacy overloads map to it; thread-local current case; text truncation and redaction; no-op when off. | EXP-10..14, 47, 48 | M |
| WI-103 | `EvalScenario` `id`/`dimensions`/`tags` (compat constructor), YAML loading, `DatasetRegistry`, `DatasetDescriptor`, `scenarios.jsonl`. | EXP-22..24 | M |
| WI-104 | Scenario binding: `EvalReportExtension` as `InvocationInterceptor`; `forScenario`/`forCase`. | EXP-25..27, 45 | S |
| WI-105 | Judge outcome capture: internal `JudgeOutcome` (samples, hits, tokens, model, latency), `REUSED`/`FRESH`, judge fingerprint in `JudgeCacheKey`, `Pricing`, `JudgeStats`, judge-error → `ERROR`. Covers `LlmJudgeCondition`, `RagContextCondition`, `ConversationJudgeCondition`, `PairwiseCondition`. | EXP-19..21, 30 (partly) | M |
| WI-106 | Instrument deterministic assertions in `AgentResultAssert`, `ConversationAssert`, `LlmResponseAssert` (record pass and fail, rethrow unchanged); settle the naming API (Q-A). | EXP-15..18, 44 | M |
| WI-107 | `RunEnvironment` (branch, commit, CI, PR), `AgentDescriptor` via `EvalRunContext`/`@EvalAgent`, `JudgeDescriptor`, `configHash`; `run.json` header and finish. | EXP §7.2–7.3, FMT-13/14 | M |
| WI-108 | `tests.jsonl` from the `TestWatcher`; agent traces from `AgentResult` (`TraceRecorder`); metric registry written to `run.json`. | EXP §9 | M |
| WI-109 | `RunExportListener` SPI, console hint, deprecate the v1 writers and history, `eval4j.report.dir` alias, migration notes. | EXP-38/39, §12 | S |

**Phase 1 done when:** TST-03, TST-05, TST-06 fixtures exist; eval4j's own judged tests and `examples/getviral` produce valid bundles (07 §7, gate 1); the existing eval4j suite passes unchanged with export off (EXP-47).

### Phase 2: report core and comparison (Track B, starts after WI-002)

| WI | Scope | Requirements | Size |
|---|---|---|---|
| WI-201 | Report-side model records and `RunBundleReader` (stream, `.gz`, strict/lenient, warnings, version checks). | RPT-10..15, FMT-19..21 | M |
| WI-202 | `RunStore` (list, load, `index.jsonl` read/rebuild, prune), `BundleMerger`, `LegacyV1Importer`. | RPT-14, FMT §7, §10 | M |
| WI-203 | `ReportConfig`: loader (YAML/JSON), shipped defaults, validation with key paths, precedence, `config init/check`. | RPT-20..22, CLI-20..23 | M |
| WI-204 | Analysis: `Classify`, `Rollup`, `Goals`, `Priorities`, `Coverage`, `Evidence`; model records. | RPT-01..03, 5.1–5.7; golden tests | L |
| WI-205 | `Baselines` (D7), `Compare`, `Noise`, aggregates, scatter data; the property tests. | RPT-30..39, 53/54 | L |
| WI-206 | Non-HTML outputs: `summary.md`, `compare.md` (≤ 60k chars), `junit.xml`, `report.csv`, escaping utilities. | UI §9, CLI-34 | M |
| WI-207 | CLI: `render` (non-HTML parts for now), `compare`, `list`, `index`, `validate`, `merge`, `import-legacy`, `prune`, exit codes; the cli shade jar. | CLI-08..10, 30..33 | M |
| WI-208 | First comparison page (`compare/<b>..<c>.html`), self-contained, built on the shared tokens and chart functions started here (dumbbell, scatter); superseded by the integrated view in WI-307. | UI §8 | M |

**Phase 2 done when:** `expected.json` is reproduced exactly (RPT-50/51); `compare` on two real runs of the same evaluation (a deliberately worsened prompt) gives headline counts a person verified (gate 2); `compare.md` pastes into a pull request.

### Phase 3: dashboard core (Track B)

| WI | Scope | Requirements | Size |
|---|---|---|---|
| WI-301 | Render infrastructure: template assembly, `DataEmbed` (UI-20), CSS tokens and themes, brand symbol, chart library (donut, bar, sparkline, histogram, radar) with golden SVG tests and Node chart tests. | UI-20, 30, 31, 40, 41 | L |
| WI-302 | Shell: navigation (families, facets, insights, status icons), top bar, breadcrumb, hash routing, theme toggle, off-canvas menu, drawer scaffold, focus management. | UI-05..07, 10..13 | M |
| WI-303 | Overview: hero, gap list, priorities (shared vectors), family tiles, radar by family, dimension cards (empty, partial, evidence bar, sparkline). Server-rendered parts per UI-03. | UI §6, RPT-52 | L |
| WI-304 | Family and facet pages; dimension drill-down (metric cards, histogram, tests table, filters, search). | UI §2, §6 | L |
| WI-305 | Case drawer for every family (without trace viewers). | UI §3 | M |
| WI-306 | Dataset coverage, Cost and evidence, Judges and models pages. | 03 §5.6–5.8 | M |
| WI-307 | Integrated Compare view: pickers, headline, environment table, dumbbells, scatter, changed cases with word diff. | UI §8 | L |
| WI-308 | `ReportRunExportListener`, automatic mode, the Maven recipe test (a sample project runs `mvn test` and gets a dashboard). | EXP-38/39, CLI §1 | S |
| WI-309 | Browser test harness; accessibility and keyboard checks; hostile fixtures; size and speed budgets. | TST-20..33, UI-60..63 | M |

**Phase 3 done when:** the dashboard renders the phase 1 bundles; every number in the overview matches a manual count for three dimensions (gate 3); TST-20..33 are green; the report passes the release bar items 1–5 for the views in scope.

### Phase 4: cost-aware runs (Tracks A and B)

| WI | Scope | Requirements | Size |
|---|---|---|---|
| WI-401 | eval4j: `EvalProfile`, judge budget, `NOT_EVALUATED` with test abort, `PARTIAL` status, `BUILD` reuse proven by the counting judge. | EXP-30..32, 46 | L |
| WI-402 | eval4j: seeded stratified `SAMPLE` selection. | EXP-31 | S |
| WI-403 | Report: evidence bars and panels, judge spend vs budget, saved-by-reuse, staleness flags, trends from retained runs, margin of error for sampled runs. | 03 §5.7, §5.9; product §5a | M |

**Done when:** a `BUILD` run after a `FULL` run on an unchanged agent makes zero judge calls and the report shows it (gate 4).

### Phase 5: prompts and reasoning (Tracks A and B)

| WI | Scope | Size |
|---|---|---|
| WI-501 | eval4j: `optimizations.jsonl` from `OptimizationRun`/`OptimizationResult`; `PromptComparison` pairwise export with order-flip data. | M |
| WI-502 | Report: Prompts view (A/B cards, optimizer chart, rounds, diff, overfitting check, budget). | M |
| WI-503 | Report: Agents → Reasoning (summary tiles, step-outcome counts, steps histogram, traces table) and the trace viewer in the drawer. | M |

### Phase 6: Loom (Tracks A, B, C)

| WI | Scope | Requirements | Size |
|---|---|---|---|
| WI-601 | eval4j: `WorkflowTrace` model, `TraceRecorder.workflow`, trajectory assertions (06 §5). | LOOM-10..12 | M |
| WI-602 | `LoomTrace` in `eval4j-report` package `evalreport.loom` (listener, graph from AST, expected/actual path, spend). | LOOM-01..04, 20..24 | L |
| WI-603 | Report: Workflows family and Trajectory view (deterministic graph layout, path rendering, Gantt, checks, spend, event log). | UI §7.3, UI-42 | L |

**Done when:** the `examples/getviral` workflow shows a graph, timeline, spend and log, and a deliberately broken workflow shows the red node and the failing check (gate 6).

### Phase 7: static edition (Track B, after WI-304)

| WI | Scope | Requirements | Size |
|---|---|---|---|
| WI-701 | `StaticRenderer` + `eval4j-static.css`: every route as a section, `<details>` for failing lists and graphs, comparison tables; the CSP test; the Jenkins publish recipe; print stylesheet. | UI-01, 04, 40, §11; TST-22 | L |

**Done when:** this repository's Jenkins stage publishes the static edition and it renders under Jenkins' default policy (gate 7).

### Phase 8: carry-over, polish, release

| WI | Scope | Size |
|---|---|---|
| WI-801 | eval4j: carry-over provider (previous bundle on the same branch), `CARRIED` lines, reported-not-asserted semantics. | M |
| WI-802 | Report: judge reliability panel (self-consistency, agreement with people when supplied) and the independence check. | S |
| WI-803 | Priority presets, logo/font branding, documentation (below), migration guide, release notes. | M |

## 4. Documentation and repository changes

| Item | Change |
|---|---|
| `src/eval4j/docs/REPORTING-AND-BASELINES.md` | Rewrite: bundles, `eval4j-report`, `@EvalBaseline` unchanged; link to the report docs. |
| `src/eval4j/README.md`, `src/eval4j/docs/README.md`, root `README.md` | Mention the add-on and the dashboard; update the ecosystem table. |
| `src/eval4j-report/README.md` and user docs | Install, quick start (automatic and CLI), configuration reference, CI recipes, troubleshooting, bundle format reference link. |
| `llms.txt` (root and module) and the link/command checks | Add the new module and docs; the repo's doc tests must stay green. |
| `docs/VERSION_MATRIX.md`, `docs/PUBLISHING.md`, `Jenkinsfile` | New rows and stages (WI-001). |
| `src/eval4j/SPEC-dashboard-v2.md` | Marked as the product spec; links to this set. |
| Migration guide (`src/eval4j/docs/MIGRATION-REPORTING.md`) | v1 → v2: properties, files, `import-legacy`, deprecations and the 6.0 removal. |

## 5. Risks and mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| **Agent output is not deterministic**, so judge-cache reuse rarely hits | "Saved by reuse" is small; `BUILD` is no cheaper than `FULL` | Say so in the report (it shows the real mix); document recorded/replayed outputs and temperature 0; `FAST`/`SAMPLE` and carry-over are the real savings then. Verified in gate 4 with a deterministic stub and a live model. |
| `InvocationInterceptor` does not see scenarios built outside test arguments | Missing `scenarioId`, weaker coverage | Explicit `forScenario`/`forCase`; fallback case key; coverage says "no dataset" rather than guessing (03 §5.6). |
| Assertion instrumentation touches many public methods | Behaviour regressions | EXP-16/44; run the full existing assertion test suite unchanged; record in one helper. |
| Bundle size on large runs | Slow report, big files | Limits and truncation (FMT §5.3), streaming reads, detail limits (03 §8), budgets in CI (TST-24). |
| Interactive script grows unmanageable (no framework, no build) | Maintenance cost | Size cap (UI §5), pure chart functions with Node tests, one function per route, shared vectors. Re-evaluate a build step only if the cap is exceeded. |
| Jenkins CSP blocks the interactive edition | Users see an unstyled page | Static edition is first-class (D10, UI-01); the recipe publishes it. |
| Path-graph layout is hard in general | Ugly graphs for large workflows | Deterministic layered layout with orthogonal routing for typical Loom shapes; large graphs collapse loop bodies; fall back to the timeline with a note (UI-42). |
| Optional `eval4j` dependency in the listener confuses some build tools | Listener not found | Documented; micro-artifact fallback (Q-R1); the CLI always works. |
| Two modules drift apart | Silent incompatibility | The schema is the contract; contract tests on both sides; compatibility fixtures per released version (TST-06). |
| Scope: the dashboard is large | Slow delivery | Phases ship complete, valuable slices; each release passes the release bar; nothing half-built behind a flag. |
| Competing hosted tools add similar views | Differentiation shrinks | Lean on what is specific here: dimensions from the golden dataset, cost-aware evidence, family classification, Loom trajectories, free and private. |

## 6. Definition of done for a work item

1. Behaviour matches the cited requirement ids; each id is covered by a named test.
2. Tests per [07](07-TEST-STRATEGY.md) for its layer, including security and size tests where it renders or reads data.
3. Spotless clean; no new warnings; javadoc on public API.
4. Docs updated where users see the change (section 4).
5. Exercised on a real evaluation from this repository when it has a dogfood gate.
6. The pull request names the work item and requirement ids, and stays reviewable (about 600 changed lines of production code or less; larger items are split along the work item's bullets).

## 7. Open decisions to settle before or during phase 1

| # | Decision | Where |
|---|---|---|
| ~~Q-A~~ | Resolved: scoped `EvalChecks.named(...)` wrapper | 02 §14 |
| Q-B | Whether the cache key also folds in the agent descriptor | 02 §14 |
| Q-C | Default export directory for Gradle projects | 02 §14 |
| Q-R1 | Listener packaging if the optional dependency causes trouble | 03 §13 |
| Q-R2 | Who writes per-run `dimRates` into `index.jsonl` | 03 §13 |
| ~~Q-L1~~ | Resolved: inside `eval4j-report`, Loom optional | 06 §8 |
| ~~Q-L2~~ | Resolved: explicit `expectPath(...)` call | 06 §8 |
