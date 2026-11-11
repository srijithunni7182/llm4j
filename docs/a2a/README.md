# A2A support in llm4j

**Who this is for:** anyone who wants an llm4j application to call an agent that someone else runs, or to let others call one of its agents, over the Agent2Agent (A2A) protocol. Start here, then follow the table to the page for your case.

llm4j supports **A2A version 1.0** as a *facilitator*. It gives you the meaning (a version-free model of agent cards, tasks and messages) and the safety (limits, checks and records that hold whatever your code does). It does **not** run a server, an HTTP client, a poller or a webhook receiver: you provide the transport, the waiting and the hosting. [Our approach](PHILOSOPHY.md) explains why, and [What you provide](INTEGRATING.md) lists exactly what is yours.

## What you get, and what you do not

| You get | You do not get |
|---|---|
| `RemoteAgent`, to call a remote agent, with the endpoint guard, the signature and required-extension checks and the file-type check | A default transport or HTTP client |
| `AgentChannel` and `ExchangeGuard`: your code answers questions, authorization requests and files, inside limits the runtime enforces | A polling loop, a webhook receiver or any thread you did not start |
| `A2AHandler`, `AgentCardBuilder`, `AgentTaskRunner`: serve an agent from any web stack, with scope-filtered tasks and a card; you do the authentication and the hosting |
| `ReceivedFileSink`: files from outside written safely under your media root | A decision to follow a link: llm4j never follows one |
| `A2AJsonRpc10`, an optional helper that translates A2A 1.0 over JSON-RPC to and from the model | Support for other protocol versions or bindings in llm4j's own types (you write the mapping) |
| A test kit with a mock A2A 1.0 agent (not published) | Verification against a third-party A2A implementation: not done yet |

## Which guide do I read

| I am writing | Read |
|---|---|
| Plain Java with ai-agent4j, calling or serving | [The Java guide](JAVA_GUIDE.md) |
| What the code around llm4j must do | [What you provide](INTEGRATING.md) |
| Types, states, errors and limits | [Reference](REFERENCE.md) |
| What A2A is | [What A2A is](WHAT_IS_A2A.md) |
| Why llm4j does it this way | [Our approach](PHILOSOPHY.md) |

The guide for Loom workflows (`remote`, `expose`, `agent_channel`, `ask`) and the security and review page arrive with the Loom support, in later steps of the same work.

## Status

Calling a remote agent from Java, and serving an agent through a framework-neutral `A2AHandler` (including a plain `ReActAgent` through `AgentTaskRunner`), are available and described here. The Loom language support and the audit rules are not built yet.
Evidence level: the code is tested against fakes and, over loopback, against a mock A2A 1.0 agent that obeys rules traced to the specification and validates every message against the official definition. It has **not** been tested against a third-party implementation.

## Next

[What A2A is](WHAT_IS_A2A.md) · [Our approach](PHILOSOPHY.md) · [The Java guide](JAVA_GUIDE.md)
