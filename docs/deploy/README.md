# Deploying a Loom workflow, watched: a guide for the agent that does it

You are taking a **finished, tested workflow** (a `.loom` script that passes `weave check`, has had its security reviewed, and has spend limits) and making it **run where people can watch it**: the run's own page, or a **Fabric** server that many processes report to. You also teach the developer to read what they see.

Everything you need is in this guide and in the scripts of this skill. The repository may not be here: never send the user to a repository path.

| # | Stage | Chapter (`weave guide dN`) | Gate before moving on |
|---|---|---|---|
| 1 | Decide the shape: one page, or a Fabric for many | `01-decide-the-shape.md` | the user chose, and you wrote the choice in the project README |
| 2 | Security decisions, before anything runs for real | `02-security-before-you-run.md` | every agent holding the trifecta is split or accepted in writing by the user; keys are in a store, not in files |
| 3 | The rules, the windows and the prices | `03-rules-windows-prices.md` | `weave observe rules` is clean; every model has a window and a price |
| 4 | Launch the application with its own page | `04-run-with-its-own-page.md` | the page opened and showed the run |
| 5 | Bring up a Fabric server | `05-bring-up-fabric.md` | `fabric-status` says running; the user can sign in |
| 6 | Launch the application reporting to Fabric | `06-run-reporting-to-fabric.md` | the process is *online* on the Processes page and its run appears |
| 7 | Teach the pages and the incidents | `07-read-the-pages.md` | the user found one thing on each page and knows what the incidents mean |
| 8 | Operate it: retention, backup, keys, restarts, troubleshooting | `08-operate-and-troubleshoot.md` | the checklist is ticked |

`weave guide d1` ... `weave guide d8` print the chapters; `weave guide deploy` prints this page.

## The scripts (in `scripts/` next to this skill's `SKILL.md`)

| Script (macOS and Linux `.sh`; Windows `.ps1`) | What it does |
|---|---|
| `fabric-up` | creates the data directory and admin password the first time, starts Fabric in the background, waits until it answers; does nothing if it is already running |
| `fabric-status` | running or not, where, and which processes may report (names, never keys) |
| `fabric-down` | stops it; what it holds stays on disk |
| `run-observed` | runs a workflow with observing on, embedded or reporting to Fabric; creates the client key and keeps it in the process's secret store |

They need Java 17 or newer and the jars in the skill's `bin/` (or `FABRIC_JAR`, `WEAVE_JAR`, `INSPECT_JAR`, `INSPECT_CORE_JAR` in the environment). Each says what it did. Read their output; do not guess.

## Rules for you (the agent)

- **A secret never goes on a command line, in a file the user did not ask for, or in anything you print.** That includes the admin password, a Fabric client key, a provider key and the master key. The scripts take them from the environment or standard input and store them; you never see them. If one reaches the chat, tell the user to rotate it.
- **Ask before** anything that opens a port beyond this computer, accepts the lethal trifecta for an agent, changes the admin password, revokes a client key, or deletes a data directory. The scripts refuse a non-loopback address without TLS or a proxy; that refusal is not something to work around.
- **The user decides** what counts as trouble (thresholds), which agents may hold the trifecta, and who may sign in. You propose, explain, write what they agree and run what they approve.
- **Say what you did and what you saw**, in plain words, after every script. Never tick a gate that was not checked.
- A watched run is **not** a security guarantee: say so (chapter 7).
