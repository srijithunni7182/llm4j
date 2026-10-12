# Observability reference

[← Wiki home](Home.md) · [Observability overview](../docs/OBSERVABILITY.md)

Everything in one place: the `weave` options, the Fabric commands, the HTTP routes, the wire messages, the OpenTelemetry
records, and the files and environment variables involved.

## `weave` options

On `run`, `resume`, `tick` and `daemon`.

| Option | Meaning |
|---|---|
| `--observe[=port]` | Serve a page about this process's runs (default port 8787). Needs `loom-inspect` and `loom-inspect-core` on the class path |
| `--observe-bind address` | Address the page listens on (default `127.0.0.1`) |
| `--observe-detail full\|truncated\|metadata` | How much text leaves the process (default `truncated`: 500 characters) |
| `--observe-name name` | This process's name. **Required** with `--observe-fabric` and `--observe-otlp` |
| `--observe-linger time` | Keep the page up this long after the run ends, e.g. `5m` |
| `--observe-fabric url` | Report to a Fabric server (or `LOOM_FABRIC_URL`). `https` only; `http` for a loopback address |
| `--observe-ca file` | A PEM CA certificate for the server (or `LOOM_FABRIC_CA`); added for this connection only |
| `--observe-ca-only` | Trust only that CA, not the default roots |
| `--observe-pin sha256:hex` | Accept only a server whose public key has this hash (or `LOOM_FABRIC_PIN`) |
| `--observe-outbox dir` | The local outbox (default `./loom-inspect-outbox`) |
| `--observe-key-secret name` | The secret holding this process's key (default `LOOM_FABRIC_CLIENT_KEY`) |
| `--observe-flush-timeout time` | At exit, try this long to deliver what is left (default `5s`) |
| `--observe-otlp url` | Also export traces and logs to an OTLP/HTTP endpoint. Needs `--observe-name` |
| `--observe-otlp-header Name=value` | A header for it; a value may be `${secret:NAME}`. Repeatable |
| `--observe-rules file` | The rules file (default `<script>.inspect` beside the script) |
| `--prices file` | The price table (also found as `<script>.prices`, `prices.properties`, or `LOOM_PRICES`) |

### `weave observe` subcommands

| Command | Does |
|---|---|
| `weave observe flush --observe-name N …` | Delivers what an earlier process left in its outbox to Fabric |
| `weave observe purge --observe-name N` | Deletes an unused outbox (undelivered events are lost) |
| `weave observe rules flow.loom [--observe-rules f]` | Checks a rules file against a script without running anything |
| `weave observe hash-password [--password-env VAR]` | Prints a salted hash of the admin password (default variable `LOOM_INSPECT_NEW_PASSWORD`, or typed) |

## `fabric` commands

`java -jar loom-fabric-5.0-all.jar <command>`

| Command | Does |
|---|---|
| `init --data dir [--master-key-file f] [--admin-password-env VAR]` | Creates the data directory, the vault and the admin password (12+ characters) |
| `admin set-password --data dir` | Replaces the admin password (from `LOOM_FABRIC_ADMIN_PASSWORD`) |
| `clients add NAME --data dir` | Creates a client; prints its key **once** |
| `clients list --data dir` | Names only, never keys |
| `clients revoke NAME --data dir` | Removes the key; its next request is refused (within a second) |
| `serve --data dir [--port 9090] [--bind addr] [--tls-keystore f --tls-password-file f \| --tls-cert pem --tls-key pem] [--trust-proxy] [--retention-days 30] [--retention-size 2GB]` | Runs the server |
| `tls init --host name [--also names] --out dir` | Makes a small private CA and a server certificate |

## HTTP routes served to the page

The embedded server and Fabric serve the same routes (Fabric adds `processes`). All JSON. Reads need a session in Fabric.

| Route | Returns / does |
|---|---|
| `GET /api/health` | `{status, runs}` |
| `GET /api/runs` | Summaries of live and recent runs: client, workflow, status, incidents, worst incident |
| `GET /api/runs/{trace}` | One run's full view: totals, agents, tools, spend series, incidents, recent events, control state, connection state, and (signed in) `controls.allowed` |
| `GET /api/runs/{trace}/graph` | The workflow graph, with node paths the page marks as the run progresses |
| `GET /api/processes` | (Fabric) the Processes table |
| `GET /api/session` | `{signedIn, csrf (when signed in), controlsEnabled, controlsReason, secretsEnabled, configEnabled, passwordSet}` |
| `POST /api/login` | JSON `{password}`; sets the cookie, returns `{csrf}`. `429` when throttled, `503` when no password is set |
| `POST /api/logout` | Ends the session |
| `POST /api/runs/{trace}/{pause\|resume\|suspend\|stop}` | A control. Needs session, `X-CSRF-Token`, same origin. `202` taken, `401`, `403`, `404`, `409` not available now |
| `GET /api/config` | (Fabric) the Configuration sections: `rules` and `prices`, each `{text, summary, warnings}` |
| `PUT /api/config/{rules\|prices}` | (Fabric) `{text}`. Checked, then kept whole or not at all: `200` saved, `422` with `problems` (line numbers), `413`, `403`. Needs session and `X-CSRF-Token` |
| `GET /api/secrets` | (embedded) secrets with fingerprints, hosts, used-by. Never values |
| `PUT /api/secrets/{name}` | (embedded) `{value, password}`. Replaces an existing secret |

`{trace}` is the run's 32-hex-character OpenTelemetry trace id, derived from the run id.

## Routes processes use (Fabric)

All `POST`, all signed: `X-Loom-Inspect-Client: <name>` and `X-Loom-Inspect-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(key, t + "." + body)>`.
A missing, wrong or revoked key all get the same `401`.

| Route | Carries |
|---|---|
| `/loom-inspect/v1/hello` | The process's name, instance, host, pid, versions, wire version, workflows. Sent first on every (re)connect |
| `/loom-inspect/v1/snapshot` | Per run: the graph, autonomy ladders, the process's rules. On connect and after any gap |
| `/v1/logs`, `/v1/traces` | OTLP/JSON batches (also a standard OTLP/HTTP path, so any collector can receive the same records) |
| `/v1/metrics` | Accepted and not kept yet |
| `/loom-inspect/v1/heartbeat` | Every 5 s: time, counts sent, backlog, lost, ladders. The answer carries the server's time |
| `/loom-inspect/v1/bye` | A clean exit, so the process is not "offline by accident" |
| `/loom-inspect/v1/commands` | Long-poll (≤ 25 s) for pause/suspend/stop. The **answer is signed** with the process's key |
| `/loom-inspect/v1/command-result` | What the process did with a command |

`429` carries `Retry-After`; the process honours it. Wire messages are JSON, versioned (`Wire.VERSION = 1`) and tolerant of
fields they do not know.

## OpenTelemetry records

Spans (kind `loom.span.kind`: `workflow`, `agent`, `tool`, `task`, `sleep`) and log records, with the
[GenAI semantic conventions](https://opentelemetry.io/docs/specs/semconv/gen-ai/) where they fit.

| Attribute | Meaning |
|---|---|
| `service.name`, `service.instance.id` | the process name and this instance |
| `gen_ai.operation.name` (`invoke_agent`, `execute_tool`), `gen_ai.agent.name`, `gen_ai.tool.name`, `gen_ai.request.model` | what ran |
| `gen_ai.usage.input_tokens`, `gen_ai.usage.output_tokens`, `loom.usage.total_tokens`, `loom.usage.calls`, `loom.cost.usd` | what it cost |
| `loom.run.id`, `loom.workflow`, `loom.step`, `loom.seq`, `loom.run.resumed` | where in the run; `loom.seq` is per instance and makes repeats ignorable and gaps countable |
| `loom.run.status`, `loom.suspend.reason`, `loom.resume_at`, `loom.sleep.wake_at` | end state, suspension, sleeps |
| `loom.action.hash`, `loom.output.hash`, `loom.grounding.figures`, `loom.grounding.ungrounded`, `loom.grounding.examples` | the in-process signals the incident rules use |
| `loom.control.action`, `loom.control.state`, `loom.control.by` | operator controls |
| `loom.text` | agent/tool text, present only as far as `--observe-detail` allows |

Log events (`event.name`): `loom.run.start`, `loom.run.end`, `loom.run.suspended`, `loom.span.start`, `loom.thought`,
`loom.observation`, `loom.budget`, `loom.control`, `loom.inspect.sample`, `loom.note`.

## Files and environment

| What | Where |
|---|---|
| Local outbox | `<--observe-outbox>/<name>/<instance>/` (segments, checksums, cursors; 256 MB or 7 days) |
| Embedded audit log | `loom-inspect-audit.log` (`-Dloom.inspect.audit=path` moves it) |
| Fabric data | `<data>/vault.json`, `master.key`, `history/<client>/<day>.jsonl`, `audit.log`, `config/rules.inspect`, `config/prices.properties` |
| Rules file | `<script>.inspect` or `--observe-rules` |
| Price table | `--prices`, `<script>.prices`, `prices.properties`, `LOOM_PRICES` |

| Variable / secret | Used for |
|---|---|
| `LOOM_INSPECT_ADMIN_PASSWORD` (secret) | The embedded page's admin password (plain or `pbkdf2-sha256$…`) |
| `LOOM_FABRIC_CLIENT_KEY` (secret) | A process's key for Fabric (`--observe-key-secret` renames it) |
| `LOOM_FABRIC_URL`, `LOOM_FABRIC_CA`, `LOOM_FABRIC_PIN` | Alternatives to `--observe-fabric`, `--observe-ca`, `--observe-pin` |
| `LOOM_FABRIC_ADMIN_PASSWORD`, `LOOM_FABRIC_MASTER_KEY` | Fabric's admin password and vault master key, for `init` / `admin set-password` and `serve` |
| `LOOM_INSPECT_NEW_PASSWORD` | The password `weave observe hash-password` hashes |
| `LOOM_PRICES` | The price file |

## See also

[Observability](../docs/OBSERVABILITY.md) · [Fabric server](Fabric-Server.md) ·
[Incidents and rules](Observability-Incidents-and-Rules.md) · [Controls, secrets and security](Observability-Controls-and-Security.md) ·
[Design and status](../docs/loom-inspect/DESIGN.md)
