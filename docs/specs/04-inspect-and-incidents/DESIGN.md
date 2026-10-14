# Phase 4 design: inspect and incidents (loom-inspect)

Status: **approved (decisions in §9 taken as recommended, per the standing instruction)**. Layer: loom-inspect-core and loom-inspect. They know nothing of Fabric's pages (phase 5).
Companion documents: [TEST_STRATEGY.md](TEST_STRATEGY.md), [VERIFICATION.md](VERIFICATION.md), [roadmap](../README.md). Builds on [Phase 3](../03-loom-integration/DESIGN.md), which makes the
Loom trace carry `context`, `compaction`, `model_call` and `tool_call` events.

## 1. Problem

loom-inspect turns Loom's trace into OpenTelemetry-shaped records, keeps a live `RunState`, and raises incidents. It was built when the trace had no per-call event, so:

- A tool call's failure is guessed from the **text** of its observation (`TraceMapper.looksLikeError`). Phase 3 now sends the real outcome and category.
- A model call is invisible: a step's tokens are one total, with no duration, no finish reason, no retries, no failover, and nothing says an answer was cut off.
- Context fill and compaction are not mapped, kept or judged. Nothing says a run is nearly out of window, that compaction is thrashing, or that a tool's success rate has collapsed.
- The tools table has calls, errors and an average; no success rate, percentiles or failure categories.

## 2. Goals and non-goals

**Goals**

- G1. The mapper uses the **real** tool outcome and category when the trace has them, and keeps the text heuristic only for traces from older runtimes.
- G2. Every model call becomes a **`chat` span** with the OpenTelemetry GenAI attributes (model, tokens, finish reason) and Loom's (duration, attempts, waits, fail-overs, category).
- G3. Context use and compactions become log records with their numbers, and the run's totals reach the run record.
- G4. `RunState` keeps, per agent, the context gauge and compaction history; per model, calls, failures by category, tokens and latency percentiles; per tool, success rate, soft
  failures, failures by category and latency percentiles. The `view()` JSON carries them (the shape phase 5 draws).
- G5. Five new **incidents**: context nearly full, truncated answer, compaction thrash, tool success rate collapse, latency spike. Each is a pure function of the event stream, replays
  to the same incidents, shows its evidence, and is configurable per agent in the `.inspect` file and centrally.
- G6. Old consumers and old traces keep working: unknown attributes are ignored, a trace without the new events behaves exactly as before.
- G7. Nothing in the new records carries prompt, answer, argument or result text, at any detail level.
- G8. **Everything phase 5's tools-centric view needs is computed here**, so the page only draws it: per tool a health state with its reason (`healthy`, `degraded`, `failing`, `unused`), recent and overall success rates, consecutive failures, last failure (category, time), last success, the failures by category, a per-agent breakdown, and a latency histogram. The same summaries are mergeable, so a server can roll them up **across runs and processes** and show tools that are failing everywhere.

**Non-goals**

- Drawing any of it (phase 5: gauges, timeline, tools table, thresholds page, simulator scenarios).
- OpenTelemetry metrics/histograms: spans and logs carry the numbers; a collector or Fabric derives the aggregates. (`MetricRec` stays unused.)
- A standard for other runtimes (phase 6).

## 3. Mapping

### 3.1 Tool calls

The `tool_call` event arrives **right after** the observation that closes the tool's span. The mapper holds the closed span until the matching `tool_call` arrives (or, for an older
trace that never sends one, until the agent's next event, the step's end or the run's end flushes it with the heuristic). The span then carries:
`loom.tool.outcome`, `loom.tool.soft_failure`, `loom.tool.result_chars`, `loom.tool.approval_ms`, `error.type` = the category (lower case) on failure, span status from the outcome (not the text),
and its duration is the event's `duration_ms` (execution only, not the approval wait) when present.

### 3.2 Model calls

A `model_call` event becomes a span `chat {model}` (kind client) ending at the event, starting `duration_ms` before it, child of the agent span:
`gen_ai.operation.name=chat`, `gen_ai.agent.name`, `gen_ai.request.model`, `gen_ai.response.model`, `gen_ai.usage.input_tokens`, `gen_ai.usage.output_tokens`,
`gen_ai.response.finish_reasons`, `loom.model.attempts`, `loom.model.waited_ms`, `loom.model.failovers`, `loom.model.truncated`, `loom.span.kind=model`; on failure status ERROR with `error.type`
(the category). Span ids are derived from the run id, step and a per-agent counter, as for tools, so a resumed run does not clash.

### 3.3 Context and compaction

`context` becomes a log record `loom.context` (attributes `loom.context.used`, `.window`, `.usable`, `.fraction`, `.source`); `compaction` a log record `loom.compaction`
(`strategy`, `outcome`, `reason`, `before`, `after`, `messages_before`, `messages_after`, summary tokens and cost), at WARN when the outcome is FAILED. `run_end` totals become attributes of the run
record (`loom.run.model_calls`, `.tool_calls`, `.tool_failures`, `.retries`, `.failovers`, `.compactions`).

### 3.4 Names

All new names are constants in `Semconv`, the one place that changes when OpenTelemetry's GenAI conventions do.

## 4. RunState

Per agent: `context` = `{used, window, usable, fraction, source, compactions, lastCompaction{strategy, outcome, before, after, at}}` and a short series of the last 60 `fraction` samples (a sparkline).
New top-level `models`: `[{name, calls, failures{category:n}, promptTokens, completionTokens, retries, failovers, truncated, p50Ms, p95Ms, waitedMs}]`.
Tools gain `{successes, successRate, softFailures, failures{category:n}, p50Ms, p95Ms}` beside the existing `calls`, `errors`, `inFlight`, `avgMillis` (kept). Percentiles are nearest-rank over a bounded
deterministic reservoir (10,000), implemented in the core module (it must not depend on ai-agent4j or Loom). `summary()` gains `contextFraction` (the highest of any agent) and `incidents` count by severity (already there).

## 4b. Tool health (for the tools-centric view of phase 5)

`ToolSummary` (public, in the core module) is one tool's account in one run, and can be merged with another's. `RunState.toolSummaries()` returns them, `RunRegistry.toolRollup()` merges them across every run it knows
(optionally one client's), and each row of `view().tools` carries a `health` map with the same fields. The **heuristics**, all deterministic and documented in one class (`ToolHealth`):

| State | When |
|---|---|
| `unused` | no call of it has run yet |
| `failing` | it has run at least 3 times and **never succeeded**; or its last `toolFailures` calls (default 3) in a row failed; or its success rate over the last 10 calls (at least 5) is below 40 % |
| `degraded` | its success rate over the last 10 calls (at least 5) is below `tool_success_rate` (default 70 %); or it has run fewer than 5 times and its last call failed |
| `healthy` | otherwise |

Each state carries a one-line `reason` ("never succeeded in 6 calls", "last 4 calls failed: timeout", "3 of 10 recent calls succeeded"). Latencies are kept as a fixed-bucket histogram (bounds 10, 25, 50, 100, 250, 500, 1,000, 2,500, 5,000, 10,000, 30,000 ms and above) so that
percentiles can be merged across runs; a merged percentile is the upper bound of the bucket it falls in. A merged summary's state is the worse of its parts, and is `failing` when the merged counts say it never succeeded, or `degraded` when the merged overall rate over at least 5 calls is below the threshold.
The per-agent breakdown (calls, successes, failures per agent) shows whether one agent misuses a tool or the tool itself is broken.

## 5. Incidents

| Kind | Fires when | Severity | Closes when | Evidence |
|---|---|---|---|---|
| `CONTEXT` | an agent's context fraction reaches `warn` (default 85 %); `high` at 95 % | warn / high | the fraction falls below `warn` (after a compaction), or the agent ends | measured fraction, threshold, used/window, compactions so far, last outcome |
| `TRUNCATED` | `truncated_answers` model calls (default 1) of an agent ended because the token limit cut the answer | warn (high at 3) | never auto-closed within the step; ends with the step | count, finish reason, completion tokens |
| `THRASH` | `compaction_thrash` compactions (default 4) within `of` model calls (default 10) of one agent, or 3 compactions in a row that gained less than 10 % | warn | a window of calls passes without enough compactions | counts, gains, the strategy |
| `TOOL_RATE` | a tool's success rate over its last `window` calls (default 10, at least 5 calls) is below `tool_success_rate` (default 70 %) | warn (high below 40 %) | the rate recovers above threshold | rate, window, top failure category |
| `SPIKE` | a model or tool call lasts more than `latency_spike` × the running p50 (default 5×) with at least 10 earlier samples and above 2 s | info → warn on a second in a row | the next call is back under the limit | duration, p50, the call |

The existing `FAILED` (consecutive tool failures) uses the event's category when present. Detectors are pure functions of the event stream: `Detectors` gains `onContext`, `onCompaction`,
`onModelCall`, `onToolCall`; `RunState.apply` feeds them from the records (spans and logs), so a recorded stream replays to the same incidents. Incident kinds are an open set
(consumers must tolerate unknown kinds).

## 6. Rules (the `.inspect` file)

New lines in the existing sidecar grammar, per agent or `*`:

```
Researcher {
    context_nearly_full: warn at 85%, high at 95%
    truncated_answers: 1 times
    compaction_thrash: 4 in 10 calls
    tool_success_rate: below 70% over 10 calls
    latency_spike: 5x p50
}
```

`InspectRules` and `Thresholds` gain nullable fields (a rule left out keeps the default); `RulesFile` parses and validates them (ranges, `warn` below `high`, the usual "did you mean" for a misspelt rule);
`RulesCheck` reports what does not meet the script (unknown agent). Central rules from Fabric carry the same record, so the JSON gains optional fields (decision 4).

## 7. Requirements (testable)

| Id | Requirement |
|---|---|
| R1 | A `tool_call` event sets the tool span's status, `error.type` and attributes; the text heuristic is not used when the event arrives |
| R2 | A trace without `tool_call` events maps exactly as before (golden records) |
| R3 | The held tool span is flushed by the agent's next event, the step's end and the run's end; no span is lost or emitted twice, in any interleaving, including across a resume |
| R4 | A `model_call` becomes a `chat` span with the attributes in §3.2; a failed call is an ERROR span with the category; start = end − duration; ids are stable across a replay |
| R5 | `context` and `compaction` become log records with the numbers in §3.3; a FAILED compaction is WARN |
| R6 | Run totals from `run_end` appear on the run record |
| R7 | No new record carries prompt, answer, argument or result text at any detail level (including a planted secret) |
| R8 | `RunState` keeps per-agent context and compaction state, per-model and per-tool statistics, exact up to the reservoir and bounded beyond it |
| R9 | `view()` carries the fields in §4; old fields are unchanged; `summary()` carries `contextFraction` |
| R10 | The five incidents fire, escalate and close per §5, with evidence, per agent thresholds and the central layering |
| R11 | Detectors are deterministic: replaying a recorded stream twice gives identical incidents; the order of unrelated agents' events does not matter |
| R12 | `RulesFile` parses the new rules, rejects bad ones with line and a suggestion, and `RulesCheck` reports unknown agents; defaults apply to rules left out |
| R13 | Old rule files, old snapshots and old traces load unchanged; new fields are optional on the wire |
| R14 | The OTLP export (`OtlpJson`) writes the new spans and logs with their attributes, valid and in order |
| R15 | A soak of 5,000 model calls and 5,000 tool calls through one `RunState` stays within a fixed memory bound and keeps totals exact |
| R16 | Incidents for the new kinds appear in Fabric's received runs without server changes (the server applies the same `RunState`) |
| R17 | No new runtime dependency |
| R18 | `ToolHealth` classifies per the table in §4b, with a reason, deterministically, at every boundary (2, 3, 4, 5 calls; 39 %, 40 %, 69 %, 70 %) |
| R19 | Each row of `view().tools` has `health` with state, reason, recent and overall rates, consecutive failures, last failure and last success, failures by category and the per-agent breakdown; the old fields are unchanged |
| R20 | `ToolSummary.merge` is associative and commutative on counts, categories, per-agent tallies and histograms; the merged state is the worst of the parts, and `failing` when the merged counts say it never succeeded |
| R21 | `RunRegistry.toolRollup()` equals the merge of every run's summaries, can be limited to one client, and lists failing tools first, then degraded, then by calls |
| R22 | A failed task or delegate emits one `failure` event: `kind` (task or delegate), `name`, `step`, `category` (the shared `ErrorCategory`), `error_type`, `attempts`, `handled` (true when `on_failure` took over) and the error text. It is emitted on every failure path (bad arguments, no approval, refused effect, replay of a recorded failure, retries used up), not only after retries |
| R23 | A failed `task_end` carries `category`, `error_type`, `attempts` and `millis`, so a task has the same failure vocabulary as a tool |
| R24 | When a run fails because of a task or delegate, `run_end` names it: `failed_kind`, `failed_name`, `failed_step`, `failed_category`; a run that fails otherwise has none of these. Old traces map as before |
| R25 | `RunState` keeps a health account per task (calls, successes, failure categories, latency, health verdict) in the same shape as a tool's, shown in the view as `tasks` and in `toolSummaries()` / the rollup with `kind` = `task` (a tool and a task with one name stay apart) |
| R26 | Two incidents: `BROKEN` (high; the run ended failed because of a named task or delegate; it stays open after the run ends) and `DIVERTED` (warn; a step failed and its `on_failure` took over; closes when the run ends). Both are replay-stable and carry the step, kind, name, category |
| R27 | The failure text follows the detail level (no raw error text at `none`), and a `failure` event adds no secrets |

## 8. Sub-phases

| Step | Delivers | Requirements |
|---|---|---|
| 4a | Mapper: authoritative tool outcome (hold and flush), `chat` spans, context and compaction logs, run totals; `Semconv` names | R1 to R7 |
| 4b | `RunState` statistics, percentile helper, `view()` and `summary()` fields | R8, R9, R15 |
| 4c | Detectors, incidents, thresholds, `.inspect` grammar and checks | R10 to R13 |
| 4d | OTLP export of the new records, Fabric end-to-end (a recorded run through the server), compatibility fixtures | R14, R16 |
| 4f | Task and delegate failures: `failure` event, enriched `task_end`, `run_end` attribution, task health, `BROKEN` and `DIVERTED` incidents | R22 to R27 |
| 4e | Documentation (observability pages, loom-inspect design, guide), example, verification run | all |

## 9. Decisions

| # | Question | Decision |
|---|---|---|
| 1 | Hold the tool span until its `tool_call`, or enrich later? | Hold (the span is emitted once, complete); flush rules in R3 |
| 2 | Model calls as spans or only logs? | Spans (`chat`), as the GenAI conventions define; the model span is a child of the agent span |
| 3 | Metrics (histograms) now? | No; spans and logs carry the numbers, aggregates are derived (Fabric, or a collector) |
| 4 | Wire compatibility for the extended `InspectRules` | New fields are nullable and optional in JSON; an older server ignores them; a newer server accepts older rules |
| 5 | Percentiles in the core module | A small own implementation (nearest-rank, bounded reservoir) because core depends on neither ai-agent4j nor Loom |
| 6 | Defaults of the five rules | As in §5; all overridable per agent and centrally; none changes a run's behaviour |

## 10. As built: notes by sub-phase

- **4a.** As §3, with these refinements. The `chat` span is parented to the workflow root like the tool spans (not the agent span), so every kind of step shares one parent. The held tool span is only used once the trace has shown a `model_call` or `context` event, so a trace from an older runtime maps exactly as before and tool spans are not delayed. A tool call that did not run (blocked repeat, unknown tool, rejected, approval unavailable, budget) closes with status unset and `loom.tool.no_result`, not as a failure. `ServerLink` now sends one request per kind of record in a batch (see the loom-inspect design §11n).
- **4b.** `RunState` keeps a `ModelStat` per answering model (a failed call without a model name is filed under `unknown`), extends the tool statistics (`successes`, `successRate`, `softFailures`, `failures`, `p50Ms`, `p95Ms`; the success rate counts only calls the runtime actually ran), and a `ContextStat` per agent (latest use, 60-sample series, compaction counts and the last compaction). A model span adds to the statistics only: it does not touch the steps table, the recent feed or the agents' token totals (those still come from the agent span), and `context` logs are not put in the recent feed (one per model call would drown it). An agent that has only reported context still appears in the agents list. Percentiles are a small package-private class (`Percentiles`). `RunState.finished` (the set of span ids already counted) still grows with every span, as before; the statistics structures are what is bounded.
- **4c.** `Thresholds` and `InspectRules` grew the telemetry rules without breaking the old constructors; `InspectRules.Telemetry` is a nested nullable record so an old value reads as before. `Detectors` keeps per-agent and per-tool state (truncations per step, compactions per model-call index, a window of tool outcomes, spike streaks) and is fed by `RunState` from the records. The duration of a call is judged against the median of the **earlier** calls, before it is added. A new `ToolTally` (mutable, per tool, per run) feeds `ToolSummary` and `ToolHealth` (spec §4b), exposed as `RunState.toolSummaries()`, `view().tools[].health` and `RunRegistry.toolRollup(client)`. Fabric's central rules form (`FabricConfig`) reads and writes the new rules (`context_nearly_full {warn, high}` in percent, `truncated_answers`, `compaction_thrash {count, of_calls}`, `tool_success_rate {below, over_calls}`, `latency_spike`) and shows their defaults, so the page of phase 5 only has to draw them. The incident store keeps its rule that a severity only goes up while an incident is open.
- **4d.** No change to the exporter was needed: the new records are ordinary spans and logs with typed attributes, and read back unchanged. The Fabric server needed no change either: it applies the same `RunState`, so context, models, tool health and the new incidents appear for a received run (`InspectContextEndToEndTest`).
- **4e.** The full run found that sending a batch's logs before its spans could show a run as finished while its last spans were still in flight; the link now sends the run-end record last. A JaCoCo rule gates the new core classes. The example is `src/examples/inspect-tools`.
