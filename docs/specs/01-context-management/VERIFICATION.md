# Phase 1 verification plan: context management and compaction

Status: **draft for review**. This is the checklist that decides whether Phase 1 is done. It is run top to bottom at the end of
sub-phase 1g, and the relevant rows after each sub-phase. Results are recorded in §7.

## 1. Acceptance criteria

| Id | A developer can… / the system does… | Evidence |
|---|---|---|
| A1 | Turn on a built-in strategy with one builder line and see it work in a run | Example runs; `ReActAgentCompactionNativeTest` |
| A2 | Write a compactor as a lambda or a class, pass it in, and have it held to the same rules as the built-ins | `LambdaCompactorContractTest`; docs example compiles in a test |
| A3 | Add a strategy in a separate jar and select it by name | ServiceLoader test with a test-jar provider |
| A4 | Upgrade without changing anything and get identical requests | `RequestStabilityTest` |
| A5 | See context use before every model call | `ReActAgentContextEventsTest` |
| A6 | Run a 300-step agent that would overflow without compaction, and have it finish | `CompactionSoakTest` |
| A7 | Rely on the task, the instructions and the latest turns surviving compaction, and on tool calls never being orphaned | Contract tests; request walker in integration tests |
| A8 | Have a failing, slow-to-help or misbehaving compactor never fail a run that could continue | `CompactionFaultTest` |
| A9 | Have the summariser's cost counted and bounded | Usage assertions; fallback tests |
| A10 | Recover from a provider's "prompt too long" error with one compaction and one retry | Provider fixtures; reactive loop test |
| A11 | Use a token window and compaction on conversation history | `ConversationHistory` tests |
| A12 | Find all of this documented with a runnable example | Docs review (§5) |

## 2. Traceability: requirement → tests

Filled in as tests are written; a row without a test blocks the exit. (Class names are the planned ones in TEST_STRATEGY.md.)

| Req | Tests | Status |
|---|---|---|
| R1 | `LambdaCompactorContractTest`, `ContextCompactorsTest` | ✅ 1a |
| R2 | `{None,DropOldest,TrimToolResults,Chain,Summarize}ContractTest` | ◐ 1a: all but `summarize` (1d) |
| R3 | `ContextCompactorsByNameTest` (ServiceLoader, unknown name suggestion) | ✅ 1a |
| R4 | `RequestStabilityTest` (native + text) | ✅ 1b native (4 golden scenarios, plus a listener+window without compactor); text loop re-checked in 1c |
| R5 | `ReActAgentCompactionNativeTest#usageIsReportedBeforeEveryModelCall` and related, `ContextManagerTest` | ✅ 1b native; text loop in 1c |
| R6 | `ContextManagerTest`, `ReActAgentCompactionNativeTest#keepsEveryRequestWithinTheWindow`, `CompactionSoakTest` (300 steps) | ✅ 1b native; text loop in 1c |
| R7 | Contract C2; `ReActAgentCompactionNativeTest#theInstructionsAndTheTaskAreInEveryRequest`, `#aPinnedResultSurvivesEveryCompaction` | ✅ 1b native; text loop in 1c |
| R8 | Contract C3; request walker in `ReActAgentCompactionNativeTest`, `CompactionSoakTest` | ✅ 1b native; text loop in 1c |
| R9 | `ContextManagerTest#aResultThatGainsTooLittleIsDiscarded`, `#aStrategyThatOverstatesItsSavingIsNotBelieved`, `#aBiggerResultFromARawCompactorIsNotUsed` | ✅ 1b |
| R10 | `ContextManagerTest#afterTwoNoGainsInARowItStopsAndSaysSoOnce`, `#afterTheMaximumNumberOfCompactionsItStops`; `ReActAgentCompactionNativeTest#aCompactorThatDoesNothingIsStoppedAndTheRunStillFinishes` | ✅ 1b |
| R11 | `ContextManagerTest#aCompactorThatThrowsDoesNotFailTheRun`, `#aCompactorThatKeepsFailingIsGivenUpOn`; `ReActAgentCompactionNativeTest#aCompactorThatThrowsDoesNotFailTheRun` | ✅ 1b |
| R12 | `SummarizeFallbackTest` (exception, empty, longer, budget) | ☐ |
| R13 | `SummarizeFallbackTest#usageCounted`, `ReActAgentContextEventsTest` | ☐ |
| R14 | `ContextOverflowClassifierTest`, per-provider tests | ☐ |
| R15 | `ReActAgentCompactionNativeTest#reactiveRetry` and `#secondOverflowFails` | ☐ |
| R16 | `ContextManagerTest#neverAboveWindow`, `…NativeTest#preCallCheck` | ☐ |
| R17 | `ScratchpadTest#byteIdentical`, `RequestStabilityTest#text` | ☐ |
| R18 | `ReActAgentCompactionTextTest#sameFinalAnswer` | ☐ |
| R19 | `ContextWindowsTest` | ✅ 1a |
| R20 | `ConversationHistoryCompactionTest` | ☐ |
| R21 | `RequestStabilityTest#anOldStyleListenerStillWorks`, `ReActAgentCompactionNativeTest#aListenerThatThrowsDoesNotFailTheRun`, whole existing suite (1,586 tests green) | ✅ 1b |
| R22 | Build dependency check (§3) | ◐ no dependency or pom change through 1b |
| R23 | `CompactionConcurrencyTest` (48 agents, 16 threads, shared strategy and catalogue), `ContextManagerTest#twoManagersShareNothing` | ✅ 1b |

## 3. Quality gates

| Gate | Threshold | How checked |
|---|---|---|
| All existing ai-agent4j tests | pass, unmodified | `mvn -pl src/ai-agent4j test` |
| New tests | all pass, 3 consecutive runs with different seeds | CI plus a local loop with `-Dseed` |
| Coverage, new package | line ≥ 90 %, branch ≥ 80 % | JaCoCo rule in the module's pom |
| Dependencies | no new compile or runtime dependency | `mvn dependency:tree` diff against `main` |
| Public API | only additions; the diff is reviewed | `javap`/`japicmp`-style listing diff, reviewed by hand |
| Thread safety | no shared mutable static state in the package | review + `CompactionConcurrencyTest` |
| Style | formatter/checkstyle the module already uses pass | `mvn verify` |
| Mutation check | surviving mutants in `ContextManager` and built-ins reviewed, each either killed or justified in a note here | PIT, run locally, results summarised in §7 |
| Other modules | Loom and eval4j still build and test | `make test` (or the modules' own) |

## 4. Manual and live checks

These cannot run in CI. Each is done once per release candidate and recorded.

1. **Live overflow.** With a real provider key, send a prompt larger than the model's window and capture the error body; add it to the classifier fixtures if it is new. Repeat for each provider the repo supports.
2. **Live compaction.** Run the example agent against a real model with a small `contextWindow` so compaction triggers; confirm the run finishes, the events look right, and read the summary for sense.
3. **Summary review.** Read three real summaries; confirm identifiers, numbers and open questions survived. Record any prompt change this causes.
4. **Estimator sanity.** Compare estimated against reported prompt tokens over a real run; record the error. If it is consistently off by more than 25 %, note it in the docs.
5. **Catalogue check.** Each `ContextWindows` built-in entry is confirmed against the provider's current documentation, with the date, before release.

## 5. Documentation checks

**At the end of every sub-phase (1a–1g)**, before it is committed as done, go through the table in [../README.md](../README.md#documents-to-update-at-the-end-of-every-sub-phase). The record is §7.


- Javadoc on every public type and method, with the contract (C1–C8) on `ContextCompactor`.
- A guide page in the wiki ("Context and compaction"): the problem, choosing a strategy, writing one, the events, the guards, what is not guaranteed.
- A runnable example under `src/examples/` that prints the events.
- The README and `llms.txt` list the feature.
- Every code sample in the docs is compiled by a test (a sample is copied from a test file, not retyped).

## 6. Exit checklist

- [ ] Open questions in DESIGN.md §9 answered and recorded in DESIGN.md
- [ ] All acceptance criteria A1–A12 have evidence
- [ ] Every requirement R1–R23 has at least one passing test (§2 has no empty rows)
- [ ] All quality gates in §3 pass
- [ ] Manual checks in §4 done and recorded
- [ ] Docs in §5 complete, and the per-sub-phase documentation record in §7 has a row for every sub-phase
- [ ] DESIGN.md reflects what was built (differences explained, not hidden)
- [ ] Status table in `docs/specs/README.md` updated
- [ ] Pull request opened with this checklist in its description

## 7. Results

Filled in when the phase is verified.

| Date | Sub-phase | Result | Notes |
|---|---|---|---|
| 2026-10-14 | 1a | Pass | 564 new tests in `io.github.llm4j.context`; whole module 1,102 tests green; new package line 97.6 % / branch 89.4 %; contract run with 3 seeds and keepRecent 0/4/12. Seed list of `ContextWindows` deferred to 1g as decided |
| 2026-10-14 | 1b | Pass | Golden requests recorded from the unmodified agent first (4 scenarios), unchanged since. 17 `ContextManager` tests, 16 native-loop tests, soak (300 steps, window never exceeded), concurrency (48 agents). Whole module 1,586 tests green; new package line 98.7 % / branch 89.6 %. One fix found by review: the gain check now uses the manager's own measurement. Dependent modules (Loom, loom-inspect-core, loom-inspect, loom-fabric) compile with their tests against the change; `eval4j-report` could not be built offline here (unrelated missing local dependencies), so that module is still to be checked in CI |

### Documentation record (per sub-phase)

| Sub-phase | Wiki page | Samples tested | Javadoc | Module README / Home / llms.txt | Stack-level pages | Specs |
|---|---|---|---|---|---|---|
| 1a | `Context-and-Compaction.md` created, marked in progress | ✅ `ContextDocsExamplesTest` (8 samples, page checked against them) | ✅ all public types | ✅ README hub + feature line, wiki Home, both `llms.txt` | n/a: not yet usable through an agent | ✅ |
| 1b | ✅ status box rewritten; sections 9 to 11 added (use in an agent, events, guards) | ✅ 2 new samples (`agent`, `listener`), 10 checked against the page | ✅ builder methods, `AgentEventListener`, `ContextManager`, events | ✅ README line, wiki Home, `llms.txt` | deferred to the end of the whole work by decision | ✅ DESIGN notes, traceability, results |
| 1c | | | | | | |
| 1d | | | | | | |
| 1e | | | | | | |
| 1f | | | | | | |
| 1g | | | | | | |

### Owed to the stack-level pages (updated once, at the end of the whole work)

Root `README.md`, `wiki/Home.md`, `docs/README.md`, `docs/THE_STACK.md`: mention context management and compaction in ai-agent4j; once phases 3 to 5 land, the
observability pages gain context gauges and compaction events. Collect here as the work goes on:

- 1a, 1b: a context and compaction capability in ai-agent4j (strategies, policy, window catalogue, automatic compaction for native tool calling).
