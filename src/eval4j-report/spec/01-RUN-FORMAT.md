# 01. Run bundle format (the contract)

Status: **Draft for review** · Version of the format: **1** · Normative files: [`schema/*.schema.json`](schema/) and [`examples/`](examples/minimal/)

A **run bundle** is what `eval4j` writes for one run and what `eval4j-report` reads. It contains facts only. If this document and a schema disagree, the schema wins; fix the document.

## 1. Layout

```
<exportRoot>/                         default: target/eval4j
  runs/
    <runId>/                          one directory per run (a "bundle")
      run.json                        header: who, where, with what; summary          (required)
      evaluations.jsonl               one line per evaluation, append-only            (required, may be empty)
      scenarios.jsonl                 golden scenarios the dataset declares           (optional)
      tests.jsonl                     JUnit outcomes                                  (optional)
      traces.jsonl                    agent step traces and workflow traces           (optional)
      optimizations.jsonl             prompt optimizer runs                           (optional)
  index.jsonl                         one line per finished run, for fast listing     (optional, rebuildable)
```

Any `.jsonl` file MAY instead be `.jsonl.gz`; readers MUST accept both. Files are UTF-8 with `\n` line ends. A blank line is ignored.

| Req | Statement |
|---|---|
| FMT-01 | A bundle MUST contain `run.json` and `evaluations.jsonl`; all other files are optional. |
| FMT-02 | A reader MUST ignore unknown files in a bundle and unknown keys in any object. |
| FMT-03 | A writer MUST NOT rewrite or reorder lines already appended to a `.jsonl` file. |
| FMT-04 | `<runId>` MUST match `^[A-Za-z0-9._-]{6,64}$` and be unique within `runs/`. It SHOULD sort by start time (a ULID, or a CI timestamp prefix). |

## 2. Lifecycle and crash safety

1. **Start.** The writer creates the directory and writes `run.json` with `"status": "RUNNING"`, `startedAt`, `source`, `profile` and `env` (whatever is known).
2. **During.** Every evaluation, test outcome, trace and so on is **appended** to its `.jsonl` file as it happens, each as a complete line, flushed. `run.json` is not touched.
3. **Finish.** The writer rewrites `run.json` **atomically** (write a temporary file, then move it over the old one) with `endedAt`, `durationMs`, final `env` descriptors and judge `stats`, `summary`, `metrics`, and `status` = `COMPLETE` (or `PARTIAL` if judging stopped early; see [02](02-EVAL4J-EXPORT.md) §8).

| Req | Statement |
|---|---|
| FMT-05 | If the JVM dies, the bundle MUST remain readable: `run.json` says `RUNNING`, and the `.jsonl` files hold every complete line written so far. |
| FMT-06 | A reader MUST tolerate a truncated final line (no trailing newline, invalid JSON): skip it and report a warning. |
| FMT-07 | A reader MUST treat a `RUNNING` bundle whose newest file is older than a configurable limit (default 6 hours) as `PARTIAL`, and say so. |
| FMT-08 | Appends from several threads MUST NOT interleave within a line. Writers use one lock or one writer thread per file. |

## 3. Identifiers

### 3.1 `caseKey`, `caseId`, `key`

Run comparison needs ids that survive across runs, JVMs and machines.

```
caseKey  = scenario id                         if the case came from an EvalScenario with an id or name
         = "<suite>#<method>[<displayName>]"   otherwise   (suite = fully qualified test class)
caseId   = "c_" + hex16( sha256( "case"    + 0x00 + caseKey ) )
key      = "k_" + hex16( sha256( caseKey   + 0x00 + metricId + 0x00 + occurrence ) )
hex16(x) = first 16 lowercase hex characters of x
occurrence = 0, 1, 2 … for the n-th evaluation of the same metric in the same case within one run
```

`sha256` is over the UTF-8 bytes; `0x00` is a single zero byte. The metric id is the slug in `metrics[].id`.

Test vectors (writers and readers MUST reproduce these):

| caseKey | metric | occurrence | caseId | key |
|---|---|---|---|---|
| `refund-inside-window` | `answer-correctness` | 0 | `c_36002d9a396666c3` | `k_e80a100a2103750c` |
| `refund-inside-window` | `faithfulness` | 0 | `c_36002d9a396666c3` | `k_aba47f539957399a` |
| `shipping-canada` | `latency-p95` | 0 | `c_548260ae655b164e` | `k_d3e3b60569e2483d` |
| `com.acme.SupportBotEvalTest#cancel[2]` | `tool-order` | 1 | `c_6b90f524197dda48` | `k_51bd8cc9dbefb14a` |

| Req | Statement |
|---|---|
| FMT-09 | `caseId` and `key` MUST be computed exactly as above. |
| FMT-10 | When a scenario has no id, eval4j MUST use its `name`; if that is also absent, the first 12 hex characters of the SHA-256 of its `input`. Scenario ids SHOULD be unique within a dataset; a duplicate is reported as a warning and disambiguated with `~2`, `~3`. |
| FMT-11 | Display names of parameterized tests that embed an invocation index (`[2]`) are fragile. When a scenario is available it MUST be preferred as `caseKey`. |

### 3.2 Other ids

- `runId`: see FMT-04. `groupId`: any string shared by the bundles of one build (§7).
- `seq` (evaluations): integer, unique and increasing within a bundle. Merged bundles keep their own `seq`; order across bundles is by `timestamp`.
- `traceId`: unique within a bundle; evaluations refer to traces by it.
- `metric`, `family`, `facet`, `dimension`: slugs, `^[a-z][a-z0-9_-]{0,63}$`.

## 4. `run.json`

See [`schema/run.schema.json`](schema/run.schema.json). Summary of the sections:

| Section | Content |
|---|---|
| `schemaVersion`, `format` | Always `1` and `"eval4j-run"`. |
| `runId`, `groupId`, `runNumber` | Identity. |
| `status`, `startedAt`, `endedAt`, `durationMs` | Lifecycle (§2). |
| `project` | Display name of the project / module. |
| `source` | `branch`, `commit`, `commitTime`, `dirty`, `pullRequest`, `ci{provider, buildUrl, buildNumber}`. Used for **baseline selection**. |
| `profile` | `name` (`FAST`/`BUILD`/`SAMPLE`/`FULL`), `judgeBudgetUsd`, `sampleRate`, `seed`, `carryOver`. |
| `env.agents[]` | The agent(s) under test: `id`, `provider`, `model`, `promptId`, `promptVersion`, `tools[]`. |
| `env.judges[]` | Judge descriptor and run **stats** (`calls`, `cacheHits`, latency, tokens, cost, `failures`, `retries`, `selfConsistency`, `noiseBand`). |
| `env.datasets[]` | `id`, `name`, `path`, `revision`, `hash`, `scenarioCount`. |
| `env.*` | `eval4jVersion`, `javaVersion`, `os`, `configHash`. |
| `metrics[]` | Every metric that appeared: `id`, `name`, `kind`, `family`, `facet`, `dimension`, `threshold`, `unit`, `budget`, `judgeId`. Evaluations refer to a metric by `id`. |
| `summary` | Counts: `evaluations`, `passed`, `failed`, `notEvaluated`, `errors`, `bySource`, `costUsd`, `tests{…}`. Convenience only; a reader MUST be able to recompute it. |
| `extensions` | Free space. |

| Req | Statement |
|---|---|
| FMT-12 | A reader MUST NOT trust `summary`; it recomputes counts from `evaluations.jsonl` and MAY warn if they differ. |
| FMT-13 | `env` is the "what changed between these runs" table. A writer SHOULD fill every field it can determine and MUST NOT guess. Unknown is `null` or absent. |
| FMT-14 | `configHash` MUST change when a setting that affects results changes (profile, thresholds, sample rate, judge samples). The report warns when comparing runs with different hashes. |
| FMT-15 | Goals, priorities, display names and branding MUST NOT appear in a bundle. |

## 5. `evaluations.jsonl`

One line per evaluation. See [`schema/evaluation.schema.json`](schema/evaluation.schema.json). Required: `seq`, `key`, `metric`, `kind`, `status`; for `status = EVALUATED`, also `passed`.

### 5.1 Kinds and how a result is read

| `kind` | `score` | Pass rule (set by the writer in `passed`) | `display` | Extra |
|---|---|---|---|---|
| `JUDGE` | 0..1 from an LLM judge | `score >= threshold` | optional | `samples[]`, `judgeId`, tokens, cost |
| `PAIRWISE` | `1` B wins, `0.5` tie, `0` A wins | B does not lose | `"B wins"`, `"Tie"`, `"A wins"` | `judgeId` |
| `ASSERTION` | `1` or `0` | the assertion held | `"Passed"` / `"Failed"` | none; free to run |
| `MEASURED` | normalised 0..1, `0.5` = exactly on budget | `value <= budget` | e.g. `"3.2 s"` | `measured{value, unit, budget}` |

### 5.2 Status and evidence

| Field | Values | Meaning |
|---|---|---|
| `status` | `EVALUATED` | A result exists. |
| | `NOT_EVALUATED` | The case and metric were expected but not evaluated (profile, budget, sampling). `passed` and `score` are `null`. It MUST NOT count as passed or failed. |
| | `ERROR` | The evaluation itself failed (for example the judge errored); `reason` says why. Counts toward neither pass nor fail. |
| `source` | `FRESH` | Evaluated in this run (default). |
| | `REUSED` | Evaluated in this run, but the verdict came from the judge cache because input, output, criteria, rubric and judge were unchanged. Valid and free. |
| | `CARRIED` | Not evaluated in this run; this is the latest known result from `evaluatedInRun` at `evaluatedAt`. Requires `evaluatedInRun`. |

| Req | Statement |
|---|---|
| FMT-16 | A `CARRIED` line MUST name the run it came from. A reader shows its age and never presents it as fresh evidence. |
| FMT-17 | `NOT_EVALUATED` lines SHOULD be written for every (case, metric) the run expected but skipped, so coverage can be computed. They carry a `reason`. |
| FMT-18 | `score` is within `[0, 1]`. A writer clamps. A reader rejects out-of-range scores in strict mode and clamps with a warning in lenient mode. |

### 5.3 Case details and limits

`input`, `actualOutput`, `expectedOutput`, `retrievalContext[]`, `reason` feed the drill-down. Limits (a writer MUST enforce, a reader MUST tolerate more):

| Field | Limit | Rule when exceeded |
|---|---|---|
| each text field | 20,000 characters | cut and append `… [truncated N chars]` |
| `retrievalContext` | 50 chunks | first 50 kept |
| `samples` | 32 | first 32 kept |
| a bundle's `evaluations.jsonl` | 200 MB uncompressed (configurable) | writer stops adding case text, keeps scores, and sets a warning in `run.json.extensions.warnings` |

## 6. Other files

| File | Schema | Notes |
|---|---|---|
| `scenarios.jsonl` | [`scenario`](schema/scenario.schema.json) | One line per golden scenario: `id`, `caseId`, `datasetId`, `dimensions[]`, `families[]`, `tags[]`. Written once at dataset load. This is what lets the report show a dimension that was **declared but not evaluated**. |
| `tests.jsonl` | [`test`](schema/test.schema.json) | JUnit outcome per test: `PASSED`, `FAILED`, `ABORTED`, `SKIPPED`, duration, message. Includes tests that failed without recording any evaluation. |
| `traces.jsonl` | [`trace`](schema/trace.schema.json) | `AGENT_STEPS`: steps with thought, action, input, observation, outcome (the seven `AgentResult.StepOutcome` values). `WORKFLOW`: graph, expected and actual path, events (the sixteen Loom trace event types), spend per agent, budget, rewinds. See [06](06-LOOM-INTEGRATION.md). |
| `optimizations.jsonl` | [`optimization`](schema/optimization.schema.json) | One line per prompt optimizer run: rounds, goal, stop reason, budget, overfitting check, diff of the accepted prompt. |
| `index.jsonl` | (informal) | One line per finished run: `runId`, `groupId`, `startedAt`, `status`, `branch`, `commit`, `profile`, counts. Written atomically when a run finishes. The report can rebuild it by scanning `runs/`. |

Trace size limits: 500 steps per agent trace; 20,000 events per workflow trace; each text 5,000 characters (2,000 for events). Exceeding lines are cut and flagged.

## 7. Several bundles, one build

A Maven reactor or a parallel CI job can produce several bundles for one build. Writers set the same `groupId` (from `eval4j.run.group`, or derived from the CI build id). A reader that is given `groupId` or several directories **merges** them into one logical run:

- `startedAt` is the earliest, `endedAt` the latest, `status` is `PARTIAL` if any part is not `COMPLETE`.
- `env` lists are unioned by `id`; `metrics` unioned by `id` (a conflicting definition is a warning, first wins).
- Evaluations are concatenated; `key` collisions across bundles are a warning and the later `timestamp` wins.
- `source` must agree; a mismatch is a warning.

## 8. Versioning and compatibility

| Req | Statement |
|---|---|
| FMT-19 | `schemaVersion` is an integer. A change that removes or reinterprets a field, or tightens a rule, increments it. Adding optional fields, enum values to open sets, or new files does not. |
| FMT-20 | A reader MUST accept the current version and the previous one, and MUST reject a newer major with a clear message ("this bundle needs eval4j-report N or later"). |
| FMT-21 | Enums that are closed sets in the schema (`kind`, `status`, `source`, step `outcome`, workflow event `type`, node `kind`) MAY gain values in a minor revision. A reader MUST render an unknown value as-is rather than failing. |
| FMT-22 | The schemas are the source of truth. They are published with the module and referenced by `$id`. |

## 9. Security and privacy

| Req | Statement |
|---|---|
| FMT-23 | Bundle text is **untrusted** (model output, user input). Readers never execute it and renderers escape it for the context it lands in ([04](04-DASHBOARD-UI.md) §9). |
| FMT-24 | Writers MUST NOT write secrets. eval4j applies a configurable redaction list (regular expressions) to text fields before writing, and never records provider API keys or request headers. |
| FMT-25 | Writers SHOULD offer a switch to omit case text entirely (scores and reasons only) for sensitive datasets. |
| FMT-26 | A reader MUST bound memory: stream `.jsonl` line by line and enforce the limits in §5.3 and §6 when loading. |

## 10. Mapping from the v1 report

`eval4j-report import-legacy <eval4j-report.json>` builds a bundle from a v1 file:

| v1 (`RunInfo` / `EvalRecord`) | Bundle |
|---|---|
| `runId`, `startedAt`, `endedAt`, `gitSha` | `run.json` `runId`, `startedAt`, `endedAt`, `source.commit` |
| `records[].suite`, `testName` | `testId` and `caseKey` = `<suite>#<testName>` |
| `records[].metric` | `metric` slug of the name; `metrics[].name` keeps the original |
| `score`, `threshold`, `passed`, `reason` | same names |
| `judgeIdentifier` | a `judges[]` entry with `id` = that string |
| `input`, `actualOutput`, `expectedOutput`, `retrievalContext`, `durationMs` | same names |
| `tests[]` | `tests.jsonl` |
| (absent) | `kind` = `JUDGE` when a threshold exists, else `ASSERTION`; `family` = `agents`, `facet` = `answers`; `source` = `FRESH` |

## 11. Examples

[`examples/minimal/previous`](examples/minimal/previous) and [`examples/minimal/candidate`](examples/minimal/candidate) are two small bundles of the same dataset on branch `main`: a previous run and a candidate run. Together they exercise: an evaluation that flipped to failing, a judged change inside the noise band, a `REUSED` verdict, a `CARRIED` result, a `NOT_EVALUATED` line, a dimension declared by a scenario (`compliance`) with no metric, an agent trace with an unknown-tool step, a workflow trace that took the wrong branch, and an optimizer run. They are validated against the schemas in CI and are the fixtures for the comparison tests.
