# Tasks: deterministic steps in a Loom workflow. Design

Status: implemented on branch `ccr-3c772fcd-wajkf9` and verified; the evidence and the iteration log are in [verification.md](verification.md). Companion documents: [test-strategy.md](test-strategy.md), [verification.md](verification.md).

## 1. Problem

Every executable step in a Loom workflow is a `delegate`, `broadcast` or `handoff` to an **agent**, so a model sits behind it. Some steps must not have a model behind them: checking a refund against policy, calling a payments API, writing an audit record. They need to be exact, testable, free, and immune to prompt injection.

Loom today can only *branch* deterministically (`alt`, `loop until`, `guardrail`, `human_prompt`, `decide`). It cannot *compute or act* deterministically. The closest workaround, an agent with one tool and an `output_schema`, still lets a model decide whether and with what arguments the tool runs.

## 2. Goal and non-goals

**Goal.** A `Task` is a unit of plain Java code that a workflow runs as a first-class step: no model, no tokens, journaled and replayable like a `delegate`, visible in traces and reports.

Non-goals (v1):
- Tasks are **not** tools. A task is never offered to an agent, so a model cannot call it.
- Tasks cannot be loaded from the script. The operator registers them in Java (or via `ServiceLoader`), so a `.loom` file cannot make the runtime load arbitrary classes.
- Tasks do not write to workflow variables directly; they return a value and the runtime binds it.
- No new budget unit. Tasks cost zero tokens and zero model calls.

## 3. Concepts

| Term | Meaning |
|---|---|
| **Task** | `io.github.llm4j.agent.task.Task` (module `ai-agent4j`): a named unit of code, `TaskResult run(TaskContext)`. |
| **TaskContext** | The read-only input: explicit named `args`, a read-only snapshot of the workflow `variables`, the `stepId` and an `idempotencyKey`. |
| **TaskResult** | The output: an `outcome` (default `ok`), optional `reason`, optional `value`, optional `data` entries. |
| **TaskRegistry** | Where an operator registers tasks by name. |
| **`run` statement** | The Loom statement that executes a task and binds its result. |

## 4. Java API (`ai-agent4j`, package `io.github.llm4j.agent.task`)

### 4.1 `Task`

```java
public interface Task {
    String getName();                                   // [A-Za-z][A-Za-z0-9_-]*  (API-01)
    default String getDescription() { return ""; }
    TaskResult run(TaskContext context) throws Exception;
    default TaskEffect effect() { return TaskEffect.CHANGES; }   // safe default (API-02)
    default EffectPolicy policy() { return EffectPolicy.DEFAULT; }
    default boolean requiresApproval(Map<String, Object> args) { return false; }

    static Task pure(String name, TaskFunction fn);                      // effect NONE (API-03)
    static Task reads(String name, TaskFunction fn);                     // effect READS
    static Task changes(String name, EffectPolicy policy, TaskFunction fn); // effect CHANGES
    static Task of(String name, TaskEffect effect, EffectPolicy policy, TaskFunction fn);
    static boolean isValidName(String name);
}
```

`TaskEffect` is `NONE` (pure computation), `READS` (observes the outside world, changes nothing) or `CHANGES` (may change the outside world). **The default is `CHANGES`**, the conservative reading, matching how Loom treats a tool of unknown kind: it is simulated, not run, and a rewind asks first, until the task says otherwise.

`EffectPolicy` is the existing record used by effect tools (`onUnknown`, `idempotent`, `maxPerRun`), reused so tasks and tools obey the same rules.

### 4.2 `TaskContext`

```java
public interface TaskContext {
    Map<String, Object> args();                  // explicit inputs, immutable          (API-04)
    Map<String, Object> variables();             // workflow variables, immutable deep copy
    Object arg(String name);                     // null when absent
    <T> T arg(String name, Class<T> type);       // converts Number/String; throws TaskNotPerformed
    Object requireArg(String name);              // throws TaskNotPerformed when missing/null
    <T> T requireArg(String name, Class<T> type);
    Object variable(String dottedPath);          // "request.order_id"
    <T> T variable(String dottedPath, Class<T> type);
    String stepId();                             // "Main/s2"; "" outside Loom
    String idempotencyKey();                     // stable across retry and resume, unique per run; "" outside Loom and for tasks that change nothing
    static TaskContext of(Map<String, ?> args, Map<String, ?> variables);              // plain Java use and tests
    static TaskContext of(Map<String, ?> args, Map<String, ?> variables, String stepId, String idempotencyKey);
}
```

Inputs are immutable deep copies: a task cannot change workflow state behind the runtime's back, which is what makes journaling and replay sound (API-05).

### 4.3 `TaskResult`

```java
TaskResult.ok();                            // outcome "ok"
TaskResult.ok(Map<String, ?> data);         // outcome "ok" + data entries
TaskResult.value(Object value);             // outcome "ok" + "value"
TaskResult.rejected(String reason);         // outcome "rejected" + "reason"
TaskResult.outcome(String outcome);         // any outcome name, e.g. "needs_review"
result.reason(String) / .with(String key, Object value) / .withValue(Object)   // fluent, immutable
Map<String, Object> toMap();                // what the workflow variable holds
```

Rules (API-06): `outcome` is a non-blank `[a-z][a-z0-9_]*` word; `data` keys `outcome`, `reason` and `value` are reserved (use `reason()` / `withValue()`); every value must be JSON-safe (`String`, `Number`, `Boolean`, `null`, `Map<String,?>` with string keys, `List`), checked at construction, because the result is written to the run journal.

### 4.4 `TaskNotPerformed`

Unchecked exception a task throws when it **provably did nothing** (bad input, a rule refused it before any side effect). Any other exception from a `CHANGES` task means the outcome is *unknown* (the call may have reached the other side), and is treated accordingly (RUN-06).

### 4.5 `TaskRegistry` (API-07)

`register(Task)` (a duplicate name is an `IllegalArgumentException`; a deterministic step is never silently replaced), `get(name)`, `names()`, `all()`, and `TaskRegistry.discovered()`, which loads every `Task` listed in `META-INF/services/io.github.llm4j.agent.task.Task` on the classpath. The `weave` CLI and every executor use `discovered()` by default, so putting a jar of tasks on the classpath is all an operator does. Host code can call `HarnessExecutor.setTaskRegistry(...)`.

## 5. Loom language

### 5.1 Syntax

```
run <Task>(<name> = <value>, ...) -> <variable>
    [retry N] [backoff 2s] [timeout 30s]
    [on_failure { ... }]
```

`run` is a **contextual keyword**: it is a statement only where a statement starts and is followed by `Name(`. It stays usable as a variable name (and `run:` inside `schedule` blocks is unaffected).

Argument values (LANG-02):

| Written | Value passed to the task |
|---|---|
| `amount` or `request.amount` (a variable path) | the variable's **typed** value (a number stays a number, a map stays a map) |
| `"Refund for {request.order_id}"` | the string with `{...}` placeholders filled in |
| `42`, `3.5` | a number |
| `true`, `false` | a boolean |

An argument that names a variable which has no value fails the step *before the task is invoked* (fail closed; RUN-03). This is deliberately stricter than the rest of Loom, where a variable that was never set reads as empty text (`DefaultVariableContext.getVariable` returns `""`): handing a deterministic step a silent empty value would fail open, so the executor checks that the variable exists, then that the path resolves to a non-null value. Argument names may be any word that is a valid identifier, including Loom keywords.

The result variable may be `{item.field}` (dynamic) exactly as in `delegate`. The task name is always static. A `budget` modifier on `run` is a parse error ("a task spends no tokens"), so a script never states something the runtime ignores.

### 5.2 What the variable holds

`-> verdict` binds the `TaskResult.toMap()`: `{outcome: "ok", reason?: "...", value?: ..., <data...>}`. So `alt verdict.outcome == "approved"` and `{verdict.reason}` work with the existing condition and placeholder machinery.

### 5.3 Example

```
agent Intake { model: "gemini/gemini-2.5-flash" system: "You are Intake." output_schema: { order_id: string, amount: number } }

workflow Refund(msg) {
    delegate "Extract the order id and amount from: {msg}" to Intake -> request
    run RefundPolicy(order = request.order_id, amount = request.amount) -> verdict
    alt (verdict.outcome == "approved") {
        run IssueRefund(order = request.order_id, amount = request.amount) -> receipt
    } else {
        human_prompt "Refund refused: {verdict.reason}. Override?" -> decision
    }
}
```

## 6. Runtime semantics (`HarnessExecutor.executeRun`)

1. **No model.** No agent is resolved, no client is built, no tokens or calls are counted against any budget (RUN-01).
2. **Replay first.** If the journal holds an entry for this step id, a `"task"` entry rebinds the variable without invoking the task and emits `task_replayed`; a `"failed"` entry goes to `on_failure`; an entry of kind `"retry"` (an operator asked for another attempt) is ignored (RUN-05).
3. **Resolve arguments** as in 5.1. Failure → step failure, never retried (RUN-03).
4. **Approval.** If `task.requiresApproval(args)` the existing `ApprovalGate` asks a person; the answer is journaled under step + task + argument hash (so a resumed run never asks twice and an answer never approves different arguments). A "no" fails the step without invoking the task and is never retried (RUN-08).
5. **Simulation.** In a simulated run only `NONE` and `READS` tasks run. A `CHANGES` task is *described, not performed*: the variable gets `{outcome: "simulated"}`, nothing is recorded as done (RUN-09).
6. **Effect protocol for `CHANGES` tasks** (RUN-06), the same journal protocol as effect tools so a rewind scan sees them:
   - key `<identityStep>#effect:<Task>:<argHash>#<ordinal>`; `effect_pending` before the call, `effect_done` after, `effect_failed` when it provably did nothing;
   - a `pending` record found at start (the process died mid-call): if `policy.idempotent()` or `onUnknown == RETRY` the task is run again with the **same** `idempotencyKey`; otherwise the step fails with "an earlier attempt's outcome is unknown; check whether it happened, then resume with an operator retry" and the task is not invoked;
   - `policy.maxPerRun() > 0` caps calls per run (RUN-10).
7. **Run the task**, optionally under `timeout`. Retries (`retry N`, `backoff`) apply only to failures known to be safe: `TaskNotPerformed`, or any failure of a `NONE`/`READS` task, or any failure of a `CHANGES` task whose policy is idempotent or has `onUnknown = RETRY`. An *unknown* outcome of a non-idempotent `CHANGES` task is **never** retried automatically, whatever the script says (RUN-07). The loop stops at the first such failure and leaves `effect_pending` in the journal.
8. **Record.** On success: bind the variable, `journal.put(step, Entry("task", resultMap))`, emit `task_end`, write a `task_run` audit event. Arguments in trace and audit are the **sorted-key compact JSON** of the resolved arguments (the same text in every runtime) with personal data masked. On exhausted failure: `on_failure` (with `_error` in scope; the failure is journaled as `failed` so a resume goes straight to the handler) or fail the run, like `delegate` (RUN-05, RUN-11). A failure with no handler is not journaled, so resuming the run tries the step again.

The idempotency key is the first 32 hex characters of `sha-256(runUid + effectKey)`, identical across retries and resumes of the same step and unique per run, so a payments API can deduplicate. It is produced only for tasks that change things (the run uid is stored in the journal under `#effect-run-uid`, as for effect tools).

A step reached again after a **rewind** is identified by `identityStep()` (the step without its attempt), so an identical, already-`effect_done` call is found and its recorded result reused rather than repeated; `side effects: repeat` changes the identity and so repeats it.

Tasks may run concurrently inside `parallel` and `for each ... parallel`; `Task` implementations **must be thread-safe** (RUN-12).

## 7. Validation (`weave check` and `initialize()`)

| ID | Rule | Severity |
|---|---|---|
| VAL-01 | `run X(...)` where `X` is not in the task registry | error (unknown names are always errors) |
| VAL-02 | `retry` on a task that is `CHANGES` and not idempotent (and has no `onUnknown = RETRY`) | error: the script would ask for a retry that the runtime refuses for an unknown outcome. The runtime still honours it for `TaskNotPerformed` if validation is bypassed |
| VAL-03 | `run` argument syntax / duplicate argument names | parse error |
| VAL-04 | a `rewind` region contains a `CHANGES` task: needs a `side effects:` clause (as for tools); `side effects: repeat` over a non-idempotent task | warning / error, as for tools |
| VAL-05 | the `run` result variable counts as defined for `decide`/condition checks | n/a (no false "unknown variable") |

## 8. Cross-cutting integration

| ID | Where | Change |
|---|---|---|
| INT-01 | `TraceEvent`, `weave run --trace` | `task_start`, `task_end`, `task_replayed` (data: `task`, `effect`, `step`, `outcome`, `millis`, and on start `args`, `variable`); console icons |
| INT-02 | `StatementWalker`, `ScriptDrift`, `DecisionChecks`, `ScriptValidator.walk`, `hasStatementBudget` | know `RunStmt` and its `on_failure` |
| INT-03 | `RunTravel` | `task` is a journaled step kind |
| INT-04 | `eval4j` `WorkflowTrace` / `WorkflowTraceAssert`, `eval4j-report` `LoomTrace`, `WorkflowGraph`, `trace.schema.json` | tasks are non-agent nodes (kind `task`, no agent) in the graph and path; `tasksInOrder`, `taskRuns`, `taskSequence`, `taskCounts`, `taskOutcomes`; assertions `runsTasksInOrder`, `runsTaskTimes`, `taskEndedWith` |
| INT-05 | CTK | `scripts/task_basic.loom`, `task_rejected.loom`; traces with `kind: "task"`, `taskName` and canonical-JSON `payload`; `mocks/task_basic.json`. The CTK runner is a stub, so the Java runtime runs the scenarios in `TaskCtkConformanceTest`. The CTK's `pom.xml` system path was repaired so its own tests run |
| INT-06 | VS Code extension | `run` is already in the keyword grammar (it was added for `schedule { run: }`), so a `run` statement is highlighted; nothing to change |
| INT-07 | Docs | Loom guide, README, prompt, `llms.txt`, workflow guide stages 06 to 10, securing-workflows |

## 9. Security properties

- **SEC-01** A task is never reachable from a model: it is not in any `ToolRegistry`, an agent's `tools:` list cannot name it, and no statement derives a task name from text (static name only).
- **SEC-02** Text from a model or a user flows into a task only as an argument *value*; it never selects code.
- **SEC-03** Task inputs are immutable copies; results are validated JSON-safe values.
- **SEC-04** Arguments are PII-masked in audit and trace.
- **SEC-05** Task code is operator-supplied (registry/ServiceLoader), never script-supplied.

## 9b. Security audit

`weave audit` lists the number of `run` steps under the *excessive agency* controls ("deterministic task steps: plain code, no model decides them"). It cannot see inside a task: review task code like any code that moves money.

## 10. Decisions taken (from the design discussion)

1. Keyword `run`, with no `task` block in the script. 2. Registry only (plus `ServiceLoader`); no script-declared classes. 3. `TaskEffect` + reused `EffectPolicy` for idempotent/compensatable behaviour. 4. `requiresApproval(args)` mirrors `Tool.requiresApproval`. 5. CTK scenario added for runtime parity.

## 11. Open points / limitations

- A timed-out task is interrupted (`cancel(true)`) but Java cannot force-stop it; a `CHANGES` task should itself honour interruption. A timeout on a non-idempotent `CHANGES` task is an *unknown* outcome and is not retried.
- Compensation (undo) is not modelled; `rewind ... side effects: ask first | keep | repeat` is the existing mechanism.
- Cross-runtime (loom4py) task registration is out of scope; the CTK pins only the trace shape.
