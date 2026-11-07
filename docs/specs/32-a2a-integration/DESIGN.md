# Phase 32 design: A2A (Agent2Agent) integration (ai-agent4j, Loom)

Status: **draft for review** (D1 to D18 and D27 were taken by the owner in discussion; D19 to D26 and D28 to D31 are proposed and open). The A2A facts in this document were checked against the 1.0.0 specification and its protocol definition (VERIFICATION §7); where the specification's prose disagrees with its definition, the definition is followed. Layers: ai-agent4j (a version-free model, the interfaces a consumer implements, `RemoteAgent`, the
server-side handler), Loom (`remote`, `expose`, `agent_channel`, `ask`, the runtime, `weave check`, `weave audit`, the graph, the journal), docs and editor support.
Companion documents: [PRINCIPLES.md](PRINCIPLES.md) (the guiding principles; **every choice here is checked against it, and a conflict is settled there first**), [TEST_STRATEGY.md](TEST_STRATEGY.md),
[VERIFICATION.md](VERIFICATION.md), [roadmap](../README.md). Builds on the media model ([Phase 31](../31-multimodal-input/DESIGN.md): `Media`, `MediaPolicy`, `media_root`, the journal descriptors, LA23),
the secret store, `LoomHost` and the run journal, and `expecting` schemas.

## 1. Problem

llm4j can call tools over MCP and run many agents inside one Loom workflow, but it cannot talk to an agent that someone else runs, and nobody else can talk to one of its agents or workflows.
A2A is the open protocol for that: one agent hands a goal to another over HTTP, the other works on it as a **task** that can take hours, may stop to ask a question, and delivers **artifacts**.
Other stacks advertise it (Google's ADK for Java, Spring AI, LangChain4j's agentic module), and an organisation that wants its workflow to buy from, or sell to, other companies' agents needs it.

The difficulty is not the wire format. It is that A2A brings an untrusted party into the middle of a workflow: its replies, its questions, its links and files, and (on the serving side) its input, all arrive
from outside, and the exchange takes time and money that no budget of ours sees. llm4j's value is that such things are declared, bounded, journaled and checked before they run. A2A has to arrive with the same guarantees,
without llm4j becoming a service that someone has to operate.

## 2. Goals and non-goals

Goals:
- G1. A Java application can **call** a remote A2A agent with a `RemoteAgent`, and a Loom script can call one with a plain `delegate`.
- G2. A Java application can **expose** a Loom workflow, and (if D22 is accepted) a plain `ReActAgent`, through a handler that any web stack can mount.
- G3. llm4j stays a **facilitator**: the consumer owns transport, polling, hosting, login, storage and who answers a question. llm4j owns meaning (the model, the mapping onto agents and runs) and safety.
- G4. Questions, requests for authorization, links and files that come from the other side are handled by a **consumer-implemented channel**, declared in the script so that the audit can read who is asked.
- G5. **Version-free**: nothing above the transport knows an A2A version; one optional helper translates A2A 1.0 over JSON-RPC.
- G6. **Fail closed, early, and visibly**: a card that points elsewhere, an unsupported version, an unhandled message or an unknown state stops the step; nothing crosses the boundary unrecorded.

Non-goals (each is something a consumer can build on top; the full list is in PRINCIPLES.md):
- N1. Running a server, an HTTP client, a poller or a webhook receiver for A2A. No default transport and no Spring adapter.
- N2. A tool wrapper for a remote agent, an agent registry or directory, an identity or login system.
- N3. Versions other than 1.0 in our own types or helper; gRPC and REST helpers (a consumer can write them against the same model).
- N4. Files as replies to a question, or following a link inside llm4j.
- N5. Verifying signed agent cards (an optional hook is D25, open).
- N6. Any change to `human_prompt`, `approve`, the person channel or `LoomHost`.

## 3. Part A: the core (package `io.github.llm4j.a2a`)

### 3.1 The model

All types are immutable records or sealed interfaces, validated in the constructor, and carry no protocol version.

- **R1. The model.**
  - `AgentCardView`: name, description, skills (id, name, description, tags, examples, input and output media types), `endpoints` (address, binding, protocol version as the card states it, and an optional tenant), the media types it accepts by default, the extensions it declares (URI and whether required), whether it carries signatures, and the authentication schemes it declares. A card has no single address: the endpoints are in preference order.
  - `RemoteTask`: id, context id, `TaskState`, an optional message (the question when input is needed), and artifacts.
  - `TaskState`: `WORKING` (which includes "submitted"), `INPUT_REQUIRED`, `AUTH_REQUIRED`, `COMPLETED`, `FAILED`, `CANCELED`, `REJECTED`, `UNKNOWN`. Terminal: completed, failed, canceled, rejected.
  - `A2AMessage`: message id, role, parts, and optional context id, task id and referenced task ids. `Part` is sealed: `Text`, `Data` (a JSON value), `FileOffered` (a link, or bytes already held, with a label and a claimed media type).
  - `SendResult`: sealed, either a `RemoteTask` or a plain `A2AMessage`. An agent may answer a send directly with a message and never create a task.
  - `Artifact`: id, name and parts.
  - Unknown fields in input are ignored. A state that is not recognised is `UNKNOWN`.
  - `A2AException` has subclasses for what the protocol reports (task not found, not cancelable, unsupported operation, content type not supported, version not supported, extension support required, and so on), so callers never see a wire code.
- **R2. Operations, not wire.** `A2ATransport` is the one interface through which everything leaves the process, written at the level of operations:
  `fetchCard(address) → AgentCardView`, `send(endpoint, credentialRef, message, options) → SendResult`, `get(endpoint, credentialRef, taskId) → RemoteTask`, `cancel(...)`, and `recentTasks(endpoint, credentialRef, since, historyLimit) → List<RemoteTask>`
  (used to look for an earlier send after a crash, R16). `options` says, among other things, that the caller does not want to wait (`returnImmediately`); the specification's default is that the server holds the call until the task finishes or pauses, so a translation always sets it. The transport also says which (binding, version) pairs and which extension URIs it supports (`supports(endpoint)`, `supportedExtensions()`). There is **no default implementation**. Each method may throw an unchecked `A2AException` subclass with a message that names what to do. The consumer's code adds headers, auth and retries.
- **R3. The credential is a reference.** `credentialRef` is a `SecretRef` (or empty). llm4j never resolves it; the transport resolves it for the host the endpoint names, as the rest of the library does. A `toString`, log line, journal entry or exception never contains a credential.

### 3.2 `RemoteAgent`

- **R4. Behaviour.** A `RemoteAgent` is built from a declared card address, the transport, an `AgentChannel` and optional limits (§3.4). It has `send(message)`, `reply(taskId, message)`, `get(taskId)`, `cancel(taskId)`; each returns a `RemoteTask`.
  `reply` is valid only for a task that is waiting on the caller (input or authorization required); a task in a terminal state takes no more messages, and a refinement is a new `send` that may name the same context. A `send` may return a plain message and no task.
  It never waits, polls or sleeps (principle 7): whoever drives it decides when to look again. It is **not** a second implementation of an `Agent` interface (D15), because a remote agent has no steps or token usage to report.
- **R5. The endpoint guard.** Before the first send, the card is fetched through the transport, and the endpoint to use is chosen from `endpoints`: the first one whose binding and version the transport says it supports.
  - The chosen address must be on the **host the declaration named** (the card address's host, or a host the declaration lists). Otherwise the call is refused before anything is sent, saying which host the card tried to use.
  - If no endpoint is supported, the call is refused and the message names the versions and bindings the card offered (principle 9).
  - The check is on data, not on HTTP, so it is ours (principle 2). Redirects, private addresses and TLS are the transport's.
- **R6. Input types.** If the message carries files and the card (or skill) declares the media types it accepts, a file of another type is refused before sending. If the card declares none, the file is sent and the remote may refuse it.
- **R6b. Required extensions.** If the card declares an extension as required and the transport does not list its URI in `supportedExtensions()`, the call is refused before anything is sent, naming the extension. Otherwise the agent would reject it anyway.
- **R7. Unknown states and fields.** `UNKNOWN` is never treated as completed. A task that stays `UNKNOWN` until the timeout fails the step. Extra fields are ignored.

### 3.3 The channel

- **R8. `AgentChannel`.** A consumer interface with one method, `handle(ChannelContext, ChannelMessage) → ChannelResponse`.
  - `ChannelMessage` is sealed: `Question` (text, optional data, optional `expecting` schema, the attempt number, the reason the last reply was rejected), `AuthorizationNeeded` (a description), `FileOffered` (the link or bytes, a label, a claimed media type).
  - `ChannelResponse` is sealed: `Reply` (text and/or data), `Defer` (a token; completed later by the consumer), `Refuse` (a reason), `Received` (the files written through the context's sink), `Ignore` (a file or link only).
  - A `switch` over these has no default branch, so a new kind of message cannot be added without every channel being updated at compile time.
- **R9. `ChannelContext`** is a read-only view: run id, step id, workflow name, direction (`INBOUND_QUESTION`, `OUTBOUND_ASK`), the counterpart (a remote agent's name, or the caller's id), the deadline, questions left, and `receiveFile(label, claimedMediaType)`, which returns an `OutputStream`. It does not expose the variable scope. Text from outside is flagged as untrusted.
- **R10. The runtime wraps every channel call** (class `ExchangeGuard`). It enforces, whatever the channel does: the **question limit** (per run and step, retries included), the **timeout**, the **size** of replies, and **validation** of a structured reply against the `expecting` shape.
  A reply that fails validation is returned to the channel with the reason and counts as one question used (D8); when the limit is spent, the step fails. A channel that throws, or returns nothing, fails the step. A message no channel handles fails the step and is never dropped silently.
- **R11. The received-file sink.** `receiveFile` returns a stream the runtime owns.
  - The destination is `received/<run id>/<generated name>` inside the script's `media_root` (D11). The sender's name is kept only as a label and never builds a path.
  - Bytes are counted as they arrive and the write stops at the script's `media { max_bytes }`; the total per task is bounded by `max_items`.
  - On `close`, the type is decided from the bytes ([Phase 31](../31-multimodal-input/DESIGN.md) `MediaSniffer`); an unknown type, SVG, or a kind that is not what was claimed is refused and the file removed. The SHA-256 is computed as the bytes pass.
  - The file is written under a temporary name and moved into place only on a clean close. A failed write leaves nothing.
  - With no `media_root`, `receiveFile` fails closed.

### 3.4 The optional 1.0 helper

- **R12. `A2AJsonRpc10`** (one class, pure functions, no network): builds the JSON-RPC 1.0 request for each operation from the model, and parses a response into the model, plus the same for a card. It is the only code in llm4j that knows a wire name. It also builds the service parameters a request needs (the protocol version header) and sets `returnImmediately`. It accepts the state spellings the 1.0 text and examples use (the enum form, and the older words that some examples still show), accepts both names the specification uses for stream events and for the push-notification setting, writes only the 1.0 form, and ignores unknown fields.
  A consumer's transport that speaks JSON-RPC 1.0 calls it and only moves bytes. A consumer who speaks anything else writes their own mapping and uses the rest unchanged.

### 3.5 The server side

- **R13. `A2AHandler`.** A framework-neutral handler: `handle(CallerIdentity, A2ARequest) → A2AResponse` or a stream of events. The consumer mounts it in any web stack and does the authentication; it passes in who the caller is.
  - `CallerIdentity` is **required**. With none, the handler refuses (D17).
  - Every task belongs to the caller that created it; `get`, `list` and `cancel` are filtered by caller. Asking for another caller's task answers "not found".
  - The handler maps requests onto a `TaskRunner` interface (§4.3 for Loom, §5 for a plain agent). It starts nothing on its own.
  - **It never blocks** (D27). A send that does not set `returnImmediately` must be held until the task finishes or pauses; the handler returns a `CompletionStage` that the host completes and decides how long to wait for. A send that does set it answers at once.
  - **Request rules from the specification.** A message that names a task must name one that exists (else "task not found"); the context is inferred from the task, and a message whose context and task disagree is rejected; a client-supplied task id for a new task is not accepted; a context id the server cannot honour is rejected and never replaced; a task in a terminal state takes no more messages. `GetTask` honours `historyLength`; `CancelTask` is idempotent; `ListTasks` is scoped to the caller, sorted by last update, newest first, with `pageSize` between 1 and 100 (default 50) and a `nextPageToken` that is always present.
  - `CallerIdentity` carries an optional tenant, which the handler uses to scope tasks.
- **R14. The card builder.** `AgentCardBuilder` turns a declaration (name, description, skills, accepted media types) plus the host's details (public address, authentication schemes, provider) into an `AgentCardView`, and `A2AJsonRpc10` writes it as the 1.0 card JSON. It runs nothing. The host's details can list several interfaces, and the declared capabilities default to **no streaming, no push notifications and no extended card**, because llm4j offers none of them itself; a host that provides them says so. The builder derives an `ETag` from the card's content so the host can serve caching headers. Signing a card is the host's job.
- **R14b. The card verifier hook.** A consumer may give `RemoteAgent` a `CardVerifier`. If a card carries signatures and a verifier is configured and rejects them, the call is refused before anything is sent. If a card is signed and no verifier is configured, the call goes ahead and the trace says the signature was not verified. There is no default verifier (D25).

## 4. Part B: Loom

### 4.1 Syntax

Three new top-level declarations and one statement. All four words are contextual: an existing agent, variable or parameter with one of these names still parses (as `attach` and `media` do).

```loom
agent_channel SupplierDesk {
    audience: staff            // staff | caller | automatic
    max_questions: 3
    timeout: 1h
}

remote Supplier {
    card: "https://supplier.example.com/.well-known/agent-card.json"
    auth: secret.SUPPLIER_KEY          // a reference, never a literal
    agent_channel: SupplierDesk
    timeout: 2h
}

expose Expense {
    workflow: Expense
    description: "Reads a receipt and returns the total and currency."
    agent_channel: CallerDesk
    skills: [ { id: "read_receipt"  name: "Read a receipt" } ]   // optional; one skill is derived from the workflow by default
    budget { tokens: 20000  calls: 10 }
    timeout: 30m
}

workflow Buy(part) {
    delegate "Quote 500 units of {part}" to Supplier -> quote
        on_failure { halt "the supplier could not quote" }
    for each f in quote_files max 4 {
        delegate "Check this against our limits." to Reviewer attach document "{f.path}" -> verdict
    }
}

workflow Expense(receipt) {
    ask CallerDesk "Which currency?" -> reply expecting { currency: enum["USD", "INR"] }
}
```

- **`remote Name { … }`** settings: `card` (required, `https`), `auth` (a `secret.NAME` or `env.NAME` reference), `agent_channel` (required, must name a declared one), `timeout`. There is **no version setting** (principle 9). It can be the target of `delegate` and of `for each … delegate`; `handoff`, `broadcast` and `extract` do not take it (D20).
- **`agent_channel Name { … }`** settings: `audience` (required), `max_questions` (default 3), `timeout` (default 1h). The implementation is registered by the host under the same name; a name with no implementation fails closed when first used, and `weave check` warns that the host must supply it (as for a `run` task).
- **`expose Name { … }`** settings: `workflow` (required; must exist; only workflows, D5), `description` (required), `agent_channel` (required), `skills`, `budget`, `timeout`. Several `expose` blocks may name the same workflow.
- **`ask Channel "text" -> var [expecting { … }]`**: a statement that sends a question to a declared `agent_channel` and binds the reply. Without `expecting`, `var` is text; with it, `var` is a typed object checked against the shape. It takes the usual `retry`, `timeout` and `on_failure`.
- **Results.** `delegate … to Remote -> quote` binds `quote` to the text of the final message and artifacts, joined. Data parts and the files are never merged into `quote`; the files arrive as a list variable `quote_files` (each item with `path`, `kind`, `mediaType`), as a grounded agent's sources arrive in `{name_citations}`. Structured results (D26) are open.

### 4.2 Behaviour: calling

- **R15. Card and guard.** At run time, before sending: the card is fetched through the transport, the endpoint guard (R5) and the input-type check (R6) run, and a failure is a step failure like any other (`retry` and `on_failure` apply). A refusal is not retried when it cannot change (a card that points elsewhere).
- **R16. The send is an effect.** Before the message is sent, the journal records the message id, the time and the remote's name (and the context id if one was proposed). The specification lets a server de-duplicate on message id but does not require it, and a server may refuse a context id the client proposes, so neither is relied on. After a crash the runtime does not resend. It asks the transport for `recentTasks` since the recorded time, with history, and looks for the task whose history holds our message id. If it finds one it re-attaches. If it finds none, or the server will not list, it **stops with a message** saying the outcome is unknown, as other unknown-outcome effects do (D19). A reply that was a plain message is journaled with the answer, so it is not asked again.
- **R17. Waiting.** The run pauses holding no thread when the task is not terminal. The host's poller, stream or webhook calls the adapter's `taskChanged(runId, taskId)` entry point (on the Loom adapter, not on `LoomHost`), which resumes the run. A push notification is delivered at least once, so `taskChanged` is idempotent: a repeat or a stale one (an older state than the run already has) changes nothing. The deadline is the remote's `timeout`.
- **R18. States.** A plain-message reply to a send binds its text and ends the step without a task. `COMPLETED` binds the result. `FAILED`, `REJECTED` and `CANCELED` fail the step with the remote's message (untrusted, shortened). `UNKNOWN` follows R7. `INPUT_REQUIRED` and `AUTH_REQUIRED` go to the channel (R19).
- **R19. Channel dispatch.** A question or authorization request from the remote is turned into a `ChannelMessage` and given to the declared channel through `ExchangeGuard` (R10). A `Reply` is sent back on the same task; a `Defer` pauses the run until the consumer completes the token; a `Refuse` fails the step. An authorization request carries the agent's explanation; the specification expects the caller to arrange it (ask a person or another service, or complete it out of band) and the agent to receive credentials out of band, so the channel's `Reply` is only a message to the task and never carries a credential. A link or file offered in a message or artifact is a `FileOffered` message (R8); `Received` puts the files in `{name_files}`; `Ignore` drops that one file explicitly; anything else fails the step. Each message and response is journaled with an idempotency key.
- **R20. Files and `attach`.** A file that arrives sits under `received/…` and passes `Media` checks. Using it in the next step is the existing `attach` with a run-time path, so [Phase 31](../31-multimodal-input/DESIGN.md)'s guards and LA23 apply. Sending files to a remote uses `attach` as today, from `media_root`; the guards on PII (`media: unscanned`) apply because the destination is outside.
- **R21. `ask`.** The question goes through `ExchangeGuard` to the named channel with `direction = OUTBOUND_ASK`. The audit reads the audience (R25).

### 4.3 Behaviour: being called

- **R22. The Loom runner.** `LoomTaskRunner` implements the handler's `TaskRunner` over `LoomHost` without changing it. A task is a durable run (`start`); its state follows the run (running → `WORKING`, finished → `COMPLETED`, failed → `FAILED`, stopped → `CANCELED`); `cancel` is the existing stop; a crash resumes the run; `get` and `list` read `run(...)` and `runs()` filtered by the caller (R13).
- **R23. Input from outside.** An incoming message becomes the workflow's parameters (D19b, open): proposed, a single text part binds a workflow with one parameter, and a JSON data part binds by parameter name; anything else is refused with a message listing the parameters. File parts (`raw` or `url`) are not accepted in the first version. All of it passes the agent's guards (PII, bias) like any input.
- **R24. Pauses.** A pending `human_prompt` or an `approve` inside an exposed workflow is **internal**: the caller sees the task as `WORKING`. Only an `ask` to a channel whose audience is `caller` produces `INPUT_REQUIRED`. A request for authorization (`AUTH_REQUIRED`) is never produced by llm4j in the first version.
- **R25. The card and `weave card`.** `weave card main.loom [--address URL]` prints the card for an `expose` block with a placeholder address, so a reviewer sees in a diff what a change does to what outsiders see. It needs no network and no model.

### 4.4 `weave check`, `weave audit`, graph, journal

- **`weave check`** (R26):
  - Errors: a `remote` without `card` or `agent_channel`; a card that is not `https`; a literal in `auth` (as a literal secret is an error today); `ask` or `remote` naming an undeclared channel; an `expose` naming a missing workflow, or one that takes parameters the mapping (R23) cannot bind; `expose` or `ask` with an unknown audience; `delegate` with `attach` to a remote and no `media_root`.
  - Warnings: every `remote` ("not validated by weave: its card is checked at run time"); a channel the host must supply.
- **Audit rules** (R27; numbers are the next free ones at build time, LA24 onwards; each is added to `docs/security/weave-audit.md` and mapped in `owasp-llm-top-10.md`):
  1. A workflow named by `expose` takes input from outside. It counts as reading untrusted content for the trifecta, as web grounding does (LA20), so the trifecta rule fires if it also reaches private data and can send or act.
  2. A `remote` receives private data or files (data leaves, as LA19 reports for a classifier); it names what is sent and whether `pii:` masks it.
  3. An `agent_channel` with `audience: automatic` answers a party from outside; the answer is produced without a person.
  4. A `delegate` to a remote with no `on_failure` (a review rule on the reliability lens, since the call is an effect with an unknown outcome).
- **Graph and inspect** (R28): a `remote` is drawn as its own node kind with a chip, an `expose` marks the entry point, an `ask` shows its channel; `scripts/sync-graph-render.sh` keeps the copies in step. Fabric shows exchanges as descriptors (counterpart, state, counts), never the text of untrusted replies unescaped.
- **Journal and replay** (R29): the journal records, per remote step, the remote name, message id, context id, task id and state transitions, and per exchange its kind, direction, a digest of the content and its idempotency key; per received file, the media descriptor (kind, type, size, SHA-256). Resume, rewind and fork never resend a message that was acknowledged, and verify received files' hashes (a missing file fails the step).

### 4.5 Documentation for the consumer

- **R30. The consumer's duties are documented, with a worked example.** Because llm4j runs nothing, a new documentation page (the A2A page in the wiki, linked from `Home.md` and the Loom guide) states plainly what the consumer provides, as in the "What the consumer provides" section of [PRINCIPLES.md](PRINCIPLES.md): the transport (binding, version, headers, credentials per host, address validation, redirects), the channel (who answers, fetching links and validating them), the ways of waiting (poll, stream or push, and handling duplicate or late notifications and the webhook's own security), serving (mounting the handler, the card address and its caching headers, authentication, the caller identity and tenant, the future for a send that waits), where tasks and received files live with the journal, signing and verifying cards, and operations. Each duty has a short example, and the examples are compiled by a test (P8). The page also lists what the framework refuses to do on the consumer's behalf, so nobody looks for it.

### 4.6 Compatibility

A script with none of the new words parses, checks and runs exactly as before; `remote`, `expose`, `agent_channel` and `ask` become keywords only in the positions above. `human_prompt`, `approve`, the person channel (`--ask-via`), `LoomHost` and `HumanInterface` are unchanged.
Java callers of existing APIs are unaffected. `API_COMPATIBILITY.md` lists the new public types as added.

## 5. Part C: exposing a plain `ReActAgent` from Java (open, D22)

A `ReActAgent` has no journal, so the handler needs a `TaskStore` supplied by the consumer to keep tasks between requests. Proposed: an `AgentTaskRunner` adapts a `ReActAgent` to the `TaskRunner` interface; a task runs one `agent.run(...)`;
the conversation (`contextId`) maps onto the agent's memory if the consumer configured one; without a store, only short tasks that finish within the request are supported and anything longer is refused with a clear message.
`INPUT_REQUIRED` is produced only if the agent has an `ApprovalCallback` or a channel; otherwise the agent cannot ask. Budgets and PII masking apply only if the consumer configured them on that agent, and the class says so. This part can be dropped from the first release without changing anything above.

## 6. Decisions

| # | Decision | Status |
|---|---|---|
| D1 | llm4j is a facilitator: it owns meaning and safety, the consumer owns transport, polling, hosting, login and storage. | **taken** (owner) |
| D2 | No default transport; the transport is an interface. | **taken** (owner) |
| D3 | No Spring adapter; one framework-neutral handler. | **taken** (owner) |
| D4 | A separate `remote` declaration for a remote agent (not an `agent` with a setting). | **taken** (owner) |
| D5 | A separate `expose` declaration; only workflows can be exposed from a script. | **taken** (owner) |
| D6 | `agent_channel` is a new concept, separate from the existing person channel; the script declares its audience, question limit and timeout; the runtime enforces them. | **taken** (owner) |
| D7 | The statement is `ask Channel "…" -> reply`, keyword first. | **taken** (owner) |
| D8 | Replies may be structured, validated by `expecting`; an invalid reply goes back to the channel and counts against `max_questions`. | **taken** (owner) |
| D9 | Files are not accepted as replies. | **taken** (owner) |
| D10 | llm4j never follows a link; a link goes to the channel, which decides. | **taken** (owner) |
| D11 | Received files are written through a runtime-owned stream into `received/` under `media_root`, and reach the workflow as a list variable. | **taken** (owner) |
| D12 | Received files share the one `media { max_bytes }` cap (a separate setting is not added now). | **taken** (owner) |
| D13 | A remote agent's card is checked at run time; `weave check` only warns that it is not validated. | **taken** (owner) |
| D14 | The model is version-free; one optional helper translates A2A 1.0 over JSON-RPC. | **taken** (owner) |
| D15 | `RemoteAgent` is a separate type with no shared `Agent` interface; no tool wrapper is shipped. | **taken** (owner) |
| D16 | `weave card` is in scope. | **taken** (owner) |
| D17 | The handler wraps `LoomHost` and does not change it; a caller identity is required. | **taken** (owner) |
| D18 | The keyword for a remote agent is `remote`. | **taken** (not objected to) |
| D19 | After a crash the runtime does not resend: it lists recent tasks and matches its message id in their history, and stops with a message when it cannot tell (R16). The specification does not require servers to de-duplicate or to accept a proposed context id (VERIFICATION P3), so neither is relied on. | proposed |
| D19b | An incoming message binds the workflow's parameters as in R23 (one text part for one parameter; a JSON data part by name; no files). | proposed |
| D20 | A remote agent can be the target of `delegate` (and `for each … delegate`) only; not `handoff`, `broadcast` or `extract`. | proposed |
| D21 | The audit rules and review rule of R27. | proposed |
| D22 | A plain `ReActAgent` can be exposed from Java through a consumer-supplied `TaskStore` (§5); it can be left out of the first release. | proposed |
| D23 | Authorization requests (`AUTH_REQUIRED`) reach the channel as their own message kind and fail closed unless handled; llm4j never produces one in the first version. | proposed |
| D24 | An inline file part (bytes already in the message) is offered to the channel as `FileOffered` like a link, so a consumer can refuse it; the channel accepts by writing the stream. | proposed |
| D25 | An optional hook (`CardVerifier`) for verifying a signed agent card, with no default. The specification says clients should verify a signature when one is present, so the hook is now recommended (R14b). | proposed, recommended |
| D26 | Structured results (a data part) of a remote task are exposed to the script; proposed: a task with a data part binds it as an object when `expecting` is given on the `delegate`. | proposed |
| D27 | The handler never blocks. A send that must wait until the task finishes or pauses (the specification's default) returns a `CompletionStage` that the host completes; llm4j holds no request open. | **taken** (owner) |
| D28 | A send returns a task or a plain message (`SendResult`), and the translation always asks not to wait (`returnImmediately`). | proposed |
| D29 | A card that declares a required extension the transport does not support is refused before sending (R6b). | proposed |
| D30 | The endpoint and the caller identity carry an optional tenant, because the specification puts a tenant on interfaces and requests. How a tenant is written on each binding is not verified. | proposed |
| D31 | The consumer's duties are documented on a page of their own with compiled examples (R30). | proposed |

## 7. Sub-phases

| Sub-phase | Delivers | Requirements |
|---|---|---|
| **32a** | The model, `A2ATransport`, `AgentChannel` and its messages, `ChannelContext`, `ExchangeGuard`, the received-file sink, `RemoteAgent`, the endpoint guard, `A2AJsonRpc10`; fake transport and channel; fixtures from the specification | R1 to R12 |
| **32b** | `A2AHandler`, `CallerIdentity`, `TaskRunner`, the card builder; the plain-agent runner if D22 is accepted | R13, R14, §5 |
| **32c** | Loom, calling: the AST, parser, `weave check` for `remote`, `agent_channel` and `ask`, the runtime, `taskChanged`, the journal, the graph | R15 to R21, R26 (calling), R29 |
| **32d** | Loom, being called: `expose`, `LoomTaskRunner`, `weave card`, `weave check` for `expose` | R22 to R25, R26 (serving) |
| **32e** | The audit and review rules, security documents, Fabric display | R27, R28 |
| **32f** | Docs everywhere they live (the guide, tutorial chapter, wiki, READMEs, `llms.txt`, skills, release notes, `API_COMPATIBILITY.md`), the consumer's-duties page, the VS Code grammar and hover, CTK scenarios, a mutation profile | R30 and the documentation requirements |

Each sub-phase is committed and pushed on its own, with the full build of every module it touches green first, as in phase 31.
