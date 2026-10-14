# Phase 3 verification plan: Loom integration

Status: **draft for review**. Run top to bottom at the end of sub-phase 3e, and the relevant rows after each sub-phase. Results are recorded in §7.

## 1. Acceptance criteria

| # | Criterion | Evidence |
|---|---|---|
| A1 | Write `context { compact: drop_oldest }` in an agent and a long tool loop completes instead of overflowing | `ContextLoomSoakTest` |
| A2 | Configure a model's window in a file outside the script and see it used, without editing the script | `ContextWindowsLayeringTest`, `ContextAgentBuildTest` |
| A3 | Name a chain of strategies and a cheaper summariser model in the script | `ContextBlockParserTest`, `ContextAgentBuildTest` |
| A4 | `weave check` rejects a mistyped strategy with the line and the known names, and warns about a model with no known window | `ContextBlockCheckTest`, `WeaveCheckContextTest` |
| A5 | See context fill, compactions, model calls and tool calls (with real outcomes) in the trace | `ContextTraceEventsTest` |
| A6 | Read the run's statistics from an observer | `RunStatsInLoomTest` |
| A7 | Loom's own transcript memory stays bounded and `rewind` still works | `TranscriptCompactionTest`, `RewindWithCompactionTest` |
| A8 | A script that does not use any of this behaves and traces exactly as before | `LoomBackwardCompatTest` |
| A9 | `weave audit` shows each agent's strategy and window source | audit test |
| A10a | Plug in my own strategy with a `.loct` file and use its name in the script | `LoctLoaderTest`, `ContextAgentBuildTest` |
| A10 | Find all of it documented, with a runnable example | docs review (§5) |

## 2. Traceability: requirement → tests

| Req | Tests | Status |
|---|---|---|
| R1 | `ContextBlockParserTest` | ✅ 3a |
| R2 | `LoomBackwardCompatTest`, `ContextBlockParserTest#theWordContextElsewhere` | ◐ 3a: parser pins; full golden in 3c |
| R3 | `ContextBlockCheckTest` | ✅ 3a (names; `.loct` names in 3b) |
| R4 | `ContextBlockCheckTest` | ✅ 3a |
| R5 | `ContextWindowsLayeringTest` | ✅ 3b |
| R6 | `ContextAgentBuildTest` | ✅ 3b |
| R7 | `ContextAgentBuildTest#routingAgentUsesTheSmallestWindow` | ✅ 3b |
| R8 | `ContextBlockCheckTest`, `ContextAgentBuildTest` | ✅ 3b (check warning and run) |
| R9 | `ContextAgentBuildTest#summariserSharesTheFactory` | ✅ 3b |
| R10 | `ContextTraceEventsTest` | ✅ 3c |
| R11 | `ContextTraceEventsTest#noEventsWithoutListeners` | ✅ 3c |
| R12 | `ContextLoomSoakTest` | ✅ 3b |
| R13 | `TranscriptCompactionTest`, `RewindWithCompactionTest` | ☐ |
| R14 | `RunStatsInLoomTest` | ✅ 3c |
| R15 | `WeaveCheckContextTest`, audit test | ☐ |
| R16 | `ReplayNoDuplicateEventsTest` | ✅ 3c |
| R17 | extended redaction audit | ✅ 3c |
| R18 | dependency diff | ☐ |
| R19 | `LoctLoaderTest` | ✅ 3b |
| R20 | `LoctLoaderTest`, `LoctCheckTest` | ✅ 3b |
| R21 | `LoctProviderOptionsTest` | ✅ 3b |
| R22 | `LoctFaultTest` | ✅ 3b |
| R23 | `LoctCheckTest#auditDoesNotLoad` | ◐ 3b: check never loads/runs; audit listing in 3e |

## 3. Quality gates

| Gate | Threshold | How checked |
|---|---|---|
| Existing ai-agent4j and Loom tests | pass, unmodified | `mvn verify` |
| Coverage, new Loom classes | line ≥ 90 %, branch ≥ 80 % | JaCoCo |
| Mutation | survivors reviewed and justified here | PIT profile |
| Dependencies | none added | pom diff |
| Public API | additions only | declaration diff |
| Redaction | secrets absent from the new events | audit test |

## 4. Manual and live checks

1. **A real long run** against a real provider with a small window set in the windows file: confirm compaction triggers at the configured fill and the answer is sound.
2. **Summariser choice**: run once with `summarize` and a cheaper model; compare cost and result with `drop_oldest`.
3. **Windows file in the field**: a deployer edits the file for a new model; no script change.
4. **A real custom strategy**: write a `ContextCompactor`, map it in a `.loct`, run a long agent with it.
5. **VS Code**: the `context` block highlights and hovers.

## 5. Documentation checks

Per the [rule](../README.md#documents-to-update-at-the-end-of-every-sub-phase): the Loom guide and language reference (the `context` block, the windows file, the `.loct` file beside the `.loot` documentation),
the observability pages' event table, VS Code grammar and hover, `llms.txt`, a tested sample page, and a runnable example under `src/examples/`.
Stack-level pages are owed once at the end of the whole work.

## 6. Exit checklist

- [ ] Decisions in DESIGN.md §9 confirmed and recorded
- [ ] Acceptance criteria A1–A10 have evidence
- [ ] Every requirement R1–R23 has a passing test
- [ ] Quality gates in §3 pass
- [ ] Manual checks done or listed as owed with the reason
- [ ] Docs complete; §7 has a row for every sub-phase
- [ ] DESIGN.md reflects what was built
- [ ] Status table in `docs/specs/README.md` updated

## 7. Results

| Date | Sub-phase | Result | Notes |
|---|---|---|---|
| 2026-10-14 | 3c | Pass | Tests first for the four new event types (data, no text, order, failures, soft failures, no listener), statistics equal to the events, `run_end` totals, replay without duplicate events, a planted secret. 15 new tests; Loom 1,821, inspect-core 108, inspect 108, fabric 34 tests green. Finding: a listener makes ai-agent4j build a context manager, so agents without a block also report context use (no window) |
| 2026-10-14 | 3b | Pass | Windows layering (5 layers), `.loct` loader and registry, `--context-windows`/`--loct`, agent wiring (policy, smallest window, summarizer client, chain, options), faults, a 150-step soak. 48 new tests. Findings: the default `max_compactions` (8) is too low for a very long loop (documented); load problems of a `.loct` had to be kept to be shown by `weave check` |
| 2026-10-14 | 3a | Pass | Tests first for the parser (every key, `chain()`, `[a, b]`, percent forms, the word `context` elsewhere) and the check (unknown strategy with a guess, `target` vs `trigger`, a window written in the script, summarizer, references). 24 new tests; Loom suite 1,759 tests green |

### Documentation record (per sub-phase)

| Sub-phase | Guide / reference | Samples tested | Javadoc | VS Code / llms.txt | Stack-level pages | Specs |
|---|---|---|---|---|---|---|
| 3a | ✅ guide section "Context and Compaction" (marked in progress) | ✅ checked by `ContextBlock*Test` | ✅ `AgentDef.ContextConfig` | ✅ VS Code grammar and hover | deferred to the end | ✅ traceability, results |
| 3b | ✅ guide: windows files, `.loct`, defaults, CLI options | ✅ `ContextAgentBuildTest`, `LoctCheckTest` | ✅ `WindowLayers`, `CompactorRegistry`, `ContextFiles`, executor methods | ✅ (VS Code unchanged) | deferred to the end | ✅ traceability, results |
| 3c | ✅ guide: the trace events and `ObservableRun.stats()` (see below) | ✅ `ContextTraceEventsTest`, `RunStatsInLoomTest` | ✅ `TraceEvent` constants, `ObservableRun.stats` | n/a | deferred to the end | ✅ DESIGN section 10, traceability, results |
| 3d | | | | | | |
| 3e | | | | | | |

### Owed to the stack-level pages (updated once, at the end of the whole work)

- (filled in as sub-phases land)
