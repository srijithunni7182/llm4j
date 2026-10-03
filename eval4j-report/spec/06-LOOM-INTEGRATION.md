# 06. Loom workflows and agent traces

Status: **Draft for review** · Modules: `eval4j` (trace model, assertions), `eval4j-report` (the bridge, package `io.github.llm4j.evalreport.loom`) · Related: [01](01-RUN-FORMAT.md) §6, [04](04-DASHBOARD-UI.md) §2 and §7

The dashboard shows whole Loom workflow runs (path graph, timeline, checks, spend, event log) and single-agent step traces. `eval4j` and the report library may not depend on Loom; only the bridge package does (D12). This document defines how Loom data gets into a bundle.

## 1. What Loom already provides

| Need | Loom source | Notes |
|---|---|---|
| Events as they happen | `TraceEvent(type, agent, step, text, data, at)` through `TraceListener`, registered with `HarnessExecutor.addTraceListener` | types: `delegate_start`, `delegate_end`, `delegate_replayed`, `thought`, `action`, `observation`, `budget`, `approval`, `memory`, `guard`, `voice`, `suspended`, `tool`, `checkpoint`, `rewind`, `decision`. Step ids are hierarchical (`main/s0/f1`). Called on worker threads, possibly concurrently. |
| Spend per step and agent | `SpendReport.Line(step, agent, model, charge)` and `Totals` | prompt/completion tokens, calls, cost, an `estimated` flag |
| The workflow's structure | the parsed AST (`ast` package): `delegate`, `alt`, `loop until`, `handoff`, `human_prompt`, `checkpoint`, `rewind` | used to build the **graph** |
| Durable record | `RunJournal` | not needed by the report |

For single agents, `AgentResult.getSteps()` (thought, action, input, observation, `StepOutcome`) is already in eval4j's world (02 §9).

## 2. The neutral model in eval4j

`io.github.llm4j.eval.export.WorkflowTrace` (a plain immutable record tree mirroring `trace.schema.json` → `workflow`): `name`, `graph{nodes[], edges[]}`, `expectedPath[]`, `actualPath[]` (optional), `events[]`, `spend[]`, `budgetUsd`, `rewinds`, `rewindCap`. `TraceRecorder.workflow(WorkflowTrace, String caseKey)` writes a `traces.jsonl` line and returns the `traceId` to attach to evaluations.

## 3. The bridge: `io.github.llm4j.evalreport.loom`

A package inside `eval4j-report` that depends on `eval4j` and `ai-agent4j-loom`, both declared `<optional>` so the report library and the CLI jar stay lean. Add `ai-agent4j-loom` yourself to use it. There is no `eval4j-loom` artifact.

```java
try (LoomTrace trace = LoomTrace.attach(executor).named("ResearchAndPublish")) {
    runWorkflow(...);
    WorkflowTrace wt = trace.finish(spendReport);       // builds the model
    assertThat(wt).visitsInOrder("Researcher", "Writer", "Publisher")   // trajectory assertions (§5)
                  .takesBranch("A", "SUFFICIENT").loopStopsWithin("L", 3).rewindsAtMost(20);
}
```

| Responsibility | Detail |
|---|---|
| **Subscribe** | `LoomTrace.attach(executor)` registers a `TraceListener` that is thread-safe (events from parallel branches arrive concurrently), appends to a bounded buffer (20,000 events; the rest are counted and a `truncated` flag set), and never throws. |
| **Map events** | `TraceEvent` → `{t, type, agent, step, node, text, data}` with `t` = seconds since the first event. `text` and string values in `data` are truncated (2,000 chars) and pass through the redaction list. The event type vocabulary is passed through unchanged (an unknown type is kept). |
| **Build the graph** | From the loaded workflow AST: one node per statement (`delegate`, `alt`, `loop`, `handoff`, `human_prompt`, `checkpoint`), plus `start` and `end`; edges follow control flow with labels for `alt` branches (`SUFFICIENT`, `else`) and loop back-edges; `loop until` nodes carry `bound` from `at most N`. Node ids are stable (`S`, `R`, `A`, …) derived from statement order so the same script yields the same ids. |
| **Map step ids to nodes** | A step id's prefix identifies the statement (`main/s2`); the bridge records it in `event.node`. |
| **Expected path** | Declared by the test (`.expectPath("S","R","A","W","P","E")`) or derived from a workflow annotation; absent means "no expectation": the graph shows only the path taken. |
| **Actual path** | Derived from `delegate_start`, `decision`, `checkpoint`, `rewind` events in order, collapsing repeats of a loop body into a node visit count. |
| **Spend** | From the `SpendReport` lines, copied field for field. |
| **Record** | `finish(...)` calls `TraceRecorder.workflow(...)` and sets `traceId` on the evaluations recorded by the assertions below. |

| Req | Statement |
|---|---|
| LOOM-01 | The bridge MUST NOT change Loom's runtime behaviour or timing materially; the listener is a bounded in-memory append. |
| LOOM-02 | `eval4j` and every `eval4j-report` package except `evalreport.loom` MUST NOT reference any Loom class; the trace in the bundle is Loom-free (event type names are strings). |
| LOOM-03 | A workflow with no available AST (for example a remote run) still produces a trace: the graph is omitted and the report shows the timeline, checks, spend and log without the path graph. |
| LOOM-04 | Secrets never reach a bundle: Loom already keeps them out of trace data; the bridge additionally applies the redaction list, and drops `data` values whose keys look like credentials (`token`, `secret`, `password`, `authorization`). |

## 4. Where the data goes in the dashboard

| Bundle data | View |
|---|---|
| `graph`, `expectedPath`, `actualPath` | path graph (expected dashed, taken solid, wrong/failed red, loop ×N vs bound, badges) |
| `events` | timeline (a lane per agent, bars from `delegate_start`/`delegate_end`, diamonds for `guard`, `checkpoint`, `rewind`, `budget`, `suspended`, `decision`) and the filterable event log |
| `spend`, `budgetUsd` | spend by agent against the run budget |
| `rewinds`, `rewindCap` | rewind badge and the "Rewinds within cap" check |
| trajectory evaluations (§5) | the "Checks for this run" list and the Workflows family rollups |

## 5. Trajectory assertions (in eval4j, over the neutral model)

Plain AssertJ-style assertions on `WorkflowTrace`, so they run with no judge and no cost, on every build. Each records an `ASSERTION` evaluation (02 §5.2) in the **workflows** family:

| Assertion | Metric id | Facet | Default dimension |
|---|---|---|---|
| `usesToolsInOrder(...)`, `usesToolsExactly(...)` (across agents) | `tool-order-matches-expected` | trajectory | `orchestration` |
| `visitsInOrder(agents…)`, `invokesAgents(agents…)` | `required-agents-invoked` | trajectory | `orchestration` |
| `callsOnlyAllowedTools(set)` | `no-unexpected-tool-calls` | trajectory | `orchestration` |
| `followsExpectedPath()` | `follows-expected-path` | trajectory | `orchestration` |
| `staysWithinSpend(usd)` | `spend-within-budget` | trajectory | `efficiency` (MEASURED) |
| `takesBranch(node, label)` | `correct-branch-taken` | orchestration | `orchestration` |
| `loopStopsWithin(node, n)` | `loop-within-bound` | orchestration | `orchestration` |
| `rewindsAtMost(n)` | `rewinds-within-cap` | orchestration | `orchestration` |
| `requestsApprovalBefore(node)` | `approval-requested-when-required` | orchestration | `orchestration` |
| `outputMatchesSchema(map, requiredKeys…)` | `typed-output-complete` | outputs | `correctness` |
| `guardHeld("pii")`, `noSecretsInTrace()` | `pii-guard-held`, `no-secret-in-trace` | guardrails | `safety` |

| Req | Statement |
|---|---|
| LOOM-10 | Every assertion records exactly one evaluation and throws on failure exactly like AssertJ (EXP-15..17). |
| LOOM-11 | `noSecretsInTrace()` scans events and spend text with the redaction patterns and a built-in credential-shape list; it MUST itself never write the secret it found (the evaluation says where, not what). |
| LOOM-12 | The same assertion names exist for a single agent's trace where meaningful (`usesToolsInOrder` already does, 02 §5.2). |

## 6. Single-agent traces

`AgentResult` steps become an `AGENT_STEPS` trace (one per result per test) with the seven `StepOutcome` values. The report derives: step counts, the outcome histogram, repeat rate (`DUPLICATE_BLOCKED / steps`), tool-error rate (`EXECUTION_ERROR / steps`), unknown-tool count, steps vs `stepBudget` (the agent's configured maximum, recorded when known). These feed the Agents → Reasoning view; no extra export is needed.

## 7. Tests

| Req | Statement |
|---|---|
| LOOM-20 | Bridge test with a scripted Loom workflow (the repo's `ResearchAndPublish`-style sample): the produced `WorkflowTrace` has the expected nodes, edges, path and event types, and validates against `trace.schema.json`. |
| LOOM-21 | Concurrency: a parallel-branch workflow produces a trace with all events and no exceptions from the listener. |
| LOOM-22 | Rewind and budget-cap scenarios produce `rewind` / `budget` events and the matching badges data. |
| LOOM-23 | The `examples/getviral` workflow evaluation (already in the repo) is the dogfood case: it must produce a bundle whose trajectory view renders without hand-editing. |
| LOOM-24 | A test proves `eval4j` and every `eval4j-report` package except `evalreport.loom` have no Loom import. |

## 8. Open questions

- ~~Q-L1~~ Resolved: the bridge lives inside `eval4j-report` (package `evalreport.loom`), Loom optional.
- ~~Q-L2~~ Resolved: an explicit call, `LoomTrace.expectPath("start", "n1", …, "end")`. Node ids are `start`, `n1`…`nN` in statement pre-order, `end`; `trace.graph()` lists them.
- **Implementation note.** Loom emits no event for an `alt` decision, so the bridge infers the branch taken and loop iterations from which delegations happened and marks those `decision` events `data.inferred = true`. Delegations map to nodes by agent name in statement order.
- **Q-L3.** Loom's earned-autonomy replay (`ReplayReport`) as a future sixth family; the format already allows a new family id without a version change.
