# Verification Plan

The work is done when every check below passes in automated tests, the gates in §Completion pass at one commit, and the evidence is committed.
Each test carries its check id as `@Tag("CC-V1.1")` so `scripts/verify_spec.py .kiro/specs/loom-invoker-credentials CC` can prove every check has a
passing test and no test has a made-up id.

Unless a check says otherwise it uses these stand-ins:

| Stand-in for | What |
|---|---|
| The downstream API | a local HTTP server (JDK `HttpServer`) that records every request's method, path and headers and answers from a script of responses (200, 401 once, 500, a body that echoes the `Authorization` header) |
| The model | the scripted `LLMClient` of the earlier suites, which calls the tool with fixed arguments |
| The host's `InvokerCredentials` | a test class that counts `get` and `invalidate` calls, can expire a token, can throw, and can be slow |
| Time | an injected `Clock` where a check involves expiry |
| Storage | `@TempDir` journal, audit and trace files |
| Two users | two `InvokerCredentials` with different tokens, on two threads, started together with a `CountDownLatch` |

The downstream API is the only network the tests touch; it runs on `localhost` (allowed over plain http by the https rule, V4.2).

## V1: The script says who the tool acts as (syntax and validation)

| # | Check |
|---|---|
| V1.1 | `auth_value: invoker.token` parses to `OptionValue` with source CALLER and name `token`; `toString()` is `invoker.token`. |
| V1.2 | `invoker.`, `invoker.a.b`, `invoker.1x` and `invoker.x y` are parse errors that quote the text and show the right form. |
| V1.3 | `invoker.x` on an option the kind does not list as late-bound (e.g. `spec:`, `serpapi api_key:`) is a load error naming the option and where `invoker.` is allowed. |
| V1.4 | `invoker.x` on `provider … api_key:` or `base_url:` is a load error ("invoker credentials are for tools"). |
| V1.5 | `invoker.x` on `header.<Name>` is refused in v1 with a message saying so. |
| V1.6 | A literal `auth_value: "Bearer abc"` is still a load error (existing rule), and the message now also offers `invoker.NAME`. |
| V1.7 | `lookupKey()` on a CALLER value throws; a test scans `src/main` for `lookupKey()` and `isReference()` call sites and fails if one is not in the reviewed list (guards a new call site that would send the name as a credential). |
| V1.8 | `weave check` on a script with `invoker.token` and no host passes, and prints that the script uses invoker credentials `token`. |
| V1.9 | `AgentIdentity` differs between `auth_value: env.X` and `auth_value: invoker.x`, and is equal between two runs of the same script. |

## V2: The credential reaches the request (per request, from the host)

| # | Check |
|---|---|
| V2.1 | A run with `InvokerCredentials.of("token", "Bearer t1")` makes the API see `Authorization: Bearer t1` on the tool's request. |
| V2.2 | The same with `auth_query:` instead of `auth_header:` (the value arrives as the query parameter). |
| V2.3 | `get` is called once per request: an agent that calls the tool three times causes three `get` calls, and the API sees the values returned each time. |
| V2.4 | **Refresh:** the test credentials return `Bearer t1` for the first call, then expire; the second call returns `Bearer t2`. The API sees t1 then t2 in the same run, with no `invalidate`. |
| V2.5 | **Rejected token:** the API answers 401 to t1 and 200 to t2. The tool calls `invalidate("token")`, calls `get` again, retries once, and the agent's observation is the 200 body. The API saw exactly two requests. |
| V2.6 | A second 401 is not retried again: the observation is the 401 error; the API saw two requests; `invalidate` was called once. |
| V2.7 | A POST that gets 401 and is retried is sent with the same body both times. |
| V2.8 | `get` returns null → the observation is `Error: credential unavailable: the invoker has no credential 'token'`; no request reaches the API. |
| V2.9 | `get` throws `SecretException("refresh token revoked")` → observation `Error: credential unavailable: refresh token revoked`; the run continues (the agent can answer without the tool). |
| V2.10 | Existing behaviour: a script with `auth_value: env.X` and the existing `executeWorkflow(name, ctx)` behave exactly as before (the existing OpenAPI and `ToolsTest` suites pass unchanged). |
| V2.11 | `OpenAPITool.builder().headerAuth("Authorization", CredentialSource)` works from Java with no Loom involved, including the 401 retry. |

## V3: The credential belongs to the run (scope and concurrency)

| # | Check |
|---|---|
| V3.1 | **Two users at once:** two executors (one per user, as supported today) run the same script at the same time with tokens `uA` and `uB`, each calling the tool and using `parallel` branches. `BRANCHES` is a static pool, so worker threads are shared between the executors. Every request carrying `uA` comes from run A and every `uB` from run B, with no mixing (checked from the API's log, 50 iterations). |
| V3.2 | **Forks:** inside `parallel` branches, a `broadcast` to three agents, a step with `timeout:`, and a tool call under `callWithTimeout`, every request carries the run's token (one test per mechanism). |
| V3.3 | A nested `call` of another workflow inherits the token. |
| V3.4 | **No leftovers:** after a run ends (normally, by exception, by timeout cancel), the `BRANCHES` pool threads and the calling thread have no invoker scope: a second run started with `InvokerCredentials.none()` on the same executor, and the same pool, fails at its start (V4.1) and its tool call sees none. Tested by running A, then B-without-credentials, 100 times. |
| V3.5 | `RunScope.capture()` / `install()` carry `step`, `locals`, `scopes` and `invoker` together: the existing step-id, local-name and budget-scope tests pass unchanged after the four sites are moved to it, and a test per site proves the invoker crosses it. |
| V3.6 | Two *concurrent top-level runs on one executor* are refused: the second `executeWorkflow` throws `IllegalStateException` naming this spec, the first run is undisturbed and finishes with its own credentials; a nested `call` and parallel branches are not refused; sequential runs on one executor work. |

## V4: Where the credential may go (safety)

| # | Check |
|---|---|
| V4.1 | A script using `invoker.token` run with `InvokerCredentials.none()` (or the old overload) fails before the first step with `MissingInvokerCredentialException`; the message names `token` and no value; no model call was made. |
| V4.2 | A invoker credential is sent to an `https://` server or `localhost`; a spec whose server is `http://example.com` makes the call fail with an error naming the rule, and nothing is sent. |
| V4.3 | The agent cannot choose the host: a tool argument `endpoint: "http://evil.test/x"` or `endpoint: "@evil.test/x"` is not found / is rejected, and the API log shows no request to another host. |
| V4.4 | A server that redirects (302) to another host does not receive the `Authorization` header (the HTTP client does not forward it across hosts; test with a second local server). |
| V4.5 | `weave audit` on a script with `invoker.token` and a tool that can POST reports the "acts as the invoker" finding at MEDIUM; with a GET-only spec, LOW; with `approve:` on every agent using the tool, the finding says it is approved. The existing finding ids are unchanged. |
| V4.6 | `invoker.*` on a tool nobody uses (no agent lists it) is reported by `weave check` as unused, like any unused tool. |

## V5: The credential is never written down (secrets sweep)

The sweep runs a full workflow with an agent that uses the tool, with the token `SECRETVALUE-9f3a…` and, separately, with a server that echoes the
`Authorization` header into its response body, into a 500 error body, and into a redirect `Location`.

| # | Check |
|---|---|
| V5.1 | The token (and the part after `Bearer `) appears in no byte of: the journal file, the trace events, the audit log, the run's output, the console, the agent's observations, `logger` output (captured appender), exceptions' messages, `toString()` of the tool and of `InvokerCredentials` wrappers. |
| V5.2 | The same when the run **suspends** (a held approval, a budget pause) and is **resumed** with a different `InvokerCredentials` (new token): the second run uses the new token; the journal contains neither. |
| V5.3 | A replayed step (resume) does not call the API again and does not call `get` for it. |
| V5.4 | The token is scrubbed from a response body that echoes it, in the 2xx and the error paths, and when it is shorter than the 6-character scrub threshold the tool refuses to use such a credential or the scrub rule is documented and tested (decision recorded in the sign-off). |
| V5.5 | A wrapper around `InvokerCredentials` used for tracing/diagnostics has `toString()` that never includes values. |
| V5.6 | `SecurityAudit` and `weave check --json` output contain credential *names* only. |

## V6: Compatibility and the executor's other invokers

| # | Check |
|---|---|
| V6.1 | The old `executeWorkflow(String, Map)` overload exists and behaves as before for scripts without `invoker.*`. |
| V6.2 | `LoomEngine` implementers outside `HarnessExecutor` compile unchanged (the new method is `default`). |
| V6.3 | `TriggerRunner`-started and scheduled runs of a script with `invoker.*` fail with V4.1's error and a pointer to the docs; a script without it is unaffected. |
| V6.4 | `weave run --invoker-env token=MY_TOKEN_VAR script.loom` builds `InvokerCredentials.of(...)` from the environment variable; an unset variable stops the run at its start naming the variable (not its value). |
| V6.5 | The full existing test suites of `ai-agent4j`, `ai-agent4j-tools` and `ai-agent4j-loom` pass with no test changed except those that assert error text for credential options. |
| V6.6 | `LoomParser` and the LSP/TextMate grammar in `vscode-loom` highlight `invoker.NAME` like `env.NAME`; completion offers `invoker.` after `auth_value:` (editor checks are covered by the existing `vscode-loom` tests, extended). |

## V7: Documentation

| # | Check |
|---|---|
| V7.1 | `LOOM_GUIDE.md` gains a "Running as the invoker" section with the web-login example and the `InvokerCredentials` implementation; the `openapi` tool table row mentions `invoker.`. |
| V7.2 | `LOOM_PROMPT.md` and `OpenAPI-Tool.md` show the syntax; `llms.txt`/`LLM_CONTEXT.md` mention the interface. |
| V7.3 | Every ```loom block that uses `invoker.` in the docs passes `weave check` (the existing doc-example test extended; it already checks `secret.` blocks). |

## Sabotage (are the tests able to fail?)

`scripts/sabotage_invoker_credentials.py` applies each change below to a copy of the source and runs the tagged tests. Each must make at least one named
check fail; a change that nothing notices is a missing test.

| # | Change | Must break |
|---|---|---|
| S1 | `RunScope.install` forgets `invoker` | V3.2 (all four mechanisms) |
| S2 | `forkWith` no longer installs the scope | V3.2 parallel |
| S3 | `finally` does not remove `invokerScope` | V3.4 |
| S4 | `get` is called once and cached in the tool | V2.3, V2.4 |
| S5 | the 401 retry is removed | V2.5 |
| S6 | the 401 retry loops | V2.6 |
| S7 | the scrub list omits the resolved value | V5.1, V5.4 |
| S8 | `lookupKey()` returns the name for CALLER | V1.7 |
| S9 | the https rule is removed | V4.2 |
| S10 | the credential is written into the journal entry | V5.1, V5.2 |
| S11 | the run-start check is removed | V4.1 |
| S12 | the fork uses an `InheritableThreadLocal` instead of explicit capture | V3.4 (pool threads keep a stale value) |

## Completion

| Gate | What |
|---|---|
| G1 build | `mvn -q verify` for `ai-agent4j`, `ai-agent4j-tools`, `ai-agent4j-loom` at one commit |
| G2 traceability | `verify_spec.py` reports every V-check with a passing test and no unknown tags |
| G3 sabotage | the table above: every row fails the named checks |
| G4 regression | the whole existing suites pass |
| G5 end to end | a 40-line demo: a small web handler (JDK `HttpServer`) with a fake OAuth login and token endpoint; two browser-like clients log in as different users, start workflows through one handler, the downstream fake API logs which user each call came from; one user's access token expires mid-run and is refreshed from a refresh token; evidence in `evidence/g5/` |
| G6 package | the Loom CLI package builds; `weave check` works on the demo script |
| G8 coverage | JaCoCo: the new classes ≥ 90% line coverage (`InvokerCredentials`, `RunScope`, the changed `OpenAPITool` paths) |
| G9 safety | the V4 and V5 suites are green and the secrets sweep is attached to the evidence |
