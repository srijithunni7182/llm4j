# 07. Test strategy

Status: **Draft for review** · Applies to: `eval4j`, `eval4j-report` (including its Loom bridge) · Requirement ids use `TST-`; the module documents define their own testable requirements.

The release bar (product spec §12) says every view is proven on real evaluations and tested. This document says how.

## 1. Layers

| Layer | What it proves | Where | Runs in |
|---|---|---|---|
| **Contract** | The bundle format is what the schemas say, on both sides | both modules | `mvn test` |
| **Unit** | Pure logic: keys, rollups, goals, weights, baseline choice, comparison, noise, formatting, escaping | both | `mvn test` |
| **Golden file** | The analysed model and the generated pages for known bundles do not drift | `eval4j-report` | `mvn test` |
| **Property** | Order independence, symmetry, idempotence | `eval4j-report` | `mvn test` |
| **Integration** | eval4j really writes valid bundles from real JUnit runs; report reads them end to end | `eval4j`, `eval4j-report` | `mvn verify` |
| **Browser** | The interactive edition behaves in a real browser | `eval4j-report` | `mvn verify` (profile) |
| **Security and size** | Hostile text, no network, budgets | `eval4j-report` | `mvn test` |
| **Dogfood** | The report is useful on this repository's own evaluations | CI job | per phase |

## 2. Contract tests

| Req | Statement |
|---|---|
| TST-01 | The schemas in `spec/schema` are copied into both modules' test resources at build time (never edited there). A build step fails if the copies differ from `spec/schema`. |
| TST-02 | Every file in `spec/examples/**` validates against its schema with a draft 2020-12 validator, with format checks on. |
| TST-03 | **eval4j (writer side):** records produced by the test suites (a representative set of every kind, source and status, with hostile text and the size limits) are written by `RunWriter` and every line and `run.json` validates. |
| TST-04 | **eval4j-report (reader side):** the reader accepts every example, rejects schema violations in strict mode with file, line and field named, and tolerates truncated final lines, unknown keys and unknown enum values in lenient mode. |
| TST-05 | `CaseKey` (eval4j) and the report's key matcher reproduce the test vectors in FMT §3.1; a mismatch fails both builds. |
| TST-06 | **Compatibility matrix:** a bundle written by the *previous* eval4j release is read by the current report; the current eval4j's bundle is read by the previous report where `schemaVersion` allows (FMT-20). Fixture bundles from each released format version are kept under `src/eval4j-report/src/test/resources/compat/`. |
| TST-07 | A bundle with a newer major `schemaVersion` fails with the documented message, never a stack trace. |

## 3. eval4j tests

Defined in [02](02-EVAL4J-EXPORT.md) §13 (EXP-40 … EXP-49). Notable techniques:

- **Counting judge.** A fake `LLMClient` counts calls, returns canned verdicts with `TokenUsage`, and can be told to fail; used for EXP-30 (zero calls on a cached re-run), cost, tokens and the judge-error path.
- **Parallel runs.** The JUnit `EngineTestKit` already used by `EvalReportPipelineTest` runs fixture classes with `junit.jupiter.execution.parallel.enabled=true`; assertions on `scenarioId` attribution and unique `seq`.
- **Crash simulation.** Write, then truncate the file mid-line and read it back with the report's reader (this couples the two modules' tests only through the schema, via a copied bundle fixture, not a dependency).
- **No-op guarantee (EXP-47).** Run the existing eval4j test suite with export off and assert no export directory is created.

## 4. eval4j-report tests

### 4.1 Reader and analysis

- Golden expectations for the examples are in [`examples/minimal/expected.json`](examples/minimal/expected.json): candidate rollups, coverage states and comparison counts. The analysis tests assert equality against it (RPT-50/51).
- **Hand-checkable cases** the unit tests MUST cover, each with an explicit expected value:

| Case | Expected |
|---|---|
| rate over passed/failed/not-evaluated/error mix | `NOT_EVALUATED` and `ERROR` excluded from numerator and denominator |
| empty dimension | rate undefined, status `NO_RESULTS`, excluded from the weighted rate |
| weighted rate with weights 3, 2, 1, 0 | `Σ w·rate / Σ w`, a `0`-weight dimension ignored, all-zero weights give undefined |
| goal gap and status boundaries | gap `0` meets goal; gap `−9.99` below; gap `−10` well below; custom `warnGap` |
| family goal default | mean of its dimensions' goals when not configured |
| coverage states | one fixture per state (COVERED, PARTLY, NOT_EVALUATED, DECLARED_NO_SCENARIOS, UNDECLARED, NO_DATASET) |
| evidence mix and saved-by-reuse | per 03 §5.7, including "cost unknown" |
| baseline selection | table-driven over 15 run-store layouts: same branch present, absent (fall back to main), detached HEAD, only partial runs, equal `startedAt`, different project, explicit overrides |
| comparison classes | each of WORSE/BETTER/SAME/NEW/REMOVED/NOT_COMPARABLE, carried on one side, different datasets |
| noise band | within, exactly at, and just outside the band; per-judge `noiseBand` vs config default; assertion/measured never noisy |
| trends | gaps for missing dimensions, branch filtering, window size |
| independence heuristic | `gemini-2.5-pro` vs `gemini-flash`: same; `llama3.3:70b` vs `gemini…`: different |

### 4.2 Property tests (RPT-53/54, plus)

| Property | Statement |
|---|---|
| order independence | shuffling the lines of `evaluations.jsonl` changes no rollup, no comparison class, no ordering that is defined by a sort key |
| self-comparison | comparing a run with a copy of itself yields zero changed cases and all `SAME` |
| symmetry | `WORSE` and `BETTER` swap when baseline and candidate swap; `NEW` and `REMOVED` swap |
| idempotent merge | merging a bundle with itself (same `groupId`) does not double count (key collisions are a warning, later wins) |
| formatter stability | rounding is half-up and monotone; a value's displayed rounded form never crosses a status boundary without the one-decimal rule (UI §10) |

### 4.3 Golden HTML and model snapshots

The analysed model for each example is serialised (`--emit-model`) and compared to a golden JSON. The static edition and the server-rendered parts of the interactive edition are compared to golden HTML with a normaliser that fixes `generatedAt`. A snapshot update requires a reviewer's eye on the diff; the update command is `mvn -Dsnapshots.update=true test`.

### 4.4 Browser tests

A headless Chromium (Playwright for Java, or the equivalent, in a `browser` Maven profile; the repo environment already provides a Chromium) loads `index.html` generated from the examples and a **large synthetic bundle** (10,000 evaluations, 300 traces):

- every route renders and has no console errors;
- priorities cycle and the summary recomputes to the shared vectors;
- baseline picker, comparison filters, "show more", drawer open/close and focus return, keyboard-only navigation;
- the word diff of two answers marks the right words;
- **no network request** is made (request interception counts requests other than `file:`/`data:`);
- the page works at 360 px and 1280 px, in light and dark, with no horizontal page scroll;
- JavaScript disabled: the overview and notice render (UI-03).

### 4.5 Script unit tests

`dashboard.js` chart and formatter functions are pure (UI-31). A Node-based harness (no npm install in the repo build: a single checked-in runner using Node's built-in test runner, executed only in the `browser` profile) calls them against fixtures and compares golden SVG strings, and runs the shared priority vectors (RPT-52).

## 5. Security and size tests

| Req | Statement |
|---|---|
| TST-20 | **Hostile fixture:** a bundle whose every text field (and metric, family, dimension, project, branch names; config strings) contains `</script><script>alert(1)</script>`, `"><img src=x onerror=…>`, `<!--`, `]]>`, ` `, an RTL override, a NUL, a lone surrogate, and a 1 MB string. Both editions, `summary.md`, `junit.xml` and `report.csv` MUST render, parse (HTML, XML, CSV, Markdown renderers where available) and contain no executable markup. |
| TST-21 | A parser-level check: the generated HTML is parsed with a standards-compliant parser and the DOM contains no `script` other than the dashboard script and the model/case JSON blocks, no `on*` attributes, and no `javascript:` URL. |
| TST-22 | The static edition passes UI-04 (no script, no `style` attribute, one stylesheet). |
| TST-23 | No network access (UI-02) is verified in the browser tests and by scanning generated pages for `http://`, `https://`, `//` URLs, `url(`, `@import`, `src=`, `href=` (anchors `#…` and the single stylesheet link excepted). |
| TST-24 | **Size and speed:** reading and analysing 100,000 evaluations stays within RPT-40; rendering 10,000 within RPT-41/42; reported in CI and failing on regression beyond 25% of the recorded baseline. |
| TST-25 | **Memory:** reading is streamed; a test limits the heap (`-Xmx256m`) and reads a 1 GB-equivalent synthetic stream without failing. |
| TST-26 | CSV formula injection, Markdown table breaking, and XML illegal characters have dedicated tests (UI §9). |
| TST-27 | Redaction (eval4j): configured patterns and `caseText=false` remove the text they promise (EXP-48), verified by reading the written bundle. |

## 6. Accessibility and visual checks

| Req | Statement |
|---|---|
| TST-30 | An automated accessibility rule engine runs in the browser tests on the overview, a family page, a dimension page, the comparison page and the open drawer, in both themes; zero serious or critical findings. |
| TST-31 | A keyboard script visits every interactive element on those pages, verifies visible focus and that the drawer and menu trap and restore focus. |
| TST-32 | The chart palette is validated for colour-blind separation in both themes with the project's validator on every build that changes tokens. |
| TST-33 | Screenshot comparison on the example bundles (overview, compare, drawer, trajectory) in light and dark. Differences are reviewed by a person; the mockups in `spec/mockups` are the visual reference, not a pixel target. |

## 7. Dogfood gates (per phase)

A phase is **not complete** until its features run on evaluations this repository already has, with the output reviewed, per the release bar. No invented data.

| Phase (08) | Dogfood input |
|---|---|
| 1 Export | Run eval4j's own judged-condition tests and the `examples/getviral` workflow evaluation with export on; validate every bundle line. |
| 2 Compare | Run the same evaluation twice (once with a deliberately worsened prompt or stub) and compare; the headline counts and the changed cases are checked by hand. |
| 3 Report core + shell | Render the bundles from phase 1; check every number in the overview against a manual count for three dimensions. |
| 4 Cost-aware | A `BUILD` run after a `FULL` run on an unchanged agent shows zero judge calls, a REUSED mix and a plausible saved-by-reuse figure. |
| 5 Prompts and reasoning | The prompt optimizer and `PromptComparison` integration tests produce the Prompts view; the agent integration tests produce traces for the Reasoning view. |
| 6 Loom | The getviral workflow produces a trajectory with a graph, timeline, spend and log; a deliberately broken workflow shows the red node and the failing check. |
| 7 Static edition | The static edition is published from the Jenkins stage of this repository and opened under Jenkins' default policy. |

## 8. CI

| Req | Statement |
|---|---|
| TST-40 | `eval4j` and `eval4j-report` get their own Jenkins stages like the other modules (build, tests, quality), plus the schema-copy check (TST-01) and the contract tests. |
| TST-41 | The `browser` profile runs in a separate stage so a missing Chromium does not block unit tests; it is required before release. |
| TST-42 | Generated sample reports (from `spec/examples` and the dogfood runs) are archived as build artifacts for review. |
| TST-43 | A release is blocked by any failing contract, security, size or accessibility test (TST-01…TST-32). |
