# D8. Operate it, and fix what goes wrong

**Goal:** the deployment survives restarts and mistakes, and the user knows what to back up and how to rotate a key.

## What to keep

| | |
|---|---|
| **History** | `<data>/history/<client>/<day>.jsonl`. 30 days and 2 GB by default (`--retention-days`, `--retention-size`); the oldest day goes first |
| **Back up** | `<data>` (history, `config/`, `vault.json`, `audit.log`) and, **separately**, `master.key` |
| **Restart** | safe at any time: the page is rebuilt from history; processes catch up from their outboxes |
| **Load** | the server runs 64 threads and rate-limits each client to about 100 requests a second; hundreds of processes are comfortable |
| **A run's detail** | a run keeps the records it was built from, up to 50,000, to show it as it was; past that the oldest drop and a past view says it is partial |

## Keys

- **Rotate a process's client key:** `fabric clients revoke NAME --data DIR`, then `run-observed` again (it makes a new client and stores the new key). The old key is refused at once.
- **Change the admin password:** `LOOM_FABRIC_ADMIN_PASSWORD='...' java -jar fabric.jar admin set-password --data DIR` (ends nothing already signed in: restart to end sessions). Ask the user first.
- **A provider key:** `weave secrets set NAME --stdin --secrets store --secrets-key-file key`.

## Troubleshooting

| Symptom | Likely cause and what to do |
|---|---|
| `--observe` says it needs the add-on | the loom-inspect jars are not on weave's class path: use `run-observed` (it adds them) or add both jars |
| The process never shows up on Processes | the server is down (`fabric-status`), the name or key is wrong (the process console says so once; it keeps running), a proxy blocks the long-poll (keep its timeout above 30 s), or a private CA is missing (`--observe-ca`) |
| Offline for a while, then everything arrives | normal: the outbox held it; the page restores in order |
| The page is read-only | no admin password is set (embedded) or you are not signed in |
| A gauge is a hatched bar | no window is known for that agent's model (chapter 3) |
| Cost shows zero | no price for the model (chapter 3) |
| `fabric-up` refuses | an unsafe bind (chapter 5), or no admin password the first time |
| Controls (pause, stop) do nothing | they apply **between steps** and only to a process that is online; suspend needs the run to have a journal (`--journal`) |
| The page keeps moving | press **Paused** (top right) to read; **Live** to follow |

## A production checklist

- [ ] TLS or a trusted proxy in front; the data directory on persistent storage
- [ ] `master.key` moved away from the vault, and backed up separately
- [ ] retention chosen; the server runs as a service that restarts it
- [ ] each process has its own name and client key; keys are in secret stores
- [ ] rules, windows and prices set (chapter 3); the trifecta decisions in writing (chapter 2)
- [ ] an admin knows how to rotate a key and change the password
- [ ] the people who will look at the pages have been shown them (chapter 7)

## Gate

The checklist is ticked, or each open item is written down with who owns it.
