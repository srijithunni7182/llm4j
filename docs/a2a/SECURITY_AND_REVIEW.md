# Security and review: what checks A2A, and when

**Who this is for:** someone who reviews a Loom workflow that calls remote agents or is called by them, or who watches one run. It says what each check reports and where the line is between what a tool can see and what only you can. For how to build, see [The Loom guide](LOOM.md); for what your code around llm4j must do, see [What you provide](INTEGRATING.md).

The rule behind all of it: **the model reasons and the code holds the authority.** Text from a remote agent, and text from a caller, is untrusted. Nothing here asks a prompt to enforce a rule.

## Before anything runs: `weave check`

`weave check` reads the script and reports each mistake with its line and what to write:

- a `remote` with no `card` or `agent_channel`, a card that is not `https`, a literal key in `auth`, an unknown setting;
- an `ask` or `remote` that names a channel that is not declared, an unknown `audience`;
- an `expose` that names a workflow that does not exist, a `text_input` that is not a parameter of it, a `max_input` that is not a size;
- a token `budget` on a step that calls a remote (its cost is outside what you see), `handoff`, `broadcast` or `extract` aimed at a remote, a list of targets that mixes remote and local agents.

It also warns, every time, that **a remote is not validated**: its card is read at run time, and whether it exists, speaks A2A 1.0 and offers what you expect is checked then. It warns that **a channel's implementation is the host's**, so until the host supplies it the first use fails closed.

## What the script gives away: `weave audit`

| Rule | What it says | Severity |
|---|---|---|
| LA24 | An `expose` lets other agents call a workflow. Every agent it reaches counts as reading untrusted content, so the lethal trifecta rule (LA01) can fire. | low |
| LA25 | A `delegate` to a remote sends text and files to another organisation, and nothing in the script masks personal data first. | low; medium if the workflow reaches private data and nothing masks it |
| LA26 | An `agent_channel` has `audience: automatic`: code answers a party from outside with no person looking. | medium; high if a `remote` uses it, because the remote can ask for authorization |
| LA27 | An `expose` has no budget of its own and the script has no run budget: whoever can call it decides what it costs. | high |

The audit reads the script only. It does not follow data, so for LA24 it assumes every agent an exposed workflow reaches sees what a caller sent. If you mask personal data in your transport or your channel instead of in the script, you accept LA25 in writing with that reason. The rules are listed in [the audit guide](../security/weave-audit.md) and mapped to the OWASP list in [OWASP LLM Top 10](../security/owasp-llm-top-10.md).

## How it can fail half way: review

The reliability lens counts every `delegate` to a remote as able to act, because nobody on your side can see what the remote does. **LR02** (a step that can write or send but has no `on_failure`) therefore fires on a remote call with no `on_failure`. The fix it offers is the same as for a tool: record the failure, or ask a person.

## While it runs: what you can watch

- A call to a remote is one `loom.remote` span. A remote that failed or ran past its timeout, or a channel that spent its question limit, is the `Remote agent failed` incident; it names the remote, the step and the attempts, never what the remote said.
- A run that other agents started through an `expose` block is marked as **served**. For the trifecta, every agent in a served run has already read untrusted content, so an agent that then reaches private data and sends something out raises the trifecta incident at once, naming "exposed workflow input" as the first leg. See [Security signals](../../wiki/Observability-Security-Signals.md).
- Served tasks report what happens to them (received, state, answered, cancelled) with the task id and state only, never the caller or any text.

## What no tool here can check

- Whether a remote agent is who its card says it is. llm4j refuses a card that points at another host and one that needs an extension you did not enable, but it does no cryptography: **signing and verifying cards is yours.**
- Whether a caller may call at all. Authentication and the scope key belong to the host that serves the endpoint; llm4j only makes sure one scope never sees another's tasks.
- What a remote does with what you send, and what a caller does with what you return.
- Anything about a remote agent from a third party: the checks above ran against llm4j's own mock agent, not against another implementation.

## Next

[A2A support in llm4j](README.md) · [Our approach](PHILOSOPHY.md) · [The Loom guide](LOOM.md) · [What you provide](INTEGRATING.md) · [Reference](REFERENCE.md)
