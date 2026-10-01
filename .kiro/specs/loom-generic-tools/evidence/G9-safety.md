# G9: safety review

## (a) Hostile-model suites

Automated: `HostileModelSuiteTest` (H1–H6: 66 attacks through a real executor, each must come back as `Error:` with the
outside world unchanged and no secret in what the model sees or the run reports), plus `FileToolTest`, `ShellToolTest`,
`SqlToolTest`, `HttpToolTest` and `EmailToolTest` attack tables and the generated tests F1–F4. By hand: `g5/R3-*.txt`,
with attack inputs chosen at the time (all refused). All pass.

## (b) Secrets sweep

`g5/R6-sweep.txt`: every secret option (webhook URL, http `auth_value` and a credential header, SMTP user and password,
database password, a variable passed with `env_pass`) set to a recognisable value, with calls chosen to provoke errors and
echoes (a webhook that fails and echoes its URL and headers, an API that reflects the `Authorization` header, a bad
database login, a refused SMTP connection, `printenv` of the passed variable). `grep -rE` for the values over the working
directory (the run journal, outbox, notes, database files, weave's own stdout and stderr): **no file contains a secret
value.** The results the model saw show `***` where the secret would be.

## (c) Security review of the change set

An independent pass over the whole diff against the baseline, looking for injection, path traversal, SSRF, credential
handling and data exposure. Findings and what was done:

| # | Finding | Severity | Resolution |
|---|---|---|---|
| 1 | With `follow_redirects: true`, the manual redirect loop re-sent every request header on a redirect to **another origin**. An `Authorization` or API-key header would go to whatever host a redirect named (any public host when `hosts:` is empty). OkHttp strips these itself, but the loop bypasses OkHttp's redirect handling | Medium (opt-in option) | **Fixed.** Headers that look like credentials (`Authorization`, `…-Key`, `…-Token`, `Cookie`, `…Secret`, `…Password`) are dropped when the scheme, host or port changes. Tests: `credentialsAreNotSentToAnotherOriginOnAFollowedRedirect`, `credentialsStayOnARedirectWithinTheSameOrigin`; sabotage S22 |
| 2 | The `file` tool would create a file whose *name* held a line break (even with the default `allow`), and the name was echoed in results and could be attached by `email`, which puts it in a MIME header | Medium | **Fixed** in the shared `PathGuard`: any control or line-break character in a path is refused for every tool that takes a path. Tests: `aNameWithAControlOrLineBreakCharacterIsRefusedEverywhere`, `anAttachmentWhoseNameCouldInjectAHeaderIsRefused`, the path fuzz now checks created names; sabotage S23 |
| 3 | `SafePaths` rejected a write when its root didn't exist yet (a functional bug found by tests) and would have followed a dangling symbolic link pointing out | Medium (the second) | **Fixed** earlier; tests in `SafePathsTest` |
| 4 | A list or an object given as `text` was sent as `[a, b]` | Low | **Fixed**: refused (requirement 12) |
| 5 | An allowed program is trusted with any arguments (`git` hooks, `tar`, `curl`, `cp` …) | By design | Documented in the guide's `shell` section; the approve-or-`unattended` rule forces a decision |
| 6 | The SQL guard is a second layer: functions that read files or change state inside a `SELECT` are a list, not a proof (for example `pg_stat_file`) | Low | Documented: give the tool a read-only database user. The common ones are in the deny-list; any new ones can be added |
| 7 | Behind an HTTP proxy, the address check only covers what Loom resolves; the proxy makes the final connection | Low | Documented in the guide |

Reviewed and found sound: the egress check connects to the addresses it checked (no second lookup); redirects are not
followed by default and re-checked on each hop; URLs with user information are refused; mapped IPv6 addresses are
judged as their IPv4 form (with a test using a genuine mapped `Inet6Address`); the shell tool never uses a shell, clears
the environment, and kills the process tree; the SQL tool binds every value; email addresses are parsed strictly and
the subject is single-line; secrets are only accepted from the environment and scrubbed from every string a tool returns.
