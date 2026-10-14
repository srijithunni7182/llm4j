# D6. Launch the application reporting to Fabric

**Goal:** the workflow runs, its events reach the server, and the process appears *online* on the Processes page.

Each process has a **name** (how it is shown, keyed and revoked: `checkout`, `getviral-prod`) and its own **client key**. Fabric never connects to a process: each process connects out to the server, so a firewall between them needs only that one direction. The key is kept in the process's **own secret store**, never on a command line.

## Do: Fabric on this computer

```
scripts/run-observed.sh --script src/main/resources/main.loom \
    --fabric http://127.0.0.1:9090 --name checkout --data ./fabric-data \
    --detail truncated -- --max-cost 0.50 --prices prices.properties
```

(Windows: `scripts\run-observed.ps1 -Script ... -Fabric http://127.0.0.1:9090 -Name checkout -Data .\fabric-data -More '--max-cost','0.50'`.) The first time, the script creates the process's secret store (next to the script, in `.loom/`, mode 700), creates the client `checkout` on the server, and puts its key straight into the store; the key is **not printed**. It then runs `weave run` with `--observe-fabric`, `--observe-name` and the store. Every later run reuses the key.

**Keep `.loom/` out of git** (add it to `.gitignore`): it holds the store and its master key.

## Do: a Fabric that someone else runs

The admin creates the client (`fabric clients add checkout --data <dir>`) and gives you the key. Pass it **on standard input**, once, from wherever you got it (not as an argument, not through the chat):

```
scripts/run-observed.sh --script main.loom --fabric https://fabric.example.com --name checkout --key-stdin < key.txt
```

For a private CA add `--ca ca.pem`; for a pinned self-signed server, run `weave run ... --observe-pin sha256:...` directly (`weave run --help` lists every `--observe-*` option). `--observe-detail metadata` keeps all text on the machine.

## Check

- The Processes page shows the name within about 5 seconds, **online**, and the run appears under it.
- Not there? `scripts/fabric-status.sh` (is the server up?), the server log `fabric-data/fabric.log`, and the process's console: a wrong key says so once and the process keeps working, delivering later from its outbox.
- Stop the server, run again, start it: the run appears (the **outbox** held its events: 256 MB or 7 days per name). `weave observe flush` delivers one by hand.

## Daemons and other commands

The same `--observe-*` options work on `run`, `resume`, `tick` and `daemon`. For a long-lived daemon put them in its service unit.

## Gate

The process is online on the Processes page, its run is listed under **Runs**, and the user opened it.
