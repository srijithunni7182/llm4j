# eval4j-report

A free, local dashboard for [eval4j](../eval4j/README.md) runs. It reads the **run bundle** that eval4j exports and builds:

- an **interactive report**: one self-contained HTML file (no server, no account, no network request), with a left navigation by test family, a ring per quality dimension showing where the agent is against where it should be, drill-down to metrics, tests and each case, priorities you set, run comparison with a judge-noise band, dataset coverage, cost and evidence, judge and agent models, agent traces, workflow trajectories and the prompt optimizer;
- a **static edition** (`static/index.html` + `eval4j-static.css`): no script, no inline style, so it displays under a strict Content-Security-Policy such as Jenkins' default for archived reports;
- `summary.md` (for pull-request comments), `compare.md`, `junit.xml` and `evaluations.csv`.

The report **informs** the release decision, it does not make it: there is no pass/fail verdict, goals are context not gates, and the CLI never fails a build because of how a run scored.

## Quick start

1. Run your eval4j tests with the extension. Each JVM exports a bundle automatically:

   ```java
   @ExtendWith(EvalReportExtension.class)
   class SupportAgentEvalTest { ... }
   ```

   ```
   mvn test            # writes target/eval4j/runs/<runId>/ and index.jsonl
   ```

2. Add the report module to the test classpath and the dashboard is written at the end of the test JVM:

   ```xml
   <dependency>
     <groupId>io.github.srijithunni7182</groupId>
     <artifactId>eval4j-report</artifactId>
     <version>5.0</version>
     <scope>test</scope>
   </dependency>
   ```

   Open `target/eval4j/report/index.html`.

3. Or render from the command line, from any directory of bundles (CI archive, a teammate's run):

   ```
   mvn -P cli package                       # builds eval4j-report-5.0-cli.jar (about 3 MB, no other dependencies)
   java -jar eval4j-report-5.0-cli.jar render target/eval4j
   java -jar eval4j-report-5.0-cli.jar compare target/eval4j --baseline <runId>
   java -jar eval4j-report-5.0-cli.jar list target/eval4j
   java -jar eval4j-report-5.0-cli.jar validate target/eval4j
   ```

Exit codes: `0` success, `2` bad usage, `3` unreadable input, `4` unexpected failure.

## Making the report meaningful

| You want | Do this |
|---|---|
| Dimensions from your golden dataset | give scenarios `id`, `dimensions` and `tags` in the YAML (see `EvalScenario`) and call `EvalRun.get().declareDataset(...)`; declared-but-unevaluated dimensions show as **No results** with the likely causes |
| Judge, agent and prompt shown | `EvalRun.get().declareJudge(...)`, `declareAgent(...)` |
| Deterministic checks named and classified | `EvalChecks.named("refund-order").dimension("reasoning").run(() -> assertThat(result).usesToolsInOrder(...))` (assertions are recorded automatically under defaults like `tool-order`) |
| Cheap builds | `-Deval4j.profile=BUILD` (default with a judge cache: only changed cases are judged), `FAST` (never calls the judge), `SAMPLE` (`-Deval4j.sample.rate=0.2`), `FULL`. Cases not judged are **not evaluated**, never passed; the report carries their latest known result, labelled |
| Cost | `-Deval4j.pricing=prices.properties` (`gemini-2.5-pro = 1.25, 10.00` USD per million tokens in, out) and `-Deval4j.judge.budgetUsd=2` |
| Goals and priorities | `eval4j-report.yaml` (below) |
| Workflow trajectories | the Loom bridge, below |

```yaml
# eval4j-report.yaml
project: { name: Support agent }
defaultGoal: 90
warnGap: 10
compare:
  baseline: sameBranch      # default: previous run on this branch, else latest on main/master
  noiseBand: 0.07           # judge variation below this is not called a change
dimensions:
  correctness: { goal: 95, priority: CRITICAL }
  efficiency:  { goal: 80, priority: NICE_TO_HAVE }
```

Priorities (`CRITICAL` ×3, `IMPORTANT` ×2, `NICE_TO_HAVE` ×1, `NONE` ×0) only weight the summary figure; viewers can change them in the report. They never change a stored result.

## Loom workflows

The bridge is the package `io.github.llm4j.evalreport.loom`; add `ai-agent4j-loom` to use it (it is an optional dependency).

```java
LoomTrace trace = LoomTrace.attach(executor).workflow(def)
        .expectPath("start", "n1", "n2", "end");    // node ids: start, n1..nN in statement order, end
executor.initialize();                               // attach before initialize()
executor.executeWorkflow(name, context);
WorkflowTrace wt = trace.finish(executor.spend());
WorkflowAssertions.assertThat(wt).followsExpectedPath().visitsInOrder("Researcher", "Writer")
        .loopStopsWithin("n3", 3).rewindsAtMost(20).noSecretsInTrace();
```

Trajectory assertions are plain deterministic checks (free, every build) and land in the **workflows** family. Loom emits no event for an `alt` decision, so the branch taken and loop iterations are inferred from which delegations happened; those `decision` events are marked `inferred`.

## Reference

- Design and contracts: [`spec/`](spec/00-OVERVIEW.md). The JSON Schemas in [`spec/schema`](spec/schema) are the contract, and the examples in [`spec/examples`](spec/examples) are tested against them.
- What is built and what is not yet: [`IMPLEMENTATION-STATUS.md`](IMPLEMENTATION-STATUS.md).
- Browser smoke test: `node src/test/browser/smoke.js <report-dir>` (needs Playwright).
- Migration from the v1 eval4j report: [eval4j reporting guide](../eval4j/docs/REPORTING-AND-BASELINES.md#the-new-dashboard-eval4j-report).

Everything is local: no network access, no telemetry. The page loads no font, script or image from anywhere.
