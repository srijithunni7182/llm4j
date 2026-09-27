# Requirements Document

## Introduction

Cost Budgets lets a developer cap what an agent system may spend on LLM calls, and have that cap enforced
by the runtime instead of hoped for. It spans two modules:

1. **ai-agent4j** enforces budgets at the only place money is spent: the `LLMClient.chat()` call. It adds
   a budget object, a client decorator that checks before and charges after every call, an optional price
   table, and budget support in `ReActAgent`.
2. **Loom** lets a `.loom` script declare budgets in three places: the whole run, an agent, and a single
   step or loop. They are enforced by the harness with the same symbolic guarantees as `retry`, `max` and
   `on_failure`, and they are durable across suspended and resumed runs.

Decisions already taken:
- **Tokens are the default unit.** Money budgets are optional and need a price table the developer
  supplies. No prices are built in, because they go stale.
- **An agent that runs out mid-task returns its partial answer** by default, marked as budget-exhausted,
  rather than failing. Failing is an opt-in policy.

All changes are additive. Scripts and Java code without budgets behave exactly as today.

---

## Glossary

- **Budget**: A thread-safe allowance of tokens, LLM calls and (optionally) cost, with running totals of
  what has been spent.
- **Budget_Set**: The budgets that apply to one LLM call, for example run + agent + step. A call is allowed
  only if every budget in the set has room, and it is charged to all of them.
- **BudgetedLLMClient**: An `LLMClient` decorator that checks a Budget_Set before each call, lowers the
  request's output cap to what can be afforded, and charges actual usage afterwards.
- **Preflight_Estimate**: The tokens a call is expected to use before it is made: estimated prompt tokens
  plus the requested (or capped) output tokens.
- **Reservation**: The part of a budget held for a call between its preflight check and its settlement,
  so that concurrent calls cannot jointly overspend.
- **Settlement**: Replacing a Reservation with the call's actual usage.
- **Price_Table**: A developer-supplied mapping from model id to price per million input and output tokens.
- **BudgetExceeded**: The interrupt raised when a call would exceed a budget. It extends `AgentInterrupt`
  and is never retried.
- **Partial_Result**: The answer an agent returns when its budget runs out mid-task, with outcome
  `BUDGET_EXHAUSTED`.
- **Spend_Report**: Totals of tokens, calls and cost per run, per agent and per step.
- **HarnessExecutor**, **RunJournal**, **LLMClientFactory**, **ReActAgent**, **AgentResult**: The existing
  Loom and ai-agent4j components of those names.

---

## Requirements

### Requirement 1: Budget Object

**User Story:** As a Java developer, I want a budget object I can give to agents and clients, so that I
can cap tokens, calls and cost for any piece of work.

#### Acceptance Criteria

1. THE Budget SHALL support independent limits on total tokens, number of LLM calls and cost; any
   limit may be left unset (unlimited).
2. THE Budget SHALL expose spent and remaining amounts for each limited dimension.
3. THE Budget SHALL be safe to use from concurrent threads, and the sum of settled charges SHALL equal
   its spent total exactly.
4. THE Budget SHALL support a warning threshold (default 80%) and report when it is first crossed.
5. WHERE several budgets apply to one call, THE Budget_Set SHALL allow the call only if every budget has
   room, and SHALL charge the call to every budget in the set.

---

### Requirement 2: Enforcement at the LLM Call

**User Story:** As a developer on a hobby budget, I want an over-budget call to be refused before it is
made, so that a runaway agent can never spend more than I allowed.

#### Acceptance Criteria

1. WHEN a call's Preflight_Estimate exceeds the remaining amount of any budget in its Budget_Set, THE
   BudgetedLLMClient SHALL raise BudgetExceeded without calling the model.
2. WHEN a call is allowed, THE BudgetedLLMClient SHALL set the request's `maxTokens` to no more than
   the output tokens the Budget_Set can still afford after the estimated prompt.
3. WHEN the affordable output is below a minimum useful size (default 64 tokens), THE BudgetedLLMClient
   SHALL refuse the call as in criterion 1.
4. WHEN a call completes, THE BudgetedLLMClient SHALL settle the Reservation with the provider's reported
   usage.
5. IF the provider reports no usage, THEN THE BudgetedLLMClient SHALL settle with an estimate and mark
   the charge as estimated.
6. IF a call fails after being sent, THEN THE BudgetedLLMClient SHALL settle the estimated prompt tokens
   and one call, since providers may bill failed requests.
7. WHILE concurrent calls share a budget, THE BudgetedLLMClient SHALL hold a Reservation between
   preflight and Settlement so that the budget is never overdrawn by more than one call's
   estimation error.

---

### Requirement 3: Cost Budgets (Optional)

**User Story:** As a developer, I want to cap spend in money when I choose to, so that I can reason in
dollars as well as tokens.

#### Acceptance Criteria

1. THE Budget SHALL accept a cost limit only when a Price_Table is supplied.
2. THE Price_Table SHALL be loaded from a developer-supplied file or API; the library SHALL NOT ship
   built-in prices.
3. WHERE a model id has the `ollama/` prefix and no Price_Table entry, THE Price_Table SHALL price it at
   zero.
4. IF a cost limit is set and a model used by the run has no Price_Table entry, THEN THE runtime SHALL
   fail at startup with a message naming the unpriced model.
5. THE cost of a call SHALL be computed from its settled input and output tokens separately.

---

### Requirement 4: Budgets in ReActAgent

**User Story:** As a Java developer, I want to give an agent a budget, so that it stops cleanly and still
gives me something when the money runs out.

#### Acceptance Criteria

1. THE ReActAgent builder SHALL accept a Budget and a per-call output cap (`maxTokensPerCall`).
2. WHEN a budget runs out mid-task, THE ReActAgent SHALL by default return a Partial_Result: the best
   answer so far (the last thought or observation summary), `completed = false`, and outcome
   `BUDGET_EXHAUSTED`.
3. WHERE the agent's policy is set to FAIL, THE ReActAgent SHALL propagate BudgetExceeded instead.
4. THE ReActAgent SHALL never retry a call that raised BudgetExceeded.
5. THE AgentResult SHALL report the agent's usage, including whether any of it was estimated.
6. THE AgentEventListener SHALL receive a budget event when a warning threshold is crossed and when a
   budget is exhausted.

---

### Requirement 5: Declaring Budgets in Loom

**User Story:** As a Loom script author, I want to declare budgets in the script in one line each, so that
spending rules live next to the workflow they govern.

#### Acceptance Criteria

1. THE Parser SHALL accept a top-level `budget { tokens: N  calls: N  cost: "$X"  warn_at: P% }` block
   applying to the whole run; every field is optional.
2. THE Parser SHALL accept a `budget { tokens: N  calls: N  cost: "$X"  per_call: N }` block inside an
   agent definition, where `per_call` caps each LLM answer's output tokens.
3. THE Parser SHALL accept a `budget N tokens` (or `budget N calls`, `budget "$X"`) modifier on
   `delegate`, `broadcast`, `loop until` and `for each` statements.
4. THE Parser SHALL treat `budget` as a contextual keyword so that existing scripts using `budget` as a
   variable name keep working.
5. THE Parser SHALL reject a negative or zero limit, and a `cost` limit written without a currency
   symbol, with a ParseError naming the line.

---

### Requirement 6: Enforcement in Loom

**User Story:** As a Loom script author, I want budget exhaustion to behave like the failure and bound
constructs I already know, so that I can handle it symbolically.

#### Acceptance Criteria

1. THE HarnessExecutor SHALL apply to each LLM call the Budget_Set of the run budget, the calling agent's
   budget, and every enclosing step, loop and for-each budget.
2. WHEN a step's call is refused by preflight, THE HarnessExecutor SHALL treat the step as failed with
   `{_error}` naming the budget that ran out, run its `on_failure` block if present, and SHALL NOT retry it.
3. WHEN an agent returns a Partial_Result, THE HarnessExecutor SHALL bind the partial value to the
   step's output variable, set `{_budget.exhausted}` to `true`, and run the step's `on_failure` block
   if present; without `on_failure`, execution SHALL continue.
4. WHEN a loop's or for-each's budget runs out, THE HarnessExecutor SHALL stop iterating and run the
   loop's `on_exhausted` block if present, with `{_loopExhaustedBy}` set to `budget` (or `rounds` when
   `max` was reached).
5. IF budget exhaustion is not handled by any enclosing construct, THEN THE HarnessExecutor SHALL stop
   the run with status `BUDGET_EXCEEDED`, keeping every variable already bound.
6. THE HarnessExecutor SHALL expose `{_budget.spent}`, `{_budget.remaining}`, `{_budget.calls}` and
   `{_budget.exhausted}` for the run budget, usable in payloads and in `alt` / `loop until` conditions.
7. WHILE `parallel` branches or `parallel for each` items run concurrently, THE HarnessExecutor SHALL
   charge them to the same shared budgets with Reservations (Requirement 2.7).

---

### Requirement 7: Budgets and Durable Runs

**User Story:** As a developer whose run stopped at its cap, I want to raise the cap and resume, so that
I don't pay again for work already done.

#### Acceptance Criteria

1. WHEN a step completes, THE HarnessExecutor SHALL journal the step's usage alongside its result.
2. WHEN a run is resumed from a RunJournal, THE HarnessExecutor SHALL initialise the run's spent totals
   from the journaled usage and SHALL NOT charge replayed steps again.
3. WHEN a run that stopped with `BUDGET_EXCEEDED` is resumed with a larger budget, THE HarnessExecutor
   SHALL continue from the refused step.
4. THE budget limits in effect SHALL come from the script (or its overrides) at resume time, not from
   the journal.

---

### Requirement 8: Reporting

**User Story:** As a developer, I want to see where my tokens went, so that I can make the expensive parts
cheaper.

#### Acceptance Criteria

1. THE HarnessExecutor SHALL provide a Spend_Report with totals per run, per agent and per step, with
   estimated charges marked.
2. THE audit logger SHALL record a `budget_warning` event when a warning threshold is crossed and a
   `budget_refused` event when a call is refused.
3. WHEN the `weave` CLI finishes a run, it SHALL print the Spend_Report as a table.
4. THE `weave` CLI SHALL accept `--max-tokens`, `--max-calls` and `--max-cost` flags that override the
   script's run budget.

---

### Requirement 9: Compatibility

**User Story:** As an existing user, I want nothing to change unless I opt in.

#### Acceptance Criteria

1. WHERE no budget is declared, THE HarnessExecutor and ReActAgent SHALL behave exactly as before and
   SHALL NOT wrap LLM clients in a BudgetedLLMClient.
2. THE existing `AgentResult.Usage` fields SHALL keep their meaning.
3. THE Loom language guide, LOOM_PROMPT, README and VS Code grammar SHALL document the new syntax.
