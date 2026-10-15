# Phase 2 design: call-level telemetry (ai-agent4j)

Status: **approved; decisions in §9**. Layer: ai-agent4j (the core). Nothing in this phase knows about Loom, Fabric or OpenTelemetry.
Companion documents: [TEST_STRATEGY.md](TEST_STRATEGY.md), [VERIFICATION.md](VERIFICATION.md), [roadmap](../README.md). Builds on
[Phase 1](../01-context-management/DESIGN.md) (it reuses the listener, the usage types and the error classification).

## 1. Problem

When an agent runs, the layers above can only see what the agent chooses to say: a thought, an action, an observation as text, a budget
event, and (since Phase 1) context use and compactions. They cannot tell, for a **model call**, how long it took, how many attempts it needed,
whether it waited out a rate limit, whether a fallback provider answered, or why it ended (`stop`, cut off by `length`, a tool request). For a
**tool call** they cannot tell how long it took, whether it really succeeded, or why it failed. Loom works around the second gap today by
reading the observation's text for the word "error" (`TraceMapper.ERROR_TEXT`).

What exists today, and what is missing:

| Exists | Missing |
|---|---|
| `LLMResponse`: model, token usage, finish reason, tool calls, cost in metadata | Latency; attempts; time spent waiting; which provider failed over to which |
| `AgentResult.StepOutcome` per step (`EXECUTED`, `EXECUTION_ERROR`, `UNKNOWN_TOOL`, …) | The exception type and a **category** of the failure; the duration; result size |
| `isFailure(outcome)` in the confidence score | Tools that **return** `"Error: …"` instead of throwing count as `EXECUTED`; nothing says they failed |
| Retries, 429 waits in `HttpClientWrapper.send`; fail-over in `RoutingLLMClient` | Any way for the agent (or its listener) to see them |
| `AgentEventListener` with `onThought/onAction/onObservation/onBudget/onContextUsage/onCompaction` | `onModelCall`, `onToolCall` |
| `AgentResult.Usage` totals | Per-tool and per-model **statistics**: success rate, latency percentiles, failures by category |

## 2. Goals and non-goals

**Goals**

- G1. Every model call and every tool call of an agent run produces **one event** with the facts above, delivered to the agent's listeners.
- G2. A run's result carries **statistics** (`RunStats`): per tool and per model, calls, failures by category, and latency percentiles.
- G3. A tool's **real** outcome is known: an exception, a refusal, a block, or a *soft failure* (a tool that returns an error as text).
- G4. Failures have a **category** that is the same across providers and tools (timeout, network, rate limit, invalid arguments, not found, …).
- G5. Retries, rate-limit waits and fail-overs inside the HTTP and routing layers are visible to the agent **without changing any signature** of
  `LLMClient`, `LLMProvider` or `Tool`.
- G6. A ready-made, thread-safe `StatsCollector` listener aggregates across runs (a daemon, a service) with no code from the developer.
- G7. Opt-in and safe: nothing about what is sent to a model or what a tool receives changes; events are default no-ops; a listener that throws
  cannot fail a run.

**Non-goals** (later phases or separate decisions)

- Mapping to Loom `TraceEvent`s, OpenTelemetry records or Fabric (phases 3–5). This phase only provides the events and statistics.
- Changing agent behaviour in response to what is observed (no automatic retries, no tool disabling).
- Per-call detail for **summariser** calls (they stay one `CompactionUsage`, as in Phase 1) and for streaming calls (the agent does not stream).
- Text of prompts and answers in events (the existing `onThought/onAction/onObservation` carry text; the new events carry **numbers and names only**,
  which keeps them safe at every detail level above).
- A metrics registry or exporter. `RunStats` and `StatsCollector` are plain Java objects; exporting is for the layers above.

## 3. The public API (what a Java developer writes)

New types are in `io.github.llm4j.telemetry`; the tool-side additions are in `io.github.llm4j.agent`.

### 3.1 Listening

`AgentEventListener` gains two default (no-op) methods, so existing listeners compile unchanged:

```java
default void onModelCall(ModelCallEvent event) {}     // after each model call, successful or not
default void onToolCall(ToolCallEvent event) {}       // after each tool call, whatever happened to it
```

```java
agent = ReActAgent.builder()....addListener(new AgentEventListener() {
    @Override public void onToolCall(ToolCallEvent e) {
        if (e.failed()) log.warn("{} failed ({}) after {} ms", e.tool(), e.category(), e.durationMillis());
    }
    ...
}).build();
```

### 3.2 Statistics

```java
AgentResult r = agent.run(question);
RunStats stats = r.getStats();
stats.tool("search").successRate();              // 0.0 – 1.0, or empty when never called
stats.tool("search").latency().p95();            // milliseconds
stats.tool("search").failures();                 // Map<ErrorCategory, Long>
stats.models().get("gemini-2.5-flash").latency().p50();
stats.totalRetries();  stats.totalFailovers();  stats.truncatedAnswers();
```

Across runs, add the collector once:

```java
StatsCollector stats = new StatsCollector();
agent = ReActAgent.builder()....addListener(stats).build();
...
RunStats now = stats.snapshot();                  // consistent copy; the collector keeps counting
stats.reset();
```

### 3.3 Making a tool say what happened

A tool does nothing to be observed. To be observed **well**, it can:

```java
throw new ToolException(ErrorCategory.TIMEOUT, "the search backend did not answer in 10 s");    // an explicit category
```

and, for a tool that returns its errors as text, override how a result is read:

```java
@Override public boolean isFailure(String result) { return result.startsWith("ERR "); }          // default: the common "Error: …" shapes
```

Both are optional; without them a thrown exception is classified by its type and a returned error is recognised by its usual wording (§4.3).

## 4. Types and behaviour

### 4.1 `ErrorCategory`

One enum for model and tool failures, so a dashboard can group them: `INVALID_ARGUMENTS`, `TIMEOUT`, `NETWORK`, `RATE_LIMITED`, `AUTHENTICATION`,
`PERMISSION_DENIED`, `NOT_FOUND`, `CLIENT_ERROR`, `SERVER_ERROR`, `CONTENT_BLOCKED`, `CONTEXT_OVERFLOW`, `BUDGET`, `REJECTED_BY_HUMAN`,
`REPEAT_BLOCKED`, `UNKNOWN_TOOL`, `APPROVAL_UNAVAILABLE`, `OTHER`.

`ErrorCategory.of(Throwable)` classifies an exception:

| Exception | Category |
|---|---|
| `ToolException` | its own category |
| `ContextOverflowException` (checked before its parent) | `CONTEXT_OVERFLOW` |
| `RateLimitException`, `RateLimited` | `RATE_LIMITED` |
| `AuthenticationException` | `AUTHENTICATION` |
| `ContentBlockedException` | `CONTENT_BLOCKED` |
| `BudgetExceeded` | `BUDGET` |
| `InvalidRequestException` (other) | by status: 404 → `NOT_FOUND`, 413/422/400 → `INVALID_ARGUMENTS`, else `CLIENT_ERROR` |
| `ServiceUnavailableException`, `ProviderException` with 5xx | `SERVER_ERROR` |
| `java.net.SocketTimeoutException`, `java.util.concurrent.TimeoutException`, `java.net.http.HttpTimeoutException`, `InterruptedIOException` with "timed out" | `TIMEOUT` |
| `java.net.ConnectException`, `UnknownHostException`, other `IOException` | `NETWORK` |
| `IllegalArgumentException`, `JsonProcessingException`, `NumberFormatException`, `NullPointerException` from the arguments | `INVALID_ARGUMENTS` |
| `FileNotFoundException`, `NoSuchFileException` | `NOT_FOUND` |
| `SecurityException`, `AccessDeniedException` | `PERMISSION_DENIED` |
| anything else (the **cause** chain is tried first, to a depth of 5) | `OTHER` |

### 4.2 Events (immutable `record`s, numbers and names only)

**`ModelCallEvent`**: `iteration` (0-based), `requestedModel` (from the request, nullable), `model` (the one that answered, nullable on failure),
`startedAt`, `durationMillis` (wall time, including waits), `waitedMillis` (time spent sleeping between attempts), `attempts` (≥ 1), `failovers`
(`List<String>`: the clients that failed before one answered, in order), `promptTokens`, `completionTokens`, `totalTokens`, `usageEstimated`, `cost`
(nullable `BigDecimal`), `finishReason` (nullable), `toolCallsRequested`, `outcome` (`OK` or `FAILED`), `category` (nullable, set when `FAILED`),
`errorType` (exception simple name, nullable). `truncated()` is `finishReason == LENGTH`. `processingMillis()` is `durationMillis − waitedMillis`.

**`ToolCallEvent`**: `iteration`, `tool`, `startedAt`, `durationMillis` (the tool's `execute` only; a human approval wait is `approvalMillis`),
`approvalMillis`, `outcome` (`AgentResult.StepOutcome`), `softFailure` (the tool **returned** an error), `category` (nullable), `errorType`
(nullable), `argumentChars`, `resultChars`. `failed()` is `outcome != EXECUTED || softFailure`. Blocked, unknown and rejected calls get an event
too (duration 0), because they matter to the loop's health.

An event contains **no prompt, answer, argument or result text**.

### 4.3 Reading a tool's result as a failure

`Tool.isFailure(String result)` (a default method, `false`-safe on null) returns `ToolResults.looksLikeFailure(result)`: the result begins
(after trimming, case-insensitively) with `error`, `failed`, `failure`, `exception`, `tool error`, or an HTTP status line / bare `4xx`/`5xx`.
This is the rule Loom's mapper uses today, moved to the layer that knows the tools. A match sets `softFailure` and the category is read from the
text (`ToolResults.categoryOf`): `not found`/`404` → `NOT_FOUND`; `timed out`/`timeout` → `TIMEOUT`; `401`/`403`/`unauthor` → `PERMISSION_DENIED`;
`429`/`rate limit` → `RATE_LIMITED`; other `4xx` → `CLIENT_ERROR`; `5xx` → `SERVER_ERROR`; `missing`/`invalid`/`must be`/`no ... provided` →
`INVALID_ARGUMENTS`; else `OTHER`. It is a heuristic and the event says so (`softFailure`); a tool that knows better overrides `isFailure` or throws
`ToolException`.

### 4.4 Seeing inside the HTTP and routing layers: `CallTrace`

`HttpClientWrapper.send` retries and sleeps; `RoutingLLMClient` fails over. Neither can tell the agent, and no signature should change. A small
`CallTrace` (package `io.github.llm4j.telemetry`, public) is a **thread-local stack of recorders**:

- the agent calls `CallTrace.begin()` before `llmClient.chat(request)` and `end()` after (in a `try/finally`), getting the recorder's totals;
- the HTTP layer calls `CallTrace.attempt()` for each request it sends and `CallTrace.waited(Duration)` for each sleep;
- the routing client calls `CallTrace.failover(name)` when it moves on from a client that failed;
- with no `begin()` in force these calls do nothing, so a library user who never uses the agent pays nothing;
- the stack makes nesting correct: a tool that itself runs an agent on the same thread gets its own recorder and cannot disturb the outer one.

Calls made on **other threads** (a tool that fans work out) are not attributed; that is documented, and the numbers for those calls are simply absent
rather than wrong.

### 4.5 When events are produced

*Model call:* in each loop, around the single `llmClient.chat` call (including the reactive retry of Phase 1, which is a second call and a second
event). *Tool call:* in `executeAction`, around the approval gate and the `tool.execute`. Events are delivered **before** the next step, in order, on the
agent's thread. A listener that throws is logged and ignored (as for every other callback).

### 4.6 Statistics

`RunStats` is an immutable snapshot: `models()` (`Map<String, ModelStats>`), `tools()` (`Map<String, ToolStats>`), and totals.

- `ModelStats`: `calls`, `failures` (`Map<ErrorCategory, Long>`), `promptTokens`, `completionTokens`, `cost`, `retries` (attempts − 1), `failovers`,
  `waitedMillis`, `finishReasons` (`Map<FinishReason, Long>`), `truncated`, `latency` (`LatencyStats`).
- `ToolStats`: `calls`, `successes`, `failures` (`Map<ErrorCategory, Long>`, soft failures included), `softFailures`, `successRate()`, `resultChars`
  total, `latency`.
- `LatencyStats`: `count`, `min`, `max`, `mean`, `p50`, `p90`, `p95`, `p99` in milliseconds. Percentiles use the **nearest-rank** method on all
  samples up to 10,000; beyond that a deterministic reservoir of 10,000, so memory is bounded and a long-lived collector does not grow.

`StatsCollector implements AgentEventListener`, thread-safe, cumulative; `snapshot()` returns a consistent `RunStats`, `reset()` clears it. Each
`AgentResult` also carries its own run's `RunStats` (always; the cost is a few small objects per call).

## 5. Requirements (testable)

| Id | Requirement |
|---|---|
| R1 | `onModelCall` fires once per model call, in both loops, including the retry after a context overflow, and for a call that fails |
| R2 | `ModelCallEvent` carries the tokens, cost and finish reason of the response, the requested and answering model, the iteration, and `toolCallsRequested` |
| R3 | `durationMillis` measures the wall time of the call; with an injected clock the value is exact; `waitedMillis ≤ durationMillis` |
| R4 | A failed model call produces an event with `outcome FAILED`, the category and the exception type, and the exception still propagates as before |
| R5 | `ModelCallEvent.truncated()` is true exactly when the finish reason is `LENGTH` |
| R6 | `onToolCall` fires once per tool call, in both loops, for executed, failed, unknown, duplicate-blocked, approval-unavailable and rejected calls |
| R7 | `ToolCallEvent` carries the tool name, outcome, duration of `execute` alone (approval wait separate), argument and result sizes, and no text |
| R8 | A tool that throws is classified by `ErrorCategory.of`; a `ToolException` carries its explicit category; the cause chain is searched |
| R9 | A tool that returns an error as text is a soft failure (`failed()`), with a category read from the text; `Tool.isFailure` overrides the rule |
| R10 | `ErrorCategory.of` maps every exception in §4.1 as tabulated, and the model-side exceptions the same way |
| R11 | `HttpClientWrapper` reports each attempt and each sleep to the active `CallTrace`; the event's `attempts` and `waitedMillis` match a scripted sequence of failures |
| R12 | `RoutingLLMClient` reports each failed client to the active `CallTrace`; the event's `failovers` lists them in order |
| R13 | With no `CallTrace` in force the HTTP and routing layers behave exactly as before and allocate nothing for it |
| R14 | `CallTrace` nests: an agent run inside a tool call does not disturb the outer call's counts; `end()` always pops, even after an exception |
| R15 | `RunStats` totals equal the events: calls, failures by category, tokens, retries, failovers, truncations, tool successes |
| R16 | `LatencyStats` percentiles are nearest-rank and exact up to 10,000 samples; beyond that memory stays bounded and results stay within the reservoir's bounds |
| R17 | `StatsCollector` is thread-safe: concurrent agents sharing one give totals equal to the sum; `snapshot()` is consistent; `reset()` clears |
| R18 | `AgentResult.getStats()` is present for every result, including failures and budget exhaustion |
| R19 | Requests sent to the model and arguments given to tools are identical with and without listeners (golden requests from Phase 1 still pass unchanged) |
| R20 | Existing `AgentEventListener` implementations compile and behave unchanged |
| R21 | A listener that throws from either callback does not fail the run |
| R22 | No new runtime dependency |
| R23 | The summariser's calls are not counted as model calls in `RunStats` (they stay in `CompactionUsage`), and this is documented |

## 6. Sub-phases

Each ends with its tests passing **and its documents updated** (see [the rule](../README.md#documents-to-update-at-the-end-of-every-sub-phase)), and
is a separate commit.

| Step | Delivers | Requirements |
|---|---|---|
| 2a | `ErrorCategory` and its classifier, `ToolException`, `ToolResults` and `Tool.isFailure`, the event records, `LatencyStats`, `RunStats`, `StatsCollector` (all pure, no agent change) | R8 (type part), R9 (rule part), R10, R15 to R17 |
| 2b | Tool-call events and statistics in both loops (`executeAction`), an injectable clock, `AgentResult.getStats()` | R6, R7, R8, R9, R18, R19 to R21 |
| 2c | Model-call events and statistics in both loops, including failures and the overflow retry | R1 to R5, R18, R23 |
| 2d | `CallTrace`, hooks in `HttpClientWrapper` and `RoutingLLMClient`, attempts, waits and fail-overs in `ModelCallEvent` and `ModelStats` | R11 to R14 |
| 2e | Documentation, a runnable example, the verification run | all |

## 7. Compatibility

- Public API only grows: new methods are `default`; `AgentResult` gains an accessor; `Tool` gains a default method.
- A tool that today returns `"Error: …"` is now counted as failed **in the statistics and events**; `AgentResult.StepOutcome` for that step stays `EXECUTED`
  (changing it would alter confidence scoring and existing callers). The difference is documented.
- Behaviour with no new listener is unchanged (R19–R21), pinned by the existing golden requests.

## 8. Risks

| Risk | Mitigation |
|---|---|
| The soft-failure heuristic mislabels a result that merely begins with "Error" | It only affects statistics and events, is flagged `softFailure`, and is overridable per tool; the same rule already drives Loom today |
| A thread-local leaks across calls | `begin/end` in `try/finally`; a test that throws between them; the stack is empty after every call (asserted in tests) |
| Timing in tests is flaky | An injectable `Clock`/`LongSupplier`; no test depends on real elapsed time |
| Memory growth in a long-lived collector | Reservoir sampling for latencies; counters are fixed-size maps keyed by category/model/tool |
| Event volume or cost | Events are small records; statistics are a few counters; delivery is skipped when no listener is attached (the result's statistics are still kept) |
| Layers above treating categories as stable API | The enum is documented as extensible: consumers must tolerate unknown values |

## 9. Decisions (settled at review)

| # | Question | Decision |
|---|---|---|
| 1 | Detect a tool's soft failure by a heuristic by default? | Yes, in `Tool.isFailure`'s default, overridable per tool (§4.3) |
| 2 | Carry retries and fail-overs by a thread-local `CallTrace` or by changing `LLMClient`? | The thread-local (§4.4): no signature changes, no cost when unused |
| 3 | Does `StepOutcome` change for a soft failure? | No (§7); the event and statistics carry it |
| 4 | Where do the types live? | `io.github.llm4j.telemetry` for events, statistics, categories and `CallTrace`; `ToolException`, `ToolResults` and `Tool.isFailure` with `Tool` in `io.github.llm4j.agent`. **As built:** `ToolCallEvent` uses its own `ToolOutcome` enum (the same constants as `StepOutcome`, converted by the agent) and `StatsCollector` sits beside `AgentEventListener` in the agent package, so that `telemetry` does not depend on the agent and the two packages do not depend on each other |
| 5 | Latency percentile method | Nearest-rank, exact to 10,000 samples, then a deterministic reservoir |
| 6 | Event content | Numbers and names only; never prompt, answer, argument or result text |

## 10. As built: notes by sub-phase

- **2a.** The soft-failure rule is stricter than the first sketch because the negative cases demanded it: a leading `error`, `failed`, `failure`, `exception` or `tool error`
  counts only when followed by the end, a colon or dash, or a verb that introduces a failure (`to`, `in`, `while`, `executing`, `with`, `status`, …), so `Failure analysis: …`
  and `Errors: 0` are not failures; a bare 4xx/5xx status counts unless it is followed by a noun that makes it a count (`500 requests`, `404 results`). Categories read from the
  text map 401/403 and "unauthorized" to `PERMISSION_DENIED` (the tool-side name), while `ErrorCategory.ofStatus(401)` is `AUTHENTICATION` (the model-side name). A Jackson
  `JsonProcessingException` is `INVALID_ARGUMENTS` although it is an `IOException`. The cause chain is searched to depth 5 with a cycle guard; the outermost exception that says
  something wins. `LatencyRecorder` keeps a deterministic reservoir (Algorithm R) past 10,000 samples and exposes `retained()` for the bound. Per-tool latency counts only calls
  that reached the tool's code (`ToolOutcome.ran()`), per-model latency counts every call, failed ones included. `StatsAccumulator` is the shared, thread-safe adder behind both
  `StatsCollector` and (from 2b) `AgentResult.getStats()`.
- **2b.** `ReActAgent.run` keeps one `RunTelemetry` (a `StatsAccumulator` plus the notifiers) for the run, passes it to both loops, and puts its snapshot on the result with
  `toBuilder().stats(...)`, so every result carries statistics, including a failed or budget-exhausted one (an exception that propagates produces no result and so none). `executeAction`
  became a thin wrapper: it records the start, calls `performAction` (the old body, now also timing the approval wait and the `execute` call with the agent's `Clock`), and builds one
  `ToolCallEvent`. A deliberate `AgentInterrupt` is rethrown before any event. `StepOutcome` is mapped to `ToolOutcome` by an exhaustive `switch` with no default, so adding an outcome to one
  enum cannot compile without deciding the other. A tool's `isFailure` that throws is logged and treated as "not a failure". The text a tool or the agent returns to the model is untouched, which the
  golden requests of Phase 1 and two new equality tests confirm. The `Clock` is a builder option (`ReActAgent.Builder.clock`), carried through `toBuilder()`.
- **2c.** `ReActAgent.chatObserved` wraps all four `llmClient.chat` call sites (native and text loops, and the retry after a context overflow, which is a second call and so a second event in the
  same iteration). It times the call with the agent's `Clock`, reads model, finish reason, tool-call count, tokens, the estimated-usage flag and cost from the response, and on a `RuntimeException`
  records a failed event (category from `ErrorCategory.of`, the exception's simple class name) and rethrows it unchanged. A failed call has no answering model, so the statistics file it under
  `unknown`; `requestedModel` is null for requests the agent builds itself. The summariser has its own client: its tokens stay in `CompactionUsage` and `AgentResult.Usage`, not in `RunStats`.
  `attempts`, `waitedMillis` and `failovers` read 1, 0 and empty until 2d.
- **2d.** `CallTrace` is a public final class with a `ThreadLocal<Deque<Recorder>>`; `begin()`/`end()` return a `Totals` record (`attempts` at least 1, `waitedMillis`, `failovers`; `Totals.NOTHING` when
  nothing was in force). `HttpClientWrapper.send` calls `attempt()` before each request and `waited(backoff)` after each sleep; `RoutingLLMClient.chat` calls `failover(simpleName)` when a client fails
  (streaming is not traced: the agent does not stream). `chatObserved` opens a recorder around each model call, closes it on every path (an `Error` too), and clamps `waitedMillis` to the
  measured duration so a frozen test clock cannot produce an invalid event. The thread-local is removed when the stack empties, so a pooled thread keeps nothing.
- **2e.** `StatsSoakTest` (a thousand tool and model calls through one collector, reservoir bounded) and `StatsConcurrencyTest` (64 agents on a pool sharing one `StatsCollector`; recorders on 16 threads do not mix),
  the `mutation-telemetry` profile, and the example `src/examples/call-telemetry`. The example showed that an anonymous client has an empty simple name, so `RoutingLLMClient` now falls back to the binary class name
  for the fail-over list (test first).
