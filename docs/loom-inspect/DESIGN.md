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
| 10 | **Remote mode centres on Fabric** (`loom-fabric`), the observability server: a standalone, packaged server you install on any machine, in its own module. A workflow process is given its URL up front on the `weave` command line (`--observe-fabric <url>`). There is no listener on the workflow process and no dynamic webhook registration. |
| 11 | **Many processes, one server.** Any number of workflow processes report to the same server, and all their workflows are monitored together: one tab per workflow, each run tagged with the process it came from. |
| 12 | Processes only connect outward. Pause, suspend and stop arrive on an outbound long-poll the process holds open. Secret values never leave a process, and secret rotation stays in embedded mode. |
| 20 | **The wire format follows OpenTelemetry.** A run is a trace, steps are spans, live lifecycle events are log records, and totals are metrics, sent as OTLP/HTTP JSON using the GenAI semantic conventions where they exist and `loom.*` attributes for the rest. Only the parts OpenTelemetry has no concept of (hello, snapshot, commands) travel outside it. |
| 21 | Because the format is standard, a process can also export the same data to any OTLP collector (Grafana, Tempo, Datadog, Honeycomb) with `--observe-otlp <endpoint>`, independently of Fabric. |
| 23 | The server is named **Fabric** and is its own module (`loom-fabric`), separate from the `loom-inspect` add-on and the `loom-inspect-core` library. |
| 22 | **The emitter is fault tolerant through a local outbox.** Every event is written to a durable file on the workflow machine before it is sent. Fabric can be down for hours or days without affecting the workflow in any way, and when it returns every missed event is streamed in, in order. |
| 13 | The server keeps history on disk (retention 30 days or 2 GB, oldest finished runs first) and has its own secret store, which holds each client's key and the admin password. |
| 14 | One key per process. Every process is named on purpose: `--observe-name` is required with `--observe-fabric`. |
| 15 | No merging across processes: each process's workflows get their own tabs, grouped under the process name. |
| 16 | The process verifies the server's certificate. Trust comes from the JDK's default roots, plus an optional private CA given to that process for this connection only, or a pinned server key. There is no "insecure" switch. |
| 17 | The private CA is given to each process with `--observe-ca`. The server does not hand out its CA. |
| 18 | Pause, suspend and stop are delivered in the server's response to the next batch (about a second while events flow). There is no direct control port on a process. |
| 19 | One admin password for the server and for the embedded page. No viewer role and no per-process visibility in v1. |

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
| C2a | *(done in phase 0)* No run edges in the trace. | `run_start` and `run_end` trace events: workflow, run id, whether it resumed, and how the run ended (completed, suspended with reason and wake time, stopped, budget, failed with the error). Also a `sleep` event (`start` and `end`). | The mapper cannot tell a run began or ended. |
| C3 | **No key-health signal.** We cannot say whether the provider accepted or rejected a secret. | Emit `secret_use` (name, outcome: ok, 401, 403, other; never the value). | The Secrets page shows used-by, host, updated and fingerprint, and leaves the Status column out. |
| C4 | **No `inspect` block in the language.** | Add `inspect <Agent> { ... }` to the parser, AST and `weave check` (see section 6). | Thresholds come from a Java `InspectConfig` with sensible defaults, and the page says "defaults". |
| C5 | **Nothing is exposed as a whole.** Some getters are public, some package-private, some missing. | One read-only `ObservableRun` interface returned by `executor.observable()`: `runId()`, `journal()`, `script()` (workflows, agents, tools, provider and secret references), `secretStore()`, `ledger()` and `levelStore()`, `budget()` (limits, `warn_at`, per-agent), `spend()`, `variables()` (immutable snapshot), `pending()` (questions and approvals waiting on a person), `clock()`. loom-inspect depends only on this interface, so internals can change without breaking it. | The app passes the pieces to `Inspect.attach`. |
| C6 | **No way to start loom-inspect from the CLI.** | An `ObserveProvider` interface in core, found with `ServiceLoader`, and a `--observe[=port]` option on `weave run`, `resume`, `tick` and `daemon` (section 5). Without loom-inspect on the classpath the option prints how to add it and exits. | Programmatic `Inspect.attach` only. |

Order of value: C5 and C6 first (they make loom-inspect reachable), then C2 and C4 (they make the console and incidents real), then C1, then C3.

## 4. Module layout

Three Maven modules under `src/loom/`, all listed in the root `pom.xml` next to `ai-agent4j-loom`:

- **`loom-inspect-core`**: everything that turns events into a view and has no dependency on the Loom runtime. The OpenTelemetry-shaped model and OTLP/JSON codec (`otel/`), the Loom-specific messages (`wire/`), `RunState`, the detectors, the JSON view API, the static UI, and the `Receiver` that accepts batches.
- **`loom-inspect`**: the add-on jar for the workflow process. Depends on core and on `ai-agent4j-loom`. Holds the trace sink, the embedded HTTP server, the `--observe` CLI provider, secrets and control, and the `ServerLink` that sends events to a server (section 5b).
- **`loom-fabric`** (**Fabric**): the packaged, runnable server (section 5b), a **separate module** from the add-on. It depends on `loom-inspect-core` only, so it is small and needs no Loom runtime or model SDK on the machine it runs on, and it is versioned and released on its own.

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
    otel/                      (core) OTLP/JSON codec (no OpenTelemetry SDK), ids, attribute names, the TraceEvent mapper
    wire/                      (core) Hello, Snapshot, Heartbeat, Command: the few Loom-specific messages
    receive/                   (core) Receiver: OTLP endpoints, verify signature, dedup, feed Pump, serve the command long-poll
    emit/
      Outbox.java              the durable local log: append-only segment files, one writer thread, torn-tail recovery, size and age caps
      Cursor.java              one acknowledged position per destination (the server, an OTLP collector), stored beside the log
      Drainer.java             reads the log from a cursor and sends it; also drains the outboxes of earlier or dead instances
      ServerLink.java          one outbound connection to a server: hello, snapshot, OTLP batching, retry, backoff, heartbeat, command long-poll
      OtlpExport.java          the same data to any OTLP collector (--observe-otlp), plain bearer headers
      Signer.java              HMAC-SHA256 over timestamp + body, client key from the secret store
      CommandExec.java         verifies and runs commands from a server reply, through RunController
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

## 5b. Remote mode: Fabric

For when you want one place to watch everything: several workflow processes, on several machines, reporting to a single admin page that is not inside any of them.

```
  workflow process A ─┐
 (getviral-prod)     │     OTLP, signed            ┌──────────────────────────┐
                     ├───────────────────────────▶ │ Fabric (loom-fabric)     │ ◀────▶  browser: one console,
 workflow process B ─┤  ◀─── commands (long-poll) ── │  receiver · state        │         every workflow of every
 (billing-svc)       │                             │  detectors · history     │         process, one tab each
 workflow process C ─┘                             │  UI · own secret store   │
                                                   └──────────────────────────┘
   each process connects outward only; the server never connects to a process
```

### The server

A packaged runnable jar (`loom-fabric-<version>.jar`, plus a Docker image and a sample systemd unit). It runs on any machine with a JDK and needs no Loom runtime, no model keys and no workflow scripts.

```
fabric init  --data /var/lib/loom-inspect          # creates its store; asks for the admin password
fabric clients add getviral-prod                   # prints that client's key once
fabric serve --data /var/lib/loom-inspect --port 9090 \
                          --tls-cert cert.pem --tls-key key.pem
fabric clients list | revoke <name>
```

- **Its own secret store.** Created by `init`, using the same encrypted-store format as Loom. It holds the admin password (`LOOM_FABRIC_ADMIN_PASSWORD`) and each client's key (`client.<name>`). The master key comes from a file or an environment variable you name, as everywhere else in Loom.
- **TLS** is served directly with `--tls-cert/--tls-key`, or the server can sit behind a reverse proxy (`--trust-proxy`). Without TLS it binds loopback only and refuses a public address.
- **Persistence.** Unlike the embedded page, the server is long-lived, so it keeps history: per-run append-only event files with periodic snapshots (JSON lines, no database to install), an index, and retention by age and size (defaults 30 days and 2 GB, oldest finished runs first). On start it rebuilds live state from disk. Live runs stay in memory; finished runs are read from disk when opened. A JDBC store is a later option.
- **One instance in v1.** No clustering. The server is the only thing a restart affects, and processes resend a snapshot when it returns.

### Pointing a workflow process at it

The URL is given to `weave` up front, every time, on the command line or through an environment variable:

```
weave daemon --secrets keys.store \
      --observe-fabric https://fabric.example.com:9090 \
      --observe-name getviral-prod

LOOM_FABRIC_URL=https://fabric.example.com:9090 weave run flow.loom --secrets keys.store
```

- `--observe-fabric <url>` enables remote reporting; `LOOM_FABRIC_URL` is the same thing from the environment.
- `--observe-name <name>` is this process's name on the server. It is **required** with `--observe-fabric`; there is no default. It must match a client created with `clients add`, and a process that omits it is refused at start-up with a message saying so.
- The client key is read from the process's own secret store as `LOOM_FABRIC_CLIENT_KEY` (changeable with `--observe-key-secret <name>`), so no key appears on a command line or in the environment.
- `--observe` (embedded page) and `--observe-fabric` can be used together.
- **The server being down never affects the workflow.** See "The local outbox" below: events are written to disk first, sending is a separate background activity, and the run never waits for either. A wrong key or an unsupported version is reported once on the console and reporting stops; it never stops the workflow.

### What a process sends: OpenTelemetry-shaped

The data follows OpenTelemetry so that anything that understands OTLP can read it, and so that loom-inspect does not invent a second vocabulary for "a step that took 12 seconds and used 5,000 tokens". It is OTLP over HTTP with JSON encoding. loom-inspect writes and reads that JSON itself, with no OpenTelemetry SDK dependency, in keeping with the stack's no-vendor-SDK stance. (A bridge to the SDK is a later option.)

**Resource** (who is reporting), on every request: `service.name` = the `--observe-name`, `service.instance.id` = a per-start id, `service.version`, `host.name`, `process.pid`, `telemetry.sdk.name` = `loom-inspect`, `loom.version`.

**A run is a trace; a step is a span.** The trace id is derived from the run id (a stable hash), so a run keeps one trace across pauses, sleeps and restarts. Span ids are derived from the step id and rewind generation, so they are the same after a resume.

| Loom | OpenTelemetry |
|---|---|
| The run | Trace. Root span `loom.workflow <name>` with `loom.workflow`, `loom.run.id` |
| `delegate` to an agent | Span `invoke_agent <Agent>` with `gen_ai.operation.name=invoke_agent`, `gen_ai.agent.name` |
| A model call | Child span `chat <model>` with `gen_ai.request.model`, `gen_ai.usage.input_tokens`, `gen_ai.usage.output_tokens`, `gen_ai.response.finish_reasons`, plus `loom.cost.usd` |
| A tool call | Child span `execute_tool <name>` with `gen_ai.tool.name`; status `ERROR` and `error.type` when it fails |
| `run` (a task) | Span `loom.task <Name>` |
| `sleep`, `human_prompt`, approvals | Spans `loom.sleep` (with `loom.sleep.wake_at`), `loom.human_prompt`, `loom.approval` |
| `loop`, `for each`, `parallel`, `alt` | Spans `loom.loop` (with `loom.loop.round`), `loom.for_each`, `loom.parallel`, `loom.alt` |
| `checkpoint`, `rewind`, budget warnings, guard findings, decision proposals | Span events (and log records) with `loom.*` attributes, for example `loom.rewind.to`, `loom.rewind.generation` |
| A decision's rung | Attributes `loom.decision.name`, `loom.autonomy.level` (`watch`, `suggest`, `act`) on the decision span |
| Tokens, time, cost, activity | Metrics: `gen_ai.client.token.usage` and `gen_ai.client.operation.duration` (the standard ones), and `loom.run.cost` (USD counter), `loom.tool.calls` (by tool and status), `loom.runs.active`, `loom.incidents.open` |

**Live state needs more than finished spans.** OpenTelemetry exports a span when it ends, but a console must show a 30-minute run's step while it is running. So the process sends, for each step, a log record `loom.span.start` (with the trace and span ids and attributes) when the step begins, and the span itself when it ends. The receiver treats a start with no end as "running". A step that crosses a pause or a restart is closed by whoever finishes it: the start time is journaled, and span ids are deterministic, so the process that wakes a `sleep` can export the completed span with its original start time.

**Ordering and duplicates.** OTLP has no sequence numbers, so every lifecycle log record carries `loom.seq` (continuous per run), and every record written to the outbox also gets an outbox offset. Spans are idempotent (the same trace and span id replaces itself), so re-sending is safe. Delivery is at least once, in order; the server drops what it has already seen and acknowledges by outbox offset.

**Content follows the detail level.** Prompts, answers and tool arguments are attached only at `--observe-detail full` (as the GenAI conventions' opt-in content attributes); `truncated` (the default) cuts them to 500 characters after the guard and redaction rules; `metadata` sends none. Redaction happens in the workflow process, before anything leaves it.

**The GenAI conventions are still marked as in development.** The mapper pins the convention version it emits (through the OTLP `schema_url`) and keeps the attribute names in one class, so a change in the convention is a one-file change here.

**What is not OpenTelemetry.** A few things have no OTel concept and travel as small versioned JSON messages next to OTLP:

- **`hello`**, when the process connects: instance id, name, host, process id, versions, the workflows it knows (name, script hash), its wire version. The server answers with the versions it accepts; a newer or older process degrades gracefully (unknown fields ignored, an unsupported version shown on the Processes page).
- **`snapshot`**, for each live run on connect and after any gap: the static graph, agents, thresholds from the script's `inspect` blocks, budgets, spend so far, autonomy ladders, pending questions, and **secrets metadata** (name, hosts, updated, fingerprint, used-by; never values).
- **`heartbeat`** every 5 seconds, and **`bye`** on a clean exit.

**Endpoints.** The server accepts OTLP at the standard paths `/v1/traces`, `/v1/logs` and `/v1/metrics`, and the Loom-specific messages under `/loom-inspect/v1/` (`hello`, `snapshot`, `heartbeat`, `commands`). Every request carries the client's signature header `X-Loom-Inspect-Signature: t=<unix>,v1=<HMAC-SHA256(client key, timestamp.body)>`. OTLP exporters accept custom headers, so this needs nothing special on the wire; a standard collector simply ignores it.

### The local outbox: fault tolerance

The emitter never sends an event straight from memory. Every event is appended to a **durable local log on the workflow machine** first, and a separate background drainer sends the log to the server from the last acknowledged position. The Fabric can be down for any length of time, and the workflow neither slows nor fails.

**Not on the run's path.** The trace listener copies the event into a small in-memory ring and returns. One writer thread appends the ring to disk; another thread sends. If the writer falls behind or the disk is full, the ring drops the oldest entries and counts them; it never blocks the workflow, never throws into it, and never retries on the workflow thread. A failure inside the emitter is logged at most once a minute.

**The log.**
- Append-only segment files (default 8 MB each) of length-prefixed, checksummed records. Each record has a monotonically increasing **outbox offset**. The records are the same OTLP-shaped and Loom-specific messages that would have been sent, already redacted to the registration's detail level, so nothing sensitive is written that would not have been sent.
- **Location:** `--observe-outbox <dir>` (default: `observe-outbox/` beside the run's journal when `--journal` is given, otherwise `./loom-inspect-outbox/`). Under it, one directory per process name and instance (`<name>/<instance-id>/`). Directories are created `0700` and files `0600`.
- **Crash safety.** Records are flushed every 200 ms or 1,000 records, whichever first, and fsynced each second. A power loss can lose at most the last second of events; a process crash loses at most the last 200 ms. A record cut off mid-write is detected by its length and checksum and dropped when the log is opened again; the rest is intact.
- **Caps.** Default 256 MB and 7 days per process name. When the cap is reached the **oldest segments are dropped first**, the number lost is recorded as a gap, and a fresh snapshot follows the next delivery, so the console's current state is correct and only the old detail between is missing. The Processes page shows lost counts.

**Sending from the log.**
- The drainer keeps a **cursor** per destination (Fabric; an OTLP collector if `--observe-otlp` is on) in a small file beside the log, updated only after the destination acknowledges. Several destinations read the same log independently, and a segment is deleted when every cursor has passed it.
- The server persists a batch before it acknowledges, and acknowledges by outbox offset. If the process dies after sending but before recording the acknowledgement, the batch is simply sent again and the server drops the duplicates.
- **Backoff and reconnect.** Retries use backoff from 1 second to 60 seconds, with jitter. On every reconnect the process sends `hello` first, then the backlog.

**When the server comes back: the backlog streams in.**
1. `hello`, then a fresh `snapshot` of every live run (so the view is correct at once).
2. The drainer then replays the log from the cursor, oldest first, in batches of up to 500 records, marked as replay. The server answers `429` with `Retry-After` if it needs the pace lowered. Live events keep being appended to the same log, so replay and live events share one ordered stream and nothing is reordered.
3. The console shows the process as **Catching up** with the backlog size and falling, then **Live** when it has reached the head of the log. History inside the outage window is filled in, step by step, with its original timestamps, so a run's timeline and cost chart show what actually happened rather than a hole.

**A process that dies, or exits, with events unsent.**
- On a clean exit the process tries to flush for a few seconds (`--observe-flush-timeout`, default 5 seconds) and then exits, leaving the rest in the log. It never waits for a down server.
- Any later `weave` process started with the same `--observe-name` and outbox directory first drains the logs of earlier instances (found under `<name>/`; a directory whose lock is not held belongs to a finished or dead process) before its own. A run that paused for a long `sleep` and is resumed days later therefore delivers the events from before the pause as well.
- `weave observe flush --observe-fabric <url> --observe-name <name> --observe-outbox <dir>` drains an outbox by hand, and `weave observe purge` deletes one.
- A run whose process died without a `bye` is shown by the server as "lost contact at …, final state unknown" until the backlog arrives or the run is resumed.

**What it does not promise.** It is a durable buffer, not a database. At most the last second can be lost in a power failure, the cap can drop old events on a long outage, and a machine whose disk is lost loses its backlog. In each case the server's view stays honest: it shows the gap and the loss count rather than inventing events.

### What comes back

OTLP responses cannot carry commands, so commands use a separate outbound **long-poll**: the process asks `GET /loom-inspect/v1/commands?wait=25s` and the server answers the moment a command is queued (or with an empty reply after 25 seconds, and the process asks again). The process stays outbound-only, and a command now arrives in well under a second rather than waiting for the next batch.

- **`commands`** are `pause`, `resume`, `suspend` and `stop` for a named run on that process, signed with the client's key. The process verifies the signature and timestamp, executes through `RunController` (C1), and reports the outcome as a `loom.command.result` log record. A command expires after 60 seconds if the process cannot be reached.
- **Acknowledgement and clock.** The `heartbeat` reply carries the server's clock, so a process can warn when the two clocks differ by more than the signature window.

### Exporting to any collector

`--observe-otlp <endpoint>` (with `--observe-otlp-header "Authorization=Bearer …"`, the key taken from the secret store) sends the same traces, logs and metrics to a standard OTLP endpoint: an OpenTelemetry Collector, Grafana Tempo and Mimir, Honeycomb, Datadog's OTLP intake. It is independent of `--observe-fabric` and can be used with or without it. It sends only OTLP, with no hello, snapshot or commands, because a generic backend has nowhere to put them. This is the cheap hedge: even if loom-inspect is never opened, the data already exists in the tools a team uses for everything else.

### Workflows across processes

The console is the same as before; what changes is where the tabs come from.

- **Tabs are grouped by process.** Each process's workflows appear as tabs under its name, in the form `getviral-prod / ContentStudio`. There is **no merging**: the same workflow running in prod and staging is two tabs, one under each process, so a run, an incident or a cost is never ambiguous about where it happened. The run picker inside a tab lists only that process's runs of that workflow.
- With many processes the tab strip groups by process and collapses to the ones with live runs or open incidents first, with the rest under a "more" menu and a search box.
- **A Processes page** lists every client: name, host, version, workflows, live runs, state (online, lagging, offline since), events per second, **backlog on disk** and events lost, and the key's name. It is also where you see an unsupported version, a revoked key, or a process that stopped answering.
- **Process-level state reaches the runs.** If a process goes quiet, its live runs show "last seen running at …" and the connection chip says Stale, exactly as in the single-process remote view. Runs of a process that sent `bye` are marked finished or suspended according to the last event, not left as running.
- **Secrets** are shown per process, read-only: names, hosts, fingerprints, used-by. Rotation is not available through the server. The page says where to rotate (the process's host, or its embedded page).

### What the server does not do (v1)

- No secret rotation, as above.
- No high availability: one instance.
- It never connects out to a workflow process, and it never fetches a URL on a client's behalf.

### How it looks

The same console, with a connection chip in the header: **Live** (with lag), **Lagging** (events behind), **Resynced** (a gap was repaired from a snapshot) and **Stale** (no heartbeat for three intervals; the view is dimmed and every "running" becomes "last seen running at …"). Pause, suspend and stop show "queued" until the command result arrives (normally well under a second).

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

In Fabric the same read routes are served by `loom-fabric`, now spanning every connected process (each run is keyed by client and run id, and each route takes an optional `client=` filter). It adds `POST /ingest` for batches, `GET /api/processes` for the Processes page, and the `clients` administration, which is available only through the CLI. The workflow process serves no routes of its own in this mode. The secrets-rotation route is served only in embedded mode; control routes in server mode enqueue a command instead of acting directly.

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
- **Server mode adds these:**
  - Every batch and every reply is signed (`HMAC-SHA256` over `timestamp.body`) with that client's key. A timestamp outside a 5-minute window is rejected, which stops replay.
  - Each process has its **own key**, so one can be revoked (`clients revoke`) without touching the others, and a leaked key reaches only one client's reports and commands. Keys live in the server's and the process's secret stores, never on a command line.
  - A client may only report under its own name; the server rejects events that claim another client's identity.
  - The process connects to exactly the URL it was given: `https` required (plain `http` only for loopback), no redirects followed. How it decides to trust the server is in "TLS trust" below. There is no server-initiated fetch, so the earlier concern about using a registration listener to reach internal addresses does not arise.
  - Redaction and truncation are applied **in the workflow process, before an event leaves it**. The server never sees more than the client's detail level allows.
  - Secret values are never put on the wire. Fingerprints and metadata only.
  - The server's admin password is a secret in its own store; failed logins are rate-limited; the UI uses the same session, CSRF and same-origin rules as embedded mode.
  - Ingest is rate-limited per client. Over the limit the server answers `429` with `Retry-After`, and the process backs off.
- Run `weave audit`-style checks on the module: no `Authorization` header logging, no value in `toString`, a test that greps responses for a known secret.

## 9a. TLS trust: how a process verifies the server

The process, not the server, decides whether the server is genuine, because a fake server would receive trace data (and could try to send commands). Commands are signed with the client's key, so a fake server cannot forge them, but it could read events. So the process always verifies the certificate, with hostname checking, and never offers an insecure option.

What a process trusts, in order:

1. **The JDK's default trust store (`cacerts`).** If the server's certificate comes from a public CA (for example Let's Encrypt, or a cloud load balancer), nothing needs to be passed to the process. This is the common case when the server sits behind a reverse proxy or a managed certificate.
2. **A private CA, passed to the process: `--observe-ca <file>`** (or `LOOM_INSPECT_CA`). The file is the CA's **public** certificate in PEM. It is not a secret, so a path is fine, and it can be distributed by whatever already distributes your configuration. It is **added** to the default roots and applies to the loom-inspect connection only. It is not installed JVM-wide, so it does not weaken TLS to model providers or any other endpoint the workflow calls. `--observe-ca-only` makes it the only root for this connection, for the strictest setups.
3. **A pinned server key: `--observe-pin sha256:<fingerprint>`** (or `LOOM_INSPECT_PIN`), for a single self-signed server where you do not run a CA. The process accepts only a server whose public key (SPKI) hashes to that value, so no CA is involved. The fingerprint is printed by `Fabric` at start-up. A pin over the key, not the certificate, survives certificate renewal as long as the key is reused.

Rules for all three:

- **Hostname verification stays on.** The certificate must name the host in the URL (a SAN match).
- **No trust on first use.** A process never learns a certificate by seeing it once; a person supplies the CA file or the pin.
- **Trust is per process and configured with the `weave` command.** It is not read from the server.
- A process whose server fails verification reports it once on the console, stops reporting, and keeps running the workflow. The server shows that client as Offline.

On the server side, `fabric tls init --host fabric.example.com` can create a small private CA and a server certificate for it, writes `ca.pem` (the file you hand to processes) and prints the server's pin. Renewing the server certificate under the same CA needs no change on any process. Behind a reverse proxy, the proxy owns the certificate and the process trusts whatever the proxy's certificate chains to.

Not in v1: mutual TLS (client certificates). The per-client signing key already authenticates each process, and mTLS would add a second mechanism. It can be added later as an alternative to the key.

## 10. Data and retention

- **v1: in memory.** `RunRegistry` keeps live runs plus the last N (default 20) finished runs, each with a bounded event ring buffer (default 5,000 events). Oldest events drop first; totals are kept as counters, so cost and tokens stay exact even after events age out.
- **After a restart** the history is gone. The run itself is unaffected, and a durable run (journal) can be resumed as usual.
- **v2: rebuild from the run journal** (`RunJournal`, file or JDBC) so a restarted server can show a finished run's graph and spend, and optional persistence of incidents.

## 11. Testing

- **Replay tests.** A recorded list of `TraceEvent`s drives `Pump` and the detectors; assert the resulting `RunState` and incidents. Fixtures cover: a normal run, a rate-limited agent, a loop, an ungrounded draft, a budget stop.
- **Concurrency test.** Many threads call the sink at once; assert the run thread never blocks and that every event that is accepted reaches the outbox in order. When the ring is full, assert the oldest are dropped and counted.
- **API tests.** Start the server on an ephemeral port; check each route's JSON shape against golden files.
- **Secrets tests.** Rotate against `InMemorySecretStore` and `EncryptedFileSecretStore`; read-only store gives a read-only row; no response, log or audit line contains the value.
- **Security tests.** Unauthenticated, viewer and admin access to every route; CSRF and same-origin rejection; refusal to bind a public address without TLS.
- **Overhead check.** Run a recorded workflow with and without the sink; the added time should be a small fraction of one model call.
- **Delivery tests (server mode).** A scripted fake transport (no real network), so the tests are fast and deterministic: the server is down for N batches and then up, and every event is delivered once, in order, from the cursor; a batch sent but not acknowledged is re-sent and de-duplicated; a restart resumes from the stored cursor; an outbox directory with a torn last record is recovered; reaching the cap drops the oldest segment, records a gap and triggers a snapshot; an earlier instance's outbox is drained first; and with the transport blocked for a long time the run thread is never delayed. Signature tests: wrong key, stale timestamp, tampered body, forged command.
- **Parity test.** Feed the same recorded events through embedded mode and through `ServerLink` to the server; assert the resulting `RunState` JSON is identical.
- **Multi-process test.** Several simulated clients, including two running the same workflow and one that dies mid-run; assert tabs, run pickers, the Processes page and the stale and `bye` handling. Add a revoked key and a client that claims another's name.
- **Persistence test.** Stop the server mid-run, restart it, and assert live state is rebuilt from disk and processes resync; assert retention removes the oldest finished runs first.
- **Live suite.** Under the existing `-Plive` profile, attach to a real run and assert the page shows tokens within a second.

## 11a. The soak test

A real, long-running workflow is the acceptance test for every phase: `src/examples/inspect-soak/`. It runs about 30 minutes against real models and is built to put every state the console shows on screen at a predictable time: parallel fan-out, a tool that fails, a loop that stops converging, a draft with ungrounded figures, a rewind, a budget-window pause, a task step, and an optional human wait. It is observed from the server (and from the embedded page) while it runs. The minute-by-minute checklist of what each phase must show is in `src/examples/inspect-soak/CHECKLIST.md`.

## 11b. Phase 0 status

Built and tested (all on this branch):

- `loom-inspect-core`: the OpenTelemetry-shaped model (`SpanRec`, `LogRec`, `MetricRec`, `Resource`), deterministic `Ids`, the OTLP/JSON codec (`OtlpJson`, no SDK), the Loom-specific `Hello` and `Heartbeat`, and `RunState` and `RunRegistry`, which build the console's view of a run from records alone. 25 tests.
- `loom-inspect`: `TraceMapper` (Loom `TraceEvent` to records) and `Detail` (full, truncated, metadata). 18 tests, including one that drives a real executor through a sleep and a resume on a second executor and checks the console view.
- `ai-agent4j-loom`: `run_start`, `run_end` and `sleep` trace events (C2a), and icons for them in `weave run --trace`.

What the mapper cannot yet do, because the trace lacks it: split a step's tokens into input and output (the delegate reports a total), time individual model calls, or know a tool's real status (a result that starts with "error", "failed", or an HTTP 4xx or 5xx is treated as an error, which is a heuristic and is tested as one). Loop, `for each` and `parallel` blocks have no spans yet, so every step hangs directly under the run's root span. These need the call-level events of C2.

## 11c. Phase 0b status

Built and tested (embedded mode, end to end, on this branch):

- `ai-agent4j-loom` (additive): `ObservableRun` and `HarnessExecutor.observable()` (C5); `ObserveProvider`, `ObserveOptions` and the `Observe` loader (C6); `weave run --observe[=port]`, `--observe-bind`, `--observe-detail`, `--observe-name`, `--observe-linger`. The session is opened once per process and attached to the executor before `initialize()`; it is not saved in the run's spec, so it is given again on resume. Without the add-on on the class path the command says so and exits before running anything.
- `loom-inspect`: `EventSink` (bounded queue, drop-oldest, never blocks or throws into the run), `InspectSession` and `InspectObserveProvider` (found with `ServiceLoader`), and the page's server. The page is read-only: `GET /`, `/api/health`, `/api/runs`, `/api/runs/{traceId}` and its assets. It listens on loopback only (it refuses any other address until there is a login), rejects a request whose `Host` is not a loopback name (DNS rebinding), serves a strict Content-Security-Policy, and its script builds the page with `textContent` only, never HTML.
- The page: run picker, status, totals, agents with the tools they are using, tool counts, latest events, a banner when the run sleeps, pauses or waits. A run that sleeps shows as sleeping with its wake time, and keeps its timeline when it resumes, including a resume in the same process under `--wait`.
- Checked against the real CLI: `weave run … --journal … --wait --observe=8799` on a script that sleeps, with the page opened in a browser.

Not in 0b: a login, writes of any kind (pause, stop, rotate), the graph, spend chart, ladders and incidents, and Fabric. `weave daemon`, `tick` and `resume` do not take `--observe` yet.

## 12. Build plan

The server comes right after the console, so the console works across processes early. Every phase is checked against the soak test (section 11a).

| Phase | Delivers | Needs from the core |
|---|---|---|
| 0. Wire format | `loom-inspect-core` with the OpenTelemetry-shaped model and OTLP/JSON codec, the `TraceEvent` mapper, the Loom-specific messages and `RunState`, used by embedded mode too, so server mode is not a rewrite | none |
| 0b. Spike | `ObservableRun` (C5), `ObserveProvider` and `--observe` (C6), `EventSink` + `RunState`, `GET /api/runs/{id}` and a bare page showing totals and active agents | C5, C6 (both small, additive) |
| 1. Console | Graph overlay, agents table, Following trace, spend chart, tools, autonomy ladders (read-only), workflow tabs | C2 helps; C5 or app-supplied stores |
| 2. Server mode | `ServerLink` in the add-on; the Fabric module (`loom-fabric`) with `init`, `clients`, `serve`, `tls init`; the durable outbox with cursors, replay and backlog catch-up; signed OTLP ingest, `hello`, snapshots, resync and heartbeat; `--observe-otlp` export to any collector; the Processes page; tabs grouped by process; disk history with 30-day, 2 GB retention; `--observe-fabric`, `--observe-name`, `--observe-ca`, `--observe-pin`; the connection chip and stale view; the Docker image. Read-only: commands arrive in Phase 5. | C5, C6 |
| 3. Incidents | Rate-limit, loop, grounding and budget detectors with default thresholds; the attention strip | none (heuristics) |
| 4. Script thresholds | `inspect { }` block in the parser and `weave check` | C4 |
| 5. Controls | Suspend and stop, then pause; through the embedded page directly and through the server as commands on the long-poll | C1 |
| 6. Secrets | List, fingerprint, rotate with audit, embedded mode only | C3 for the Status column |
| 7. Hardening | Admin password hashing, login rate-limiting review, log and body redaction audit, packaging polish | none |

Phases 0 to 3 need only two small additive changes to the core (an interface and an option), so the first useful release, with a working server, does not wait on language work.

## 13. Open questions and deferred work

Settled since draft 1: run identity (the executor already has `getRunId()` and `getJournal()`, and `ObservableRun` exposes them), the `--observe` activation, per-workflow tabs, the admin password living in the store, remote mode as a standalone `Fabric` that many processes report to and that is configured from `weave` up front, one key per process, required process names, tabs per process and workflow with no merging, fixed retention (30 days or 2 GB), TLS trust through `--observe-ca` or a pin, commands carried on an outbound long-poll, an OpenTelemetry-shaped wire format with OTLP export, and a single admin password.

**Still open**

1. **`no_progress` needs a variable.** `ObservableRun.variables()` gives a snapshot, so loom-inspect can read the named variable at the end of each loop round. Is a read-only snapshot from outside the executor acceptable?
2. **Where does `inspect` live in the grammar?** A top-level block per agent (as in section 6), or inside the `agent` block next to `budget`? I lean toward inside the agent block.
3. **Pause semantics.** Should pause let in-flight model calls finish (recommended) or cancel them? Cancelling wastes tokens already spent.
4. **Fingerprint in the audit.** OK to record the truncated hash before and after a rotation in the audit log? It is the only way to prove which key was live when.
5. **Password form.** Accept the plain stored value in v1 and add hashing later, or require the hashed form from the start?
6. *(Settled)* `--observe` on one-shot runs: `--observe-linger <time>` keeps the page up after the run ends. The default is no linger.
7. **Outbox defaults.** 256 MB and 7 days per process name, fsync every second. Are these right for your machines, and should the default location be beside the run journal (as proposed) or a fixed system directory?

**Deferred past v1**

- A read-only viewer password, and per-process visibility.
- A direct control port on a process, for urgent stops.
- The server handing out its CA on first enrolment (needs a bootstrap trust step).
- Mutual TLS as an alternative to the per-process signing key.
- A database store for the server's history, and running more than one server instance.
- Secret rotation through the server (the value would have to be encrypted to a key held only by the process).

## 11d. Phase 1 status

Done, embedded mode, read-only:

- The page follows the Console design (tokens, dark and light, bundled fonts, header, tiles, tabs per workflow run).
- **Spend chart**: cumulative tokens against the run's clock, bounded to 600 points; a sleep is a flat stretch.
- **Workflow graph overlay**: `GraphView` gives every statement the step path the executor reports for it (`Main/s2/r.0`: loop rounds and rewind generations share one place, see `StepPaths`); `RunState` keeps each path's state and run count; the page lays the statements out left to right, with what a statement contains in a dashed box beneath it, and marks running, done and failed steps. Statements that leave no step of their own (a `note`) count as done once the block has moved past them. `GET /api/runs/{trace}/graph`. Checked against a real run with a loop and an `alt`: every reported step lands on a node. A called workflow's inner steps light its `call` node, not a graph of their own yet.
- **Autonomy ladders**: `AutonomyView` reads the run's level store and shows, per decision, the rung (watch, suggest, act, always in words), the ceiling, each scope, forced and frozen. Read-only: looking never creates a level. Evidence against thresholds is not shown yet.
- Trace lines read as what happened ("sleeping until 14:05", "woke up").

The graph and ladders are delivered by the embedded session. Fabric will carry the same two things in its snapshot (Phase 2).

## 11e. Phase 2 status

Done and tested. Read-only: commands arrive in Phase 5.

- **Outbox** (`emit/Outbox`, `Cursor`): append-only checksummed segments with offsets; a record cut off by a crash is dropped on reopen and the rest kept; caps of 256 MB and 7 days drop the oldest segments first and the loss shows as a count, never as invented events; trimmed only past what every destination has acknowledged; a directory lock tells a finished process's log from a live one's. Flushed every 200 ms and forced to disk every second (the stated bound on what a crash or a power cut can lose; the power-cut case is by construction, not by a test).
- **Sending** (`ServerLink`): `hello`, then a snapshot of each live run, then the backlog in ordered batches of up to 500, oldest first; cursors move only on acknowledgement, so a restart between sending and recording just sends a batch again and the server drops the duplicates; backoff 1 s to 60 s with jitter; `429` honoured; a wrong key is reported once and reporting stops (the workflow never does); a batch the server calls unreadable is skipped and counted so the rest can flow; the logs of earlier instances with the same name are drained first, oldest first, and removed. At exit it tries for `--observe-flush-timeout` and never waits for a server that is down.
- **Fabric** (`loom-fabric`, depends only on `loom-inspect-core`): signed ingest at the OTLP paths and `/loom-inspect/v1/*`; an unknown, revoked or wrong key all answer the same 401; a 5-minute replay window; a per-client rate limit; a client may only report as itself (403); every message is appended to the history and forced to disk before the 200 that acknowledges it; a restart rebuilds the console from the history. Admin password and client keys live in Fabric's own AES-GCM vault; sessions are cookies (HttpOnly, SameSite=Strict, Secure behind TLS); a growing delay follows failed logins per address; login accepts JSON only, which stops cross-site form posts. Without TLS it listens on loopback only; for another address it needs TLS or `--trust-proxy`. Retention is 30 days and 2 GB, oldest day first.
- **TLS trust** (`tls/Tls`): the process verifies the server and the host name, always. Default roots, plus a private CA for this connection only (`--observe-ca`, `--observe-ca-only`), or a pinned server key (`--observe-pin`). `fabric tls init` makes a small CA and a server certificate with `keytool` (a JDK is already required). Tested against a real TLS server, and through the real CLIs over https.
- **The page**: login, Processes (state, host, workflows, live runs, events per second, backlog, lost, last seen), one tab per process and workflow (never merged), a connection chip (Live, Catching up with the backlog size, Stale and dimmed with the last-seen time, Unsupported). An embedded page is unchanged.
- **weave**: `--observe-fabric`, `--observe-ca`, `--observe-ca-only`, `--observe-pin`, `--observe-outbox`, `--observe-key-secret`, `--observe-flush-timeout`, `--observe-otlp`, `--observe-otlp-header` on `run`, `resume`, `tick` and `daemon` through one mixin; `LOOM_FABRIC_URL`, `LOOM_FABRIC_CA` and `LOOM_FABRIC_PIN` from the environment; `weave observe flush` and `purge`.
- **OTLP export** to any collector, from the same outbox with its own cursor, independent of Fabric.
- **Resource use**: a process that only reports keeps no copy of its own; the sink of a run that has ended frees its thread (a daemon fires many runs); the registry keeps the live runs and the latest 50 finished ones.
- **Checked end to end**: a real executor, the real add-on and the real server over loopback, including a server that is down for the whole run and receives all of it, with no gap, when it comes up; and the real command lines (`fabric init`, `clients add`, `tls init`, `serve`; `weave secrets`, `weave run --observe-fabric`) against each other, over http and over https with a private CA. That run found a real bug (a relative `--out` for `tls init`), now fixed and tested.

**Deviations from the plan above**

- Fabric's vault is its own small AES-GCM file, not Loom's secret-store format, so that the server needs no Loom runtime. The trust model is the same (encrypted at rest, the master key kept elsewhere).
- The history is replayed in full at start; finished runs are not yet read lazily from disk. Memory is bounded by the registry's limits and the history by retention, but a very large history makes a slow start.
- `/v1/metrics` is accepted and not kept (the process sends no metrics yet).
- The command long-poll and the `commands` endpoint belong to Phase 5.
- The Docker image and the systemd unit are written and not yet built or run in CI.

**Not verified**

- The 30-minute soak (`src/examples/inspect-soak`) has not been run, because it needs a live model. Its checklist is the acceptance test for this phase.
- A network cut between a process and Fabric has been reasoned and unit-tested (cursors, backoff), not performed.

**Performed by hand with the real command lines**: a process started while Fabric was down and killed with `kill -9`; Fabric then started; a second process with the same name and outbox started. The second drained the dead instance's log (both runs appeared on the server, with the dead one's last known state, `sleeping`) and removed its directory.

**Not done yet**: a run whose process died without a `bye` still shows its last state; the design's "lost contact at …, final state unknown" per run is not drawn (the process row and the connection chip are right, the run is not marked). Small, and left for the incidents phase, which needs the same "silent" notion.

## 11f. Phase 3 status

Done and tested, embedded and Fabric (the detectors live in `RunState`, which both feed from the same records).

- **Signals computed in the process, before redaction** (`map/Signals`): a short hash of each tool call and of each answer, and for each answer how many of the figures it states appear in nothing the step was given or fetched (the task, tool inputs, tool results; what a failed call returned is not a source). Only hashes and counts travel, so every rule works at the `metadata` detail level; the examples of ungrounded figures travel only above it.
- **Rules** (`state/Detectors`, defaults in `Thresholds`): *paused* (a limit, not a sleep or a person: information, a warning once longer than 60 s); *looping* (the same tool call 4 times in a step with loop rounds counted together, or the same answer 3 times in a row from one step); *suspect output* (warn at 2 ungrounded figures, high at 4; says "no source found", never "false", and is labelled suspect); *failing tool* (3 failures in a row, ended by the next success); *budget* (a warning, or running out). Pure functions of the stream, so a recorded stream replays to the same incidents.
- **Lifecycle**: an incident opens when its rule fires, its severity only rises while it is open, and it closes when its condition clears (an answer changes, a tool answers, the run resumes) or when the run ends for good. A suspect-output incident is never closed as resolved by the console; it ends with the run. A run that is only suspended (a sleep) keeps its loop incidents.
- **The page**: an attention strip under the header, an Incidents panel with the evidence (rule, measured against threshold, detail), a count badge on the run's tab, a flag on the agent's row. Severity is always written in words.
- **Found by a real run**: the agent runtime blocks an exact repeat of a tool call (and counts it) without reporting an observation, which left the call "in flight" for the rest of the run. The mapper now closes an unanswered call as "no result" at the agent's next thought, and the detectors count it as neither failure nor success. The runtime's own `redundantActionCount` is the authoritative number; it is not on the trace yet.

**Not done**: `no_progress ... on <variable>` and `unsupported_claims ... from <field>` need the script's `inspect` block (Phase 4); thresholds are the defaults until then (`RunState.setThresholds` is the seam). Incident actions (pause, suspend, stop) are Phase 5. Rate-limit pauses from a *provider* (as opposed to a budget window) have not been seen with a real model, so the exact reason strings are untested beyond the budget-window case the soak stages.

## 11g. Phase 4 status

Done and tested. Thresholds are kept **beside** the workflow, not in it.

**What changed from the first version of this phase.** The first version put an `inspect { }` block inside the Loom agent. That put watching instructions into the language and made the script harder to read, for something that changes nothing about what the workflow does. It was reverted (the Loom grammar, parser, `weave check`, guide text, VS Code grammar and hover are as they were before Phase 4) and the rules moved one layer down, into `loom-inspect`, which owns the format and the checks.

- **The file**: `flow.inspect` next to `flow.loom` (found by name), or any file given with `--observe-rules <file>` (which wins). Blocks by agent name, `*` for every agent, `#` and `//` comments, one rule per line or several separated by `;`:

  ```
  Editor {
      same_action: 4 times
      same_output: 3 times
      tool_failures: 3 times
      no_progress: 3 rounds on review.score
  }
  Scriptwriter { ungrounded_figures: warn at 2, high at 4 }
  *            { paused_longer_than: 90s }
  ```

  A rule left out keeps the default. An agent's block overrides the `*` block rule by rule. Nothing in it changes how a run behaves, so it cannot affect an agent's earned autonomy. A script without a rules file is watched with the defaults, exactly as in Phase 3.
- **`weave observe rules <script> [--observe-rules file]`** checks the file against the script without running anything: an agent the script does not have (nearest name suggested), a `no_progress` variable that no step stores and no workflow takes, a number below 1, an unknown rule (nearest rule suggested), a `warn` level not below its `high` level, a `*` block with `no_progress`; a block that sets nothing is a warning. When a run starts, a file with errors is reported once and not used (the defaults apply); the workflow is never affected.
- **Delivery**: unchanged from the first version. The rules are read at attach time; the embedded page gets them directly and Fabric gets them in the run's snapshot, so a server needs neither the script nor the file. `Detectors` lays the `*` block over the defaults and an agent's own block over that. A pause belongs to the run, so the shortest `paused_longer_than` anyone set decides when it becomes a warning.
- **`no_progress`** (open question 1, reading a variable from outside the executor: acceptable, as a copy at one moment): at the first event of a new loop round the previous round has finished and stored its result, so the variable is read then, on the run's own thread, from the copy `ObservableRun.variables()` hands out, and only a **hash** is recorded and sent. N equal hashes in a row at round boundaries is no progress. Reading on the run's thread matters: reading on the queue's thread made a fast run look as if every round ended with its last value, and a real-loop test caught it.
- **Found while doing the move**: the sidecar needs the script's path at attach time, so `Session.attach(run, scriptFile)` was added next to `attach(run)`; `weave run`, `resume`, `tick` and `daemon` pass it.
- **Not done**: `unsupported_claims ... from <field>` (a reviewer's structured count against a share) needs the same variable reading plus a notion of "claims"; left out rather than guessed. The first round's start is not a boundary, so the earliest a `no_progress: N rounds` fires is the start of round N+1. A central rules file kept on a Fabric server (so an operator can change thresholds without touching the process's machine) is a natural next step and is not built.

## 11h. Phase 5 status

Done and tested: pause, resume, suspend and stop, embedded and through Fabric.

- **Executor** (`RunControl`): a request is checked at the start of each step. Pause holds before the next step and keeps the thread (a held run is not a suspended one: nothing is written to the journal); resume continues. Suspend ends with `RunSuspended` (reason `OPERATOR`, no wake time), which a journaled run resumes with `weave resume`. Stop ends with `RunStopped`. A stop or suspend stays pending until the run ends, and is dropped at the start of the next top-level run so it cannot hit a later one. Each change is a trace event (`control`, states `requested`, `held`, `resumed`, `cancelled`, `applied`), so it appears in the run, in Fabric, and in an OTLP backend as the log `loom.control`.
- **Limits, stated plainly**: a request takes effect **between steps**. A model call or tool call already under way finishes first; a long single step delays it. Pause keeps a thread; suspend is the way to free it, and it needs a journal (a run without one is not offered Suspend).
- **Who may ask**: only a signed-in administrator. Sign-in is a JSON POST with the password, answered with an HttpOnly, SameSite=Strict cookie; every request also carries an `X-CSRF-Token` and must come from the page's own origin; wrong passwords make that address wait longer each time; comparison is constant time. Embedded, the password is the secret `LOOM_INSPECT_ADMIN_PASSWORD` in the app's secret store, read each time (so rotating it takes effect at once); with none set, no control is offered and the page says what to set. Fabric uses its own admin password through the same code.
- **Audit**: one JSON line per request (who, run, action, outcome) and, on Fabric, per command result. Never a password, token or secret value.
- **Through Fabric**: the page's request becomes a `Command` (id, run, action, who, expiry 60 s) in that process's queue. The process polls `POST /loom-inspect/v1/commands` (long-poll up to 25 s, signed like every request); the server's answer is signed with the process's key and carries the process name, and the process refuses an answer that is unsigned, for another name, expired or already seen. The run decides whether the command still fits and the process posts the result back. The server offers controls only for a process that is online, and the page learns the outcome from the run's own `loom.control` events.
- **Not done**: interrupting a step in progress; per-user accounts and roles (one admin password for now); a queued-but-uncollected indicator on the page (the response text says it was sent).

## 11i. Phase 6 status

Done and tested (embedded only, by design).

- **List**: `GET /api/secrets` for a signed-in administrator: name, whether it is in the store, allowed hosts, last update, fingerprint (first 4 bytes of SHA-256 shown as `3b1e…90`), and used-by. Used-by is read from the script alone: `secret.NAME` in a `provider` or `tool`, mapped to the agents whose `model:` names the provider or whose `tools:` lists the tool. A name the script uses but the store lacks is listed as "not in the store". The store is the one the run uses (`ObservableRun.secretStore()`), so the page and the run agree.
- **Rotate**: `PUT /api/secrets/{name}` with `{value, password}`. It needs the session, the CSRF token, a same-origin request, JSON, a body of at most 16 KB, and **the admin password again** (a stolen session cookie alone cannot replace a key). The re-entered password counts against the same lockout as sign-in. Only an existing secret is replaced (`put` with its metadata, so allowed hosts are kept); adding, removing and changing hosts are not offered. A read-only store answers 409.
- **Never the value**: not in a response, an audit line, or an error message (a store failure is reported as "could not save it", without the exception text). The audit line has the name, who, address and outcome (`rotated`, `refused: password not confirmed`, ...). The request's value array is wiped after the call.
- **Fabric**: no route and no tab. Values never leave a process, and a server does not hold the process's store.
- **Not done**: "test and save" against the allowed host before storing (needs a per-tool ping definition); the Status column (no signal of whether a provider accepted a key); secrets of a store that only holds names from the environment are listed but cannot be replaced.
- **Not exercised in a browser here**: the Secrets tab and dialog are plain DOM code checked for syntax and served by the tested routes; look at them once in a real browser.

## 11j. Phase 7 status

Done and tested.

- **Admin password hashing**: the stored password may be a salted PBKDF2-HMAC-SHA256 hash (`pbkdf2-sha256$210000$<salt>$<hash>`) as well as the password itself, so an existing setup keeps working. `weave observe hash-password` prints one (password from `LOOM_INSPECT_NEW_PASSWORD`, another variable via `--password-env`, or typed). Fabric's `init` and `admin set-password` now store a hash. A hash with a bad shape or an unreasonable iteration count never matches; the hash itself is not accepted as a password.
- **Sign-in throttle review**: found and fixed (a) per-address failure counts lived forever and the map was unbounded: a count is forgotten after 15 minutes without another wrong guess and at most 10,000 addresses are tracked; (b) a guess spread over many addresses was never slowed (and behind a proxy every client is one address): 50 wrong guesses anywhere in a minute make every sign-in wait out that minute; (c) sessions were never pruned: expired ones are dropped at each sign-in, at most 100 are kept (the oldest ends first). Rotating a secret re-checks the password through the same throttle. The cost of (b) is that an attacker can delay the administrator for up to a minute; a guess that cannot be slowed is worse.
- **Redaction audit**: a real run at `metadata` detail through a real Fabric, with planted prompt, thought, tool argument, tool result and answer, then every file under both data directories (outbox segments, history, audit), the page's JSON, the graph and the console searched for them, for the process's client key and for the admin password: none found (the vault itself is excluded, being the place keys belong). The code was also searched for any message that prints a key, password or secret: the only ones are the one-time key display of `fabric clients add` and file names.
- **Packaging**: `Implementation-Version` and title in the manifests of `loom-inspect` and `loom-inspect-core` (the process reports its add-on version in `hello`; it was "dev"), a `loom-inspect` README, and the guide and Fabric README cover the new commands.
- **Left for later** (stated in earlier sections): per-user accounts and roles; a central rules file on a server; interrupting a step in progress; secrets "test and save" and a key-health column; a browser pass over the Secrets and controls UI (served and tested at the route level; the DOM code is syntax-checked only).

## 11k. Simulator (`fabric-sim`)

A way to exercise Fabric and the page without a model: `Simulate` (in the `loom-inspect` jar) runs the real executor and the real add-on over `sim.loom`, with scripted models and fake tools, and `src/examples/fabric-sim/run.sh` starts a Fabric server and N of them. A unit test runs a fast simulated run against a real server.

**Found by using it** (and fixed):
- A running Fabric server did not see `fabric clients add` or `revoke` (the vault was read once at start), so a revoked key kept working until a restart. The vault now notices a change to its file, at most once a second.
- `broadcast` and `handoff` targets emitted no step start or end, so their agents and tokens were missing from the page (their tool calls showed). They now start and end like a delegate. This also affects `soak.loom`'s phase A.
- A run stopped while held by the operator still showed *held*; an ended run now shows nothing held or pending.
- Looked at in a real browser for the first time (sign-in, tabs, incidents, controls, confirm dialog): no script errors, and the layout is as designed.

## 11l. Cost and the price table

Cost on the page is the cost the Loom runtime puts on each step; loom-inspect and Fabric hold no prices. The runtime computes it from a `PriceTable` (`model = input / output` per million tokens; none ship with Loom because prices go stale). A model with no price has no cost, which the page currently shows as $0.000 (unpriced, not free).

What was missing: `--prices` existed only on `weave run`, `eval` and `replay`, so a `daemon`, `tick` or `resume` (the usual way a workflow is watched through Fabric) reported no cost. The file is now found without a flag: `--prices`, then `<script>.prices` or `prices.properties` beside the script, then `LOOM_PRICES`. The simulator reads its own `sim.prices`, and its test checks a priced run reports a positive cost end to end.

## 11m. Central configuration in Fabric

An admin edits the **inspect rules** and the **price table** on a Configuration page; they live in `<data>/config/` (`rules.inspect`, `prices.properties`) in the same formats a process keeps beside its script.

- **Rules** are laid over each run's own rules rule by rule (`RunState` keeps the process's rules and the server's apart and merges them), and applied to existing runs at once. The `RulesFile` parser moved from `loom-inspect` to `loom-inspect-core` (with a copy of Loom's name suggester) so the server can check a file without the Loom runtime; `RulesCheck`, which needs a script, stays in the add-on. A `no_progress` variable is still read in the process.
- **Prices** (`PriceBook`, same lines as Loom's file): a step that reports tokens and no cost is costed at the server. That needed three things from the process: the prompt/completion token split on the agent step (`gen_ai.usage.input_tokens` and `output_tokens`), and, in the snapshot, the model each agent uses. A process that reports its own cost keeps it. Costs are fixed when a step is ingested, so a price change affects later steps only.
- **API**: `GET /api/config`, `PUT /api/config/{rules|prices}` (session, CSRF, same origin, 256 KB). A save is validated and written whole or not at all (422 with line-numbered problems); every change is audited (document and length, not the text).
- **Not built**: rules or prices per client; recomputing the cost of runs already counted; editing the retention settings from the page.

### 11m (addendum). The editors are structured

The Configuration page edits by choosing, not by typing syntax. `GET /api/config` returns each section's `structured` form (rules by agent and behaviour, prices as model / input / output) and a `catalog` of the agents and models the runs known so far have shown (agents from the runs and from each snapshot's agent-to-model map; models with the agents that use them). `PUT` accepts `{structured}`; the server writes the same text a person would (`RulesFile.write`, `PriceBook.write`), then validates and keeps it exactly as a text save. The text editor remains, under "Edit as text"; saving the structured editor rewrites the file, so comments in a hand-written file are not kept. Defaults are shown beside each rule so the effect of a threshold is visible.

## 11n. Call telemetry, context and incidents (specs phase 4)

Specified in [`docs/specs/04-inspect-and-incidents`](../specs/04-inspect-and-incidents/DESIGN.md). Status by sub-phase:

- **4a (done).** The mapper uses the runtime's own `tool_call` event for a tool span's status, `error.type` (the category), `loom.tool.outcome`, `loom.tool.soft_failure`, result size and durations, and keeps the text heuristic only for traces without it. Because the event follows the observation that closes the span, a closed tool span is **held** for its `tool_call` once the trace has shown a `model_call` or `context` event (older traces still emit at the observation, as before); the agent's next event, its step end, and the run end flush a span whose `tool_call` never came. Every `model_call` is a `chat <model>` span (GenAI attributes plus `loom.model.*`: attempts, waited, failovers, truncated), `context` and `compaction` are log records with typed numbers, and `run_end` totals are attributes of the run record and root span. New names are in `Semconv`. None of them carries text.
- **A finding that changed the link.** The new records alternate with the old (a chat span, a context log, a tool span...), and `ServerLink` sent one request per change of record kind, so a modest run needed hundreds of requests and tripped the server's rate limit (the simulator test never finished). It now sends one request per kind in a batch (logs, then spans, each in order) and moves the cursor once the whole batch is acknowledged; a failure part-way resends it and the server drops the repeats.
- **4b (done).** The run view gains `agents[].context` (latest use, window, fraction, source, compactions, last compaction, a 60-sample series), a top-level `models` list (calls, failures by category, tokens, retries, fail-overs, truncations, waited, p50 and p95), and per-tool `successes`, `successRate`, `softFailures`, `failures` by category, `p50Ms`, `p95Ms` (the old `calls`, `errors`, `inFlight`, `avgMillis` are unchanged). The list summary has `contextFraction`.
- **4c (done).** Five incident kinds join the six: `CONTEXT` (an agent's context nearly full: warn at 85 %, high at 95 %), `TRUNCATED` (an answer cut off by the token limit), `THRASH` (4 compactions in 10 model calls, or 3 in a row that freed little), `TOOL_RATE` (a tool's success rate over its last 10 calls below 70 %, high below 40 %), `SPIKE` (a call more than 5 times slower than the running median, over 2 s). Each has evidence and its thresholds are rules in the `.inspect` file (`context_nearly_full: warn at 85%, high at 95%`, `truncated_answers: 1 times`, `compaction_thrash: 4 in 10 calls`, `tool_success_rate: below 70% over 10 calls`, `latency_spike: 5x p50`), per agent or `*`, and centrally in Fabric. **For the tools view:** every tool has a health (`unused`, `healthy`, `degraded`, `failing`) with a reason, its recent and overall success rate, consecutive failures, last failure and last success, failures by category, a per-agent breakdown and a latency histogram; `RunRegistry.toolRollup()` merges them across every run (or one client's), failing tools first. A tool is `failing` when it has run 3 times and never worked, or its last 3 calls failed, or fewer than 40 % of its last 10 succeeded.
- **4f (done).** A task or delegate that fails is observable. Loom tells it in a `failure` event (kind, name, step, category, error type, attempts, handled) on every path, and a failed `task_end` and a failed `run_end` carry the category and, for the run, the name of what broke it. The mapper makes a `loom.failure` log (names and numbers always, error text only as far as the detail level allows), puts `loom.task.attempts` and the category on the task span, and `loom.run.failed_*` on the run record and root span. `RunState` keeps a health account per task (the same shape and verdicts as a tool's; `tasks` in the view; `kind` = `task` in `toolSummaries()` and the rollup, so a task and a tool with one name stay apart), and two incidents: `DIVERTED` (warn: a step failed and `on_failure` took over; closes with the run) and `BROKEN` (high: the run ended failed because of a named task or delegate; stays open after the run ends so the page still says why).
- **4g (done).** Runtime security signals, observe only (design §4c). One public `ToolReach` answers "what does this tool reach" for both `SecurityAudit` and the trace, so `tool_call` carries `reach` and `reach_known`. The mapper scans the results of untrusted tools in the process for injected instructions (five fixed groups, 20,000 characters, bounded patterns) and records only group names and a count. `RunState` keeps per agent the legs used and, from the static audit's profile (carried in the snapshot), the legs the script allows, and raises `TRIFECTA` (high; info when the rules accept it with a written reason; a warning that closes with the run when a leg rests only on assumed tools), `INJECTION` (warn; high after an outward or never-called next call, and always high for an accepted agent) and `ACCESS` (3 refusals in 60 s; an unknown tool is info). The rules `access_denied`, `injection_scan` and `trifecta: accepted "reason"` (reason required, quote-aware reader) layer like the others, in the file, on the wire and in Fabric's central form. Loom's `guard` trace events carry an `event` name so guard counts need no text. `RunRegistry.securityRollup` adds the counts across runs. See the wiki page [Security signals](../../wiki/Observability-Security-Signals.md) and the example `src/examples/inspect-security`.
- **4d, 4e (done).** The exporter and the Fabric server needed no change; they are covered by round-trip and end-to-end tests. `ServerLink` sends one request per kind in a batch with the run-end record last. Runnable example: `src/examples/inspect-tools`.

## 11o. The page: context, tools and tasks, security, thresholds (specs phase 5)

Specified in [`docs/specs/05-fabric-ui`](../specs/05-fabric-ui/DESIGN.md). Status by sub-phase:

- **5a (done).** The data the page needs. A run's `agents[].context` gains `points` (the last 120 model calls: `n`, `used`, `fraction`) and `events` (the last 60 compactions: `n`, strategy, outcome, before, after); `series` is unchanged. `WindowBook` (`model = tokens`, parsed and written like the price table) is the context-window catalog: `RunRegistry.setWindows` hands it to every run, and `RunState` gives an agent whose process reported no window one from the catalog by its model (`windowSource` is `process` or `catalog`; the `CONTEXT` incident uses the filled fraction, also for a run already open when the catalog changes). Fabric's configuration gains a `windows` section (`windows.properties`, text and structured, checked, audited by the generic config route). `GET /api/tools` and `GET /api/security` (embedded and Fabric) answer from `RunRegistry.toolRows` and `RunRegistry.securityOverview`; run summaries carry `security`. `Scenarios` holds thirteen scripted situations (real executor, scripted models, fake tools) used as the page's fixtures and runnable with `Simulate --scenario` or `SCENARIO=... fabric-sim.sh`. Two findings while building it: a short run exited before its last events reached the server (the simulator now waits for delivery, `InspectSession.awaitDelivered`), and the scenario path did not give the executor the secret store that holds the server key.
- **5b (done).** The run page. A cause banner (`BROKEN`, the quieter `DIVERTED` line), Context and Tasks cards, a Context panel (per-agent gauge with threshold marks, hatched bar and hint when no window is known, a compaction timeline with keyboard-reachable markers and a table equivalent), a Tools & tasks table (kind shown, worst health first, expandable to failures by category, calls per agent, recent outcomes and a latency histogram) and a Security panel (four cards, per-agent trifecta matrix with used / can / assumed marks, findings with evidence, guards, the fixed "cannot tell you" statement). The incidents panel knows the five new kinds. Still no `innerHTML`; text is set as text. Checked by 13 Playwright tests (`src/test/ui/run.page.test.js`) against `sim.Fixtures`, a small server that replays the scenarios.
- **5c (done).** The fleet pages. Two tabs beside Processes (shown on Fabric, and in the embedded page once there are two runs): Tools & tasks (counts by health, filters for health, kind and process, the table over every run with a last-ten strip, a row that opens to failures by category, callers, latency and a link to the run it last failed in; an open row keeps its place) and Security (the four posture cards, the agents that are exercised, accepted or armed with the reason, the open findings with links to their runs, and the fixed statement). Badges: failing tools (`/api/runs` now carries `fleet.toolsFailing`) and open security incidents (summed from the run summaries). `/api/tools` rows carry `recent` (each run's last ten, oldest run first) and `histogramBounds`. Fabric is branded Fabric (name, title, favicon, its own mark `/fabric-logo.svg`); the embedded page keeps loom·inspect.
- **5d (done).** The Configuration page. The rule editor offers fourteen behaviours: the six it had and context nearly full, truncated answers, compaction thrash, tool success rate, latency spike, access refused, injection scan and accepted trifecta, each with its inputs and its default shown beside the value. The page does not re-implement the grammar: the server's check is the only one, and its refusal (or the warning for `*`) is shown as written, with the form kept. An accepted trifecta needs a reason before the page will add it. A Context windows card edits the catalog (model and usable tokens) with set, edit, remove, save, discard and the text form, saved through the same route and audit as prices.
- **5e (done).** Cross-cutting checks, each a test: contrast of the token pairs and of the text as drawn (both themes), keyboard and roles, 360 / 768 / 1360 px, hostile text, console and request health, and scale. They changed three tokens (`--line-strong` dark, `--rung-suggest` and `--rung-watch` light), made text wrap instead of widening the page (a name is cut in its cell, the whole in its title) and capped a fleet table at 200 rows with a note. The page remains `index.html`, `app.js`, `app.css`, the two marks and the fonts.
- **5f-1 (done).** A page that holds still, found by use: the simulator showed fifty identical tabs that reordered on every poll. Tabs keep the order they first appeared in (the client remembers it, whatever order the server lists), a new run is appended and never takes the selection, the row holds at most eight (the selected run, live runs, runs that finished within the last minute after being seen live), and a **Runs** list (process, workflow, status, started, incidents, cost; search and status chips) holds every run. Rows of the tools and tasks tables keep their place; when the live order differs a chip says so and **Re-sort** applies it. **Live / Paused** stops every redraw and counts what changed from the run list alone; moving to another page while paused refreshes once, for what was asked.
- **5f-2 (done).** Time. `RunState` keeps the records it was built from (`HISTORY_MAX` 50,000, oldest dropped; a Fabric restart replays its stored messages through the same path, so a restored run has its history again). `viewAt(seq)` feeds a scratch run, set up like the real one, the kept records up to `seq`, with the very code that builds the live view: it cannot drift. `GET /api/runs/{trace}/timeline` returns `{marks, history}` (marks: `seq`, `at`, `kind`, `label`; start, end, incident-open and -close, failure, compaction, state), and `GET /api/runs/{trace}?at=SEQ` the view as it was (`live: false`, `asOf`, `seq`, `history`, `partial` when the earliest records were dropped, no `controls`); out of range answers 404 with `historyStartsAt`. A view of a past point uses the rules, prices and windows the server holds now. The run page has a time bar (slider over the history, a keyboard-reachable marker for each mark, previous and next incident, **Live**) and, when looking at a past point, a banner ("As of 14:31:02 (3 min ago): a snapshot, not live", **Back to live**); the snapshot does not change while polls go on, "ago" counts from its moment, controls are hidden. The fleet pages are not replayed (they have Pause).
