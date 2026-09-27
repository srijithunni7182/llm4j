# Requirements Document

## Introduction

Resumable Runs lets a long-running, background agent workflow **pause when it hits a limit and resume
by itself when the limit lifts**, without losing or repeating any paid-for work. It covers two kinds of
limit:

- **Provider rate limits and quotas.** The provider says no (HTTP 429). This could be requests per
  minute, tokens per minute, or a daily quota.
- **Windowed budgets.** The workflow's own cap, for example `budget { tokens: 100000 per day }`, which
  refills when its window rolls over.

It spans the stack:

1. **ai-agent4j** turns a provider's "no" into a precise signal: which limit, and **when it resets**.
   Short waits are absorbed inline; long ones are handed up to the harness instead of being retried
   blindly.
2. **Loom** decides what the workflow does with that signal: **suspend** the run (the default for
   durable runs), wait inline, fail, or ask a person. It records the reason and the resume time in the
   run journal.
3. **Loom's scheduler** is a durable list of wake-ups that resumes each suspended run once its limit has
   reset. It is safe with several instances, survives restarts, and is also usable from the `weave` CLI.

It builds on what already exists: durable replay from the run journal, `RunSuspended` (a pause that
holds no thread), journaled spend, and budgets enforced at the call.

---

## Glossary

- **Rate_Limit_Info**: What a provider's refusal tells us: reset time, scope (requests, tokens, daily
  quota, unknown), provider, quota id and the raw detail.
- **Reset_Time**: The instant the limit is expected to lift. It is always absolute, never a duration.
- **RateLimited**: An `AgentInterrupt` carrying Rate_Limit_Info, raised when a limit won't lift within
  the inline-wait threshold. It is never retried and never turned into a partial answer.
- **Windowed_Budget**: A budget whose spend resets at fixed window boundaries (minute, hour, day).
- **Suspension**: A paused run: the step that hit the limit, the reason and the Reset_Time, recorded in
  the run journal.
- **Wake_Up**: A scheduled resume of one run at one instant.
- **Run_Scheduler**: The durable store of Wake_Ups plus the loop that fires them.
- **Run_Resumer**: The host's callback that rebuilds an executor for a run id and calls
  `executeWorkflow` with its journal.
- **Clock**: An injectable `java.time.Clock`. Every time decision uses it, so tests control time.

---

## Requirements

### Requirement 1: Rate-Limit Signal in ai-agent4j

**User Story:** As an agent developer, I want a provider's rate-limit refusal to tell me exactly which
limit I hit and when it resets, so that my workflow can wait the right amount of time instead of
guessing.

#### Acceptance Criteria

1. WHEN a provider returns HTTP 429, THE HTTP layer SHALL build Rate_Limit_Info from, in order of
   preference:
   - provider reset headers (`anthropic-ratelimit-*-reset` as ISO-8601; `x-ratelimit-reset-*` as
     durations such as `6m0s`);
   - `Retry-After`, as seconds or as an HTTP-date;
   - the Gemini error body (`google.rpc.RetryInfo.retryDelay` and `google.rpc.QuotaFailure.quotaId`).
2. WHEN a Gemini `quotaId` names a per-day quota (it contains `PerDay` or `Daily`), THE provider SHALL
   set the Reset_Time to the next daily reset (default: midnight America/Los_Angeles, configurable)
   and SHALL ignore the short `retryDelay`.
3. IF no reset information is present, THEN THE HTTP layer SHALL set the Reset_Time to now plus a
   fallback delay (default 60 s, doubling on consecutive 429s for the same provider up to 1 h), and mark
   the Reset_Time as estimated.
4. WHEN the Reset_Time is within the inline-wait threshold (default 30 s) and retries remain, THE HTTP
   layer SHALL wait until the Reset_Time plus up to 10% jitter and retry, instead of exponential
   backoff.
5. WHEN the Reset_Time is beyond the inline-wait threshold, THE HTTP layer SHALL NOT retry, and SHALL
   raise `RateLimitException` carrying the Rate_Limit_Info.
6. THE providers (Google, Ollama, Sarvam) SHALL pass `RateLimitException` through unchanged instead of
   wrapping it in `ProviderException`.
7. THE existing `RateLimitException.getRetryAfterSeconds()` SHALL keep working, derived from the
   Reset_Time.

---

### Requirement 2: Windowed Budgets

**User Story:** As a developer running agents in the background, I want budgets that refill every
minute, hour or day, so that a quiet background agent keeps working within a steady allowance.

#### Acceptance Criteria

1. THE Budget SHALL accept a window (`MINUTE`, `HOUR`, `DAY`) for its token, call and cost limits.
   Windows are aligned to the Clock's time zone (UTC by default).
2. WHEN a window rolls over, THE Budget SHALL start the new window with nothing spent. Spend in earlier
   windows stays visible in reports.
3. WHEN a Windowed_Budget refuses a call, THE `BudgetExceeded` SHALL carry the Reset_Time (the end of
   the current window).
4. WHEN a run is resumed, THE harness SHALL restore only the journaled spend whose timestamp falls in
   the current window. Journaled usage therefore records when it was spent.
5. THE Loom syntax SHALL accept `per minute`, `per hour` and `per day` after a limit, e.g.
   `budget { tokens: 100000 per day }`.

---

### Requirement 3: From Agent to Harness

**User Story:** As a harness author, I want an agent that hits a limit to stop and tell me, instead of
returning a half-finished answer, so that the step can simply run again later.

#### Acceptance Criteria

1. WHEN `ReActAgent` receives a `RateLimitException`, it SHALL raise `RateLimited` (an `AgentInterrupt`)
   carrying the Rate_Limit_Info. It SHALL NOT return a partial result and SHALL NOT retry.
2. WHEN a Windowed_Budget refuses a call and its Reset_Time is known, `BudgetExceeded.resetAt()` SHALL
   return it. The existing partial-answer behaviour for lifetime budgets is unchanged.
3. THE usage spent before the interruption SHALL still be settled and reported.

---

### Requirement 4: Suspending a Loom Run

**User Story:** As a Loom script author, I want to choose, in one line, whether my workflow pauses and
resumes, waits, fails or asks me when it hits a limit.

#### Acceptance Criteria

1. THE Parser SHALL accept a top-level
   `rate_limits { on_limit: suspend | wait | fail  max_wait: 24h }` block.
2. THE Parser SHALL accept `when_exhausted: suspend | stop | ask` inside a `budget { }` block.
   `stop` is today's behaviour and the default.
3. WHERE no `rate_limits` block is given, THE HarnessExecutor SHALL suspend if its journal is durable
   (file or SQL), and otherwise wait inline up to `max_wait` (default 5 min) and then fail.
4. WHEN a run is suspended, THE HarnessExecutor SHALL:
   - journal a Suspension entry (step id, reason, Reset_Time, detail);
   - keep every result and charge already recorded;
   - end `executeWorkflow` by throwing `RunSuspended` with `resumeAt()` and `reason()` set.
5. WHEN a suspended run is resumed, THE step that hit the limit SHALL run again; every earlier step
   SHALL replay from the journal without calling a model.
6. WHEN parallel branches or for-each items hit limits, THE HarnessExecutor SHALL let running branches
   finish or suspend. It SHALL then suspend once, at the latest Reset_Time among them.
7. IF the Reset_Time is later than `max_wait` from now, THEN THE HarnessExecutor SHALL fail the run with
   a message naming the limit and its Reset_Time, rather than suspend indefinitely.
8. WHERE `when_exhausted: ask` is set, THE HarnessExecutor SHALL raise a human question ("Budget <name>
   spent <n>/<limit>. Allow <limit> more? yes/no"). A journaled `yes` SHALL raise that budget's limit by
   its original amount on resume.

---

### Requirement 5: Scheduling Resumes

**User Story:** As someone running agents that "just keep running in the background", I want paused
runs to resume by themselves when their limit resets, even after a restart or on another server.

#### Acceptance Criteria

1. THE Run_Scheduler SHALL store each Wake_Up (run id, resume time, reason, attempts) durably. There
   SHALL be in-memory, file and SQL (`loom_wakeups` table) versions.
2. WHEN a run is suspended with a Reset_Time, THE harness SHALL register a Wake_Up at that time. Only one
   pending Wake_Up SHALL exist per run.
3. WHEN a Wake_Up is due, THE Run_Scheduler SHALL claim it atomically, so exactly one instance resumes a
   given run, and SHALL call the Run_Resumer.
4. IF the resumed run suspends again, THEN its new Wake_Up SHALL replace the old one and attempts SHALL
   increase. After `max_resumes` (default 50) the run SHALL be marked failed.
5. WHEN the scheduler starts, THE Run_Scheduler SHALL fire every overdue Wake_Up (missed while nothing
   was running).
6. THE Run_Scheduler SHALL allow a Wake_Up to be cancelled or brought forward (e.g. after a manual
   top-up).
7. THE Run_Scheduler SHALL use the injectable Clock and a configurable poll interval (default 5 s).

---

### Requirement 6: The weave CLI

**User Story:** As a solo developer, I want to start a background workflow from the command line and
have it pause and continue on its own.

#### Acceptance Criteria

1. `weave run --journal <dir>` SHALL use a file journal, so the run is durable. A suspension SHALL print
   the reason, the resume time and the command to resume.
2. `weave run --journal <dir> --wait` SHALL stay alive and resume the run itself when it is due.
3. `weave resume <dir>` SHALL resume a suspended run now. `weave daemon <dir>` SHALL watch a directory
   of run journals and resume each when due.
4. Exit codes: 0 done, 1 failed, 3 stopped by a budget, 4 suspended.

---

### Requirement 7: Observability

**User Story:** As an operator, I want to see why a run is paused and when it will continue.

#### Acceptance Criteria

1. THE audit log SHALL record `run_suspended` (reason, resume time, limit detail) and `run_resumed`
   (waited for, attempt).
2. THE HarnessExecutor SHALL expose `{_run.resumes}` and `{_run.lastSuspension}` to scripts.
3. THE Spend_Report SHALL cover the whole run across resumes.

---

### Requirement 8: Compatibility

**User Story:** As an existing user, I want nothing to change unless I opt in, apart from clearer errors.

#### Acceptance Criteria

1. WHERE no journal and no `rate_limits` or windowed budget is configured, THE behaviour SHALL be as
   today, except that a 429 now uses the provider's Reset_Time for inline waits and raises
   `RateLimitException` (still an `LLMException`) instead of a generic error.
2. Existing budget scripts SHALL behave exactly as before (`when_exhausted` defaults to `stop`).
3. THE Loom guide, LOOM_PROMPT, READMEs and VS Code grammar SHALL document the new syntax.
