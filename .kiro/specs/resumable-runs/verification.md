# Verification Plan: Resumable Runs

Every criterion is a test with exact expected values. Time is controlled via an injected `Clock`
(fixed at **2026-09-27T10:00:00Z** unless stated) and a recording `Sleeper`. No test sleeps for real
beyond the scheduler poll tick (≤ 50 ms).

## 1. Rate-limit signal (ai-agent4j)

| ID | Given | Then |
|---|---|---|
| V1.1 | `Retry-After: 120` | resetAt = 10:02:00Z, scope UNKNOWN, estimated=false |
| V1.2 | `Retry-After: Sun, 27 Sep 2026 10:05:00 GMT` | resetAt = 10:05:00Z |
| V1.3 | Anthropic: tokens-remaining 0, tokens-reset `2026-09-27T10:00:45Z`, requests-remaining 12 | resetAt 10:00:45Z, scope TOKENS, limit/remaining from headers |
| V1.4 | OpenAI: `x-ratelimit-remaining-tokens: 0`, `x-ratelimit-reset-tokens: 6m0s` | resetAt 10:06:00Z, scope TOKENS |
| V1.5 | OpenAI durations `1h2m3.5s`, `120ms`, `17s` | parsed to 3723.5 s, 0.12 s, 17 s |
| V1.6 | Gemini body with `RetryInfo.retryDelay "34s"`, quotaId `…PerMinute…` | resetAt 10:00:34Z, scope REQUESTS |
| V1.7 | Gemini body quotaId `GenerateRequestsPerDayPerProjectPerModel-FreeTier`, retryDelay `"20s"` | scope DAILY_QUOTA, resetAt = 2026-09-28T07:00:00Z (midnight PDT); retryDelay ignored |
| V1.8 | V1.7 with `dailyResetZone=UTC` | resetAt 2026-09-28T00:00:00Z |
| V1.9 | 429 with no headers, no body; three in a row, then success, then 429 | fallbacks 60 s, 120 s, 240 s, then 60 s again; estimated=true |
| V1.10 | Malformed values (`Retry-After: soon`, bad JSON) | never throws while parsing; falls through to the next parser / fallback |
| V2.1 | MockWebServer: 429 `Retry-After: 5`, then 200 | one retry; Sleeper recorded 5 s ≤ wait ≤ 5.5 s; response returned |
| V2.2 | 429 `Retry-After: 3600` | no retry (server saw 1 request); `RateLimitException` with resetAt 11:00:00Z; `getRetryAfterSeconds()` = 3600 |
| V2.3 | 429 `Retry-After: 5` × 4 with maxRetries 3 | 4 requests, then `RateLimitException` |
| V2.4 | 500 responses | existing exponential backoff unchanged (existing tests pass) |
| V2.5 | GoogleProvider behind MockWebServer returning V1.7 | caller catches `RateLimitException` (not `ProviderException`) with DAILY_QUOTA |
| V2.6 | Same for Ollama and Sarvam providers | `RateLimitException` passes through |

## 2. Windowed budgets

| ID | Given | Then |
|---|---|---|
| V3.1 | `tokens 1000 per HOUR`; spend 900 at 10:10 | at 10:59:59 remaining 100; at 11:00:00 remaining 1000; `lifetimeSpent()` 900 |
| V3.2 | exhausted at 10:30 | `BudgetExceeded.resetAt()` = 11:00:00Z; lifetime budgets have `resetAt()` empty |
| V3.3 | DAY window with clock zone Asia/Kolkata | window ends at 18:30:00Z |
| V3.4 | warning fired in window 1 | fires again once in window 2 |
| V3.5 | reservation made at 10:59:59, settled at 11:00:01 | the charge lands in window 2; reserved counters return to 0; no negative values |
| V3.6 | 32 threads × 100 calls across a roll-over, limit 1000/min | per-window charged ≤ limit + in-flight estimate error; sum of windows = sum of charges |
| V3.7 | `restore(spent, at=yesterday)` on a DAY budget | remaining unchanged; `restore(spent, at=today)` reduces remaining |

## 3. Agent to harness

| ID | Given | Then |
|---|---|---|
| V4.1 | ReActAgent whose client throws `RateLimitException` on call 2 | `RateLimited` thrown with the same info; no partial result; the client saw 2 calls |
| V4.2 | Budget with SUSPEND policy + window refuses | `RateLimited(BUDGET_WINDOW)` with resetAt = window end |
| V4.3 | Lifetime budget refuses (default policy) | partial answer, exactly as the cost-budgets tests (regression) |
| V4.4 | 429 through `BudgetedLLMClient` | budget calls +1, tokens +0 |

## 4. Loom syntax

| ID | Script | Then |
|---|---|---|
| V5.1 | `rate_limits { on_limit: suspend max_wait: 24h max_resumes: 10 }` | RateLimitDef(SUSPEND, PT24H, 10) |
| V5.2 | durations `30s 15m 6h 2d` | PT30S, PT15M, PT6H, P2D |
| V5.3 | `budget { tokens: 100000 per day when_exhausted: suspend }` | BudgetDef window DAY, whenExhausted SUSPEND |
| V5.4 | `on_limit: later`, `max_wait: 0s`, `per fortnight` | ParseError naming the line |
| V5.5 | `per` and `rate_limits` used as variable names outside those blocks | parse as before |
| V5.6 | All existing parser tests and documented examples | unchanged |

## 5. Suspension (HarnessExecutor)

A three-step script `research → draft → review` over a scripted factory, with a FileRunJournal in a
temp dir.

| ID | Given | Then |
|---|---|---|
| V6.1 | `draft` throws `RateLimited(resetAt 11:00)`; durable journal; no `rate_limits` block | `executeWorkflow` throws `RunSuspended` with reason RATE_LIMIT, resumeAt 11:00:00Z, stepId of draft; journal has research's result and a `suspension` entry |
| V6.2 | resume V6.1 at 11:00 with a healthy client | research replays (0 model calls), draft and review run once each; final context equals an uninterrupted run |
| V6.3 | V6.1 with an in-memory journal, reset in 20 s | waits inline (Sleeper recorded 20 s), re-runs draft, completes |
| V6.4 | in-memory journal, reset in 1 h, default max_wait 5 min | run fails; message contains "rate limit" and "11:00" |
| V6.5 | `on_limit: fail` | `on_failure` handler runs; no suspension entry |
| V6.6 | `max_wait: 1h`, reset in 2 h, durable | fails instead of suspending |
| V6.7 | `parallel` of 3 branches: A ok, B limit resetAt 10:30, C limit resetAt 10:45 | A's result journaled; one RunSuspended at 10:45; on resume A replays, B and C run |
| V6.8 | `for each` over 5 items, item 3 hits a limit | items 1, 2, 4, 5 journaled; resume runs item 3 only |
| V6.9 | suspended 3 times with `max_resumes: 2` | third resume fails with "max_resumes" |
| V6.10 | `_run.resumes` in a payload after 2 resumes | "2"; `_run.lastSuspension.reason` = "RATE_LIMIT" |
| V6.11 | audit | exactly one `run_suspended` and one `run_resumed` per cycle with the documented fields |
| V6.12 | budget `tokens: 500 per hour when_exhausted: suspend` exhausted at 10:20 | suspends until 11:00; on resume at 11:00 the restored spend of the old window is not counted; run finishes |
| V6.13 | same with `when_exhausted: ask`; journaled human answer `yes` | on resume the budget's limit is 1000 and the run finishes |
| V6.14 | budget with no `when_exhausted` | behaves exactly as cost-budgets V6.* (regression) |
| V6.15 | Spend report after 2 suspensions | totals equal the sum of all attempts; replayed steps charged once |

## 6. Persistent triggers

| ID | Given | Then |
|---|---|---|
| V7.1 | AT `resume:r1` @10:05; tick at 10:04:59 | nothing fired; tick at 10:05 + jitter bound fires r1 once |
| V7.2 | upsert `resume:r1` @10:05 then @10:30 | one trigger, nextFire 10:30 |
| V7.3 | target returns SUSPENDED(12:00) | `resume:r1` @12:00, attempts 2 |
| V7.4 | target returns DONE for AT | trigger deleted; for CRON `0 7 * * *` Asia/Kolkata: nextFire 2026-09-28T01:30:00Z, lastOutcome DONE |
| V7.5 | target returns HUMAN | AT deleted, none added |
| V7.6 | Cron parser: `*/15 9-17 * * MON-FRI`, `0 0 1 * *`, `30 2 * * *` across US DST start/end | exact next instants from a fixture table; skipped hour moves forward; repeated hour fires once |
| V7.7 | invalid cron `61 * * * *`, zone `Mars/Base` | errors naming the field |
| V7.8 | `every 6h` last fired 00:00, runner down until 19:00, `misfire: run_once` | fires once at 19:00; next 00:00 (next day) |
| V7.9 | same with `misfire: skip` | not fired; next 00:00 |
| V7.10 | resume triggers overdue by 2 days | always fire on the first tick |
| V7.11 | `overlap: skip`, the 07:00 run is suspended when 07:00 next day arrives | no second run; `trigger_fired` outcome SKIPPED_OVERLAP |
| V7.12 | `overlap: queue` | second run starts with id `MorningDigest@2026-09-28T01:30:00Z` |
| V7.13 | pause / cancel / fire-now | never fires / removed / fires on the next tick |
| V7.14 | File store: process killed between claim and complete (claim file left) | re-claimed after `staleAfter`; fired exactly once more |
| V7.15 | File store: two `tick()` calls on two threads/processes, 20 due triggers | each fired exactly once |
| V7.16 | JDBC on H2: two runners, 50 due triggers | each fired exactly once |
| V7.17 | New runner instance on the same store after restart | pending triggers still fire |
| V7.18 | Jitter: 1 h wait ≤ 30 s; 60 s wait ≤ 6 s | holds over 1000 samples |

## 6b. Schedules in scripts

| ID | Given | Then |
|---|---|---|
| V8.1 | `schedule MorningDigest { cron: "0 7 * * *" timezone: "Asia/Kolkata" run: DailyDigest(topic="AI") }` | parses; initialize upserts `schedule:<script>/MorningDigest` with StartWorkflow target and args |
| V8.2 | old `schedule DailyCleanup { pattern: "24h" agent: AdminBot task: "…" }` with a store | EVERY PT24H, AgentTask target; without a store, runs in memory as today and logs "not persistent" |
| V8.3 | script edited: cron changed; one schedule removed | changed one keeps lastFire with a new nextFire; removed one `enabled=false` |
| V8.4 | `run: NoSuchWorkflow()` | initialize error naming the line |
| V8.5 | a scheduled workflow run hits a rate limit | its own journal and `resume:MorningDigest@…` trigger; the schedule's nextFire advances independently |

## 6c. System triggers

Tested on the Plan (golden files) with a fake `CommandRunner`; nothing on the test machine changes.

| ID | Backend / mode | Then |
|---|---|---|
| V9.1 | cron heartbeat | crontab = existing lines untouched + `*/5 * * * * '<weave>' tick '<store>' # loom:<id>`; re-install is byte-identical; uninstall restores the original crontab exactly |
| V9.2 | cron exact with 2 pending triggers | 2 minute-precision lines + heartbeat, all tagged |
| V9.3 | systemd heartbeat | `loom-<id>.service` (ExecStart) and `.timer` (`OnCalendar=*:0/5`, `Persistent=true`) under `~/.config/systemd/user`; commands `systemctl --user daemon-reload`, `enable --now loom-<id>.timer` |
| V9.4 | launchd | plist `dev.llm4j.loom.<id>` with `StartInterval` 300; `launchctl bootstrap gui/<uid> …` |
| V9.5 | windows | `schtasks /Create /TN \Loom\<id> /SC MINUTE /MO 5 /TR "…" /F`; uninstall `/Delete /F` |
| V9.6 | cloud-scheduler | the exact `gcloud scheduler jobs create http` command with `--oidc-service-account-email`, printed and never executed unless `--apply` |
| V9.7 | `install` without `--apply` | CommandRunner never invoked; plan printed |
| V9.8 | any backend | no path outside the user's home; no `sudo`; paths with spaces quoted |
| V9.9 | exact mode: a tick creates `resume:r2` and completes `resume:r1` | re-sync adds r2's entry and removes r1's |
| V9.10 | `TriggerEndpoint`: no token / wrong token / right token | 401 / 401 / 200 with `{"fired": n}`; token read from env only |
| V9.11 | Linux integration (if `crontab` exists; temp HOME) | install → `crontab -l` shows the line → uninstall → gone |
| V9.12 | `weave tick` with nothing due | exit 0, no output beyond a debug line; a concurrent `tick` exits 0 without firing (lock held) |

## 7. End to end

| ID | Scenario | Then |
|---|---|---|
| E2E-1 | Gemini daily quota: MockWebServer returns V1.7's body for `draft`; H2 journal + JDBC store; clock advanced to 07:00Z next day; server now returns 200; `TriggerEndpoint` called (as Cloud Scheduler would) | the run suspends, `resume:` trigger exists for 2026-09-28T07:00Z (+jitter), the endpoint call fires it, the run completes, the server saw research once and draft twice |
| E2E-2 | Background agent: `tokens: 1000 per hour when_exhausted: suspend`, `for each` over 10 items costing 300 each, driven only by `tick()` calls every 5 min of test clock | runs 3 items/hour; suspends 3 times; completes after the 4th window; total spend 3000 |
| E2E-3 | CLI: `weave run x.loom --journal d --store s` hitting a limit; then a **separate** `weave tick s` process after the reset | first exits 4 with "Paused" + resume time + install hint; second resumes from `d/run.json` and completes, printing the spend table |
| E2E-4 | CLI `--wait` with a 3 s reset (test clock) | single process completes, exit 0 |
| E2E-5 | Scheduled workflow: `cron: "*/5 * * * *"`, `weave tick` at 10:00, 10:05, 10:10 (test clock), the 10:05 run suspended | runs at 10:00 and 10:10 complete; the 10:05 run resumes via its own trigger; `overlap: skip` respected |

## 8. Non-functional

- **N1.** A suspended run holds no thread: after V6.1, no executor thread named `loom-*` is alive.
- **N1b.** No trigger lives only in memory when a store is configured: killing the runner at any point
  (fault injection between each store call) loses no trigger and never fires one twice after a
  completed `complete()`.
- **N2.** Parsing a 429 never throws; fuzz 1000 random header/body combinations.
- **N3.** No new runtime dependencies in ai-agent4j or Loom.
- **N4.** Coverage for the `ratelimit`, `trigger` and `trigger.system` packages ≥ 90% lines / 85% branches.
- **N5.** All existing suites stay green (ai-agent4j 448, Loom 94, addons, eval4j, Engram, GetViral).

## 9. Live checks (need the user's key or machine)

- **L1.** A real Gemini free-tier 429 parses to the documented scope and a plausible reset.
- **L2.** A real Anthropic or OpenAI 429 (if a key is provided) parses headers correctly.
- **L3.** On your machine: `weave triggers install --apply` (systemd or launchd), a paused run resumes
  unattended, and `uninstall` leaves no trace.

## 10. Phase gates

- **Phase 1 done:** V1–V4 and N2–N4 (ai-agent4j part) pass.
- **Phase 2 done:** V5, V6 and N1 pass; cost-budgets tests are unchanged.
- **Phase 3 done:** V7–V9, E2E-1..5, N1b and N5 pass; docs are updated and their examples parse.

## 11. Results (2026-09-27)

**Every suite passes:**

| Suite | Tests |
|---|---|
| ai-agent4j | 496 (was 448) |
| addons | 17 |
| eval4j | 125 |
| Loom | 232, 1 skipped (was 94) |
| Engram | 9, 1 skipped |
| GetViral | 77, 4 skipped |

**Where each part of the plan is covered:**

| Plan section | Test classes |
|---|---|
| V1–V2 | `RateLimitParsersTest`, `ParserEdgeCasesTest`, `HttpRateLimitTest` (MockWebServer, real providers) |
| V3 | `WindowedBudgetTest` |
| V4 | `RateLimitedAgentTest` |
| V5 | `ResumeSyntaxTest` |
| V6 and N1 | `SuspensionTest` |
| V7 and N1b | `CronScheduleTest`, `TriggerRunnerTest`, `StoreContractTest` (file and H2, including races and crash injection), `TriggerEdgeCasesTest` |
| V8 | `ScheduleSyncTest` |
| V9 | `SystemTriggerTest`, `BackendEdgeCasesTest`, `TriggerEndpointTest` |
| E2E-1 | `GeminiQuotaEndToEndTest` |
| E2E-2 | `BackgroundAgentEndToEndTest` |
| E2E-3 to E2E-5 and V9.12 | `WeaveResumeCliTest` |
| Documented examples | `DocumentedExamplesTest` |

**N4 coverage (lines / branches):**

| Package | Lines | Branches |
|---|---|---|
| `ratelimit` | 98.1% | 96.1% |
| `loom.trigger` | 90.3% | 91.2% |
| `loom.trigger.system` | 98.1% | 88.9% |

**V9.11** is opt-in with `-Dloom.realCrontabTest=true`. It was run once in the build container after installing `cron`: install, `crontab -l` shows the tagged line, uninstall restores the crontab.

### Deviations from the design

1. **Parser order.** Google's structured error body is read before `Retry-After`: a daily `QuotaFailure` is more specific than a generic delay.
2. **Durations are parsed in the parser** (a number followed by `s|m|h|d`, or a string), not as a lexer literal. The lexer is unchanged, so no existing script can lex differently.
3. **`max_wait` default.** It is 5 minutes for `wait` and 7 days for `suspend`.
4. **Sequential `for each` pauses at the limited item.** It does not skip ahead, so order-dependent loops stay correct. V6.8 applies to `parallel for each`; V6.8b covers the sequential case.
5. **SQL trigger store columns.** `loom_triggers` stores times as epoch milliseconds and adds a `note` column.
6. **Bearer tokens.** `TriggerEndpoint` checks them through a pluggable `BearerVerifier`. No OIDC library is bundled; `X-Loom-Token` with `LOOM_TRIGGER_TOKEN` works out of the box.
7. **CLI state.** It lives in `<run dir>/run.json` plus the trigger store, not a `<run dir>/wakeup` file.
8. **`_run` is published only once a run has paused.** Legacy bare-name substitution would otherwise rewrite words such as "dry_run".
9. **Windowed spend.** A step's usage counts in the window of its last call (journaled `at`).
10. **Provider name.** `RateLimitInfo.provider` comes from the request host: google, anthropic, openai, sarvam, ollama, else the host.
11. **Agent-level policy.** `BudgetPolicy.SUSPEND` was added for plain Java agents. Loom applies `when_exhausted` per budget itself.

### Open

- **L1–L3:** live checks, which need your API key and machine (tasks 14 and 16).
- **Task 15:** GetViral adoption (optional).
