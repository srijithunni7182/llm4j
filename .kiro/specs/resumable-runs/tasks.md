# Implementation Plan: Resumable Runs

## Overview

Three phases, each shippable on its own:

1. **ai-agent4j signal.** Rate-limit parsing, honest inline waits, `RateLimitException` with a reset
   time, windowed budgets, and `RateLimited`.
2. **Loom suspension.** Policies, the suspension record, parallel suspension, and resume bookkeeping.
3. **Scheduling and hosting.** `RunScheduler` (memory/file/JDBC), CLI `--journal/--wait/resume/daemon`,
   and docs.

Defaults: durable runs suspend; non-durable runs wait inline up to 5 min; budgets still `stop` unless
`when_exhausted` says otherwise.

---

## Tasks

<!-- PHASE 1: ai-agent4j -->

- [ ] 1. Rate-limit info and parsers (`io.github.llm4j.ratelimit`)
  - [ ] 1.1 `RateLimitInfo`, `Scope`, `RateLimitParser`, `RateLimitParsers.standard()`
    - _Requirements: 1.1, 1.3_
  - [ ] 1.2 Parsers: Retry-After (seconds, HTTP-date), Anthropic headers, OpenAI headers (duration
        grammar), Google RPC body (RetryInfo, QuotaFailure, daily ⇒ next midnight in zone)
    - _Requirements: 1.1, 1.2_
  - [ ] 1.3 Fixture tests with exact expected instants
    - _Requirements: 1.1–1.3_

- [ ] 2. HTTP layer and providers
  - [ ] 2.1 `RetryPolicy`: inlineWaitThreshold, fallbackDelay, clock, sleeper
    - _Requirements: 1.3, 1.4_
  - [ ] 2.2 `HttpClientWrapper` 429 path: wait until reset (+jitter) or throw `RateLimitException(info)`
    - _Requirements: 1.4, 1.5, 1.7_
  - [ ] 2.3 Google/Ollama/Sarvam pass `RateLimitException` through
    - _Requirements: 1.6_
  - [ ] 2.4 Tests on MockWebServer
    - _Requirements: 1.4–1.7, 8.1_

- [ ] 3. Windowed budgets
  - [ ] 3.1 `Window`, `Budget.Builder.window/clock`, roll-over under lock, `lifetimeSpent()`
    - _Requirements: 2.1, 2.2_
  - [ ] 3.2 `BudgetExceeded.resetAt()`; `restore(Spent, Instant)`
    - _Requirements: 2.3, 2.4_
  - [ ] 3.3 Tests, including a reservation in flight across a roll-over and 32 threads at the boundary
    - _Requirements: 2.1–2.4_

- [ ] 4. Agent to harness
  - [ ] 4.1 `RateLimited extends AgentInterrupt`; ReActAgent maps `RateLimitException` and suspend-policy
        `BudgetExceeded`
    - _Requirements: 3.1, 3.2_
  - [ ] 4.2 `BudgetedLLMClient`: a 429 charges one call, no tokens
    - _Requirements: 3.3_
  - [ ] 4.3 Tests
    - _Requirements: 3.1–3.3_

- [ ] 5. Checkpoint: ai-agent4j suite green, coverage of new packages ≥ 90% lines / 85% branches

<!-- PHASE 2: Loom -->

- [ ] 6. Syntax
  - [ ] 6.1 Duration literal; `rate_limits { }`; `per minute|hour|day`; `when_exhausted`
    - _Requirements: 2.5, 4.1, 4.2_
  - [ ] 6.2 AST and import merge; parse errors with line numbers
  - [ ] 6.3 Parser tests (including `per` and `rate_limits` used as ordinary identifiers)

- [ ] 7. Suspension in `HarnessExecutor`
  - [ ] 7.1 `RunJournal.isDurable()`, `RunSuspended.resumeAt()/reason()`, clock and sleeper setters
    - _Requirements: 4.3, 4.4_
  - [ ] 7.2 Policies `suspend | wait | fail | ask` and the `max_wait` guard
    - _Requirements: 4.3, 4.7, 4.8_
  - [ ] 7.3 Journal the suspension; resume re-runs only the suspended step; `max_resumes`
    - _Requirements: 4.4, 4.5, 5.4_
  - [ ] 7.4 Parallel/broadcast/for-each: gather, suspend once at the latest reset
    - _Requirements: 4.6_
  - [ ] 7.5 Usage entries carry `at`; windowed restore
    - _Requirements: 2.4_
  - [ ] 7.6 `_run.resumes`, `_run.lastSuspension`; audit `run_suspended` / `run_resumed`
    - _Requirements: 7.1, 7.2_
  - [ ] 7.7 Tests (see verification.md)

- [ ] 8. Checkpoint: Loom suite green; existing budget and durability tests unchanged

<!-- PHASE 3: Scheduling and hosting -->

- [ ] 9. `RunScheduler`
  - [ ] 9.1 Interface, `WakeUp`, `RunResumer`, `AbstractPollingScheduler` with jitter
    - _Requirements: 5.1, 5.7_
  - [ ] 9.2 `InMemoryRunScheduler`, `FileRunScheduler`, `JdbcRunScheduler` (claim semantics, stale claims)
    - _Requirements: 5.1, 5.3_
  - [ ] 9.3 Executor registers/replaces wake-ups; overdue on start; cancel/resumeNow
    - _Requirements: 5.2, 5.4, 5.5, 5.6_
  - [ ] 9.4 Tests, including two JDBC schedulers racing on one H2 database
    - _Requirements: 5.1–5.7_

- [ ] 10. CLI
  - [ ] 10.1 `--journal <dir>` with `run.json`; exit 4 and the paused message
    - _Requirements: 6.1, 6.4_
  - [ ] 10.2 `--wait`, `weave resume`, `weave daemon`
    - _Requirements: 6.2, 6.3_
  - [ ] 10.3 CLI tests

- [ ] 11. Documentation: LOOM_GUIDE "Pausing and resuming", LOOM_PROMPT, READMEs, VS Code grammar,
      ai-agent4j README "Rate limits"; documented example parses
  - _Requirements: 8.3_

- [ ]* 12. Live check (needs a key): trigger a real Gemini free-tier 429 and confirm the parsed reset
- [ ]* 13. GetViral: background pack generation suspends on quota and resumes from the JDBC scheduler

- [ ] 14. Final checkpoint: all suites green

## Notes

- Tasks marked `*` are optional or need the user's API key.
- No thread is held during a suspension; the journal plus a wake-up row is the whole state.
- 429s are never billed as tokens.
