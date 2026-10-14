# Phase 1 test strategy: context management and compaction

Status: **draft for review**. Requirements (`R1`…`R23`) are defined in [DESIGN.md](DESIGN.md) §5; the traceability matrix in
[VERIFICATION.md](VERIFICATION.md) maps each to the tests named here.

## 1. Principles

- **Tests are written before the code** for each sub-phase (1a–1g) and fail first for the right reason.
- **No network, no keys, no sleeping.** A scripted `LLMClient` stands in for every model; time and randomness are injected.
- **The invariants are tested as properties**, over many generated conversations, not only over hand-written examples.
- **One contract test, many strategies.** Anything that implements `ContextCompactor`, ours or a user's, can extend the same abstract test and be held to the same rules.
- **Behaviour that must not change is pinned** by comparing exact requests with recorded ones.
- Tests live beside the code in `src/ai-agent4j/src/test/java/io/github/llm4j/context/` (and the existing `agent/` tests for loop integration). JUnit 5 and the assertions already in use; **no new test dependency** except where noted.

## 2. Test levels

| Level | What | Examples | Requirements |
|---|---|---|---|
| **Contract** | An abstract `ContextCompactorContract` with `protected abstract ContextCompactor subject()`. Runs C1–C8 over a fixed set of conversations and over generated ones | `DropOldestContractTest`, `TrimToolResultsContractTest`, `ChainContractTest`, `SummarizeContractTest` (scripted summariser), `NoneContractTest`, plus a `LambdaCompactorContractTest` proving a user lambda is held to it | R1, R2, R7, R8 |
| **Unit** | Each class alone: value-type validation, `ContextWindows` parsing, `ContextManager` decisions, `Scratchpad` rendering, `ContextOverflowClassifier` | `ContextPolicyTest`, `ContextWindowsTest`, `ContextManagerTest`, `ScratchpadTest` | R3, R5, R6, R9–R12, R19 |
| **Loop integration** | A real `ReActAgent` with a scripted `LLMClient` that returns canned tool calls/answers and reports token usage; assertions on the requests it received and the events it emitted | `ReActAgentCompactionNativeTest`, `ReActAgentCompactionTextTest`, `ReActAgentContextEventsTest` | R4–R6, R10, R13, R15–R18, R21 |
| **Fault injection** | The same loops with a compactor that throws, returns a bigger list, returns an unpaired tool message, hangs briefly, or a summariser whose client fails, returns empty, returns too much, or is refused by the budget | `CompactionFaultTest` | R9, R11, R12 |
| **Provider fixtures** | Recorded real error bodies per provider for "prompt too long" (and bodies that must **not** match) fed to the classifier; provider classes tested to raise `ContextOverflowException` through a mock HTTP server already used by the module's provider tests | `ContextOverflowClassifierTest`, per-provider tests | R14 |
| **Compatibility** | Golden requests: the exact `LLMRequest`s the agent sends for a fixed scripted run, in both loops, stored as fixtures from the **pre-change** code and compared after | `RequestStabilityTest` | R4, R17, R21 |
| **Concurrency** | Many agents sharing the same strategy instances and catalogue on a thread pool; results equal the single-threaded ones | `CompactionConcurrencyTest` | R23 |
| **Soak / scale** | A scripted agent with 300 tool steps and large observations: the prompt stays under the window, the run finishes, memory and time are bounded | `CompactionSoakTest` | R6, R10 |
| **Build checks** | Dependency check (no new compile-scope dependency); public API listing diff; coverage gate | build, CI | R22 |

## 3. The contract test (C1–C8)

For every strategy, over each fixture conversation and each target in {95 %, 50 %, 10 % of the input size}:

| Check | Assertion |
|---|---|
| C1 pure | Deep copy of the input compared with the input after the call; the input list is unmodifiable in the test, so a mutation throws |
| C2 protected | Leading system messages, the first user message, the last `keepRecent` and every pinned message appear in the output, equal to the originals |
| C3 pairs | For every assistant message with tool calls in the output, all its tool results are present and no tool result lacks its call (by `toolCallId`) |
| C4 order | The surviving original messages are a subsequence of the input |
| C5 not bigger | Estimated output size ≤ input; when equal, outcome is `NO_GAIN` or `SKIPPED` |
| C6 deterministic | Two calls give equal output (non-model strategies) |
| C7 idempotent | A second pass over the result gains less than `minGain` or reports `NO_GAIN` |
| C8 marked | Any message not equal to an input message carries `llm4j.compaction` |

**Fixture conversations** (hand-written, in `src/test/resources/context/`): empty; only system+task; short (fits); long with
many small turns; long with a few huge tool results; many parallel tool calls in one assistant message; a tool result
that is the last message; an already-compacted conversation containing a prior summary; non-ASCII and very long single lines.

**Generated conversations** (a small seeded generator, no new library): random numbers of exchanges, tool calls per exchange (0–5),
sizes (0–20 000 chars), with pinned messages sprinkled in. 500 cases per strategy per run with a fixed seed, plus a
`-Dseed=` override to reproduce a failure. The seed is printed on failure.

## 4. Unit tests: points to cover

**`ContextPolicy` / values:** defaults; every invalid combination rejected with a message naming the field (`targetAt ≥ triggerAt`, zero or negative sizes, `keepRecent < 0`); records are immutable (defensive copies of lists); `ContextUsage.fraction()` empty without a window.

**`ContextWindows`:** parse `model = 128000 / 8192`; comments and blank lines; duplicate model; non-numeric; negative; missing file; line numbers in every message; `with()` does not mutate the original; unknown model → empty (never a default guess); lookup is case-insensitive and tolerates a provider prefix (`openai/gpt-…`) exactly as `PriceTable` does.

**`ContextManager`** (a table-driven test, scripted compactor and clock-free):

| Case | Expectation |
|---|---|
| No window | usage reported without fraction; compactor never called |
| Below trigger | not called; usage reported |
| Exactly at trigger / one token above | boundary behaviour as specified (strictly above triggers) |
| Reported tokens present vs absent | reported count + estimate of new messages vs full estimate |
| Compactor returns smaller by ≥ `minGain` | used; event `COMPACTED` |
| Smaller by < `minGain` | discarded; event `NO_GAIN` |
| Two `NO_GAIN` in a row | third call skipped; one `SKIPPED` event with reason; then silent |
| `maxCompactions` reached | skipped as above |
| Compactor throws | event with failure; uncompacted used; not retried in this iteration; later iterations still try |
| Still over the window after compaction | `ContextOverflowException` before any provider call |
| Concurrency | two managers do not share state |

**`Scratchpad`:** renders byte-identically to the old string building for sequences of plan/action/observation, empty plans, null input, observations containing the words "Observation:" or "Action:"; projection to messages and back is stable; compaction replaces earlier turns by the block text and keeps the question first.

**`ContextOverflowClassifier`:** for each provider, positive bodies (recorded) and negative bodies (rate limit, auth, invalid
model, a *different* 400). Negative cases matter as much as positive: a misclassified error would trigger an unneeded compaction.

## 5. Loop integration tests

A `ScriptedClient` returns a queue of responses with chosen `TokenUsage`, records every `LLMRequest`, and can throw on the n-th call.

**Native loop (`ReActAgentCompactionNativeTest`):**
1. No compactor: requests equal the golden fixtures (R4).
2. Window 2 000 tokens, `dropOldest`, 40 scripted tool steps with 300-token observations: no request exceeds 80 % of the window after the first compaction; the final answer is returned; `onCompaction` fired N times with consistent before/after numbers.
3. The first request still starts with the system prompt and the task; the last `keepRecent` messages are in every request after a compaction.
4. A tool call/result pair is never split in any recorded request (a checker walks every request).
5. Savings persist: the request after a compaction is based on the compacted conversation, not the original.
6. Gain check and thrash guard end-to-end with a compactor that does nothing.
7. A compactor that throws: run completes; `onCompaction` carries the failure.
8. Reactive: the scripted client throws `ContextOverflowException` on call 3; the agent compacts with the lower target and the retry succeeds; if the second call also throws, the run fails with that exception.
9. Pre-call check: window smaller than the protected content alone → fails before calling the client (call count 0 for that iteration).
10. Usage events: `onContextUsage` fires once per model call, source `REPORTED` when the scripted usage is present, `ESTIMATED` when absent.

**Text loop (`ReActAgentCompactionTextTest`):** the same scenarios with the text protocol, plus: the rendered prompt before any
compaction equals the golden fixture; after compaction the question and the last turns are present and an "Earlier steps"
block carries the marker or summary; the run reaches the same final answer as the uncompacted run on the same script.

**`SummarizeContractTest` and `SummarizeFallbackTest`:** scripted summariser returns a good summary (used, marked, merged with a prior
summary rather than nested); throws `LLMException` (fallback to `dropOldest`, result says so); returns empty; returns
longer than its input; `BudgetExceeded` from the client (fallback, no failure); the summariser prompt contains the
instruction to keep identifiers and numbers (asserted on the recorded request); summariser usage appears in `AgentResult.Usage` and in the event.

**A fact-survival scenario** (not a unit test of the model, a test of the plumbing): a scripted conversation in which a unique token
appears in an early tool result; with `trimToolResults` the token survives when it is within the kept head or tail; the
test documents, by assertion, which strategies are allowed to lose it, so the limits are explicit rather than assumed.

## 6. Compatibility tests

`RequestStabilityTest` is created **first**, before any production change in 1b/1c: it runs fixed scripted scenarios on the current code and
writes the requests as fixtures; the same test must pass unchanged after the change. This is what makes R4, R17 and R21
a fact rather than a promise. A second check compiles a minimal old-style `AgentEventListener` implementation (anonymous class
with only the old methods) against the new interface.

## 7. What is deliberately not tested here

- Real provider calls in CI (see manual checks in VERIFICATION.md; a `live` test is provided, skipped without keys, in the style of the module's existing `live` tests).
- The quality of a model's summary. We test that the plumbing sends the right instructions and handles every outcome; judging summary quality is an evaluation task for eval4j and is listed as a follow-up.
- Exact token counts. Estimates are tested for monotonicity and plausibility only.

## 8. Quality gates (also in VERIFICATION.md)

- Line coverage of the new `io.github.llm4j.context` package ≥ 90 %, branch coverage ≥ 80 % (JaCoCo rule added to the module).
- Mutation check (manual, once per sub-phase, with PIT run locally and not added to the build): the surviving mutants in `ContextManager` and the built-in strategies are reviewed; any that reveal an untested rule get a test.
- All existing ai-agent4j tests pass unchanged (R21).
- No test uses `Thread.sleep` for synchronisation; the soak test has a generous fixed bound and a count-based, not time-based, assertion.
