# A2A in llm4j: what it is and how we approach it

**Status: draft for review.** This page is meant to guide the A2A work in llm4j, so every design choice can be checked against it. It is not a specification: the design, tests and verification plan come after it, in this folder. Facts about the A2A protocol are marked **[verified]** when they were read in the published 1.0.0 specification, and **[unverified]** when they come from a summary or from memory and must be checked before they are relied on (see "What has and has not been checked" at the end).

## 1. What A2A is

A2A (Agent2Agent) is an open protocol that lets one agent hand a goal to another agent, even when the two are built with different frameworks, by different teams, and run on different servers. The specification is published at <https://a2a-protocol.org/>, and its current version is **1.0.0** [verified].

Think of phoning a travel agency. You do not press a button and get a fixed answer. You say what you want ("a flight to Pune on Friday, under ₹8,000"), they work on it their own way, they may call back with a question ("morning or evening?"), it may take a while, and in the end they send you the tickets. A2A is the agreed way to make that phone call between two programs.

The protocol reuses HTTP, JSON-RPC 2.0 and Server-Sent Events [verified], and defines three ways to carry the same operations: JSON-RPC over HTTP(S), gRPC, and HTTP with JSON (REST) [verified].

The parts you need to know:

| Part | What it is |
|---|---|
| **Agent card** | A JSON document an agent publishes about itself: its name and description, the addresses and protocols it can be reached on, what it can do (streaming, push notifications), its **skills**, the input and output types it accepts, and how callers must authenticate. By convention it is served at `/.well-known/agent-card.json` [verified]. A server must make one available [verified]. |
| **Skill** | One thing the agent says it can do, with an id, a description, examples and the media types it takes in and gives out [verified]. |
| **Message** | One turn of the conversation, from the user (the caller) or the agent, made of **parts** [verified]. |
| **Part** | A piece of a message: text, a file (inline bytes or a link, with a media type), or structured data [text, `raw` and `url` verified; the separate structured-data part is unverified]. |
| **Task** | The unit of work. A task has an id, a state, optional messages, and the **artifacts** produced [partly verified]. Related tasks can share a **context** id. |
| **Artifact** | An output of a task, such as a document or a data object, made of parts [verified]. |

A task moves through states. The names in the 1.0.0 enumeration are `TASK_STATE_SUBMITTED`, `TASK_STATE_WORKING`, `TASK_STATE_COMPLETED`, `TASK_STATE_FAILED`, `TASK_STATE_CANCELED`, `TASK_STATE_REJECTED`, `TASK_STATE_INPUT_REQUIRED` and `TASK_STATE_AUTH_REQUIRED` [the list is unverified; the specification's own examples spell some states differently, and that has to be settled when we read it closely]. Two of them are not endings but pauses: the agent needs **input** from the caller, or needs the caller to **authorize** something. Both pauses matter to llm4j (section 5).

An agent can be reached in three ways while a task runs: the caller holds a connection open and receives updates as they happen (streaming), the caller asks for the task's state from time to time (polling), or the agent calls a web address the caller registered (a push notification) [partly verified].

## 2. How it differs from MCP

Both protocols publish a described capability. The description is written in plain language so that a model can read it, decide it fits, and use it. If you strip away the jargon, that is what they share.

What differs is everything after the choice:

| | MCP | A2A |
|---|---|---|
| **What is on the other side** | A tool or data source. It does no thinking of its own. | Another agent, with its own model, prompts, tools and judgement. |
| **What you send** | Exact arguments that match a declared schema. | A goal, in words (with optional structured data). |
| **What comes back** | A result, straight away. | A task that moves through states and delivers artifacts. |
| **Time** | A call returns. | A task can run for minutes or days. |
| **Dialogue** | None. | The agent can stop and ask the caller a question, or ask for authorization, and then carry on. |
| **Visibility** | You know exactly which tool ran. | The other agent is opaque: its prompts, tools and memory never cross over. |
| **Trust** | The tool does what its schema says. | The other agent may refuse, misunderstand, or answer in a way you did not expect. |

Short version: MCP connects an agent to its tools. A2A connects an agent to other agents. Many stacks use both.

The English description is only how a model finds something. What decides whether it is safe is everything that is not English: who may call it, what it may do, how long it may run, what it costs, and what is checked on the way in and out. A description in a tool or a card is also input that someone else wrote, and it can try to steer the model. That is why llm4j never lets a card's text grant access: the script names what may be called.

## 3. What a typical exchange looks like

Say a buying workflow needs a price from a supplier's agent. The supplier publishes a card. This is the usual sequence. The JSON below is **illustrative**: it shows the shape, uses names from the 1.0.0 specification where they were verified, and is not copied from the specification's own examples, which were not read in full.

**1. Discover.** The caller fetches the card from the supplier's well-known address and reads what it offers, how to reach it and how to authenticate. The caller obtains its credentials separately; the specification expects them to be obtained out of band [verified].

**2. Send a goal.** The caller sends a message to the address named in the card:

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "SendMessage",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "messageId": "m-1",
      "parts": [ { "text": "Quote 500 units of part 12." } ]
    }
  }
}
```

**3. A task comes back.** The agent answers with a task and its state. If it needs something, the state says so and the task carries the question:

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "task": {
      "id": "t-77",
      "contextId": "c-9",
      "status": {
        "state": "TASK_STATE_INPUT_REQUIRED",
        "message": { "role": "ROLE_AGENT", "parts": [ { "text": "Which delivery date do you want?" } ] }
      }
    }
  }
}
```

**4. Answer, on the same task.** The caller sends another message that names the task:

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "SendMessage",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "messageId": "m-2",
      "taskId": "t-77",
      "parts": [ { "text": "Within 30 days." } ]
    }
  }
}
```

**5. Completion.** The state becomes `TASK_STATE_COMPLETED`, and the result is in an artifact (here, a quote). If the work is slow, the caller learns of it by streaming, by asking for the task again (`GetTask`), or by a push notification to an address it registered. A running task can be cancelled (`CancelTask`).

Things worth noticing: the caller never sees how the supplier arrived at the quote; the pause for a question is part of the protocol and not an error; and between steps 3 and 4 nothing has to stay connected, so the task has to be remembered by someone.

## 4. Why llm4j does not host an A2A server

llm4j is a framework for building agents and workflows. A2A, like any protocol, has a part that is about **meaning** (what a card, a task and a message are, and how they map onto an agent or a workflow) and a part that is about **running a service** (a web server, TLS, authentication, storage, scaling, polling loops and webhook receivers). We provide the first and leave the second to the people who use the framework. The reasons:

- **Hosting is full of decisions that belong to the consumer.** Which web stack, which address, which certificate, which identity provider, where task state is kept, how many instances run, how tenants are separated. The specification itself says a server must authenticate every request and authorises according to its own policies [verified]. Those policies are the operator's.
- **A framework that owns a public endpoint owns its security for everyone.** Every deployment's attack surface would become ours to patch and to explain. A handler that someone mounts in their own server keeps that responsibility with the people who run it.
- **It would break the promise to stay light.** llm4j avoids framework dependencies, background threads nobody asked for, and static initialisers that do work. A built-in server and polling loop would need all three.
- **People already have servers.** Teams use Spring, Javalin, Quarkus, a gateway or a cloud function. What they lack is the protocol layer, and that is what llm4j gives them.

What llm4j does provide: the A2A types; a card builder that turns a script's declaration into card JSON; a handler that takes an A2A request and returns a response (or a stream of events), which any web server can mount; a `RemoteAgent` for calling other agents; and the interfaces a consumer fills in. In short, **llm4j supplies something to mount, never something to run.**

## 5. Our philosophy: a facilitator, not a decision-maker

llm4j makes it possible to expose and to call agents over A2A. It does not decide how a particular organisation should do either. These principles are the test for every choice made during the work:

1. **Mechanism, not policy, for plumbing.** For transport, storage, waiting, authentication and who answers a question, llm4j defines an interface and the consumer implements it. We ship **no default transport**, no polling loop, no Spring adapter and no ready-made tool wrapper for remote agents. A consumer's own implementation can add any header, token or retry rule it needs.
2. **Opinionated only about safety.** Where a choice protects people from a failure they cannot easily see, llm4j decides and fails closed. The safety rules in this work are fixed, and a consumer cannot turn them off:
   - A credential is a reference to a secret and never text. It is resolved by the consumer's code for the host it is bound to.
   - A card cannot redirect a call. Before anything is sent, the card's endpoint must be on the host the script declared.
   - Everything from outside is untrusted: card text, replies, questions and incoming messages. Replies are validated against a declared shape before the workflow sees them.
   - Every exchange is bounded: the number of questions, the time allowed, and the size of messages. The bound is enforced by the runtime and not by consumer code, because a bound that depends on the consumer's code is not a bound.
   - An exchange is an effect. It is journaled, and a resume or retry never sends the same reply twice.
   - A failed check denies. An unknown value, a missing setting or an unreachable card never means "allow".
3. **The script declares what; the host decides who, where and how.** A script says that a remote agent is called (`remote`), that a workflow is exposed (`expose`), and who answers questions in terms the audit can read (`agent_channel`, with an audience, a question limit and a timeout). The host decides the address, the login and the implementation. This keeps the facts a reviewer needs in the script, which is where `weave check`, `weave audit` and the graph can see them.
4. **What leaves or enters is visible.** Nothing crosses the boundary invisibly. A remote agent is a different kind of declaration from a local agent, because it differs in the ways that matter: it is opaque, its reply is untrusted, its cost is outside the budgets, and it can take hours.
5. **Honest about what cannot be checked.** A remote agent's card is fetched at run time and checked then. `weave check` says so in a warning and does not pretend it validated anything. The same honesty applies to a channel: the script declares the audience, and a reviewer reads the implementation once, as with a tool's declared reach.
6. **Additive, and nothing existing changes.** `human_prompt`, `approve`, the person channel (console, Telegram) and `LoomHost` keep working exactly as they do. A2A arrives as new declarations and new types.
7. **No ownership of waiting.** llm4j does not poll, sleep or schedule on behalf of a consumer. It returns the state of a task, and whoever drives the call decides when to look again. In Loom, a durable run pauses and the host resumes it.
8. **Smallest footprint.** The protocol types and the handler interface go in the core with no new dependencies. Loom's adapter depends on the core, never the other way round. No HTTP server or client library is added for A2A.

**What we will not do**, so nobody has to ask: run a server; ship an HTTP client or poller for A2A; provide an identity or login system; operate a registry or directory of agents; wrap a remote agent as a tool; or add a framework adapter. Each of these is a reasonable thing for a consumer to build on top.

**When in doubt**, ask in this order: Does this move a decision about the consumer's environment into llm4j? If so, make it an interface. Does this protect against a failure the consumer cannot easily see? If so, llm4j decides and fails closed. Can a reviewer see it in the script? If not, it needs a declaration.

A change to these principles is the owner's decision and is made here, in this page, before it is made in code.

## What has and has not been checked

The specification page was read in part. The sections that were read word for word: the overview, the list of operations, the agent card and its discovery, the security considerations for authentication and TLS, and the push-notification flow. Sections that were only seen through a summary: the exact definitions of Task, task states, and Part; the complete agent card field list; the error codes; and the migration notes between versions.

Specific things to settle when the specification is read closely, before the design is written:

- The spelling of task states (the specification's examples are inconsistent with its enumeration).
- Whether a separate structured-data part exists, and how a file is represented (the specification names `raw` and `url`, with a media type).
- The exact JSON-RPC method names and result shapes. `SendMessage`, `SendStreamingMessage`, `GetTask`, `ListTasks`, `CancelTask`, `SubscribeToTask` and `GetExtendedAgentCard` were seen as headings; the push-notification configuration methods were not.
- Which bindings llm4j's types should be written for (JSON-RPC only, or independent of the binding).
- `TASK_STATE_AUTH_REQUIRED`, a second kind of pause besides asking for input. The `agent_channel` design currently covers questions; it must decide how to handle a request for authorization.
- The 1.0.0 specification changes names from earlier versions (0.3.0, 0.2.6 and 0.1.0 are linked). Other sources and examples on the web may use the old names.
