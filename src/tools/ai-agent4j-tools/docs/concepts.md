# Concepts shared by every tool

[← all tools](README.md)

## What a tool is, and who calls it

A tool here is an ordinary `io.github.llm4j.agent.Tool`: a name, a description that tells the model its arguments, and
`execute(Map<String, Object>)` returning text. Everything is built for the case where **the model chooses the arguments**,
so the options you set (the URL, the allowed programs, the root directory) are the fence, and the model works inside it.

- **A tool never throws at the agent.** A refused or failed call comes back as text starting `Error:`.
- **Text means text.** A list or an object where text is expected is refused rather than sent as `[a, b]`; a number or a
  boolean is read as its text.
- **Limits.** Every call has a timeout (default 15 s) and a cap on what it returns (default 64 KiB, cut with a marker that
  says how much).

## Options

`kind.create(name, options, baseDir)` takes `Map<String, String>`. Values are strings: numbers (`"3"`), booleans (`"true"`),
durations (`500ms`, `20s`, `2m`) and sizes (`64k`, `1m`). Lists are comma-separated (`"a.com, b.com"`). Fixed request headers
are written `header.Name`, for example `header.X-Trace-Id`.

- `kind.required()`, `optional()` and `prefixes()` say what a kind accepts.
- `kind.check(options, baseDir)` returns what is wrong with them, or `null`, and touches neither the network nor the files.
  A missing option, an unknown option or a value out of range is an error naming the option.
- Relative paths (`root`, `outbox`, `cwd`, attachments) are resolved against `baseDir` and may not leave it.

### Secrets

`kind.secrets()` lists the options that hold credentials (`url` for `webhook` and `sql`, `password`, `auth_value`), and any
header that looks like one (`Authorization`, `…-Key`, `…-Token`, `Cookie`, `…Secret`, `…Password`) counts too. Read them from
your environment or secret store; do not put them in source. A host can enforce that (Loom does: a literal is a load error).

Every string a tool returns is scrubbed of its secrets: the plain value, the URL-encoded form and the Base64 form. A secret never
appears in a result, an error, a trace or an audit event. Secrets shorter than 4 characters are refused as not being credentials.

## Options every network tool takes

`webhook` and `http`:

| Option | Meaning |
|---|---|
| `timeout` | Per request, default `15s` |
| `retries` | Extra attempts on a 429, a 5xx, or a failure that sent nothing (default 2 for `webhook`, 2 for `http` GET; at most 5) |
| `idempotency` | `true` sends an `Idempotency-Key` header, the same on every attempt of a call |
| `on_unknown` | `skip` (default) or `retry`: see below |
| `hosts` | Further limits the hosts the tool may reach (`a.example.com, *.example.org`) |
| `allow_http` | Permit `http://` (always allowed for `localhost`) |
| `allow_private` | Permit private and loopback addresses; cloud metadata stays refused unless this is set |
| `header.*` | Fixed request headers |

### Where requests may go

- **https only.** `http://` needs `allow_http`, except for `localhost`. A URL may not carry a user name or password.
- **No internal addresses.** The host is resolved and each address checked: loopback, private (`10.x`, `192.168.x`,
  `172.16–31.x`), link-local (including `169.254.169.254`), CGNAT, multicast and unspecified are refused, and IPv4-mapped IPv6
  is unwrapped before the check. The connection then uses exactly the addresses that were checked, so a second lookup cannot
  return something else.
- **The model chooses no host.** A webhook's URL is fixed; an `http` tool lets the model choose only a path below `base_url`.
- **Redirects are not followed** unless `follow_redirects` (`http` only): then at most three, to allowed hosts, never from https
  to http, and credential headers are dropped when the redirect leaves the original origin.

Behind an HTTP proxy the proxy makes the final connection, so the address check can only cover what the library resolves.

## The effect journal

A message sent twice is worse than most failures. Tools that change something (`webhook`, `email`, `shell`, `file` writes and
appends, `http` requests other than GET) implement `Effectful`, and `kind.create(...)` wraps them in an `EffectTool` that
records each call in an `EffectJournal`:

1. before the call, `effect_pending`;
2. after it, `effect_done` (with the result) or `effect_failed` (with the reason).

Keys look like `<step>#effect:<tool>:<hash of the arguments>#<n>`, so the same call in the same place of a re-run finds its
record. When a run is repeated on the same journal:

- a call that finished returns what it returned and is **not made again** (the result says it was already done);
- a call that failed provably before acting may be tried again;
- a call left `pending` (the process died between acting and recording) follows `on_unknown`: `skip` (default) does not
  repeat it, because a missed notification shows up and a second one to a whole team cannot be taken back; `retry` repeats it.
  With `idempotency`, a receiver that honours `Idempotency-Key` removes the duplicate, so these tools always retry.
- reads (`http` GET, `file` read and list, `sql`) are never journaled.

`create(name, options, baseDir)` uses a journal in memory, which prevents a duplicate within one process. For a durable
guarantee give `create(name, options, baseDir, context)` an `EffectContext` whose `journal()` is backed by a file or a
database. `EffectContext` (in `ai-agent4j`) also carries the audit and trace hooks, the current step, the clock and the sleeper
used for retries, and the paths a `file` tool must never touch (such as the journal's own file).

`max_per_run` (on `email`) is counted from the journal under a lock, so it holds across a restart and across parallel branches.

## Hosts: Java and Loom

The contracts live in `ai-agent4j` (`io.github.llm4j.agent.tool`): `ToolKind`, `Effectful`, `EffectContext`, `EffectJournal`,
`EffectPolicy` and `Outcome`. This library implements them. Loom adapts each kind to its own `ToolKind` so a script can write
`use: webhook`, and adapts its run journal to `EffectJournal`.
