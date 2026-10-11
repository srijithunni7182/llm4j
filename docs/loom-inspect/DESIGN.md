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
| 10 | **Remote mode centres on `loom-inspect-server`:** a standalone, packaged server you install on any machine. A workflow process is given its URL up front on the `weave` command line (`--observe-server <url>`). There is no listener on the workflow process and no dynamic webhook registration. |
| 11 | **Many processes, one server.** Any number of workflow processes report to the same server, and all their workflows are monitored together: one tab per workflow, each run tagged with the process it came from. |
| 12 | Processes only connect outward. Pause, suspend and stop arrive on an outbound long-poll the process holds open. Secret values never leave a process, and secret rotation stays in embedded mode. |
| 20 | **The wire format follows OpenTelemetry.** A run is a trace, steps are spans, live lifecycle events are log records, and totals are metrics, sent as OTLP/HTTP JSON using the GenAI semantic conventions where they exist and `loom.*` attributes for the rest. Only the parts OpenTelemetry has no concept of (hello, snapshot, commands) travel outside it. |
| 21 | Because the format is standard, a process can also export the same data to any OTLP collector (Grafana, Tempo, Datadog, Honeycomb) with `--observe-otlp <endpoint>`, independently of the inspect server. |
| 22 | **The emitter is fault tolerant through a local outbox.** Every event is written to a durable file on the workflow machine before it is sent. The inspect server can be down for hours or days without affecting the workflow in any way, and when it returns every missed event is streamed in, in order. |
| 13 | The server keeps history on disk (retention 30 days or 2 GB, oldest finished runs first) and has its own secret store, which holds each client's key and the admin password. |
| 14 | One key per process. Every process is named on purpose: `--observe-name` is required with `--observe-server`. |
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
| C3 | **No key-health signal.** We cannot say whether the provider accepted or rejected a secret. | Emit `secret_use` (name, outcome: ok, 401, 403, other; never the value). | The Secrets page shows used-by, host, updated and fingerprint, and leaves the Status column out. |
| C4 | **No `inspect` block in the language.** | Add `inspect <Agent> { ... }` to the parser, AST and `weave check` (see section 6). | Thresholds come from a Java `InspectConfig` with sensible defaults, and the page says "defaults". |
| C5 | **Nothing is exposed as a whole.** Some getters are public, some package-private, some missing. | One read-only `ObservableRun` interface returned by `executor.observable()`: `runId()`, `journal()`, `script()` (workflows, agents, tools, provider and secret references), `secretStore()`, `ledger()` and `levelStore()`, `budget()` (limits, `warn_at`, per-agent), `spend()`, `variables()` (immutable snapshot), `pending()` (questions and approvals waiting on a person), `clock()`. loom-inspect depends only on this interface, so internals can change without breaking it. | The app passes the pieces to `Inspect.attach`. |
| C6 | **No way to start loom-inspect from the CLI.** | An `ObserveProvider` interface in core, found with `ServiceLoader`, and a `--observe[=port]` option on `weave run`, `resume`, `tick` and `daemon` (section 5). Without loom-inspect on the classpath the option prints how to add it and exits. | Programmatic `Inspect.attach` only. |

Order of value: C5 and C6 first (they make loom-inspect reachable), then C2 and C4 (they make the console and incidents real), then C1, then C3.

## 4. Module layout

Three Maven modules under `src/loom/`, all listed in the root `pom.xml` next to `ai-agent4j-loom`:

- **`loom-inspect-core`**: everything that turns events into a view and has no dependency on the Loom runtime. The OpenTelemetry-shaped model and OTLP/JSON codec (`otel/`), the Loom-specific messages (`wire/`), `RunState`, the detectors, the JSON view API, the static UI, and the `Receiver` that accepts batches.
- **`loom-inspect`**: the add-on jar for the workflow process. Depends on core and on `ai-agent4j-loom`. Holds the trace sink, the embedded HTTP server, the `--observe` CLI provider, secrets and control, and the `ServerLink` that sends events to a server (section 5b).
- **`loom-inspect-server`**: the packaged, runnable server (section 5b). Depends on core only, so it is small and does not need Loom or any model SDK on the machine it runs on.

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

## 5b. Remote mode: the inspect server

For when you want one place to watch everything: several workflow processes, on several machines, reporting to a single admin page that is not inside any of them.

```
  workflow process A ─┐
 (getviral-prod)     │     OTLP, signed            ┌──────────────────────────┐
                     ├───────────────────────────▶ │ loom-inspect-server      │ ◀────▶  browser: one console,
 workflow process B ─┤  ◀─── commands (long-poll) ── │  receiver · state        │         every workflow of every
 (billing-svc)       │                             │  detectors · history     │         process, one tab each
 workflow process C ─┘                             │  UI · own secret store   │
                                                   └──────────────────────────┘
   each process connects outward only; the server never connects to a process
```

### The server

A packaged runnable jar (`loom-inspect-server-<version>.jar`, plus a Docker image and a sample systemd unit). It runs on any machine with a JDK and needs no Loom runtime, no model keys and no workflow scripts.

```
loom-inspect-server init  --data /var/lib/loom-inspect          # creates its store; asks for the admin password
loom-inspect-server clients add getviral-prod                   # prints that client's key once
loom-inspect-server serve --data /var/lib/loom-inspect --port 9090 \
                          --tls-cert cert.pem --tls-key key.pem
loom-inspect-server clients list | revoke <name>
```

- **Its own secret store.** Created by `init`, using the same encrypted-store format as Loom. It holds the admin password (`LOOM_INSPECT_ADMIN_PASSWORD`) and each client's key (`client.<name>`). The master key comes from a file or an environment variable you name, as everywhere else in Loom.
- **TLS** is served directly with `--tls-cert/--tls-key`, or the server can sit behind a reverse proxy (`--trust-proxy`). Without TLS it binds loopback only and refuses a public address.
- **Persistence.** Unlike the embedded page, the server is long-lived, so it keeps history: per-run append-only event files with periodic snapshots (JSON lines, no database to install), an index, and retention by age and size (defaults 30 days and 2 GB, oldest finished runs first). On start it rebuilds live state from disk. Live runs stay in memory; finished runs are read from disk when opened. A JDBC store is a later option.
- **One instance in v1.** No clustering. The server is the only thing a restart affects, and processes resend a snapshot when it returns.

### Pointing a workflow process at it

The URL is given to `weave` up front, every time, on the command line or through an environment variable:

```
weave daemon --secrets keys.store \
      --observe-server https://inspect.example.com:9090 \
      --observe-name getviral-prod

LOOM_INSPECT_SERVER=https://inspect.example.com:9090 weave run flow.loom --secrets keys.store
```

- `--observe-server <url>` enables remote reporting; `LOOM_INSPECT_SERVER` is the same thing from the environment.
- `--observe-name <name>` is this process's name on the server. It is **required** with `--observe-server`; there is no default. It must match a client created with `clients add`, and a process that omits it is refused at start-up with a message saying so.
- The client key is read from the process's own secret store as `LOOM_INSPECT_CLIENT_KEY` (changeable with `--observe-key-secret <name>`), so no key appears on a command line or in the environment.
- `--observe` (embedded page) and `--observe-server` can be used together.
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

The emitter never sends an event straight from memory. Every event is appended to a **durable local log on the workflow machine** first, and a separate background drainer sends the log to the server from the last acknowledged position. The inspect server can be down for any length of time, and the workflow neither slows nor fails.

**Not on the run's path.** The trace listener copies the event into a small in-memory ring and returns. One writer thread appends the ring to disk; another thread sends. If the writer falls behind or the disk is full, the ring drops the oldest entries and counts them; it never blocks the workflow, never throws into it, and never retries on the workflow thread. A failure inside the emitter is logged at most once a minute.

**The log.**
- Append-only segment files (default 8 MB each) of length-prefixed, checksummed records. Each record has a monotonically increasing **outbox offset**. The records are the same OTLP-shaped and Loom-specific messages that would have been sent, already redacted to the registration's detail level, so nothing sensitive is written that would not have been sent.
- **Location:** `--observe-outbox <dir>` (default: `observe-outbox/` beside the run's journal when `--journal` is given, otherwise `./loom-inspect-outbox/`). Under it, one directory per process name and instance (`<name>/<instance-id>/`). Directories are created `0700` and files `0600`.
- **Crash safety.** Records are flushed every 200 ms or 1,000 records, whichever first, and fsynced each second. A power loss can lose at most the last second of events; a process crash loses at most the last 200 ms. A record cut off mid-write is detected by its length and checksum and dropped when the log is opened again; the rest is intact.
- **Caps.** Default 256 MB and 7 days per process name. When the cap is reached the **oldest segments are dropped first**, the number lost is recorded as a gap, and a fresh snapshot follows the next delivery, so the console's current state is correct and only the old detail between is missing. The Processes page shows lost counts.

**Sending from the log.**
- The drainer keeps a **cursor** per destination (the inspect server; an OTLP collector if `--observe-otlp` is on) in a small file beside the log, updated only after the destination acknowledges. Several destinations read the same log independently, and a segment is deleted when every cursor has passed it.
- The server persists a batch before it acknowledges, and acknowledges by outbox offset. If the process dies after sending but before recording the acknowledgement, the batch is simply sent again and the server drops the duplicates.
- **Backoff and reconnect.** Retries use backoff from 1 second to 60 seconds, with jitter. On every reconnect the process sends `hello` first, then the backlog.

**When the server comes back: the backlog streams in.**
1. `hello`, then a fresh `snapshot` of every live run (so the view is correct at once).
2. The drainer then replays the log from the cursor, oldest first, in batches of up to 500 records, marked as replay. The server answers `429` with `Retry-After` if it needs the pace lowered. Live events keep being appended to the same log, so replay and live events share one ordered stream and nothing is reordered.
3. The console shows the process as **Catching up** with the backlog size and falling, then **Live** when it has reached the head of the log. History inside the outage window is filled in, step by step, with its original timestamps, so a run's timeline and cost chart show what actually happened rather than a hole.

**A process that dies, or exits, with events unsent.**
- On a clean exit the process tries to flush for a few seconds (`--observe-flush-timeout`, default 5 seconds) and then exits, leaving the rest in the log. It never waits for a down server.
- Any later `weave` process started with the same `--observe-name` and outbox directory first drains the logs of earlier instances (found under `<name>/`; a directory whose lock is not held belongs to a finished or dead process) before its own. A run that paused for a long `sleep` and is resumed days later therefore delivers the events from before the pause as well.
- `weave observe flush --observe-server <url> --observe-name <name> --observe-outbox <dir>` drains an outbox by hand, and `weave observe purge` deletes one.
- A run whose process died without a `bye` is shown by the server as "lost contact at …, final state unknown" until the backlog arrives or the run is resumed.

**What it does not promise.** It is a durable buffer, not a database. At most the last second can be lost in a power failure, the cap can drop old events on a long outage, and a machine whose disk is lost loses its backlog. In each case the server's view stays honest: it shows the gap and the loss count rather than inventing events.

### What comes back

OTLP responses cannot carry commands, so commands use a separate outbound **long-poll**: the process asks `GET /loom-inspect/v1/commands?wait=25s` and the server answers the moment a command is queued (or with an empty reply after 25 seconds, and the process asks again). The process stays outbound-only, and a command now arrives in well under a second rather than waiting for the next batch.

- **`commands`** are `pause`, `resume`, `suspend` and `stop` for a named run on that process, signed with the client's key. The process verifies the signature and timestamp, executes through `RunController` (C1), and reports the outcome as a `loom.command.result` log record. A command expires after 60 seconds if the process cannot be reached.
- **Acknowledgement and clock.** The `heartbeat` reply carries the server's clock, so a process can warn when the two clocks differ by more than the signature window.

### Exporting to any collector

`--observe-otlp <endpoint>` (with `--observe-otlp-header "Authorization=Bearer …"`, the key taken from the secret store) sends the same traces, logs and metrics to a standard OTLP endpoint: an OpenTelemetry Collector, Grafana Tempo and Mimir, Honeycomb, Datadog's OTLP intake. It is independent of `--observe-server` and can be used with or without it. It sends only OTLP, with no hello, snapshot or commands, because a generic backend has nowhere to put them. This is the cheap hedge: even if loom-inspect is never opened, the data already exists in the tools a team uses for everything else.

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

In server mode the same read routes are served by `loom-inspect-server`, now spanning every connected process (each run is keyed by client and run id, and each route takes an optional `client=` filter). It adds `POST /ingest` for batches, `GET /api/processes` for the Processes page, and the `clients` administration, which is available only through the CLI. The workflow process serves no routes of its own in this mode. The secrets-rotation route is served only in embedded mode; control routes in server mode enqueue a command instead of acting directly.

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
3. **A pinned server key: `--observe-pin sha256:<fingerprint>`** (or `LOOM_INSPECT_PIN`), for a single self-signed server where you do not run a CA. The process accepts only a server whose public key (SPKI) hashes to that value, so no CA is involved. The fingerprint is printed by `loom-inspect-server` at start-up. A pin over the key, not the certificate, survives certificate renewal as long as the key is reused.

Rules for all three:

- **Hostname verification stays on.** The certificate must name the host in the URL (a SAN match).
- **No trust on first use.** A process never learns a certificate by seeing it once; a person supplies the CA file or the pin.
- **Trust is per process and configured with the `weave` command.** It is not read from the server.
- A process whose server fails verification reports it once on the console, stops reporting, and keeps running the workflow. The server shows that client as Offline.

On the server side, `loom-inspect-server tls init --host inspect.example.com` can create a small private CA and a server certificate for it, writes `ca.pem` (the file you hand to processes) and prints the server's pin. Renewing the server certificate under the same CA needs no change on any process. Behind a reverse proxy, the proxy owns the certificate and the process trusts whatever the proxy's certificate chains to.

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

## 12. Build plan

The server comes right after the console, so the console works across processes early. Every phase is checked against the soak test (section 11a).

| Phase | Delivers | Needs from the core |
|---|---|---|
| 0. Wire format | `loom-inspect-core` with the OpenTelemetry-shaped model and OTLP/JSON codec, the `TraceEvent` mapper, the Loom-specific messages and `RunState`, used by embedded mode too, so server mode is not a rewrite | none |
| 0b. Spike | `ObservableRun` (C5), `ObserveProvider` and `--observe` (C6), `EventSink` + `RunState`, `GET /api/runs/{id}` and a bare page showing totals and active agents | C5, C6 (both small, additive) |
| 1. Console | Graph overlay, agents table, Following trace, spend chart, tools, autonomy ladders (read-only), workflow tabs | C2 helps; C5 or app-supplied stores |
| 2. Server mode | `ServerLink` in the add-on; the `loom-inspect-server` jar with `init`, `clients`, `serve`, `tls init`; the durable outbox with cursors, replay and backlog catch-up; signed OTLP ingest, `hello`, snapshots, resync and heartbeat; `--observe-otlp` export to any collector; the Processes page; tabs grouped by process; disk history with 30-day, 2 GB retention; `--observe-server`, `--observe-name`, `--observe-ca`, `--observe-pin`; the connection chip and stale view; the Docker image. Read-only: commands arrive in Phase 5. | C5, C6 |
| 3. Incidents | Rate-limit, loop, grounding and budget detectors with default thresholds; the attention strip | none (heuristics) |
| 4. Script thresholds | `inspect { }` block in the parser and `weave check` | C4 |
| 5. Controls | Suspend and stop, then pause; through the embedded page directly and through the server as commands on the long-poll | C1 |
| 6. Secrets | List, fingerprint, rotate with audit, embedded mode only | C3 for the Status column |
| 7. Hardening | Admin password hashing, login rate-limiting review, log and body redaction audit, packaging polish | none |

Phases 0 to 3 need only two small additive changes to the core (an interface and an option), so the first useful release, with a working server, does not wait on language work.

## 13. Open questions and deferred work

Settled since draft 1: run identity (the executor already has `getRunId()` and `getJournal()`, and `ObservableRun` exposes them), the `--observe` activation, per-workflow tabs, the admin password living in the store, remote mode as a standalone `loom-inspect-server` that many processes report to and that is configured from `weave` up front, one key per process, required process names, tabs per process and workflow with no merging, fixed retention (30 days or 2 GB), TLS trust through `--observe-ca` or a pin, commands carried on an outbound long-poll, an OpenTelemetry-shaped wire format with OTLP export, and a single admin password.

**Still open**

1. **`no_progress` needs a variable.** `ObservableRun.variables()` gives a snapshot, so loom-inspect can read the named variable at the end of each loop round. Is a read-only snapshot from outside the executor acceptable?
2. **Where does `inspect` live in the grammar?** A top-level block per agent (as in section 6), or inside the `agent` block next to `budget`? I lean toward inside the agent block.
3. **Pause semantics.** Should pause let in-flight model calls finish (recommended) or cancel them? Cancelling wastes tokens already spent.
4. **Fingerprint in the audit.** OK to record the truncated hash before and after a rotation in the audit log? It is the only way to prove which key was live when.
5. **Password form.** Accept the plain stored value in v1 and add hashing later, or require the hashed form from the start?
6. **`--observe` on one-shot runs.** `weave run` ends when the workflow ends, which closes the embedded page. Should `--observe` keep it up for a short time (for example `--observe-linger 5m`) so the final state can be read? Server mode does not have this problem.
7. **Outbox defaults.** 256 MB and 7 days per process name, fsync every second. Are these right for your machines, and should the default location be beside the run journal (as proposed) or a fixed system directory?

**Deferred past v1**

- A read-only viewer password, and per-process visibility.
- A direct control port on a process, for urgent stops.
- The server handing out its CA on first enrolment (needs a bootstrap trust step).
- Mutual TLS as an alternative to the per-process signing key.
- A database store for the server's history, and running more than one server instance.
- Secret rotation through the server (the value would have to be encrypted to a key held only by the process).
