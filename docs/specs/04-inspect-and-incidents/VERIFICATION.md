# Phase 4 verification plan: inspect and incidents

Status: **draft**. Run top to bottom at the end of sub-phase 4e; relevant rows after each sub-phase. Results in §7.

## 1. Acceptance criteria

| # | Criterion | Evidence |
|---|---|---|
| A1 | A tool that returns an error as text, or fails with a category, shows that outcome and category on its span, without guessing from text | `ToolOutcomeMappingTest` |
| A2 | Every model call is a `chat` span with tokens, finish reason, attempts and category | `ModelSpanMappingTest` |
| A3 | Context fill and compactions are in the records and in the run's view | `ContextLogMappingTest`, `RunStateViewTest` |
| A4 | Per model and per tool: calls, success rate, failures by category, p50 and p95 | `RunStateStatsTest` |
| A5 | An incident is raised for a nearly full context, a truncated answer, compaction thrash, a collapsing tool success rate and a latency spike, with evidence | the five incident tests |
| A6 | The thresholds are set per agent in the `.inspect` file and centrally | `NewRulesFileTest`, `ConfigLayeringTest` |
| A7 | An older trace, rules file or snapshot behaves exactly as before | the compatibility tests |
| A8 | Replaying a recorded run gives the same incidents | `IncidentReplayTest` |
| A9 | Nothing new carries prompt, answer, argument or result text | `NewRecordsHoldNoTextTest` |
| A10 | It works through Fabric end to end | `FabricContextEndToEndTest` |
| A11 | Documented, with a runnable example | docs review (§5) |

## 2. Traceability: requirement → tests

| Req | Tests | Status |
|---|---|---|
| R1 | `ToolOutcomeMappingTest` | ☐ |
| R2 | `MapperBackwardCompatTest` | ☐ |
| R3 | `HeldSpanFlushTest` | ☐ |
| R4 | `ModelSpanMappingTest` | ☐ |
| R5 | `ContextLogMappingTest` | ☐ |
| R6 | `ContextLogMappingTest#runTotals` | ☐ |
| R7 | `NewRecordsHoldNoTextTest` | ☐ |
| R8 | `RunStateStatsTest`, `PercentilesTest` | ☐ |
| R9 | `RunStateViewTest` | ☐ |
| R10 | the five incident tests, `ConfigLayeringTest` | ☐ |
| R11 | `IncidentReplayTest` | ☐ |
| R12 | `NewRulesFileTest`, `NewRulesCheckTest` | ☐ |
| R13 | `RulesBackwardCompatTest`, `SnapshotCompatTest` | ☐ |
| R14 | `OtlpNewRecordsTest` | ☐ |
| R15 | `RunStateSoakTest` | ☐ |
| R16 | `InspectContextEndToEndTest`, `FabricContextEndToEndTest` | ☐ |
| R17 | pom diff | ☐ |

## 3. Quality gates

| Gate | Threshold | How checked |
|---|---|---|
| Existing loom-inspect-core, loom-inspect, loom-fabric tests | pass, unmodified | `mvn verify` |
| Coverage, new classes | line ≥ 90 %, branch ≥ 80 % | JaCoCo |
| Mutation | survivors reviewed and justified here | PIT profile |
| Dependencies | none added | pom diff |
| Public API | additions only | declaration diff |

## 4. Manual and live checks

1. Send a run's new spans and logs to a real OpenTelemetry collector (Jaeger or similar) and look at the `chat` spans.
2. A real long run against a real provider with a small window: watch the context incident open and close around compactions.
3. A real flaky tool: the success-rate incident.

## 5. Documentation checks

Per the [rule](../README.md#documents-to-update-at-the-end-of-every-sub-phase): `docs/OBSERVABILITY.md` (records and attributes), `docs/loom-inspect/DESIGN.md` (the incident table and the rules), the Loom guide's
inspect section, `llms.txt`, a runnable example under `src/examples/`. Stack-level pages are owed once at the end of the whole work.

## 6. Exit checklist

- [ ] Decisions in DESIGN.md §9 recorded
- [ ] Acceptance criteria A1–A11 have evidence
- [ ] Every requirement R1–R17 has a passing test
- [ ] Quality gates in §3 pass
- [ ] Manual checks done or listed as owed with the reason
- [ ] Docs complete; §7 has a row for every sub-phase
- [ ] DESIGN.md reflects what was built
- [ ] Status table in `docs/specs/README.md` updated

## 7. Results

| Date | Sub-phase | Result | Notes |
|---|---|---|---|

### Documentation record (per sub-phase)

| Sub-phase | Docs | Samples tested | Javadoc | llms.txt | Stack-level pages | Specs |
|---|---|---|---|---|---|---|
| 4a | | | | | | |
| 4b | | | | | | |
| 4c | | | | | | |
| 4d | | | | | | |
| 4e | | | | | | |

### Owed to the stack-level pages (updated once, at the end of the whole work)

- (filled in as sub-phases land)
