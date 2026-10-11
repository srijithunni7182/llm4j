# loom-inspect: module design (draft 1)

Live observability for Loom workflows, shipped as an add-on jar. It watches a run while it happens: tokens and cost per agent, which agent and tool is active, where each decision sits on the autonomy ladder, and what has gone wrong. It is read-only, with three controls (pause, suspend, stop) and one write (rotate a secret).

The UI mockups are the design system at https://claude.ai/artifact/G8ah58W5edzPPPWBJfPGVW (pages: Console, Incidents, Secrets). This document is how to build what they show.

## 1. Decisions already made

| # | Decision |
|---|---|
| 1 | Read-only except pause, suspend, stop, and rotating an existing secret. |
| 2 | Thresholds (loop, hallucination, paused-too-long) live in the Loom script, not in the admin page. |
| 3 | Secrets: rotate only. No add, no delete, no allowed-host change. Works only with the store the running app already uses. |
| 4 | It is an add-on jar that runs inside the application's own JVM. |
| 5 | The Loom logo appears in the page header. |

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
- `TraceEvent` has no run id. The application supplies one when attaching.
- `levelStore()` on the executor is package-private. The `Ledger` and `LevelStore` come from whoever called `setAutonomy`, so the app hands them to loom-inspect.

## 3. Gaps, and the small core changes they need

These are changes in `ai-agent4j-loom`, kept as small as possible. Each has a fallback so loom-inspect can ship without it.

| # | Gap | Proposed change | Fallback without it |
|---|---|---|---|
| C1 | **No pause.** `setStopAt` exists but there is no "hold before the next model call". | A `RunControl` object on the executor: `pause()`, `resume()`, `requestStop()`, `requestSuspend()`. The executor checks it before each LLM call and between steps, the same place budgets are enforced. | v1 offers **Stop** (via `setStopAt` on the next step boundary) and **Suspend** only; Pause is hidden. |
| C2 | **No call-level events.** There is no trace event for an LLM call with tokens, latency and model, and `observation` carries no tool name. | Emit `llm_call` (agent, model, tokens in/out, ms, cost) and add the tool name and duration to `observation`. | Poll `spend()` every second for tokens and cost; derive "tool in flight" from an `action` with no `observation` yet, per agent. Latency is unavailable. |
| C3 | **No key-health signal.** We cannot say whether the provider accepted or rejected a secret. | Emit `secret_use` (name, outcome: ok, 401, 403, other; never the value). | The Secrets page shows used-by, host, updated and fingerprint, and leaves the Status column out. |
| C4 | **No `inspect` block in the language.** | Add `inspect <Agent> { ... }` to the parser, AST and `weave check` (see section 6). | Thresholds come from a Java `InspectConfig` with sensible defaults, and the page says "defaults". |
| C5 | `levelStore()` is package-private. | Make it public, or add `executor.autonomy()` returning ledger and level store. | The app passes both to `Inspect.attach`. |

Order of value: C2 and C4 first (they make the console and incidents real), then C1, then C3.

## 4. Module layout

New Maven module `src/loom/loom-inspect`, artifactId `loom-inspect`, listed in the root `pom.xml` next to `src/loom/ai-agent4j-loom`.

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
    http/
      InspectServer.java       JDK HttpServer, JSON and server-sent events
      Auth.java                pluggable authenticator, CSRF, roles
      Api.java                 route table (section 7)
  src/main/resources/inspect-ui/
    index.html, app.js, app.css   plain static files, no build step
    loom-logo.png              copied from src/loom/vscode-loom/media/loom-logo-320.png
```

Choices and why:

- **No web framework in the jar.** The JDK's `com.sun.net.httpserver` serves JSON and SSE, so loom-inspect adds only Jackson (already used). A later `loom-inspect-spring` can mount the same `Api` under an existing Spring app (GetViral uses Spring Boot with Spring Security) and reuse its login. That is v2.
- **Static UI, no build.** The mockups are plain HTML, CSS and a little JS. Shipping them as static resources keeps the jar self-contained and removes a Node toolchain from the build.
- **One writer thread.** `EventSink` only enqueues; `Pump` mutates `RunState`. HTTP handlers read immutable snapshots. This keeps the run's own threads fast and avoids locks on the hot path.

## 5. How it attaches

```java
HarnessExecutor executor = ...;
Inspect.Handle h = Inspect.attach(executor,
        RunInfo.of("r-7f3a", "ContentStudio", "GetViral"),
        Options.builder()
            .bind("127.0.0.1", 8787)
            .authenticator(Auth.token(System.getenv("INSPECT_ADMIN_TOKEN")))
            .autonomy(ledger, levelStore)          // until C5
            .build());
executor.initialize();                              // after attach, so agents report
executor.executeWorkflow("Main", inputs);
```

`attach` does three things: registers the `EventSink`, registers the suspension listener, and (first call only) starts the server. It returns a handle with `close()`. One server serves many runs; each `attach` adds a run to the registry.

If the app never calls `attach`, nothing changes: the runtime pays nothing when no listener is registered.

The CLI path: `weave run flow.loom --inspect :8787` loads loom-inspect from the classpath if present and calls the same `attach`. v2.

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
- Two roles: `viewer` (everything read-only) and `admin` (control and rotate). Secrets and control endpoints require `admin`.
- Rotation also requires re-entering the admin credential, a CSRF token, and a same-origin check.
- Audit log of every control and rotation: who, when, what, fingerprint before and after, source address. Never values. Written through a pluggable `AuditSink`, with a file default.
- **Trace content is sensitive.** Events hold prompts and answers. Defaults: truncate text to 500 characters, apply the agent's existing `guard { pii: mask }` settings (events are already masked at source where configured), and let `Options.redact(...)` add patterns. The mockups show full trace lines; the shipped default shows the truncated form.
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
- **Live suite.** Under the existing `-Plive` profile, attach to a real run and assert the page shows tokens within a second.

## 12. Build plan

| Phase | Delivers | Needs |
|---|---|---|
| 0. Spike | `EventSink` + `RunState` + `GET /api/runs/{id}` and a bare page showing totals and active agents | nothing in core |
| 1. Console | Graph overlay, agents table, Following trace, spend chart, tools, autonomy ladders (read-only) | C2 helps; C5 or app-supplied stores |
| 2. Incidents | Rate-limit, loop, grounding and budget detectors with default thresholds; attention strip | none (heuristics) |
| 3. Script thresholds | `inspect { }` block in the parser and `weave check` | C4 |
| 4. Controls | Suspend and stop, then pause | C1 |
| 5. Secrets | List, fingerprint, rotate with audit | C3 for the Status column |
| 6. Hardening | TLS, roles, retention options, `weave run --inspect` | none |

Phases 0 to 2 need no change to the core, so the first useful release does not wait on language work.

## 13. Open questions

1. **Run identity.** Should the app always name the run on `attach`, or can the executor expose its journal's run id so loom-inspect can read it?
2. **`no_progress` needs a variable.** Is reading a named workflow variable from outside the executor acceptable, or should the script emit it as a `note`/`observe` event that loom-inspect watches?
3. **Where does `inspect` live in the grammar?** A top-level block per agent (as above), or inside the `agent` block next to `budget`? The second keeps it near the agent and avoids a new top-level keyword.
4. **Multiple executors in one JVM** (GetViral runs many workflows): one server, many attached runs. Is a per-workflow tab enough, or do you want a cross-run overview as the landing page?
5. **Pause semantics.** Should pause let in-flight model calls finish (recommended) or cancel them? Cancelling wastes tokens already spent.
6. **Fingerprint in the audit.** OK to record the truncated hash before and after in the audit log? It is the only way to prove which key was live when.
