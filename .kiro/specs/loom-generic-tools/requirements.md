# Requirements Document

## Introduction

Loom's harness (durable runs, budgets, quota pausing, schedules, guards, approvals) is generic: it is
written once and every workflow gets it. The **tools** an agent needs are not. Today a script that has to
notify a person, call a small API, keep a file, run a command or query a database needs a Java `Tool`
class (`use: class`) or an OpenAPI spec. That is where most of the per-workflow effort now goes.

This spec adds six generic tool kinds so that a long-running, unattended workflow (collect, summarise,
notify) can be written with no Java:

| Kind | For |
|---|---|
| `webhook` | Post a message to Slack, Discord, Teams or any endpoint |
| `email` | Send mail over SMTP |
| `http` | Call a REST endpoint that has no OpenAPI spec |
| `file` | Read, write, append and list files inside the script's directory |
| `shell` | Run one of an allow-listed set of programs on the box |
| `sql` | Run read-only queries on a database |

These tools have side effects on the outside world. Reading a file is harmless; sending an email a second
time, or letting a model reach an internal network address, is not. So the spec is as much about the
**guard rails** (egress policy, allow-lists, caps, no duplicate sends after a crash, no leaked secrets) as
about the tools.

The standing rules from earlier Loom specs still apply:

- **Nothing is silently ignored.** Anything the runtime can't honour is a load error that names the line.
- **Scripts stay symbolic.** These are tool kinds (`tool X { use: … }`), not new statements.
- **Secrets only come from the environment**, and never appear in output, traces, audit or errors.
- **Existing valid scripts keep working.** Every change to shared code is additive.
- **Logic lives in the framework**, with the guards, approvals, budgets and audit that already wrap tools.

**Out of scope** (each is a follow-up, not a gap in this one):

- Remote (HTTP/SSE) MCP servers, which are the better answer for hosted services such as Gmail.
- OAuth flows, and email through provider APIs (Gmail API, Microsoft Graph).
- SQL writes, DDL and stored procedures.
- Downloading binary files, and attachments other than for `email`.
- Deleting files, and any file access outside the script's directory.
- Browser automation, SMS (use `http` or `openapi` against the provider).

---

## Glossary

| Term | Meaning |
|---|---|
| **Kind** | A `use:` value in a `tool` block, implemented as a `ToolKind`. |
| **Side-effect kind** | A kind whose calls change something outside the process: `webhook`, `email`, `shell`, and `http` (non-GET), `file` (write, append). |
| **Effect journal** | Records in the run journal of each side-effect call, so a resumed run doesn't repeat it. |
| **Unknown outcome** | An effect whose attempt is journaled but whose result isn't: the process died between sending and recording. |
| **Egress policy** | The rules for which network addresses a tool may contact. |
| **Allow-list** | A comma-separated option that names everything a tool may touch (hosts, paths, programs, recipients). |

---

## Requirements

### Requirement 1: Shared foundations

**User Story:** As a script author, I want every generic tool to behave the same way about options,
secrets, limits and output, so that once I know one I know all six.

#### Acceptance Criteria

1. **Registered by default.** `webhook`, `email`, `http`, `file`, `shell` and `sql` SHALL be usable with
   `use:` in any script, with no host code. None of them is a bare built-in name in `tools:`, because each
   needs configuration.
2. **Validation at load.** Missing required options, unknown options, wrong types, values out of range and
   secrets written as literals SHALL each be a load error naming the tool, the option and the line, as for
   the existing kinds. A kind's own checks (a file that must exist, a program that must be on the `PATH`)
   SHALL run at load. No network connection is made at load.
3. **Secrets.** Options that hold credentials SHALL be accepted only as `env.NAME`. A secret's value SHALL
   NOT appear in:
   - a tool result, an error message or an exception text;
   - the trace, the audit log or the run journal;
   - the prompt sent to a model.

   Every kind SHALL scrub each resolved secret from any string it returns.
4. **Options.**
   - Values SHALL be strings, numbers, `true`/`false`, or `env.NAME`, as today.
   - Lists SHALL be written as a comma-separated string (`hosts: "a.example.com, b.example.com"`).
   - Durations SHALL be written like `timeout: 20s` or `timeout: 2m`, and sizes like `max_bytes: 64k`.
   - Every kind SHALL accept `description:` to add to what the model is told about the tool.
5. **Header options.** A tool block SHALL accept options named `header.<Name>`, and the name MAY be written
   as a quoted key (`"header.X-Trace-Id": "abc"`) so header names with hyphens work. Only kinds that take
   headers accept them. A header whose name contains `authorization`, `token`, `key`, `secret`, `cookie`
   or `password` (any case) SHALL be a secret.
6. **The model is told how to call it.** Tools have no schema; the model sees a description. Each kind
   SHALL generate a description that lists its arguments, their limits and what the result looks like, so
   a script author writes no prompt for it.
7. **Errors are results.** A failed call (bad argument, denied by policy, timeout, remote error) SHALL
   return an error text the agent can read and act on, not throw, and SHALL never include a secret.
8. **Limits.** Every kind SHALL have a timeout (default 15 s, at most 10 min) and cap what it returns to
   the model (default 64 KiB, truncated with a marker that says how much was cut).
9. **Works with what exists.** These tools SHALL be usable in `tools:`, `approve:`, `guard { pii }` and
   budgets exactly as the existing kinds are. A tool's result is content, so `pii: mask` applies to it.
10. **Audit and trace.** Each call SHALL add an audit event `tool_effect` (for side-effect kinds) or
    `tool_call` (for the others), and a trace event, with the tool, kind, target and outcome. A target is
    a host, a path, a program name or a recipient *count*, never a body, a message, a query result or an
    address list. The `pii` guard's masking rules apply to anything that could hold personal data.
11. **No local deletions in v1.** No kind SHALL delete or truncate local files, and `sql` SHALL NOT change
    data. (`http` can send a `DELETE` request to a remote service only when its `methods` lists it.)
12. **Text arguments are text.** Where an argument is text (`text`, `title`, `subject`, `body`, `path`, `content`,
    `action`, `program`, `sql`, …), a string, a number or a boolean SHALL be accepted (a number is read as its
    text), and a list or an object SHALL be refused with "<name> must be text". A list or an object SHALL never
    be turned into text such as `[a, b]` and sent.

---

### Requirement 2: No duplicate side effects after a crash

**User Story:** As someone running a nightly digest, I want a crash and resume never to send the same
message twice, or to lose it silently.

#### Acceptance Criteria

1. **Effect journal.** Every call to a side-effect kind SHALL be recorded in the run journal under
   `<step>#effect:<tool>:<12 hex of sha-256 of tool + canonical arguments>#<n>`, where `n` counts calls
   with identical arguments in the same attempt of the step. The record is written **before** the call
   (`pending`) and completed **after** it (`done` with the result text).
2. **Replay.** When a step is executed again (after a crash, or a resume) and a `done` record exists for the
   same key, the tool SHALL return the recorded result and SHALL NOT act again. The result text SHALL say
   it was already done, so the agent doesn't repeat it.
3. **Unknown outcome.** When a `pending` record exists with no `done`:
   - with `on_unknown: skip` (the default), the call SHALL NOT be repeated, and the tool SHALL say the earlier
     outcome is unknown;
   - with `on_unknown: retry`, it SHALL be repeated;
   - `http` and `webhook` with `idempotency: true` SHALL always retry, sending the same `Idempotency-Key`
     header, so a receiver that supports it removes the duplicate.

   Each case SHALL be audited as `effect_unknown`.
4. **Different calls are different.** A call with different arguments SHALL run, and two calls with
   identical arguments in the same step SHALL run twice (`n` differs) unless replaying.
5. **Reads aren't journaled.** `http` GET, `file` reads and listings, and `sql` SHALL NOT write effect
   records.
6. **Not a new pause.** An effect record SHALL NOT suspend a run, ask a person, or change budgets. It works
   with the in-memory journal too, but a duplicate is only prevented across a restart when the journal is
   durable.
7. **Compatible.** Journals written before this spec, and runs that use none of these kinds, SHALL be
   unaffected.

---

### Requirement 3: Egress policy for `webhook` and `http`

**User Story:** As an operator, I want a model that has been prompt-injected to be unable to reach my
internal network, or to send data to arbitrary hosts.

#### Acceptance Criteria

1. **HTTPS only.** URLs SHALL use `https`. `http` SHALL be allowed only for `localhost`, `127.0.0.1` and
   `::1`, or when the tool sets `allow_http: true`. A URL SHALL NOT carry a user name or password.
2. **No internal addresses.** Before connecting, and again for every redirect, the host SHALL be resolved
   and the connection refused if any resolved address is loopback, private (RFC 1918, ULA), link-local
   (including the cloud metadata address 169.254.169.254), unspecified or multicast. `allow_private: true`
   SHALL turn this off for that tool. Loopback SHALL also be allowed when the configured host is itself
   `localhost` or a loopback literal.
3. **Allow-list.** `hosts: "a, b"` SHALL limit a tool to those host names (an exact name, or `*.example.com`
   for subdomains). For `http` the host of `base_url` is always allowed. The agent SHALL NOT be able to
   choose a host: for `webhook` the URL is fixed; for `http` the agent supplies only a path.
4. **Redirects.** Redirects SHALL NOT be followed unless `follow_redirects: true`, and then only to an
   allowed host, at most 3 times, and never from `https` to `http`.
5. **Sizes and time.** Requests SHALL time out (`timeout`), and responses SHALL be read only up to `max_bytes`.
6. **Errors don't leak.** A refusal SHALL say which rule refused it ("host resolves to a private address")
   without echoing the secret parts of the URL (path and query of a webhook URL).

---

### Requirement 4: `webhook`

**User Story:** As a script author, I want to post a message to Slack or Discord in one declaration.

#### Acceptance Criteria

1. **Syntax.**

   ```loom
   tool Slack   { use: webhook  url: env.SLACK_WEBHOOK  format: slack }
   tool Alerts  { use: webhook  url: env.DISCORD_HOOK   format: discord  retries: 3 }
   tool Ingest  { use: webhook  url: env.INGEST_URL     format: json  "header.X-Source": "loom" }
   ```

2. **Options.**

   | Option | Meaning |
   |---|---|
   | `url` | **Secret** (`env.NAME`): webhook URLs carry tokens. Required. |
   | `format` | `slack` (default), `discord`, `teams`, or `json` |
   | `retries` | Extra attempts on a 429 or 5xx (default 2, at most 5) |
   | `idempotency` | Send an `Idempotency-Key` header (default false) |
   | `on_unknown`, `timeout`, `hosts`, `allow_http`, `allow_private`, `description`, `header.*` | as in requirements 1–3 |

3. **Arguments.** `text` (required), `title` (optional). The kind SHALL build the body for the format:
   - `slack`: `{"text": …}`, with `title` as a bold first line.
   - `discord`: `{"content": …}`, cut to 2000 characters with a marker.
   - `teams`: a Workflows-webhook message envelope containing an Adaptive Card with the text.
   - `json`: `{"title": …, "text": …}`.

   `text` SHALL be limited to 20,000 characters, and an empty text SHALL be refused.
4. **Retries.** A 429 or 5xx SHALL be retried with exponential backoff (1 s, 2 s, …), honouring
   `Retry-After` up to 30 s. A longer `Retry-After`, and any other 4xx, SHALL fail the call with the status
   and a short excerpt of the body.
5. **Result.** `Sent to <format> webhook (HTTP <status>).` The URL is never echoed.
6. **Side-effect kind.** Requirement 2 applies.

---

### Requirement 5: `email`

**User Story:** As a script author, I want the digest emailed to the team, and I want a model not to be
able to email anyone else.

#### Acceptance Criteria

1. **Syntax.**

   ```loom
   tool Mail {
       use: email
       host: "smtp.example.com"  port: 587  security: starttls
       username: env.SMTP_USER   password: env.SMTP_PASSWORD
       from: "Loom <digest@example.com>"
       to: "team@example.com"              // fixed: the agent can't change it
   }
   tool Reply {
       use: email  host: "smtp.example.com"  username: env.SMTP_USER  password: env.SMTP_PASSWORD
       from: "support@example.com"
       allow_to: "*@example.com"           // the agent chooses, within this list
   }
   ```

2. **Options.**

   | Option | Meaning |
   |---|---|
   | `host`, `port` | SMTP server. `host` is required unless `outbox` is given. Default port 587 (`starttls`), 465 for `ssl`. |
   | `security` | `starttls` (default), `ssl`, or `none` (only for `localhost` or with `allow_insecure: true`) |
   | `username`, `password` | `password` is a **secret**. Both or neither. |
   | `from` | Required. |
   | `to` | Fixed recipients. Exactly one of `to` or `allow_to` is required. |
   | `allow_to` | Address patterns the agent may use (`a@x.com`, `*@x.com`). |
   | `cc`, `bcc` | Fixed extra recipients (optional) |
   | `max_recipients` | Default 20 |
   | `max_per_run` | Emails per run (default 20) |
   | `attachments` | `true` lets the agent attach files inside the script's directory (default false) |
   | `outbox` | A directory: messages are written there as `.eml` files and **nothing is sent**. For development and tests. |
   | `on_unknown`, `timeout`, `description` | as in requirements 1–2 |

3. **Arguments.** `subject` and `body` (required); `to` (only when `allow_to` is set); `html` (default false:
   the body is plain text); `attach` (a list of paths, only when `attachments: true`).
4. **Injection.** A subject, address or name that contains CR or LF SHALL be refused. Addresses SHALL be
   parsed strictly, and an address outside `allow_to` SHALL be refused, naming the pattern and not
   revealing the list.
5. **Limits.** Body at most 200 KB, subject at most 200 characters, attachments inside the script's
   directory (`SafePaths`) and at most 10 MiB in total. Calls beyond `max_per_run` SHALL be refused. The
   count SHALL come from the effect journal, so it survives a resume.
6. **Failures.** Authentication and connection failures SHALL be reported without the password, and
   SHALL NOT be retried inside one call.
7. **Result.** `Sent to <n> recipient(s).` No addresses are echoed.
8. **Side-effect kind.** Requirement 2 applies. The default `on_unknown` is `skip`.

---

### Requirement 6: `http`

**User Story:** As a script author, I want to call a small REST API from an agent without writing a spec
or a Java tool.

#### Acceptance Criteria

1. **Syntax.**

   ```loom
   tool Github {
       use: http
       base_url: "https://api.github.com"
       auth_header: "Authorization"  auth_value: env.GITHUB_TOKEN
       "header.Accept": "application/vnd.github+json"
       allow_paths: "/repos/*, /search/*"
   }
   tool Status { use: http  base_url: "https://status.example.com"  methods: "GET,POST" }
   ```

2. **Options.**

   | Option | Meaning |
   |---|---|
   | `base_url` | Required. The agent can only choose a path below it. |
   | `methods` | Allowed methods (default `GET`). |
   | `allow_paths` | Path patterns the agent may use (`*` within a segment, `**` across). Default: all under `base_url`. |
   | `auth_header` / `auth_query` with `auth_value` | as for `openapi`: exactly one, `auth_value` is a **secret**. |
   | `header.*` | Fixed request headers. |
   | `max_bytes` | Response cap (default 64 KiB). |
   | `follow_redirects`, `retries`, `idempotency`, `on_unknown`, `timeout`, `hosts`, `allow_http`, `allow_private`, `description` | as in requirements 1–3 |

3. **Arguments.** `path` (required; starts with `/`); `method` (default `GET`); `query` (an object of
   strings); `body` (a string, or an object sent as JSON with `Content-Type: application/json`). The agent
   SHALL NOT be able to set headers or a host. A path containing `..`, `//`, a scheme, a host, `@` or a
   control character SHALL be refused.
4. **Result.** `HTTP <status> <reason>`, the `Content-Type`, and the body. Text, JSON, XML and form types
   are returned as text. A JSON body is passed through as received (not re-formatted). Other types are
   refused with a message that says so. A truncated body says how many bytes were cut.
5. **Retries.** A GET is retried on 429 and 5xx (default 2 retries, `Retry-After` up to 30 s). Other methods
   are retried only with `idempotency: true`.
6. **Non-GET is a side effect.** `POST`, `PUT`, `PATCH` and `DELETE` SHALL follow requirement 2 and SHALL
   be journaled. GET SHALL NOT. `DELETE` SHALL be refused unless `methods` lists it.
7. **Approvals.** `requiresApproval` SHALL be false, so `approve:` on the agent is the switch, but the
   guide SHALL say to use it for any tool that allows a method other than GET.

---

### Requirement 7: `file`

**User Story:** As a script author, I want an agent to keep a small state file and write its reports,
within a directory I chose.

#### Acceptance Criteria

1. **Syntax.**

   ```loom
   tool Notes  { use: file  root: "notes/"  mode: readwrite }
   tool Reports { use: file  root: "reports/"  mode: write  allow: "*.md, *.json" }
   tool Docs   { use: file  root: "docs/"  mode: read }
   ```

2. **Options.**

   | Option | Meaning |
   |---|---|
   | `root` | A directory inside the script's directory (default `.`). Created on the first write if missing. |
   | `mode` | `read` (default), `write` (create new files and append), or `readwrite` |
   | `allow` | File name patterns (default `*.md, *.txt, *.json, *.jsonl, *.csv, *.log`) |
   | `overwrite` | Whether an existing file may be replaced (default false) |
   | `max_bytes` | Read cap (default 256 KiB); write cap is 1 MiB |
   | `description` | as in requirement 1 |

3. **Arguments.** `action` is one of `read`, `list`, `exists`, `write`, `append`, and `path` is relative to
   `root`. `read` takes `from_line` and `lines`. `write` and `append` take `content`. `list` takes
   `pattern`.
4. **Confinement.**
   - Every path SHALL pass `SafePaths` against `root`, and `root` SHALL itself lie inside the script's
     directory.
   - Symbolic links that lead outside SHALL be refused.
   - Hidden files and directories (a name starting with `.`) SHALL always be refused, and `list` SHALL omit
     them. This protects `.loom-triggers`, journals, and `.env` files.
   - The run journal directory and the trigger store SHALL be refused even when they are not hidden.
   - Names outside `allow` SHALL be refused.
5. **Writes.** `write` SHALL be atomic (a temporary file, then a move). It SHALL fail if the file exists
   and `overwrite` is false. `append` SHALL add to an existing file or create it, and SHALL add a newline
   if the file doesn't end in one. Content larger than 1 MiB is refused.
6. **Reads.** Text files only (UTF-8; a NUL byte means binary and is refused). Output above `max_bytes` is
   cut with a marker.
7. **Modes are enforced.** A `write` in `read` mode, or a `read` in `write` mode, SHALL be refused, and
   the description SHALL list only the actions the mode allows.
8. **Effects.** `write` and `append` follow requirement 2. `read`, `list` and `exists` do not.

---

### Requirement 8: `shell`

**User Story:** As a script author, I want an agent to run a few named programs on the box (`git`, `df`,
`rsync`) without being able to run anything else.

#### Acceptance Criteria

1. **Syntax.**

   ```loom
   tool Ops {
       use: shell
       allow: "git, df, du"
       cwd: "repo/"
       timeout: 30s
   }
   ```

2. **Options.**

   | Option | Meaning |
   |---|---|
   | `allow` | Program names the agent may run. Required. |
   | `cwd` | Working directory, inside the script's directory (default: the script's directory) |
   | `env_pass` | Names of environment variables to pass through (default none; `PATH`, `LANG` and `TZ` are always set) |
   | `max_output` | Cap on stdout and on stderr, each (default 64 KiB) |
   | `unattended` | Acknowledges that nobody approves calls (see 6). Default false. |
   | `allow_interpreters` | Allows shells and interpreters in `allow` (default false, see 4). |
   | `on_unknown`, `timeout`, `description` | as in requirements 1–2 |

3. **Arguments.** `program` (a name from `allow`, not a path) and `args` (a list of strings). Nothing is
   passed to a shell: the program is started directly with `args` as its argument vector, so quoting,
   `;`, `|`, `>`, `$( )` and globbing have no meaning. There is no standard input.
4. **Programs.**
   - Each name in `allow` SHALL be resolved to an absolute path at load, from the `PATH`. One that isn't
     found is a load error. The agent's `program` SHALL be matched to the name, never used as a path.
   - Shells and interpreters (`sh`, `bash`, `zsh`, `dash`, `fish`, `ksh`, `cmd`, `powershell`, `pwsh`,
     `python*`, `node`, `perl`, `ruby`, `php`, `lua`, `env`, `xargs`, `sudo`, `su`, `ssh`, `find`, `awk`,
     `sed`) SHALL be a load error in `allow` unless `allow_interpreters: true`, because each turns "one
     program" into "any program".
5. **Arguments are checked.** An argument containing NUL, CR or LF SHALL be refused. At most 64 arguments of
   at most 4,096 characters each.
6. **Approval is required, or acknowledged.** An agent that lists a `shell` tool in `tools:` SHALL also list
   it in `approve:`, unless the tool sets `unattended: true`. Otherwise the load fails with a message that
   says both ways out. (No other kind has this rule.)
7. **Isolation.**
   - The environment SHALL be cleared except for what is passed.
   - On timeout, the whole process tree SHALL be killed, and the call SHALL report the timeout.
   - Output beyond `max_output` SHALL be cut with a marker, and the process SHALL NOT be allowed to fill
     memory or the disk through it.
8. **Result.** `exit <code>`, then stdout, then stderr, each labelled and possibly cut.
9. **Side-effect kind.** Requirement 2 applies. The default `on_unknown` is `skip`.
10. **Platforms.** `shell` supports Linux and macOS. On Windows, `use: shell` SHALL be a load error that
    says so, rather than behaving differently.

---

### Requirement 9: `sql`

**User Story:** As a script author, I want an agent to look things up in a database, without any chance of
it changing the data.

#### Acceptance Criteria

1. **Syntax.**

   ```loom
   tool Db { use: sql  url: env.DB_URL  user: env.DB_USER  password: env.DB_PASSWORD  max_rows: 200 }
   ```

2. **Options.**

   | Option | Meaning |
   |---|---|
   | `url` | **Secret**: a JDBC URL (it may hold a password). Required. |
   | `user`, `password` | `password` is a **secret**; `user` may be a literal or `env.NAME`. |
   | `max_rows` | Default 100, at most 1,000 |
   | `max_bytes` | Result cap (default 64 KiB) |
   | `format` | `table` (default, a text table), `json`, or `csv` |
   | `timeout` | Query timeout (default 15 s) |
   | `description` | as in requirement 1 |

3. **Arguments.** `action` is `query` (default) or `schema`. `query` takes `sql` and `params` (a list bound
   to `?` placeholders). `schema` takes an optional `table` and returns tables and columns.
4. **Read-only, in two layers.**
   - The connection SHALL be opened read-only (`setReadOnly(true)`, autocommit on).
   - Before running, the statement SHALL be checked: comments removed, exactly one statement, starting
     with `SELECT`, `WITH` or `VALUES`, and containing none of `INSERT`, `UPDATE`, `DELETE`, `MERGE`,
     `DROP`, `ALTER`, `CREATE`, `TRUNCATE`, `GRANT`, `REVOKE`, `CALL`, `EXEC`, `INTO`, `COPY`
     outside of quoted strings and identifiers. A refusal SHALL say which rule.

   The guide SHALL say that the database user should itself be read-only, since the two layers protect
   against a mistake, not a determined attacker with a writable account.
5. **Bound values.** Values SHALL only reach the database as bound parameters, never as text in the query.
6. **Limits.** At most `max_rows` rows are fetched (`setMaxRows`) and a note says when rows were dropped.
   Long text values are cut at 1,000 characters.
7. **Drivers.** Loom SHALL NOT bundle a JDBC driver in the library. A JDBC URL whose driver isn't on the
   classpath SHALL be a load error naming the driver. The packaged `weave` JAR SHALL include PostgreSQL
   (already a dependency of the addons module).
8. **Connections.** One connection per call, closed after it. No pooling in v1.
9. **Not a side effect.** No effect records are written.

---

### Requirement 10: Documentation, tooling and a runnable sample

**User Story:** As a user, I want to learn these from the guide, get help in the editor, and see the digest
use case working end to end.

#### Acceptance Criteria

1. **Guide.** `LOOM_GUIDE.md` SHALL gain a "Generic Tools" section under Tools, with a table of the six
   kinds, each kind's options, the egress policy, the effect journal and a note on approvals.
2. **Examples checked.** A test SHALL read the "Generic Tools" section of `LOOM_GUIDE.md` itself, take every
   `loom` code block in it, and check that each one parses and passes the load-time checks (secrets from a fake
   environment). There is no separate copy of the examples to drift out of date.
3. **Editor.** The VS Code grammar and language server SHALL know the six kinds, their options, and
   `header.*` keys, for completion and hover.
4. **LLM prompt.** `LOOM_PROMPT.md` (the prompt for models that write Loom) SHALL list the six kinds.
5. **Sample.** `samples/digest/` SHALL contain a runnable script for the periodic
   collect → summarise → notify case: `core.loom` (the tools, agents and workflow), `digest.loom` (imports the
   core and adds the `schedule`), `digest-slack.loom` (adds a `webhook` notifier) and `run.sh`. It uses `http`
   to collect, `file` for state and `email` in `outbox` mode, so the first version runs with no accounts. The
   `run.sh` output shows the `weave schedule sync` and `weave triggers install` commands.
6. **READMEs.** The root and Loom READMEs SHALL mention the generic tools.
7. **Gap analysis.** The capability gap analysis SHALL be updated to record the tool-kind coverage.

---

## Non-functional requirements

1. **Dependencies.** The only new runtime dependency SHALL be an SMTP library (Angus Mail), used only by
   `email`. HTTP uses OkHttp, which ai-agent4j already brings. Everything else is the JDK.
2. **Java.** Whatever the module already targets. No preview features.
3. **Thread safety.** Kinds SHALL be safe to call from `parallel` blocks and `for each` iterations.
4. **Startup cost.** Declaring these kinds SHALL NOT load the SMTP library or a JDBC driver unless the
   script uses them.
