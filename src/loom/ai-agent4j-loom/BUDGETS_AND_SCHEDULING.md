# Budgets, Pausing and Scheduling in Loom

Long-running and background workflows run into three limits:

- **money**: you set it;
- **provider rate limits and quotas**: the provider sets them, and they lift at a known time;
- **time**: some work should simply happen every morning.

Loom handles all three in the script, in one or two lines each, and keeps working through them without you
babysitting it:

```loom
budget      { tokens: 100000 per day  when_exhausted: suspend }   // an allowance that refills
rate_limits { on_limit: suspend  max_wait: 24h }                   // quotas pause the run, not kill it
schedule MorningDigest { cron: "0 7 * * *"  timezone: "Asia/Kolkata"  run: DailyDigest(topic="AI agents") }
```

A paused run holds **no thread and no process**. It is a journal plus a trigger in a store. When the limit
lifts, something wakes Loom: a daemon, your OS scheduler (cron, systemd, launchd, Windows Task Scheduler)
or a cloud scheduler. The run then replays what it already did, for free, and carries on.

This guide covers the script syntax, what happens at run time, the `weave` commands, running Loom inside
your own Java service, and troubleshooting. For the library underneath, see ai-agent4j's
[Budgets and Rate Limits](../../ai-agent4j/wiki/Budgets-and-Rate-Limits.md).

---

## Contents

1. [Budgets](#1-budgets)
2. [Budgets that refill](#2-budgets-that-refill)
3. [Rate limits and quotas](#3-rate-limits-and-quotas)
4. [Pausing and resuming](#4-pausing-and-resuming)
5. [Schedules](#5-schedules)
6. [Triggers and the trigger store](#6-triggers-and-the-trigger-store)
7. [Waking Loom: daemon, system triggers, cloud](#7-waking-loom-daemon-system-triggers-cloud)
8. [The weave CLI](#8-the-weave-cli)
9. [Embedding in Java](#9-embedding-in-java)
10. [Observability](#10-observability)
11. [Walkthroughs](#11-walkthroughs)
12. [Troubleshooting and FAQ](#12-troubleshooting-and-faq)
13. [Syntax reference](#13-syntax-reference)

---

## 1. Budgets

A budget caps what model calls may spend, in **tokens** (the default), **calls**, or **money** (with your
own price table). It is enforced at the call itself: a call that can't be paid for is **refused before it
reaches the model**, and every answer's length is capped to what is left.

Budgets go in four places, one line each:

```loom
budget { tokens: 200000  calls: 150  warn_at: 80% }        // the whole run

agent Writer {
    model: "gemini/gemini-2.5-flash"
    budget { tokens: 20000  per_call: 2000 }                 // this agent's share; per_call caps each answer
}

workflow Main(topic) {
    delegate "Draft {topic}" to Writer -> draft budget 5000 tokens      // one step
        on_failure { note "Out of budget: {_error}" }

    loop until (review.verdict == "OK") max 5 budget 30000 tokens {     // a loop (also: for each, broadcast)
        delegate "Review {draft}" to Critic -> review
    } on_exhausted {
        note "Stopped by {_loopExhaustedBy} after {_loopRounds} rounds"
    }

    alt (_budget.remaining < 20000) {                         // route on money left
        delegate "Polish {draft}" to CheapWriter -> final
    } else {
        delegate "Polish {draft}" to Writer -> final
    }
}
```

| Field | Where | Meaning |
|---|---|---|
| `tokens: N` | any `budget { }` | Prompt + completion tokens |
| `calls: N` | any `budget { }` | Model calls |
| `cost: "$0.50"` | any `budget { }` | Money. The currency symbol is required; needs a price table |
| `warn_at: 80%` | any `budget { }` | Log a `budget_warning` at this point (default 80%) |
| `per_call: N` | agent `budget { }` | Cap every answer's output tokens |
| `per minute \| hour \| day` | after a limit | The budget refills each window ([§2](#2-budgets-that-refill)) |
| `when_exhausted: stop \| suspend \| ask` | any `budget { }` | What happens when it runs out ([§2](#2-budgets-that-refill)) |
| `budget N tokens`, `budget N calls`, `budget "$X"` | after `delegate`, `broadcast`, `loop until`, `for each` | A budget for that statement |

**Budgets nest.** Each call is charged to the run, its agent, and every enclosing step, loop and for-each
budget, and must fit all of them. Parallel branches share the same budgets exactly: they can't jointly
overspend.

**When a budget runs out** (with the default `when_exhausted: stop`):

| Situation | Behaviour |
|---|---|
| A step's call would exceed a budget | The model is **not** called. The step fails; its `on_failure` runs with `{_error}` naming the budget. Never retried. |
| An agent runs out part-way through | Its best answer so far is bound to the variable, `{_budget.exhausted}` becomes `true`, `on_failure` runs if present, and the run continues. |
| A loop's or for-each's own budget runs out | It stops and runs `on_exhausted` with `{_loopExhaustedBy} = budget`. |
| Nothing handles it | `executeWorkflow` throws `BudgetExceeded`. Everything bound so far is kept. `weave run` exits with code 3. |

**Live values** for conditions and payloads: `{_budget.spent}`, `{_budget.remaining}` (or `unlimited`),
`{_budget.calls}`, `{_budget.cost}`, `{_budget.exhausted}`.

**Money** needs a price table, because prices change and none ship with Loom:

```properties
# prices.properties — model = input / output, per million tokens, in your currency
gemini/gemini-2.5-flash = 0.30 / 2.50
gemini/gemini-2.5-pro   = 1.25 / 10.00
```

Pass it with `weave run … --prices prices.properties` or `executor.setPriceTable(PriceTable.load(path))`.
`ollama/*` models are free. A cost budget covering an unpriced model fails at start-up, not mid-run.

**Durable.** Each step's usage is journaled. A replayed step is never charged twice, and a run stopped by
its budget can be resumed with a bigger one (`--max-tokens`, or edit the script): it continues from the
refused step.

**Spend report.** `executor.spend()` gives totals, per agent and per step (`total()`, `byAgent()`,
`byStep()`, `table()`). `weave run` prints the table at the end of any budgeted run:

```text
💸 Spend
agent                      calls     prompt   completion         cost
Researcher                     1        100           50      $0.0002
Writer                         2        240          310      $0.0010
total                          3        340          360      $0.0012
```

---

## 2. Budgets that refill

A background agent wants an **allowance**: "100k tokens a day, forever". Add `per minute`, `per hour`
or `per day` after a limit:

```loom
budget {
    tokens: 100000 per day
    calls: 500 per day
    when_exhausted: suspend    // pause until the next window, then carry on
}
```

- A budget has **one window** (writing `per day` on one field and `per hour` on another is an error).
- Windows are aligned to the executor's clock zone (UTC by default): days start at midnight.
- When a window rolls over, the budget starts from zero again. Reports still show every window's spend.
- On resume, only this window's journaled spend counts against the budget.

`when_exhausted` decides what happens when the budget runs out:

| Value | Behaviour |
|---|---|
| `stop` (default) | As in [§1](#1-budgets): refuse, `on_failure`, or stop the run. |
| `suspend` | **Pause the run until the window refills** ([§4](#4-pausing-and-resuming)). Needs `per …`. |
| `ask` | Ask a person: *"Budget run is used up (…). Allow 100000 tokens more? yes/no"*. A **yes** raises the budget by its original amount and the step runs again; a **no** (or nobody to ask) stops as usual. The answer is journaled, so a resumed run doesn't ask twice. With a durable journal, the run pauses until someone answers. |

---

## 3. Rate limits and quotas

Providers refuse calls with **HTTP 429** when you exceed requests per minute, tokens per minute, or a
daily quota (Gemini's free tier). Loom reads **when the limit lifts** from the refusal. ai-agent4j does the
reading:

| Provider | Loom reads |
|---|---|
| Gemini | the error body: `RetryInfo.retryDelay`, and `QuotaFailure.quotaId`. A **per-day** quota resets at **midnight Pacific**, whatever the short delay says. |
| Anthropic | `anthropic-ratelimit-{requests,tokens,…}-reset` (the exhausted one) |
| OpenAI and compatible APIs | `x-ratelimit-reset-{requests,tokens}` (e.g. `6m0s`) |
| Anyone | `Retry-After` (seconds or a date) |
| No hint at all | an estimate: 60 s, doubling on repeated refusals, up to 1 h (marked *estimated*) |

Limits that lift **within 30 seconds** are waited out inside the call; your script never notices. Longer
ones reach the harness, and `rate_limits` decides:

```loom
rate_limits {
    on_limit: suspend     // suspend | wait | fail
    max_wait: 24h         // a limit further away than this fails the run instead
    max_resumes: 50       // a run resumed more often than this is failed
}
```

| `on_limit` | Behaviour |
|---|---|
| `suspend` | Pause the run until the reset ([§4](#4-pausing-and-resuming)). **Default when the run has a durable journal** (file or SQL). |
| `wait` | The step sleeps until the reset, then runs again. **Default otherwise**, with `max_wait` 5 minutes. Holds a thread, so use it for short limits. |
| `fail` | The step fails: `on_failure` runs with `{_error}` = `rate limited: google daily quota (…); resets at 2026-09-28T07:00:00Z`. |

Defaults: `max_wait` is 5 minutes for `wait` and 7 days for `suspend`; `max_resumes` is 50. Durations are
written `30s`, `15m`, `6h`, `2d`.

A refused call costs no tokens (it counts as a call). With a `routing` policy, a limited tier fails over to
the next; only when every tier is limited does the run pause, until the soonest one frees up.

---

## 4. Pausing and resuming

When a step hits a limit and the policy is `suspend`:

1. The step's partial work is discarded (what it spent stays counted).
2. The journal records a **suspension**: the step, the reason (`RATE_LIMIT` or `BUDGET_WINDOW`), the reset
   time, and the limit's details.
3. `executeWorkflow` throws **`RunSuspended`** with `resumeAt()` and `reason()`. `weave run` prints
   `⏸ Paused: google daily quota. Resumes at 2026-09-28 07:00 UTC (in 21h).` and exits with **code 4**.
4. With a trigger store, a **resume trigger** is written (`resume:<runId>`, at the reset plus a few seconds
   of jitter, so many paused runs don't all come back at once).

When the run is executed again with the same journal, on any machine, whether by a trigger, `weave resume`
or your own code:

- every finished step **replays from the journal**: no model call, no charge;
- the limited step **runs again**;
- `{_run.resumes}` counts the resumes, and `{_run.lastSuspension.reason}` / `.step` / `.resumeAt`
  describe the last pause;
- if the limit is still there, it pauses again and replaces its resume trigger;
- after `max_resumes` resumes, the run fails with a message naming the last limit.

**Parallel work** (`parallel`, `broadcast`, `parallel for each`): branches that can still work **finish and
are journaled**; the run then pauses **once**, until the latest reset among the limited branches. On resume
only the limited branches run. A sequential `for each` pauses at the limited item and continues from there.

**What "durable journal" means.** `FileRunJournal` (what `weave run --journal` uses) and `JdbcRunJournal`
outlive the process; the in-memory journal does not. That is why `suspend` is the default only for durable
runs: an in-memory run that "paused" could never be picked up again.

---

## 5. Schedules

```loom
schedule MorningDigest {
    cron: "0 7 * * *"                     // minute hour day-of-month month day-of-week
    timezone: "Asia/Kolkata"              // default UTC
    run: DailyDigest(topic="AI agents")   // a workflow in this script, with arguments
    misfire: run_once                     // run_once (default) | skip
    overlap: skip                         // skip (default) | queue
}

schedule Hourly {
    every: 1h                             // instead of cron
    run: Sweep()
}

schedule DailyCleanup {                   // the classic form: one task for one agent
    initial_delay: "30s"
    pattern: "24h"
    agent: AdminBot
    task: "Purge temporary RAG indices"
}
```

**Cron** has the usual five fields, with `*`, lists (`1,15`), ranges (`9-17`), steps (`*/15`, `8-18/2`)
and names (`JAN`–`DEC`, `SUN`–`SAT`; `0` and `7` are Sunday). When both day fields are restricted, a day
matching either fires (classic cron). Times are read in `timezone`:

- a time skipped by a daylight-saving jump (02:30 on a spring-forward night) fires when the clock jumps;
- a repeated hour (autumn) fires once.

**misfire**: if Loom wasn't running when slots came due (a laptop asleep, a deploy), `run_once` fires once
when it wakes and moves on to the next future slot; `skip` just moves on.

**overlap**: each scheduled run gets its own run id (`MorningDigest@2026-09-28T01:30:00Z`) and journal, so
it can pause and resume like any other run while the schedule moves on. If an earlier run of the same
schedule is still paused when the next slot comes, `skip` doesn't start another; `queue` does.

**Kept, not in memory.** When a script is loaded with a trigger store ([§6](#6-triggers-and-the-trigger-store)),
its schedules are **written to the store**:

- new schedules are added;
- changed ones are updated (keeping when they last ran);
- schedules removed from the script are **disabled**, never silently deleted.

Loading the same script again changes nothing. Without a store, classic agent schedules still run in
memory as before (with a warning that they won't survive a restart), and workflow schedules need a store.

Mistakes are reported with the line: an invalid cron field (`hour field: 25 is outside 0-23`), an unknown
time zone, `cron` together with `every`, a `run:` naming a workflow that doesn't exist.

---

## 6. Triggers and the trigger store

Everything Loom must do *later* is a **trigger**:

| Trigger | Id | Fires |
|---|---|---|
| Resume a paused run | `resume:<runId>` | once, at the reset |
| A workflow schedule | `schedule:<script>/<name>` | on its cron or interval, starting run `<name>@<slot>` |
| An agent-task schedule | `schedule:<script>/<name>` | on its interval, giving the agent its task |

Triggers live in a **trigger store**:

| Store | For | Layout |
|---|---|---|
| `FileTriggerStore(dir)` | one machine: the CLI, cron, systemd, launchd | `dir/triggers/<id>.json`, written atomically; claims are `<id>.claim` files; `dir/tick.lock` |
| `JdbcTriggerStore(dataSource)` | several instances sharing a database (e.g. Cloud Run) | table `loom_triggers` (`JdbcTriggerStore.createTable` or your migrations) |
| `InMemoryTriggerStore` | tests | |

**Each trigger fires once**, even with several processes or machines ticking the same store: firing
starts with an atomic **claim** (an exclusive file, or a conditional SQL `UPDATE`). If the process
holding a claim dies, the claim is taken over after 10 minutes. The trigger is never lost, and a trigger
whose firing completed is never fired again.

The `weave` CLI keeps its store next to your run directories by default (`<run dir>/../.loom-triggers`),
and puts scheduled runs under `<store>/runs/<schedule>@<slot>/`.

---

## 7. Waking Loom: daemon, system triggers, cloud

A store only records what is due. Something has to look at it now and then and fire what is due: that is
a **tick**. Pick whichever suits the machine.

### A process that's running anyway

```bash
weave daemon runs/.loom-triggers            # polls every 5 s (--poll 30s)
```

Or embed a `TriggerRunner` in your service ([§9](#9-embedding-in-java)).

### Let the operating system do it: system triggers

No Loom process needs to stay up. The OS runs `weave tick <store>`, which fires what is due and exits.

```bash
weave triggers install runs/.loom-triggers                 # shows exactly what it would do; changes nothing
weave triggers install runs/.loom-triggers --apply         # does it
weave triggers uninstall runs/.loom-triggers --apply       # removes exactly what it added
```

| `--backend` (default: detected) | What `--apply` sets up |
|---|---|
| `systemd` (Linux with a user session) | `~/.config/systemd/user/loom-<id>.service` + `.timer` (`OnCalendar=*:0/5`, `Persistent=true`, so a tick missed while the machine was off runs at boot), then `systemctl --user daemon-reload` and `enable --now` |
| `cron` (other Linux/Unix) | one line in **your** crontab: `*/5 * * * * <weave> tick <store> # loom:<id>`; every other line is left as it was |
| `launchd` (macOS) | `~/Library/LaunchAgents/dev.llm4j.loom.<id>.plist` with `StartInterval`, loaded with `launchctl bootstrap gui/<uid>` |
| `windows` | a per-user task `\Loom\<id>` repeating every 5 minutes (`schtasks /Create … /SC MINUTE /MO 5`) |
| `cloud-scheduler` | prints (and with `--apply` runs) `gcloud scheduler jobs create http loom-<id> … --uri=<url>/loom/tick` |

Options:

| Option | Meaning |
|---|---|
| `--every 5m` | Heartbeat interval. A resume happens at most this late. |
| `--mode exact` | Also add one OS entry at each pending trigger's exact time (systemd timers, crontab lines, launchd calendar entries, Windows one-time tasks). They are **re-synced after every tick**, and the heartbeat stays as a safety net. |
| `--env-file ~/.loom/env` | A file of `KEY=VALUE` lines loaded before each tick. **OS schedulers don't see your shell's environment**, so this is how API keys reach a tick. Keep it `chmod 600`; its contents are never copied into generated files. |
| `--weave /usr/local/bin/weave` | The command that runs weave (default: this Java and classpath). |
| `--url`, `--service-account`, `--region` | For `cloud-scheduler`. |

Safety: `install` changes **nothing** without `--apply`. It only writes **user-level** entries and never
needs root. Everything it writes is tagged with the store's id, and re-installing is idempotent.

**Heartbeat or exact?** Heartbeat (the default) is one entry that never needs re-syncing, and a resume
happens at most one interval late. Rate limits rarely need better than that. Use `exact` for long
intervals, or when a schedule must fire on the minute.

### Services that scale to zero: Cloud Scheduler → your endpoint

On Cloud Run (or any serverless host), keep triggers in SQL and expose a tick endpoint. Cloud Scheduler
calls it every few minutes, so the service only wakes when needed.

```java
@RestController
class LoomTickController {
    private final TriggerEndpoint endpoint;

    LoomTickController(DataSource db, RunHost host) {
        TriggerRunner runner = new TriggerRunner(new JdbcTriggerStore(db), host::fire, Clock.systemUTC());
        this.endpoint = TriggerEndpoint.fromEnvironment(runner, token -> oidc.verify(token, AUDIENCE));
    }

    @PostMapping("/loom/tick")
    ResponseEntity<String> tick(@RequestHeader Map<String, String> headers) {
        TriggerEndpoint.Response r = endpoint.handle("POST", headers);
        return ResponseEntity.status(r.status()).body(r.body());      // {"fired": 2}
    }
}
```

```bash
weave triggers install .loom-triggers --backend cloud-scheduler \
    --url https://studio-xyz.a.run.app --service-account loom-tick@my-project.iam.gserviceaccount.com \
    --region asia-south1 --apply
```

The endpoint accepts only `POST`, authenticated either by `X-Loom-Token` (compared in constant time with
the `LOOM_TRIGGER_TOKEN` environment variable) or by `Authorization: Bearer …` checked by your
`BearerVerifier`, e.g. Google OIDC verification. With neither configured, it refuses every call.

---

## 8. The weave CLI

```bash
weave run  <script> [--journal DIR] [--store DIR] [--wait]
                    [--max-tokens N] [--max-calls N] [--max-cost X --prices FILE]
                    [-w Workflow] [-i key=value …] [-l tools.loot]
weave resume <run dir>                        # resume a paused run now
weave tick   <store>                          # fire what is due, then exit (what system triggers run)
weave daemon <store> [--poll 5s]              # keep firing as things come due
weave triggers list      <store>
weave triggers pause|enable|cancel|fire <store> <id>
weave triggers install   <store> [--backend …] [--mode heartbeat|exact] [--every 5m] [--env-file F] [--apply]
weave triggers uninstall <store> [--apply]
weave schedule sync <script> --store <store>  # write a script's schedules to a store without running it
```

- **`--journal DIR`** makes a run durable. `DIR/run.json` records the script, inputs, limits and store,
  so `resume` and `tick` can rebuild the run later in a fresh process; `DIR/journal.json` is the journal.
- **`--wait`**: when the run pauses, stay alive, sleep until the reset, resume, and repeat until it ends.
- **Exit codes**: `0` done, `1` failed, `2` bad options, `3` stopped by a budget, `4` paused.

A paused run tells you what will happen next:

```text
⏸ Paused: google daily quota. Resumes at 2026-09-28 07:00 UTC (in 21h).
   A system trigger (systemd, heartbeat every 5m) will resume it. To resume now: weave resume runs/digest-1
```

or, when nothing is installed yet:

```text
   No system trigger is installed for runs/.loom-triggers: run `weave triggers install runs/.loom-triggers --apply`
   (or keep `weave daemon runs/.loom-triggers` running), or resume yourself: weave resume runs/digest-1
```

`weave triggers list` shows each trigger, when it is next due, its last outcome and why it exists:

```text
● resume:/home/me/runs/digest-1
    at 2026-09-28T07:00:07Z  next: 2026-09-28 07:00 UTC
    why:  google daily quota (GenerateRequestsPerDayPerProjectPerModel-FreeTier)
● schedule:/home/me/digest.loom/MorningDigest
    cron "0 7 * * *" Asia/Kolkata  next: 2026-09-29 01:30 UTC
    last: DONE
A system trigger (systemd, heartbeat every 5m) wakes this store.
```

---

## 9. Embedding in Java

The executor needs a **durable journal**, a **trigger store** and a **run id**. The host supplies the one
thing Loom can't know: how to rebuild a run from its id. That is where your agent factories, secrets and
inputs live.

```java
DataSource db = …;
JdbcRunJournal.createTable(db);
JdbcTriggerStore.createTable(db);
TriggerStore triggers = new JdbcTriggerStore(db);

HarnessExecutor newRun(String runId) {
    HarnessExecutor e = new HarnessExecutor(new LoomLoader().load("digest.loom"), tools, clients);
    e.setJournal(new JdbcRunJournal(db, runId));
    e.setTriggerStore(triggers);   // paused runs leave resume:<runId>; schedules are stored
    e.setRunId(runId);
    e.setScriptRef("digest.loom"); // how schedule triggers name this script
    e.setPriceTable(prices);       // optional
    e.initialize();
    return e;
}

// Start a run (e.g. from an HTTP request)
try {
    newRun("digest-42").executeWorkflow("DailyDigest", Map.of("topic", "AI agents"));
} catch (RunSuspended paused) {
    // RATE_LIMIT / BUDGET_WINDOW: a resume trigger is already stored. HUMAN: wait for the answer.
}

// Fire triggers: embedded loop, or TriggerEndpoint (above), or both
TriggerRunner runner = new TriggerRunner(triggers, (trigger, runId) -> {
    try {
        switch (trigger.target()) {
            case Trigger.ResumeRun r      -> newRun(r.runId()).executeWorkflow("DailyDigest", Map.of());
            case Trigger.StartWorkflow w  -> newRun(runId).executeWorkflow(w.workflow(), w.args());
            case Trigger.AgentTask a      -> newRun("task").runAgentTask(a.agent(), a.task());
        }
        return TriggerTarget.Outcome.done();
    } catch (RunSuspended s) {
        return s.resumeAt() != null ? TriggerTarget.Outcome.suspended(s.resumeAt(), s.getMessage())
                                    : TriggerTarget.Outcome.human(s.getMessage());
    }
}, Clock.systemUTC());
runner.onFired(f -> log.info("{} → {}", f.trigger().id(), f.outcome()));
runner.start(Duration.ofSeconds(5));
```

Useful executor APIs:

| Method | Purpose |
|---|---|
| `setJournal`, `setTriggerStore`, `setRunId`, `setScriptRef` | Durability and triggers |
| `setClock`, `setSleeper` | Time and inline waiting (tests use a fixed clock) |
| `addSuspensionListener(Consumer<RunSuspended>)` | Be told when the run pauses (e.g. update a UI) |
| `getResumes()` | How many times this run has been resumed |
| `setPriceTable`, `setTokenEstimator`, `setBudgetOverrides` | Budgets |
| `getRunBudget()`, `getAgentBudget(name)`, `spend()` | Reading spend |
| `runAgentTask(agent, task)` | One task for one agent, under the same limit handling |

`RunSuspended` carries `stepId()`, `reason()` (`HUMAN`, `RATE_LIMIT`, `BUDGET_WINDOW`), `resumeAt()` (null
when waiting for a person), `limit()` (the `RateLimitInfo`) and, for people, `prompt()`.

---

## 10. Observability

Audit events (to the script's `audit` logger, or `setAuditLogger`):

| Event | Data |
|---|---|
| `budget_warning`, `budget_refused` | budget, spent tokens/calls/cost, limits |
| `rate_limit_wait` | step, provider, scope, quotaId, resumeAt, waitMillis |
| `run_suspended` | step, reason, resumeAt, suspendedAt, provider, scope, quotaId, estimated, resumes |
| `run_resumed` | attempt, waitedMillis, step |

`TriggerRunner.onFired` reports each firing: trigger, run id, outcome (`DONE`, `FAILED`, `SUSPENDED`,
`HUMAN`, `SKIPPED`, `SKIPPED_OVERLAP`) and how late it fired. The journal's `#suspension` entry shows a
paused run's state at any time.

---

## 11. Walkthroughs

### A research agent on Gemini's free tier

```loom
rate_limits { on_limit: suspend }
agent Researcher { model: "gemini/gemini-2.5-flash" system: "You are Researcher." tools: [WebSearch] }
workflow Main(topic) {
    delegate "Research the history of {topic}" to Researcher -> history
    delegate "Research the state of the art of {topic}" to Researcher -> current
    delegate "Research the open problems of {topic}" to Researcher -> problems
    delegate "Write a briefing from {history} {current} {problems}" to Researcher -> briefing
}
```

```bash
weave triggers install runs/.loom-triggers --env-file ~/.loom/env --apply   # once
weave run research.loom --journal runs/brief-1 -i topic="agent memory"
# … ⏸ Paused: google daily quota. Resumes at 2026-09-28 07:00 UTC (in 9h).
```

The laptop can sleep. At 07:05 the systemd timer (or the next boot) runs `weave tick`, the finished
steps replay from the journal, and the rest runs on the fresh quota.

### A background agent with a daily allowance

```loom
budget { tokens: 200000 per day  when_exhausted: suspend }
agent Triage { model: "gemini/gemini-2.5-flash" system: "You are Triage." }
workflow Main() {
    for each ticket in backlog {
        delegate "Triage {ticket}" to Triage -> {ticket.id}
    }
}
```

It works through the backlog until the day's tokens are gone, pauses until midnight, and continues. It
never spends more than 200k tokens in a day, and nothing it finished is redone.

### A morning digest, every day

```loom
workflow DailyDigest(topic) { … }
schedule MorningDigest { cron: "0 7 * * *"  timezone: "Asia/Kolkata"  run: DailyDigest(topic="AI agents") }
```

```bash
weave schedule sync digest.loom --store ~/.loom/triggers
weave triggers install ~/.loom/triggers --env-file ~/.loom/env --apply
```

If a morning's run hits a quota, it pauses on its own and the next morning's run still starts on time;
`overlap: skip` holds the new one back only while yesterday's is still paused.

---

## 12. Troubleshooting and FAQ

**"Nothing resumed my run."** Run `weave triggers list <store>`. Is the resume trigger there, and is a
system trigger installed? Run `weave tick <store>` by hand and watch the output. For systemd:
`systemctl --user list-timers | grep loom` and `journalctl --user -u loom-<id>`.

**"The tick runs, but the model calls fail with authentication errors."** OS schedulers don't inherit
your shell's environment. Install with `--env-file` (Windows: set user environment variables).

**"My run failed instead of pausing."** Either the journal isn't durable (use `--journal`, or
`on_limit: suspend` explicitly), the reset is beyond `max_wait`, or the run passed `max_resumes`. The
message says which.

**"It paused on a limit I think is short."** Limits under 30 seconds never reach Loom. If the provider sent
no reset information, the time is an estimate (60 s, doubling); `run_suspended` shows `estimated=true`.

**"Can two machines tick the same store?"** Yes. Claims make each trigger fire once. Use the SQL store
across machines; a file store on a network share works but is best kept to one host.

**"What if I edit the script while a run is paused?"** The resume replays journaled steps by their
position in the script. Adding steps after the paused point is fine; reordering steps before it can
misalign the replay, just as for any durable run.

**"Does pausing lose money already spent?"** No. Usage is journaled per step and restored on resume;
nothing already paid for runs twice.

**"How do I stop a paused run for good?"** `weave triggers cancel <store> resume:<runId>`.

---

## 13. Syntax reference

```text
budget { tokens: N [per W]  calls: N [per W]  cost: "$X" [per W]  warn_at: P%  when_exhausted: stop|suspend|ask }
agent A { … budget { tokens: N  per_call: N  … } }
delegate … -> x budget N tokens | budget N calls | budget "$X"
loop until (…) max N budget N tokens { … } on_exhausted { … }
for each i in list budget N tokens { … } on_exhausted { … }
broadcast … budget N tokens

rate_limits { on_limit: suspend|wait|fail  max_wait: D  max_resumes: N }

schedule Name { cron: "m h dom mon dow" | every: D   timezone: "Zone"
                run: Workflow(arg="v", …)   misfire: run_once|skip   overlap: skip|queue }
schedule Name { initial_delay: "30s"  pattern: "24h"  agent: A  task: "…" }

W: minute | hour | day        D: 30s | 15m | 6h | 2d        P: 1–100
Live values: {_budget.spent} {_budget.remaining} {_budget.calls} {_budget.cost} {_budget.exhausted}
             {_run.resumes} {_run.lastSuspension.reason} {_loopExhaustedBy} {_loopRounds}
```

`budget`, `rate_limits`, `per` and `when_exhausted` are contextual keywords: existing scripts that use them
as names keep working.
