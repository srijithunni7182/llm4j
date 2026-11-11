# What A2A is

**Who this is for:** anyone who needs to know what the Agent2Agent (A2A) protocol is before using or reviewing llm4j's support for it. No llm4j knowledge is needed.

This page describes A2A version **1.0.0**, read from its published text and its protocol definition (the protobuf file). Where the prose disagrees with itself or with the definition, the definition is followed. llm4j's own view of it, and what it does and does not do, is on [Our approach](PHILOSOPHY.md).

## What A2A is

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

## How it differs from MCP

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

## What a typical exchange looks like

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

## Glossary

| Word | Meaning |
|---|---|
| **Card** | The JSON document an agent publishes about itself. |
| **Interface** | One address, binding and protocol version at which an agent can be reached. |
| **Task** | The unit of work a remote agent does for a goal; it has a state and artifacts. |
| **Context** | An id that groups related messages and tasks. |
| **Part** | A piece of a message: text, bytes, a link or structured data. |
| **Artifact** | An output of a task. |
| **Interrupted** | A task waiting for the caller: input required or auth required. |
| **Terminal** | A task that is finished: completed, failed, canceled or rejected. |

## Next

[A2A support in llm4j](README.md) · [Our approach](PHILOSOPHY.md) · [The Java guide](JAVA_GUIDE.md)
