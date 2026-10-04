# Creating Tasks

A **task** is a deterministic step of a workflow: plain Java, no model, no tokens. Where a [tool](Creating-Custom-Tools.md) is
something a model *chooses* to call, a task is something the workflow *runs*. Use one for the parts of a process that are too
important to leave to an LLM: checking a refund against policy, calling a payments API, writing an audit record.

|  | Tool | Task |
|---|---|---|
| Who decides it runs | the model, in its reasoning loop | the workflow script (`run Name(...)` in Loom) |
| Offered to a model | yes | never |
| Same input, same output | not guaranteed | yes, it is your code |
| Costs tokens | the model's calls around it do | no |

Tasks live in the core library (`io.github.llm4j.agent.task`), so you can use them from plain Java. The [Loom](../../loom/ai-agent4j-loom/LOOM_GUIDE.md#tasks-deterministic-steps-run)
language runs them as `run` steps and adds journaling, replay and crash safety.

## The interface

```java
public interface Task {
    String getName();                                   // a letter, then letters, digits, _ or -
    TaskResult run(TaskContext context) throws Exception;

    default String getDescription() { return ""; }
    default TaskEffect effect() { return TaskEffect.CHANGES; }       // NONE | READS | CHANGES
    default EffectPolicy policy() { return EffectPolicy.DEFAULT; }   // idempotent? what if the outcome is unknown? cap per run
    default boolean requiresApproval(Map<String, Object> args) { return false; }
}
```

For a lambda use the factories: `Task.pure(name, fn)` (computation only), `Task.reads(name, fn)` (observes the world), `Task.changes(name, policy, fn)`.

**`effect()` defaults to `CHANGES`.** That is the safe assumption: a runtime will not run such a task in a simulation and will not repeat it when it
does not know whether an earlier attempt happened. A pure task says so (`Task.pure(...)` or `effect() { return TaskEffect.NONE; }`).

## What a task is given: `TaskContext`

```java
Task policy = Task.pure("RefundPolicy", ctx -> {
    String order  = ctx.requireArg("order", String.class);      // the named argument the script passed
    double amount = ctx.requireArg("amount", Double.class);     // numbers and numeric strings convert
    String tier   = ctx.variable("customer.tier", String.class); // any workflow variable, by dotted path
    ...
});
```

| Member | Meaning |
|---|---|
| `args()`, `arg(name)`, `arg(name, Type)`, `requireArg(name[, Type])` | the explicit inputs. `requireArg` throws `TaskNotPerformed` when one is missing or unusable |
| `variables()`, `variable(path[, Type])` | a read-only copy of the workflow's variables |
| `stepId()` | where in the run this is, such as `Main/s2` |
| `idempotencyKey()` | stable when the step is retried or the run is resumed; unique per run. Pass it to a payments or ticketing API |

Every collection in a context is an **immutable deep copy**. A task cannot change workflow state behind the runtime's back; it returns a result and the runtime binds it.
That is what makes a run replayable.

Outside Loom, build a context yourself: `TaskContext.of(Map.of("amount", 90), Map.of())`. A task is a plain function, so it unit-tests with no model, no network and no workflow.

## What a task returns: `TaskResult`

```java
TaskResult.ok();                                  // outcome "ok"
TaskResult.value(40);                             // outcome "ok", value 40
TaskResult.rejected("over the limit");            // outcome "rejected", reason "over the limit"
TaskResult.outcome("needs_review").reason("two similar orders").with("candidates", List.of("A-1", "A-2"));
```

A Loom variable bound to a result holds its `toMap()`: `outcome` (always), `reason` and `value` when present, then the data entries. `outcome`, `reason` and `value` are reserved data keys.
Every value must be JSON-safe (strings, numbers, booleans, null, maps with string keys, lists), because the result is stored in the run journal; anything else is rejected when the result is built.
A result is immutable and copies what it is given.

## Failing safely

- Throw **`TaskNotPerformed`** when the task *provably did nothing* (an input was unusable, a rule refused it before any side effect). Running the step again is safe.
- Any other exception from a task that `CHANGES` things means the outcome is **unknown**: the call may have reached the other side. A runtime will not run such a task again unless its policy says repeating is safe.

```java
Task issue = Task.changes("IssueRefund",
        new EffectPolicy(EffectPolicy.OnUnknown.SKIP, /* idempotent */ true, /* maxPerRun */ 0),
        ctx -> TaskResult.value(payments.refund(ctx.requireArg("order", String.class),
                                                ctx.requireArg("amount", Double.class),
                                                ctx.idempotencyKey())));      // the provider deduplicates a repeat
```

`EffectPolicy` is the same record effect tools use: `idempotent` (the receiver deduplicates by the key, so an unknown attempt is simply repeated), `onUnknown` (`SKIP` or `RETRY`) and `maxPerRun`.

## Registering tasks

```java
TaskRegistry tasks = new TaskRegistry().register(policy).register(issue);   // a duplicate name is an IllegalArgumentException
TaskRegistry onTheClassPath = TaskRegistry.discovered();                    // every Task listed under META-INF/services
```

To make tasks available to the `weave` CLI, list each class (public, with a no-argument constructor) in
`META-INF/services/io.github.llm4j.agent.task.Task` and put the jar on the class path. Tasks are code the *operator* supplies:
a Loom script can name a task but can never cause one to be loaded.

## Thread safety

A workflow may run one task from several parallel branches at once. Implementations must be thread-safe and must not keep per-call state in fields.
