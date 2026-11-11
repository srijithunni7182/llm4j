# The Loom guide: calling a remote agent

**Who this is for:** someone writing a Loom workflow that calls an agent that another organisation runs. You need to know what [A2A is](WHAT_IS_A2A.md); you do not need Java except for the host that runs the workflow. Letting others call a workflow (`expose`) and `weave card` arrive with the serving support for Loom.

## The words

| Word | What it is |
|---|---|
| `remote Name { … }` | An agent someone else runs: its card address, how to authenticate (a reference), which channel answers it, and a deadline |
| `agent_channel Name { … }` | Who answers what comes from outside: the audience, how many questions, how long |
| `delegate "…" to Remote -> result` | The call. The same statement as a local agent, with `retry`, `timeout` and `on_failure` |
| `ask Channel "…" -> reply [expecting { … }]` | A question to a channel, with an optional shape for the answer |

All four are contextual words: an agent or variable called `remote` or `ask` still works.

## A worked script

```loom
agent_channel SupplierDesk {
    audience: staff
    max_questions: 3
    timeout: 1h
}

remote Supplier {
    card: "https://supplier.example.com/.well-known/agent-card.json"
    auth: secret.SUPPLIER_KEY
    agent_channel: SupplierDesk
    timeout: 2h
    require_signed_card: false
}

workflow Buy(part) {
    delegate "Quote 500 units of {part}" to Supplier -> quote
        retry 2
        backoff 5s
        on_failure { halt "the supplier could not quote" }
    ask SupplierDesk "Which currency should we pay in?" -> currency expecting { currency: enum["USD", "INR"] }
}
```

## What you get back

| Variable | Holds |
|---|---|
| `quote` | The text of the final message and the artifacts, joined. With `expecting { … }`, one object checked against the shape (from the single data part, or from JSON in a text part) |
| `quote_data` | The structured parts, as a list |
| `quote_files` | The files the channel accepted: `path`, `kind`, `mediaType`, `size`, `sha256` |

Everything a remote returns is untrusted text: it is tagged as such, so a policy that stops unchecked text from leaving still applies. A reply that is a plain message and not a task ends the step at once with the same variables.

## Waiting, and the host's part

A task that is not finished suspends the run; it holds no thread. Something must say when to look again, and that is the host's: poll, hold a stream, or receive a push notification, then call `RemoteTasks.taskChanged(runId, taskId)`. A repeat or a stale call changes nothing, because push notifications arrive at least once. The step gives up at the `remote`'s `timeout` and asks the remote to cancel the task, best effort.

```java
LoomHost host = LoomHost.builder(script, tools, models)
        .persistence(Persistence.file(store))
        .customizer(RemoteTasks.wiring(transport, Map.of("SupplierDesk", channel)))
        .build();
RemoteTasks remoteTasks = new RemoteTasks(host);
// when your poller, stream or webhook learns that a task changed:
remoteTasks.taskChanged(runId, taskId);
```

`transport` and `channel` are the consumer's own implementations: see [What you provide](INTEGRATING.md).

## Never sent twice

The message id and the time are journaled before the send. If the process dies after the send, the run does not send again: it asks the remote for its recent tasks and looks for one whose history holds that id. If it finds the task it carries on; if it cannot tell, the step stops with a message. `retry` is used only after a definite failure, never after an unknown outcome.

## What `weave check` says

Each mistake is reported with its line and what to write: a card that is not `https`, a literal in `auth`, a channel that is not declared, an unknown setting, a token `budget` on a step that calls a remote (its cost is outside Loom's budgets), a list of agents that mixes remote and local ones, `handoff`, `broadcast` or `extract` naming a remote (use `delegate`, in a `parallel` block for several). Every `remote` also gets a warning that it is **not validated**: its card is read at run time. Every channel gets one saying the host must supply its implementation.

## Next

[A2A support in llm4j](README.md) · [Our approach](PHILOSOPHY.md) · [What you provide](INTEGRATING.md) · [Reference](REFERENCE.md)
