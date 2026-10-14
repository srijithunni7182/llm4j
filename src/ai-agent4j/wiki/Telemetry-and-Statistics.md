# Telemetry and statistics

When an agent runs, you want to know more than what it answered: **how long** each model call and each tool call took, **whether a tool really worked**,
**why** a call failed, how often, and how the numbers add up. This page is about the events and statistics ai-agent4j keeps for that, and about writing a
tool so that it is observed well.

> **Status: in progress.** The building blocks on this page work on their own: the failure vocabulary (`ErrorCategory`), `ToolException`, how a tool's
> text result is read as a failure, the event types, latency percentiles and `StatsCollector`. The agent does **not** produce `onModelCall` and
> `onToolCall` events yet; that is the next steps of the work and will be documented here as it lands. The design is in
> [`docs/specs/02-call-telemetry`](../../../docs/specs/02-call-telemetry/DESIGN.md).

Everything here is opt-in and changes nothing for code that does not use it. Events and statistics hold **numbers and names only**: never a prompt, an
answer, a tool's arguments or its result.

---

## Contents

1. [One vocabulary for failures](#1-one-vocabulary-for-failures)
2. [Writing a tool that is observed well](#2-writing-a-tool-that-is-observed-well)
3. [The events](#3-the-events)
4. [Statistics](#4-statistics)
5. [What is coming](#5-what-is-coming)

---

## 1. One vocabulary for failures

`ErrorCategory` names why something failed, the same way for every provider and every tool, so failures can be grouped and compared:

`INVALID_ARGUMENTS`, `TIMEOUT`, `NETWORK`, `RATE_LIMITED`, `AUTHENTICATION`, `PERMISSION_DENIED`, `NOT_FOUND`, `CLIENT_ERROR`, `SERVER_ERROR`,
`CONTENT_BLOCKED`, `CONTEXT_OVERFLOW`, `BUDGET`, `REJECTED_BY_HUMAN`, `REPEAT_BLOCKED`, `UNKNOWN_TOOL`, `APPROVAL_UNAVAILABLE`, `OTHER`.

```java
ErrorCategory a = ErrorCategory.of(new SocketTimeoutException("Read timed out"));              // TIMEOUT
ErrorCategory b = ErrorCategory.of(new IllegalStateException("backend", new IOException()));   // NETWORK: found in the cause
ErrorCategory c = ErrorCategory.ofStatus(429);                                                 // RATE_LIMITED
```

`ErrorCategory.of(Throwable)` looks at the exception first (the library's own exceptions say what they are; `ContextOverflowException` is
`CONTEXT_OVERFLOW`, not just a bad request), then at its **cause chain** to a depth of five, so an exception wrapped by a framework is still
recognised. Timeouts and connection failures are told apart; an HTTP status is read the same way everywhere. Anything unrecognised is `OTHER`. Treat the
set as extensible: code that reads a category should tolerate values added later.

## 2. Writing a tool that is observed well

A tool does nothing to be observed: a tool that throws is classified by the exception's type. Two optional things make it better:

```java
/** A tool that says why it failed, and how to read its own results. */
static final class LookupTool implements Tool {
    @Override public String getName() { return "lookup"; }
    @Override public String getDescription() { return "looks a record up by id"; }

    @Override
    public String execute(Map<String, Object> args) throws Exception {
        Object id = args.get("id");
        if (id == null) throw new ToolException(ErrorCategory.INVALID_ARGUMENTS, "the 'id' argument is missing");
        if ("42".equals(String.valueOf(id))) return "ERR 42: no record";                  // an error returned as text, in this tool's own style
        return "record " + id;
    }

    @Override
    public boolean isFailure(String result) {
        return result.startsWith("ERR ");                                                  // the default knows "Error: …", "Failed to …", HTTP statuses
    }
}
```

- **`ToolException(category, message)`** says why in the shared vocabulary, whatever the underlying exception was.
- **A tool that returns its errors as text.** Many tools return `"Error: …"` instead of throwing, so a normal return does not mean success. The default
  `Tool.isFailure(result)` recognises the common wording: a result that begins with `Error`, `Failed to …`, `Failure:`, `Exception in …`,
  `Tool error`, or an HTTP error status. Such a call is reported as a **soft failure** (the tool returned normally, its result says it failed) with a
  category read from the wording. Override `isFailure` when your tool's results have a shape of their own. The rule is a heuristic, which is why it is
  flagged as soft: it only affects events and statistics, never what the model is told.

```java
boolean failed = io.github.llm4j.agent.ToolResults.looksLikeFailure("Error: Endpoint 'GET /x' not found in API specification");   // true
ErrorCategory why = io.github.llm4j.agent.ToolResults.categoryOf("Error: Endpoint 'GET /x' not found in API specification");        // NOT_FOUND
```

The wording rule is conservative on purpose: `No errors found`, `The error rate is 3%`, `Errors: 0` and `Failure analysis: …` are not failures.

## 3. The events

Two immutable records, each built with a builder, hold what is known about a call. They are the types the listener methods will receive.

| `ModelCallEvent` | |
|---|---|
| `iteration`, `requestedModel`, `model` | which call, what was asked for, what answered |
| `durationMillis`, `waitedMillis`, `processingMillis()` | wall time, time spent waiting between attempts, and the difference |
| `attempts`, `retries()`, `failovers` | requests sent, attempts beyond the first, clients that failed before one answered |
| `promptTokens`, `completionTokens`, `totalTokens`, `usageEstimated`, `cost` | usage |
| `finishReason`, `truncated()`, `toolCallsRequested` | how the answer ended |
| `outcome`, `category`, `errorType` | `OK` or `FAILED`, and why |

| `ToolCallEvent` | |
|---|---|
| `iteration`, `tool` | which call |
| `durationMillis`, `approvalMillis` | the tool's own run, and a person's time to approve |
| `outcome` (`ToolOutcome`), `softFailure`, `failed()`, `ran()` | what happened to the call |
| `category`, `errorType` | why it failed, and the exception's class if it threw |
| `argumentChars`, `resultChars` | sizes, not content |

## 4. Statistics

`StatsCollector` is a listener that adds events up, across runs and threads. One collector may be shared by many agents.

```java
StatsCollector stats = new StatsCollector();
stats.onToolCall(ToolCallEvent.builder().tool("search").durationMillis(120).resultChars(800).build());
stats.onToolCall(ToolCallEvent.builder().tool("search").durationMillis(90).softFailure(ErrorCategory.NOT_FOUND).build());
stats.onToolCall(ToolCallEvent.builder().tool("search").durationMillis(2_000)
        .outcome(ToolOutcome.EXECUTION_ERROR, ErrorCategory.TIMEOUT, "SocketTimeoutException").build());

RunStats now = stats.snapshot();
double rate = now.tool("search").successRate().orElseThrow();                 // 1 of 3
long p95 = now.tool("search").latency().p95();                                // 2000 ms
Map<ErrorCategory, Long> why = now.tool("search").failures();                 // NOT_FOUND=1, TIMEOUT=1
```

- **Per tool:** calls, successes, failures by category (soft failures included), result size, and latency (`count`, `min`, `max`, `mean`, `p50`, `p90`,
  `p95`, `p99`). Latency counts only the calls that reached the tool's own code, so a call blocked as a repeat does not pull the numbers down.
- **Per model:** calls (failed ones included), failures by category, tokens, cost where reported, retries, fail-overs, time spent waiting, how answers
  ended, answers cut off by the token limit, and latency.
- **A tool never called** gives a result with no calls and an *empty* success rate, never `NaN`.
- **Percentiles** use the nearest-rank method. They are exact up to 10,000 samples per tool or model; beyond that a deterministic reservoir keeps memory
  bounded while the count, minimum, maximum and mean stay exact.
- `snapshot()` is a consistent copy and `reset()` starts again.

## 5. What is coming

In this order, each with its own tests and documentation:

1. The agent produces `onToolCall` events and keeps the statistics, in both loops, and `AgentResult.getStats()`.
2. The agent produces `onModelCall` events: duration, usage, finish reason, failures.
3. Retries, rate-limit waits and fail-overs inside the HTTP and routing layers show up in the model-call event.

See also: [Context and Compaction](Context-and-Compaction.md) · [Budgets and Rate Limits](Budgets-and-Rate-Limits.md) ·
[ReAct Agent Guide](ReAct-Agent-Guide.md)
