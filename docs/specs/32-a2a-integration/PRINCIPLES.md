# A2A in llm4j: what it is and how we approach it

**Status: draft for review.** This page is meant to guide the A2A work in llm4j, so every design choice can be checked against it. It is not a specification: the design, tests and verification plan come after it, in this folder. The description of A2A below follows version **1.0.0** of the specification, read from its published text and its protocol definition (the protobuf file). Where the prose disagrees with itself or with the definition, the definition is followed.

## 1. What A2A is

A2A (Agent2Agent) is an open protocol that lets one agent hand a goal to another agent, even when the two are built with different frameworks, by different teams, and run on different servers. The specification is published at <https://a2a-protocol.org/>, and its current version is **1.0.0**. Version 1.0 renamed methods, states and fields from 0.3, so older documents and older agents use different names.

Think of phoning a travel agency. You do not press a button and get a fixed answer. You say what you want ("a flight to Pune on Friday, under ₹8,000"), they work on it their own way, they may call back with a question ("morning or evening?"), it may take a while, and in the end they send you the tickets. A2A is the agreed way to make that phone call between two programs.

The protocol reuses HTTP, JSON-RPC 2.0 and Server-Sent Events. It defines one data model and three ways to carry it: JSON-RPC over HTTP(S), gRPC, and HTTP with JSON (REST). The specification requires every binding to represent the same data in an equivalent way.

### The parts

| Part | What it is |
|---|---|
| **Agent card** | A JSON document an agent publishes about itself, by convention at `/.well-known/agent-card.json`. A server must make one available. It holds the agent's name, description and version, its **interfaces**, what it can do (streaming, push notifications, extensions), its **skills**, the media types it accepts and produces by default, and how callers must authenticate. It may be signed. |
| **Interface** | One way to reach the agent: an address, a binding (`JSONRPC`, `GRPC` or `HTTP+JSON`), the protocol version it speaks (such as `1.0`), and optionally a tenant. A card lists them in preference order. **There is no single address on a card.** A client must use the first one it supports. |
| **Skill** | One thing the agent says it can do: an id, a name, a description, tags, examples, and the media types it takes in and gives out. |
| **Message** | One turn of the conversation. It has an id made by whoever sends it, a role (user, meaning the caller, or agent), parts, and optionally a context id, a task id and references to other tasks. |
| **Part** | A piece of a message. It is exactly one of: **text**, **raw** (file bytes, base64 in JSON), **url** (a link to a file) or **data** (any JSON value), plus an optional file name and media type. There is no separate "file" part. |
| **Task** | The unit of work. It has an id (always made by the server), a context id, a status (a state, an optional message and a time), artifacts and optionally the history of messages. |
| **Artifact** | An output of a task, such as a document or a data object, made of parts. |
| **Context** | An id that groups related tasks and messages, such as one conversation. |

### Task states

A task has one of these states: submitted, working, completed, failed, canceled, rejected (the agent decided not to do it), **input required** and **auth required**. (In JSON they are written `TASK_STATE_SUBMITTED` and so on; the specification's prose and a few examples use older spellings.)

- **Terminal:** completed, failed, canceled and rejected. A finished task cannot restart and takes no more messages. A refinement is a new task in the same context.
- **Interrupted:** input required and auth required. The task is waiting for the caller. The caller continues it by sending another message that names the same task and context.

**Input required** means the agent needs an answer. **Auth required** means the agent needs authorization, and the specification hands that to the caller: the caller is expected to arrange it (for example by asking a person or another service, or by completing it out of band) and credentials are received by the agent **out of band**, not in the message. Messages may still be sent to the task meanwhile.

### Operations

`SendMessage`, `SendStreamingMessage`, `GetTask`, `ListTasks`, `CancelTask`, `SubscribeToTask`, four operations to create, get, list and delete a task's push-notification configuration, and `GetExtendedAgentCard` (a richer card for authenticated callers). Details that matter:

- **A send returns a task or a plain message.** An agent may answer directly with a message and never create a task.
- **A send waits by default.** Unless the request sets `returnImmediately`, the server must hold the call until the task finishes or becomes interrupted.
- **Identifiers.** Task ids are always made by the server, and a message that names a task must name one that exists. A client may propose a context id, but a server may refuse it, and clients are advised not to propose one unless they know how the server handles it. A server may use the message id to spot duplicates, but is not required to.
- **Listing and getting are scoped to the caller.** A server must return only what the caller may see, and must not reveal that a resource exists when the caller may not see it.

### Following a running task

While a task runs, a caller can hold a connection open and receive events (streaming), ask for the task's state from time to time (polling), or register a web address that the agent calls (push notification). A push is delivered **at least once**, so it can arrive twice, and the receiver must acknowledge it with a success status.

### What the specification says about safety

- Production use must be encrypted (TLS). A server must authenticate every request and decides what the caller may do by its own policies. Credentials are obtained out of band and are sent with every request.
- File references (the `url` part) must be validated to prevent server-side request forgery, and agents that call a registered web address are advised to reject private and local addresses.
- Agent cards may be signed, and clients are advised to verify a signature when one is present.
- A card can mark an **extension** as required. A client that does not understand it will be refused, so it should not try.
- Each interface states the version it speaks, the client sends the version it uses, and an unsupported version is an error.

## 2. How it differs from MCP

Both protocols publish a described capability. The description is written in plain language so that a model can read it, decide it fits, and use it. If you strip away the jargon, that is what they share.

What differs is everything after the choice:

| | MCP | A2A |
|---|---|---|
| **What is on the other side** | A tool or data source. It does no thinking of its own. | Another agent, with its own model, prompts, tools and judgement. |
| **What you send** | Exact arguments that match a declared schema. | A goal, in words (with optional structured data and files). |
| **What comes back** | A result, straight away. | A task that moves through states and delivers artifacts, or sometimes a plain message. |
| **Time** | A call returns. | A task can run for minutes or days. |
| **Dialogue** | None. | The agent can stop and ask the caller a question, or ask for authorization, and then carry on. |
| **Visibility** | You know exactly which tool ran. | The other agent is opaque: its prompts, tools and memory never cross over. |
| **Trust** | The tool does what its schema says. | The other agent may refuse, misunderstand, or answer in a way you did not expect. |

Short version: MCP connects an agent to its tools. A2A connects an agent to other agents. Many stacks use both.

The English description is only how a model finds something. What decides whether it is safe is everything that is not English: who may call it, what it may do, how long it may run, what it costs, and what is checked on the way in and out. A description in a tool or a card is also input that someone else wrote, and it can try to steer the model. That is why llm4j never lets a card's text grant access: the script names what may be called.

## 3. What a typical exchange looks like

Say a buying workflow needs a price from a supplier's agent. This is the usual sequence, shown in the JSON-RPC binding. The shapes follow the specification's definitions and worked examples; the values are made up.

**1. Discover.** The caller fetches the card from the supplier's well-known address. It reads the interfaces, picks the first one it can speak, and notes the media types the agent accepts, whether streaming or push are offered, and how to authenticate. Credentials are obtained separately.

**2. Send a goal.** The caller sends a message to the address of the chosen interface. It asks to be answered at once, because the work may take a while:

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "SendMessage",
  "params": {
    "message": {
      "messageId": "m-1",
      "role": "ROLE_USER",
      "parts": [ { "text": "Quote 500 units of part 12." } ]
    },
    "configuration": { "returnImmediately": true }
  }
}
```

**3. A task comes back.** The result holds either a task or a message. Here it is a task that has started, with an id the server made:

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "task": {
      "id": "t-77",
      "contextId": "c-9",
      "status": { "state": "TASK_STATE_WORKING" }
    }
  }
}
```

**4. The caller asks again later** (or is told by a stream or a push notification):

```json
{ "jsonrpc": "2.0", "id": 2, "method": "GetTask", "params": { "id": "t-77" } }
```

The state is now `TASK_STATE_INPUT_REQUIRED`, and the status carries the question as a message from the agent: "Which delivery date do you want?"

**5. Answer, on the same task.** The caller sends a message that names the task (and so its context):

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "SendMessage",
  "params": {
    "message": {
      "messageId": "m-2",
      "taskId": "t-77",
      "role": "ROLE_USER",
      "parts": [ { "text": "Within 30 days." } ]
    },
    "configuration": { "returnImmediately": true }
  }
}
```

**6. Completion.** A later `GetTask` shows `TASK_STATE_COMPLETED` and an artifact. The quote can be text, structured data, or a link to a file that the agent made:

```json
{
  "artifactId": "a-1",
  "name": "Quote",
  "parts": [
    { "text": "500 units at ₹42, delivery within 30 days." },
    { "url": "https://files.supplier.example.com/q-77.pdf", "filename": "quote.pdf", "mediaType": "application/pdf" }
  ]
}
```

Things worth noticing:
- The caller never sees how the supplier arrived at the quote.
- The pause for a question is part of the protocol and not an error.
- Between the question and the answer nothing has to stay connected, so the task has to be remembered by someone.
- The link in the artifact is a request for the caller to fetch something that a stranger named. Nothing in the protocol makes that safe: the specification only says references must be validated.
- The agent could have answered step 2 with a plain message and no task, and a caller that does not set `returnImmediately` would have been held until the task finished or paused.

## 4. Why llm4j does not host an A2A server

llm4j is a framework for building agents and workflows. A2A, like any protocol, has a part that is about **meaning** (what a card, a task and a message are, and how they map onto an agent or a workflow) and a part that is about **running a service** (a web server, TLS, authentication, storage, scaling, polling loops and webhook receivers). We provide the first and leave the second to the people who use the framework. The reasons:

- **Hosting is full of decisions that belong to the consumer.** Which web stack, which address, which certificate, which identity provider, where task state is kept, how many instances run, how tenants are separated. The specification itself says a server must authenticate every request and authorises according to its own policies. Those policies are the operator's.
- **A framework that owns a public endpoint owns its security for everyone.** Every deployment's attack surface would become ours to patch and to explain. A handler that someone mounts in their own server keeps that responsibility with the people who run it.
- **It would break the promise to stay light.** llm4j avoids framework dependencies, background threads nobody asked for, and static initialisers that do work. A built-in server and polling loop would need all three.
- **People already have servers.** Teams use Spring, Javalin, Quarkus, a gateway or a cloud function. What they lack is the protocol layer, and that is what llm4j gives them.

What llm4j does provide: a version-free model of agents, tasks and messages (principle 9); a card builder that turns a script's declaration into card JSON; a handler that takes an A2A request and returns a response (or a stream of events), which any web server can mount; a `RemoteAgent` for calling other agents; one optional helper that translates A2A 1.0 over JSON-RPC to and from that model; and the interfaces a consumer fills in. In short, **llm4j supplies something to mount, never something to run.**

### What the consumer provides

Because llm4j runs nothing, the people who use it supply the running parts. The framework's documentation has to say each of these plainly, with a worked example, because they are the consumer's duty and not a detail:

- **A transport**, to move requests and responses to the other agent. It speaks whatever binding and version that agent uses, adds any headers, tokens or retries the organisation needs, resolves credentials from the secret store for the right host, validates addresses, and never follows a redirect it should not. llm4j supplies the model and, optionally, a helper that translates A2A 1.0 over JSON-RPC.
- **A channel**, for what comes back that needs a decision: a question, a request for authorization, a link. It decides who answers, and whether to fetch a link and from where. Fetching a link is the consumer's responsibility, including validating the address against server-side request forgery, as the specification requires.
- **The ways of waiting.** llm4j never waits or polls. The consumer chooses to poll, to stream, or to receive push notifications, tells the workflow when a task has changed, and handles a notification that arrives twice or late. A consumer that registers a webhook also owns its security: authenticating the caller and answering with a success status.
- **To serve:** mount the handler on a route and a well-known card address, authenticate every request, decide what each caller may do, and tell the handler who the caller is (and the tenant, if any). The handler never accepts a request without an identity and shows each caller only its own tasks.
- **When a caller wants to be answered only when the task finishes or pauses** (the specification's default for a send), the handler gives the host a future or a callback to complete, and the host decides how to wait, for how long, and what to do on a timeout. llm4j never holds a request open itself.
- **Where tasks and files live.** A workflow's journal is durable, and the folder for received files has to survive with it, because a resumed run checks the files again. For a plain agent exposed from Java, the consumer supplies the task store.
- **Cards:** serving the card with sensible caching headers, signing it if the organisation wants that, and verifying a signature on a card it receives, through a hook llm4j provides.
- **Operations:** TLS, rate limits, scaling, monitoring and the identity provider.

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
7. **No ownership of waiting.** llm4j does not poll, sleep or schedule on behalf of a consumer. It returns the state of a task, and whoever drives the call decides when to look again. In Loom, a durable run pauses and the host resumes it. This holds when the protocol asks a server to wait: where a caller wants an answer only when the task finishes or pauses, the handler hands the host something to complete and does not block a thread itself.
8. **Smallest footprint.** The model and the handler interface go in the core with no new dependencies. Loom's adapter depends on the core, never the other way round. No HTTP server or client library is added for A2A.
9. **Version-agnostic.** llm4j's own model is normalised and carries no protocol version. It holds only what llm4j's behaviour needs, and the consumer's transport translates between it and the wire for whatever version and binding the other agent speaks.
   - **What the model holds.** For an agent: its name, description, skills, the endpoints it offers (address, binding, version) and the input types it accepts. For a task: its id, its context id and one state from a small fixed set (working, input needed, authorization needed, completed, failed, canceled, rejected, or unknown). For messages and artifacts: text and structured data.
   - **Nothing above the transport knows a version.** That covers the Loom syntax, `agent_channel`, the journal, the audit rules and the card check. A script never states an A2A version, and the `remote` block has no version setting.
   - **One place knows one version.** The single optional helper that translates A2A 1.0 over JSON-RPC is the only code in llm4j that knows a wire name. A consumer who speaks another version or binding writes their own translation and uses everything else unchanged. We do not add `if (version == X)` branches to shared code.
   - **Fail closed on what is not understood.** If the card offers no interface the transport can speak, or marks an extension as required that the transport does not support, the call is refused before anything is sent, with a message that names what was offered. A state that is not recognised becomes *unknown*: it is never treated as completed, it is reported to the consumer, and if it lasts until the timeout the step fails. Unknown fields in incoming data are ignored, so a newer agent's extra fields do not break a caller.

**What we will not do**, so nobody has to ask: run a server; ship an HTTP client or poller for A2A; follow every protocol version in our own types; provide an identity or login system; operate a registry or directory of agents; wrap a remote agent as a tool; or add a framework adapter. Each of these is a reasonable thing for a consumer to build on top.

**When in doubt**, ask in this order: Does this make llm4j's own types or syntax depend on a protocol version? If so, move it into the translation. Does this move a decision about the consumer's environment into llm4j? If so, make it an interface. Does this protect against a failure the consumer cannot easily see? If so, llm4j decides and fails closed. Can a reviewer see it in the script? If not, it needs a declaration.

A change to these principles is the owner's decision and is made here, in this page, before it is made in code.
