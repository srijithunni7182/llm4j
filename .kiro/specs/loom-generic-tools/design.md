# Design Document

## Overview

Six new `ToolKind`s (`webhook`, `email`, `http`, `file`, `shell`, `sql`) registered in `ToolFactory`, plus
four small pieces of shared machinery they stand on. Nothing new is needed in the grammar beyond quoted
option keys, and nothing changes for existing kinds.

```
 tool X { use: webhook … }
        │ parse (quoted keys, header.*)          load: ToolFactory.problems()            per call
        ▼                                          ▼                                       ▼
   ToolDef ───────────────► ToolKind.required/optional/prefixes/secrets/check ──► Tool ─► EffectTool ─► kind
                            + agent rules (shell needs approve|unattended)         │    (journal,      │
                                                                                   │     caps)        ▼
                                                        NamedTool (script's name) ◄┘         NetPolicy / SafePaths /
                                                                                            Redactor / Limits
```

New code lives in `io.github.llm4j.loom.tools.generic` (the kinds and helpers) with two edits to existing
classes (`ToolKind`, `ToolFactory`) and one to the executor (wrapping side-effect tools). Existing kinds
are untouched.

## 1. Foundations (R1)

### 1.1 `ToolKind` additions (all `default` methods)

| Method | Default | Use |
|---|---|---|
| `Set<String> prefixes()` | `Set.of()` | Option name prefixes the kind accepts (`header.`). |
| `boolean sideEffect()` | `false` | The factory wraps the tool in `EffectTool` (§2). |

`ToolFactory.problems` accepts an option if its key is in `required ∪ optional` **or** starts with one of
`prefixes()`. The error text for an unknown option lists both. The universal option `description` is
accepted by every kind: `ToolFactory` removes it from the map passed to `ToolKind.create` and wraps the
tool in `DescribedTool` (like `NamedTool`), which appends it to the description. Existing kinds gain it
without a change.

### 1.2 Parser: quoted option keys

`parseToolDef` reads a key with `word()`. It also accepts a `STRING_LITERAL` key, which is what makes
`"header.X-Trace-Id": "abc"` possible (a bare `X-Trace-Id` doesn't tokenise as one word because of the
hyphen). The key text is stored as written. Duplicate detection and the `use:` special case are as today.
A quoted key on a kind with no matching prefix is reported by the same unknown-option check.

### 1.3 `Options` (typed reads over `Map<String,String>`)

`Options` wraps the resolved option map for a kind and reads typed values with the error text a load check
returns:

- `duration("timeout", default, max)` accepts `500ms`, `20s`, `2m`, a bare number as seconds.
- `size("max_bytes", default, max)` accepts `64k`, `1m`, a bare number as bytes.
- `integer(name, default, min, max)`, `bool(name, default)`, `list(name)` (split on commas, trimmed, empty
  entries dropped), `choice(name, default, allowed…)`.
- `headers()` collects the `header.*` options into a `LinkedHashMap`, and `secretHeaders()` names those
  that are secrets: a header whose lower-cased name contains `authorization`, `token`, `key`, `secret`,
  `cookie` or `password`. `ToolFactory` needs the decision at load time, before values are resolved, so
  `ToolFactory.problems` applies `Options.isSecretHeader(name)` to each `header.*` key and requires
  `env.NAME` for those, exactly as for `secrets()`.

Each kind's `check(options, baseDir)` calls the same reads, so a bad value is a load error and `create`
can trust it.

### 1.4 `Redactor`

`Redactor(Collection<String> secrets)` holds the resolved secret values (values of at least 4 characters;
a shorter one is refused at load, since scrubbing it would mangle output) and replaces every occurrence in
a string with `***`, also matching their URL-encoded and Base64 forms. Every kind builds one from its
secret options at `create` and passes **every** string it returns, including exception messages, through
it. This is the single place that guarantees R1.3.

### 1.5 `Limits`

Static helpers used by every kind:

- `cut(String text, int maxBytes)` truncates on a character boundary and appends
  `… [cut: N of M bytes]`.
- `readCapped(InputStream, int maxBytes)` reads at most `maxBytes + 1` bytes so an unbounded body or
  output can't fill memory, and reports whether it was cut.

### 1.6 Description and results

Each kind builds a fixed description (its arguments, limits, and result shape), parameterised by the
options (a `file` tool in `read` mode doesn't mention `write`). The script's `description:` follows it.
Results are plain text; errors start with `Error:` so a model can tell them apart. All exceptions inside
`execute` are caught in one wrapper (`GenericTool.execute`) that converts them to an `Error:` result after
redaction, so no kind can throw a secret.

### 1.7 Audit and trace

`GenericTool` (the abstract base of the six tools) emits events through a small `Effects` interface that
the executor supplies (`audit(event, data)`, `trace(...)`), defaulting to no-ops when a tool is used
outside an executor (unit tests). Events:

| Event | When | Data |
|---|---|---|
| `tool_call` | read-only kinds, per call | tool, kind, target, outcome, millis |
| `tool_effect` | side-effect kinds, per call | tool, kind, target, outcome, millis, `replayed?` |
| `effect_unknown` | a `pending` record was found | tool, step, policy |

`target` per kind: webhook → host; email → `"<n> recipients"`; http → `METHOD host+path` (no query);
file → relative path; shell → program; sql → `"query"` / `"schema"`. Never bodies or results.

## 2. The effect journal (R2)

### 2.1 Where it sits

`HarnessExecutor.createTool` wraps a tool in `EffectTool` when its kind says `sideEffect()`. The tool
already runs on the delegate's thread (as approvals do), so `executor.currentStep()` names the step, and
`executor.getJournal()` is the run journal. `ToolFactory` returns a small record `(Tool tool, ToolKind
kind)` internally so the executor can ask the kind, or `EffectTool` is applied inside the factory with an
`EffectContext` the executor injects. The second option is chosen: `ToolFactory.create(def, env, baseDir,
EffectContext ctx)` (the old three-argument method stays and passes a no-op context), so the wrapping is in
one place and testable without an executor.

### 2.2 Keys and states

```
key    = <step>#effect:<tool>:<12 hex sha-256(tool + canonical json(args))>#<n>
states = pending  → done(result text)
```

Canonical JSON and the hash reuse `ApprovalGate.key`'s method (sorted keys), moved to a shared
`CanonicalArgs` helper that `ApprovalGate` also calls (behaviour unchanged, covered by its tests).
`n` is the count of calls with the same `(tool, hash)` **in this attempt of the step**, held in a
`ThreadLocal<Map<String,Integer>>` that `EffectTool` clears when it sees a different `currentStep()` than
last time on that thread. `parallel` branches run on their own threads and have their own step ids, so
they don't interfere.

### 2.3 Call flow

```
execute(args):
  key ← key(step, tool, args, n++)
  rec ← journal.get(key)
  if rec is done      → return "(already done in an earlier attempt) " + rec.result      [replayed]
  if rec is pending   → audit effect_unknown
                        if idempotent kind & idempotency:true   → fall through (same Idempotency-Key = hash)
                        else if on_unknown = skip → return "Error: an earlier attempt's outcome is unknown; not repeated"
                        else                       → fall through
  cap check (email max_per_run: count journal keys with prefix "<*>#effect:<tool>:" whose state is done|pending)
  journal.put(key, pending)
  result ← kind.execute(args)              // may throw → converted to Error: text, and the record is set to done(error)
  journal.put(key, done(result))
  return result
```

Journal entries reuse `RunJournal.Entry(kind, value)` with kinds `effect_pending` and `effect_done`. The
existing journals store any `Entry` (file and JDBC journals already persist `kind` and `value`), so **no
journal change is needed**. `RunJournal.all()` is used to enumerate keys for the per-run caps.

**Failed calls.** If the kind returns an `Error:` result, the record is set to `effect_failed` instead of
`effect_done`. `EffectTool` treats `effect_failed` as absent: an agent that fixes its arguments and retries
the same call must not be told "already done", and the retry reuses the same `n`. The journal has no delete,
so the failed record stays, and the audit log keeps the failure.

### 2.4 What this does not cover

A crash after the kind acted but before `journal.put(done)` leaves `pending`, which is the "unknown outcome"
case and is why `on_unknown` exists. There is no way to close that window without the receiver's help;
`Idempotency-Key` is the receiver's help, and the design says so in the guide.

## 3. Egress policy (R3): `NetPolicy`

One class used by `webhook` and `http`:

```java
record NetPolicy(boolean allowHttp, boolean allowPrivate, Set<String> hosts, boolean followRedirects)
NetPolicy.check(URI uri) → problem text or null
```

- **Scheme and userinfo** are checked on the URI.
- **Host allow-list** is matched case-insensitively; `*.example.com` matches any subdomain but not the apex.
- **Address check.** `InetAddress.getAllByName(host)`; refuse if any of `isLoopbackAddress`,
  `isAnyLocalAddress`, `isLinkLocalAddress`, `isSiteLocalAddress`, `isMulticastAddress`, or an IPv6 unique
  local (`fc00::/7`) address, and IPv4-mapped IPv6 addresses are unmapped first. Literal IPs are checked
  without a lookup.
- **DNS rebinding.** The check and the connection must use the same address. OkHttp is given a custom
  `Dns` implementation that resolves once, applies the check, and returns only the checked addresses, so
  there is no second lookup between check and connect. The same `Dns` runs for redirects, so R3.2 for
  redirects needs no extra code.
- **Redirects.** OkHttp's own follow is off. When `follow_redirects` is true, a small loop follows up to 3
  `Location` headers itself, re-running `check` and refusing an `https`→`http` downgrade.
- **Loopback exception.** If the configured host is `localhost` or a loopback literal, loopback addresses
  are allowed for that tool (this is how the tests and the sample use a local server).

`HttpSupport` builds the `OkHttpClient` (timeouts, `Dns`, no cookie jar, no cache, `retryOnConnectionFailure`
off since retries are explicit) and does capped reads, retries with `Retry-After`, and error excerpts
(first 200 characters, redacted).

## 4. The kinds

### 4.1 `webhook`

`WebhookKind extends GenericKind`. Required `url` (secret). `create` builds the URL once, runs
`NetPolicy.check`, and prepares a `BodyFormat` (`slack`, `discord`, `teams`, `json`) that maps
`(title, text)` to JSON with Jackson.

| Format | Body |
|---|---|
| `slack` | `{"text": "*title*\ntext"}` |
| `discord` | `{"content": "**title**\ntext"}`, cut to 2000 characters |
| `teams` | `{"type":"message","attachments":[{"contentType":"application/vnd.microsoft.card.adaptive","content":{"type":"AdaptiveCard","version":"1.4","body":[{"type":"TextBlock","text":…,"wrap":true}]}}]}` |
| `json` | `{"title": …, "text": …}` |

Retries are in `HttpSupport`. The idempotency key is the effect hash, so a resumed run sends the same
value. Result: `Sent to slack webhook (HTTP 200).`. The `teams` shape is a documented assumption to check
live (L1). What happens on each kind of failure, and when an attempt is retried, is in §7.2.

### 4.2 `email`

`EmailKind` uses Angus Mail (`org.eclipse.angus:angus-mail`, `jakarta.mail`). The library classes are only
touched inside `EmailSender`, which `EmailKind` loads reflectively-by-name on first use, so a script with
no email tool never loads the SMTP classes (NFR 4). `outbox` mode writes the same `MimeMessage` with
`writeTo` to `<outbox>/<timestamp>-<n>.eml` and never opens a connection.

- **Addresses.** `InternetAddress.parse(addr, strict=true)`, then a check that the local part and domain
  contain no CR/LF, no `<`/`>` beyond the parsed display form, and one address per entry. Patterns for
  `allow_to` are exact addresses or `*@domain`, matched case-insensitively.
- **Body.** `text/plain; charset=UTF-8`, or `text/html` when `html: true`. Attachments become a
  `multipart/mixed` with parts read through `SafePaths`, capped by total size.
- **Session.** `mail.smtp.starttls.required=true` for `starttls`; `mail.smtp.ssl.enable=true` for `ssl`;
  timeouts from `timeout`; `mail.smtp.ssl.checkserveridentity=true`. `none` is refused unless the host is
  loopback or `allow_insecure: true`.
- **Error redaction.** `MessagingException` text is passed through `Redactor` (the password and the
  username). Authentication failures are reported as "authentication failed" without server text.
- **Sender seam and failure stages.** `EmailKind` builds and checks the message; an `EmailSender` delivers
  it (`SmtpSender`, or `OutboxSender` for `outbox:`). Partial recipient delivery is turned off. How each
  SMTP stage fails, and which ones leave the outcome unknown, is in §7.3.

### 4.3 `http`

`HttpKind`. `create` parses `base_url`, runs `NetPolicy.check` on it (which is what makes `allow_private`
and the host rules apply to the configured host too), and compiles `allow_paths` into `PathMatcher`s
(`*` = one segment, `**` = any depth).

Request building:

1. `path` is validated (must start with `/`, no `..`, `//`, `@`, `:` before the first `/`, no control
   characters), then appended to `base_url`'s path (`HttpUrl.newBuilder().addPathSegments` so encoding is
   done by the library, not by string concatenation).
2. `query` entries are added with `addQueryParameter`. `auth_query` is added last.
3. `body`: a string is sent as `text/plain`; an object as `application/json`.
4. Headers are the `header.*` options plus `auth_header`. The agent supplies none.

Response handling: only `text/*`, `application/json`, `application/xml`, `application/*+json|+xml` and
`application/x-www-form-urlencoded` are read; anything else returns `Error: content type X isn't text`.
Read is capped by `max_bytes`.

GET is not journaled. To keep the rule in one place, `HttpKind.sideEffect()` returns true and the kind
declares `isEffect(args)` (a new default method `boolean isEffect(Map<String,Object> args)` on the base
class, `true` by default), and `EffectTool` skips the journal when it returns false. `file` uses the same
hook for `read`/`list`/`exists`. This keeps `ToolKind.sideEffect()` a per-kind answer and the per-call
answer with the kind that knows the arguments.

### 4.4 `file`

`FileKind`. `root` is resolved with `SafePaths.inside(baseDir, root)`, and every call then does
`SafePaths.inside(root, path)`.

- **Refusals** in one function `permit(Path relative, Action)`:
  1. any path segment starting with `.` → refused;
  2. the journal directory and trigger store, obtained from a `Reserved` set that the executor supplies
     through `EffectContext` (`getJournalDir()`, `getTriggerStore()` as paths, null when in-memory) → refused;
  3. the file name doesn't match `allow` → refused;
  4. mode doesn't allow the action → refused.
- **read.** `Files.newInputStream` capped by `max_bytes`; `from_line`/`lines` are applied to the decoded
  text after the cap; a NUL in the first 8 KiB means binary → refused.
- **write.** Temp file in the same directory (`Files.createTempFile`), `Files.move(ATOMIC_MOVE,
  REPLACE_EXISTING only when overwrite)`; `create_dirs` always on inside `root`.
- **append.** `Files.write(CREATE, APPEND)` under a per-path `ReentrantLock` from a `ConcurrentHashMap`, so
  parallel branches don't interleave.
- **list.** Non-recursive, sorted, hidden entries omitted, at most 500 entries with a marker.

### 4.5 `shell`

`ShellKind`.

- **Load.** Each `allow` name is resolved against the `PATH` (`Executable.find`), refused if it contains a
  path separator, refused if it's in the interpreter list without `allow_interpreters`. The resolved
  absolute path is what runs.
- **Run.** `ProcessBuilder(absolute, args…)`: `redirectInput(NULL)`, `directory(cwd)`, `environment().clear()`
  then `PATH`, `LANG`, `TZ`, plus `env_pass`. stdout and stderr are drained by two virtual threads through
  `Limits.readCapped` (so a chatty process can't block on a full pipe, and can't fill memory).
  `waitFor(timeout)`; on timeout, `ProcessHandle.descendants()` are destroyed, then the process
  (`destroyForcibly`).
- **Platform.** `ShellKind.check` refuses Windows (R8.10, §7.4).
- **Approval rule.** In `HarnessExecutor.checkToolsAndApprovals`, for each agent tool whose `ToolDef` kind
  is `shell` (declared tools only; a host-registered tool with the same name is not touched): if the tool
  isn't in `approve:` (and `approve` isn't `all`) and `unattended` isn't `true`, add a load error. The
  message: `tool Ops runs programs on this machine: add it to this agent's approve: list, or set
  unattended: true on the tool if nobody should be asked`. This uses an existing extension point (the
  checker already knows both sets).

### 4.6 `sql`

`SqlKind`. `check` verifies a driver accepts the URL: `DriverManager.getDriver(url)` (`SQLException` →
"no JDBC driver for <scheme>; add it to the classpath"). It doesn't connect.

- **Connection.** `DriverManager.getConnection(url, props)` with `props.user/password`, then
  `setReadOnly(true)`, `setAutoCommit(true)`, and `setNetworkTimeout` where the driver supports it.
- **Statement check.** `SqlGuard.check(sql)`: a small scanner (not regexes over raw text) that walks the
  string tracking `'…'`, `"…"`, `` `…` ``, `--`, `/* … */` and dollar-quoting (`$tag$…$tag$`), emits the
  tokens outside them, rejects a second statement (a `;` followed by anything but whitespace or comments),
  requires the first token to be `SELECT`, `WITH` or `VALUES`, and rejects the forbidden keywords as
  tokens. Because it works on tokens, `SELECT 'DELETE'` and a column called `created_at` pass; `SELECT …
  INTO x` and `WITH x AS (DELETE …) SELECT …` don't.
- **Run.** `PreparedStatement`, `setQueryTimeout`, `setMaxRows(max_rows + 1)` (to know whether rows were
  dropped), `setObject(i, param)` for each param. Output is rendered by `ResultTable` in the chosen
  format with cell text cut at 1,000 characters, then `Limits.cut`.
- **schema.** `DatabaseMetaData.getTables(null, null, "%", {"TABLE","VIEW"})` and `getColumns`, listed as
  `table(col type, …)`, capped at 200 tables.

## 5. Registration and wiring

- `ToolFactory()` registers the six kinds (each in its own class, not inline).
- `HarnessExecutor.createTool` passes an `EffectContext` (audit, trace, journal, current step, reserved
  paths). No other executor change except the shell approval check.
- `BUILT_INS` is unchanged.
- `PackagedLoomApp` needs nothing: the kinds are in the library. The shade config gains Angus Mail and the
  PostgreSQL driver in the `weave` JAR only (the library's own pom keeps the driver `optional`/`provided`).

## 6. Documentation and tooling (R10)

- `LOOM_GUIDE.md`: new "Generic Tools" section after "Tools, Knowledge and Approvals".
- `src/test/resources/docs/generic_tools_examples.loom`: every block from the guide;
  `DocumentedExamplesTest` gets a test for it (as for `tools_examples.loom`), with a fake environment that
  defines the `env.*` names used.
- `vscode-loom`: `server.ts` completion and hover text for `use:` values and the option lists;
  `loom.tmLanguage.json` for quoted keys in `tool` blocks.
- `LOOM_PROMPT.md`: a compact table of the kinds and the one-line rules (secrets from env, allow-lists,
  `approve` for side effects).
- `samples/digest/`: `digest.loom` and `run.sh`. Collection uses `http` against a public API. State and
  the report go to `file`, and mail goes to `email` in `outbox` mode, so it needs no account. A second
  file, `digest-slack.loom`, imports it and adds a `webhook` notifier; it needs `SLACK_WEBHOOK`, because
  Loom checks every declared tool's environment at load and so the webhook can't be made optional inside
  one script. Tests run the same script against MockWebServer instead of the public API.

## 7. Test seams, failure paths and platforms

This section holds what the [test strategy](test-strategy.md) needs from the design.

### 7.1 Seams

| Seam | Definition | Default |
|---|---|---|
| `NetPolicy.Resolver` | `interface Resolver { List<InetAddress> resolve(String host) throws UnknownHostException; }`, passed to `NetPolicy` and to the `Dns` given to OkHttp | `InetAddress.getAllByName` |
| `Sleeper`, `Clock` | The executor's existing `setSleeper`/`setClock`, handed to `HttpSupport` and `EffectTool` through `EffectContext`. Retry backoff and `Retry-After` waits go through the sleeper; elapsed-time checks through the clock. | real |
| `EffectContext` | `audit(...)`, `trace(...)`, `journal()`, `currentStep()`, `reservedPaths()`, `sleeper()`, `clock()`. Static `noop()` and (test sources) `recording()`. | the executor |
| `EmailSender` | `interface EmailSender { void send(EmailMessage m) throws EmailException; }` with `SmtpSender` (Angus Mail) and `OutboxSender` (.eml files). `EmailKind` builds the message and policy; the sender only delivers. | `SmtpSender`, or `OutboxSender` when `outbox:` is set |
| `FaultJournal` | Test-only `RunJournal` wrapper that throws `SimulatedCrash` after N `put`s. | n/a |
| Process launcher | `shell` calls `ProcessBuilder` directly; tests use real fixtures, so no seam. | n/a |

`EffectTool` and the kinds take these through constructors, never statics, so two executors in one JVM (and
parallel tests) don't share state.

### 7.2 `webhook` failure paths

| Situation | Behaviour |
|---|---|
| DNS failure, connection refused, TLS failure | `Error: couldn't reach the webhook (<class>)`, no URL. Counted as a failed attempt; **retried** like a 5xx up to `retries` (a connect failure means nothing was sent). |
| Timeout while waiting for the response | **Not retried inside the call**: the request may have been delivered. Result `Error: no answer within <t>; the message may have been delivered`, and the effect record stays `pending` so a resume applies `on_unknown` (or, with `idempotency: true`, retries with the same key). |
| Connection dropped after the body was sent | As a timeout. |
| 429 | Wait `Retry-After` (seconds or HTTP date, capped at 30 s) through the sleeper, retry up to `retries`. Over the cap: `Error: rate limited, retry after <n>s`, record `effect_failed` (nothing was delivered), so the agent or a later run may try again. |
| 5xx | Retry with backoff up to `retries`; a final failure is `effect_failed` with the status. |
| 4xx other than 429 | No retry; `effect_failed` with status and a 200-character redacted excerpt. |
| 2xx/3xx | Success (3xx is not followed unless `follow_redirects`; a redirect response without it is reported, not treated as sent). |
| Response body too large | Read to `max_bytes` and ignore the rest; the call still succeeded. |

The rule behind the table: an **attempt that provably didn't reach the server** (refused, DNS, 429, 5xx) may
be retried and ends `effect_failed` if it never succeeds; an attempt that **may have reached it** (timeout,
dropped after send) ends as `pending` so R2.3 decides.

### 7.3 `email` failure paths

SMTP is a conversation, so the stage at which it fails matters.

| Stage | Behaviour |
|---|---|
| Connect / TLS / STARTTLS refused | `Error: couldn't connect to <host>:<port>` (or "server doesn't offer STARTTLS"); nothing was sent, record `effect_failed`. No retry in the call. |
| AUTH rejected | `Error: authentication failed`; `effect_failed`. |
| `MAIL FROM` or one `RCPT TO` rejected | The message is **not** sent to the others: Angus's partial-send behaviour is turned off (`mail.smtp.sendpartial=false`). `Error: the server refused a recipient`, naming the *index* not the address. `effect_failed`. |
| `DATA` accepted, then the connection drops before the final `250` | The server may or may not have queued it. Record stays `pending`; the call returns `Error: connection lost after sending; delivery unknown`; R2.3 decides on resume (default `skip`). |
| 4xx transient reply (greylisting) | Reported as failure, `effect_failed`, no retry inside the call. A later scheduled run is the retry. |
| 5xx permanent reply after DATA | `effect_failed` with the (redacted) reply code. |
| `outbox:` write fails (disk full, permissions) | `Error: couldn't write the message`; `effect_failed`. |

`max_per_run` counts `done` and `pending` records, not `effect_failed`, so failed attempts don't use up the
allowance but an unknown one does (it may have been sent).

### 7.4 Platforms

- **Linux and macOS** are supported for all kinds.
- **`shell` on Windows is refused at load** (R8.10): program resolution, process-tree kill and the
  interpreter list differ there, and a quietly different behaviour is worse than a clear error. The check
  is `System.getProperty("os.name")` in `ShellKind.check`, and is the only OS-specific code in the six
  kinds.
- File name matching (`allow`, `allow_paths`) is case-sensitive everywhere. Paths in options use `/`.
- `file` symlink protection needs real-path resolution, which `SafePaths` already performs; on a platform
  where symlinks aren't creatable the protection is simply never exercised.


## 8. Key decisions

| Decision | Chosen | Instead of | Why |
|---|---|---|---|
| Agent picks host? | No: webhook URL fixed, http chooses only a path | Free URL | Removes the main exfiltration and SSRF route. |
| Where secret webhook URLs live | `url` is a secret option | Literal URL allowed | Slack, Discord and Teams URLs are credentials. |
| Shell | Program allow-list, argv only | `sh -c` | No quoting to get wrong. |
| Shell approval | Required or acknowledged at load | Warn | Only kind that can run arbitrary local code. |
| Email recipients | Fixed `to` or `allow_to` patterns | Free | Stops a hijacked agent mailing anyone. |
| SQL safety | Read-only connection + token check | Regex only | Two layers; token scanner handles quotes. |
| Duplicate sends | Effect journal, pending/done | At-least-once and hope | The digest use case is exactly where a crash mid-notify happens. |
| Unknown outcome default | `skip` | `retry` | A missed notification is visible; a duplicate to 200 people isn't reversible. Configurable. |
| DNS rebinding | Custom `Dns` used for connect | Check, then connect | Closes the gap between check and use. |
| SMTP library | Angus Mail | Hand-rolled SMTP | SMTP has too many corners (TLS, AUTH). |
| Grammar | Quoted keys only | Nested `headers { }` blocks | Additive; one parser tweak. |

## 9. Implementation notes

Where the code differs from the plan above, and why. The requirements are unchanged except where noted in them.

| Area | Planned | Built | Why |
|---|---|---|---|
| Side-effect detection | `ToolKind.sideEffect()` and a per-kind wrapper | A tool that implements `Effectful` (`isEffect(args)`, `policy()`, `target(args)`, `perform(args, key)`) is wrapped by `EffectTool` inside `ToolFactory.create` | The per-call answer (GET vs POST, read vs write) belongs with the tool that reads the arguments; no executor change is needed beyond passing the context |
| `ToolKind` additions | `prefixes()`, `sideEffect()` | `prefixes()`, a four-argument `create` taking the `EffectContext`, and `agentProblem(...)` for rules about how an *agent* uses a tool (the shell approval rule) | The shell rule is generic: any kind can ask for it, so the executor has no `if kind == shell` |
| `EffectContext` | audit, trace, journal, step, reserved paths, sleeper, clock | The same plus `attempt()`: a counter that changes each time a delegate starts on a thread, so a retried delegate numbers its calls afresh (a retry of a finished call is a replay, not a second send) | Needed for R2.2 across `retry` |
| Option syntax | quoted keys | Quoted keys, and a number followed directly by a unit (`20s`, `64k`) is one value; a word that is itself a key is never taken for a unit | The lexer splits `20s` into a number and a word |
| Egress DNS | A custom `Dns` that resolves once | Per hop: resolve and check through the resolver seam, then connect to exactly those addresses by giving OkHttp a `Dns` that answers only for that host (other names, such as a proxy, resolve normally) | Same guarantee, and a system proxy keeps working |
| IP literals | checked at connect | Also checked when the script loads, with no lookup | `base_url: "https://10.0.0.5"` is a load error |
| Email addresses | `InternetAddress.parse(strict)` | `MailAddress`, a small strict parser with no mail-library types | Validation happens at load, and scripts without an `email` tool must never load the mail library (checked by a test that starts a JVM and watches class loading) |
| `email` host | required | Required unless `outbox` is given | The outbox mode needs no server |
| Subject | refused if it has a line break | Trimmed first (surrounding whitespace is not significant in a header), then refused if it has a line break | Found by the generated test F3 |
| `file` confinement | `SafePaths` + `permit` | `PathGuard` (root, hidden names, the run's own files), shared with `email` attachments; `SafePaths` now handles a root that doesn't exist yet and a dangling symbolic link | Both found by tests: a write that creates its root was wrongly refused, and a dangling link pointing out would have been followed |
| `SqlGuard` | tokens outside quotes and comments | Also: a `Dialect` (dollar quoting is honoured only for PostgreSQL, CockroachDB and H2, so a database that doesn't know it can't be shown "code" as text); numbers are tokens (a statement can't start with one); `--` is a comment only when followed by whitespace; `/*! … */` is code | Wherever databases disagree, the guard reads the text as code, which can only refuse more |
| `shell` | `ShellKind` | Plus a `PATH`-resolved absolute program, `env_pass` values scrubbed from results, output drained on daemon threads | Pipes can't block a program that writes more than the cap |
| Text arguments | not specified | Lists and objects are refused where text is expected (R1.12) | Found by the hostile-model suite: a list given as a webhook's `text` was sent as `[a, b]` |
| Per-run cap | counted from the journal | Counted under a lock together with writing `pending` | Otherwise parallel branches could all pass the check (C3) |
| `file` writes | atomic | Atomic, serialised per file; the temporary file is hidden, so a listing never shows it | |
| Trace | events named in §1.7 | Also a `tool` trace type shown by `weave run --trace` | |
| Redirects and credentials | follow up to 3 hops, each checked | Also: on a redirect to a different origin, credential-looking headers are dropped before the next request | Found in the security review: a configured `Authorization` header would otherwise have been sent to whichever host the target redirected to |
| Path characters | no control characters | Any character below U+0020, U+007F, U+0085, U+2028 and U+2029 is refused in a `file` path | Found in the security review: a name with a line break could be created and then reach logs and mail headers |
| Sabotage list | S1–S21 | S1–S23 (S22 and S23 added after the review for the two rules above) | A rule with no sabotage row is not known to be tested |
| Where the code lives | `loom/ai-agent4j-loom`, package `tools.generic` | A new library, `ai-agent4j-tools` (package `io.github.llm4j.tools`): the six tools, their guards (`NetPolicy`, `SqlGuard`, `PathGuard`, `Redactor`, `RequestPath`, `MailAddress`), `SafePaths`, `CanonicalArgs` and the effect journal. Loom keeps `ToolKind`, `ToolFactory` and a small `GenericKindAdapter` | The tools are useful without Loom, and the user wants to keep adding tools. Angus Mail is optional in the library; Loom's CLI brings it |
| Journal seam | The tools read and write Loom's `RunJournal` | They use a small `EffectJournal` (get, put, all) that Loom's executor adapts its run journal to. `GenericKind.create` wraps a tool that is `Effectful` in `EffectTool`, so exactly-once holds for any host | The library cannot depend on Loom, and a plain Java user should get the same guarantee |
| Test layout | One suite in Loom | Tool-level tests moved with the code (about 370), with a script-style `Declared` helper of their own; their helpers are shared as a test jar. Tests that need the executor, the parser, the CLI or the guide stay in Loom (about 400 including the rest of Loom's suite) | Each module proves its own part; the coverage gates now run in the tools module |
| Fuzz tests | default timeout | Fuzz tests that scale with `loom.fuzz.iterations` have a 20 minute timeout; the default per-test timeout stays at 60 s | The 20 000-iteration run in G1 legitimately takes minutes |
