# loom-inspect

Live observability for Loom workflows, as an add-on. Put `loom-inspect` and `loom-inspect-core` on the class path beside weave; without them `--observe` says so and nothing runs.

```bash
weave run flow.loom --observe                       # a page at http://127.0.0.1:8787, this process only
weave run flow.loom --observe --observe-linger 5m   # keep it up after the run
weave daemon triggers/ --secrets keys.store \
      --observe-fabric https://fabric.example.com:9090 --observe-name getviral-prod --observe-ca ca.pem
```

What it shows: tokens and cost per agent, who and what is active, the workflow with the running step marked, autonomy ladders, sleeps and pauses, and flagged trouble (repeated calls and answers, failing tools, no progress, ungrounded figures). How a workflow is watched lives beside it, in `flow.inspect`; the script itself is untouched.

What it can change, and only for a signed-in administrator:

- **Pause, resume, suspend, stop** a run (at its next step), embedded or through Fabric.
- **Replace the value** of an existing secret (embedded only; allowed hosts are kept; values are never shown).

Setting it up safely:

- The admin password is the secret `LOOM_INSPECT_ADMIN_PASSWORD` in the app's secret store. Store a hash rather than the password: `LOOM_INSPECT_NEW_PASSWORD='a long password' weave observe hash-password`, then `weave secrets set LOOM_INSPECT_ADMIN_PASSWORD` and paste the `pbkdf2-sha256$…` line. Signing in still takes the password. With no password set, the page is read-only and says so.
- Sign-in is throttled per address (each wrong guess from the third waits longer, up to a minute) and across all addresses (50 wrong guesses in a minute slows every sign-in). Every control request and secret replacement is written to `loom-inspect-audit.log` (never passwords, tokens or values).
- `--observe-detail metadata` keeps all prompt, answer and tool text in the process. The rules that flag trouble use hashes and counts, so they work at every level.
- Reporting to a server goes through a local outbox on disk, so a server that is down never slows a workflow.

Design notes and phase-by-phase status: `docs/loom-inspect/DESIGN.md`. The server for many processes: `../loom-fabric/README.md`.

More: [Observability guide](../../../docs/OBSERVABILITY.md) · [Fabric server](../../../wiki/Fabric-Server.md) · [Incidents and rules](../../../wiki/Observability-Incidents-and-Rules.md) · [Controls, secrets and security](../../../wiki/Observability-Controls-and-Security.md) · [Reference](../../../wiki/Observability-Reference.md) · [Try it with no model](../../examples/fabric-sim/README.md)
