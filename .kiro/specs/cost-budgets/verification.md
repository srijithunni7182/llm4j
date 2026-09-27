# Verification Plan: Cost Budgets

## Purpose

This plan defines when Cost Budgets is **done**: the tests that prove every acceptance criterion in
`requirements.md`, the exact numbers they must produce, and the gate each phase in `tasks.md` has to
pass before the next begins.

A check passes only when its assertion holds **exactly** as written. Budget arithmetic is deterministic
with the fixtures below, so "roughly right" is a failure.

---

## 1. Test Fixtures

The tests use shared fixtures from `ai-agent4j/src/test/java/io/github/llm4j/budget/fixtures/`. Loom
tests depend on ai-agent4j's test-jar, so they use the same ones.

| Fixture | Behaviour |
|---|---|
| `ScriptedLLMClient` | Returns scripted content in order and reports `TokenUsage(prompt = 100, completion = 50)` unless a step overrides it. Counts calls and records every `LLMRequest` it receives (so `maxTokens` can be asserted). |
| `NoUsageClient` | Returns 400 characters of content and **no** `TokenUsage`. |
| `FailingClient` | Throws `LLMException("provider down")` on every call after recording the request. |
| `GreedyClient` | Ignores `maxTokens` and reports `completion = 500`. |
| `LatchedClient` | Blocks every call on a latch, so tests can hold N calls in flight at once. |
| `FixedEstimator` | Estimates every prompt as exactly 100 tokens, and completions as `ceil(chars / 4 × 1.1)`. |
| `prices-test.properties` | `test/model = 1.00 / 2.00` (USD per million input / output tokens). |

**Standard call.** With `FixedEstimator` and `perCall = 50`, each call reserves **150 tokens** (100 prompt +
50 output). `ScriptedLLMClient` then reports usage of exactly **150**, so settled spend equals the
reservation. It costs `100 × $1/M + 50 × $2/M = **$0.0002**`.

---

## 2. Verification Matrix

Each check has an ID (`V<requirement>.<n>`), the test that implements it, and its pass condition.

### Requirement 1: Budget object
*Tests: `ai-agent4j/.../budget/BudgetTest`, `BudgetSetTest`*

| ID | Scenario | Pass condition |
|---|---|---|
| V1.1 | Budget with only `tokens = 1000`; charge 3 standard calls | `spent.tokens == 450`, `remaining.tokens == 550`, `remaining.calls` and `remaining.cost` are empty (unlimited) |
| V1.2 | Budget with `calls = 3`; make 4 standard calls | calls 1–3 succeed; call 4 throws `BudgetExceeded` with `dimension == CALLS`; client call count is 3 |
| V1.3 | Budget with no limits; 10 calls | never refuses; `spent.tokens == 1500`, `spent.calls == 10` |
| V1.4 | `tokens = 1000`, `warnAt = 0.8`; 6 standard calls, listener attached | exactly **one** WARNING event, fired on call 6 (spend 750 → 900 crosses 800); none on calls 1–5 |
| V1.5 | `BudgetSet(run = 1000, agent = 100)`; one standard call | refused; exception names `agent`; run `spent.tokens == 0` and run `reserved == 0` afterwards (all-or-nothing release) |
| V1.6 | `BudgetSet(run = 1000, agent = 500)`; 2 standard calls | both budgets show `spent.tokens == 300` |

### Requirement 2: Enforcement at the LLM call
*Tests: `BudgetedLLMClientTest`, `BudgetConcurrencyTest`*

| ID | Scenario | Pass condition |
|---|---|---|
| V2.1 | `tokens = 1000`; 7 standard calls | calls 1–6 succeed; call 7 throws `BudgetExceeded`; **delegate call count == 6** (the 7th never reached the model); `spent.tokens == 900` |
| V2.2 | `tokens = 300`; request `maxTokens = 1000` | the delegate receives `maxTokens == 200` (300 − 100 prompt) |
| V2.3 | `tokens = 160`, no per-call cap | affordable output 60 < min(1024, 64) → refused; delegate call count == 0 |
| V2.3b | `tokens = 150`, `perCall = 50` | affordable output 50 ≥ min(50, 64) → allowed; delegate receives `maxTokens == 50` |
| V2.4 | `ScriptedLLMClient` reports `prompt = 120, completion = 30` for a reservation of 150 | settled `spent.tokens == 150`; reservation released; `estimated == false` |
| V2.5 | `NoUsageClient` | charged `100 + 110 = 210` tokens; `spent.estimated == true`; `AgentResult.usage.estimated == true` when used through an agent |
| V2.6 | `FailingClient` | the exception propagates unchanged; charged `100` tokens and `1` call; `estimated == true` |
| V2.7a | 32 threads × 100 standard calls; `tokens = 150,000` (exactly 1,000 calls) | **exactly 1,000** calls reach the delegate; 2,200 refused; `spent.tokens == 150,000`; `spent == Σ settled charges` |
| V2.7b | Same, but `ScriptedLLMClient` reports prompt 110 (estimate 100); `LatchedClient` holds 32 calls in flight | `spent.tokens ≤ 150,000 + 32 × 10`; `spent == Σ settled charges`; never negative remaining before settlement |
| V2.7c | 16 threads each reserving on overlapping `BudgetSet`s in shuffled member order, 10,000 iterations | completes within **10 s** (`@Timeout`); no deadlock; totals exact |
| V2.8 | `tokens = 500`; `GreedyClient` | call 1 charged `600`; Spend_Report shows `overdraw == 100`; call 2 refused with delegate count 1 |

### Requirement 3: Cost budgets
*Tests: `PriceTableTest`, `BudgetedLLMClientCostTest`*

| ID | Scenario | Pass condition |
|---|---|---|
| V3.1 | Build a Budget with `cost` but no PriceTable | `IllegalStateException` mentioning "price table" |
| V3.2 | Load `prices-test.properties` | `price("test/model") == (1.00, 2.00)`; `price("unknown/x")` is empty; the library contains no bundled price file (a classpath scan finds none) |
| V3.3 | `price("ollama/llama3.1:8b")` with no entry | `(0, 0)` |
| V3.4 | Loom script with `budget { cost: "$0.01" }` using an unpriced model | `initialize()` throws, and the message names the model and `--prices` |
| V3.5 | `cost = $0.001`; standard calls on `test/model` | calls 1–5 succeed (`spent.cost == 0.0010` exactly, as `BigDecimal`); call 6 refused |

### Requirement 4: ReActAgent
*Tests: `ReActAgentBudgetTest`*

| ID | Scenario | Pass condition |
|---|---|---|
| V4.1 | Builder with `budget` and `maxTokensPerCall(50)` | every request the client sees has `maxTokens ≤ 50` |
| V4.2 | Scripted agent needing 4 LLM turns; budget allows 2 | result `completed == false`, `budgetExhausted() == true`, last step outcome `BUDGET_EXHAUSTED`, `finalAnswer` equals the 2nd turn's thought, `usage.llmCalls == 2` |
| V4.3 | Same with `onBudgetExhausted(FAIL)` | `BudgetExceeded` is thrown from `run()` |
| V4.4 | Client wrapped in a retrying `DefaultLLMClient` (`maxRetries = 3`); budget allows 1 call | delegate call count **1**; no retry after the refusal |
| V4.5 | Agent with `NoUsageClient` | `result.usage.estimated == true`; `usage.cost` is null without a price table, non-null with one |
| V4.6 | Budget `tokens = 1000`, `warnAt = 0.8`; agent makes 7 calls | listener receives exactly `[WARNING, EXHAUSTED]` in that order |
| V4.7 | Agent without budget (baseline) vs. the same agent before this change | identical `AgentResult` (steps, answer, usage); the client is **not** a `BudgetedLLMClient` |

### Requirement 5: Loom syntax
*Tests: `loom/.../parser/BudgetParserTest`*

| ID | Scenario | Pass condition |
|---|---|---|
| V5.1 | `budget { tokens: 200000 calls: 150 cost: "$0.50" warn_at: 80% }` at top level | `LoomScript.budget` has all four values; a block with any subset parses |
| V5.2 | `budget { tokens: 20000 per_call: 2000 }` inside an agent | `AgentDef.budget.perCall == 2000`; `per_call` at top level is a ParseError |
| V5.3 | `budget 5000 tokens`, `budget 10 calls`, `budget "$0.05"` on delegate, broadcast, loop until, for each | each statement's `BudgetLimit` holds the right dimension and value (12 cases) |
| V5.4 | Script using `budget` as a variable (`delegate "x" to A -> budget` then `{budget}`) | parses and runs exactly as before |
| V5.5 | `tokens: 0`, `tokens: -5`, `cost: "0.50"` | ParseError whose message contains the line number and the field |

### Requirement 6: Enforcement in Loom
*Tests: `loom/.../execution/BudgetEnforcementTest` (scripted client factory, standard calls)*

| ID | Scenario | Pass condition |
|---|---|---|
| V6.1 | Run 1000, agent `Writer` 400, step `budget 300 tokens`; one delegate | the call's Budget_Set contains exactly {run, agent Writer, step Main/…}; all three charged 150 |
| V6.2 | Step `budget 100 tokens retry 3 on_failure { note "{_error}" }` | the delegate client is called **0** times; `on_failure` runs once; `_error` contains `step` and `tokens`; no retry |
| V6.3a | Agent budget allows 1 of the 2 turns it needs; step has `on_failure` | the output variable holds the partial answer; `_budget.exhausted == "true"`; `on_failure` ran |
| V6.3b | Same, no `on_failure` | the next statement executes; the output variable holds the partial answer |
| V6.4a | `loop until (x == "never") max 10 budget 450 tokens { 1 delegate }` | exactly **3** rounds; `on_exhausted` runs; `_loopExhaustedBy == "budget"`; `_loopRounds == 3` |
| V6.4b | Same with `max 2`, no budget | `_loopExhaustedBy == "rounds"` |
| V6.4c | `for each item in list budget 300 tokens` over 5 items | 2 items processed; the rest skipped; `on_exhausted` if present, otherwise propagation per V6.5 |
| V6.5 | Run budget 300; 3 sequential delegates, no handlers | `executeWorkflow` throws `BudgetExceeded`; the first 2 output variables stay bound in `getContext()`; `spend().total.tokens == 300` |
| V6.6 | `alt (_budget.remaining < 500) { delegate … to Cheap } else { delegate … to Writer }` after spending 600 of 1000 | `Cheap` is called; `Writer` isn't; `{_budget.spent}` in a payload renders `600` |
| V6.7 | `parallel { 4 branches × 5 delegates }`; run budget = 20 calls' worth (3000) | all 20 run; `spent.tokens == 3000` exactly |
| V6.7b | Same with run budget 1500 | exactly **10** calls reach the client; `spent.tokens == 1500` |
| V6.8 | Routing policy with primary and fallback tiers; run budget declared | every tier client is a `BudgetedLLMClient` |

### Requirement 7: Durability
*Tests: `loom/.../execution/BudgetDurabilityTest` (in-memory and `JdbcRunJournal` on H2)*

| ID | Scenario | Pass condition |
|---|---|---|
| V7.1 | 4 delegates complete with a journal | the journal holds 4 `usage` entries of 150 tokens each |
| V7.2 | Re-run the same workflow with that journal and a fresh executor | the delegate client is called **0** times; `spend().total.tokens == 600` (restored, not doubled) |
| V7.3 | Run budget 300; 4 delegates → stops after 2 (V6.5); new executor, same journal, budget 1000 | steps 1–2 replay with 0 calls; steps 3–4 make 2 calls; final `spend().total.tokens == 600`, **equal to one uninterrupted run** |
| V7.4 | Journal written under budget 300; resumed script declares 1000 | the limit in effect is 1000 (it is not read from the journal) |

### Requirement 8: Reporting
*Tests: `BudgetReportingTest`, `WeaveCliBudgetTest`*

| ID | Scenario | Pass condition |
|---|---|---|
| V8.1 | 2 agents × 3 steps | `Σ byAgent == Σ byStep == total` for tokens, calls and cost; `estimated` is true iff any charge was estimated |
| V8.2 | Cross the warning threshold, then get refused | the audit log holds exactly one `budget_warning` and one `budget_refused`, with budget name, dimension, spent and limit |
| V8.3 | `weave run test.loom` with a scripted provider | stdout ends with the spend table; golden-file match on the header and one row per agent |
| V8.4 | `--max-tokens 300` on a script declaring 1000 | the run stops after 300 (as V6.5); `--max-cost` without `--prices` exits non-zero with a clear message |

### Requirement 9: Compatibility
*Tests: existing suites plus `BudgetCompatibilityTest`*

| ID | Scenario | Pass condition |
|---|---|---|
| V9.1 | Script with no budgets | no client is a `BudgetedLLMClient`; the trace is identical to the pre-change CTK expected traces |
| V9.2 | `AgentResult.Usage` getters | existing getters return the same values as before for a scripted run |
| V9.3 | Docs | the new README example is in `docs/readme_examples.loom` and `DocumentedExamplesTest` passes; the VS Code hover table has `budget`, `per_call` and `warn_at` (checked by a node script) |

---

## 3. End-to-End Scenarios

These run as JUnit integration tests with scripted providers.

- **E2E-1: Hobby run with a hard cap.** A 3-agent `.loom` with `budget { tokens: 5000 }` and an agent
  `per_call: 300`. It must finish or stop without the client ever seeing a request whose estimated
  tokens exceed what is left, and the final spend must be ≤ 5000 plus V2.7b's bound.
- **E2E-2: Stop, raise, resume.** V7.3 through `JdbcRunJournal` on H2, with a new executor in between.
  This mirrors GetViral's hosted resume.
- **E2E-3: Cost-aware routing.** A script that switches from `Writer` to `CheapWriter` when
  `_budget.remaining` falls below a threshold. The spend report shows both agents, and the switch
  happens at the predicted call.

**Manual smoke test (optional, local):** `weave run` a 3-agent script on Ollama with `--max-tokens 3000`.
Expected:
- the run stops cleanly;
- the spend table prints;
- no request exceeds the remaining budget (checked in the audit log);
- charges are marked estimated only if the model returned no usage.

---

## 4. Non-Functional Criteria

| Criterion | Measure | Target |
|---|---|---|
| Overhead | 10,000 calls through `BudgetedLLMClient` over a no-op client, after warm-up | < 500 ms total (< 50 µs per call) |
| No deadlock | V2.7c and V6.7 under `@Timeout` | always within 10 s |
| Coverage | JaCoCo on `io.github.llm4j.budget` | ≥ 90% lines, ≥ 85% branches |
| Determinism | V2.7a repeated 20 times with seeds 1–20 (`@RepeatedTest`, seeded thread interleaving) | identical totals on every run |
| No regressions | Full suites | ai-agent4j (373), Loom (41), Engram (9), eval4j (125) and GetViral (77) all green; counts may only grow |

---

## 5. Phase Exit Criteria

| Phase | Must pass before the next phase starts |
|---|---|
| 1: ai-agent4j | V1.*, V2.*, V3.1–V3.3, V3.5, V4.*; overhead, coverage and determinism criteria; full ai-agent4j suite green |
| 2: Loom | V3.4, V5.*, V6.*, V9.1–V9.2; E2E-1, E2E-3; Loom and GetViral suites green |
| 3: Durability and reporting | V7.*, V8.*, V9.3; E2E-2; every suite green; spec checkboxes ticked in `tasks.md` |

A phase is not complete if any check is skipped, disabled or marked flaky. A failing check is fixed in
the code, or the spec is changed first with the reason recorded here.

---

## 6. Traceability

| Requirement | Acceptance criteria | Checks |
|---|---|---|
| 1 Budget object | 1.1–1.5 | V1.1–V1.6, V2.7a |
| 2 Enforcement | 2.1–2.8 | V2.1–V2.8 |
| 3 Cost | 3.1–3.5 | V3.1–V3.5 |
| 4 ReActAgent | 4.1–4.6 | V4.1–V4.7 |
| 5 Loom syntax | 5.1–5.5 | V5.1–V5.5 |
| 6 Loom enforcement | 6.1–6.7 | V6.1–V6.8, E2E-1, E2E-3 |
| 7 Durability | 7.1–7.4 | V7.1–V7.4, E2E-2 |
| 8 Reporting | 8.1–8.4 | V8.1–V8.4 |
| 9 Compatibility | 9.1–9.3 | V9.1–V9.3, the no-regression criterion |

Every acceptance criterion maps to at least one check, and every check maps back to a criterion.
