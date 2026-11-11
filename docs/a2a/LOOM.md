# The Loom guide: calling a remote agent, and letting others call yours

**Who this is for:** someone writing a Loom workflow that calls an agent that another organisation runs. You need to know what [A2A is](WHAT_IS_A2A.md); you do not need Java except for the host that runs the workflow. Letting others call a workflow (`expose`) and `weave card` are at the end of this page support for Loom.

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

## Testing it offline

`weave test` scripts a remote agent and a channel in a test file, so a workflow that calls one is tested with no network and no remote. A remote is scripted one step per look at the task, and a channel answers in order:

```yaml
script: ../main.loom
workflow: Buy
cases:
  - name: the supplier asks a question and then quotes
    input: { part: bolt }
    remotes:
      Supplier:
        - { state: working }
        - { state: input_required, question: "Which date?" }
        - { state: working }
        - { state: completed, text: "42 each, 30 days" }
    channels:
      SupplierDesk:
        - "Within 30 days."
    expect:
      result: "42 each, 30 days"
      asked: [ "Which date?" ]
      route: [ "remote Supplier" ]
```

The other states are `auth_required` (with an `explanation`), `failed`, `rejected` and `canceled` (with a `message`); a completed step may carry `data` and `files: [ { link, name, type } ]`; `{ reply: "…" }` is a plain message and no task. The last step repeats, so a remote that stays `working` fails the step at its `timeout`, in virtual time. A channel answer may also be `{ refuse: "…" }` or, for a file or link, `{ ignore: true }`. A remote or channel the case did not script fails the case: nothing is guessed. The remote's step counts in `--coverage` like any other.

## Never sent twice

The message id and the time are journaled before the send. If the process dies after the send, the run does not send again: it asks the remote for its recent tasks and looks for one whose history holds that id. If it finds the task it carries on; if it cannot tell, the step stops with a message. `retry` is used only after a definite failure, never after an unknown outcome.

## What `weave check` says

Each mistake is reported with its line and what to write: a card that is not `https`, a literal in `auth`, a channel that is not declared, an unknown setting, a token `budget` on a step that calls a remote (its cost is outside Loom's budgets), a list of agents that mixes remote and local ones, `handoff`, `broadcast` or `extract` naming a remote (use `delegate`, in a `parallel` block for several). Every `remote` also gets a warning that it is **not validated**: its card is read at run time. Every channel gets one saying the host must supply its implementation.

## Letting others call a workflow

An `expose` block says that one workflow may be called over A2A, and what the world is told about it:

```loom
agent_channel CallerDesk {
    audience: caller
    max_questions: 2
}

expose Totals {
    workflow: Total
    description: "Totals a receipt."
    agent_channel: CallerDesk
    text_input: receipt
    max_input: 64k
}

workflow Total(receipt, currency) {
    ask CallerDesk "Which currency?" -> reply
    return "total for {receipt} in {reply}"
}
```

It is a declaration. llm4j runs no server: the host serves the card and the endpoint, authenticates the caller and gives the handler a scope key for each caller. See [What you provide](INTEGRATING.md).

**What comes in.** A text message binds only to the parameter named by `text_input`, which you always write, even for a workflow with one parameter. A JSON object binds by parameter name; unknown keys are ignored. A missing parameter is refused with a message that lists what the workflow takes. Files are refused, and a message over `max_input` (64 KB unless you say) is refused before any work starts.

**What the caller sees.** A task is a durable run of the workflow, owned by the caller's scope: no other scope sees it or can cancel it. It shows as working while the workflow runs, including while the workflow waits for a `human_prompt` or an `approve`, because those are internal. Only an `ask` to a channel whose `audience` is `caller` pauses the task as *input required*; the caller's answer carries it on. When the workflow finishes, the caller gets what it `return`s and never its notes. When it fails, the caller gets one fixed sentence, never the workflow's own error text. llm4j does not produce *authentication required* in this version.

**`weave card`.** `weave card main.loom` prints the card an `expose` block shows to outsiders, with a placeholder address; `--address https://…` sets the address and `--expose NAME` picks one block when there are several. It needs no network and no model, so a reviewer sees in a diff what a change does to what outsiders can see. The card says what is accepted (text for `text_input`, JSON for the other parameters) and nothing about the agents, channels or tools inside.

**From Java.** `LoomTaskRunner` in `io.github.llm4j.loom.host` serves one `expose` block as tasks. Create it with the block, the script, a `TaskStore` and a clock, pass `wiring()` to `LoomHost.Builder.customizer`, then call `attach(host)`. Give it to the handler as its `TaskRunner`. A task that must survive a restart needs a durable `TaskStore`. `onEvent` reports what happens to a task (received, state, answered, cancelled) with the task id and state only, never the caller or any text.

## Next

[A2A support in llm4j](README.md) · [Our approach](PHILOSOPHY.md) · [What you provide](INTEGRATING.md) · [Reference](REFERENCE.md)
