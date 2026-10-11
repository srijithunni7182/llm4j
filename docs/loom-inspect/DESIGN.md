# loom-inspect: module design (draft 1)

Live observability for Loom workflows, shipped as an add-on jar. It watches a run while it happens: tokens and cost per agent, which agent and tool is active, where each decision sits on the autonomy ladder, and what has gone wrong. It is read-only, with three controls (pause, suspend, stop) and one write (rotate a secret).

The UI mockups are the design system at https://claude.ai/artifact/G8ah58W5edzPPPWBJfPGVW (pages: Console, Incidents, Secrets). This document is how to build what they show.

## 1. Decisions already made

| # | Decision |
|---|---|
| 1 | Read-only except pause, suspend, stop, and rotating an existing secret. |
| 2 | Thresholds (loop, hallucination, paused-too-long) live in the Loom script, not in the admin page. |
| 3 | Secrets: rotate only. No add, no delete, no allowed-host change. Works only with the store the running app already uses. |
| 4 | By default it is an add-on jar that runs inside the application's own JVM (embedded mode). |
| 5 | The Loom logo appears in the page header. |
| 6 | The executor exposes what loom-inspect needs through one read-only `ObservableRun` view. No package-private reach-through. |
| 7 | Activation is `weave ... --observe` with loom-inspect on the classpath. No code needed in the app. |
| 8 | The page has one tab per workflow, with a run picker inside each tab, plus one store-wide Secrets tab. |
| 9 | The admin password is a secret in the app's store (`LOOM_INSPECT_ADMIN_PASSWORD`), so it is set and rotated like any other key. |
| 10 | **Remote mode:** the workflow JVM runs no UI. It exposes a listener where you register a webhook; events are pushed to your service, which shows the same view. |
| 11 | Remote mode is outbound-only from the workflow process: pause, suspend and stop travel back in the webhook response. Secret values never leave the process. |

## 2. What the runtime gives us today (checked against the code)

| Need | What exists | File |
|---|---|---|
| Live events | `HarnessExecutor.addTraceListener(TraceListener)`. Types: `delegate_start/end/replayed`, `thought`, `action`, `observation`, `budget`, `approval`, `memory`, `guard`, `suspended`, `tool`, `checkpoint`, `rewind`, `decision`, `note`, `task_*`. Each carries `agent`, `step` (e.g. `main/s0/f1`), `text`, `data`, `at`. | `execution/TraceEvent.java` |
| Spend | `executor.spend()` returns a `SpendReport` with a line per settled LLM call (step, agent, model, `Charge`) and totals by agent and by step. | `execution/SpendReport.java` |
| Rate-limit pause | `addSuspensionListener(Consumer<RunSuspended>)` and a `suspended` trace event carrying the resume time. | `HarnessExecutor` |
| Static workflow graph | `GraphBuilder.build(WorkflowDef, file)`. Runs nothing. | `graph/GraphBuilder.java` |
| Autonomy | `Ledger` (append-only records) and `LevelStore` (level per decision and scope, freeze flags). | `autonomy/` |
| Secrets | `executor.getSecretStore()`; `SecretStore.names()`, `metadata(name)` (allowed hosts, `updatedAt`, never the value), `put(name, char[], metadata)`. Read-only stores throw `UnsupportedOperationException`. | `ai-agent4j/.../secret/` |
| Stop at a point | `setStopAt(step)` ends the run cleanly as `RunStopped`; resumable. | `HarnessExecutor` |

Constraints found while reading:

- Listeners must be added **before** `initialize()`; agents report only when someone listens.
- Listeners run on the worker thread, and parallel branches call them concurrently. A slow listener slows the run, and an exception is swallowed with a warning. loom-inspect's listener must only enqueue.
- `TraceEvent` has no run id, but the executor does: `getRunId()` is already public, and so are `getJournal()` and `getSecretStore()`. So loom-inspect can read the journal and run id from the executor without the app passing them.
- Still hidden: the `LevelStore` (`levelStore()` is package-private) and the ledger, which only `setAutonomy` receives; the parsed script's workflows and agents; the run's budget limits; and a read-only snapshot of workflow variables. These are what `ObservableRun` (C5) exposes.

## 3. Gaps, and the small core changes they need

These are changes in `ai-agent4j-loom`, kept as small as possible. Each has a fallback so loom-inspect can ship without it.

| # | Gap | Proposed change | Fallback without it |
|---|---|---|---|
| C1 | **No pause.** `setStopAt` exists but there is no "hold before the next model call". | A `RunControl` object on the executor: `pause()`, `resume()`, `requestStop()`, `requestSuspend()`. The executor checks it before each LLM call and between steps, the same place budgets are enforced. | v1 offers **Stop** (via `setStopAt` on the next step boundary) and **Suspend** only; Pause is hidden. |
| C2 | **No call-level events.** There is no trace event for an LLM call with tokens, latency and model, and `observation` carries no tool name. | Emit `llm_call` (agent, model, tokens in/out, ms, cost) and add the tool name and duration to `observation`. | Poll `spend()` every second for tokens and cost; derive "tool in flight" from an `action` with no `observation` yet, per agent. Latency is unavailable. |
| C3 | **No key-health signal.** We cannot say whether the provider accepted or rejected a secret. | Emit `secret_use` (name, outcome: ok, 401, 403, other; never the value). | The Secrets page shows used-by, host, updated and fingerprint, and leaves the Status column out. |
| C4 | **No `inspect` block in the language.** | Add `inspect <Agent> { ... }` to the parser, AST and `weave check` (see section 6). | Thresholds come from a Java `InspectConfig` with sensible defaults, and the page says "defaults". |
| C5 | **Nothing is exposed as a whole.** Some getters are public, some package-private, some missing. | One read-only `ObservableRun` interface returned by `executor.observable()`: `runId()`, `journal()`, `script()` (workflows, agents, tools, provider and secret references), `secretStore()`, `ledger()` and `levelStore()`, `budget()` (limits, `warn_at`, per-agent), `spend()`, `variables()` (immutable snapshot), `pending()` (questions and approvals waiting on a person), `clock()`. loom-inspect depends only on this interface, so internals can change without breaking it. | The app passes the pieces to `Inspect.attach`. |
| C6 | **No way to start loom-inspect from the CLI.** | An `ObserveProvider` interface in core, found with `ServiceLoader`, and a `--observe[=port]` option on `weave run`, `resume`, `tick` and `daemon` (section 5). Without loom-inspect on the classpath the option prints how to add it and exits. | Programmatic `Inspect.attach` only. |

Order of value: C5 and C6 first (they make loom-inspect reachable), then C2 and C4 (they make the console and incidents real), then C1, then C3.

## 4. Module layout

Two Maven modules under `src/loom/`, both listed in the root `pom.xml` next to `ai-agent4j-loom`:

- **`loom-inspect-core`**: everything that turns events into a view and has no dependency on the Loom runtime. The versioned wire format (`InspectEvent`, `Snapshot`), `RunState`, the detectors, the JSON view API, the static UI, and `WebhookReceiver`. This is what a remote service embeds, and it also has a `serve` main for running the receiver standalone.
- **`loom-inspect`**: the add-on jar for the workflow process. Depends on core and on `ai-agent4j-loom`. Holds the trace sink, the embedded HTTP server, the `--observe` CLI provider, secrets and control, and the webhook emitter (section 5b).

Embedded and remote mode share the same wire format and the same state code: in embedded mode the sink feeds `RunState` directly, in remote mode the same events cross a webhook first. So the two views cannot drift apart. The tree below shows both; packages marked (core) live in `loom-inspect-core`.

```
loom-inspect/
  pom.xml                      depends on ai-agent4j-loom (provided), jackson-databind
  src/main/java/io/github/llm4j/loom/inspect/
    Inspect.java               entry point: attach(executor, RunInfo, Options) -> Handle
    Options.java               port, bind address, auth, retention, redaction
    collect/
      EventSink.java           the TraceListener: copies the event into a bounded queue, nothing else
      Pump.java                one thread: drains the queue into the RunState
    state/
      RunState.java            per-run model: agents, steps, tools, spend, incidents, ring buffer of events
      AgentState.java          status, current step, current tool, tokens in/out, cost, calls
      RunRegistry.java         live runs plus the last N finished runs
    detect/
      Detector.java            interface: (RunState, Event) -> Optional<Incident>
      LoopDetector.java
      GroundingDetector.java
      RateLimitDetector.java
      BudgetDetector.java
    graph/
      GraphOverlay.java        static WorkflowGraph + live state keyed by step id
    autonomy/
      AutonomyView.java        reads Ledger and LevelStore; shapes ladders and evidence
    secrets/
      SecretsService.java      list metadata, fingerprint, rotate
    control/
      RunController.java       pause, resume, suspend, stop (see C1)
    wire/                      (core) InspectEvent, Snapshot, Envelope: versioned JSON, seq numbers
    receive/                   (core) WebhookReceiver: verify signature, dedup by (run, seq), feed Pump, reply with ack and commands
    emit/
      WebhookEmitter.java      per-registration queue, batching, retry, backoff, signing, snapshot on (re)connect
      Registrations.java       listener endpoints and the stored list (survives restarts)
      Signer.java              HMAC-SHA256 over timestamp + body, key from the secret store
      CommandExec.java         verifies and runs commands from a webhook reply, through RunController
    cli/
      InspectObserveProvider.java   the ObserveProvider; listed in META-INF/services
    http/
      InspectServer.java       JDK HttpServer, JSON and server-sent events
      Auth.java                login, sessions, CSRF, lockout
      AdminPassword.java       reads the password secret from the app's store
      Api.java                 route table (section 7)
  src/main/resources/inspect-ui/
    index.html, app.js, app.css   plain static files, no build step
    loom-logo.png              copied from src/loom/vscode-loom/media/loom-logo-320.png
```

Choices and why:

- **No web framework in the jar.** The JDK's `com.sun.net.httpserver` serves JSON and SSE, so loom-inspect adds only Jackson (already used). A later `loom-inspect-spring` can mount the same `Api` under an existing Spring app (GetViral uses Spring Boot with Spring Security) and reuse its login. That is v2.
- **Static UI, no build.** The mockups are plain HTML, CSS and a little JS. Shipping them as static resources keeps the jar self-contained and removes a Node toolchain from the build.
- **One writer thread.** `EventSink` only enqueues; `Pump` mutates `RunState`. HTTP handlers read immutable snapshots. This keeps the run's own threads fast and avoids locks on the hot path.

## 5. How it starts

### From the CLI (the main path)

```
weave run flow.loom --secrets keys.store --secrets-key-env MYAPP_MASTER_KEY --observe
weave daemon --observe=9090
```

`--observe` takes an optional port (default 8787) and binds `127.0.0.1`; `--observe-bind <address>` changes that (section 9 lists what is then required). Mechanics:

1. `WeaveCLI` finds an `ObserveProvider` with `ServiceLoader`. loom-inspect registers one in `META-INF/services`. If none is found, the CLI says "add loom-inspect to the classpath" and exits before running anything.
2. The provider is started once per process and owns the HTTP server.
3. Each time the CLI creates a `HarnessExecutor` (a `run`, a `resume`, or one fired by `tick` or `daemon`), it calls `provider.attach(executor.observable())` **before** `initialize()`, so agents report events.
4. The provider reads the run id, journal, script, secret store, autonomy stores and budgets from `ObservableRun`. Nothing is passed by hand.

The secret store used is the one `--secrets` gave the run, so the page and the run always agree about which keys exist.

### From Java

```java
HarnessExecutor executor = ...;
Inspect.Handle h = Inspect.attach(executor.observable(), Options.builder().port(8787).build());
executor.initialize();
executor.executeWorkflow("Main", inputs);
```

`attach` registers the trace listener and the suspension listener, and starts the server on the first call. One server serves many runs; each `attach` adds a run. If nothing attaches, the runtime pays nothing, because agents report only when a listener exists.

### Per-workflow tabs

The page is organised by workflow, matching how you think about the system:

- **One top-level tab per `workflow` in the loaded script(s)**, in script order, each with a badge showing its open incidents (red) or running state (amber). Inside a tab there is a run picker (live runs first, then recent finished ones) and the sections shown in the mockup: Live run, Agents, Autonomy, Spend, Tools, Journal, Replay.
- **One store-wide Secrets tab** at the end. Secrets belong to the application, not to a workflow, so this tab is the same wherever you open it.
- A workflow with no live run still has a tab; it shows its last run, or the static graph if it has never run.
- With `weave daemon`, a scheduled workflow gains a live run in its tab each time it fires. There is no cross-run overview page; the first tab is the landing page.

## 5b. Remote mode

For when the workflow runs somewhere you do not want a web server: a small always-on machine, a container with no inbound port, a production host. The workflow process runs only a thin **emitter**. Your own service receives the events and shows the same view.

```
 workflow JVM                                    your service                      browser
 ┌──────────────────────────┐   signed POST    ┌─────────────────────────┐
 │ weave daemon             │ ───────────────▶ │ loom-inspect-core       │ ◀────▶  same console
 │  --observe-remote=9099   │   batches of     │  WebhookReceiver        │         (workflow tabs,
 │  emitter (no UI, no      │   events         │  RunState + detectors   │          incidents, ladders)
 │  state model)            │ ◀─────────────── │  view API + UI          │
 └──────────────────────────┘   ack + commands └─────────────────────────┘
   outbound only; no inbound port needed for viewing
```

### Starting it

```
weave daemon --secrets keys.store --observe-remote=9099
weave run flow.loom --observe-webhook https://ops.example.com/loom/hook
```

- `--observe-remote[=port]` starts a **registration listener** (default 9099, loopback unless `--observe-bind`). It serves no UI. You register webhooks on it at any time, including while runs are live.
- `--observe-webhook <url>` registers one webhook at start-up, with no listener, for the simple case. Repeatable.
- `--observe` (embedded UI) and the remote options can be used together.

### Registering a webhook

| Method and path | Purpose |
|---|---|
| `POST /observe/webhooks` | Register: `{name, url, workflows: ["*"], signingSecret: "LOOM_INSPECT_WEBHOOK_SECRET", detail: "truncated"}` |
| `GET /observe/webhooks` | List registrations with delivery health (below) |
| `DELETE /observe/webhooks/{id}` | Remove one |
| `POST /observe/webhooks/{id}/test` | Send a test event now |
| `POST /observe/webhooks/{id}/resync` | Send a fresh snapshot |

`signingSecret` is the **name** of a secret in the app's store, never the value, which is consistent with how everything else here handles keys. The registration listener itself requires a bearer token held in the store as `LOOM_INSPECT_REGISTER_TOKEN`. Registrations are stored in a file beside the trigger store, so they survive a restart.

### What is sent

One wire format for everything, versioned (`v: 1`):

```json
{ "v": 1, "delivery": "d_8f21", "sent": "2026-10-11T14:08:52Z",
  "events": [
    { "run": "r-7f3a", "workflow": "ContentStudio", "seq": 12408, "at": "...",
      "type": "action", "agent": "Fact-Checker", "step": "main/s4/p1",
      "text": "Fetch healthline.com/…", "data": { "tool": "Fetch" } } ] }
```

- **Snapshot first.** On registration and after any gap, the emitter sends a `snapshot` event: run info, the static graph, agents, thresholds from the script's `inspect` blocks, budgets, spend so far, autonomy ladders, pending questions, and **secrets metadata** (name, hosts, updated, fingerprint, used-by; never values). The receiver can therefore draw a complete view at any moment, even if it joined mid-run.
- **Then deltas**: every trace event as in section 2, plus `llm_call` and `secret_use` when C2 and C3 exist, and a `heartbeat` every 5 seconds so a quiet run is told apart from a dead link.
- **Ordering and delivery.** Events carry a per-run `seq`. Delivery is at least once, in order per run; the receiver drops anything it has already seen and asks for a resync on a gap.
- **Batching and retry.** Up to 200 events or one second per POST. Failures retry with backoff from 1s to 60s. Each registration has its own bounded queue (default 10,000 events). When it overflows the oldest events are dropped, a `dropped` counter is kept, and a fresh snapshot follows when the link recovers.
- **The run never waits.** Emitting happens on its own thread behind the sink's queue. A slow, down or hostile webhook cannot slow or fail a workflow.

### What comes back

The receiver answers `200` with `{ "ack": { "r-7f3a": 12408 }, "commands": [ ... ] }`, signed with the same key.

- **`ack`** lets the emitter trim its queue.
- **`commands`** are `pause`, `resume`, `suspend` and `stop` for a named run. The emitter verifies the signature and timestamp, executes through `RunController` (C1), and reports the outcome as a `command_result` event. This keeps the workflow process **outbound-only**: no inbound port is needed for viewing or control. A command waits for the next response, so latency is the batch interval (about a second while events flow, up to a heartbeat when idle).
- A command is delivered at most once and expires after 60 seconds if the run is not reachable.

### What remote mode does not do (v1)

- **No secret rotation.** A new secret value would have to travel through your service to the process. Rotation stays in embedded mode and in `weave secrets`. The Secrets tab shows names, hosts, fingerprints and used-by, read-only, with a note saying where to rotate.
- **No full-detail trace by default.** Trace text leaves the machine, so each registration chooses a `detail` level: `full`, `truncated` (default, 500 characters, with the guard and redaction rules applied first) or `metadata` (no text, just types, agents, sizes and timings).

### How it looks

The receiver shows the same console. What changes is honesty about the link: a connection chip in the header says **Live** (with delivery lag), **Lagging** (catching up, how many events behind), **Resynced** (a gap was detected and repaired from a snapshot), or **Stale** (no heartbeat for 3 intervals). When stale, the whole view is dimmed and every "running" state is shown as "last seen running at 14:09:03", so it never presents old data as live. Pause, suspend and stop buttons show "queued" until the command result arrives.

### Delivery health (on the listener)

Per registration: state (delivering, retrying, failing), lag, last success, queued, dropped, consecutive failures. A registration that fails continuously for 24 hours (configurable) is disabled and audited, not retried forever.

## 6. Thresholds in the script (proposed syntax, needs C4)

```loom
inspect Editor {
    loop: same_action 4 times, no_progress 3 rounds on review.score
}
inspect Scriptwriter {
    ungrounded_figures: warn at 1, high at 2
    unsupported_claims: high at 25% from review.claims
}
inspect SEO {
    paused_longer_than: 60s
}
```

What each rule means and how it is measured:

| Rule | Measured from | Notes |
|---|---|---|
| `same_action N times` | Hash of (tool, input) from `action` events, per agent per step | Detects "Search 'hook patterns' x6". |
| `no_progress N rounds on <var>` | The named workflow variable at the end of each loop round | The variable is how the script says what "progress" is. Needs read access to the run context. |
| `ungrounded_figures` | Numbers in a `delegate_end` result that appear in no `observation` or input of that step | A heuristic. Says "no source found", never "false". |
| `unsupported_claims` | A reviewer's structured output (an `output_schema` list the script names) | Only meaningful when the script has a checker agent. |
| `paused_longer_than` | `suspended` event time against now | Informational: a rate limit is not an error. |

`weave check` validates an `inspect` block: the agent exists, the variable exists, durations parse. An `inspect` block changes no run behaviour; it only tells loom-inspect when to raise an incident. A script without one gets defaults from `Options`.

## 6a. Incident model

```
Incident { id, runId, agent, kind: PAUSED|LOOPING|SUSPECT_OUTPUT|FAILED|BUDGET,
           severity, since, evidence: [ {rule, measured, threshold, detail} ],
           actions: [pause, suspend, stop] }
```

Detectors are pure functions over the event stream and `RunState`, so they are tested by replaying a recorded event list. An incident opens when a rule fires and closes when its condition clears or the step ends. Hallucination incidents are labelled "suspect", carry the list of signals that fired, and are never auto-closed by the tool as "resolved".

## 7. HTTP API

All JSON, all under `/inspect`. Writes are `POST`/`PUT` only.

| Method and path | Returns |
|---|---|
| `GET /api/runs` | Live runs and the last N finished: id, workflow, status, started, tokens, cost |
| `GET /api/runs/{id}` | Header totals: tokens in/out, budget and `warn_at`, cost, projected cost, calls, needs-you count |
| `GET /api/runs/{id}/graph` | Static graph from `GraphBuilder` with a state per node (running, done, queued, paused, waiting, failed) |
| `GET /api/runs/{id}/agents` | Agent rows: status, current task, tokens, cost, tools in flight, rung |
| `GET /api/runs/{id}/agents/{name}/trace?after=` | Recent events for the "Following" panel |
| `GET /api/runs/{id}/spend` | Cumulative tokens over time, cost by agent, forecast |
| `GET /api/runs/{id}/tools` | Per tool: calls, in flight, error rate, p50 |
| `GET /api/autonomy` | Ladders: decision, agent, rung, evidence against thresholds, recent moves, items needing a person |
| `GET /api/runs/{id}/incidents` | Open and closed incidents with evidence |
| `GET /api/runs/{id}/events` | Server-sent events: state deltas, so the page updates without polling |
| `POST /api/runs/{id}/pause`, `/resume`, `/suspend`, `/stop` | Control. Admin role, CSRF token, audited |
| `GET /api/secrets` | Name, allowed hosts, updated, fingerprint, read-only flag, used-by |
| `PUT /api/secrets/{name}` | Rotate. Body `{value, confirm}`; response never contains the value |

In remote mode the same routes are served by the receiver, from `loom-inspect-core`'s `ViewApi`, which you mount in your own service (for example behind your existing login), plus one extra route, `POST <your hook url>`, handled by `WebhookReceiver`. The workflow side serves only the `/observe/webhooks` routes of section 5b. The secrets and control routes are served only in embedded mode, except that control routes in remote mode enqueue a command instead of acting directly.

Live updates use server-sent events rather than WebSockets: one-way, works through proxies, and the JDK server handles it.

## 8. Secrets: how rotation works

- **Listing.** `store.names()` plus `store.metadata(name)`. Entries from `env.NAME` are not in the store, so they are listed from the script's references and marked "environment, read-only".
- **Used by.** Computed statically from the parsed script: every `secret.NAME` in a `provider`, `tool` or `knowledge` block, mapped to the agents that use that provider or tool. No run needed.
- **Fingerprint.** First 4 bytes of SHA-256 of the value, shown as `3b1e…90`. It lets a person confirm which value is live after a rotation. Computing it resolves the value inside the JVM for an instant, the same as any tool does. API keys are high-entropy, so 32 bits reveals nothing practical.
- **Rotate.** `store.put(name, newValue, existing.withUpdatedAt(now))`. The existing allowed hosts are kept as they are, which is what makes the host read-only on the page. A store that throws `UnsupportedOperationException` (environment-only, read-only) makes the row read-only.
- **When it applies.** A provider key is fetched per request, so it applies at once. A tool option is read when the tool is created, so it applies from the next run. The drawer states this per secret.
- **Test and save.** Optional: one request to the allowed host with the new value before storing it. Needs a per-tool "ping" definition, so v1 may ship without it.
- **Never.** The value is never in a response, a log line, a trace, an exception message or the audit record. `char[]` is wiped after `put`. The request body is excluded from access logs.

## 9. Security

The pages can stop a run and replace API keys, so the module is off by default and closed by default:

- Disabled unless `Inspect.attach` is called; binds to `127.0.0.1` unless told otherwise.
- Refuses to start on a non-loopback address without TLS or without an authenticator. No unauthenticated mode on a network address.
- **The admin password is a secret in the app's store**, named `LOOM_INSPECT_ADMIN_PASSWORD` (changeable with `--observe-password-secret <name>`). Set it with the existing command: `weave secrets set LOOM_INSPECT_ADMIN_PASSWORD --secrets keys.store`. It is resolved from the store on each login, so rotating it takes effect at once, and it needs no new file or flag.
- **Behaviour by situation:**
  - *Password secret present:* a login page; a session after a correct password; control and rotation require it (re-entered for rotation).
  - *No password secret, loopback address:* read-only. The page works, and the pause, suspend, stop and rotate buttons are disabled with the line "set LOOM_INSPECT_ADMIN_PASSWORD to enable".
  - *No password secret, any other address:* refuses to start.
- Comparison is constant-time. Failed logins are rate-limited per address with a growing delay, and every attempt is audited without the attempted value.
- The store holds the password encrypted at rest, with the same trust as an API key. v1 compares the stored value directly. A salted hash is the better form for a human password, so loom-inspect also accepts a value written as `pbkdf2$<iterations>$<salt>$<hash>`, which a later `weave observe hash-password` helper can produce.
- The password can be rotated from the Secrets tab like any other secret, with extra care: it requires the current password, it is labelled "loom-inspect admin" with no used-by list, and saving it ends every other session.
- Roles: a single role, `admin`, in v1. A separate read-only password (`LOOM_INSPECT_VIEW_PASSWORD`) is a later addition.
- Rotation also requires a CSRF token and a same-origin check.
- Audit log of every control and rotation: who, when, what, fingerprint before and after, source address. Never values. Written through a pluggable `AuditSink`, with a file default.
- **Trace content is sensitive.** Events hold prompts and answers. Defaults: truncate text to 500 characters, apply the agent's existing `guard { pii: mask }` settings (events are already masked at source where configured), and let `Options.redact(...)` add patterns. The mockups show full trace lines; the shipped default shows the truncated form.
- **Remote mode adds these:**
  - Every delivery and every reply is signed (`HMAC-SHA256` over `timestamp.body`) with a key from the app's store; a timestamp outside a 5-minute window is rejected, which stops replay.
  - Webhook URLs must be `https` (plain `http` only for loopback). The emitter resolves the host and refuses private, link-local and metadata addresses unless `--observe-allow-private` is set, and does not follow redirects. An optional `--observe-webhook-hosts` allow-list restricts where events may go. This is what stops the registration listener being used to reach internal services.
  - Redaction and truncation are applied **in the workflow process, before an event leaves it**. A receiver never sees more than its registration's `detail` level allows.
  - Secret values are never put on the wire. Fingerprints and metadata only.
  - The registration listener needs its own token and binds loopback by default; exposing it on a network address requires TLS and the token.
- Run `weave audit`-style checks on the module: no `Authorization` header logging, no value in `toString`, a test that greps responses for a known secret.

## 10. Data and retention

- **v1: in memory.** `RunRegistry` keeps live runs plus the last N (default 20) finished runs, each with a bounded event ring buffer (default 5,000 events). Oldest events drop first; totals are kept as counters, so cost and tokens stay exact even after events age out.
- **After a restart** the history is gone. The run itself is unaffected, and a durable run (journal) can be resumed as usual.
- **v2: rebuild from the run journal** (`RunJournal`, file or JDBC) so a restarted server can show a finished run's graph and spend, and optional persistence of incidents.

## 11. Testing

- **Replay tests.** A recorded list of `TraceEvent`s drives `Pump` and the detectors; assert the resulting `RunState` and incidents. Fixtures cover: a normal run, a rate-limited agent, a loop, an ungrounded draft, a budget stop.
- **Concurrency test.** Many threads call the sink at once; assert no event is lost under the queue bound and the run thread never blocks (drop policy, with a counter shown in the UI).
- **API tests.** Start the server on an ephemeral port; check each route's JSON shape against golden files.
- **Secrets tests.** Rotate against `InMemorySecretStore` and `EncryptedFileSecretStore`; read-only store gives a read-only row; no response, log or audit line contains the value.
- **Security tests.** Unauthenticated, viewer and admin access to every route; CSRF and same-origin rejection; refusal to bind a public address without TLS.
- **Overhead check.** Run a recorded workflow with and without the sink; the added time should be a small fraction of one model call.
- **Delivery tests (remote).** A receiver that fails, lags or drops: assert ordering, de-duplication by `(run, seq)`, bounded queue with a drop counter, snapshot after a gap, backoff, and that the run thread never blocks. Signature tests: wrong key, stale timestamp, tampered body, forged command.
- **Parity test.** Feed the same recorded events through embedded mode and through emitter → receiver; assert the resulting `RunState` JSON is identical.
- **Live suite.** Under the existing `-Plive` profile, attach to a real run and assert the page shows tokens within a second.

## 12. Build plan

| Phase | Delivers | Needs |
|---|---|---|
| 0. Wire format | `loom-inspect-core` with `InspectEvent`, `Snapshot` and `RunState`, used by embedded mode too, so remote mode is not a rewrite | none |
| 0b. Spike | `ObservableRun` (C5), `ObserveProvider` and `--observe` (C6), `EventSink` + `RunState`, `GET /api/runs/{id}` and a bare page showing totals and active agents | C5, C6 (both small, additive) |
| 1. Console | Graph overlay, agents table, Following trace, spend chart, tools, autonomy ladders (read-only) | C2 helps; C5 or app-supplied stores |
| 2. Incidents | Rate-limit, loop, grounding and budget detectors with default thresholds; attention strip | none (heuristics) |
| 3. Script thresholds | `inspect { }` block in the parser and `weave check` | C4 |
| 4. Controls | Suspend and stop, then pause | C1 |
| 5. Secrets | List, fingerprint, rotate with audit | C3 for the Status column |
| 6. Hardening | TLS, a separate viewer password, password hashing, retention options | none |
| 7. Remote mode | `WebhookEmitter`, registration listener, `WebhookReceiver`, snapshot and resync, signed commands, the connection chip and stale view | C5, C6; commands need C1 |

Phase 0 needs only two small additive changes to the core (an interface and an option), and phases 1 and 2 need nothing more, so the first useful release does not wait on language work.

## 13. Open questions

Settled since draft 1: run identity (the executor already has `getRunId()` and `getJournal()`, and `ObservableRun` exposes them), the `--observe` activation, per-workflow tabs, and the admin password living in the store.

1. **`no_progress` needs a variable.** `ObservableRun.variables()` gives a snapshot, so loom-inspect can read the named variable at the end of each loop round. Is a read-only snapshot from outside the executor acceptable?
2. **Where does `inspect` live in the grammar?** A top-level block per agent (as above), or inside the `agent` block next to `budget`? The second keeps it near the agent and avoids a new top-level keyword. I lean toward the second.
3. **Pause semantics.** Should pause let in-flight model calls finish (recommended) or cancel them? Cancelling wastes tokens already spent.
4. **Fingerprint in the audit.** OK to record the truncated hash before and after in the audit log? It is the only way to prove which key was live when.
5. **Password form.** Accept the plain stored value in v1 and add hashing later, or require the hashed form from the start?
6. **`--observe` on one-shot runs.** `weave run` ends when the workflow ends, which closes the page. Should `--observe` keep the server up for a short time after (for example `--observe-linger 5m`) so the final state can be read?
7. **Who hosts the receiver?** Is your service the receiver (embedding `loom-inspect-core`, behind your own login), or do you also want a standalone `loom-inspect-core serve` for cases where there is no service of yours yet?
8. **Commands over the webhook response.** Pause, suspend and stop reach the process only on the next response (about a second while events flow). Is that acceptable for stop, or do you want an optional direct control port on the process for urgent use?
9. **Secret rotation in remote mode.** Left out of v1 because the value would transit your service. Would you want it later, with the value encrypted to a key held only by the process?
10. **Detail level per registration.** Default `truncated`. Is `metadata` (no text at all) worth offering for shared or less-trusted receivers?
