# The Loom deployer skill

A coding agent can take a finished Loom workflow to a **running, watched** one, and teach you to read the pages. The skill is **`llm4j-loom-deployer`**; the workflow itself is built and tested with its sibling, `llm4j-workflow-guide`.

```
weave guide --install-skill          # installs both into .claude/skills/ of this folder
weave guide --install-skill --skill deployer
weave guide deploy                   # the map; weave guide d1 ... d8 print the chapters
```

## What it does with you

| Stage | What happens |
|---|---|
| 1. The shape | one page for one run (`--observe`), or a Fabric for many processes (`--observe-fabric`), or both; written in your README |
| 2. Security | `weave audit`; each agent that can read untrusted content, reach private data and send something out is split or **accepted by you in writing, with a reason**; tools declare what they reach; keys in a store |
| 3. Rules, windows, prices | the `<script>.inspect` rules file, each model's context window, a price table; checked with `weave observe rules` |
| 4. Its own page | the application runs with a live page on `127.0.0.1:8787` |
| 5. Fabric | the server is brought up (data directory, admin password, health check), on this computer; TLS or a proxy for anything beyond it, only after you say |
| 6. Reporting | the application runs reporting to Fabric; its client key goes straight into its own secret store and is never printed |
| 7. The pages | a tour; you find one thing on each page; what each incident means and what to do |
| 8. Operating | retention, backup, `master.key`, rotating keys, troubleshooting |

## The scripts

`fabric-up`, `fabric-status`, `fabric-down` and `run-observed` (`.sh` for macOS and Linux, `.ps1` for Windows) do the launching, so the agent does not improvise a server with shell one-liners. Each says what it did, and each is safe to run twice. They never take a password or key on a command line, never print one, and refuse to listen beyond this computer without TLS or a proxy.

```
scripts/fabric-up.sh --data ./fabric-data --port 9090 --generate-password
scripts/run-observed.sh --script flow.loom --fabric http://127.0.0.1:9090 --name checkout --data ./fabric-data
scripts/fabric-status.sh --data ./fabric-data --port 9090
scripts/fabric-down.sh --data ./fabric-data
```

## The rules the skill keeps

- A secret never goes on a command line, in a printed line or in the chat.
- It asks first before opening a port beyond this computer, accepting the lethal trifecta, changing the admin password, revoking a key or deleting a data directory.
- You decide what counts as trouble, which agents may hold the trifecta, who may sign in and how much text may leave the machine.
- It says what the page cannot tell you: a green page is not a review.

See also [Fabric](Fabric-Server.md), [Observability](Observability.md), [Incidents and rules](Observability-Incidents-and-Rules.md) and [how the skills are published](../docs/PUBLISHING_SKILL.md).
