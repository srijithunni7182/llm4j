# Design Document

## Overview

Three additions to the Loom language and runtime, small because the machinery exists: the run journal already records every step by id, `RunSuspended` and `RunStopped` already end a run without a failure, and `TaskRunner` already marks a simulated step (`data.put("simulated", true)`, outcome `simulated`).

```
halt "reason"            ──▶ throws RunHalted(reason)   (not a StepFailure: retry/on_failure/rewind do not see it)
set x = { … } / return …  ──▶ evaluates a literal to a typed value, stores it in the variable context
x is ok                  ──▶ ConditionEvaluator asks the step's recorded result: outcome ∈ task.okOutcomes() ∨ simulated
```

## 1. Language

### 1.1 Grammar

```
statement   := … | halt | set | return
halt        := "halt" STRING
set         := "set" NAME "=" value
return      := "return" value
value       := STRING | NUMBER | "true" | "false" | path | object
object      := "{" [ NAME ":" value { "," NAME ":" value } ] "}"
condition   := comparison | NAME "is" "ok" | NAME "is" "not" "ok" | NAME      (existing forms unchanged)
```

Contextual words, as `run` and `checkpoint` are: `halt` starts a statement only when a string follows; `set` only when `NAME =` follows; `return` only when a value follows and the next token is not `=` or `(`; `is ok` / `is not ok` only inside a condition after a name. A variable named `halt`, `set`, `return` or `ok` keeps working.

### 1.2 Examples

The nirmaan-yantra `Fix` workflow, today and after:

```loom
// today
run RunCommand(project = project_id, command = command_line, label = "fix") -> check_run
run Gate(outcome = check_run.outcome, expect = "passed") -> check_gate
loop until (check_gate.outcome == "met") max 3 budget 250000 tokens {
    …
    run RunCommand(project = project_id, command = command_line, label = "fix") -> check_run
    run Gate(outcome = check_run.outcome, expect = "passed") -> check_gate
}
alt (check_gate.outcome == "met") {
    run Verdict(stage = "fix", verdict = "PASSED", detail = "passes: {command_line}") -> result
} else {
    human_prompt "{command_line} still fails after 3 fixes: …\n\nCarry on anyway? Answer exactly: yes or no" -> fix_waiver
    alt (fix_waiver != "yes") {
        run Halt(reason = "{command_line} does not pass and a person chose to stop: {check_run.reason}") -> halted
    }
    run Verdict(stage = "fix", verdict = "PASSED", waived = "yes", detail = "a person let it through: {command_line}") -> result
}

// after
run RunCommand(project = project_id, command = command_line, label = "fix") -> check_run
loop until (check_run is ok) max 3 budget 250000 tokens {
    …
    run RunCommand(project = project_id, command = command_line, label = "fix") -> check_run
}
alt (check_run is ok) {
    return { verdict: "PASSED", waived: "no", detail: "passes: {command_line}" }
}
human_prompt "{command_line} still fails after 3 fixes: …\n\nCarry on anyway? Answer exactly: yes or no" -> fix_waiver
alt (fix_waiver != "yes") {
    halt "{command_line} does not pass and a person chose to stop: {check_run.reason}"
}
return { verdict: "PASSED", waived: "yes", detail: "a person let it through: {command_line}" }
```

Three tasks and five generic steps become zero tasks and two statements; the `else` disappears because `return` and `halt` end the path.

## 2. AST and parser

New nodes `HaltStmt(message)`, `SetStmt(name, Value)`, `ReturnStmt(Value)`; a `Value` is a sealed type (`Text`, `Number`, `Bool`, `Ref`, `Obj`). The condition AST gains `OkCondition(name, negated)`. `StatementWalker`, the lint walkers and the graph builder get the three statements; `assigned(Statement)` in `WorkflowLint` learns `SetStmt` (and reads `run` arguments, which it ignores today: see the note at the end).

## 3. Executor

### 3.1 `halt`

`executeStatement` evaluates the message (`resolvePayload`, then the same masking as `note`), writes a journal entry `{kind: "halt", value: reason}` under the step id, emits trace `halt`, and throws `RunHalted(reason)`.

`RunHalted extends RuntimeException` and is deliberately **not** a `StepFailure`: the places that catch step failures (`retry`, `on_failure`, `rewind … on failure`, loop `on_exhausted`) catch `StepFailure`/`Exception` today and must be changed to let `RunHalted` through; a test per place pins it (V1.3). `parallel` cancels sibling branches the way a budget stop does. `executeWorkflow` records the final status `halted` (`run_end.data.status`) and rethrows.

A resumed run replays the journal: reaching the step id finds the `halt` entry and throws `RunHalted` again without evaluating anything.

### 3.2 `set` and `return`

`SetStmt` evaluates its `Value` to a plain Java value (`String`, `Long`/`Double`, `Boolean`, `LinkedHashMap`) and calls `setVariable`. Text values go through `resolvePayload` and objects are built recursively, so placeholders work inside. `ReturnStmt` is `set result` then a `ReturnSignal` (an unchecked control-flow exception caught by `runWorkflow`, like `RewindSignal`) that unwinds to the workflow boundary; a `call` reads `result` exactly as `executeCall` does now. Nothing is journaled for a `set` (it is pure and re-derived on resume, as `note` is).

### 3.3 `is ok`

**As built.** The result map keeps exactly its documented shape (`outcome`, `reason`, `value`, data), so nothing that reads or prints it changes. Beside it `TaskRunner` sets a hidden variable `_ok:<name>` (a boolean) at the four places a `run` result is bound: executed, replayed from the journal, reused after a rewind, and simulated. It is `task.okOutcomes().contains(outcome)` for the first three (recomputed on replay, so a journal from before `is ok` works) and `true` for a simulated step. `ConditionEvaluator` reads it:

```java
static boolean ok(String path, VariableContext vars) {
    String head = path.contains(".") ? path.substring(0, path.indexOf('.')) : path;
    return Boolean.TRUE.equals(vars.getVariable("_ok:" + head));     // never ran, or not a run result: no such variable: closed
}
```

A condition is recognised by the text `name is ok` / `name is not ok` (the parser keeps those words, with single spaces, where it used to join tokens with none).

The static check (R3.7) rejects `is ok` on a name whose only assignments are not `run` steps; it is a validator rule, so the error carries a line number.

### 3.4 Task API

```java
public interface Task {
    /** The outcomes that mean "it did what was wanted". Anything else is not ok. */
    default Set<String> okOutcomes() { return Set.of("ok"); }
}
```

The default means every existing task keeps working: a task that returns `TaskResult.ok()` is ok, `rejected(...)` is not.

## 4. Exit codes, status, events

| Thing | Value |
|---|---|
| `weave run` exit code | **6** = halted (0 done, 1 failed, 2 bad options, 3 budget, 4 paused, 5 stop-at) |
| `run_end.data.status` | `halted`, with `data.reason` |
| trace event | `halt` {reason, step} |
| audit event | `run_halted` {reason} (masked) |
| Fabric run list / live page | `halted` is mapped to the existing **stopped** state (loom-inspect's `RunState`, which would otherwise leave the run showing as running forever); the reason is in the run's trace |
| `RunHalted` from `executeWorkflow` | thrown; hosts catch it next to `BudgetExceeded` and `RunSuspended` |

## 5. Tooling

- **graph**: node kinds `halt` (terminal), `set`, `return` (terminal for its path); `is ok` conditions render as `x is ok`. The renderer and the extension's copy stay in sync with `scripts/sync-graph-render.sh`.
- **explain**: three new sentence forms (R4.2).
- **check/lint**: unreachable-after-`halt`; `is ok` target rule; `set` target never read.
- **eval4j**: `WorkflowAssertions.haltedWith(String)` / `didNotHalt()`; `LoomTrace` already records the new event type once it is in `TraceEvent`.
- **VS Code**: TextMate grammar, snippets (`halt`, `set`, `return`, `is ok`), the graph viewmodel.

## 6. Compatibility

No journal format change for existing runs. A journal from the new build contains a `halt` entry kind; an older `weave` resuming it would not know it: the run-directory format marker (introduced by rewind-and-fork) is bumped only when a script *uses* `halt`, so old scripts and journals are untouched.

## 7. Found while writing this spec (separate, small)

`WorkflowLint` reads the text of statements by reflection and ignores the arguments of `run` steps (`RunStmt.getArgs()` returns `Arg` objects it does not unwrap). Any variable consumed only as a task argument is reported "set and never used", and a loop whose variable is set by a `run` is reported "never runs". Nirmaan Yantra's `weave check` shows eight such warnings on a correct script. A one-line fix in `append()` (handle `Arg`) is part of this work because the new `set` statement needs the same reader.
