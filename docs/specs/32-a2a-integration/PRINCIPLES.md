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

What llm4j does provide: a version-free model of agents, tasks and messages (principle 9); a card builder that turns a script's declaration into card JSON; a handler that takes an A2A request and returns a response (or a stream of events), which any web server can mount; a `RemoteAgent` for calling other agents; one optional helper that translates A2A 1.0 over JSON-RPC to and from that model; and the interfaces a consumer fills in. In short, **llm4j supplies something to mount, never something to run.**

## 5. Our philosophy: a facilitator, not a decision-maker

llm4j makes it possible to expose and to call agents over A2A. It does not decide how a particular organisation should do either. These principles are the test for every choice made during the work:

1. **Mechanism, not policy, for plumbing.** For transport, storage, waiting, authentication, fetching a link and who answers a question, llm4j defines an interface and the consumer implements it. We ship **no default transport**, no polling loop, no Spring adapter and no ready-made tool wrapper for remote agents. A consumer's own implementation can add any header, token or retry rule it needs.
2. **Opinionated only about safety.** Where a choice protects people from a failure they cannot easily see, llm4j decides and fails closed. The safety rules in this work are fixed, and a consumer cannot turn them off:
   - A credential is a reference to a secret and never text. It is resolved by the consumer's code for the host it is bound to.
   - A card cannot redirect a call. Before anything is sent, the card's endpoint must be on the host the script declared.
   - Everything from outside is untrusted: card text, replies, questions, links, files and incoming messages. Replies are validated against a declared shape before the workflow sees them.
   - Credentials never travel through a reply. A request for authorization is its own kind of message to the channel, and the consumer's code obtains the credential out of band.
   - llm4j never follows a link it is given. A link goes to the channel, which decides. A file that comes back through the channel is checked like any media: the type is decided from its bytes, the size is capped while it is written, and SVG is refused.
   - Every exchange is bounded: the number of questions, the time allowed, and the size of messages. The bound is enforced by the runtime and not by consumer code, because a bound that depends on the consumer's code is not a bound.
   - An exchange is an effect. It is journaled, and a resume or retry never sends the same reply twice.
   - A failed check denies. An unknown value, a missing setting or an unreachable card never means "allow".
3. **The script declares what; the host decides who, where and how.** A script says that a remote agent is called (`remote`), that a workflow is exposed (`expose`), and who answers questions in terms the audit can read (`agent_channel`, with an audience, a question limit and a timeout). The host decides the address, the login and the implementation. This keeps the facts a reviewer needs in the script, which is where `weave check`, `weave audit` and the graph can see them.
4. **What leaves or enters is visible.** Nothing crosses the boundary invisibly. A remote agent is a different kind of declaration from a local agent, because it differs in the ways that matter: it is opaque, its reply is untrusted, its cost is outside the budgets, and it can take hours.
5. **Honest about what cannot be checked.** A remote agent's card is fetched at run time and checked then. `weave check` says so in a warning and does not pretend it validated anything. The same honesty applies to a channel: the script declares the audience, and a reviewer reads the implementation once, as with a tool's declared reach.
6. **Additive, and nothing existing changes.** `human_prompt`, `approve`, the person channel (console, Telegram) and `LoomHost` keep working exactly as they do. A2A arrives as new declarations and new types.
7. **No ownership of waiting.** llm4j does not poll, sleep or schedule on behalf of a consumer. It returns the state of a task, and whoever drives the call decides when to look again. In Loom, a durable run pauses and the host resumes it.
8. **Smallest footprint.** The model and the handler interface go in the core with no new dependencies. Loom's adapter depends on the core, never the other way round. No HTTP server or client library is added for A2A.
9. **Version-agnostic.** llm4j's own model is normalised and carries no protocol version. It holds only what llm4j's behaviour needs, and the consumer's transport translates between it and the wire for whatever version and binding the other agent speaks.
   - **What the model holds.** For an agent: its name, description, skills, the endpoints it offers (address, binding, version) and the input types it accepts. For a task: its id, its context id and one state from a small fixed set (working, input needed, authorization needed, completed, failed, canceled, rejected, or unknown). For messages and artifacts: text and structured data.
   - **Nothing above the transport knows a version.** That covers the Loom syntax, `agent_channel`, the journal, the audit rules and the card check. A script never states an A2A version, and the `remote` block has no version setting.
   - **One place knows one version.** The single optional helper that translates A2A 1.0 over JSON-RPC is the only code in llm4j that knows a wire name. A consumer who speaks another version or binding writes their own translation and uses everything else unchanged. We do not add `if (version == X)` branches to shared code.
   - **Fail closed on what is not understood.** If the card offers no endpoint the transport can speak, the call is refused before anything is sent, with a message that names the versions offered. A state that is not recognised becomes *unknown*: it is never treated as completed, it is reported to the consumer, and if it lasts until the timeout the step fails. Unknown fields in incoming data are ignored, so a newer agent's extra fields do not break a caller.

**What we will not do**, so nobody has to ask: run a server; ship an HTTP client or poller for A2A; follow every protocol version in our own types; provide an identity or login system; operate a registry or directory of agents; wrap a remote agent as a tool; or add a framework adapter. Each of these is a reasonable thing for a consumer to build on top.

**When in doubt**, ask in this order: Does this make llm4j's own types or syntax depend on a protocol version? If so, move it into the translation. Does this move a decision about the consumer's environment into llm4j? If so, make it an interface. Does this protect against a failure the consumer cannot easily see? If so, llm4j decides and fails closed. Can a reviewer see it in the script? If not, it needs a declaration.

A change to these principles is the owner's decision and is made here, in this page, before it is made in code.

## 6. What we have decided so far

These choices follow from the principles above. They are the starting point for the design, and a change to one of them is made here first.

**Calling another agent**

- A `remote` declaration, separate from `agent`, because a remote agent is opaque, its reply is untrusted, its cost is outside our budgets, and it can take hours. Its card address and a reference to a secret go in the block; there is no version setting.
- The statement that calls it is the one we already have: `delegate "…" to Supplier -> quote`.
- The card is fetched and checked **at run time**, before anything is sent. `weave check` does not pretend to validate it: it warns that the remote agent is not validated. What the remote accepts (files, for instance) is decided by its card.
- In Java, a separate `RemoteAgent` type, not a second implementation of one `Agent` interface. It is built from a card, an injected transport and a channel, and it has `send`, `reply`, `get` and `cancel`. It does not wait or poll. We do not ship a tool wrapper for it.
- A crash after a send never creates a second remote task: the message and context ids are journaled first, and a resume looks the task up before it considers sending again.

**Being called**

- An `expose` declaration, separate from the workflow it names, with a description, limits and skills. Only workflows can be exposed from a script; to expose an agent, wrap it in a one-step workflow. A published agent that bypasses a workflow's budget, exit checks and approvals is the "god prompt" mistake, and the audit says so.
- A card builder turns the declaration, plus the address and login the host supplies, into card JSON. `weave card main.loom` prints it for review.
- The handler wraps `LoomHost` and does not change it: a task is a durable run, a pending `human_prompt` is a pause, and a crash resumes the run.
- The handler requires the caller's identity from the host and shows each caller only its own tasks. With no identity, it refuses.

**Questions, authorization and links: `agent_channel`**

- An `agent_channel` is the script's declaration of a consumer-implemented channel for what passes between agents. It is separate from the existing person channel (console, Telegram), which, along with `human_prompt` and `approve`, is not changed at all.
- It declares an **audience** (staff, caller or automatic), a **question limit** and a **timeout**. The audit reads the audience. The runtime enforces the limit and the timeout.
- `remote` and `expose` each name their channel. The statement for our workflow to ask is `ask Channel "…" -> reply`. It is keyword-first, because every Loom statement starts with a keyword.
- Replies are **text or structured**, and structured ones are checked against `expecting`, the schema language Loom already has. A reply that fails is returned to the channel with the reason, and each attempt uses one of the question limit. The count is per run and step, retries included. Files are not accepted as replies.
- The channel receives a defined, read-only **context** (run, step, workflow, direction, counterpart, deadline, questions left) and messages of three kinds: a question, a request for authorization, and a link. It answers with a reply, a deferral to be completed later, a refusal, or, for a link, a received file. An unhandled message fails the step. A message nobody handles is never dropped silently.
- Every message and response is journaled with an idempotency key, so a resume never repeats an effect.

**Files that arrive**

- When the channel is given a link, the context gives it a **writable stream**. The runtime chooses the destination, a `received/` folder inside `media_root`, and a generated file name. The sender's own file name is kept only as a label and never builds a path.
- The size is capped while the bytes arrive, using the script's `media { max_bytes }`. There is no separate setting for now. The type is decided from the bytes, and SVG and unknown types are refused. A failed write leaves nothing behind.
- The files reach the workflow as a **list variable**, such as `{quote_files}`, like the citations of a grounded agent. A script that has no `media_root` cannot receive files, which fails closed in the same way as `attach`. Because the path is chosen at run time, the audit rule for run-time media paths (LA23) already applies.
- A resumed run needs the `received/` folder to survive with the run's journal. Otherwise the hash check fails the step, which is the safe result. The documentation must say so.

**Versions**

- The model is version-free, as principle 9 says. We target the 1.0 JSON-RPC translation only, as one optional helper.

## What has and has not been checked

The specification page was read in part. The sections that were read word for word: the overview, the list of operations, the agent card and its discovery, the security considerations for authentication and TLS, and the push-notification flow. Sections that were only seen through a summary: the exact definitions of Task, task states, and Part; the complete agent card field list; the error codes; and the migration notes between versions.

Specific things to settle when the specification is read closely, before the design is written:

- The spelling of task states. The specification's examples are inconsistent with its enumeration. This now affects only the optional 1.0 helper, which must accept what it meets and write the 1.0 form.
- Whether a separate structured-data part exists, and how a file is represented (the specification names `raw` and `url`, with a media type).
- The exact JSON-RPC method names and result shapes. `SendMessage`, `SendStreamingMessage`, `GetTask`, `ListTasks`, `CancelTask`, `SubscribeToTask` and `GetExtendedAgentCard` were seen as headings; the push-notification configuration methods were not.
- ~~Which bindings llm4j's types should be written for.~~ Settled by principle 9: the model is independent of binding and version, and the only translation we ship is 1.0 over JSON-RPC.
- `TASK_STATE_AUTH_REQUIRED`, a second kind of pause besides asking for input. The channel receives it as its own kind of message and fails closed unless handled (section 6); how a channel should handle it in detail is open (see "Still to decide").
- The 1.0.0 specification changes names from earlier versions (0.3.0, 0.2.6 and 0.1.0 are linked). Other sources and examples on the web may use the old names.

## Still to decide

These are open design questions, to be settled before the specification is written:

1. How an incoming A2A message becomes the exposed workflow's parameters, since a message can carry text, structured data and files.
2. How to expose a plain `ReActAgent` from Java, which has no journal behind it.
3. The audit rules and their numbers. At minimum: a published workflow that reaches private data and can send something; a remote call with no `on_failure`; an `agent_channel` whose audience is `automatic` and answers an outside party; private data or files sent to a remote agent.
4. How an `agent_channel` handles a request for authorization in detail, beyond failing closed.
5. Whether to offer a hook for verifying signed agent cards.
