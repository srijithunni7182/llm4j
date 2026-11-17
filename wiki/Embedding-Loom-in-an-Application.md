# Embedding Loom in an application

`weave run` runs a workflow from a terminal. An application that starts workflows for its users, waits for their
answers and survives a restart runs them with **`LoomHost`** instead. This page is the short version: what you write,
in what order, and where each part is described in full. A complete, tested example is
[Expense Desk](../src/examples/expense-desk/README.md), a web page on the JDK's HTTP server.

## What you write

| Part | What it is | Described in |
|---|---|---|
| The script | A `.loom` file: the agents, the steps and their order | [Loom guide](../src/loom/ai-agent4j-loom/LOOM_GUIDE.md) |
| Tasks | The steps that must be exact (a rule, a payment), as plain Java | Loom guide, "Tasks: deterministic steps" |
| A host | One `LoomHost` for the script, with somewhere to keep runs | Loom guide, "Running workflows from an application" |
| Two listeners | To learn that a run asks a person something, and how each run ended | below |

## The host

```java
LoomScript script = new LoomLoader().load("expense.loom");

LoomHost host = LoomHost.builder(script, new ToolRegistry(), new DefaultLLMClientFactory())
        .persistence(Persistence.file(Path.of("var/desk")))            // runs survive a restart
        .taskRegistry(new TaskRegistry().register(new ExpensePolicy()).register(new Reimburse(ledger)))
        .questionListener((run, step, prompt, choices) -> show(run, step, prompt))
        .runListener((record, variables) -> store(record, variables))
        .build();
host.validate();                                                        // every problem, before any run
```

- **Models.** `DefaultLLMClientFactory` reads provider keys from the environment. Any `LLMClientFactory` will do, a
  scripted one included, which is how the example runs with no key.
- **Persistence.** `Persistence.file(dir)` or `Persistence.jdbc(dataSource)`. Without one a run cannot wait for a
  person, and a script that can is refused.

## Start a run, and learn how it ended

```java
host.start(RunRequest.of("Claim", Map.of("employee", who, "text", what)).withRunId(id));
```

`start` returns at once. The application makes the run id; never take one from a user, because an id that was used
before replays that run's journal.

The **run listener** is told how every run ended, with the workflow's values at that moment: `record.status()` is
`COMPLETED`, `SUSPENDED`, `STOPPED` or `FAILED`, and for a finished run `variables.get("result")` is what the
workflow returned. It is told for a run started in the background and for one carried on by an answer, which hand
nothing back to their caller.

## A question to a person

When a step asks (`human_prompt`, `approve`), the run stops and holds no thread. The **question listener** is told the
run, the step and the question, once the run is recorded as suspended. Keep the step id: the answer needs it.

```java
AnswerResult taken = host.answer(run, step, "yes", approverName);      // from a web request, later
```

The run carries on from its journal. The answer is recorded once: a second answer is refused, and a step whose
answer was no is never run.

## Steps as they happen

For a page that shows progress, add a trace listener to each run's executor:

```java
.customizer(executor -> executor.addTraceListener(event -> show(executor.getRunId(), event)))
```

`TraceEvent` lists the kinds (`extract_end`, `task_end`, `delegate_end`, `halt` and the rest). An event never holds a secret.

## Test it, and watch it

- `weave test` runs every route with scripted models and your real task code, and costs nothing.
- `weave eval` runs your golden cases against real models. Read "What a real run really does" in the Loom guide
  first: a real run carries out tasks that change things unless you pass `--effects simulate`.
- `builder.observe(session)` reports each run to the live page or to a Fabric server; see
  [Observability](Observability.md). Build the jars it needs together with `make observe-jars`.

## Close it

`host.shutdown(grace)` asks the runs in progress to wrap up and suspends what is left; `host.close()` releases the
host. A run that was waiting is still waiting when the application starts again.
