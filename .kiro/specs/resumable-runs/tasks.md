# Implementation Plan: Resumable Runs

## Overview

Three phases, each shippable on its own:

1. **ai-agent4j signal.** Rate-limit parsing, honest inline waits, `RateLimitException` with a reset
   time, windowed budgets, and `RateLimited`.
2. **Loom suspension.** Policies, the suspension record, parallel suspension, and resume bookkeeping.
3. **Triggers and hosting.** A persistent `TriggerStore` (file/JDBC) for resumes and `schedule` blocks
   (cron/interval), `TriggerRunner`, system triggers (cron, systemd, launchd, Windows, Cloud
   Scheduler), the CLI and docs.

Defaults: durable runs suspend; non-durable runs wait inline up to 5 min; budgets still `stop` unless
`when_exhausted` says otherwise.

---

## Tasks

<!-- PHASE 1: ai-agent4j -->

- [x] 1. Rate-limit info and parsers (`io.github.llm4j.ratelimit`)
  - [x] 1.1 `RateLimitInfo`, `Scope`, `RateLimitParser`, `RateLimitParsers.standard()`
    - _Requirements: 1.1, 1.3_
  - [x] 1.2 Parsers: Retry-After (seconds, HTTP-date), Anthropic headers, OpenAI headers (duration
        grammar), Google RPC body (RetryInfo, QuotaFailure, daily ⇒ next midnight in zone)
    - _Requirements: 1.1, 1.2_
  - [x] 1.3 Fixture tests with exact expected instants
    - _Requirements: 1.1–1.3_

- [x] 2. HTTP layer and providers
  - [x] 2.1 `RetryPolicy`: inlineWaitThreshold, fallbackDelay, clock, sleeper
    - _Requirements: 1.3, 1.4_
  - [x] 2.2 `HttpClientWrapper` 429 path: wait until reset (+jitter) or throw `RateLimitException(info)`
    - _Requirements: 1.4, 1.5, 1.7_
  - [x] 2.3 Google/Ollama/Sarvam pass `RateLimitException` through
    - _Requirements: 1.6_
  - [x] 2.4 Tests on MockWebServer
    - _Requirements: 1.4–1.7, 10.1_

- [x] 3. Windowed budgets
  - [x] 3.1 `Window`, `Budget.Builder.window/clock`, roll-over under lock, `lifetimeSpent()`
    - _Requirements: 2.1, 2.2_
  - [x] 3.2 `BudgetExceeded.resetAt()`; `restore(Spent, Instant)`
    - _Requirements: 2.3, 2.4_
  - [x] 3.3 Tests, including a reservation in flight across a roll-over and 32 threads at the boundary
    - _Requirements: 2.1–2.4_

- [x] 4. Agent to harness
  - [x] 4.1 `RateLimited extends AgentInterrupt`; ReActAgent maps `RateLimitException` and suspend-policy
        `BudgetExceeded`
    - _Requirements: 3.1, 3.2_
  - [x] 4.2 `BudgetedLLMClient`: a 429 charges one call, no tokens
    - _Requirements: 3.3_
  - [x] 4.3 Tests
    - _Requirements: 3.1–3.3_

- [x] 5. Checkpoint: ai-agent4j suite green, coverage of new packages ≥ 90% lines / 85% branches

<!-- PHASE 2: Loom -->

- [x] 6. Syntax
  - [x] 6.1 Duration literal; `rate_limits { }`; `per minute|hour|day`; `when_exhausted`
    - _Requirements: 2.5, 4.1, 4.2, 6.1_
  - [x] 6.2 AST and import merge; parse errors with line numbers
  - [x] 6.3 Parser tests (including `per` and `rate_limits` used as ordinary identifiers)

- [x] 7. Suspension in `HarnessExecutor`
  - [x] 7.1 `RunJournal.isDurable()`, `RunSuspended.resumeAt()/reason()`, clock and sleeper setters
    - _Requirements: 4.3, 4.4_
  - [x] 7.2 Policies `suspend | wait | fail | ask` and the `max_wait` guard
    - _Requirements: 4.3, 4.7, 4.8_
  - [x] 7.3 Journal the suspension; resume re-runs only the suspended step; `max_resumes`
    - _Requirements: 4.4, 4.5, 5.4_
  - [x] 7.4 Parallel/broadcast/for-each: gather, suspend once at the latest reset
    - _Requirements: 4.6_
  - [x] 7.5 Usage entries carry `at`; windowed restore
    - _Requirements: 2.4_
  - [x] 7.6 `_run.resumes`, `_run.lastSuspension`; audit `run_suspended` / `run_resumed`
    - _Requirements: 9.1, 9.2_
  - [x] 7.7 Tests (see verification.md)

- [x] 8. Checkpoint: Loom suite green; existing budget and durability tests unchanged

<!-- PHASE 3: Scheduling and hosting -->

- [x] 9. Persistent triggers (`loom.trigger`)
  - [x] 9.1 `Trigger`, `Target` variants, `Misfire`, `Overlap`; `CronSchedule` (5-field, zones, DST)
    - _Requirements: 5.1, 5.2, 5.3_
  - [x] 9.2 `TriggerStore`: in-memory, file (atomic writes, exclusive claim files, tick lock), JDBC
        (`loom_triggers`, claim UPDATE, stale claims)
    - _Requirements: 5.1, 5.5, 7.7_
  - [x] 9.3 `TriggerRunner`: `tick()`, embedded loop, firing rules, misfire, overlap, jitter, max_resumes
    - _Requirements: 5.4–5.9, 5.11_
  - [x] 9.4 Executor upserts `resume:<runId>` on suspension; per-run ids for scheduled workflows
    - _Requirements: 5.4, 5.6, 5.10_
  - [x] 9.5 Tests, including two JDBC runners racing on one H2 database and two `weave tick`s on one dir
    - _Requirements: 5.1–5.11_

- [x] 10. Schedules in Loom
  - [x] 10.1 Parser: `cron`, `every`, `timezone`, `run`, `misfire`, `overlap`; old fields still accepted
    - _Requirements: 6.1, 6.2, 6.4_
  - [x] 10.2 Reconcile script schedules into the store on `initialize()`; in-memory fallback logs a warning
    - _Requirements: 6.3, 10.2_
  - [x] 10.3 Tests

- [x] 11. System triggers (`loom.trigger.system`)
  - [x] 11.1 `SystemTriggerBackend`, `Plan`, `PlanApplier`, `CommandRunner`; heartbeat and exact modes
    - _Requirements: 7.2, 7.3, 7.4_
  - [x] 11.2 Backends: cron, systemd (user), launchd, windows (schtasks), cloud-scheduler (gcloud command)
    - _Requirements: 7.2, 7.5, 7.6_
  - [x] 11.3 `TriggerEndpoint` (HTTP tick with OIDC or `LOOM_TRIGGER_TOKEN`)
    - _Requirements: 7.5_
  - [x] 11.4 Exact-mode re-sync after tick; backend auto-detection
    - _Requirements: 7.3_
  - [x] 11.5 Golden-file tests per backend; real-crontab integration test with a temp HOME (skipped if
        absent)

- [x] 12. CLI
  - [x] 12.1 `--journal` + `run.json`, `--store`; exit 4 and the paused message naming the system trigger
    - _Requirements: 8.1, 8.5_
  - [x] 12.2 `--wait`, `resume`, `tick`, `daemon`, `triggers list|pause|cancel|fire|install|uninstall`,
        `schedule sync`
    - _Requirements: 7.1, 8.2–8.4_
  - [x] 12.3 CLI tests

- [x] 13. Documentation: LOOM_GUIDE "Pausing, resuming and scheduling" (with a system-trigger how-to
      per OS and for Cloud Run), LOOM_PROMPT, READMEs, VS Code grammar, ai-agent4j README "Rate limits";
      documented examples parse
  - _Requirements: 10.3_

- [ ]* 14. Live check (needs a key): trigger a real Gemini free-tier 429 and confirm the parsed reset
- [ ]* 15. GetViral: background pack generation suspends on quota and resumes via Cloud Scheduler →
      `TriggerEndpoint` on the JDBC store
- [ ]* 16. Real system-trigger smoke test on the user's machine (install, fire, uninstall)

- [x] 17. Final checkpoint: all suites green

## Notes

- Tasks marked `*` are optional or need the user's API key.
- No thread is held during a suspension; the journal plus a trigger row is the whole state.
- Triggers are never memory-only outside tests; the store is the truth, and system triggers only wake Loom.
- `weave triggers install` changes nothing without `--apply`, and only writes user-level entries.
- 429s are never billed as tokens.
