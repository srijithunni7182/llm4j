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

## 6. Scheduler

| ID | Given | Then |
|---|---|---|
| V7.1 | InMemory: schedule r1@10:05; tick at 10:04:59 | not fired; tick at 10:05 + jitter bound → resumer called once with r1 |
| V7.2 | schedule r1 twice (10:05 then 10:30) | one pending wake-up at 10:30 |
| V7.3 | Resumer throws `RunSuspended(resumeAt 12:00)` via executor | pending r1@12:00, attempt 2 |
| V7.4 | Resumer completes | no pending wake-up |
| V7.5 | Human suspension | wake-up removed, none added |
| V7.6 | start() with wake-ups due at 09:00 (overdue) | fired on the first tick |
| V7.7 | cancel(r1) / resumeNow(r1) | never fires / fires on the next tick |
| V7.8 | Jitter for a 1 h wait | 0 ≤ jitter ≤ 30 s; for a 60 s wait ≤ 6 s |
| V7.9 | FileRunScheduler: two schedulers over one dir, one due wake-up | resumer called exactly once in total |
| V7.10 | JdbcRunScheduler on H2: two instances, 50 due wake-ups | each run resumed exactly once; none twice |
| V7.11 | JDBC claim older than 10 min (crashed instance) | re-claimed and resumed |
| V7.12 | Scheduler restarted (new instance, same DB) | pending wake-ups still fire |

## 7. End to end

| ID | Scenario | Then |
|---|---|---|
| E2E-1 | Gemini daily quota: MockWebServer returns V1.7's body for `draft`; H2 journal + JdbcRunScheduler; clock advanced to 07:00Z next day; server now returns 200 | the run suspends, a wake-up exists for 2026-09-28T07:00Z (+jitter), the scheduler resumes it, the run completes, the server saw research once and draft twice |
| E2E-2 | Background agent: `tokens: 1000 per hour when_exhausted: suspend`, `for each` over 10 items costing 300 each | runs 3 items/hour; suspends 3 times; completes after the 4th window; total spend 3000 |
| E2E-3 | CLI: `weave run x.loom --journal d` hitting a limit | exit 4, prints "Paused" + resume time + `weave resume d`; `d/run.json` and `d/wakeup` exist; `weave resume d` (limit lifted) exits 0 with the spend table |
| E2E-4 | CLI `--wait` with a 3 s reset (test clock) | single process completes, exit 0 |

## 8. Non-functional

- **N1.** A suspended run holds no thread: after V6.1, no executor thread named `loom-*` is alive.
- **N2.** Parsing a 429 never throws; fuzz 1000 random header/body combinations.
- **N3.** No new runtime dependencies in ai-agent4j or Loom.
- **N4.** Coverage for `ratelimit` and scheduler packages ≥ 90% lines / 85% branches.
- **N5.** All existing suites stay green (ai-agent4j 448, Loom 94, addons, eval4j, Engram, GetViral).

## 9. Live checks (need the user's key)

- **L1.** A real Gemini free-tier 429 parses to the documented scope and a plausible reset.
- **L2.** A real Anthropic or OpenAI 429 (if a key is provided) parses headers correctly.

## 10. Phase gates

- **Phase 1 done:** V1–V4 and N2–N4 (ai-agent4j part) pass.
- **Phase 2 done:** V5, V6 and N1 pass; cost-budgets tests are unchanged.
- **Phase 3 done:** V7, E2E-1..4 and N5 pass; docs are updated and their examples parse.
