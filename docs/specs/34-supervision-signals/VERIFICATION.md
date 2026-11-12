# Phase 34 verification plan: closing the gaps, progress, signals and `wait any`

Status: **final for phase 34, ready to build. Nothing is built; every row is `not built`.** Section 0 lists what was read and what is still to read; section 1 lists what must be proved. Companions: [DESIGN.md](DESIGN.md), [TEST_STRATEGY.md](TEST_STRATEGY.md), [DECISIONS.md](DECISIONS.md), [phase 33 VERIFICATION](../33-async-workflows/VERIFICATION.md).
**Evidence levels:** E0 read in the source or documents; E1 a unit test; E2 an engine test with scripted models on the controlled scheduler; E3 a kill-and-resume or concurrency test; E4 run by hand on a real provider or process (the owner's gate). No live key is needed; `make test-live` is never run.

## 0. Survey

### Read (E0), with where

| # | Fact | Where |
|---|---|---|
| R1 | `wait` journals what it returned under the statement's step id (kind `WAIT`) and a replay applies it; the tree suspends only when no handle is `RUNNING`; a single `wait -> r` binds only for a completed child. | `ChildLauncher.await`, `bindOutcome` |
| R2 | A child that sleeps or waits for a person throws `RunSuspended`; `runChild` records `SLEEPING` or `WAITING` and **its thread ends**. | `ChildLauncher.runChild` |
| R3 | `ChildHandle.resume()` is called only from `InterventionCoordinator` (a run-control pause); nothing read wakes a suspended child in the process. | `ChildHandle`, `InterventionCoordinator` |
| R4 | Supervision is a `Watch` per handle ticked by a single-thread scheduled executor; each tick evaluates counted, then code rules, then judges; it journals `<step>#fire.N` and `<step>#judge.<rule>.<n>` in the **parent's** journal, with an intent entry before a steer, wrap-up or cancel. | `ChildLauncher.supervise`, `tick`, `apply`, `runJudges` |
| R5 | "Did anything change" is `signatureOf`: weighted tokens and calls plus the child's journal size; idle time and the judge's skip-if-unchanged use it. | `ChildLauncher.signatureOf`, `factsOf` |
| R6 | The judge sees `Judge.facts`: counters and `Trajectory` (calls by name and count, first-call order, checkpoints, refused calls); content only with `seeing content`; the verdict is pass, fail or unreadable. | `Judge`, `Trajectory` |
| R7 | The judge's budget is created when the `supervise` runs (`newJudgeBudget`, `enclosingBudgets`), after the `start`; it is charged to the enclosing budgets and not reserved at `start`. | `ChildLauncher.supervise` |
| R8 | `weave test` wires `ControlledChildScheduler` (children run one at a time when waited for) and reads `judges:` like `models:`; `children:` exists only as an `expect:` key. | `CaseRunner`, `TestChecker`, `Assertions` |
| R9 | The statement parser recognises `wait`, `cancel`, `steer`, `supervise` with `isWord(...)` and lookahead; `wait` takes an optional `all`. | `LoomParser` |
| R10 | In the repository's `.loom` scripts `report` is a word in **37** files (as a variable, `-> report`), `progress` in 2 (starter 16), `notify`, `signal` and `event` in 1 each, `any` in 17, `parent` in 5. | `grep` |
| R11 | The steer text limit is 2000 characters (`InterventionCoordinator.MAX_STEER_CHARS`). | `InterventionCoordinator` |
| R12 | `ChildStatus` has eight statuses, `SLEEPING` and `WAITING` unsettled; the `start` deadline is enforced (`checkDeadlines`). | `ChildStatus`, `ChildLauncher` |
| R13 | v3 words are absent: none of `notify`, `report`, `wait any` or `detach` appears in the parser or the AST. | `grep` |

### To read before the design is final (each ends in a short note under §0)

| # | Read | Why |
|---|---|---|
| P1 | `ChildLauncher.startLocked` and how a **replayed `start`** re-attaches to and re-runs a suspended child. | Risk 2: the wake path should reuse it. |
| P2 | `InterventionCoordinator` pause and resume in full, and `RunControl`. | A woken child and a run-control pause must not collide. |
| P3 | `ChildChecks` and `ScriptValidator`: where handle checks live and how the `start`/`supervise` pairing can be done. | D3, F9. |
| P4 | How the child's trace reaches `Trajectory`, and what else the trace carries that a progress fact could ride on. | D6, D4. |
| P5 | `wtest/CaseRunner` child handling beyond the scheduler line: how a case drives a child today and where a stub fits. | F5, D15. |
| P6 | Fabric: `loom-inspect-core` `Snapshot` and the `loom.run.start` record where the parent attribute was added; what an older console does with an unknown attribute. | F10, D16. |
| P8 | Whether a re-run over a child **suspended mid-journal** behaves as a top-level resume does (`LoomHost.resume`, `executeWorkflow` over a journal with a missing step entry), and whether `Rewinder` can rewind a suspended child. | D12, F1. |
| P9 | That **spend is restored** for a re-derived child executor (`BudgetCoordinator.spendRestored`, the `#usage:` journal entries) and that the outer budgets re-installed by `installOuterBudgets` charge correctly after a wake. | D12 rule 7, A9. |
| P10 | How **trace events behave during a replay** (whether replayed steps emit model-call and tool events), for the `Trajectory` after a restart. | D12 rule 7, G35. |
| P7 | `HumanInterface` and `RunJournal.answer`: the nearest precedent for a durable external input reaching a suspended run. | D8: the mailbox should follow it. |

## 1. Acceptance: what must be true when the phase is built

Every row is `not built`.

| # | The claim | How | Level |
|---|---|---|---|
| A1 | `report`, `progress`, `notify`, `signal`, `any` and `parent` are contextual and **every existing script, including the 37 that use `report` and the two that use `progress`, still loads and runs unchanged** (G30) | the parse tests and `RepositoryScriptsTest` | E1 |
| A2 | Every load error and `weave check` finding of TEST_STRATEGY §2 fires with a message that says what to do | one test each | E1 |
| A3 | The closure items F1 to F9 are done, each with its test: `cancel ... rewind to` (G25), the status error for an unfinished `-> r` (G27), the wait on the injected clock (G28), the stubbed child, the worst-case cost, `why` on a child folder, the handle runtime guard (G29), the sleeping-child test, the two warnings | tests per item | E2 |
| A4 | The judge's budget is reserved at `start` when the `supervise` can be paired, and an unpaired one fails with a message (G26) | engine tests | E2 |
| A5 | G7 and G13 of phase 33 were broken on purpose and fail; G2's resume half has an engine test | the guard checks | E2 |
| A6 | Declared progress: validated against the declaration, bounded (dropped and recorded beyond the bounds), `unchecked`, journaled in the child, readable after a restart, restored by a rewind (G21) | tests | E2 and E3 |
| A7 | `assert progress.<field> changes within <duration>` fires when the field has not changed, and never reads its meaning | tests on the virtual clock | E2 |
| A8 | A signal is received **exactly once** across a crash at every journal write (G16) and one sent before the wait is not lost (G17) | the kill-point test extended; a unit test | E3 |
| A9 | A `notify` wakes a waiting child; one run of a child at a time under a racing `notify` (G19) | engine and concurrency tests | E2 and E3 |
| A9b | A `sticky` signal is read by every wait without being consumed and a message is consumed once, FIFO per name (G31); `weave check` refuses two same-name waits that can be active at once (G32) | tests | E2 |
| A9c | A wake is idempotent and single flight (G33); a child waiting on a signal does not make the parent suspend (G34); the trajectory survives an in-process wake (G35) | tests | E2 and E3 |
| A10 | A `notify` to a settled child is a recorded no-op; an old generation's signal is not delivered to a new one (G20); the limits fail loudly (G24) | tests | E2 |
| A11 | `wait any` returns signal, finished and timeout events; the winner and the cursor are journaled and a replay returns the same (G18) | tests; the kill-point test | E2 and E3 |
| A12 | `supervise ... when signal "name"` fires on arrival, and its actions' text stays literal (G23) | tests | E2 |
| A13 | LA30 fires; a signal's payload keeps its provenance both ways (G22); a child signals only its parent and a parent only its own handles | the audit and oracle tests | E2 |
| A14 | `weave test` runs `signals:`, the stubbed child with `progress` and `signal` steps, and the new `expect:` keys | the vocabulary tests | E2 |
| A15 | Graph, `explain`, `timeline`, `why`, `fork` (copying the mailbox entries) handle progress and signals; the renderer is in sync | snapshot and command tests | E2 |
| A16 | Fabric and the live page show pending signals and the latest progress; **an old console reads a newer process** (F10) | the `loom-inspect` and `loom-fabric` tests | E2 |
| A17 | VS Code, the CTK scenarios (including a woken child across a restart), the starter(s), the tutorial **chapter of its own** and `docs-check` pass (F11) | each module's tests | E2 |
| A18 | Every document in D16 is updated and consistent, including the root README and the workflow-guide skill (F11), and `docs/API_COMPATIBILITY.md` | `docs-check`; a grep for each new word | E1 |
| A19 | `mutation-children` covers the new classes at 85% or more; new classes meet the coverage gates | the profile run; `make test-fast` | E1 |
| A20 | The extended **demo** runs: a child asks for help, the supervisor reacts, the judge's headline case is in starter 18's tests (D5) | the starter's tests, then the owner by hand | E2 then **E4** |
| A21 | The provenance oracle passes with the new canary cases | `make test-oracle` | E2 |

## 2. How the report is written

At the end of the session the report states, for each row, the level reached and the evidence (the test names), **what was not run** (`make test-full`, the other `slow` tests, anything at E4), the risks of DESIGN §7 that turned out to be real, and any decision that was changed and why. A row is never marked at a higher level than its test.
