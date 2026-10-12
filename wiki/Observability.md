# Observability

[← Wiki home](Home.md)

Watch Loom workflows while they run: which agent is doing what, what it has cost, and whether something has gone wrong.
Two parts, one page:

- **loom-inspect**: an add-on jar. `weave run flow.loom --observe` serves a page about that process's own runs.
- **[Fabric](Fabric-Server.md)**: a standalone server. Many processes report to it; one tab per process and workflow.

```bash
weave run flow.loom --observe                       # http://127.0.0.1:8787
weave daemon triggers/ --secrets keys.store \
      --observe-fabric https://fabric.example.com:9090 --observe-name getviral-prod --observe-ca ca.pem
```

## Capabilities

- **Live run view**: tokens, calls, cost, active agents and tools, the workflow graph with the running step marked, a
  spend chart, plain-language trace, sleeps and pauses with their wake times, autonomy ladders.
- **Incidents**: looping calls and answers, failing tools, no progress, figures with no source, long pauses, budgets.
  Thresholds in a file beside the script, never in it. → [Incidents and rules](Observability-Incidents-and-Rules.md)
- **Many processes**: a Processes page (online, catching up, offline, backlog, loss) and one tab per process and workflow.
  → [Fabric server](Fabric-Server.md)
- **Controls**: pause, resume, suspend, stop; admin only, confirm dialog, CSRF, audited, delivered to remote processes as
  signed commands. → [Controls, secrets and security](Observability-Controls-and-Security.md)
- **Secrets** (embedded): list with fingerprints and used-by; replace a value with the password re-entered.
- **Central configuration**: an admin sets the inspect rules and the price table from Fabric's Configuration page.
  → [Fabric server](Fabric-Server.md#configuration-page)
- **Never loses events**: a local on-disk outbox; a Fabric that is down or restarted affects nothing and catches up.
- **OpenTelemetry**: OTLP/JSON to Fabric and, optionally, any OTLP endpoint.
- **Privacy**: `--observe-detail full|truncated|metadata`; the incident rules work at every level.
- **Cost** from your price file (`flow.prices`, `prices.properties`, `--prices`, `LOOM_PRICES`).

## Try it with no model and no key

`./fabric-sim.sh` at the repository root starts a Fabric server and simulated processes that trigger every incident.
→ [fabric-sim](../src/examples/fabric-sim/README.md)

## Pages

| Page | Covers |
|---|---|
| [Observability guide](../docs/OBSERVABILITY.md) | The full overview: concepts, how it works, detail levels, cost, embedded vs Fabric, limits |
| [**Deployment guide**](../docs/OBSERVABILITY_DEPLOYMENT.md) | Setting it up for production, step by step, with a checklist |
| [Fabric server](Fabric-Server.md) | Install, clients and keys, TLS, retention, the Processes page, packaging, troubleshooting |
| [Incidents and rules](Observability-Incidents-and-Rules.md) | Every incident, defaults, the `flow.inspect` file, `weave observe rules` |
| [Controls, secrets and security](Observability-Controls-and-Security.md) | Admin password, sign-in protection, controls, secrets rotation, audit, threat notes |
| [Reference](Observability-Reference.md) | Every option, command, route, wire message and record attribute |
