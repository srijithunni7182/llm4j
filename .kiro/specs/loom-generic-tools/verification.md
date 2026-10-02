# Verification Plan

The work is done when every check below passes, in automated tests unless marked *live*. How the checks are
built, the layers they run at, the seams they need, and the done criteria are in
[test-strategy.md](test-strategy.md), which also maps every requirement to its checks. The procedure for
proving the work finished, with commands, evidence and sign-off, is
[completion-verification.md](completion-verification.md).

Unless a check says otherwise, it uses:

| Stand-in for | What |
|---|---|
| Models | scripted mock `LLMClient`s that make the tool calls the check needs |
| HTTP services | MockWebServer (loopback, so the tools are configured for it as described in R3.2) |
| SMTP | GreenMail, or `outbox` mode |
| Databases | H2 in PostgreSQL mode |
| Files | JUnit `@TempDir` |
| Processes | small, fixed programs from the JDK's `bin/` or `sh` scripts written to the temp dir |
| Crashes | `FaultJournal`: a journal that throws after the Nth `put`, or an executor abandoned mid-step |
| DNS and time | `StubResolver` and a recording `Sleeper`/`Clock` (test-strategy §3), so rebinding and `Retry-After` are tested without real lookups or waits |
| SMTP failures | a scripted SMTP socket server that drops, rejects or stalls at a chosen stage |

## V1: Shared foundations (R1)

| # | Check |
|---|---|
| V1.1 | The six kinds appear in `ToolFactory.kinds()`. A script using each with valid options loads with no problems. |
| V1.2 | For each kind: a missing required option, an unknown option, and a wrong-typed value (`timeout: soon`, `max_rows: 0`) are each a load error naming the tool, option and line. |
| V1.3 | Each secret option written as a literal is a load error that suggests `env.NAME`. An unset variable is a load error naming it. |
| V1.4 | `"header.X-Trace-Id": "abc"` parses on `http` and `webhook`. On `file` it is an unknown-option error. `header.Authorization: "literal"` and `"header.X-Api-Key": "literal"` are errors; `env.NAME` is accepted. |
| V1.5 | A secret never appears: for each kind, call with a mock that echoes the request back in its response body and in an error, and assert the returned text, the audit events, the trace events and the journal contain no secret value, nor its URL-encoded or Base64 form. |
| V1.6 | A tool call that throws inside the kind returns an `Error:` text, not an exception. |
| V1.7 | Every kind honours `timeout` (a mock that never answers gives an `Error:` within the limit) and truncates a large result with a marker naming the bytes cut. |
| V1.8 | `description:` appears after the generated text. The generated description of a `file` tool in `read` mode doesn't mention `write`. |
| V1.9 | The existing suites (parser, tools, approvals, budgets, resume) pass unchanged. Existing kinds accept `description:`. |
| V1.10 | `pii: mask` on an agent masks personal data in a tool result from each read-only kind. |
| V1.11 | `tool_call` and `tool_effect` audit events and trace events are recorded with target, outcome and millis, and contain no body, address list or query result. |
| V1.13 | A list or an object given where text is expected is refused, not sent as `[a, b]`; a number is read as text (R1.12). |
| V1.12 | No local deletion: `file` refuses `action: delete` and doesn't list it; no kind removes or truncates a local file; `sql` makes no change (also V9.4). `http` `DELETE` is refused unless `methods` lists it. |

## V2: Effect journal (R2)

| # | Check |
|---|---|
| V2.1 | A side-effect call writes `pending` then `done` under the documented key. |
| V2.2 | **Replay.** Run a workflow with a delegate that calls `webhook`; abandon the executor after the tool call but before the delegate's result is journaled; run again with the same journal. The mock receives **one** request in total, and the agent's tool result says it was already done. |
| V2.3 | **Unknown outcome, `skip`.** Fault-inject between `pending` and `done`; on re-run the mock receives no second request, the agent is told the outcome is unknown, and `effect_unknown` is audited. |
| V2.4 | **Unknown outcome, `retry`.** As V2.3 with `on_unknown: retry`: a second request arrives. |
| V2.5 | **Idempotency.** With `idempotency: true`, both requests carry the same `Idempotency-Key`, and it equals across a resume. |
| V2.6 | Different arguments run; two identical calls in one step run twice; replaying both returns each recorded result in order. |
| V2.7 | An `Error:` result is recorded `effect_failed`, and the identical call afterwards runs again (it isn't reported as done). |
| V2.8 | Reads (`http` GET, `file` read/list/exists, `sql`) write no effect records. |
| V2.9 | Two `parallel` branches calling the same tool with the same arguments have separate records and both run. |
| V2.10 | Works with the in-memory, file and JDBC journals. A journal from before this change loads and runs. |
| V2.11 | `ApprovalGate.key` gives the same values before and after moving the hashing to `CanonicalArgs`. |

## V3: Egress policy (R3)

| # | Check |
|---|---|
| V3.1 | `http://example.com/x` is refused for a tool without `allow_http`. `http://localhost:<port>` is accepted. `https://user:pw@host/` is refused. |
| V3.2 | With a stub resolver: hosts resolving to `10.0.0.5`, `192.168.1.1`, `172.16.0.1`, `169.254.169.254`, `127.0.0.1`, `::1`, `fd00::1`, `0.0.0.0` and `::ffff:10.0.0.5` are each refused with "resolves to a private address" and no connection is made. `allow_private: true` lets them through. |
| V3.3 | **Rebinding.** A stub resolver that answers a public address first and a private one on a second lookup: the connection uses the checked address only, and the resolver is asked once per host per call. |
| V3.4 | `hosts: "*.example.com"` allows `a.example.com` and refuses `example.com` and `example.com.evil.net`. |
| V3.5 | A 302 to another host is not followed by default. With `follow_redirects: true` it is followed to an allowed host, refused to a private address, refused to a disallowed host, refused from https to http, and stops after 3. |
| V3.6 | A refusal names its rule and doesn't echo the webhook URL's path or query. |
| V3.7 | A 10 MiB response with `max_bytes: 64k` is read as at most 64 KiB (+1) and truncated with a marker. |

## V4: `webhook` (R4)

| # | Check |
|---|---|
| V4.1 | `slack`: the mock receives `{"text": "*Title*\nBody"}` with `Content-Type: application/json`; result `Sent to slack webhook (HTTP 200).` and no URL. |
| V4.2 | `discord`: body `{"content": …}`, cut at 2000 characters with a marker. `json`: `{"title", "text"}`. `teams`: an Adaptive Card envelope. |
| V4.3 | Empty text and text over 20,000 characters are refused with no request. |
| V4.4 | 429 with `Retry-After: 1` then 200: sent after one retry. A 500 three times with `retries: 2` fails with the status and an excerpt. `Retry-After: 120` fails at once. A 404 is not retried. |
| V4.5 | `url: "https://…"` literal is a load error (secret). |
| V4.6 | `"header.X-Source": "loom"` reaches the mock. |
| V4.7 | **Failure paths (design §7.2).** Connection refused and DNS failure are retried up to `retries`, then give `effect_failed` and no URL in the text. A response that never comes (`NO_RESPONSE`) is **not** retried inside the call; the record stays `pending` and a resume applies `on_unknown`. A connection dropped after the body was sent behaves the same. |
| V4.8 | 429 with `Retry-After` is waited for through the test sleeper (the sleeper is asked for the stated duration; no real wait), 5xx backs off 1 s, 2 s, …; an HTTP-date `Retry-After` works; a 3xx without `follow_redirects` is reported and not counted as sent. A 429 that gives up is `effect_failed` and the identical call can run again. |

## V5: `email` (R5)

| # | Check |
|---|---|
| V5.1 | Fixed `to`: GreenMail receives one message with the right From, To, Subject and plain-text body. The agent's `to` argument is refused (not offered in the description). |
| V5.2 | `allow_to: "*@example.com"`: `a@example.com` is accepted; `a@evil.com` is refused and the list isn't revealed; `a@example.com.evil.com` is refused. |
| V5.3 | Subject or address containing CR/LF is refused with no message sent. `max_recipients` and a 200 KB+ body are refused. |
| V5.4 | `max_per_run: 2`: the third call is refused, and after a simulated crash and resume the count still includes the earlier two. |
| V5.5 | `html: true` sends `text/html`. `attachments: true` attaches a file inside the directory and refuses `../x` and a symlink out; over 10 MiB is refused. Without `attachments: true`, `attach` isn't accepted. |
| V5.6 | `outbox: "mail/"`: an `.eml` is written, parsable, and GreenMail receives nothing. |
| V5.7 | `security: starttls` (default) refuses a server that doesn't offer it. `none` is a load error for a non-loopback host, unless `allow_insecure: true`. |
| V5.8 | A wrong password gives "authentication failed" with no password or server text. |
| V5.9 | A script with no `email` tool doesn't load any `jakarta.mail` class (checked with a class-loading probe). |
| V5.10 | `to` and `allow_to` together, or neither, are load errors. `username` without `password` is a load error. |
| V5.11 | **Failure stages (design §7.3).** Using a socket server that scripts the SMTP conversation: connect refused, STARTTLS not offered, AUTH rejected, one `RCPT TO` rejected (nobody receives the mail; the error names an index, not an address), 4xx after DATA and 5xx after DATA each end `effect_failed` with a redacted reason. |
| V5.12 | The connection dropped after `DATA` and before the final reply leaves the record `pending`, the result says delivery is unknown, and a resume follows `on_unknown` (default `skip`: nothing re-sent; `retry`: sent again). `max_per_run` counts that unknown attempt but not `effect_failed` ones. |

## V6: `http` (R6)

| # | Check |
|---|---|
| V6.1 | GET `/repos/x` → mock sees `GET /repos/x`, the fixed headers, and the auth header. Result: `HTTP 200 OK`, content type, body. |
| V6.2 | The agent can't change the host or headers. Paths `https://evil/`, `//evil`, `/a/../b`, `/x@y`, `:80/x` and a control character are refused with no request. |
| V6.3 | `allow_paths: "/repos/*"`: `/repos/a` passes, `/repos/a/b` and `/users/a` are refused; `**` crosses segments. |
| V6.4 | `query` is added with encoding. `auth_query` is added and not echoed. Both `auth_header` and `auth_query`, or `auth_value` without either, are load errors. |
| V6.5 | POST with an object body sends JSON; with a string, `text/plain`. `PUT`/`DELETE` are refused unless listed in `methods`. |
| V6.6 | An `image/png` response is refused as not text. `application/vnd.api+json` and `text/csv` are returned. |
| V6.7 | GET retries on 500 and 429 (`Retry-After`), POST doesn't unless `idempotency: true`. |
| V6.8 | GET writes no effect record; POST does, and is replayed per V2. |
| V6.9 | The host of `base_url` is checked by `NetPolicy` at load (a literal private IP is a load error). |
| V6.10 | Failure paths as for `webhook` (V4.7–V4.8): a stalled POST isn't retried and stays `pending`; a GET is retried on a dropped connection; `Retry-After` is honoured through the sleeper. |

## V7: `file` (R7)

| # | Check |
|---|---|
| V7.1 | `write` creates a file atomically (no partial file visible while writing; verified by a slow reader on a large content). A second `write` fails unless `overwrite: true`. `append` adds a newline if missing and creates the file if absent. |
| V7.2 | `read` returns content; `from_line` and `lines` select a window; a file over `max_bytes` is cut with a marker; a file with a NUL byte is refused. |
| V7.3 | Refused: `../x`, an absolute path, a symlink pointing out, a hidden file or directory (`.env`, `.loom-triggers/x`), the run journal directory and trigger store when they're inside `root`, a name outside `allow`. `list` omits hidden entries and is sorted and capped. |
| V7.4 | Modes: `write` in `read` mode and `read` in `write` mode are refused, and the description lists only the allowed actions. |
| V7.5 | `root` outside the script's directory, or a file, is a load error. |
| V7.6 | Two parallel branches appending to one file produce whole, non-interleaved lines (200 lines each). |
| V7.7 | `write`/`append` follow V2 (replay doesn't append twice). `read`/`list`/`exists` don't. |

## V8: `shell` (R8)

| # | Check |
|---|---|
| V8.1 | `allow: "echo"`: `program: "echo", args: ["a b", "$HOME", "; rm -rf /"]` prints the three arguments literally: no expansion, no second command. |
| V8.2 | A `program` not in `allow`, or given as a path (`/bin/echo`, `../x`), is refused. A name in `allow` not on the `PATH` is a load error. |
| V8.3 | `sh`, `bash`, `python3`, `env`, `xargs`, `find`, `sed` in `allow` are load errors, and accepted with `allow_interpreters: true`. |
| V8.4 | Arguments with NUL, CR or LF, more than 64 arguments, or one longer than 4,096 characters are refused. |
| V8.5 | The child's environment holds only `PATH`, `LANG`, `TZ` and `env_pass` names: a parent variable holding a marker value isn't visible to a program that prints its environment. |
| V8.6 | `cwd` outside the script's directory is a load error. The program runs in `cwd`. |
| V8.7 | A program that sleeps past `timeout` is killed, its child process too (a `sleep` grandchild is gone), and the call reports the timeout. |
| V8.8 | A program that prints 50 MB is cut at `max_output` per stream, with a marker, and memory use stays bounded. Nothing deadlocks on a full pipe. |
| V8.9 | Result shows `exit <code>` (0 and non-zero), stdout and stderr separately. |
| V8.10 | **Approval rule.** An agent using a `shell` tool with neither `approve:` for it nor `unattended: true` on the tool fails at load with the two ways out. With either, it loads. `approve: all` counts. |
| V8.11 | A call goes through the approval gate when the agent lists the tool in `approve:`: a rejection means the program doesn't run. |
| V8.12 | Follows V2 (a resumed run doesn't run the program twice). |
| V8.13 | On Windows, `use: shell` is a load error that says the kind isn't supported there (skipped, with the load check unit-tested through an injected OS name, on Linux and macOS). |

## V9: `sql` (R9)

| # | Check |
|---|---|
| V9.1 | `SELECT` with bound params returns a table, JSON or CSV as chosen. Values arrive only as bound parameters (a param `x'; DROP TABLE t; --` is returned as data). |
| V9.2 | `SqlGuard` accepts: `SELECT 1`, `select * from t where n = 'DELETE'`, `WITH a AS (SELECT 1) SELECT * FROM a`, `SELECT created_at FROM t`, a query with comments and dollar-quoted text containing `INSERT`. |
| V9.3 | `SqlGuard` refuses: `INSERT …`, `DELETE …`, `SELECT 1; SELECT 2`, `SELECT 1; DROP TABLE t`, `SELECT * INTO x FROM t`, `WITH x AS (DELETE FROM t RETURNING *) SELECT * FROM x`, `/* */ UPDATE t …`, `CALL p()`, an empty statement. Each says which rule. |
| V9.4 | The connection is read-only: with the guard bypassed in a test, a write on a database that enforces read-only connections fails. |
| V9.5 | `max_rows` cuts the result and says rows were dropped; more than 1,000 is a load error; a 5,000-character cell is cut at 1,000. |
| V9.6 | A query over `timeout` is cancelled and reported. |
| V9.7 | `schema` lists tables and columns, and one table's columns with `table`. |
| V9.8 | A URL with no driver on the classpath is a load error naming the scheme. The `url` and password never appear in errors. |
| V9.9 | `sql` writes no effect records. |

## V10: Documentation, tooling and sample (R10)

| # | Check |
|---|---|
| V10.1 | Every `loom` block in the "Generic Tools" section of the guide, read from `LOOM_GUIDE.md` itself, parses and passes the load-time checks with a fake environment; the section names all six kinds and the rules they share. |
| V10.2 | The VS Code grammar and server list the six kinds and offer their options (checked by the extension's existing tests or a scripted completion request). |
| V10.3 | `samples/digest/digest.loom` runs end to end in a test against MockWebServer with a scripted model: it collects over `http`, writes the report and state with `file`, and leaves an `.eml` in the outbox. A second run the next "day" reads the state file. |
| V10.4 | `digest-slack.loom` sends one webhook to the mock. Resuming after a crash between the report and the notification sends it once. |
| V10.5 | The digest's `schedule` block syncs (`weave schedule sync`) and `weave triggers install` prints the expected plan without `--apply`. |
| V10.6 | READMEs, `LOOM_PROMPT.md` and the gap analysis are updated. |
| V10.7 | The commands shown in the guide and the sample (`weave schedule sync`, `weave triggers install`, `weave run`) are executed through the CLI entry points in a test, with `--apply` omitted, and their flags and output match what the documents show. |

## V11: Generated and negative tests (strategy §5)

Deterministic: a fixed seed, printed on failure; 2,000 iterations by default and `-Dloom.fuzz.iterations=N`.

| # | Check |
|---|---|
| F1 | `SqlGuard` agrees with a slow reference tokenizer on every generated statement: each forbidden keyword, in each context (bare, in a string, in a comment, in an identifier, after a `;`, in dollar-quoted text), is accepted or refused exactly as the rules say. |
| F2 | For the `http` path validator and the `file` path checks: every accepted path, after normalisation, stays under its base, has no `..` segment, no scheme or host and no control character; a refused path causes no filesystem or network access (spy). Includes percent-encodings and Unicode look-alikes. |
| F3 | For `email`: generated subjects, names and addresses containing CR, LF, U+2028/2029, NEL, `<>` and quotes either are refused or produce a message that, written and parsed back, has exactly the expected headers and the recipient set that was asked for. |
| F4 | `Redactor`: a random secret, in plain, URL-encoded and Base64 forms and at a cut boundary, never survives; text without a secret is unchanged. |
| F5 | `Options`: valid durations, sizes and lists give the expected values; invalid ones give a load error and never throw. |

## V12: Hostile-model suite (strategy §6)

For each tool, a scripted model runs its attack list through `HarnessExecutor`. For **every** attack: the
result is an `Error:` or a load refusal, **and** the outside world is unchanged (zero requests on the mock,
file hashes unchanged, no process started, database rows unchanged), **and** no secret appears in the
result, trace or audit.

| # | Tool | Attacks (the test's table is the full list) |
|---|---|---|
| H1 | `webhook` | a `url` argument; a 301 to `http://169.254.169.254/`; a host resolving to a private address; a 10 MB `text`; CRLF in `title` |
| H2 | `http` | `path` of `https://evil.example/`, `//evil`, `/a/../../etc/passwd`, `/x?y=1#@evil`; a redirect to a private address; `DELETE` when unlisted; a `headers` argument |
| H3 | `email` | `to` outside `allow_to`; CRLF `Bcc:` in the subject; 500 recipients; an attachment of `../../.env` |
| H4 | `file` | `../x`, an absolute path, a symlink out, `.env`, `.loom-triggers/…`, the journal directory, a write through a `read` tool, an overwrite without `overwrite` |
| H5 | `shell` | `program` of `/bin/sh`, `sh`, `../x`; args `["; rm -rf /"]`, `["$(id)"]`, `` ["`id`"] ``, `["a\nb"]`; an output flood; an endless loop |
| H6 | `sql` | `INSERT`, `DROP`, `SELECT 1; DELETE …`, `SELECT … INTO`, a CTE containing `DELETE`, a Unicode-escaped keyword, `COPY … TO PROGRAM` |

## V13: Concurrency (strategy §7)

| # | Check |
|---|---|
| C1 | 32 threads call one `webhook` tool with distinct bodies: 32 requests and 32 effect records. (Ordinals are counted per thread, because a step runs on one thread; identical calls in the *same* step are numbered in order, as V2.6 checks, and parallel branches have different steps, as C4 checks.) |
| C2 | 8 threads × 200 appends to one file: 1,600 whole lines, none interleaved. |
| C3 | 50 threads against `email` with `max_per_run: 20`: exactly 20 messages. |
| C4 | `for each` and `parallel for each` over a list calling `file` and `webhook`: every item acted once, and a resume repeats none. |
| C5 | C1–C4 repeated 20 times under random test order. |

## Live checks (optional, need accounts)

| # | Check |
|---|---|
| L1 | A real Slack, Discord and Teams (Workflows) webhook receives a message. Confirms the `teams` envelope (the one body shape here that is an assumption). |
| L2 | A real SMTP account (STARTTLS on 587 and SSL on 465) delivers a message. |
| L3 | `http` calls GitHub's API with a token, and a second tool uses a paid API with `auth_query`. |
| L4 | `sql` against a PostgreSQL database with a read-only user, and with a writable user (the statement guard is what stops the write). |

## Results

Recorded at code SHA `fa18b98` (the code under test; later commits change only spec and evidence files). The full
evidence is in [`evidence/`](evidence/), with the gate table in [`evidence/SIGN-OFF.md`](evidence/SIGN-OFF.md).

- **Tests.** 777 in all: 379 in `ai-agent4j-tools` (the six tools, their guards and the documentation examples) and 398 in the Loom module (scripts, executor, CLI, guide, end-to-end), 0 failures, 0 errors, 1 skipped (a baseline test). Three clean builds at
  one SHA: two plain with identical counts, one in random order with 20 000 fuzz iterations (`G1-build.txt`). 240 test methods were added against the
  baseline and none removed or newly failing (`G4-regression.txt`).
- **Coverage.** `io.github.llm4j.tools` lines 95.9% (target 80%); every guard class at 92.5% branches or more (target 90%) (`G8-coverage.txt`).
- **Mutations.** 23 deliberate bugs in the guard code, each caught by the named checks (`G3-sabotage.md`).
- **Hostile-model attacks.** The V12 suite and the attacks chosen for the real runs: none had an effect; the secrets sweep found no secret value (`G9-safety.md`, `G5-runs.md`).
- **Real runs.** R1–R6 with real `weave` processes and a stand-in model and services, including `kill -9` after the Slack post and a resume that sent nothing twice (`G5-runs.md`).
- **Live checks (L1–L4).** Not run: no accounts, no model key. The Teams body shape stays unverified against a real Teams endpoint.
- **Layout.** The tools live in the `ai-agent4j-tools` library and the contracts they implement are in `ai-agent4j`; Loom adapts them. The coverage gates run in that module (`mvn verify`).
- **Not run.** `triggers install --apply` (no crontab or systemd here) and the language server in an editor.
