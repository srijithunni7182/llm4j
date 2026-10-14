# Phase 2 verification plan: call-level telemetry

Status: **draft for review**. Run top to bottom at the end of sub-phase 2e, and the relevant rows after each sub-phase. Results are recorded in §7.

## 1. Acceptance criteria

| Id | A developer can… / the system does… | Evidence |
|---|---|---|
| A1 | Add a listener and receive one event per model call and one per tool call, in both loops | `ModelCallEventsTest`, `ToolCallEventsTest` |
| A2 | Read a run's statistics from `AgentResult.getStats()`: success rate and latency percentiles per tool, calls and failures per model | `RunStatsIntegrationTest` |
| A3 | Tell a tool that failed from one that succeeded even when it returns its error as text | `ToolResultsTest`, `ToolCallEventsTest` |
| A4 | Group failures by a category that is the same for every provider and tool | `ErrorCategoryTest` |
| A5 | See how many attempts a model call needed, how long it waited, and which providers failed over, without changing any client | `HttpCallTraceTest`, `RoutingCallTraceTest` |
| A6 | Aggregate across many runs and threads with one line | `StatsCollectorTest`, `StatsConcurrencyTest`, `StatsSoakTest` |
| A7 | Make a tool report an explicit category by throwing `ToolException`, or change how its results are read by overriding `isFailure` | `TelemetryFaultTest` |
| A8 | Upgrade without changing anything and send identical requests | `RequestStabilityTest` (Phase 1 golden files) |
| A9 | Rely on a failing listener never failing a run | `TelemetryFaultTest` |
| A10 | Find all of this documented with a runnable example | Docs review (§5) |

## 2. Traceability: requirement → tests

Filled in as tests are written; a row without a test blocks the exit.

| Req | Tests | Status |
|---|---|---|
| R1 | `ModelCallEventsTest` (both loops, overflow retry) | ☐ |
| R2 | `ModelCallEventsTest` | ☐ |
| R3 | `ModelCallEventsTest#durationFromTheClock` | ☐ |
| R4 | `ModelCallEventsTest#aFailingCall`, `TelemetryFaultTest` | ☐ |
| R5 | `ModelCallEventsTest#truncated` | ☐ |
| R6 | `ToolCallEventsTest` (all outcomes, both loops) | ☐ |
| R7 | `ToolCallEventsTest` | ☐ |
| R8 | `ErrorCategoryTest`, `TelemetryFaultTest` | ☐ |
| R9 | `ToolResultsTest`, `ToolCallEventsTest#softFailure`, `#isFailureOverride` | ☐ |
| R10 | `ErrorCategoryTest` | ☐ |
| R11 | `HttpCallTraceTest` | ☐ |
| R12 | `RoutingCallTraceTest` | ☐ |
| R13 | `HttpCallTraceTest#noTraceNoEffect`, `CallTraceTest` | ☐ |
| R14 | `CallTraceTest` | ☐ |
| R15 | `RunStatsTest`, `RunStatsIntegrationTest` | ☐ |
| R16 | `LatencyStatsTest`, `StatsSoakTest` | ☐ |
| R17 | `StatsCollectorTest`, `StatsConcurrencyTest` | ☐ |
| R18 | `RunStatsIntegrationTest#everyResultHasStats` | ☐ |
| R19 | `RequestStabilityTest`, `TelemetryCompatibilityTest` | ☐ |
| R20 | `TelemetryCompatibilityTest#oldListener` | ☐ |
| R21 | `TelemetryFaultTest#aListenerThatThrows` | ☐ |
| R22 | Build dependency check (§3) | ☐ |
| R23 | `ModelCallEventsTest#summariserCallsAreNotModelCalls` | ☐ |

## 3. Quality gates

| Gate | Threshold | How checked |
|---|---|---|
| All existing ai-agent4j tests | pass, unmodified | `mvn -pl src/ai-agent4j verify` |
| New tests | all pass, several seeds where random | CI plus a local loop |
| Coverage, new package | line ≥ 90 %, branch ≥ 80 % | JaCoCo rule in the module's pom |
| Mutation | survivors reviewed, each killed or justified here | PIT, `-Pmutation-telemetry` |
| Dependencies | none added | diff of `pom.xml` |
| Public API | additions only | diff of public declarations against `main` |
| Thread safety | no mutable static state beyond the `CallTrace` thread-local; concurrency tests | review + tests |
| Other modules | Loom compiles and its suite passes | `mvn -pl src/loom/ai-agent4j-loom -am test` |

## 4. Manual and live checks

1. **Live retry accounting.** Against a real provider, force a retry (a bad key is not enough: use a rate-limited key or a network pause) and compare `attempts`/`waitedMillis` with the provider's behaviour.
2. **Latency sanity.** Compare `durationMillis` with the wall clock for a real call.
3. **Fail-over.** With a routing client whose primary is a wrong URL, confirm `failovers` names it.
4. **Soft failures in the built-in tools.** Run each built-in tool that returns `Error: …` (search tools, OpenAPI) against a failing target and confirm they are counted as failed with a sensible category; list any wording the rule misses and extend `ToolResults`.

## 5. Documentation checks

At the end of every sub-phase go through the table in [../README.md](../README.md#documents-to-update-at-the-end-of-every-sub-phase). Specific to this phase:
a wiki page **"Telemetry and statistics"** (listening, `RunStats`, `StatsCollector`, error categories, writing a tool that reports well, what a soft failure
is, what is not measured), tested samples, Javadoc on every public type, a runnable example that prints per-tool success rates and latencies, and a note in
the Advanced Configuration error table.

## 6. Exit checklist

- [ ] Proposed decisions in DESIGN.md §9 confirmed and recorded
- [ ] All acceptance criteria A1–A10 have evidence
- [ ] Every requirement R1–R23 has a passing test
- [ ] All quality gates in §3 pass
- [ ] Manual checks in §4 done and recorded, or listed as owed with the reason
- [ ] Docs in §5 complete, and the per-sub-phase record in §7 has a row for every sub-phase
- [ ] DESIGN.md reflects what was built
- [ ] Status table in `docs/specs/README.md` updated

## 7. Results

| Date | Sub-phase | Result | Notes |
|---|---|---|---|
| | | | |

### Documentation record (per sub-phase)

| Sub-phase | Wiki page | Samples tested | Javadoc | Module README / Home / llms.txt | Stack-level pages | Specs |
|---|---|---|---|---|---|---|
| 2a | | | | | | |
| 2b | | | | | | |
| 2c | | | | | | |
| 2d | | | | | | |
| 2e | | | | | | |

### Owed to the stack-level pages (updated once, at the end of the whole work)

- (collected as the work goes on)
