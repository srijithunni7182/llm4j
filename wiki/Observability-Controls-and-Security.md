# Controls, secrets and security

[← Wiki home](Home.md) · [Observability overview](../docs/OBSERVABILITY.md)

The page can change two things, and only for a signed-in administrator: it can **pause, resume, suspend and stop a run**,
and (embedded page only) **replace the value of an existing secret**. Everything else is read-only. This page says what
each does, who may ask, and how it is protected.

## Who is the administrator

| Where | The admin password is | Set it with |
|---|---|---|
| **Embedded page** (`--observe`) | the secret `LOOM_INSPECT_ADMIN_PASSWORD` in the app's own secret store, read at each sign-in, so rotating it takes effect at once | `weave secrets set LOOM_INSPECT_ADMIN_PASSWORD --secrets keys.store` |
| **Fabric** | Fabric's own admin password, kept in its vault as a hash | `fabric init` / `fabric admin set-password` |

With **no password set, the page is read-only** and says what to set. Nothing is offered that would fail.

**Store a hash, not the password.** The stored value may be a salted PBKDF2-HMAC-SHA256 hash
(`pbkdf2-sha256$210000$<salt>$<hash>`). Signing in still takes the password; the hash itself is not accepted as one.

```bash
LOOM_INSPECT_NEW_PASSWORD='a long password' weave observe hash-password     # prints the pbkdf2-sha256$… line
weave secrets set LOOM_INSPECT_ADMIN_PASSWORD --secrets keys.store           # paste that line
```

A plain password still works, so an existing setup keeps working. Fabric stores a hash itself.

## Signing in

- Sign-in is a JSON `POST` with the password; the answer sets a session cookie (**HttpOnly**, **SameSite=Strict**, `Secure`
  when served over TLS) and returns a CSRF token. Sessions last 12 hours; at most 100 are kept.
- **Wrong guesses are slowed**, per address (each wrong guess from the third waits longer, up to a minute, forgotten after
  15 minutes of quiet) and **globally** (50 wrong guesses anywhere within a minute make every sign-in wait out that
  minute, because a guess spread over many addresses is still a guess). The cost: an attacker can delay the administrator
  for up to a minute. A guess that cannot be slowed is worse.
- Comparison is constant-time. A form post or a request from another site is refused.

## Pause, resume, suspend, stop

| Action | What happens | Reversible |
|---|---|---|
| **Pause** | The run holds **before its next step** and keeps its thread. The page shows *held by operator* | **Resume** carries on exactly where it stopped |
| **Suspend** | The run ends cleanly at its next step and **frees its thread**, as a suspension with reason `OPERATOR` and no wake time. Needs a journal | `weave resume <run dir>` |
| **Stop** | The run ends at its next step for good | No |

Each needs a confirm dialog, the session, the CSRF token (`X-CSRF-Token`) and a same-origin request.

**When it takes effect.** A request is checked at the start of each step. A model call or tool call already under way
**finishes first**, so a long single step delays it. A stop or suspend stays pending until the run ends; a request that
arrives when no run is active is dropped, so it can never hit a later run. Pause persists until resumed.

**Embedded**, the request goes straight to the run. **Through Fabric**, it becomes a command (id, run, action, who, 60-second
expiry) in that process's queue. The process polls `POST /loom-inspect/v1/commands` (a long-poll of up to 25 s, signed like
every request); the server's answer is signed with that process's key and names the process, and the process refuses an
answer that is unsigned, for another name, expired or already seen. The run decides whether the command still fits, and the
process posts the result back. A command not collected within 60 seconds is dropped, so a process that was offline never
wakes up to an old "stop". Fabric only offers controls for a process that is **online**.

Everything is recorded: the run shows `loom.control` events (*requested*, *held*, *resumed*, *cancelled*, *applied*), and the
audit log has the request and, in Fabric, the process's result.

## Secrets (embedded only)

The **Secrets** tab lists the secrets of the store the run itself uses, so the page and the run agree about which keys exist:

| Column | |
|---|---|
| Name | the secret's name |
| Fingerprint | the first bytes of a SHA-256 of the value, like `3b1e…90`: enough to tell which value is live after a rotation, not to learn it |
| Allowed hosts | where the secret may be sent, or *any host* |
| Updated | when the value was last set |
| Used by | read from the script alone: `secret.NAME` in a `provider` or `tool`, mapped to the agents whose `model:` names the provider or whose `tools:` lists the tool |

A name the script uses but the store lacks is shown as *not in the store*.

**Replace** swaps the value of a secret that already exists. You type the new value **and the admin password again**: a
stolen session cookie alone cannot replace a key, and wrong passwords there count against the same lockout. Allowed hosts
are kept. **Nothing can be added, removed or re-pointed from the page.** A provider key applies from the next request, a
tool option from the next run. A read-only store answers that it is read-only.

Fabric has no secrets page: values never leave a process, and a server does not hold the process's store.

## Audit log

One JSON line per sign-in outcome, control request, secret replacement and configuration change (and, in Fabric, per command result): who, from
which address, what, and the outcome. File: `loom-inspect-audit.log` in the working directory for an embedded page
(`-Dloom.inspect.audit=…` moves it), `<data>/audit.log` in Fabric. **Never** a password, token or secret value.

## What never leaves the process

- Secret values: not in a record, a response, an audit line, a log line or an error message (a store failure is reported
  as "the store could not save it").
- Prompt, answer and tool text at `--observe-detail metadata`; the first 500 characters at the default.
- The client key: it lives in the transport and is omitted from `toString`.

A test runs a real workflow at `metadata` through a real Fabric with planted text and searches the outbox, the history, the
audit log, the page, the graph and the console for it, for the client key and for the admin password. None is found.

## Threat notes

| Threat | Control |
|---|---|
| Someone reports as another process | Key per process; the message must name the client it is signed for |
| Replay of a captured batch or command | 5-minute signature window; commands expire in 60 s and carry unique ids that the process remembers |
| A man in the middle orders a stop | The command answer is signed with the process's key and names the process |
| Cross-site request | SameSite=Strict cookie, CSRF token, same-origin check, JSON-only sign-in |
| Password guessing | Per-address and global slowing, constant-time compare, optional hashed storage |
| A revoked key | Takes effect within a second, with no restart |
| A public server without TLS | Refused at start-up unless `--trust-proxy` |
| A runaway client | Per-client rate limit (`429` + `Retry-After`), 16 MB body cap |

Not built: per-user accounts and roles (one admin password), interrupting a step already in progress, a "test and save"
for rotated secrets.

## See also

[Observability](../docs/OBSERVABILITY.md) · [Fabric server](Fabric-Server.md) ·
[Reference](Observability-Reference.md) · [Security guide](../SECURITY.md)
