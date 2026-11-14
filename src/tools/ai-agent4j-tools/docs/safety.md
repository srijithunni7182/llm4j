# Safety model

[← all tools](README.md)

These tools assume the model that calls them may be wrong, manipulated by text it read, or hostile. Each tool's options set the
fence; the checks below are what keeps a call inside it. They are tested with a hostile-call suite for each tool, with generated
inputs, and by deliberately breaking each guard to see that a test notices.

| Tool | A hostile call tries to... | What stops it |
|---|---|---|
| `webhook` | send the message elsewhere; carry a header or a second line | The URL is fixed; the model supplies only `text` and a one-line `title`; the address policy refuses internal targets |
| `email` | add recipients, inject headers, mail strangers | A strict address parser, fixed `to` or an `allow_to` list, line breaks refused, one refused recipient cancels the message, `max_per_run`, `Bcc` never written to an outbox file |
| `http` | reach another host or an internal service, smuggle a path, set headers | Only a path below `base_url`; no `..`, `//`, `@`, `?`, `#` or encoded forms; methods and path patterns allow-listed; the model sets no headers; address policy; redirects off by default |
| `file` | read or write outside the directory, hidden files, the run's own journal | Paths resolved and checked after symbolic links; hidden names and control or line-break characters refused; name patterns; reserved paths; atomic writes; no delete |
| `shell` | run another program, use shell syntax, read the environment | An allow-list resolved on `PATH`; interpreters and wrappers refused; arguments passed as an array to a process with no shell; a cleared environment; output and time caps; process-tree kill |
| `sql` | write, run two statements, call a file-reading function | A read-only connection plus a token-level statement check (one `SELECT`, `WITH` or `VALUES`; no `INTO`, `CALL`, writes or dangerous functions); values only as bound parameters; row and byte caps |
| `compute` | run code through an expression, exhaust memory with a huge number | A small expression grammar with no names or calls into Java; decimal sizes and CSV rows bounded |
| `verify` | have a broken report counted as a pass, read outside its root, load an external entity | An unreadable report counts as one failure; DOCTYPE refused; paths kept under `root` |
| `scan` | make the tool echo a secret it found | Findings name a rule and a place, never the match |
| `workspace` | write through a link, edit `.git` or the tests that judge it, restore a tampered snapshot | Links never followed; a deny list `allow` cannot override; protected paths read only; snapshots checked by SHA-256 before a restore; restore refused while a sandbox serves |
| `sandbox` | run another program, read the host, reach the network, keep a secret, get a weaker isolation | Allow-listed program names with an argument list; isolation declared with no default and checked on every result; the workspace the only mount, protected paths read only; `network: none` by default, listed hosts through an egress proxy; an empty environment, secrets by name, scrubbed; bounded time and output. See [its threat model](../../../../docs/security/sandbox.md) |

## What these do not do

- **They do not replace least privilege.** Give the `sql` tool a database user that is read-only, run `shell` as a user that
  cannot harm anything, give `file` a directory with nothing precious in it. The checks guard against mistakes and tricks, not
  against a determined attacker with a powerful account.
- **Allowing a program trusts it with any arguments.** `df` and `ls` only read; `git`, `tar`, `curl` and `cp` can do much more.
- **A proxy changes the network check.** Behind an HTTP proxy the proxy makes the final connection.
- **They cannot judge content.** A webhook will happily post a harmful message the model wrote. Pair tools with approvals
  (Loom's `approve:`) where a person should look first.
- **`teams` format** is the Adaptive Card envelope Teams Workflows accept; it has not been checked against a live Teams endpoint.
