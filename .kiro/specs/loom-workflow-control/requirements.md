# Requirements Document

## Introduction

Building the Nirmaan Yantra software factory on Loom needed three Java tasks that have nothing to do with software factories:

| Task | What it does | Why a script author had to write Java for it |
|---|---|---|
| `Halt` | throws, so the run ends with a reason | Loom has no statement that ends a run on purpose. The only way to stop is to fail a step, so the run is reported as "Task Halt failed: …", is indistinguishable from a bug, and an enclosing `on_failure` or `retry` may react to it |
| `Verdict` | builds `{stage, verdict, detail, waived}` | Loom can bind a variable only from a `delegate`, `run`, `human_prompt`, `decide` or `call`. A sub-workflow that must hand a **structured result** back to its caller (`-> result`) has to get it from a task, so a trivial task exists only to build a map |
| `Gate` | `met` when a step's outcome is exactly the one wanted, else `unmet` | A condition is one comparison. `alt (x.outcome != "failed")` is **fail-open** (an unexpected value counts as success) and `alt (x.outcome == "passed")` is **fail-closed**, but the closed form never holds in a simulated run (`weave eval --mock`, `weave fork --effects simulate`) because the runtime describes a step that changes things and gives it the outcome `simulated`. The author must pick between a safe script and a testable one. `Gate` is the workaround: it is the one place that accepts exactly the wanted word plus `simulated` |

All three are generic: they say how a **workflow behaves**, not what a **domain does**. A task is the wrong home for them: it is code a script's operator must supply, it is invisible to `weave audit`, it cannot be drawn faithfully in the graph (a `Halt` is a task node, not an end), and every workflow author will re-invent it.

This spec makes them part of the language:

| Today | Becomes |
|---|---|
| `run Halt(reason = "…") -> halted` | `halt "…"` |
| `run Verdict(stage = …, verdict = "PASSED", detail = …) -> result` | `return { verdict: "PASSED", detail: … }` (with `set name = value` for the general case) |
| `run Gate(outcome = x.outcome, expect = "passed") -> g` … `until (g.outcome == "met")` | `until (x is ok)`, where a task says which of its outcomes are `ok` |

The standing rules from earlier Loom specs still apply:

- **Nothing is silently ignored.** Anything the runtime can't honour is a load error that names the line.
- **Existing valid scripts and journals keep working.** `halt`, `set`, `return` and `is ok` are contextual: a variable or agent with one of those names still works.
- **Logic lives in the framework**, with the budgets, approvals, guards, audit and journal that exist.
- **Agents are not given the power to halt a run or to mark a step ok.** Only the script does.

**Out of scope** (each a follow-up, not a gap in this one):

- Compound conditions (`&&`, `||`, `not`). `is not ok` is the only negation added.
- `is ok` for the result of a `delegate`, `human_prompt` or `decide` (R3.7 says what happens if it is written there).
- Expression arithmetic in `set`. A `set` value is a literal, an interpolated string, or a copy of a variable.
- A general `fail`-with-recovery construct. `halt` is terminal by design; recovery is `on_failure`.

## Glossary

- **Halt**: ending a run on purpose, with a reason, because the workflow's own rule says it cannot go on.
- **Step result**: the map a `run` step binds (`outcome`, optional `reason` and `value`, and data entries).
- **ok outcome**: an outcome a task declares means "it did what was wanted". Default `ok`.
- **Simulated step**: a `run` step the runtime only described (its result carries `simulated: true`, outcome `simulated`).

## Requirements

### Requirement 1: `halt`

**User Story:** As a workflow author, I want to end a run on purpose with a reason, so that a stage that cannot go on does not leave later stages working on something broken, and so that the run says why in words that are not "a task failed".

1. `halt "text"` is a statement. The text may use `{variable}` placeholders like a `note`.
2. A halt ends the whole run (from any depth: a `call`, a loop, an `alt`, a `parallel` branch). The run's status is **halted**, distinct from failed, budget-stopped, suspended and completed.
3. A halt is not a step failure: it is not retried, it does not run an enclosing step's `on_failure`, it does not trigger a `rewind`, and a loop's `on_exhausted` does not run.
4. Inside `parallel` a halt is the reason the run ended, whatever its sibling branches report. Each branch is one statement and finishes it; nothing new is started after the join.
5. The reason is recorded in the journal, the trace (`halt` event), the audit log, the run's final line, `weave run`'s exit status (a new code) and the Fabric run list. It passes through the same secret scrubbing and PII masking as a `note`.
6. A resumed run that had halted does not run anything again: it reports the same halt and exits with the same code. `weave reset` can clear it (as for a failed run).
7. `weave check`: a statement after a `halt` in the same block is a warning ("never reached"); `halt` with no text is an error.
8. `weave graph` draws a halt as a terminal node: an edge to the end, none to the next statement.

### Requirement 2: `set` and `return`

**User Story:** As a workflow author, I want to give a variable a value and hand a structured result back to a caller without writing Java, so that stages can report `{verdict, detail}` the way an agent does.

1. `set name = value` assigns a workflow variable. `value` is one of: a string (with `{placeholders}`), a number, `true` or `false`, a variable or `var.path` (a typed copy), or an object literal `{ key: value, … }` whose values are any of these. Lists are out of scope.
2. `return value` is `set result = value` followed by the end of the workflow. The caller's `-> var` receives it (as it receives `result` today).
3. A workflow that ends without `return` and without having set `result` returns nothing, as today.
4. Values are typed: a number stays a number, an object stays an object, so `alt (r.score > 3)` and `{r.verdict}` work.
5. `set` does not invent magic: it cannot assign a name that is a workflow parameter being used as a `call` argument name in the same statement, and a name starting with `_` is reserved (as today).
6. `weave check`: assigning a variable that is never read is the same warning as for a `delegate` result; `return` in the entry workflow is allowed and ends the run normally.
7. Contextual: `set` and `return` are statements only at the start of a statement followed by a name and `=` (for `set`) or by a value (for `return`); a variable called `set` or `return` still works.

### Requirement 3: `is ok`

**User Story:** As a workflow author, I want to ask "did that step do what was wanted?" in a form that fails closed and still lets the free mock evaluation reach the end, so I do not have to choose between a safe script and a testable one.

1. A condition may be `name is ok` or `name is not ok`, where `name` is the variable bound by a `run` step.
2. A task declares its ok outcomes: `Task.okOutcomes()` (default `{"ok"}`). `RunCommand` would declare `{"passed"}`.
3. `x is ok` is true exactly when the step ran and its outcome is one of the task's ok outcomes, **or the step was simulated**. Anything else is false: another outcome, an unexpected word, a missing variable, a step that never ran.
4. `x is not ok` is exactly the negation of R3.3, so it fails closed too: in a real run an unset `x` is "not ok".
5. In a simulated run (R3.3, second clause) a simulated step counts as ok because nothing ran that could have failed. A real run never has simulated steps, so the clause cannot weaken a real run. The runtime, not the script, decides what "simulated" means, using the `simulated` flag the step already carries.
6. `is ok` composes where a condition is accepted today: `alt`, `loop until`, `rewind … when`.
7. `x is ok` where `x` is not a `run` result (a `delegate`, `human_prompt` or `decide` variable, or a parameter) is a **load error** naming the line, not a silent false.
8. The explicit comparison `x.outcome == "word"` keeps working and keeps meaning exactly that (including in a simulated run, where it is false).

### Requirement 4: Everything that reads a script knows the new statements

1. The parser, the validator, `weave check`, `weave explain`, `weave graph` (JSON and Mermaid), the VS Code extension (grammar, snippets, the graph renderer and its sync), the audit and the eval4j trajectory assertions all handle `halt`, `set`, `return` and `is ok`.
2. `weave explain` says them in plain English ("Stops the run, saying: …", "Hands back …", "Continue when X is ok").
3. eval4j gains `haltedWith(reason)` and `didNotHalt()` assertions.
4. `weave audit` is unaffected (these statements reach nothing), and says so in the guide.
5. The Fabric run list and the live page show the status **halted** and its reason.

### Requirement 5: The proof

**User Story:** As the maintainer, I want one real workflow to prove the constructs are enough, so that the feature is not justified by examples nobody runs.

1. After this lands, `src/examples/nirmaan-yantra` deletes `Gate`, `Halt` and `Verdict` (and their registrations and tests) and uses `halt`, `return` and `is ok` instead.
2. Its `weave check`, `weave eval --mock` and trajectory tests pass unchanged in what they assert (the same paths, the same call counts).
3. Its fail-closed behaviour is **stronger or equal**: no check becomes fail-open.
