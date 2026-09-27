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
3. **Loom's triggers** are persisted, never kept only in memory. Resume wake-ups and `schedule` blocks
   (cron or interval) live in one Trigger_Store. They are fired either by an embedded runner or by a
   **system trigger** (cron, systemd, launchd, Windows Task Scheduler, Cloud Scheduler) that calls
   `weave tick`, so no process has to stay up just to wait.

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
- **Trigger**: A persisted rule that fires a target at a time. It is `at` (once), `every` or `cron`, and
  its target is a run resume, a workflow start or an agent task.
- **Trigger_Store**: The durable home of every Trigger (file or SQL).
- **Trigger_Runner**: Fires due Triggers. It runs embedded in a host (poll loop) or once per
  `weave tick`, called by a System_Trigger.
- **System_Trigger**: An entry in the OS or cloud scheduler (cron, systemd timer, launchd, Windows Task
  Scheduler, Google Cloud Scheduler) that wakes Loom.
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

### Requirement 5: Persistent Triggers

**User Story:** As someone running agents that "just keep running in the background", I want every
schedule and every pending resume to be stored durably, so that nothing is lost when the process
restarts, is scaled to zero, or moves to another server.

#### Acceptance Criteria

1. THE Trigger_Store SHALL persist every Trigger. There SHALL be in-memory (tests only), file and SQL
   (`loom_triggers` table) versions. A Trigger has:
   - an id, a kind and a target;
   - a next fire time, a last fire time and an outcome;
   - an attempt count, a misfire policy and an enabled flag.
2. THE Trigger kinds SHALL be:
   - `at`: a one-shot instant (used for resume wake-ups);
   - `every`: a fixed interval;
   - `cron`: a 5-field cron expression with a time zone.
3. A Trigger's target SHALL be one of: resume run `<runId>`; start workflow `<name>(args)`; run agent
   task (today's `schedule` blocks).
4. WHEN a run is suspended with a Reset_Time, THE harness SHALL upsert an `at` Trigger for that run
   (id `resume:<runId>`). Only one pending resume Trigger SHALL exist per run.
5. WHEN a Trigger is due, THE Trigger_Runner SHALL claim it atomically, so exactly one instance fires it,
   then fire it and compute its next fire time. `at` Triggers are removed once their target completes.
6. IF a resumed run suspends again, THEN its resume Trigger SHALL be replaced and its attempts
   increased. After `max_resumes` (default 50) the run SHALL be marked failed.
7. WHEN Triggers were missed while nothing was running, THE Trigger_Runner SHALL apply the misfire
   policy:
   - resume Triggers always fire;
   - schedules fire once (`misfire: run_once`, default) or skip to the next slot (`misfire: skip`).
8. WHERE a scheduled workflow's previous run is still running or suspended, THE Trigger_Runner SHALL
   follow `overlap: skip` (default) or `overlap: queue`.
9. THE Trigger_Store SHALL allow a Trigger to be listed, paused, cancelled or fired now.
10. Every scheduled workflow run SHALL get its own run id and journal, so it can itself suspend and resume.
11. THE Trigger_Runner SHALL use the injectable Clock.

---

### Requirement 6: Schedules in Loom

**User Story:** As a script author, I want to declare in the script that a workflow runs every morning,
and have that schedule survive restarts.

#### Acceptance Criteria

1. THE Parser SHALL accept:
   ```loom
   schedule MorningDigest {
       cron: "0 7 * * *"  timezone: "Asia/Kolkata"
       run: DailyDigest(topic="AI agents")
       misfire: run_once  overlap: skip
   }
   ```
   It SHALL also accept `every: 6h` in place of `cron`.
2. Existing `schedule` blocks (`pattern`, `initial_delay`, `agent`, `task`) SHALL keep working. They
   become `every` Triggers with an agent-task target.
3. WHEN a script is loaded, THE harness SHALL reconcile its schedules into the Trigger_Store:
   - add new schedules;
   - update changed ones, keeping `lastFire`;
   - disable (not delete) schedules that were removed from the script.
4. Invalid cron expressions, time zones or workflow names SHALL be ParseErrors or initialize errors with
   the line.

---

### Requirement 7: System Triggers

**User Story:** As a solo developer, I don't want a Java process running all day just to wait for a
reset at 7 a.m. I want the operating system or my cloud's scheduler to wake Loom when needed.

#### Acceptance Criteria

1. THE Trigger_Runner SHALL be startable in two ways:
   - embedded: a poll loop inside the host;
   - `weave tick <store>`: a one-off command that fires every due Trigger and then exits.
2. `weave triggers install --backend <b> <store>` SHALL create a system trigger that runs `weave tick`
   for that store. `<b>` is one of `cron`, `systemd`, `launchd`, `windows` or `cloud-scheduler`.
3. THE system trigger SHALL be installed in one of two modes:
   - **heartbeat** (default): a recurring system entry, every 5 min by default and configurable, that
     calls `weave tick`. It is always correct, because the store is the truth.
   - **exact**: one system entry per pending Trigger at its exact time (systemd `OnCalendar`, launchd
     calendar intervals, Windows task triggers, `at`/crontab lines). These entries are re-synced after
     every tick.
4. Install SHALL show what it will change and do nothing without `--apply`. Entries SHALL be tagged
   (`# loom:<store-id>`, unit and plist names prefixed `loom-`), so that `weave triggers uninstall` removes
   exactly them and a repeat install is idempotent.
5. WHERE the backend is `cloud-scheduler`, THE CLI SHALL generate the `gcloud scheduler jobs create
   http` command. It targets an HTTP endpoint that the host exposes through
   `TriggerEndpoint.handle(request)`, which fires due Triggers and is authenticated by an OIDC token or
   a shared secret taken from the environment.
6. Backends SHALL only write user-level entries (the user crontab, `~/.config/systemd/user`,
   `~/Library/LaunchAgents`, a per-user Windows task). They SHALL never require root.
7. `weave tick` SHALL take a lock on the store, so overlapping system invocations are safe, and SHALL
   exit 0 when there is nothing due.

---

### Requirement 8: The weave CLI

**User Story:** As a solo developer, I want to start a background workflow from the command line and
have it pause and continue on its own.

#### Acceptance Criteria

1. `weave run --journal <dir>` SHALL use a file journal, so the run is durable. A suspension SHALL:
   - write a resume Trigger to the store (default `<dir>/../.loom-triggers`, or `--store`);
   - print the reason and the resume time;
   - tell the user either that a system trigger will pick it up, or how to install one.
2. `weave run --journal <dir> --wait` SHALL stay alive and resume the run itself when due.
3. `weave resume <dir>` SHALL resume a suspended run now. `weave daemon <store>` SHALL run the embedded
   Trigger_Runner in the foreground.
4. `weave triggers list|pause|cancel|fire <store> [id]` SHALL manage stored Triggers.
5. Exit codes: 0 done, 1 failed, 3 stopped by a budget, 4 suspended.

---

### Requirement 9: Observability

**User Story:** As an operator, I want to see why a run is paused and when it will continue.

#### Acceptance Criteria

1. THE audit log SHALL record `run_suspended` (reason, resume time, limit detail), `run_resumed`
   (waited for, attempt) and `trigger_fired` (id, kind, target, outcome, late by).
2. THE HarnessExecutor SHALL expose `{_run.resumes}` and `{_run.lastSuspension}` to scripts.
3. THE Spend_Report SHALL cover the whole run across resumes.

---

### Requirement 10: Compatibility

**User Story:** As an existing user, I want nothing to change unless I opt in, apart from clearer errors.

#### Acceptance Criteria

1. WHERE no journal and no `rate_limits` or windowed budget is configured, THE behaviour SHALL be as
   today, except that a 429 now uses the provider's Reset_Time for inline waits and raises
   `RateLimitException` (still an `LLMException`) instead of a generic error.
2. Existing budget scripts SHALL behave exactly as before (`when_exhausted` defaults to `stop`).
3. THE Loom guide, LOOM_PROMPT, READMEs and VS Code grammar SHALL document the new syntax.
