# 02. Changes inside eval4j: capturing and exporting a run

Status: **Draft for review** · Module: `eval4j` · New package: `io.github.llm4j.eval.export` · Depends on: [01-RUN-FORMAT.md](01-RUN-FORMAT.md)

eval4j's job in this design is to **capture facts and write a bundle**. It does not render, roll up, compare or interpret. Everything here is additive: a project that does nothing keeps today's behaviour.

## 1. Principles

| Req | Statement |
|---|---|
| EXP-01 | **Opt-in.** Nothing is written unless export is enabled (§3). Existing tests behave exactly as before when it is off. |
| EXP-02 | **Never fail a test because of export.** Any error while writing a bundle is caught, reported once on stderr, and disables further writing for the run. |
| EXP-03 | **No new runtime dependency.** Jackson, JUnit API and AssertJ are already present. |
| EXP-04 | **Facts only** ([00](00-OVERVIEW.md) D3): no goals, priorities or display text beyond the metric's own name. |
| EXP-05 | **Thread-safe.** Evaluations are recorded from parallel tests and from judge worker threads. |
| EXP-06 | Old public API keeps compiling. New behaviour arrives through new overloads, builders and optional fields. |

## 2. What changes, at a glance

| Area | Today | After |
|---|---|---|
| `EvalRecorder` | collects `EvalRecord` in memory for judged conditions; v1 writers render from it | records rich `Evaluation`s and hands each to the `RunWriter`; legacy overloads kept |
| Deterministic assertions (`AgentResultAssert`, `ConversationAssert`, `LlmResponseAssert`) | throw on failure, **record nothing** | record an `ASSERTION` (or `MEASURED`) evaluation for every check, pass or fail, then rethrow |
| `EvalScenario` | `name, input, expectedOutputContains, expectedOutput, expectedTools, context, retrievalContext` | adds `id`, `dimensions`, `tags`; old constructor kept |
| Binding a result to its scenario | none | the extension reads the `EvalScenario` argument of parameterized tests automatically |
| Judge calls | return a verdict only | also expose samples, cache hits, tokens, model, latency (internal `JudgeOutcome`) |
| Environment | none | branch, commit, CI, agent and judge descriptors, dataset descriptor, `configHash` |
| Output | v1 `EvalReportWriter` (HTML/JSON/CSV/JUnit/MD) | the run bundle; v1 writers deprecated |
| Hand-off to the report | none | `RunExportListener` SPI, discovered by `ServiceLoader` |

## 3. Enabling export

| Property | Default | Meaning |
|---|---|---|
| `eval4j.export` | `false` | `true` turns export on with the default directory. |
| `eval4j.export.dir` | `target/eval4j` (Maven) / `build/eval4j` (Gradle, detected by a `build/` directory beside `pom.xml`-less layout) | Root directory. Setting it also turns export on. |
| `eval4j.report.dir` | unset | **Deprecated alias** of `eval4j.export.dir`; prints a one-line deprecation notice. |
| `eval4j.export.retain` | `40` | Runs kept under `runs/`; older bundles are deleted when a run starts. `0` keeps all. |
| `eval4j.export.maxBytes` | `209715200` | Per-bundle cap on `evaluations.jsonl` (FMT §5.3). |
| `eval4j.export.caseText` | `true` | `false` omits `input`, `actualOutput`, `expectedOutput`, `retrievalContext` (FMT-25). |
| `eval4j.export.redact` | none | Comma-separated regular expressions replaced with `[redacted]` in all text fields (FMT-24). |
| `eval4j.run.id` | generated ULID | Override the run id. |
| `eval4j.run.group` | derived from CI build id, else `runId` | The `groupId` (FMT §7). |
| `eval4j.profile` | `BUILD` when a cache is configured, else `FULL` | See §8. |
| `eval4j.judge.budgetUsd` | unset | Per-run judge spend cap (§8). |
| `eval4j.pricing` | `eval4j.pricing.properties` on the classpath or working directory | Price table for cost (§7). Absent: tokens recorded, cost omitted. |

## 4. The new `export` package

```
io.github.llm4j.eval.export
  RunWriter            writes a bundle (run.json + .jsonl); thread-safe; atomic finish
  RunBundleLayout      paths and file names
  ExportConfig         reads the properties above
  Evaluation           immutable record of one evaluation (mirrors evaluation.schema.json)
  MetricRef            metric id, name, kind, family, facet, dimension, threshold, unit, budget
  CaseKey              caseKey / caseId / key computation (FMT §3), with the test vectors
  Hashes               sha256 → hex16
  RunEnvironment       detects branch, commit, CI; builds run.json env
  AgentDescriptor      id, provider, model, promptId, promptVersion, tools
  JudgeDescriptor      id, provider, model, temperature, samples, aggregation, rubric
  DatasetDescriptor    id, name, path, revision, hash, scenarioCount
  JudgeStats           running totals per judge (calls, hits, latency, tokens, cost, failures)
  Pricing              price table
  TraceRecorder        converts AgentResult steps / WorkflowTrace into trace lines
  WorkflowTrace        neutral workflow trace model (see 06)
  RunExportListener    SPI: onRunFinished(RunExport)
  RunExport            the finished run: root, bundle directory, runId, status
```

`Evaluation`, `MetricRef` and the descriptors are plain immutable records. The JSON they produce MUST validate against the schemas in [`schema/`](schema/) (EXP-30).

## 5. Recording evaluations

### 5.1 `EvalRecorder`

Keep the current static API shape (it is global and thread-safe by design). Add:

```java
EvalRecorder.record(Evaluation e);                       // the one real entry point
EvalRecorder.evaluation(MetricRef metric)                // fluent builder → .score(..).threshold(..).reason(..)
        .source(Source.FRESH).details(EvalDetails d)     //   .judge(JudgeOutcome o).trace(Trace t).record();
```

The existing `record(metric, score, threshold, reason, judgeIdentifier[, details])` overloads remain and build an `Evaluation` with `kind = JUDGE`, `family = agents`, `facet = answers`, and a metric id derived from the name (§5.3).

| Req | Statement |
|---|---|
| EXP-10 | `record` MUST be a no-op when export and the in-memory recorder are both off (as today). |
| EXP-11 | `record` MUST attribute the evaluation to the current test and scenario (§6) using thread-local state set by the extension, and compute `caseKey`, `caseId` and `key` (FMT §3). |
| EXP-12 | The occurrence index for `key` is counted per (caseKey, metric) within the run and MUST be stable for a deterministic test. |
| EXP-13 | Text fields are truncated and redacted **before** the line is handed to the writer (FMT §5.3, FMT-24). |
| EXP-14 | `seq` MUST increase monotonically across threads. |

### 5.2 Instrumenting deterministic assertions

Today `AgentResultAssert.usesToolsInOrder(...)` and its siblings throw `AssertionError` and leave no trace. They MUST record an evaluation for **every** call, pass or fail, then behave as before (rethrow on failure). Implementation: a package-private helper `Checks.run(MetricRef, Supplier<String> failureMessageOrNull, Runnable body)` used by each assertion.

| Assertion | Metric id | Kind | Family / facet | Default dimension |
|---|---|---|---|---|
| `usesToolsInOrder`, `usesToolsExactly` | `tool-order` | ASSERTION | agents / tools | `reasoning` |
| `usesTool`, `usesToolSuccessfully`, `hasStepOutcome` | `tool-use` | ASSERTION | agents / tools | `reasoning` |
| `usesToolWithArgument` | `tool-arguments` | ASSERTION | agents / tools | `correctness` |
| `usesNoTools` | `no-tools-used` | ASSERTION | agents / tools | `reasoning` |
| `hasFinalAnswerContaining`, `hasFinalAnswerMatching` | `final-answer-match` | ASSERTION | agents / answers | `correctness` |
| `hasValidJson` | `valid-structure` | ASSERTION | agents / answers | `correctness` |
| `isConfidentAbove` | `confidence` | ASSERTION | agents / reasoning | `reasoning` |
| `hasRedundantActionCountAtMost` | `redundant-actions` | MEASURED (count, budget = max) | agents / reasoning | `efficiency` |
| `usesFewerTokensThan` | `token-budget` | MEASURED (tokens, budget = max) | agents / answers | `efficiency` |
| `ConversationAssert` checks | `conversation-*` | ASSERTION | conversations / multi | `correctness` |
| `LlmResponseAssert` checks | `response-*` | ASSERTION | agents / answers | `correctness` |

| Req | Statement |
|---|---|
| EXP-15 | Each instrumented assertion records exactly one evaluation per invocation, with `reason` = the failure message (or `null` when it passed) and the relevant `measured{value, unit, budget}`. |
| EXP-16 | A failing assertion MUST still throw the same `AssertionError` as before; recording MUST NOT change test outcomes. |
| EXP-17 | Under `SoftAssertions` / `assertAll`, each sub-check is recorded individually. |
| EXP-18 | Users may name the metric and classify it: `assertThat(result).as(MetricRef.assertion("refund-flow").dimension("correctness"))…` using AssertJ's `as(...)` description hook, or a dedicated `EvalChecks.named(...)` wrapper. The exact fluent form is decided in the API review (open question Q-A). |

### 5.3 `MetricRef` and the default classification

`MetricRef.id` is a slug of the metric name: lowercase, every run of non-alphanumeric characters becomes `-`, trimmed. Built-in metrics carry a default classification, overridable on the builder with `.dimension("…")`, `.family("…")`, `.facet("…")`.

| Built-in metric (name) | id | Kind | Family / facet | Default dimension |
|---|---|---|---|---|
| Correctness | `correctness` | JUDGE | agents / answers | `correctness` |
| Answer Relevancy | `answer-relevancy` | JUDGE | agents / answers | `relevancy` |
| Faithfulness | `faithfulness` | JUDGE | agents / answers | `grounding` |
| Groundedness | `groundedness` | JUDGE | agents / answers | `grounding` |
| Hallucination-Free | `hallucination-free` | JUDGE | agents / answers | `grounding` |
| Task Completion | `task-completion` | JUDGE | agents / answers | `correctness` |
| Toxicity | `toxicity` | JUDGE | agents / answers | `safety` |
| Bias | `bias` | JUDGE | agents / answers | `safety` |
| Contextual Precision / Recall / Relevancy | `contextual-precision` … | JUDGE | retrieval / rag | `retrieval` |
| Knowledge Retention, Conversation Completeness | `knowledge-retention` … | JUDGE | conversations / multi | `correctness` |
| Role Adherence | `role-adherence` | JUDGE | conversations / multi | `safety` |
| Conversation Relevancy | `conversation-relevancy` | JUDGE | conversations / multi | `relevancy` |
| Pairwise prompt comparison (`PromptComparison`) | slug of the comparison name | PAIRWISE | prompts / compare | `prompting` |
| Optimizer best validation | `optimizer-best-validation` | MEASURED | prompts / optimization | `prompting` |
| custom `llmJudged("X")` | slug of `X` | JUDGE | agents / answers | **unclassified** (§5.4) |

### 5.4 Unclassified metrics

A custom judged condition has no built-in dimension. It exports `dimension = null`; the report places it under **Other** (product spec §5.1). eval4j MUST NOT invent a dimension. The builder gains `.dimension("compliance")`, `.family(…)`, `.facet(…)` so a team classifies its own metrics at the point of definition.

### 5.5 What a judged evaluation carries

`LlmJudgeCondition.matches` and the multi-call conditions (`RagContextCondition`, `ConversationJudgeCondition`, `PairwiseCondition`) call an internal `evaluateDetailed(...)` that returns a `JudgeOutcome`:

| Field | Source |
|---|---|
| `verdict` | as today |
| `samples[]` | the per-sample verdicts already collected before `combine` |
| `hits`, `calls` | per sample: a cache hit (`cache.get(key)` present) or a real call |
| `tokensIn`, `tokensOut`, `model`, `durationMs` | from the `LLMResponse` (`getTokenUsage()`, `getModel()`) that `callJudge` currently discards after reading `getContent()`; plus wall time |
| `source` | `REUSED` if every sample was a cache hit; otherwise `FRESH` |

`evaluate(Object)` keeps its signature and delegates. The aggregation across samples is today the **mean** (`combine`); the descriptor records `aggregation = MEAN`.

| Req | Statement |
|---|---|
| EXP-19 | The judge call path MUST capture token usage, model and latency without changing prompts, temperature or caching behaviour. |
| EXP-20 | `JudgeCacheKey` MUST include a **judge fingerprint** (provider, model and rubric version) in addition to the user-supplied `judgeIdentifier`, so that changing the judge model invalidates reuse. This changes keys once; the first run after upgrading re-judges. Documented in the migration guide. |
| EXP-21 | A judge error (`JudgeEvaluationException`) records an evaluation with `status = ERROR` and the message in `reason`, then rethrows. |

## 6. Cases and scenarios

### 6.1 `EvalScenario`

Add to the record: `id` (String), `dimensions` (List<String>), `tags` (List<String>). All optional; the existing 7-argument constructor is kept. YAML loading (`EvalScenarios`) accepts the new keys and ignores unknown ones. `EvalScenario.caseKey()` implements FMT-10.

```yaml
- id: gdpr-data-export
  name: GDPR data export
  input: "I am in the EU. Send me all the data you hold on me."
  expectedOutput: "Request an export under Settings, Privacy. We deliver it within 30 days."
  dimensions: [compliance, correctness]
  tags: [privacy]
```

### 6.2 Dataset registry and `scenarios.jsonl`

`EvalScenarios.fromYaml*` registers the loaded dataset in a global `DatasetRegistry` (thread-safe) with a `DatasetDescriptor`: `id` = file name without extension, `path`, `revision` = the property `eval4j.dataset.<id>.revision` if set, else `null`, `hash` = SHA-256 of the file content (first 16–64 hex characters), `scenarioCount`. When export starts or a dataset loads later, the writer appends its scenarios to `scenarios.jsonl` once.

| Req | Statement |
|---|---|
| EXP-22 | Loading a dataset MUST NOT require export to be on; registration is cheap and in-memory. |
| EXP-23 | A scenario with `dimensions` MUST be written to `scenarios.jsonl` even if no test ever evaluates it, so the report can show a declared-but-unevaluated dimension. |
| EXP-24 | `families` in `scenarios.jsonl` is inferred from the evaluations that cite the scenario when not declared; it MAY be empty. |

### 6.3 Binding results to scenarios without test changes

`EvalReportExtension` additionally implements `InvocationInterceptor`. For `@Test`, `@ParameterizedTest` and `@TestTemplate` methods it reads `invocationContext.getArguments()`, finds the first `EvalScenario`, and sets it as the thread-local **current case** for the duration of the invocation (restored in `finally`). `EvalRecorder.record` reads it (EXP-11). Where a test builds scenarios another way, `EvalRecorder.forScenario(scenario, () -> …)` and `EvalRecorder.forCase(String caseKey, …)` do the same explicitly.

| Req | Statement |
|---|---|
| EXP-25 | Binding MUST work under parallel test execution (thread-local, set and cleared around each invocation). |
| EXP-26 | If no scenario is found, `caseKey` falls back to `<suite>#<method>[<displayName>]` (FMT-11). |
| EXP-27 | Tests that run evaluations on helper threads MAY propagate the case with `EvalRecorder.currentCase()` / `withCase(...)`. |

## 7. Evidence, cost and descriptors

### 7.1 Cost

`Pricing` reads a properties file: `<provider>/<model>.in=<usd per 1M input tokens>` and `.out=…`. `costUsd = tokensIn × in + tokensOut × out` (per million). Without a price, tokens are recorded and `costUsd` is omitted; the report then shows tokens, not money. No built-in prices (they go stale).

### 7.2 Judge and agent descriptors

- **Judge.** `JudgeCalls` and `LlmJudgeCondition.Builder` gain `.descriptor(JudgeDescriptor)`; when absent, a descriptor is derived: `id` = `judgeIdentifier` or `"judge"`, `model` from the first `LLMResponse.getModel()`, `temperature` and `samples` from the condition, `aggregation = MEAN`, `rubricId` = `"eval4j-judge"`, `rubricVersion` = a hash of `JudgePrompt.SYSTEM_PROMPT` (EXP-20 uses the same value).
- **Agent.** The agent under test is not known to eval4j. `EvalRunContext.agent(AgentDescriptor)` (static, or via an `@EvalAgent(model=…, promptId=…, promptVersion=…)` class annotation) declares it. When an `AgentResult` is judged and no descriptor exists, the model is taken from the result if available; otherwise `env.agents` is empty and the report says "agent not described".
- `JudgeStats` accumulates per judge across the run and is written into `run.json` at finish.

### 7.3 Environment detection (`RunEnvironment`)

No subprocess is spawned. Order of sources:

| Field | Sources, first non-empty wins |
|---|---|
| `branch` | `eval4j.run.branch`; `GITHUB_HEAD_REF`, `GITHUB_REF_NAME`; `BRANCH_NAME`, `GIT_BRANCH` (strip `origin/`); `CI_COMMIT_REF_NAME`, `CI_COMMIT_BRANCH`; read `.git/HEAD` walking up from the working directory |
| `commit` | `eval4j.run.commit`; `GITHUB_SHA`; `GIT_COMMIT`; `CI_COMMIT_SHA`; resolve the ref in `.git` |
| `pullRequest` | `GITHUB_REF` (`refs/pull/N/merge`); `CHANGE_ID`; `CI_MERGE_REQUEST_IID` |
| `ci` | `GITHUB_ACTIONS` → `github`; `JENKINS_URL` → `jenkins` (+ `BUILD_URL`, `BUILD_NUMBER`); `GITLAB_CI` → `gitlab`; else absent |
| `dirty` | `null` unless cheaply known |

A detached HEAD yields `branch = null`; the report then cannot choose a same-branch baseline and falls back (03 §6).

`configHash` = hex16 of the SHA-256 of the sorted `profile`, `judge.budgetUsd`, `sampleRate`, thresholds seen, judge `samples`, and `eval4j.*` result-affecting properties.

## 8. Profiles, budget and "not evaluated"

LLM judging is expensive, so a run states how much of it it does. Profile semantics (product spec §5a):

| Profile | Judge calls | Behaviour of a judged condition |
|---|---|---|
| `FULL` | all | evaluates everything (cache still used if configured) |
| `BUILD` | only cases whose cache key misses | a cache hit is `REUSED`; a miss is judged `FRESH` (change-aware), unless the budget is exhausted |
| `SAMPLE` | a seeded stratified share | selected cases are judged; others are `NOT_EVALUATED` |
| `FAST` | none | cache hits are `REUSED`; misses are `NOT_EVALUATED` |

When a judged condition is **not evaluated** (FAST miss, SAMPLE skip, budget exhausted) it MUST: record an evaluation with `status = NOT_EVALUATED` and a `reason`, then call `org.junit.jupiter.api.Assumptions.abort(...)`-style abort so the **test is reported as aborted, not passed and not failed**. A skipped judge never silently passes (product spec §5a).

| Req | Statement |
|---|---|
| EXP-30 | Under `BUILD`, a case judged once and unchanged MUST cause zero judge calls on re-run (the cache hit path), proven by a test counting `LLMClient` calls. |
| EXP-31 | Under `SAMPLE`, selection is deterministic for a seed: `hash(seed, caseKey, metricId) / 2^64 < sampleRate`. Stratification is per dimension. |
| EXP-32 | The judge budget is checked **before** each judge call using the running `JudgeStats.costUsd` plus an estimate for the call; when exhausted, further judged evaluations become `NOT_EVALUATED` and the run ends `PARTIAL`. |
| EXP-33 | `CARRIED` results (FMT-16) are produced only when `profile.carryOver` is true and a previous bundle of the same branch holds a verdict for the same `key`. A carried verdict is **reported, never asserted**: the test is aborted like any not-evaluated check, unless `eval4j.carryover.gate=true`. (Delivered in phase 9.) |

## 9. Tests, traces and prompt results

- **Test outcomes.** The extension's existing `TestWatcher` callbacks write `tests.jsonl`: `PASSED`, `FAILED`, `ABORTED`, `SKIPPED` (disabled tests are written as `SKIPPED` rather than ignored), duration, failure message.
- **Agent traces.** When the object under evaluation is an `AgentResult`, `TraceRecorder` writes an `AGENT_STEPS` line from `getSteps()` (thought, action, input, observation, outcome, timing if present) and sets the evaluation's `traceId`. One trace per `AgentResult` per test, shared by its evaluations.
- **Workflow traces.** Written by the bridge ([06](06-LOOM-INTEGRATION.md)) through `TraceRecorder.workflow(WorkflowTrace)`.
- **Prompt A/B.** `PromptComparison` already calls `EvalRecorder.record(...)` per scenario; it now records `PAIRWISE` evaluations whose `display` is `B wins`/`Tie`/`A wins`, and attributes them to the scenario.
- **Optimizer.** `OptimizationRun` writes one `optimizations.jsonl` line from its `OptimizationResult` (rounds from `Round`, `StopReason`, budget, overfitting check, `LineDiff` of the accepted prompt). It keeps writing its own `optimizer-trace.json` and `optimizer-report.md`.

## 10. Writing the bundle

`RunWriter` lifecycle (FMT §2):

1. **start** (first `record`, first `beforeAll`, or an explicit `EvalRun.start()`): create `runs/<runId>/`, apply retention, write `run.json` (`RUNNING`).
2. **append** (per evaluation / test / trace / optimization / scenario): serialize to one line with Jackson, take the file lock, append `line + "\n"`, flush. One `FileChannel`/`BufferedWriter` per file, opened on first use, closed at finish.
3. **finish** (root extension store `CloseableResource`, as `RunState` does today): flush and close files, compute `summary` and `metrics`, collect `JudgeStats`, rewrite `run.json` atomically (reuse `AtomicFiles`), append `index.jsonl`, then call each `RunExportListener` (§11).

| Req | Statement |
|---|---|
| EXP-34 | `RunWriter` MUST survive being used from many threads, including `ForkJoinPool` workers, and from tests running in parallel classes. |
| EXP-35 | Append failures (disk full, permissions) set the writer to `FAILED`: no further writes, one stderr message, tests unaffected (EXP-02). `run.json` is still finalised with `status = FAILED` if possible. |
| EXP-36 | If several JVMs in one build write to the same export root, each has its own `runId` and shares `groupId` (FMT §7). They MUST NOT write the same bundle directory. |
| EXP-37 | `finish` MUST run on normal JVM exit (a shutdown hook) even if the extension's root store is not closed, so a bundle is finalised after `System.exit`. |

## 11. The listener SPI

```java
package io.github.llm4j.eval.export;
public interface RunExportListener {
    /** Called once per run after the bundle is finalised. Must not throw; exceptions are caught and logged. */
    void onRunFinished(RunExport run);
}
```

Discovered with `ServiceLoader` (`META-INF/services/io.github.llm4j.eval.export.RunExportListener`). `eval4j-report` ships an implementation that renders the dashboard into `eval4j.export.dir/report`. When no listener is on the classpath and export is on, eval4j prints one line:

```
eval4j: run bundle written to target/eval4j/runs/<runId>. Add the eval4j-report dependency, or run
        java -jar eval4j-report-cli.jar render target/eval4j   to build the dashboard.
```

| Req | Statement |
|---|---|
| EXP-38 | Listener failures and slowness MUST NOT fail or hang the build; each listener runs with a timeout (default 60 s, `eval4j.export.listenerTimeoutSec`). |
| EXP-39 | The listener receives only paths and ids, not eval4j internals, so report versions are not tied to eval4j versions. |

## 12. What stays and what is deprecated

| Item | Fate |
|---|---|
| `@EvalBaseline`, `BaselineGate`, `EvalBaseline` | **Stay**, unchanged; independent of export (D6). |
| `PassRate`, `JudgeCache`, `FileSystemJudgeCache` | Stay. The cache gains the judge fingerprint (EXP-20). |
| `EvalRecorder` legacy overloads, `EvalRecord`, `EvalDetails` | Stay; map to `Evaluation`. |
| `EvalReportWriter`, `HtmlDashboard`, `Charts`, `ReportAnalysis`, `MarkdownSummary`, `JUnitXmlWriter`, `CsvWriter`, `EvalReportCli`, `dashboard.css/js` (the v1 report code) | **Deprecated** in the release that adds export; **removed** in the next major. Their features are superseded by `eval4j-report`. While deprecated they keep working with `eval4j.report.dir`. |
| `FileSystemScoreHistory`, `ScoreHistory`, `HistoryEntry` | Deprecated with the v1 writers; history is derived from retained bundles. |
| Console summary printed by the extension | Stays. |

## 13. Tests for this module (see [07](07-TEST-STRATEGY.md))

| Req | Statement |
|---|---|
| EXP-40 | Every line `RunWriter` produces validates against the schemas; a test replays a recorded set of evaluations and validates each line and `run.json`. |
| EXP-41 | `CaseKey` reproduces the FMT test vectors. |
| EXP-42 | Crash test: kill a writer mid-run (truncate the last line); the bundle is readable per FMT-05/06. |
| EXP-43 | Concurrency test: N threads record M evaluations each; the file has N×M valid lines with unique `seq`. |
| EXP-44 | Instrumented assertions: each records once, pass and fail, and a failing one still throws the same message. |
| EXP-45 | Scenario binding: a `@ParameterizedTest` over `EvalScenario`s produces evaluations whose `scenarioId` matches the argument, including under parallel execution. |
| EXP-46 | Profile tests: `FAST` and `SAMPLE` produce `NOT_EVALUATED` and an aborted test, never a pass; `BUILD` reuse makes zero judge calls. |
| EXP-47 | Compatibility: a project using only today's API compiles and passes unchanged; with export off, no file is created. |
| EXP-48 | Redaction and `caseText=false` remove the text they promise. |
| EXP-49 | Listener SPI: discovery, timeout, and exception isolation. |

## 14. Open questions

- **Q-A.** The fluent way to name and classify a deterministic assertion (AssertJ `as(...)` hook, a `Checks.named(...)` wrapper, or both). Needs a small API spike.
- **Q-B.** Whether `JudgeCacheKey` should also fold in the **agent** descriptor (so a prompt change always misses). Today the key includes the agent's *output*, which already changes when the prompt changes the answer; folding in the descriptor would be stricter than needed.
- **Q-C.** Default `eval4j.export.dir` for Gradle projects.
