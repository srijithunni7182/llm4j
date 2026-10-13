# Deploying observability: a guide for operators

[← Back to the README](../README.md) · [Observability overview](OBSERVABILITY.md)

This guide is for the person who puts Loom workflows into production and has to make them watchable: install **Fabric**,
connect the processes, secure it, and keep it running. It is a checklist in the order you do things. For what the page
shows, read the [overview](OBSERVABILITY.md); for every option, the [reference](../wiki/Observability-Reference.md).

## 1. Decide the shape

| You have | Use |
|---|---|
| One workflow on one machine, you want to look at it now | **Embedded**: `weave run flow.loom --observe` (nothing to install on a server) |
| Several processes, or a daemon that should be watched from your desk | **Fabric** plus `--observe-fabric` on each process |
| A monitoring stack already (Grafana, Datadog, Honeycomb) | Add `--observe-otlp` to send the same traces and logs there; Fabric is optional |

You can combine them. Everything below is the Fabric path; embedded needs only the add-on jars.

## 2. What you need

- **JDK 17+** on the Fabric host. Nothing else: no Loom runtime, no model keys, no scripts.
- **On each Loom process:** `loom-inspect` and `loom-inspect-core` jars on the class path beside `weave`
  (`mvn -DskipTests -pl src/loom/loom-inspect -am package`). Without them an `--observe*` option says so and does nothing.
- **The Fabric jar:** `mvn -DskipTests -pl src/loom/loom-fabric -am package` gives
  `src/loom/loom-fabric/target/loom-fabric-5.0-all.jar`.
- **A name** for each process, such as `getviral-prod`. It is how the process is shown, keyed and revoked.
- **Network:** each process must reach the server **outbound** (HTTPS). Fabric never connects to a process, so firewalls
  between them only need to allow that one direction.

## 3. Install the server

```bash
sudo useradd --system --home-dir /var/lib/fabric --create-home fabric
sudo mkdir -p /opt/fabric && sudo cp loom-fabric-5.0-all.jar /opt/fabric/fabric.jar

# once: data directory, vault, admin password (12+ characters). Not on the command line.
sudo -u fabric LOOM_FABRIC_ADMIN_PASSWORD='a long password' java -jar /opt/fabric/fabric.jar init --data /var/lib/fabric
```

`init` writes `vault.json` (AES-256-GCM; holds the admin password hash and each client's key) and `master.key` in the same
directory. **Move `master.key` somewhere else** (a secrets manager, a root-only file) and give it back at run time with
`LOOM_FABRIC_MASTER_KEY` or `--master-key-file`. Without it the vault cannot be read, and a backup that holds both is a
backup that holds the keys.

Set the page's admin password, then the sections below as needed:

```bash
sudo -u fabric java -jar /opt/fabric/fabric.jar clients add getviral-prod --data /var/lib/fabric   # prints the key ONCE
```

## 4. Put TLS on it

Fabric refuses to listen on a non-loopback address without TLS or `--trust-proxy`. Choose one:

| Choice | How | Processes need |
|---|---|---|
| **Reverse proxy terminates TLS** (nginx, Caddy, a cloud load balancer) | `serve --trust-proxy` on loopback; proxy `https://fabric.example.com` to it. Allow long requests: commands use a **25 s long-poll** | nothing extra (public CA) |
| **Fabric terminates TLS, public certificate** | `serve --bind 0.0.0.0 --tls-cert chain.pem --tls-key key-pkcs8.pem` | nothing extra |
| **Fabric terminates TLS, private CA** | `fabric tls init --host fabric.example.com --out /var/lib/fabric/tls`, then `serve --bind 0.0.0.0 --tls-keystore …/server.p12 --tls-password-file …/server.password` | `--observe-ca ca.pem` |
| **One self-signed server** | as above; `serve` prints a key pin | `--observe-pin sha256:…` |

Host names are always verified; plain `http` is accepted by a process only for a loopback address. With a proxy, keep the
proxy's request timeout above 30 s, or controls will look unreliable.

### Run it as a service

`src/loom/loom-fabric/fabric.service` is a systemd unit (loopback plus proxy by default; hardening flags included):

```bash
sudo cp fabric.service /etc/systemd/system/ && sudo systemctl daemon-reload && sudo systemctl enable --now fabric
```

Set `LOOM_FABRIC_MASTER_KEY` through the unit's `EnvironmentFile=` (mode 600). A Dockerfile is in the same directory, not yet
built in CI: treat it as a starting point. Put `/data` on a persistent volume.

## 5. Connect each process

On the machine that runs the workflow:

```bash
# the key from 'clients add' goes into the process's own secret store, never on a command line
weave secrets set LOOM_FABRIC_CLIENT_KEY --secrets keys.store

weave daemon triggers/ --secrets keys.store \
      --observe-fabric https://fabric.example.com --observe-name getviral-prod \
      --observe-ca ca.pem          # only for a private CA
```

The same options work on `run`, `resume`, `tick` and `daemon`. For a long-lived daemon, put them in the service unit. Useful
settings:

| Setting | When |
|---|---|
| `--observe-detail metadata` | Prompts and answers must not leave the machine. The incident rules still work |
| `--observe-outbox /var/lib/loom/outbox` | Put the outbox on a disk with room: it holds events while Fabric is away (256 MB or 7 days per name) |
| `--observe-rules prod.inspect` | Thresholds for this deployment (see §8) |
| a price file | So cost is reported: `flow.prices` beside the script, or `--prices`, or `LOOM_PRICES` (see §8) |

**Check it:** the process should appear on Fabric's **Processes** page within about 5 seconds, *online*. If not, §10.

## 6. Turn on the controls

Pause, suspend and stop need an admin; with none set the page is read-only.

- **On Fabric:** the admin password you set at `init` is the one (`fabric admin set-password` changes it).
- **On an embedded page:** put `LOOM_INSPECT_ADMIN_PASSWORD` in the app's secret store. Store a **hash**:
  `LOOM_INSPECT_NEW_PASSWORD='…' weave observe hash-password`, then `weave secrets set LOOM_INSPECT_ADMIN_PASSWORD` and paste
  the `pbkdf2-sha256$…` line.

Controls take effect **between steps** and only reach a process that is online. Suspend needs the run to have a journal
(`--journal`). Details: [controls and security](../wiki/Observability-Controls-and-Security.md).

## 7. Retention, backup, sizing

| | |
|---|---|
| **History** | `<data>/history/<client>/<day>.jsonl`. 30 days and 2 GB by default: `--retention-days`, `--retention-size`. Oldest day goes first |
| **Back up** | `<data>` (history, `config/`, `vault.json`, `audit.log`) and, **separately**, `master.key` |
| **Restart** | Safe at any time: the page is rebuilt from history; processes catch up from their outboxes |
| **Load** | The server runs 64 threads (each connected process holds one for its command long-poll) and rate-limits each client to about 100 requests/s with a burst of 200. Hundreds of processes are comfortable; if you run thousands, put several Fabrics behind different names |
| **Disk** | History grows with how chatty your runs are and the detail level: `metadata` is a fraction of `full` |

## 8. Configure rules and prices (central)

Sign in to Fabric and open the **Configuration** page. Two documents are kept on the server, in the same format as the files a
process keeps beside its script:

- **Inspect rules**: what counts as trouble per agent. Pick an agent (one your runs have shown, or all agents), a behaviour (repeated tool call, repeated answer, failing tool, figures with no source, long pause, no progress) and a threshold. 
  They are laid over each run's own rules, rule by rule, and apply at once to every run. Use this to tune thresholds for
  the whole fleet without touching any machine.
- **Price table**: the models your runs use are listed; pick one and set its **input** and **output** price (dollars per million tokens). A step whose process reported tokens but no cost is
  costed from it. A process with its own price file keeps reporting its own cost, which wins.

A save is checked first: errors come back with line numbers and **nothing is kept**; a valid save is written to
`<data>/config/` and survives restarts. Every change is in the audit log. Per-process alternatives remain: `flow.inspect`,
`flow.prices`, `--observe-rules`, `--prices`.

Limits: costs already counted do not change when prices change; a `no_progress` variable is still read in the process, so
it only takes effect where that process's own rules name it.

## 9. Operate it

| Task | How |
|---|---|
| Add a process | `fabric clients add NAME`, then connect it (§5). No server restart |
| Remove or rotate a key | `fabric clients revoke NAME` (refused within a second), then `clients add NAME` again and update the process's secret |
| Change the admin password | `LOOM_FABRIC_ADMIN_PASSWORD=… fabric admin set-password`; restart to end signed-in sessions |
| See what changed | `<data>/audit.log`: sign-ins, controls, command results, configuration changes. Never passwords or secret values |
| A process left events behind | `weave observe flush --observe-name NAME …` delivers them; `weave observe purge` discards them |
| Upgrade | Replace the jar and restart. Processes keep working while it is down |

Watch these on the Processes page: **Backlog** (growing means the server is slow or unreachable), **Lost** (anything above 0
means an outbox hit its cap), **State** (*offline* means no message for 15 s).

## 10. Troubleshooting

| You see | Check |
|---|---|
| The process never appears | Is `--observe-name` exactly a name from `clients list`? Is `LOOM_FABRIC_CLIENT_KEY` in the secret store the process uses? The process prints a refused key once on its console |
| `unauthorised` / refused key | The key was revoked or mistyped; issue a new one |
| *offline* though it is running | Network or TLS between them; clock skew over 5 minutes fails the signature (run NTP) |
| TLS errors | A private CA needs `--observe-ca ca.pem`; the name in the URL must match the certificate (`--also` adds names) |
| Controls missing | No admin password; process offline; for suspend, no journal |
| Backlog grows, never drains | Fabric down, rate-limited (`429`) or out of disk |
| Cost shows `$0.000` | Unpriced, not free: add a price file to the process, or a model to Fabric's price table |
| Nothing on `--observe` | The add-on jars are not on the class path; `weave` says so |

## 11. A production checklist

- [ ] Fabric behind TLS, on a host with a persistent `<data>` and backups
- [ ] `master.key` stored apart from `vault.json` and its backups
- [ ] One client per process, named for what it is, keys in the process's secret store
- [ ] `--observe-detail` chosen for what may leave each machine
- [ ] Outbox on a disk with room, on every process
- [ ] Admin password set (hash form for embedded pages); controls tested once on a harmless run
- [ ] Thresholds and prices set on the Configuration page; one run reviewed for false alarms
- [ ] Someone looks at the Processes page's *Backlog*, *Lost* and *offline* signals (or alerts on them through your OTLP backend)
- [ ] You have tried the whole thing first with the [simulator](../src/examples/fabric-sim/README.md)

## See also

[Observability overview](OBSERVABILITY.md) · [Fabric server](../wiki/Fabric-Server.md) ·
[Controls, secrets and security](../wiki/Observability-Controls-and-Security.md) ·
[Incidents and rules](../wiki/Observability-Incidents-and-Rules.md) · [Reference](../wiki/Observability-Reference.md)
