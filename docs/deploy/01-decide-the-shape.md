# D1. Decide the shape

**Goal:** choose how the workflow will be watched, once, with the user.

| The user has | Use | What it needs |
|---|---|---|
| One workflow on one machine, and wants to look at it now | **Embedded**: `weave run flow.loom --observe`: the run serves its own page on `127.0.0.1:8787` while it runs | the add-on jars next to weave |
| Several processes, a daemon, or runs they want to keep and compare | **Fabric**: one server, each process reports to it with `--observe-fabric` | the Fabric jar on one host; the add-on jars on each process; a name per process |
| A monitoring stack already (Grafana, Datadog, Honeycomb) | add `--observe-otlp <url>` to send the same traces and logs there | the add-on; Fabric is then optional |

They combine. Embedded is the way to try it; Fabric is the way to keep it.

## Ask

1. Where does the workflow run (this computer, a server, several)? Who needs to look at it, and from where?
2. Must prompts and answers stay on the machine that runs the workflow? If yes, use `--observe-detail metadata`: only counts, hashes and names leave; the incident rules still work.
3. Is there a Fabric already? If so you only connect to it (chapter 6): ask for its address and who gives out client keys.
4. For Fabric: is it for this computer only (loopback), or will others reach it? Others means TLS or a reverse proxy (chapter 5), and **you ask the user before opening it**.

## Do

Write the choice into the project's README, for example:

```
Observing: Fabric on this computer (http://127.0.0.1:9090), process name "checkout", detail truncated.
```

## Gate

The user chose a shape, the README says which, and you know whether anything will be reachable from beyond this computer.
