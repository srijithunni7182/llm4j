# Fabric server

[← Wiki home](Home.md) · [Observability overview](../docs/OBSERVABILITY.md)

**Fabric** is one console for every workflow of every Loom process. Processes report to it; it never connects to them.
It is a single JAR that needs a JDK and nothing else: no Loom runtime, no model keys, no scripts. Module README:
[`src/loom/loom-fabric`](../src/loom/loom-fabric/README.md).

![A run on Fabric's page: tokens and cost, the workflow with each step's state, incidents and a live trace](https://raw.githubusercontent.com/srijithunni7182/llm4j/main/docs/images/observability/fabric-run.png)

## What it does

| Capability | Detail |
|---|---|
| **Receives** | Signed batches of OpenTelemetry-shaped logs and traces from many processes, plus `hello`, `snapshot`, `heartbeat` and `bye` messages |
| **Shows** | The same page as the embedded add-on: one tab per process and workflow (runs of different processes are never merged), a **Processes** page, incidents, controls |
| **Keeps history** | On disk, one JSON-lines file per client and day. Every accepted batch is forced to disk before it is acknowledged; a restart rebuilds the page from it |
| **Authenticates processes** | A key per process (HMAC-SHA256 over the timestamp and body, 5-minute replay window). Revoking a key takes effect within a second, no restart |
| **Authenticates people** | One admin password; session cookie (HttpOnly, SameSite=Strict), CSRF token on every write, growing delay and a global limit on wrong guesses |
| **Central configuration** | An admin edits the inspect rules and the price table on the Configuration page ([below](#configuration-page)) |
| **Controls** | Pause, resume, suspend and stop, delivered to the process as signed commands on its long-poll ([details](Observability-Controls-and-Security.md)) |
| **Protects itself** | Per-client rate limit (answers `429` with `Retry-After`; the process backs off), body size cap (16 MB), no listening on a public address without TLS |
| **Audits** | Every sign-in outcome, control request and command result goes to `<data>/audit.log`. Never passwords, tokens or secret values |

## Set it up

```bash
# once: the data directory, the vault, the admin password (12+ characters)
LOOM_FABRIC_ADMIN_PASSWORD='a long password' java -jar loom-fabric-6.1.0-all.jar init --data /var/lib/fabric

# one key per process, printed once
java -jar loom-fabric-6.1.0-all.jar clients add getviral-prod --data /var/lib/fabric
java -jar loom-fabric-6.1.0-all.jar clients list   --data /var/lib/fabric       # names only, never keys
java -jar loom-fabric-6.1.0-all.jar clients revoke getviral-prod --data /var/lib/fabric

# run it (loopback only, without TLS)
java -jar loom-fabric-6.1.0-all.jar serve --data /var/lib/fabric --port 9090
```

- **Build the jar** from the repository root: `mvn -q -DskipTests -pl src/loom/loom-fabric -am package` gives
  `src/loom/loom-fabric/target/loom-fabric-6.1.0-all.jar`.
- **The vault** (`vault.json`) is Fabric's own AES-256-GCM store for the admin password (stored as a salted PBKDF2 hash)
  and each client's key. `init` writes `master.key` beside it unless you give the master key through
  `--master-key-file` or `LOOM_FABRIC_MASTER_KEY`. Keep the master key apart from the vault's backups: without it the vault
  cannot be read. Passwords and keys are never taken from the command line.
- **Changing the admin password:** `LOOM_FABRIC_ADMIN_PASSWORD=... fabric admin set-password --data ...`. Sessions already
  signed in are not ended until the server restarts.

## TLS

Fabric will not listen on a non-loopback address without TLS or `--trust-proxy` (a reverse proxy that terminates TLS in
front of it).

| Situation | What to do |
|---|---|
| Public certificate (Let's Encrypt, a load balancer) | `--tls-cert chain.pem --tls-key key-pkcs8.pem`, or terminate at the proxy and pass `--trust-proxy`. Processes need nothing extra |
| Private CA, made for you | `fabric tls init --host fabric.example.com --also 10.0.2.5 --out /var/lib/fabric/tls`, then `serve --bind 0.0.0.0 --tls-keystore .../server.p12 --tls-password-file .../server.password`. Give `ca.pem` to each process as `--observe-ca ca.pem` (public, not a secret; added for that connection only, never installed JVM-wide) |
| One self-signed server | `serve` prints the server key **pin**; processes use `--observe-pin sha256:…` |

Host names are always verified. Plain `http` is accepted by a process only for a loopback address.

## Point a process at it

```bash
weave secrets set LOOM_FABRIC_CLIENT_KEY --secrets keys.store            # paste the key from 'clients add'
weave daemon triggers/ --secrets keys.store \
      --observe-fabric https://fabric.example.com:9090 --observe-name getviral-prod --observe-ca ca.pem
```

`--observe-name` is required and must be a client created with `clients add`. The same options work on `run`, `resume`,
`tick` and `daemon`. The key is read from the process's own secret store (`LOOM_FABRIC_CLIENT_KEY`, changeable with
`--observe-key-secret`), so it never appears on a command line or in the environment.

### When Fabric is down

Events are written to a local outbox first (`--observe-outbox`, default `./loom-inspect-outbox`; capped at 256 MB or
7 days per process name), then sent. A Fabric that is down, slow, restarted or refusing a request never affects a workflow;
everything missed streams in when it returns, oldest first, and the page shows the process as *catching up*, then with no
gaps. A refused key (401 or 403) is reported once and the process stops trying, without touching the workflow. A process
that exits with events unsent leaves them on disk for the next process of the same name: `weave observe flush` delivers
them, `weave observe purge` removes them.

## The Processes page

![The Processes page: three processes with state, host, workflows, live runs, events per second, backlog, lost and last seen](https://raw.githubusercontent.com/srijithunni7182/llm4j/main/docs/images/observability/fabric-processes.png)

| Column | Meaning |
|---|---|
| State | **online** (a message in the last 15 s; heartbeats come every 5 s), **catching up** (online with a backlog), **offline** |
| Host, Workflows | what the process said in its `hello` |
| Live runs, Events/s | currently running, and the recent rate |
| Backlog | records the process has written but the server has not yet acknowledged |
| Lost | records dropped because the outbox hit its cap. Normally 0; anything else is shown in red |
| Last seen | the last message |

The server's time comes back in every heartbeat answer, but nothing yet warns about a skewed clock: keep clocks in sync (NTP), because a signature more than 5 minutes off is refused.

## Configuration page

![The Configuration page with inspect rules and a price table saved](https://raw.githubusercontent.com/srijithunni7182/llm4j/main/docs/images/observability/fabric-configuration.png)

Signed in, open **Configuration**. Two documents live on the server, in the same format as the files a process keeps beside its
script. You edit them with pickers, not by typing syntax; each has its own **Save** and **Discard changes**, and an
**Edit as text** section for anyone who prefers the file.

**Inspect rules: pick an agent, a behaviour, then a threshold.**

1. **Agent**: *All agents*, or one of the agents your runs have shown (read from the runs and their snapshots), or *Another agent…* to type a name.
2. **Behaviour**: *Same tool call repeated*, *Same answer repeated*, *Tool keeps failing*, *Figures with no source*, *Paused for too long*,
   *No progress across loop rounds*, *Context nearly full*, *Truncated answers*, *Compaction thrash*, *Tool success rate*, *Latency spike*,
   *Access refused*, *Injection scan* or *Accepted trifecta*. A line under the picker says what it flags and the default.
3. **Threshold**: the fields change with the behaviour: a count of times; *warn at* and *high at* for figures; a number with a unit (seconds
   to days) for a pause; rounds and the workflow variable for no progress; warn and high percent for context; a count and a span for compaction thrash and refused access; a percent and a number of calls for tool success; a factor for a latency spike; on or off for the injection scan; and, for an accepted trifecta, a **reason** (required: the page will not add one without it, and accepting for *All agents* brings the check's warning that it hides the signal). The page does not check the grammar itself: what the server refuses comes back as it wrote it, and your edits stay on the page.

**Add rule** puts it in the table (agent, behaviour, threshold, and the default it replaces); **Remove** takes one out. Nothing is kept until
**Save rules**. The table is built from the saved rules, so a file written by hand shows up in it too.

**Context windows: pick a model, then set its usable window in tokens.** The same table shape as the prices (set, edit, remove, save, discard), kept in `windows.properties`.

**Price table: pick a model, then set its input and output price.** The table lists the models your runs use, with the agents that use each
and its prices, or *not priced*. **Set price** (or **Edit**) on a row opens its two fields, **Input** and **Output**, in dollars per million
tokens, apart. **Another model** adds one that has not run yet. **Save prices** keeps the table.

| Document | What it does | Notes |
|---|---|---|
| **Inspect rules** (`<data>/config/rules.inspect`) | What counts as trouble, per agent (`Editor { same_action: 3 times }`, `* { paused_longer_than: 90s }`). Laid over each run's own rules, **rule by rule**, and in force at once for every run, running ones included | The format and the rules are those of [`flow.inspect`](Observability-Incidents-and-Rules.md). A variable for `no_progress` is read in the process, so it only takes effect where that process's own rules name it |
| **Price table** (`<data>/config/prices.properties`) | `model = input / output`, dollars per million tokens. A step whose process reported tokens but no cost is costed from it | A process with its own price file keeps its own cost. Costs already counted do not change. A model with no price has no cost |
| **Context windows** (`<data>/config/windows.properties`) | `model = tokens`, the usable context window of a model. An agent whose process was never told its model's window shows its context fill from here, by the agent's model (`provider/model` also matches `model`) | A window a process reports always wins, and the view says where each came from (`windowSource`). A model not listed changes nothing. Saved like the prices and recorded in the audit log |

A save is **checked first**. Errors come back with line numbers (an unknown rule with the nearest name suggested, a price
that is not a number) and **nothing is kept**. A valid save is written atomically, survives a restart, and is recorded in
`audit.log` (who, which document, how long; not the text). The page shows what is in force now and any warnings. The same
files can be edited on disk; they are read at start-up, and a file with errors is reported and not used.

## What it keeps

`<data>/history/<client>/<day>.jsonl` (no database) plus `config/` (rules and prices), `audit.log`, `vault.json` and `master.key`. Retention is 30 days
and 2 GB by default (`--retention-days`, `--retention-size`), oldest day first; it runs at start-up and hourly.

## Packaging and operation

- `src/loom/loom-fabric/fabric.service`: a systemd unit for loopback plus a reverse proxy (or with TLS flags).
- `src/loom/loom-fabric/Dockerfile`: not built in CI yet; treat it as a starting point.
- Back up `<data>` (history, vault) and, separately, `master.key`.
- Threads: the server runs 64, because each connected process holds a command long-poll open (up to 25 s).

## Troubleshooting

| You see | Look at |
|---|---|
| A process never appears | Is `--observe-name` a client from `clients list`? Is the key in the process's secret store under `LOOM_FABRIC_CLIENT_KEY`? Console of the process: a refused key is printed once |
| Process shows *offline* though running | Network between process and server; the clocks (more than 5 minutes apart fails signatures) |
| Backlog grows and never drains | Server unreachable or rate-limited; check `<data>/` disk space and the server's console |
| Controls are missing | Fabric has no admin password, or the process is offline: a command can only be delivered to an online process |
| "Lost" is above 0 | The process's outbox hit its cap while the server was away for a long time |

## Try it without a model

`./fabric-sim.sh` at the repository root starts a server and simulated processes. See
[fabric-sim](../src/examples/fabric-sim/README.md).

## See also

[Observability](../docs/OBSERVABILITY.md) · [Incidents and rules](Observability-Incidents-and-Rules.md) ·
[Controls, secrets and security](Observability-Controls-and-Security.md) · [Reference](Observability-Reference.md)

## Reading across runs

Two read-only routes (behind the same sign-in as the rest of the API) give the fleet views, each optionally limited with `?client=NAME`:

- `GET /api/tools` is the roll-up of every tool and task of every run: one row each (`kind` is `tool` or `task`; a tool and a task with one name are two rows), failing first, with calls, success rate, latency percentiles and histogram, health with its reason, failures by category, the agents that use it and, when it has failed, the run it last failed in. `counts` is the number of rows in each health state.
- `GET /api/security` is the security picture: the added-up counts, the agents whose verdict is `exercised`, `accepted` or `armed` (each with its run and, when accepted, the reason), and the open trifecta, injection and access incidents with their runs. At most 200 agents and 100 incidents, newest run first.

`GET /api/runs/{trace}/timeline` returns a run's marks and the range of its kept history; `GET /api/runs/{trace}?at=N` returns the view as it was after record `N` (`live: false`, `asOf`), 404 with `historyStartsAt` when `N` is not kept.

`GET /api/runs` carries `security`, the number of open security incidents, on each run, for a tab badge. An agent's `context` in a run's view also carries `points` (the last 120 model calls) and `events` (the last 60 compactions) for a timeline.

### Holding still, and finding a run

The page does not rearrange itself under your pointer. Run tabs keep the order they first appeared in; the tab row holds only the run you are looking at, the runs that are live and those that just finished (at most eight). Every run, finished or not, is in the **Runs** list: search it, filter by live, finished or failed, and open one. Rows of the tools and tasks tables keep their place; when the live order has changed a chip says "order changed" and **Re-sort** applies it. **Live / Paused** (top right) stops every redraw so you can read; while paused the page counts what changed ("Paused at 14:32:05 · 3 changes since") and **Live** brings it up to date.

For a first look use the calm simulator: `./run.sh calm` in [fabric-sim](../src/examples/fabric-sim/README.md) makes one process and one run. The default is a busy fleet, which keeps adding runs and incidents.

### Going back in time

On a run's page the **time bar** under the view tabs spans everything the run has kept. Each marker is something that happened: the start, an incident opening or closing, a failure, a compaction, a change of state (hover or focus a marker for what it was). Click one, drag the slider, or use **‹ incident** and **incident ›**, and the whole page shows the run **as it was** at that point, under a banner: *As of 14:31:02 (3 min ago): a snapshot, not live*. Nothing in a snapshot changes while the run goes on; **Back to live** (or the end of the slider) returns. Run controls are hidden in a snapshot.

A run keeps the records it was built from, up to 50,000; past that the oldest drop and a view says its totals are partial. A past view is made by the same code as the live one, using the rules, prices and windows the server holds now. The fleet pages are not replayed: use **Pause**.

### What the pages show

A run that broke says why, in words, and where:

![A failed run: the banner names the task that broke it, the category, the attempts and the step](https://raw.githubusercontent.com/srijithunni7182/llm4j/main/docs/images/observability/fabric-run-broken.png)

Each agent's context window, and what compaction cut to make room, with the thresholds the rules set drawn on it:

![The Context view: a gauge per agent and a timeline with a marker for each compaction](https://raw.githubusercontent.com/srijithunni7182/llm4j/main/docs/images/observability/fabric-run-context.png)

Across every run, the tools and tasks by health, and the agents that read untrusted content, reach private data and can send something out:

![Tools and tasks across all runs, worst first, one row open](https://raw.githubusercontent.com/srijithunni7182/llm4j/main/docs/images/observability/fabric-fleet-tools.png)

![The Security page: posture counts, the agents that exercised or were accepted for the lethal trifecta, and the open findings](https://raw.githubusercontent.com/srijithunni7182/llm4j/main/docs/images/observability/fabric-fleet-security.png)

The pages work from a 360 px phone to a wide screen, in a light and a dark theme, from the keyboard alone, and treat every name and message from a run as text. The fleet tables draw at most 200 rows and say when they hold some back.
