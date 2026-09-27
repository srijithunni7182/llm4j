# Design Document: Resumable Runs

## Overview

```
 provider 429 ──► HttpClientWrapper ──► RateLimitException(info: resetAt, scope, …)
                     │  short wait (≤30s)? sleep until resetAt, retry
                     ▼
 windowed Budget ──► BudgetExceeded(resetAt)
                     ▼
 ReActAgent ───────► RateLimited (AgentInterrupt)    ← never retried, never a partial answer
                     ▼
 HarnessExecutor ──► policy: suspend | wait | fail | ask
                     │  suspend: journal "suspension" entry, throw RunSuspended(resumeAt, reason)
                     ▼
 TriggerStore ─────► persisted Trigger resume:<runId> @resumeAt      (file or SQL; never memory-only)
   also holds       schedule blocks: cron "0 7 * * *" / every 6h → StartWorkflow
                     ▼ due
 TriggerRunner ────► embedded poll loop  ─or─  `weave tick` called by a SYSTEM TRIGGER
                     (cron · systemd timer · launchd · Windows Task Scheduler · Cloud Scheduler → HTTP)
                     ▼
 TriggerTarget ────► executeWorkflow(journal) replays finished steps, continues
```

Nothing sleeps with a thread held during a long wait: a suspended run is just a journal plus a trigger
row, and no Loom process needs to be alive until a system trigger calls `weave tick`. That is what makes
"agents that quietly run in the background for days" cheap.

---

## 1. ai-agent4j

### 1.1 `io.github.llm4j.ratelimit`

```java
public record RateLimitInfo(
        Instant resetAt,          // absolute; never null
        Scope scope,              // REQUESTS, TOKENS, DAILY_QUOTA, UNKNOWN
        String provider,          // "google", "anthropic", "openai", …
        String quotaId,           // e.g. "GenerateRequestsPerDayPerProjectPerModel-FreeTier", may be null
        Long limit, Long remaining,
        boolean estimated,        // true when no reset info was sent (fallback delay)
        String detail) {
    public Duration waitFrom(Instant now);
}

public interface RateLimitParser {                    // one per provider shape
    Optional<RateLimitInfo> parse(int status, Map<String, List<String>> headers, String body, Clock clock);
}
```

Parsers, all pure functions of `(headers, body, clock)`, so they're tested with exact fixtures:

| Parser | Reads | Notes |
|---|---|---|
| `RetryAfterParser` | `Retry-After` (seconds or HTTP-date) | generic, always tried last among headers |
| `AnthropicHeaders` | `anthropic-ratelimit-{requests,tokens,input-tokens,output-tokens}-{limit,remaining,reset}` | picks the exhausted dimension (`remaining == 0`), else the latest reset |
| `OpenAiHeaders` | `x-ratelimit-{limit,remaining,reset}-{requests,tokens}` | durations like `1s`, `6m0s`, `1h2m3.5s`, `120ms` |
| `GoogleRpcBody` | `error.details[]`: `RetryInfo.retryDelay` (`"34s"`), `QuotaFailure.violations[].quotaId` | `PerDay`/`Daily` ⇒ `DAILY_QUOTA`, reset = next midnight in `dailyResetZone` (default `America/Los_Angeles`) |

`RateLimitParsers.standard()` chains them (provider-specific first, `Retry-After` next, body last)
and falls back to `now + fallbackDelay` with `estimated = true`.

### 1.2 `HttpClientWrapper`

`executeWithRetry` changes only on 429:

```java
RateLimitInfo info = parsers.parse(code, headers, body, clock).orElseGet(() -> fallback(provider));
Duration wait = info.waitFrom(clock.instant());
if (attempt < maxRetries && wait.compareTo(inlineWaitThreshold) <= 0) {
    sleeper.sleep(wait.plus(jitter(wait)));      // ≤10%, injectable Sleeper for tests
    continue;
}
throw new RateLimitException(info);              // extends LLMException(status 429)
```

`inlineWaitThreshold` (30 s), `fallbackDelay` (60 s, doubling per provider up to 1 h, reset on a
success), `Clock` and `Sleeper` are `RetryPolicy` settings. Other statuses keep the existing
exponential backoff.

`RateLimitException` gains `info()` and a constructor taking `RateLimitInfo`. Its `getRetryAfterSeconds()`
now reads `ceil(waitFrom(now))`.

### 1.3 Providers

`GoogleProvider`, `OllamaProvider` and `SarvamProvider` add `catch (RateLimitException e) { throw e; }`
before their generic `LLMException` wrap. Every provider passes its name to the wrapper, which uses it
to pick parsers.

### 1.4 Windowed budgets

```java
Budget.builder().name("daily").tokens(100_000).window(Window.DAY).clock(clock).build();
```

- `Window { MINUTE, HOUR, DAY }` with `start(Instant, ZoneId)` and `end(...)`. The Clock's zone is used
  (UTC by default).
- Under the budget lock, each access first calls `rollIfNeeded(now)`. When `now >= windowEnd`, it moves
  the current window's totals into `history` (lifetime totals stay available through `lifetimeSpent()`),
  zeroes the window counters and clears `warned`/`exhaustedFired`. Reservations in flight carry over, so
  settling stays exact.
- `BudgetExceeded` gains `Optional<Instant> resetAt()`, the current window end, set by `BudgetSet.reserve`
  from the refusing budget.
- `restore(Spent, Instant at)` ignores spend recorded before the current window's start. The old
  `restore(Spent)` treats spend as lifetime, as before.

### 1.5 `RateLimited` and ReActAgent

```java
public final class RateLimited extends AgentInterrupt {
    public RateLimitInfo info();
    public Reason reason();      // PROVIDER_LIMIT, BUDGET_WINDOW
}
```

In ReActAgent, around `chat`:
- `RateLimitException` → `throw new RateLimited(info, PROVIDER_LIMIT)`.
- `BudgetExceeded` with `resetAt().isPresent()` and a budget whose policy is `SUSPEND` →
  `RateLimited(BUDGET_WINDOW)`.
- Every other `BudgetExceeded` is handled as today (partial answer or FAIL).

Usage settled before the interrupt is already in the budgets. `BudgetedLLMClient.failed()` charges the
prompt of the refused call as an estimate. For a 429 this is changed: a 429 is not billed, so
`failed(e)` charges **nothing but the call count** when `e` is a `RateLimitException`.

---

## 2. Loom

### 2.1 Syntax

```loom
rate_limits {
    on_limit: suspend        // suspend | wait | fail
    max_wait: 24h            // durations: 30s, 15m, 6h, 2d
    max_resumes: 50
}

budget {
    tokens: 100000 per day
    calls: 500 per hour
    when_exhausted: suspend  // stop (default) | suspend | ask
}
```

```loom
schedule MorningDigest {
    cron: "0 7 * * *"        // or: every: 6h
    timezone: "Asia/Kolkata"
    run: DailyDigest(topic="AI agents")
    misfire: run_once        // run_once | skip
    overlap: skip            // skip | queue
}
```

`ScheduleDef` gains `cron`, `every`, `timezone`, `run` (a workflow call), `misfire` and `overlap`. The
old `pattern`/`initial_delay`/`agent`/`task` fields map to `every` plus an agent-task target.

- The lexer adds a duration literal (`NUMBER` immediately followed by `s|m|h|d`). `per` becomes a
  contextual keyword inside budget blocks only.
- AST: `RateLimitDef(onLimit, maxWait, maxResumes)` on `LoomScript`. `BudgetDef` gains
  `window` and `whenExhausted`. Imports merge them like the top-level budget.
- VS Code grammar and hovers are updated.

### 2.2 HarnessExecutor

New collaborators, all optional setters:
`setClock(Clock)`, `setTriggerStore(TriggerStore)`, `setRunId(String)`.

**Catching limits.** The delegate path already catches `BudgetExceeded`. It now also catches
`RateLimited`, and `BudgetExceeded` from a `suspend` budget, and passes them to `onLimit(stepId, info)`:

| Policy | Behaviour |
|---|---|
| `wait` | if `wait ≤ maxWait`: sleep (injectable Sleeper) then re-run the step; else fail |
| `fail` | step fails → `on_failure` / run fails with a message naming limit and reset time |
| `suspend` | if `wait > maxWait` → fail; else throw `RunSuspended.limit(stepId, info)` |
| `ask` (budgets only) | `RunSuspended` human question as for `ask` steps; journaled `yes` raises that budget's limit by its original amount on replay |

Default policy: `suspend` if the journal is durable (`RunJournal.isDurable()`, a new default method
that returns false; true for File and Jdbc), otherwise `wait`.

**Suspension record.** Before throwing, the executor journals
`"#suspension"` → `Entry("suspension", Map{step, reason, resumeAt, detail, attempt})`. The existing
`RunSuspended` gains `resumeAt()` (nullable for human waits) and `reason()`
(`HUMAN`, `RATE_LIMIT`, `BUDGET_WINDOW`).

**Parallel work.** `parallel`, `broadcast` and `for each` branches collect `RunSuspended` from their
futures instead of cancelling siblings. Branches that are running finish and journal their results.
The executor then throws a single `RunSuspended` with the **max** `resumeAt`. On resume, finished
branches replay and the suspended ones run again.

**Resume.** On `executeWorkflow`, if the journal holds a suspension, the executor:
- increments `attempt` (and fails once it passes `max_resumes`);
- emits `run_resumed`;
- exposes `_run.resumes` and `_run.lastSuspension`;
- runs as usual. Replay handles the rest, because the suspended step was never journaled as done.

**Windowed spend on resume.** Usage entries gain `at` (epoch millis). `restoreSpend` passes it to
`Budget.restore(spent, at)`, so yesterday's tokens don't count against today's window.

### 2.3 Triggers (`io.github.llm4j.loom.trigger`)

One model covers resume wake-ups and scheduled workflows, and it is always persisted.

```java
public record Trigger(
        String id,                 // "resume:<runId>" | "schedule:<script>/<name>"
        Kind kind,                 // AT, EVERY, CRON
        String spec,               // ISO instant | ISO duration | "0 7 * * *"
        ZoneId zone,
        Target target,             // ResumeRun(runId) | StartWorkflow(script, name, args) | AgentTask(script, agent, task)
        Instant nextFire, Instant lastFire, String lastOutcome,
        int attempts, Misfire misfire, Overlap overlap, boolean enabled,
        String claimedBy, Instant claimedAt) { }

public interface TriggerStore {                  // FileTriggerStore, JdbcTriggerStore, InMemoryTriggerStore
    void upsert(Trigger t);
    Optional<Trigger> get(String id);
    List<Trigger> all();
    List<Trigger> due(Instant now);
    boolean claim(String id, String owner, Instant now, Duration staleAfter);
    void complete(String id, Instant nextFireOrNull, String outcome);   // null → delete (AT)
    void remove(String id);
    Instant nextDue();                            // for the "exact" system mode and --wait
}

public interface TriggerTarget {                  // supplied by the host (or the CLI)
    Outcome fire(Trigger t) throws Exception;     // DONE, FAILED, SUSPENDED(resumeAt), HUMAN
}

public final class TriggerRunner {
    TriggerRunner(TriggerStore store, TriggerTarget target, Clock clock, String owner);
    int tick();                                   // fire everything due now; returns count; used by weave tick
    void start(Duration pollEvery);               // embedded loop (daemon thread); tick() on start = overdue
    void stop();
}
```

**`CronSchedule`** is a small in-house parser for 5-field cron. It supports `*`, lists, ranges, steps and
the names `MON`/`JAN`. It computes `next(after, zone)` DST-safely: a slot inside a skipped hour moves
forward, and a repeated hour fires once. There is no new dependency.

**Stores.**
- **File:** `<store>/triggers/<id>.json`, one file per trigger, written atomically (temp file plus move).
  A claim adds `<id>.claim` with `O_CREAT|O_EXCL` semantics (`Files.createFile`); a stale claim is
  re-claimable after `staleAfter`. `<store>/tick.lock` (`FileChannel.tryLock`) serialises `weave tick`.
- **SQL:**
  ```sql
  CREATE TABLE loom_triggers (id VARCHAR(300) PRIMARY KEY, kind VARCHAR(10) NOT NULL, spec VARCHAR(200),
      zone VARCHAR(60), target TEXT NOT NULL, next_fire TIMESTAMP, last_fire TIMESTAMP,
      last_outcome VARCHAR(200), attempts INT NOT NULL, misfire VARCHAR(10), overlap VARCHAR(10),
      enabled BOOLEAN NOT NULL, claimed_by VARCHAR(100), claimed_at TIMESTAMP)
  ```
  A claim is an `UPDATE … WHERE id=? AND enabled AND next_fire<=? AND (claimed_by IS NULL OR claimed_at<?)`
  statement. One updated row means this instance owns the firing.

**Firing rules.**

| Target outcome | AT (resume) | EVERY / CRON |
|---|---|---|
| DONE / FAILED | delete | `nextFire = next slot after now`; `lastOutcome` recorded |
| SUSPENDED(resumeAt) | the harness has already upserted `resume:<runId>`, and it replaces this one | schedule advances; the run's own resume trigger carries it on |
| HUMAN | delete; the answer resumes it | advance |

- **Misfire.** When `nextFire` is more than one slot behind, `run_once` fires once and jumps to the next
  future slot, while `skip` jumps without firing.
- **Overlap.** Each scheduled workflow run gets the run id `<schedule>@<slot ISO>`. `overlap: skip` does
  not fire if a `resume:` trigger or a running claim exists for an earlier run of the same schedule.
- **Jitter.** `AT` triggers get `+[0, min(30s, 10% of wait)]`.

**Reconciling scripts.** `HarnessExecutor.initialize()`, when a store is set, upserts the script's
`schedule` blocks:
- a changed spec recomputes `nextFire` but keeps `lastFire`;
- a schedule removed from the script is set to `enabled=false`.

Today's in-memory `AgentScheduler` path remains only when no store is configured, and logs that the
schedule is not persistent.

**Resume from the executor.** On `RunSuspended` with `resumeAt`, the executor upserts
`resume:<runId>` → `ResumeRun(runId)` if a store is set. Hosts supply only the `TriggerTarget`, e.g.:

```java
TriggerTarget target = t -> switch (t.target()) {
    case ResumeRun r      -> outcomeOf(() -> executorFor(r.runId()).executeWorkflow(Map.of()));
    case StartWorkflow w  -> outcomeOf(() -> newRun(w).executeWorkflow(w.args()));
    case AgentTask a      -> outcomeOf(() -> agent(a.agent()).run(a.task()));
};
new TriggerRunner(store, target, clock, hostName).start(Duration.ofSeconds(5));
```

The CLI ships `WeaveTriggerTarget`, which rebuilds runs from `<runDir>/run.json` (script path, inputs,
options), so any stored trigger can be fired by a fresh process.

### 2.4 System triggers (`io.github.llm4j.loom.trigger.system`)

```java
public interface SystemTriggerBackend {
    String name();                                        // cron | systemd | launchd | windows | cloud-scheduler
    Plan plan(InstallRequest req);                        // files to write, commands to run, entries to remove
    Plan uninstall(String storeId);
}
public record InstallRequest(String storeId, Path store, Path weaveCommand, Mode mode,
                             Duration heartbeat, List<Trigger> pending) { }
public enum Mode { HEARTBEAT, EXACT }
```

A **Plan** is pure data: file writes, commands and removals. `weave triggers install` prints it, and
applies it only with `--apply` through a `PlanApplier` with an injectable `CommandRunner`. That keeps
every backend unit-testable without touching the machine.

| Backend | Heartbeat | Exact |
|---|---|---|
| `cron` | one line in the user crontab: `*/5 * * * * <weave> tick <store> # loom:<id>`, edited through `crontab -l` / `crontab -` | one line per pending trigger (minute resolution), plus the heartbeat as a safety net |
| `systemd` | `~/.config/systemd/user/loom-<id>.service` + `.timer` (`OnCalendar=*:0/5`, `Persistent=true`); `systemctl --user daemon-reload && enable --now` | one `.timer` per pending trigger with `OnCalendar=<exact UTC>` |
| `launchd` | `~/Library/LaunchAgents/dev.llm4j.loom.<id>.plist` with `StartInterval` 300; `launchctl bootstrap gui/$UID` | `StartCalendarInterval` dict per pending trigger |
| `windows` | `schtasks /Create /TN \Loom\<id> /SC MINUTE /MO 5 /TR "<weave> tick <store>"` | one `/SC ONCE /ST /SD` task per pending trigger |
| `cloud-scheduler` | `gcloud scheduler jobs create http loom-<id> --schedule="*/5 * * * *" --uri=<url>/loom/tick --oidc-service-account-email=…` | same (Cloud Scheduler has minute resolution; heartbeat recommended) |

- **Exact mode is re-synced.** After every `weave tick`, when `--sync-system <backend>` was installed,
  the entries match `store.all()`. Stale per-trigger entries are removed, and new ones are added (for
  example, a resume trigger created by a suspension).
- **Cloud Run and serverless.** The `TriggerEndpoint` servlet-agnostic handler runs
  `TriggerRunner.tick()` with a JDBC store, so a scaled-to-zero service only wakes when Cloud Scheduler
  calls it. It checks `Authorization: Bearer <OIDC>` (audience configured) or `X-Loom-Token` against
  env `LOOM_TRIGGER_TOKEN`.
- **Why heartbeat is the default:** it is one entry that never needs re-syncing, and its lateness is at
  most the heartbeat interval. Rate-limit resets rarely need better than 5-minute precision.
- **Detection.** `weave triggers install` without `--backend` picks systemd (if `systemctl --user`
  works), else cron on Linux, launchd on macOS, and windows on Windows.

### 2.4b CLI

- `weave run file.loom --journal runs/job [--store runs/.loom-triggers]`
  - On suspension it writes the `resume:` trigger and exits 4:
    `⏸ Paused: google daily quota. Resumes at 2026-09-28 00:00 PDT (in 7h12m).`
  - Then it prints either `A system trigger (systemd, every 5m) will resume it.` or
    `No system trigger installed: run weave triggers install runs/.loom-triggers, or weave daemon`.
- `--wait` keeps the process alive, running an embedded `TriggerRunner` on the same store.
- `weave tick <store>`, `weave daemon <store>`, `weave resume <runDir>`.
- `weave triggers list|pause|cancel|fire|install|uninstall <store> …`.
- `weave schedule sync file.loom --store …` reconciles a script's `schedule` blocks into the store
  without running anything.

### 2.5 Audit

`run_suspended {step, reason, resumeAt, provider, quotaId, estimated}` and
`run_resumed {attempt, waitedMillis}` and `trigger_fired {id, kind, target, outcome, lateMillis}` go
through the existing `AuditLogger`.

---

## 3. Testing approach

- Every time decision uses the injected `Clock` and `Sleeper`. Tests use a `MutableClock` and a
  recording Sleeper, so a "one day" wait runs in milliseconds and its duration is asserted exactly.
- 429s come from OkHttp `MockWebServer` (already a test dependency of ai-agent4j) with
  real header and body fixtures copied from provider docs.
- Trigger tests call `TriggerRunner.tick()` with a controllable clock rather than real time.
- System backends are tested on their **Plan** (golden files for crontab lines, unit files, plists,
  schtasks and gcloud commands) with a fake `CommandRunner`. One Linux integration test runs the real
  `crontab` binary against a temp `HOME`, if available, and is skipped otherwise.
- JDBC tests run on H2, like the durable-run tests.

## 4. Decisions and alternatives

- **Absolute reset times, not durations,** go through every layer, because a suspension may be read
  hours later on another machine.
- **Unknown resets use an estimate with backoff**, not a failure. It is marked `estimated` in audit so
  an operator can tell.
- **No resumer inside Loom that rebuilds executors by itself:** the host owns agent factories, secrets
  and inputs. The CLI is the one built-in host, via `run.json`.
- **The store is the truth, and system triggers only wake Loom.** Losing a crontab line never loses a
  schedule: re-installing restores it, and `weave tick` catches up on misfires.
- **Install is a dry run unless `--apply` is given,** and entries are user-level and tagged. Changing a
  user's crontab or timers is an outward-facing action.
- **Not using Quartz or cron libraries:** one table, a small cron parser and one poll loop are enough,
  and avoid a dependency.
