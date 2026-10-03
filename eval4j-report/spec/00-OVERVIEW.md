# eval4j-report: implementation specification

Status: **Draft for review** · Applies to: `eval4j` (changes), `eval4j-report` (new; includes the Loom bridge) · Product spec: [`eval4j/SPEC-dashboard-v2.md`](../../eval4j/SPEC-dashboard-v2.md)

This is the engineering specification for the dashboard described in the product spec. The product spec says **what** the user gets; this set says **how** it is built, where each piece lives, and how each piece is tested. Where the two disagree, the product spec wins on behaviour and this set wins on structure; raise the conflict before building.

## 1. The idea in one paragraph

`eval4j` stays small. It runs evaluations and **exports** what happened, as facts, into a documented **run bundle**. A separate add-on, `eval4j-report`, **reads** bundles and builds the dashboard. The bundle format is the only contract between them. Because the contract is a file format and not a Java API, the report can evolve without touching eval4j, can run in a different process (a CLI in CI), and can read bundles written by anything else that follows the format.

```
 ┌────────────── test JVM ──────────────┐        ┌──────────────── anywhere ────────────────┐
 │ JUnit tests                          │        │ eval4j-report                             │
 │   + eval4j (assertions, judges)      │        │   reader ─► analysis ─► renderers         │
 │   + EvalReportExtension / Recorder   │        │                                           │
 │         │                            │        │  index.html        (interactive edition)  │
 │         ▼                            │  run   │  static/index.html (+ eval4j-static.css)  │
 │   export: RunWriter ─────────────────┼─bundle─►  compare/*.html    (run comparison)       │
 │                                      │        │  summary.md · junit.xml · report.csv      │
 │   optional: RunExportListener (SPI) ─┼─ calls ─► (auto-render at end of the test run)     │
 └──────────────────────────────────────┘        └───────────────────────────────────────────┘
                                                    also: CLI  `eval4j-report render|compare|…`
```

## 2. Architecture decisions

| # | Decision | Why |
|---|---|---|
| D1 | Two artifacts: **`eval4j`** captures and exports; **`eval4j-report`** reads and renders. The contract is the **run bundle** ([01](01-RUN-FORMAT.md)). | Keeps eval4j lean; lets the report ship and change on its own cadence. |
| D2 | `eval4j-report` has **no required dependency on `eval4j` or `ai-agent4j`**. Its only required runtime dependencies are Jackson (and Jackson YAML for config). `eval4j` is an **optional** dependency used by exactly one class, the `RunExportListener` implementation in the `spi` package (D5); the CLI and the library never load it. The schema, not shared Java classes, is the contract: each side has its own model and both are tested against the same schemas and example bundles. | A CI step can run the report from a bare JAR. No cycles (`eval4j` does not depend on `eval4j-report`). Other tools can write bundles. |
| D3 | eval4j exports **facts only**. Goals, priorities, display names, status thresholds, branding and retention live in **report configuration** ([05](05-CLI-AND-CONFIG.md)). | Interpretation can change without re-running evaluations; no result is ever edited by presentation settings. |
| D4 | A bundle is a **directory**: `run.json` (header), plus append-only **JSON Lines** files for evaluations, scenarios, tests, traces and optimizations. | Crash-safe (everything before a crash survives), streamable (large runs without large memory), parallel-test safe, easy to grep. |
| D5 | eval4j exposes a tiny SPI, **`RunExportListener`**, discovered with `ServiceLoader`. `eval4j-report` provides an implementation, so adding the dependency makes `mvn test` also produce the dashboard. Without it, eval4j prints how to run the CLI. | One-step experience without eval4j knowing the report exists. |
| D6 | The regression gate (`@EvalBaseline`) **stays in eval4j** with its own baseline file. The dashboard never fails a build. | CI gating must work with no dashboard installed. |
| D7 | The default comparison baseline is **the previous run on the same branch**. Fallbacks: the default branch's latest run, then none. | Decided with the product owner. Detailed in [03](03-REPORT-CORE.md) §6. |
| D8 | The v1 report writers inside eval4j are **deprecated** and removed in the next major. `eval4j-report` can import a v1 `eval4j-report.json`. | One report implementation, a clear migration path. |
| D9 | Format versioning: integer `schemaVersion`; readers accept the current and the previous major; unknown fields are ignored. | Old reports keep rendering; new fields are additive. |
| D10 | The interactive edition embeds data as inert JSON and renders with a small hand-written script; the **static edition** has no script and no inline style. No Node/npm build in the repository. | Matches the repo's Maven-only toolchain; both editions share one analysed model. |
| D11 | New Java packages: `io.github.llm4j.eval.export` (in eval4j) and `io.github.llm4j.evalreport` (in eval4j-report). The existing `io.github.llm4j.eval.report` package in eval4j is not reused for new code. | Avoids a package split across two JARs. |
| D12 | Loom data reaches the report through a neutral **workflow trace** in the bundle. The Loom bridge lives in a package of `eval4j-report` (`io.github.llm4j.evalreport.loom`) with `ai-agent4j-loom` as an **optional** dependency. `eval4j` and the rest of `eval4j-report` never reference Loom (decided: no separate `eval4j-loom` module). | Fewer artifacts; Loom stays optional; the bundle and the CLI jar stay Loom-free. |

## 3. Responsibilities

| Concern | `eval4j` | `eval4j-report` | `eval4j-report` Loom bridge |
|---|---|---|---|
| Run assertions and judges | ✔ | | |
| Record each evaluation with its case, scenario, metric, evidence and cost | ✔ | | |
| Detect branch, commit, CI, agent and judge descriptors | ✔ | | |
| Write the run bundle | ✔ | | |
| Regression gate (`@EvalBaseline`) | ✔ | | |
| Profiles, judge budget, change-aware reuse, carry-over | ✔ | | |
| Read bundles (any supported version), merge bundles of one build | | ✔ | |
| Roll up: families, facets, dimensions, goals, priorities, coverage, evidence | | ✔ | |
| Compare runs, judge-noise band, trends, baseline selection | | ✔ | |
| Render interactive edition, static edition, summaries, JUnit XML, CSV | | ✔ | |
| CLI and configuration | | ✔ | |
| Turn Loom trace events into a workflow trace | | | ✔ |
| Trajectory assertions over a workflow trace | ✔ (model + assertions) | | |

## 4. Document map

| Doc | Contents | Primary readers |
|---|---|---|
| [01-RUN-FORMAT.md](01-RUN-FORMAT.md) | The contract: bundle layout, every file and field, identifiers, enums, versioning, limits, security, legacy mapping. With [`schema/`](schema/) and [`examples/`](examples/). | both sides |
| [02-EVAL4J-EXPORT.md](02-EVAL4J-EXPORT.md) | Changes inside eval4j: recorder, scenarios, binding, descriptors, evidence and cost, profiles, writer, SPI, deprecations. | eval4j implementers |
| [03-REPORT-CORE.md](03-REPORT-CORE.md) | The `eval4j-report` module: structure, reader, analysis algorithms (rollups, goals, coverage, evidence, comparison, noise, trends), configuration model. | report implementers |
| [04-DASHBOARD-UI.md](04-DASHBOARD-UI.md) | The two editions: views, components, charts, data embedding, interaction, accessibility, branding, static edition. Maps each view to the mockups. | UI implementers |
| [05-CLI-AND-CONFIG.md](05-CLI-AND-CONFIG.md) | CLI commands, configuration file, output layout, CI recipes (Maven, Gradle, Jenkins, GitHub Actions). | users, CI |
| [06-LOOM-INTEGRATION.md](06-LOOM-INTEGRATION.md) | Workflow trace, the bridge, trajectory assertions. | Loom, eval4j |
| [07-TEST-STRATEGY.md](07-TEST-STRATEGY.md) | Test layers, contract tests, fixtures, size and performance budgets, security tests, the dogfood gates. | everyone |
| [08-DELIVERY-PLAN.md](08-DELIVERY-PLAN.md) | Work items in order, with scope, dependencies, acceptance and the module/pom/CI/docs changes. | planning |
| [`mockups/`](mockups/README.md) | Visual reference (sample data). | UI implementers |

## 5. Conventions

- Java 17, Jackson aligned with the repo (`2.21.x`), MIT licence, group id `io.github.srijithunni7182`, version aligned with the other modules (`5.x`).
- Formatting with the repo's Spotless setup; tests with JUnit and AssertJ like the rest of the repo.
- Requirement ids (`FMT-`, `EXP-`, `RPT-`, `UI-`, `CLI-`, `LOOM-`, `TST-`) are stable. A pull request names the ids it satisfies; a test names the id it proves.
- **MUST / SHOULD / MAY** are used as in RFC 2119.
- Examples in these documents are illustrative; the files in [`schema/`](schema/) and [`examples/`](examples/) are normative and are validated in CI ([07](07-TEST-STRATEGY.md)).

## 6. Non-goals

A hosted service, accounts, a database, live updating, production tracing, alerting, team comments, a dataset editor, and a Node/npm build. These are covered in the product spec's release bar (§12).

## 7. Glossary

| Term | Meaning |
|---|---|
| **Run** | One execution of an eval suite (one build, one profile). |
| **Run bundle** | The directory eval4j writes for a run: `run.json` plus JSON Lines files. |
| **Evaluation** | One judged, asserted, measured or compared result for one case and one metric. |
| **Case** | One scenario or test that evaluations belong to. Has a stable `caseId`. |
| **Key** | The stable id of an evaluation across runs (case, metric, occurrence). Run comparison matches on it. |
| **Family / facet** | What is under test (Prompts, Agents, Conversations, Retrieval, Workflows) and its sub-area (Reasoning, Trajectory, …). |
| **Dimension** | What quality is measured (Correctness, Grounding, Efficiency, …). |
| **Goal / priority** | Report-side interpretation: the pass rate aimed for, and how much a stakeholder cares. Never stored in a bundle. |
| **Evidence source** | `FRESH` (evaluated this run), `REUSED` (verdict reused, output unchanged), `CARRIED` (from an earlier run). |
| **Edition** | Interactive (one self-contained HTML file) or static (HTML plus one CSS file, no script). |
| **Profile** | `FAST`, `BUILD`, `SAMPLE` or `FULL`: how much judging a run does. |
