# Phase 2 test strategy: call-level telemetry

Status: **draft for review**. Requirements (`R1`…`R23`) are defined in [DESIGN.md](DESIGN.md) §5; the traceability matrix in
[VERIFICATION.md](VERIFICATION.md) maps each to the tests named here.

## 1. Principles

- **Tests first** for each sub-phase (2a–2e), failing for the right reason.
- **No network, no keys, no sleeping, no real elapsed time.** Models and tools are scripted; time comes from an injected clock; the HTTP layer is
  tested against `MockWebServer` (already used by the provider contract tests) with a recording `Sleeper`.
- **Totals are checked against the events they came from**, not against numbers typed into the test twice.
- **Behaviour that must not change is pinned**: the golden requests recorded in Phase 1 must still pass byte for byte (R19).
- Tests live beside the code in `src/ai-agent4j/src/test/java/io/github/llm4j/telemetry/` (and `agent/`, `http/`, `routing/` for the integration
  points). JUnit 5 and AssertJ as in use; **no new test dependency**.

## 2. Test levels

| Level | What | Examples | Requirements |
|---|---|---|---|
| **Unit, pure** | `ErrorCategory.of` over a table of exceptions (and cause chains); `ToolResults` over a table of result texts; `LatencyStats`; `RunStats`; `StatsCollector` | `ErrorCategoryTest`, `ToolResultsTest`, `LatencyStatsTest`, `RunStatsTest`, `StatsCollectorTest` | R8 (type), R9 (rule), R10, R15, R16, R17 |
| **Loop integration** | A real `ReActAgent` with a scripted model, scripted tools and a fake clock, in both loops; assertions on the events and on `getStats()` | `ToolCallEventsTest`, `ModelCallEventsTest`, `RunStatsIntegrationTest` | R1–R9, R15, R18, R21, R23 |
| **Fault injection** | Tools that throw each exception type, return errors as text, hang past a deadline (fake clock), a `ToolException`; models that throw each `LLMException` subtype; listeners that throw | `TelemetryFaultTest` | R4, R8, R9, R21 |
| **HTTP layer** | `MockWebServer` scripted with 500, 503, 429 (`Retry-After`), a dropped connection, then success; a recording sleeper; the `CallTrace` read back | `HttpCallTraceTest` | R11, R13, R14 |
| **Routing** | A `RoutingLLMClient` with clients that fail in order, then one that answers; the trace read back | `RoutingCallTraceTest` | R12 |
| **Trace discipline** | `begin/end` pairs, nesting, an exception between them, many threads each with their own stack, the stack empty at the end | `CallTraceTest` | R13, R14 |
| **Concurrency** | Many agents on a pool sharing one `StatsCollector`; the totals equal the sum of the per-run stats | `StatsConcurrencyTest` | R17 |
| **Compatibility** | The Phase 1 golden requests, unchanged; an old-style listener (only the original methods) compiles; a listener-attached run sends the same requests as one without | `RequestStabilityTest` (existing), `TelemetryCompatibilityTest` | R19, R20 |
| **Soak** | 1,000 tool calls and 1,000 model calls through one collector: bounded memory (the reservoir), totals exact | `StatsSoakTest` | R16, R17 |

## 3. Points to cover

**`ErrorCategory.of`:** every row of DESIGN §4.1, each with a direct instance and wrapped in a `RuntimeException`/`ExecutionException`; a cycle in the
cause chain; a null message; `ContextOverflowException` is `CONTEXT_OVERFLOW` and not `INVALID_ARGUMENTS`; 404 versus 400 versus 429; a `ProviderException`
with and without a status.

**`ToolResults`:** positives (`Error: x`, `error`, `ERROR`, `Failed to read`, `Exception in thread`, `Tool error: …`, `HTTP/1.1 503 Service Unavailable`,
`404`, `500 Internal`) and **negatives that must not match** (`No errors found`, `The error rate is 3%`, `Result: error handling guide`, `Errors: 0`,
`Errorless`, an empty string, null, a long result that merely contains "error" later). Categories for each text family, and `OTHER` as the default.

**`LatencyStats`:** nearest-rank on 1, 2, 5, 10, 100 samples against hand-computed values; ties; unsorted input; zero samples (empty, not zero); the
reservoir beyond 10,000 keeps count and min/max exact and percentiles within a stated tolerance on a known distribution; a fixed seed makes it deterministic.

**Tool events (both loops):** a tool that succeeds (duration from the fake clock, sizes); throws `IllegalArgumentException` (`INVALID_ARGUMENTS`),
`SocketTimeoutException` (`TIMEOUT`), `ToolException(NOT_FOUND)` (explicit wins over the type); returns `"Error: not found"` (soft, `NOT_FOUND`); an
overridden `isFailure`; an unknown tool; a duplicate action; a tool needing approval with no callback; one a human rejects (approval time separate
from the execute time, using the fake clock inside the callback); a budget interrupt (`AgentInterrupt` still propagates and produces no tool event).

**Model events (both loops):** success with usage and a cost in the metadata; `LENGTH` finish (`truncated`); native tool calls
(`toolCallsRequested`); estimated usage; each exception type from the model (category and type, then propagation); the Phase 1 overflow retry gives two
events (the first `FAILED`/`CONTEXT_OVERFLOW`, the second `OK`); a rate limit surfaced as `RateLimited`; `requestedModel` from the request and `model` from
the response, which may differ.

**`CallTrace` with the HTTP layer:** success on the first attempt (attempts 1, waited 0); two 503s then 200 (attempts 3, waited = the two backoffs); a 429
with `Retry-After: 2` waited out inline (waited ≈ 2 s of fake time); a 429 too long to wait (no sleep, `RATE_LIMITED`); an `IOException` then success;
exhausted retries (attempts = max + 1, failed). With no trace active the same sequences behave identically and `CallTrace` allocates nothing (asserted
by the stack depth staying 0).

**Routing:** primary fails, secondary answers (`failovers == ["primary"]`); two fail, the third answers (order kept); all fail (the event is `FAILED`
and the exception is the router's, with the failed clients still listed).

**Statistics:** after a scripted run the totals equal the sums over the events; success rate with 0 calls is empty, not NaN; failures by category sum to
the failed count; truncated answers; retries = attempts − 1 summed; the same events fed to a `StatsCollector` and to the result's stats agree.

## 4. What is deliberately not tested here

- Real provider behaviour for retries and rate limits (covered by the existing provider tests; live checks are in VERIFICATION §4).
- Wall-clock accuracy of `durationMillis` against a real clock (a live sanity check only).
- Anything about how the layers above present these numbers.

## 5. Quality gates (also in VERIFICATION.md)

- Coverage of `io.github.llm4j.telemetry` ≥ 90 % line, ≥ 80 % branch, enforced in the module's build like the Phase 1 gate.
- Mutation run (PIT, a `mutation-telemetry` profile) with survivors reviewed and justified.
- All existing ai-agent4j and Loom tests pass unchanged.
- Generated-input tests (the classifier tables, the percentile cases) run over several seeds where randomness is used.
