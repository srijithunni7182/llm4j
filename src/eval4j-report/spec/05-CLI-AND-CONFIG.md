# 05. Command line, configuration, outputs and CI recipes

Status: **Draft for review** · Module: `eval4j-report` (`cli/`, `config/`) · Related: [03](03-REPORT-CORE.md), [04](04-DASHBOARD-UI.md)

## 1. Ways to run the report

| Way | How | Typical use |
|---|---|---|
| **Automatic** | Add `eval4j-report` as a dependency beside `eval4j`; run tests with `-Deval4j.export=true`. The `RunExportListener` (02 §11) renders at the end of the run. | local development, simple CI |
| **CLI** | `java -jar eval4j-report-<v>-cli.jar render …` | CI steps, re-rendering, merging modules, comparing arbitrary runs |
| **Library** | `EvalReport.load / compare / render` (03 §11) | custom tooling |

Defaults make the three equivalent: `render` with no arguments reads `target/eval4j` and writes `target/eval4j/report`.

## 2. Commands

```
eval4j-report render   [ROOT | BUNDLE…]  [options]       build the dashboard
eval4j-report compare  BASELINE CANDIDATE [options]       build a comparison page and Markdown summary
eval4j-report list     [ROOT]                              list retained runs
eval4j-report index    [ROOT]                              rebuild index.jsonl from the bundles
eval4j-report validate BUNDLE…  [--strict]                 check bundles against the schemas
eval4j-report merge    BUNDLE… --out BUNDLE                 merge bundles of one build into one
eval4j-report import-legacy FILE --out ROOT                 turn a v1 eval4j-report.json into a bundle
eval4j-report prune    [ROOT] --keep N                      delete the oldest bundles
eval4j-report config   init | check [FILE]                  write an example config / validate one
eval4j-report --version | --help
```

A run is addressed by its `runId`, by `latest`, by `previous` (the run before the candidate on the same branch), or by a path to a bundle directory.

### 2.1 `render`

| Option | Default | Meaning |
|---|---|---|
| `ROOT` / `BUNDLE…` | `target/eval4j` | An export root (contains `runs/`) or one or more bundle directories (merged into one run when they share a `groupId`, FMT §7). |
| `--run ID` | `latest` | The candidate run. |
| `--baseline X` | `sameBranch` | `sameBranch`, `main`, `lastFull`, `none`, or a `runId` (03 §6). |
| `--out DIR` | `ROOT/report` | Output directory. |
| `--editions LIST` | `interactive,static` | Any of `interactive`, `static`. |
| `--formats LIST` | `md,junit,csv` | Extra outputs (§4). |
| `--config FILE` | `eval4j-report.yaml` if present | Configuration (§3). |
| `--full-detail` | off | Lift the embedded-detail limits (03 §8). |
| `--strict` | off | Fail on any schema violation instead of warning (RPT-11). |
| `--stale-after HOURS` | `6` | When a `RUNNING` bundle counts as `PARTIAL` (FMT-07). |
| `--emit-model` | off | Also write `model.json`, the analysed model, for debugging and tooling. |
| `--quiet` / `--verbose` | | Output level. |

### 2.2 `compare`

`compare BASELINE CANDIDATE --out DIR [--formats md,html]` writes `compare/<baseline>..<candidate>.html` and `compare.md` without building the whole dashboard. It performs no LLM call and needs only the two bundles.

### 2.3 Exit codes

| Code | Meaning |
|---|---|
| `0` | Success (including warnings). |
| `1` | Unexpected error. |
| `2` | Usage error. |
| `3` | Unreadable or invalid input (always in `--strict`; otherwise only for a missing `run.json` or an unsupported `schemaVersion`). |
| `4` | No runs found under the root. |
| `5` | Invalid configuration. |

| Req | Statement |
|---|---|
| CLI-08 | **No exit code ever depends on results.** A failing metric, a regression, a status "Well below goal" or a worse comparison never makes the CLI exit non-zero. Build gating is `@EvalBaseline`'s job (D6). |
| CLI-09 | Output is deterministic for the same inputs, except `generatedAt`, which can be fixed with `--now ISO8601` for reproducible builds. |
| CLI-10 | The CLI never makes a network request and never writes outside `--out` (and `--emit-model`, `index`, `prune` targets that the user named). |

## 3. Configuration file

`eval4j-report.yaml` (or `.json`). Everything is optional. Precedence, low to high: shipped defaults, config file, system properties (`eval4j.report.*`), CLI options. The config only affects **presentation and weighting** (RPT-20).

```yaml
project:
  name: Support agent                 # shown in the navigation; default: run.json project.name
  logo: assets/acme.svg               # optional; embedded as a data URI; the llm4j mark and credit always remain
branding:
  font: null                          # optional path to a font file to embed (adds size)

defaults:
  goal: 90                            # pass-rate goal for any dimension/family without its own
  priority: IMPORTANT                 # CRITICAL | IMPORTANT | NICE_TO_HAVE | NONE
  warnGap: 10                         # gap at or below −warnGap points is "well below goal"

dimensions:                           # ids come from the dataset / metrics; unknown ids are fine
  correctness: { name: Correctness, blurb: "Right answer, job finished.",   goal: 90, priority: CRITICAL, order: 10 }
  grounding:   { name: Grounding,   blurb: "Claims backed by sources.",     goal: 90, priority: CRITICAL, order: 20 }
  efficiency:  { name: Efficiency,  blurb: "Latency, tokens, steps.",       goal: 80, priority: NICE_TO_HAVE }
  compliance:  { name: Compliance,  blurb: "GDPR and GST rules.",           goal: 95, priority: IMPORTANT }

families:
  agents:    { name: Agents,            goal: 88 }
  workflows: { name: "Workflows (Loom)", goal: 95 }

presets:                              # optional named priority sets shown as a picker (interactive)
  customer-facing: { correctness: CRITICAL, grounding: CRITICAL, safety: CRITICAL, efficiency: NICE_TO_HAVE }
  internal-tool:   { correctness: IMPORTANT, efficiency: IMPORTANT, safety: NICE_TO_HAVE }

compare:
  baseline: sameBranch                # sameBranch | main | lastFull | none | <runId>
  defaultBranch: main                 # else master
  noiseBand: 0.07                     # default judge-noise band when the run reports none
  pooledNoise: false                  # widen the band by pooled sample deviation when available
  trendRuns: 40

rates:
  carried: include                    # include | exclude: whether CARRIED results count in rates

staleness:
  carriedDays: 7                      # carried evidence older than this is flagged "stale"

detail:                               # interactive-edition size control (03 §8)
  maxCases: 3000
  maxTextChars: 4000
  maxTraces: 300
  maxBytes: 26214400

static:
  maxFailing: 40                      # failing evaluations listed per family in the static edition

retention:
  runs: 40                            # used by `prune` and the trend window
```

| Req | Statement |
|---|---|
| CLI-20 | `config check` MUST report unknown keys (warning), wrong types and out-of-range values (error) with the key path and line. |
| CLI-21 | `config init` writes the example above, commented. |
| CLI-22 | A goal is an integer or decimal in `[0, 100]`; `warnGap` in `[0, 100]`; `noiseBand` in `[0, 1]`; unknown priority names are errors. |
| CLI-23 | The configuration used is embedded in the report (RPT-22) and shown under Data notes. |

System-property equivalents for automatic mode: `eval4j.report.config`, `eval4j.report.out`, `eval4j.report.editions`, `eval4j.report.baseline`, `eval4j.report.formats`.

## 4. Output layout

```
<out>/                                 default: <root>/report
  index.html                           interactive edition (one self-contained file)
  static/
    index.html                         static edition
    eval4j-static.css                  its stylesheet
  compare/
    <baseline>..<candidate>.html       comparison page (interactive edition)
  summary.md                           short digest of the run, for job summaries
  compare.md                           pull-request-sized comparison, from the selected baseline
  junit.xml                            one test case per evaluation (CI test reporters)
  report.csv                           every evaluation, for spreadsheets
  model.json                           (--emit-model) the analysed model
```

### 4.1 `summary.md` and `compare.md`

- `summary.md`: title, pass rate and the "informs, does not decide" line, family table (pass rate, goal, gap, status text with icon), dimension table, evidence line (fresh / reused / carried, spend vs budget), coverage warnings (declared but unevaluated), and the failing evaluations (top 25, collapsed in `<details>`).
- `compare.md`: the headline, the environment-change list, movement by family and dimension (table), the largest 10 changed cases (names and scores, no text), the within-noise count, and a note naming the baseline rule. **It MUST stay under 60,000 characters** (GitHub's comment limit is 65,536); it truncates the case list and says so.
- Neither carries a verdict. Markdown cells escape `\ | < > \`` and newlines (UI §9).

### 4.2 `junit.xml`

One `testsuite` per suite, one `testcase` per **counted** evaluation named `<test> [<metric>]`, a `failure` for a failed evaluation (message: `<metric> scored 0.31, below threshold 0.70`; body: the reason). `NOT_EVALUATED` becomes `skipped`; `ERROR` becomes `error`. Illegal XML characters stripped.

## 5. CI recipes

### 5.1 The baseline must persist

The default baseline is the previous run on the same branch (03 §6), and the fallback is the latest run on `main`. A CI job starts clean, so **the `runs/` directory must be restored before the tests and saved after**. Every recipe below does that. Without it, comparison shows "no baseline yet".

### 5.2 Maven (automatic mode)

```xml
<dependency>
  <groupId>io.github.srijithunni7182</groupId><artifactId>eval4j-report</artifactId>
  <version>${eval4j.version}</version><scope>test</scope>
</dependency>
```

```
mvn test -Deval4j.export=true
# bundle:    target/eval4j/runs/<runId>/
# dashboard: target/eval4j/report/index.html   and   .../static/index.html
```

### 5.3 Jenkins

```groovy
stage('Evals') {
  steps {
    // restore previous runs (same branch) and main's runs, from archived artifacts or a shared directory
    sh 'mvn -B test -Deval4j.export=true -Deval4j.profile=BUILD'
  }
  post {
    always {
      junit 'target/eval4j/report/junit.xml'
      // the static edition is the one Jenkins' default Content-Security-Policy can show
      publishHTML(target: [reportDir: 'target/eval4j/report/static', reportFiles: 'index.html',
                           reportName: 'eval4j', keepAll: true, allowMissing: true])
      archiveArtifacts artifacts: 'target/eval4j/runs/**, target/eval4j/report/**', allowEmptyArchive: true
    }
  }
}
```

Jenkins serves HTML Publisher content under a restrictive policy, so use the **static edition** there; the interactive edition is available from the archived artifacts. (Relaxing `hudson.model.DirectoryBrowserSupport.CSP` also works but is not recommended.)

### 5.3.1 GitHub Actions

```yaml
- uses: actions/cache@v4                      # restore this branch's runs, then main's
  with: { path: target/eval4j/runs, key: eval4j-${{ github.ref_name }}-${{ github.run_id }},
          restore-keys: |
            eval4j-${{ github.ref_name }}-
            eval4j-main- }
- run: mvn -B test -Deval4j.export=true
- run: cat target/eval4j/report/summary.md >> "$GITHUB_STEP_SUMMARY"
- uses: actions/upload-artifact@v4
  with: { name: eval4j-report, path: target/eval4j/report }
# optional pull-request comment from compare.md (post it with your preferred action)
```

### 5.4 Gradle

Same properties through `systemProperty` on the `test` task; the output directory defaults to `build/eval4j` when a Gradle layout is detected.

### 5.5 Several modules

Each module writes its own bundle; set a shared `-Deval4j.run.group=$BUILD_ID`. Then `eval4j-report render target/eval4j --run <group>` (or `render module-a/target/eval4j/runs/* module-b/…`) builds one dashboard.

## 6. Requirements for tests

| Req | Statement |
|---|---|
| CLI-30 | Each command has a golden-output test against the example bundles (stdout, exit code, files written). |
| CLI-31 | `render` on `examples/minimal` produces both editions and the three extra formats, all passing UI-02/UI-04/UI-20/UI-50. |
| CLI-32 | `compare previous candidate` on the examples produces the documented counts (07 §4). |
| CLI-33 | Exit codes match §2.3 for each failure scenario (missing root, bad schema in `--strict`, bad config, no runs). |
| CLI-34 | `compare.md` stays under 60,000 characters for a 50,000-case comparison. |
