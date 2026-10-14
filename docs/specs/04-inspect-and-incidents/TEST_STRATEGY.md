# Phase 4 test strategy: inspect and incidents

Status: **draft for review**. Requirements `R1`…`R17` are in [DESIGN.md](DESIGN.md) §7; the traceability matrix is in [VERIFICATION.md](VERIFICATION.md).

## 1. Principles

- **Tests first** for each sub-phase (4a–4e), failing for the right reason.
- **Streams, not runs.** The mapper and `RunState` are tested by feeding them recorded or constructed lists of `TraceEvent`s and records; no model, no network, no real time (the mapper takes event times; detectors take `now`).
- **Replay is the oracle.** Incidents are checked by replaying the same stream twice, and in reordered-but-equivalent orders, and comparing.
- **Old behaviour is pinned** with fixtures recorded from the current code before any change: golden records for a trace without the new events, an old `.inspect` file, an old snapshot JSON.
- Tests live beside the code in `loom-inspect/src/test/.../map`, `loom-inspect-core/src/test/.../state|rules|otel`, and the end-to-end ones in `loom-inspect` and `loom-fabric`. JUnit and AssertJ as in use; no new test dependency.

## 2. Levels

| Level | What | Examples | Requirements |
|---|---|---|---|
| **Mapper** | Event lists → records: tool outcome with and without `tool_call`; flush in every interleaving (property-style over generated orders); `chat` spans for success, failure, retry, failover; context and compaction logs; run totals | `ToolOutcomeMappingTest`, `HeldSpanFlushTest`, `ModelSpanMappingTest`, `ContextLogMappingTest` | R1–R6 |
| **No text** | A stream with planted secrets and long texts in every field; assert no new record contains them at any `Detail` | `NewRecordsHoldNoTextTest` | R7 |
| **Compatibility** | Golden records for an old trace; old rules file; old snapshot JSON; unknown attributes ignored by `RunState` | `MapperBackwardCompatTest`, `RulesBackwardCompatTest`, `SnapshotCompatTest` | R2, R13 |
| **State** | `RunState.apply` over streams: statistics, percentiles (exact and bounded), view fields, summary | `RunStateStatsTest`, `PercentilesTest`, `RunStateViewTest` | R8, R9 |
| **Detectors** | A table per incident: below, at and above the threshold; escalation; closing; per-agent and central layering; two agents interleaved | `ContextIncidentTest`, `TruncatedIncidentTest`, `ThrashIncidentTest`, `ToolRateIncidentTest`, `SpikeIncidentTest` | R10, R11 |
| **Replay** | A recorded stream (checked into test resources, made from a real scripted Loom run) replayed twice and shuffled across agents; incidents identical | `IncidentReplayTest` | R11 |
| **Rules** | Grammar, ranges, suggestions, line numbers, defaults, unknown agent | `NewRulesFileTest`, `NewRulesCheckTest` | R12 |
| **Export** | `OtlpJson` output of the new records validated for shape and ordering | `OtlpNewRecordsTest` | R14 |
| **End to end** | A scripted Loom run (Phase 3's `TestLoom` pattern) observed by loom-inspect embedded; then the same through Fabric's receiver; the view shows the new fields and incidents | `InspectContextEndToEndTest`, `FabricContextEndToEndTest` | R16 |
| **Soak** | 5,000 model + 5,000 tool calls, memory bounded, totals exact | `RunStateSoakTest` | R15 |
| **Tool health** | A table of tool histories → state and reason at every boundary; merging summaries (associative, commutative, histograms); a rollup across several runs and clients, ordering worst first | `ToolHealthTest`, `ToolSummaryMergeTest`, `ToolRollupTest` | R18–R21 |
| **Build checks** | pom diff (no dependency), public API additions only | build | R17 |

## 3. Points to cover

- A tool call that is blocked as a repeat (`no_result`) or rejected by a person: outcome set from the event, no text guess.
- `tool_call` arriving before its observation (a runtime that orders them the other way): the mapper still pairs by agent order.
- Two parallel agents' tool events interleaved; a step that ends with a held span; a resume that replays a delegate (no events, no spans) next to live ones.
- A model call with unknown finish reason or zero tokens; a failed call with no model name; very long durations; clock going backwards (duration clamped).
- Percentiles: empty, one sample, ties, reservoir overflow determinism.
- Incident ids are stable per (kind, agent, scope) so a re-raise updates rather than duplicates; closed ones are kept (20) as today.
- Thresholds: `warn` equal to `high` rejected; 100 % allowed for `high`; `over 10 calls` minimum of 5 calls enforced.

## 4. Not tested here

- How it looks (phase 5). Live collectors (manual check: send the new spans to a real OpenTelemetry collector).

## 5. Quality gates

- All existing loom-inspect-core, loom-inspect and loom-fabric tests pass unmodified.
- Line coverage of new classes ≥ 90 %, branch ≥ 80 % (JaCoCo rules); a PIT profile over the detectors and mapper additions with survivors reviewed.
- No new runtime dependency; public API additions only.
