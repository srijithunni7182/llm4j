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
 RunScheduler ─────► durable Wake_Up(runId, resumeAt) ──due──► RunResumer.resume(runId)
                                                              └► executeWorkflow(journal) replays, continues
```

Nothing sleeps with a thread held during a long wait: a suspended run is just a journal plus a
wake-up row. That is what makes "agents that quietly run in the background for days" cheap.

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

- The lexer adds a duration literal (`NUMBER` immediately followed by `s|m|h|d`). `per` becomes a
  contextual keyword inside budget blocks only.
- AST: `RateLimitDef(onLimit, maxWait, maxResumes)` on `LoomScript`. `BudgetDef` gains
  `window` and `whenExhausted`. Imports merge them like the top-level budget.
- VS Code grammar and hovers are updated.

### 2.2 HarnessExecutor

New collaborators, all optional setters:
`setClock(Clock)`, `setRunScheduler(RunScheduler)`, `setRunId(String)`.

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

### 2.3 RunScheduler

```java
public interface RunScheduler extends AutoCloseable {
    void schedule(WakeUp wakeUp);                 // upsert by runId
    void cancel(String runId);
    void resumeNow(String runId);                 // bring forward
    List<WakeUp> pending();
    void start(RunResumer resumer);               // begins polling; fires overdue immediately
}
public record WakeUp(String runId, Instant at, String reason, int attempt) { }
@FunctionalInterface public interface RunResumer { void resume(String runId) throws Exception; }
```

Implementations share `AbstractPollingScheduler` (a poll loop on a single daemon thread with the
Clock and poll interval; resumes are handed to a small executor):

- `InMemoryRunScheduler`: a map, for tests and single-process hosts.
- `FileRunScheduler(dir)`: one `<runId>.wakeup` JSON file per run. It claims a wake-up by atomically
  renaming it to `.claimed`. Used by `weave daemon`.
- `JdbcRunScheduler(DataSource)`:
  ```sql
  CREATE TABLE loom_wakeups (run_id VARCHAR(200) PRIMARY KEY, due_at TIMESTAMP NOT NULL,
      reason VARCHAR(200), attempt INT NOT NULL, claimed_by VARCHAR(100), claimed_at TIMESTAMP)
  ```
  A claim is `UPDATE … SET claimed_by=?, claimed_at=? WHERE run_id=? AND claimed_by IS NULL AND due_at<=?`.
  One updated row means this instance owns the resume. Claims older than 10 min are considered dead
  and can be re-claimed.

**Lifecycle.** The resumer's outcome decides what happens next:
- `RunSuspended` with `resumeAt`: the harness has already `schedule`d the new wake-up. The claim row is
  replaced, not deleted.
- Success or a failure: the wake-up is deleted.
- Human suspension: the wake-up is deleted and no new one is created. A human answer resumes it.

`HarnessExecutor` registers the wake-up itself when a scheduler is set, so hosts only supply the
`RunResumer`:
```java
scheduler.start(runId -> newExecutorFor(runId).executeWorkflow(Map.of()));
```

**Jitter.** Each wake-up gets `+[0, min(30s, 10% of wait)]` so a fleet of runs throttled by the same
provider doesn't all return at the same second.

### 2.4 CLI

- `weave run file.loom --journal runs/my-run` uses a `FileRunJournal` at `runs/my-run/journal.json`.
  On suspension it writes `runs/my-run/wakeup` and exits 4:
  `⏸ Paused: google daily quota. Resumes at 2026-09-28 00:00 PDT (in 7h12m). Run: weave resume runs/my-run`.
- `--wait` keeps the process alive with an in-memory scheduler and prints a countdown line every minute.
- `weave resume <dir>` resumes now. `weave daemon <parentDir>` runs a FileRunScheduler over every
  sub-directory.
- The script, the inputs (`--input` values) and the options are saved in `runs/my-run/run.json`, so a
  resume needs only the directory.

### 2.5 Audit

`run_suspended {step, reason, resumeAt, provider, quotaId, estimated}` and
`run_resumed {attempt, waitedMillis}` go through the existing `AuditLogger`.

---

## 3. Testing approach

- Every time decision uses the injected `Clock` and `Sleeper`. Tests use a `MutableClock` and a
  recording Sleeper, so a "one day" wait runs in milliseconds and its duration is asserted exactly.
- 429s come from OkHttp `MockWebServer` (already a test dependency of ai-agent4j) with
  real header and body fixtures copied from provider docs.
- Scheduler tests run the polling loop with a controllable clock and `tick()` rather than real time.
- JDBC tests run on H2, like the durable-run tests.

## 4. Decisions and alternatives

- **Absolute reset times, not durations,** go through every layer, because a suspension may be read
  hours later on another machine.
- **Unknown resets use an estimate with backoff**, not a failure. It is marked `estimated` in audit so
  an operator can tell.
- **No resumer inside Loom that rebuilds executors by itself:** the host owns agent factories, secrets
  and inputs. The CLI is the one built-in host.
- **Not using Quartz or cron libraries:** one table and one poll loop are enough, and avoid a
  dependency.
