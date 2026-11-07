# Phase 32 design: A2A (Agent2Agent) integration (ai-agent4j, Loom)

Status: **draft for review**. Every decision except D32 (§6) was taken by the owner in discussion; D32 is deferred. The A2A facts in this document were checked against the 1.0.0 specification and its protocol definition (VERIFICATION §7); where the specification's prose disagrees with its definition, the definition is followed. Layers: ai-agent4j (a version-free model, the interfaces a consumer implements, `RemoteAgent`, the
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
- G2. A Java application can **expose** a Loom workflow, and a plain `ReActAgent`, through a handler that any web stack can mount.
- G3. llm4j stays a **facilitator**: the consumer owns transport, polling, hosting, login, storage and who answers a question. llm4j owns meaning (the model, the mapping onto agents and runs) and safety.
- G4. Questions, requests for authorization, links and files that come from the other side are handled by a **consumer-implemented channel**, declared in the script so that the audit can read who is asked.
- G5. **Version-free**: nothing above the transport knows an A2A version; one optional helper translates A2A 1.0 over JSON-RPC.
- G6. **Fail closed, early, and visibly**: a card that points elsewhere, an unsupported version, an unhandled message or an unknown state stops the step; nothing crosses the boundary unrecorded.

Non-goals (each is something a consumer can build on top; the full list is in PRINCIPLES.md):
- N1. Running a server, an HTTP client, a poller or a webhook receiver for A2A. No default transport and no Spring adapter.
- N2. A tool wrapper for a remote agent, an agent registry or directory, an identity or login system.
- N3. Versions other than 1.0 in our own types or helper; gRPC and REST helpers (a consumer can write them against the same model).
- N4. Files as replies to a question, or following a link inside llm4j.
- N5. Verifying signed agent cards inside llm4j: the transport verifies and reports the result (D25).
- N6. Any change to `human_prompt`, `approve`, the person channel or `LoomHost`.

## 3. Part A: the core (package `io.github.llm4j.a2a`)

### 3.1 The model

All types are immutable records or sealed interfaces, validated in the constructor, and carry no protocol version.

- **R1. The model.**
  - `AgentCardView`: name, description, skills (id, name, description, tags, examples, input and output media types), `endpoints` (address, binding, protocol version as the card states it, and an optional tenant), the media types it accepts by default, the extensions it declares (URI and whether required), its signature status as reported by the transport (`NOT_SIGNED`, `VERIFIED`, `FAILED` or `NOT_CHECKED`), and the authentication schemes it declares. A card has no single address: the endpoints are in preference order.
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
  - `ChannelMessage` is sealed: `Question` (text, optional data, optional `expecting` schema, the attempt number, the reason the last reply was rejected), `AuthorizationNeeded` (a description), `FileOffered`, itself sealed: a `Link` (the address, a label, a claimed media type; the channel fetches it) or an `Inline` file (bytes already held, with `open()` to read them, a label and a claimed media type). Both are offered the same way (D24), so a channel's code to accept a file is the same for either.
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
  - `CallerIdentity` is **required** and is one opaque **scope key** that the host chooses (D30). With none, the handler refuses (D17).
  - Every task belongs to the scope that created it; `get`, `list` and `cancel` are filtered by scope. Asking for another scope's task answers "not found". This is defence in depth, not isolation (principle 10).
  - The handler maps requests onto a `TaskRunner` interface (§4.3 for Loom, §5 for a plain agent). It starts nothing on its own.
  - **It never blocks** (D27). A send that does not set `returnImmediately` must be held until the task finishes or pauses; the handler returns a `CompletionStage` that the host completes and decides how long to wait for. A send that does set it answers at once.
  - **Request rules from the specification.** A message that names a task must name one that exists (else "task not found"); the context is inferred from the task, and a message whose context and task disagree is rejected; a client-supplied task id for a new task is not accepted; a context id the server cannot honour is rejected and never replaced; a task in a terminal state takes no more messages. `GetTask` honours `historyLength`; `CancelTask` is idempotent; `ListTasks` is scoped to the caller, sorted by last update, newest first, with `pageSize` between 1 and 100 (default 50) and a `nextPageToken` that is always present.
  - **One runner per scope.** The handler asks a `TaskRunners` resolver (`forScope(scope) → TaskRunner`) which runner serves a scope, so a consumer that needs isolation can give each tenant its own host, with its own journal, files, memory, secrets and budgets (principle 10). llm4j never interprets a tenant. An endpoint's tenant, from the card, is passed through to the transport untouched.
- **R14. The card builder.** `AgentCardBuilder` turns a declaration (name, description, skills, accepted media types) plus the host's details (public address, authentication schemes, provider) into an `AgentCardView`, and `A2AJsonRpc10` writes it as the 1.0 card JSON. It runs nothing. The host's details can list several interfaces, and the declared capabilities default to **no streaming, no push notifications and no extended card**, because llm4j offers none of them itself; a host that provides them says so. The builder derives an `ETag` from the card's content so the host can serve caching headers. Signing a card is the host's job.
- **R14b. Signed cards.** llm4j does no cryptography and adds no dependency for it (principle 8). The transport verifies a card's signature if it wishes, with its own libraries, and reports the result in the card view (D25). A `FAILED` card is always refused before anything is sent. A `remote` that sets `require_signed_card: true` refuses anything but `VERIFIED`. With no setting, a `NOT_SIGNED` or `NOT_CHECKED` card goes ahead and the trace says so.

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

- **`remote Name { … }`** settings: `card` (required, `https`), `auth` (a `secret.NAME` or `env.NAME` reference), `agent_channel` (required, must name a declared one), `timeout`, `require_signed_card` (default false). There is **no version setting** (principle 9) and **no `pii:` setting**: how personal data is masked on the way out is the developer's decision (in the transport, in the channel, or in the script). It can be the target of `delegate` and of `for each … delegate` only; `handoff`, `broadcast` and `extract` do not take it, and fan-out uses `parallel` (D20). A `budget N tokens` on such a step is a `weave check` error, because the remote's cost is outside what we see. A dynamic target list (`in [A, B]`) names only remote agents or only local ones, never a mix.
- **`agent_channel Name { … }`** settings: `audience` (required), `max_questions` (default 3), `timeout` (default 1h). The implementation is registered by the host under the same name; a name with no implementation fails closed when first used, and `weave check` warns that the host must supply it (as for a `run` task).
- **`expose Name { … }`** settings: `workflow` (required; must exist; only workflows, D5), `description` (required), `agent_channel` (required), `text_input` (the one parameter that receives a text message; always explicit), `max_input` (the largest message accepted; default 64 KB), `skills`, `budget`, `timeout`. Several `expose` blocks may name the same workflow.
- **`ask Channel "text" -> var [expecting { … }]`**: a statement that sends a question to a declared `agent_channel` and binds the reply. Without `expecting`, `var` is text; with it, `var` is a typed object checked against the shape. It takes the usual `retry`, `timeout` and `on_failure`.
- **Results** (D26, D28). `delegate … to Remote -> quote` binds `quote` to the text of the final message and the artifacts, joined. The files arrive as a list variable `quote_files` (each item with `path`, `kind`, `mediaType`), as a grounded agent's sources arrive in `{name_citations}`, and the data parts as a list `quote_data`. A reply that is a plain message and not a task ends the step at once with the same bindings.
  With `expecting { … }` on the `delegate`, the result is **one structured object** checked against the shape: it comes from the single data part or, failing that, from a text part that parses as JSON (some remotes send it that way). Several data parts, or content that does not match, fail the step as a definite failure (so `retry` applies). The size and nesting depth of JSON from outside are capped by the runtime.

### 4.2 Behaviour: calling

- **R15. Card and guard.** At run time, before sending: the card is fetched through the transport, the endpoint guard (R5) and the input-type check (R6) run, and a failure is a step failure like any other (`retry` and `on_failure` apply). A refusal is not retried when it cannot change (a card that points elsewhere).
- **R15b. Stopping.** When a run is stopped or cancelled, the runtime asks the transport to cancel any remote tasks still running, as a best effort. The cancel is journaled, and a failure to cancel is recorded and does not block the stop (D20). `retry` on a `delegate` to a remote applies only after a definite failure (the remote reported failed or rejected, or the send was refused before anything left), never after an unknown outcome.
- **R16. The send is an effect.** Before the message is sent, the journal records the message id, the time and the remote's name (and the context id if one was proposed). The specification lets a server de-duplicate on message id but does not require it, and a server may refuse a context id the client proposes, so neither is relied on. After a crash the runtime does not resend. It asks the transport for `recentTasks` since the recorded time, with history, and looks for the task whose history holds our message id. If it finds one it re-attaches. The search starts a short margin before the recorded time, to allow for clock differences. A server may return less history than asked for and cut our message off; that is treated as finding none. If it finds none, or the server will not list, it **stops with a message** saying the outcome is unknown, as other unknown-outcome effects do (D19). A reply that was a plain message is journaled with the answer, so it is not asked again.
- **R17. Waiting.** The run pauses holding no thread when the task is not terminal. The host's poller, stream or webhook calls the adapter's `taskChanged(runId, taskId)` entry point (on the Loom adapter, not on `LoomHost`), which resumes the run. A push notification is delivered at least once, so `taskChanged` is idempotent: a repeat or a stale one (an older state than the run already has) changes nothing. The deadline is the remote's `timeout`.
- **R18. States.** A plain-message reply to a send binds its text and ends the step without a task. `COMPLETED` binds the result. `FAILED`, `REJECTED` and `CANCELED` fail the step with the remote's message (untrusted, shortened). `UNKNOWN` follows R7. `INPUT_REQUIRED` and `AUTH_REQUIRED` go to the channel (R19).
- **R19. Channel dispatch.** A question or authorization request from the remote is turned into a `ChannelMessage` and given to the declared channel through `ExchangeGuard` (R10). A `Reply` is sent back on the same task; a `Defer` pauses the run until the consumer completes the token; a `Refuse` fails the step. An authorization request carries the agent's explanation; the specification expects the caller to arrange it (ask a person or another service, or complete it out of band) and the agent to receive credentials out of band, so the channel's `Reply` is only a message to the task and never carries a credential. An authorization request counts as one of the questions (D23). A channel declared `audience: automatic` may handle it; the audit reports that as a high finding (R27). A link or file offered in a message or artifact is a `FileOffered` message (R8); `Received` puts the files in `{name_files}`; `Ignore` drops that one file explicitly; anything else fails the step. Each message and response is journaled with an idempotency key.
- **R20. Files and `attach`.** A file that arrives sits under `received/…` and passes `Media` checks. Using it in the next step is the existing `attach` with a run-time path, so [Phase 31](../31-multimodal-input/DESIGN.md)'s guards and LA23 apply. Sending files to a remote uses `attach` as today, from `media_root`; the guards on PII (`media: unscanned`) apply because the destination is outside.
- **R21. `ask`.** The question goes through `ExchangeGuard` to the named channel with `direction = OUTBOUND_ASK`. The audit reads the audience (R25).

### 4.3 Behaviour: being called

- **R22. The Loom runner.** `LoomTaskRunner` implements the handler's `TaskRunner` over the `LoomHost` that the resolver returns for the scope, without changing it. A task is a durable run (`start`); its state follows the run (running → `WORKING`, finished → `COMPLETED`, failed → `FAILED`, stopped → `CANCELED`); `cancel` is the existing stop; a crash resumes the run; `get` and `list` read `run(...)` and `runs()` filtered by the caller (R13).
- **R23. Input from outside** (D19b). An incoming message becomes the workflow's parameters:
  - A text part binds only to the parameter named by `text_input`; it is always explicit, even for a workflow with one parameter. Several text parts are joined with a blank line. A text message with no `text_input` set is refused.
  - A JSON data part binds by parameter name. Unknown keys are ignored. A missing parameter is refused, with a message listing what the workflow takes.
  - File parts (`raw` or `url`) are refused with a clear message, and the card advertises only text and JSON as accepted input.
  - A message over `max_input` is refused before any work starts. Everything that binds passes the agents' guards (PII, bias) like any input.
  - A message that names an existing task is not new input: it answers the `ask` that is waiting. A message that names only a context starts a new run, and the context id is stored with it so the caller can list its own tasks.
- **R24. Pauses.** A pending `human_prompt` or an `approve` inside an exposed workflow is **internal**: the caller sees the task as `WORKING`. Only an `ask` to a channel whose audience is `caller` produces `INPUT_REQUIRED`. A request for authorization (`AUTH_REQUIRED`) is never produced by llm4j in the first version.
- **R25. The card and `weave card`.** `weave card main.loom [--address URL]` prints the card for an `expose` block with a placeholder address, so a reviewer sees in a diff what a change does to what outsiders see. It needs no network and no model.

### 4.4 `weave check`, `weave audit`, graph, journal

- **`weave check`** (R26):
  - Errors: a `remote` without `card` or `agent_channel`; a card that is not `https`; a literal in `auth` (as a literal secret is an error today); `ask` or `remote` naming an undeclared channel; an `expose` naming a missing workflow, or one that takes parameters the mapping (R23) cannot bind; `expose` or `ask` with an unknown audience; `delegate` with `attach` to a remote and no `media_root`; a `budget N tokens` on a `delegate` to a remote; a dynamic target list that mixes remote and local agents; a `text_input` that names no parameter of the workflow; a `max_input` that is not a size; a `require_signed_card` that is not a boolean.
  - Warnings: every `remote` ("not validated by weave: its card is checked at run time"); a channel the host must supply.
- **Audit rules** (R27, D21; numbers are the next free ones at build time, LA24 onwards; each is added to `docs/security/weave-audit.md` and mapped in `owasp-llm-top-10.md`):

  | Rule | What it says | Severity | Maps to |
  |---|---|---|---|
  | LA24 | A workflow named by `expose` takes input from outside. Every agent it reaches counts as reading untrusted content, as web grounding does (LA20). The audit does not track data, so it assumes all of them see it. The trifecta rule fires if they also reach private data and can send or act. | follows LA01; low on its own | LLM01 |
  | LA25 | A `delegate` sends text and files to a remote agent, so data leaves. The finding names the remote and says that nothing in the script masks personal data first; a developer who masks in the transport or channel accepts the finding in writing with that reason. | low; medium if the workflow reaches private data and nothing in the script masks it | LLM02 |
  | LA26 | An `agent_channel` with `audience: automatic` answers a party from outside without a person. If it also handles authorization requests, the finding is high. | medium; high with authorization | LLM06 |
  | LA27 | An exposed workflow has no spend cap: neither a script budget nor an `expose` budget. Outsiders can drive the cost. | high | LLM10 |

  On the reliability lens, LR02 (a `delegate` that can act but has no `on_failure`) counts every remote agent as able to act, because we cannot see what it does. Whether Loom's `guardrail` block can wrap a remote step for masking is checked in the survey (VERIFICATION P4).
- **Graph and inspect** (R28): a `remote` is drawn as its own node kind with a chip, an `expose` marks the entry point, an `ask` shows its channel; `scripts/sync-graph-render.sh` keeps the copies in step. Fabric shows exchanges as descriptors (counterpart, state, counts), never the text of untrusted replies unescaped.
- **Journal and replay** (R29): the journal records, per remote step, the remote name, message id, context id, task id and state transitions, and per exchange its kind, direction, a digest of the content and its idempotency key; per received file, the media descriptor (kind, type, size, SHA-256). Resume, rewind and fork never resend a message that was acknowledged, and verify received files' hashes (a missing file fails the step).

### 4.5 Documentation for the consumer

- **R30. The consumer's duties are documented, with a starter that compiles** (D31). Because llm4j runs nothing, the page `docs/a2a/INTEGRATING.md` (R41) states plainly what the consumer provides, as in the "What the consumer provides" section of [PRINCIPLES.md](PRINCIPLES.md): the transport (binding, version, headers, credentials per host, address validation, redirects, a bound on the response size), the channel (who answers, fetching links and validating them, accepting inline files), the ways of waiting (poll, stream or push, and handling duplicate or late notifications and the webhook's own security), serving (mounting the handler, the card address and its caching headers, authentication, the scope key and the runner per scope, the future for a send that waits), isolation between tenants, where tasks and received files live with the journal, signing and verifying cards, and operations. The page also lists what the framework refuses to do on the consumer's behalf, so nobody looks for it.
  The examples come from a **starter project**, `src/examples/a2a-starter`, which is not published and is built only with the extended profile. It **compiles and need not run**. It uses only the JDK (no Spring) and contains a transport, a channel, a minimal serving route, a per-tenant runner resolver, a task store sketch and the Loom script that goes with them. **The script is an owner-approved exception to the rule that every `.loom` file in the repository passes `weave check` (D33):** it is a compile-only illustration with placeholders (an example card address, a secret that is not set, a channel the host supplies), so it is named, with this reason, in `RepositoryScriptsTest`'s exclusion list and in the starter's README. It is still **parsed** by a test, so a change to the grammar cannot break it silently. Wherever authentication, TLS, address validation, signature checking, durable storage or similar must be plugged in, the code has a clearly marked `TODO` comment and not an invented implementation. A test checks that the page's excerpts match the starter's source, and the starter is built in the extended CI job.

### 4.6 The ecosystem around Loom (nothing may drift)

Loom is more than its parser. It has an editor extension, a compatibility kit, an inspection and Fabric stack, a tutorial and starters, skills, test, evaluation and debugging tools, and many documents, and each of them describes or lists the language. A construct that exists in the runtime and is missing from any of them is a bug (principle 11). A survey of the repository for the places a recent construct (`intentclassify`) appears found the surfaces below. Each construct of this phase (`remote`, `agent_channel`, `expose`, `ask`, `delegate` to a remote, and `weave card`) must be handled in every one, **in the sub-phase that adds it** and not afterwards (§7).

| # | Surface | What must change | What catches drift |
|---|---|---|---|
| R31 | **Language core** (`ai-agent4j-loom`) | Every visitor of statements and declarations handles the new ones: `StatementWalker`, `SchemaInfer`, `StatementText`, `SubstitutedText`, `StaticSinkChecks`, `WorkflowLint`, `ScriptValidator`, `ScriptDrift`, the loader and imports | the existing suites and the maintainer's review of this table (the completeness test is deferred, D32) |
| R32 | **Symbols and editor** | `FileIndexer` and `SymbolService` index `remote`, `agent_channel` and `expose` as definitions, with references from `to X`, `agent_channel: X` and `ask X`, so go-to-definition, find references, rename and completion work; `docs/serve/PROTOCOL.md` documents the new symbol kinds (additive); in the VS Code extension: the grammar, hover text (`server.ts`), semantic completion, snippets (each is checked by `weave check`), the README lists of keywords and snippets, and the frozen TypeScript parser's parity baseline | grammar, semantic and snippet tests; the parity baseline; manifest and docs tests |
| R33 | **Test and evaluation tools** | `weave test` can **script a remote agent** (its states, a question, an authorization request, a file offered, a refusal) and **script a channel**, assert on remote steps, and count them in step coverage (`wtest`: `Assertions`, `Labels`, `TestChecker`); `weave eval` mock runs and eval4j trajectories name remote steps; `weave capture`, `regress` and `gate` (`Capture`) record exchanges as digests. Without this a workflow that calls a remote agent cannot be tested offline, which would break the promise of `weave test` | the `wtest` suites, CTK |
| R34 | **Debugging and demos** | `Stepper` steps over a remote call and shows the exchange; `why` and `whatif` explain a remote step; `Narrator` and the `try` demo read the new statements | debug tests |
| R35 | **Review, audit and cost** | `AuditCatalog` and the generated rule pages (regenerate with `-Dregenerate.rule.docs=true`); the review rules (`reliability.yaml`, the LR02 extension); `ConditionFacts`, `HandoffFacts` and `TestabilityRules`; `CostEstimator` and `RunProfile` show a remote step as **outside your budgets** and not as free; `BudgetCoordinator` does not count it | `RuleDocsGeneratedTest`, `SecurityDocTest`, review tests |
| R36 | **Graph** | `Kinds`, `NodeDescriber`, `GraphBuilder`, `graph-result.schema.json`, `GraphJson`, and `graph-render.js` with its copies in the VS Code extension and the eval4j report (`scripts/sync-graph-render.sh`, then `check-graph-render-sync.sh`); chips; the extension README's table of kinds | sync check, graph tests, JS tests |
| R37 | **Observation: trace, loom-inspect, Fabric** | `TraceEvent` types for a remote send, a state change, a question, an authorization request, a file offered, a channel reply, a cancel, and for served tasks; `ObserveProvider`, `ObserveMixin` and `EventSink`; `loom-inspect-core` incidents (a remote task past its deadline, a channel at its question limit) and security signals (an exposed workflow's untrusted input); OTLP spans (`OtlpJson`); Fabric shows exchanges as escaped descriptors; the wiki Observability pages and `docs/loom-inspect/DESIGN.md`. `loom-inspect*` and `loom-fabric` compile against the **installed** Loom jar, so it is reinstalled before their tests | inspect and Fabric tests |
| R38 | **Compatibility kit** (`ctk`) | Scenarios, mocks and traces for a call that completes, a question, a refused card, and an exposed workflow's card; the kit's README; a check of whether any other runtime in the repository needs the same scenarios (VERIFICATION P9) | `ctk` tests and the Java conformance tests |
| R39 | **Teaching: guide, tutorial, starters, skills, documents** | `LOOM_GUIDE.md` (parse-tested) and `LOOM_PROMPT.md`; `docs/guide` (01, 06, 07, `RECIPES.md`, `README.md`, 16); the tutorial chapters and index, with the page regenerated by `scripts/make-tutorial-html.sh`; a starter with its diagram script; the four skills (`llm4j-workflow-guide`, `llm4j-loom-deployer`, the `llm4j-harness-design` mapping, the `llm4j-eval-design` mappings) and `make skill-package`; the wiki pages; `docs/deploy/02-security-before-you-run.md`; `COMMAND_REFERENCE.md` and `weave man` for `weave card`; the READMEs, `ABOUT.md`, `THE_STACK.md`, `llms.txt`, a row in `WHY_LLM4J.md` and `REVIEWABLE_ARCHITECTURE.md` where it applies, `EXAMPLES.md`, the release notes and `API_COMPATIBILITY.md` | `TutorialExamplesTest`, `StartersTest`, `GuideTutorialPageTest`, the guide tests, `RepositoryScriptsTest` |
| R40 | **The VS Code extension release** | A version bump (the release check requires one), the `CHANGELOG.md` section, the README, the marketplace description; the extension suite; the publish workflow's checks | the extension suite and the publish workflow |

Two traps the survey found:
- **The "not validated" warning.** The tutorial's examples must pass `weave check` with no warning, and every `remote` produces that warning. Tutorial and starter scripts that call a remote agent need a stated policy (VERIFICATION P9): `loom-parse` blocks, or a marker for an expected warning, and the same for `StartersTest`. A remote step cannot run in the CLI (there is no transport), so a tutorial shows it through `weave test` with a scripted remote (R33).
- **The frozen TypeScript parser.** It cannot learn the new words, so the parity baseline records them as known differences; it must be regenerated deliberately, as it was when 1.3.0 was published.

### 4.7 Compatibility

A script with none of the new words parses, checks and runs exactly as before; `remote`, `expose`, `agent_channel` and `ask` become keywords only in the positions above. `human_prompt`, `approve`, the person channel (`--ask-via`), `LoomHost` and `HumanInterface` are unchanged.
Java callers of existing APIs are unaffected. `API_COMPATIBILITY.md` lists the new public types as added.

### 4.8 The documentation set

A2A support has its own set of documents, in **`docs/a2a/`**, written for different readers and linked together, so nobody has to read one long page to find what they need. Principle 7 of `CLAUDE.md` applies: a page lands **with the code it describes** (§7), never before it, and never after it. The one exception already exists: `PRINCIPLES.md` in this folder is the guiding page for the work. When 32a ships, its content moves into `docs/a2a/` (as `WHAT_IS_A2A.md` and `PHILOSOPHY.md`), and the file here becomes a short pointer so existing links keep working.

- **R41. The set.**

| Page | Reader and purpose | Lands with |
|---|---|---|
| `docs/a2a/README.md` | **A2A support in llm4j.** What you get and what you do not, the A2A version supported (1.0), who does what (llm4j and the consumer), a map of the other pages, and a table "which guide do I read" (pure Java, or Loom). Links to the philosophy first | 32a, final in 32f |
| `docs/a2a/WHAT_IS_A2A.md` | **What A2A is.** The protocol in plain language: the card, tasks, messages, parts, states, operations, following a running task, the safety the specification asks for; how it differs from MCP; a typical exchange with real shapes; a glossary | 32a |
| `docs/a2a/PHILOSOPHY.md` | **Our approach.** Why llm4j does not host a server; the facilitator principles (the eleven in `PRINCIPLES.md`); the safety rules llm4j enforces; what we will not do; "when in doubt" | 32a |
| `docs/a2a/JAVA_GUIDE.md` | **Developer guide for pure Java.** Calling a remote agent with `RemoteAgent`; writing a transport and a channel; exposing a workflow or a `ReActAgent` through the handler (`TaskRunners`, `AgentTaskRunner`, `TaskStore`); waiting; scopes and isolation; errors; testing with fakes. Excerpts are compiled | 32a (calling), 32b (serving) |
| `docs/a2a/LOOM.md` | **Developer guide for Loom.** `remote`, `agent_channel`, `expose` and `ask` with worked scripts; results (`quote`, `quote_files`, `quote_data`, `expecting`); receiving files; testing with a scripted remote in `weave test`; running through `LoomHost`; `weave card`; what `weave check`, the graph and the inspect stack show. ` ```loom ` blocks are parse-tested | 32c (calling), 32d (expose) |
| `docs/a2a/INTEGRATING.md` | **What you provide.** The consumer's duties, one section each, with excerpts from the starter: the transport, the channel, the ways of waiting, serving (mounting the handler, the card address, authentication, the scope key), isolation between tenants, where tasks and received files live, signing and verifying cards, operations; a go-live checklist | 32a to 32d, final in 32f |
| `docs/a2a/SECURITY_AND_REVIEW.md` | **How review happens.** What `weave check` reports (errors and the warning on every `remote`); `weave audit` rules LA24 to LA27 and the extended LR02 with what each means and how to fix it; what a reviewer reads (the declarations, the channel's audience, the implementation once); accepting a finding in writing; the safety rules llm4j enforces and the ones the consumer must; the mapping to the OWASP list | 32c (check), 32e (audit and review) |
| `docs/a2a/REFERENCE.md` | **Reference.** The Java interfaces and records; the Loom syntax and every setting with its default; task states and how they map to Loom outcomes; errors; trace events; limits and defaults (`max_questions`, `max_input` and the rest); audit and review rule ids | each sub-phase adds its part |
| `src/examples/a2a-starter/README.md` | **The starter.** What it contains, that it compiles and need not run, and a list of every `TODO` where authentication, TLS, address validation, signature checking or storage plug in | 32f |

Every page begins with who it is for and ends with links to the README, the philosophy and the next page to read. A short page, `src/ai-agent4j/wiki/A2A.md`, in the core wiki and an entry in `wiki/Home.md` and `docs/README.md` point to the set.

- **R42. The READMEs.** Every README below is updated in the sub-phase that makes it true, and a README that is **not** affected is recorded as reviewed with no change (VERIFICATION §3), so "all projects" is a list that was checked and not a hope.

| README | What it says about A2A |
|---|---|
| `README.md` (repository) | The stack table rows for ai-agent4j and Loom; a line in "Why llm4j"; the documentation list and the examples list, linking `docs/a2a/` and the starter |
| `src/ai-agent4j/README.md` | A feature entry (call and expose agents over A2A as a facilitator), the documentation hub link, and the Java guide |
| `src/ai-agent4j-tools/README.md` | That `NetPolicy` and `HttpSupport` are what a consumer's transport and link fetching should use to refuse private addresses |
| `src/loom/README.md`, `src/loom/ai-agent4j-loom/README.md` | The new declarations and `ask` in the primitives table, `weave card`, and the Loom guide link |
| `src/loom/vscode-loom/README.md` | The new keywords, the hover and completion for the new declarations, the graph kinds, the snippets list |
| `src/loom/ctk/README.md` | The new scenarios |
| `src/loom/loom-inspect/README.md`, `src/loom/loom-fabric/README.md` | The exchange events, the incidents and security signals, the escaped display |
| `src/loom/starters/README.md` | The new starter, if one is added |
| `src/eval4j/README.md`, `src/eval4j-report/README.md` | Scripted remote agents in trajectory and mock runs; the new graph kinds in the report |
| `src/spring/llm4j-spring-boot-starter/README.md` | That there is **no** A2A adapter (D3), and how to mount the handler from a Spring controller, linking the guide |
| `src/examples/README.md`, `src/examples/a2a-starter/README.md` | The starter and its limits |
| `docs/README.md`, `docs/guide/README.md`, `docs/tutorial/README.md`, `docs/deploy/README.md`, `docs/specs/README.md` | Indexes and the status row; the deployment page says that the consumer hosts an A2A endpoint and `weave deploy` does not |
| `src/ai-agent4j-addons/README.md`, `src/engram/engram-core/README.md`, `install/README.md`, `probes/README.md` | Reviewed; no change expected |

The same sub-phase also updates the other places that list the project: `wiki/Home.md`, `src/ai-agent4j/wiki/Home.md`, `DEVELOPER_GUIDE.md`, `docs/THE_STACK.md`, `docs/ABOUT.md`, `llms.txt`, `docs/EXAMPLES.md`, `docs/COMMAND_REFERENCE.md`, the release notes and `API_COMPATIBILITY.md`.

## 5. Part C: exposing a plain `ReActAgent` from Java (D22)

For someone who uses ai-agent4j without Loom. An `AgentTaskRunner` adapts agents to the handler's `TaskRunner`. The consumer supplies three things: a **function that builds the agent for each task** (given the caller's scope and the context id, so each caller can have its own memory, budget and masking; a consumer who does not care returns the same agent every time), a **`TaskStore`** that keeps tasks between requests, and an **`Executor`** to run them on (llm4j starts no threads). A task runs one `agent.run(...)`. The context id may map onto the agent's memory if the consumer configured one.

Limits of the first version, stated in the Javadoc and on the documentation page:
- **No `INPUT_REQUIRED`.** A plain agent has no `ask`, so tasks run to completion or fail.
- **A crash loses running tasks**, unless the store is durable and the consumer restarts unfinished tasks at startup. There is no journal.
- **Cancel** works only if the agent's run can be interrupted (to be confirmed in the survey, VERIFICATION P4).
- **Limits are what the consumer configured on each agent.** There is no audit for Java.

The runner **refuses to start** unless the consumer explicitly confirms that the agent has limits (a required argument, as `allowUnscannedMedia()` is for media). Every place that describes the limits (the Javadoc, the page and the refusal message) says: if you need questions to the caller, crash-safe tasks, cancel and the audit, **expose a Loom workflow instead**.

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
| D17 | The handler wraps a `LoomHost` and does not change it; the host supplies a caller identity, and a resolver says which host serves each scope (amended by D30). | **taken** (owner) |
| D18 | The keyword for a remote agent is `remote`. | **taken** (not objected to) |
| D19 | After a crash the runtime does not resend: it lists recent tasks since a short margin before the send, matches its message id in their history, and stops with a message when it cannot tell (R16). The specification does not require servers to de-duplicate or to accept a proposed context id (VERIFICATION P3), so neither is relied on. | **taken** (owner) |
| D19b | An incoming message binds the workflow's parameters as in R23: text only to the parameter named by `text_input` (always explicit), a JSON data part by name, unknown keys ignored, no files, a `max_input` cap (default 64 KB). | **taken** (owner) |
| D20 | A remote agent is the target of `delegate` (and `for each … delegate`) only; fan-out uses `parallel`; no token budget on a remote step; no mixed target lists; `retry` only after a definite failure; a stopped run asks the transport to cancel in-flight remote tasks, as a best effort (R15b). | **taken** (owner) |
| D21 | The audit rules LA24 to LA27 and the extension of LR02 (R27). No `pii:` setting on `remote`: the developer decides how to mask. Everything reachable from an exposed workflow is treated as exposed. | **taken** (owner) |
| D22 | A plain `ReActAgent` can be exposed from Java through an `AgentTaskRunner` with a per-task agent function, a consumer-supplied `TaskStore` and `Executor`, the stated limits, and an explicit confirmation that the agent has limits (§5). | **taken** (owner) |
| D23 | Authorization requests (`AUTH_REQUIRED`) reach the channel as their own message kind and fail closed unless handled; a reply never carries a credential; it counts as a question; an `audience: automatic` channel may handle it, with a high audit finding. llm4j never produces one in the first version. | **taken** (owner) |
| D24 | An inline file part is offered to the channel as `FileOffered` like a link, so a consumer can refuse it; the channel accepts by writing the stream. The documentation tells consumers to bound the response size in their transport. | **taken** (owner) |
| D25 | The transport reports a card's signature status; a failed one is always refused; `require_signed_card: true` on a `remote` refuses anything not verified. There is no verifier hook, and llm4j does no cryptography (R14b). | **taken** (owner) |
| D26 | The result of a remote task: `quote` is the joined text, `quote_data` lists the data parts, and with `expecting` the result is one object from the single data part or from a text part that parses as JSON (§4.1). | **taken** (owner) |
| D27 | The handler never blocks. A send that must wait until the task finishes or pauses (the specification's default) returns a `CompletionStage` that the host completes; llm4j holds no request open. | **taken** (owner) |
| D28 | A send returns a task or a plain message (`SendResult`), and the translation always asks not to wait (`returnImmediately`). A plain-message reply ends the step at once. | **taken** (owner) |
| D29 | A card that declares a required extension the transport does not support is refused before sending (R6b). | **taken** (owner) |
| D30 | The endpoint carries an optional tenant that is passed through to the transport; the caller identity is one scope key chosen by the host; the handler resolves a runner per scope, so a consumer can give each tenant its own host. A workflow and a host are tenant-agnostic and isolation is the consumer's duty (principle 10). A `_caller` variable and a memory audit finding are left out for now. | **taken** (owner) |
| D31 | The consumer's duties are documented on a page of their own, with excerpts from a starter project (`src/examples/a2a-starter`) that compiles, need not run, and marks with `TODO` where authentication, cryptography and similar plug in (R30). | **taken** (owner) |
| D32 | A **language surfaces completeness test**: for every statement and declaration type in the AST, a test asserts that each registry handles it or lists it as deliberately excluded with a reason (the statement walker, the graph kinds, the symbol kinds, the grammar keywords, a mention in `LOOM_GUIDE.md`, a compatibility-kit scenario). It would prevent this kind of drift for every future construct. **Deferred** (owner: a good idea, but not now; the maintainer is the gate, and reviews each sub-phase against the §4.6 checklist). It is not part of phase 32 and may be taken up later. | deferred |
| D33 | The Loom script in the compile-only starter `src/examples/a2a-starter` is **excluded from `weave check`** (named, with the reason, in the `RepositoryScriptsTest` exclusion list and in the starter's README); a parse-only test stays. This is a deliberate exception for one illustrative file and does not change the rule for any other script. | **taken** (owner) |

## 7. Sub-phases

Documentation and the ecosystem surfaces ship **with the code that needs them**, in the same sub-phase, and not afterwards: a sub-phase is not done while any surface in §4.6 that it touches is out of step (principle 11, and principle 7 of `CLAUDE.md`). The last sub-phase finishes what only makes sense at the end (the tutorial chapter, the starters, the release).

| Sub-phase | Delivers | Requirements |
|---|---|---|
| **32a** | The model, `A2ATransport`, `AgentChannel` and its messages, `ChannelContext`, `ExchangeGuard`, the received-file sink, `RemoteAgent`, the endpoint guard, `A2AJsonRpc10`; fake transport and channel; fixtures from the specification. With it: the Javadoc, the `API_COMPATIBILITY.md` entries, and the first pages of `docs/a2a/` (the README, `WHAT_IS_A2A.md`, `PHILOSOPHY.md`, the calling half of `JAVA_GUIDE.md`, the Java part of `REFERENCE.md`, the transport and channel sections of `INTEGRATING.md`), with `PRINCIPLES.md` becoming a pointer; `llms.txt`, the core README and the wiki page | R1 to R12 |
| **32b** | `A2AHandler`, `CallerIdentity`, `TaskRunners`, `TaskRunner`, the card builder, `AgentTaskRunner` with `TaskStore`. With it: the serving half of `JAVA_GUIDE.md`, the serving and isolation sections of `INTEGRATING.md`, `REFERENCE.md` and the Javadoc | R13, R14, §5 |
| **32c** | Loom, calling: the AST, parser, `weave check`, the runtime, `taskChanged`, the journal. With it, **every surface for the calling constructs**: the language core visitors, symbols and the editor (grammar, hover, completion, snippets, parity baseline), `weave test` scripting of remote agents and channels, the debugger, the graph, trace events and loom-inspect, cost and budget display, a compatibility-kit scenario, the `LOOM_GUIDE.md` and `LOOM_PROMPT.md` sections, `docs/a2a/LOOM.md` (calling) and the check part of `SECURITY_AND_REVIEW.md` | R15 to R21, R26 (calling), R29, R31 to R37 (calling constructs), R38 (calling scenarios) |
| **32d** | Loom, being called: `expose`, `LoomTaskRunner`, `weave card`, `weave check` for `expose`. With it, every surface for `expose` and `weave card`: symbols, graph entry marker, trace events for served tasks, a card scenario in the kit, `COMMAND_REFERENCE.md` and `weave man`, and `docs/a2a/LOOM.md` (expose and `weave card`) | R22 to R25, R26 (serving), R31 to R38 (serving constructs) |
| **32e** | The audit and review rules, the generated rule pages, the security documents, Fabric signals and incidents, and the audit and review parts of `docs/a2a/SECURITY_AND_REVIEW.md` | R27, R28, R35, R37 |
| **32f** | What belongs at the end: the final `docs/a2a/README.md` and a consistency pass over the whole set, the starter's README, every README in R42, the tutorial chapter and index with the regenerated page, the starter scripts, the compile-only consumer starter `src/examples/a2a-starter`, the skills, the remaining guide chapters, the READMEs, `ABOUT.md`, `THE_STACK.md`, the release notes, the VS Code release (version bump, `CHANGELOG.md`, README) and the mutation profile | R30, R39, R40 and a final pass over §4.6 |

Each sub-phase is committed and pushed on its own, with the full build of every module it touches green first, as in phase 31.
