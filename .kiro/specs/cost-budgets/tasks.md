# Implementation Plan: Cost Budgets

## Overview

The work is in three phases, each shippable on its own:

1. **ai-agent4j enforcement.** `Budget`, `BudgetSet`, `BudgetedLLMClient`, `PriceTable` and
   `ReActAgent` support. Plain Java users can cap spend after this phase alone.
2. **Loom syntax and runtime.** Budgets declared in `.loom` scripts and enforced by the harness.
3. **Durability, reporting and adoption.** Usage in the journal, resume after raising the cap, spend
   reports, the CLI, documentation, then GetViral.

Defaults (decided): tokens are the unit unless a price table is given; an agent that runs out returns
its partial answer.

---

## Tasks

<!-- ================================================================ -->
<!-- PHASE 1: ai-agent4j                                              -->
<!-- ================================================================ -->

- [ ] 1. Budget core (`io.github.llm4j.budget`)
  - [ ] 1.1 `Budget`, `Budget.Builder`, `Limits`, `Spent`, `Remaining`, `Charge`, `Reservation` with
        per-budget lock, reserve/settle/restore and warning threshold
    - _Requirements: 1.1, 1.2, 1.3, 1.4_
  - [ ] 1.2 `BudgetSet` with all-or-nothing reservation in a fixed lock order and a `Lease` that
        settles or releases every member
    - _Requirements: 1.5, 2.7_
  - [ ] 1.3 `BudgetExceeded extends AgentInterrupt` carrying budget name, dimension, spent and limits
    - _Requirements: 2.1, 4.4_
  - [ ] 1.4 Unit tests: limits per dimension, unlimited dimensions, warning fires once, all-or-nothing
        release, 32-thread stress test (no overdraw, exact totals)
    - _Requirements: 1.1–1.5, 2.7_

- [ ] 2. Metering at the call
  - [ ] 2.1 `TokenEstimator` interface and `CharsPerTokenEstimator` (4 chars/token × 1.1)
    - _Requirements: 2.1, 2.5_
  - [ ] 2.2 `PriceTable` (`load(Path)`, `of(Map)`, `ollama/*` = 0, unknown = empty)
    - _Requirements: 3.1, 3.2, 3.3, 3.5_
  - [ ] 2.3 `BudgetedLLMClient`: preflight refusal, output cap on `maxTokens`, minimum useful
        output, settle with reported or estimated usage, failed-call charge, streaming settle on close
    - _Requirements: 2.1–2.6_
  - [ ] 2.4 Unit tests with a scripted client: refused call never reaches the delegate; `maxTokens`
        lowered; estimated usage flagged; failed call charged; cost computed from input and output
        separately
    - _Requirements: 2.1–2.6, 3.5_

- [ ] 3. ReActAgent support
  - [ ] 3.1 Builder options `budget`, `maxTokensPerCall`, `onBudgetExhausted(RETURN_PARTIAL | FAIL)`;
        wrap the client only when budgeted and not already wrapped
    - _Requirements: 4.1, 9.1_
  - [ ] 3.2 Catch `BudgetExceeded` around the LLM call: partial result (default) or rethrow; add
        `StepOutcome.BUDGET_EXHAUSTED` and `AgentResult.budgetExhausted()`
    - _Requirements: 4.2, 4.3, 4.4_
  - [ ] 3.3 `AgentResult.Usage` gains `estimated` and `cost`; `AgentEventListener.onBudget` default
        method with WARNING / EXHAUSTED events
    - _Requirements: 4.5, 4.6, 9.2_
  - [ ] 3.4 Tests: partial answer returned with outcome and usage; FAIL policy propagates; no retry
        after refusal; listener receives warning then exhausted; unbudgeted agent unchanged
    - _Requirements: 4.1–4.6, 9.1_

- [ ] 4. Checkpoint: `mvn install` in ai-agent4j; all existing tests still pass

<!-- ================================================================ -->
<!-- PHASE 2: Loom                                                    -->
<!-- ================================================================ -->

- [ ] 5. Syntax
  - [ ] 5.1 Contextual `budget` keyword: top-level block, agent block (`per_call` allowed), statement
        modifier on `delegate`, `broadcast`, `loop until`, `for each`
    - _Requirements: 5.1, 5.2, 5.3, 5.4_
  - [ ] 5.2 AST: `BudgetDef` on `LoomScript` and `AgentDef`; `BudgetLimit` on the four statements
    - _Requirements: 5.1–5.3_
  - [ ] 5.3 Validation: non-positive values and `cost` without currency symbol are ParseErrors with
        the line
    - _Requirements: 5.5_
  - [ ] 5.4 Parser tests, including a script that uses `budget` as a variable name
    - _Requirements: 5.1–5.5_

- [ ] 6. Enforcement in `HarnessExecutor`
  - [ ] 6.1 Run budget (always present for counting), agent budgets, and a thread-local scope stack
        for step/loop/for-each budgets that `parallel` branches inherit
    - _Requirements: 6.1, 6.7_
  - [ ] 6.2 Wrap every factory client (including routing tiers) in `BudgetedLLMClient` with a
        per-call `Supplier<BudgetSet>`; only when any budget is declared
    - _Requirements: 6.1, 9.1_
  - [ ] 6.3 Outcomes: refused step → `on_failure`, no retry; partial result → bind + `_budget.exhausted`
        + `on_failure`; loop/for-each → `on_exhausted` with `_loopExhaustedBy`; unhandled →
        `BUDGET_EXCEEDED` with context kept
    - _Requirements: 6.2, 6.3, 6.4, 6.5_
  - [ ] 6.4 `_budget.spent / remaining / calls / exhausted` readable in payloads and conditions
    - _Requirements: 6.6_
  - [ ] 6.5 Cost limits: fail `initialize()` if any model the script uses is unpriced
    - _Requirements: 3.4_
  - [ ] 6.6 Tests: each outcome above; `parallel` branches share one budget exactly; `alt` routing on
        `_budget.remaining`; unbudgeted scripts behave as before
    - _Requirements: 6.1–6.7, 9.1_

- [ ] 7. Checkpoint: Loom suite green; GetViral suite green against the new Loom

<!-- ================================================================ -->
<!-- PHASE 3: Durability, reporting, adoption                         -->
<!-- ================================================================ -->

- [ ] 8. Durable budgets
  - [ ] 8.1 Journal `stepId#usage` entries (new `usage` entry kind) for every settled step
    - _Requirements: 7.1_
  - [ ] 8.2 On resume, restore run and agent spend from usage entries; replayed steps never charge
    - _Requirements: 7.2, 7.4_
  - [ ] 8.3 Tests: resume charges nothing for replayed steps; a run stopped at its cap and resumed
        with a bigger cap finishes, with totals equal to one uninterrupted run
    - _Requirements: 7.2, 7.3_

- [ ] 9. Reporting and CLI
  - [ ] 9.1 `SpendReport` via `executor.spend()`: totals, by agent, by step, estimated flag
    - _Requirements: 8.1_
  - [ ] 9.2 Audit events `budget_warning` and `budget_refused`
    - _Requirements: 8.2_
  - [ ] 9.3 `weave run`: `--max-tokens`, `--max-calls`, `--max-cost`, `--prices`; spend table at the end
    - _Requirements: 8.3, 8.4_

- [ ] 10. Documentation
  - [ ] 10.1 LOOM_GUIDE "Budgets" section, README feature entry, LOOM_PROMPT syntax, VS Code grammar
        and hover docs; add the example to `docs/readme_examples.loom`
    - _Requirements: 9.3_
  - [ ] 10.2 ai-agent4j README: `Budget` and `BudgetedLLMClient` usage for plain Java

- [ ]* 11. GetViral adoption (optional, after the libraries ship)
  - [ ]* 11.1 A per-pack `budget { }` in `getviral.loom` and `per_call` caps on the chattiest agents
  - [ ]* 11.2 Live spend meter in the studio from budget events; spend in the pack export
  - [ ]* 11.3 Monthly token cap per creator next to the pack quota

- [ ] 12. Final checkpoint
  - All suites green: ai-agent4j, Loom, Engram, eval4j, GetViral
  - Documented examples parse
  - Ask the user if questions arise

---

## Notes

- Tasks marked with `*` are optional.
- No prices ship with the libraries; price files are the developer's, and keys stay in environment
  variables.
- Budget exhaustion is never retried anywhere; it extends `AgentInterrupt` for that reason.
- `budget` is a contextual keyword, so no existing script breaks.
- Estimation (4 chars ≈ 1 token × 1.1) is only used before a call and when a provider reports no usage;
  reported usage always wins.
