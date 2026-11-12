# Phase 34 verification plan: closing the gaps, progress, signals and `wait any`

Status: **final for phase 34. 34a (the closure) is built and waiting at the review gate; 34b to 34e are not built.** Rows A3, A4 and A5 reached E2 in 34a; every other row is `not built` (see §1b, which is kept current). Section 0 lists what was read and what is still to read; section 1 lists what must be proved. Companions: [DESIGN.md](DESIGN.md), [TEST_STRATEGY.md](TEST_STRATEGY.md), [DECISIONS.md](DECISIONS.md), [phase 33 VERIFICATION](../33-async-workflows/VERIFICATION.md).
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

### Survey notes (read in the build session of 34a, E0)

Depth is stated per note: "read" means the code named was opened and followed; "grep only" means the mechanism was located but not read line by line.

- **P1 how a replayed `start` re-attaches to and re-runs a suspended child.** *Read* (`ChildLauncher.startLocked`, `runChild`, `HarnessExecutor.deriveChild`). A replayed `start` finds its own entry in the parent's journal (so it does not write it again), opens the child's journal by the derived id (`ChildRuns.openJournal`: the same journal every time), builds a new child executor over it with `deriveChild`, rebinds the arguments (kept in memory, resolved again by the replayed `start`), and submits `runChild`, which calls `executeWorkflow` over the child's journal: **every recorded step is replayed from the journal and the first unrecorded one runs**. A child that is asleep is re-run the same way. So the re-run path already existed for a replay; 34a factored it out of `startLocked` (`launch` and `body`) and the wake in 34c will call it. Where it differs from a wake: after a *process* restart the in-memory arguments and `Trajectory` are rebuilt by the replayed `start`, not carried (rule 7 only covers an in-process re-run).
- **P2 `InterventionCoordinator` pause and resume, `RunControl`.** *Grep only, plus `RunControl.checkpoint` read.* `ChildHandle.resume()` is called only from `InterventionCoordinator` for a run-control pause (confirmed again). `RunControl.checkpoint(step)` throws `RunStopped` for STOP and `RunSuspended.byOperator` for SUSPEND before each step; that is the point where a parent's cancel and a rewind request reach a running child. The collision of a woken child with a run-control pause is **not yet read**: it belongs to 34c.
- **P3 `ChildChecks`, `ScriptValidator`: where handle checks live; the `start`/`supervise` pairing.** *Read* (`ChildChecks` in full). Handle checks are per workflow and by the handle's written form (`HandleRef.describe()`). The pairing is done on the AST at `start` time (`ChildPairing.superviseOf`), not in the parser, so no AST mutation: both the check and the launcher use it.
- **P4 how the child's trace reaches `Trajectory`.** *Read* (`deriveChild`, `Trajectory.onEvent`). `deriveChild` registers the child's own `Trajectory` as a trace listener; it counts `TOOL_CALL`, `TASK_START` and `DELEGATE_START` and remembers `CHECKPOINT`. Nothing else rides on it for a progress fact (34b will read the child's journal instead, as the design says).
- **P5 `wtest/CaseRunner` child handling.** *Read.* The runner sets a `ControlledChildScheduler`, in-memory child journals, `setSuperviseTick(ZERO)` and `clock.afterAdvance(tickChildren)`, so **only a sleep on the virtual clock makes the supervisor look** (a scripted model call also makes it look through the child's progress listener). That is why a stubbed child (F5) plays its steps with `sleeper().sleep(after)`.
- **P6 Fabric wire format.** *Grep only.* `loom-inspect-core` has `Semconv.LOOM_PARENT_RUN_ID` and `InspectServer` lists a parent's children. Not read; belongs to 34e (F10).
- **P7 `HumanInterface` and `RunJournal.answer`.** *Grep only.* `RunJournal.answer(stepId, value)` is a default method that writes a person's answer; `CaseRunner` calls it and re-runs the workflow over the same journal, which is the same shape as a wake (a journal entry first, then a re-run).
- **P8 whether a re-run over a child suspended mid-journal behaves as a top-level resume, and whether a suspended child can be rewound.** *Read* (`Rewinder`, `Generations`, `RunTravel`, `StopHandlers`; `DelegateRunner`'s stop-and-rewind lines by grep only). **Confirmed by tests**: a child asleep at `sleep 1h` was rewound to a checkpoint and re-run over its own journal (`ChildRewindTest`): the steps before the checkpoint replayed and did not run again, the steps after it ran in generation 2, and the old generation's entries stayed. **The design's "plausible, unverified" claim that cancel-rewind needs the re-run path is true for a child with no thread (asleep or waiting)**, which has nothing to catch a signal. For a *running* child I did not use the child's own `on_stopped` machinery (it needs the child to declare a rewind on the step and applies per agent): the parent writes a request in the child's journal, asks the child to stop (`RunControl` STOP, honoured at the next step), and re-runs it from the checkpoint when its body ends. That works whatever the child's script declares.
- **P9 spend restored for a re-derived child executor; outer budgets after a wake.** *Read* (`BudgetCoordinator.restoreSpend`, called at the start of `runWorkflow`), and **confirmed by a test** (`ChildClosureTest.aChildRunAgainRestoresItsOwnSpend...`): a child that spent 30 tokens and was re-run restored 30 from its `#usage` entries and spent 15 more (45), and the parent, which holds the outer budgets in memory, was charged only the 15 (45, not 60 or 75). **Not covered:** after a *process* restart the parent's own budget is rebuilt from the parent's journal, which does not hold what its children spent; that is the phase 33 behaviour and was not changed.
- **P10 how trace events behave during a replay.** *Read* (`TaskRunner`: a recorded step returns before `TASK_START` is traced; `DelegateRunner` traces `DELEGATE_START` after the recorded check). **A replay emits no call events for steps it skips**, so a re-derived executor's `Trajectory` starts empty and misses what the child did before. This is why D12 rule 7 is needed, and it is covered by `ChildRerunCarryTest` (calls, first-call order, checkpoints and refusals are carried; so are the `never calls` names the supervisor had forbidden). `CHECKPOINT` events are emitted on replay (the statement runs again), which is harmless because the set deduplicates.

### Where the code contradicts a decision or the design, and where I read the design differently (34a)

Nothing contradicts a decision. Four things the owner should look at, none of which changed a decision:

1. **F4 "the child's budget times the live cap".** I count the child's whole budget once for each `start` the walk reaches, so a bounded loop multiplies it by its `max` (`ChildCostTest`). Multiplying a single `start` by `max_children` would price starts that cannot happen; a loop that starts more children at once than the cap is warned about by `weave check` (F9) instead. If the owner meant the other reading, it is a one-line change in `CostEstimator.start`.
2. **F6 "a small `run.json` (workflow, parent id, inputs summary)".** `FolderChildRuns.record` writes the parent's script and settings with the child's workflow and **no inputs** (a `start`'s arguments are in memory and may be model text, so they are not kept) and the parent id stays in `child.json`. The child folder is also refused by `weave resume` (`Runs.execute`), because it cannot be run on its own. `weave why` opens it.
3. **F1 "reusing `Rewinder`".** `Rewinder` is the in-script engine; I reused the engine behind `weave rewind` (`RunTravel.resolve` and `Generations.start`), which is what writes a boundary for an outside request. The count "at most N" is per checkpoint in the child's own journal (so a loop cannot get round it), not per parent statement.
4. **F15 "the supervisor's steer is delivered".** The new case in starter 18 passes a judge once and fails it once on virtual time, and the steer rule fires (`fired: [ { rule: 3, action: steer } ]`), but a scripted child has no agent running, so the steer is recorded as *not delivered*. Delivery to a running steerable agent is tested at the engine level (`ChildJudgeTest`). Showing delivery in a starter case would need a scripted model call that takes virtual time, which is new `weave test` vocabulary that no decision asks for; I did not add it.

A fifth, found while building: an earlier phase 33 test (`ChildLauncherDetailTest.aWaitThatTimesOutWhile...`) passed only because it used a frozen clock and a real one-second wait, the bug F3 names. It now moves the virtual clock. A sixth: `ChildCheckDetailTest` asserted the "not available yet" refusal of `cancel ... rewind to`; it now asserts that an undeclared checkpoint is still refused and a declared one is accepted.

## 1. Acceptance: what must be true when the phase is built

Rows A3, A4 and A5 are at E2 (see §1b); every other row is `not built`.

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

## 1b. What the build reached (kept current; written at the end of each sub-phase)

### 34a, the closure (built; review gate)

Commits on the branch, one concern each. `make test-core` passed after the sub-phase; `make docs-check` passed with the documentation edits; `make test-fast`, `make format-check` (it covers core and addons, which this sub-phase did not touch), `make test-oracle` and the mutation profile are for the end of the phase (34e) and **were not run**; `make test-full` and the other `slow` tests were not run (CI does that).

| Item | What was built | Guard (broken on purpose once) | Tests | Level |
|---|---|---|---|---|
| D12 step 1 | `ChildLauncher.launch` and `body` (one launch method, shared by `start` and a re-run), a `Live` that keeps the arguments, caps, outer budgets and label and changes its attempt, `ChildMonitor` (a version counter with a condition), a `settle` that sleeps on it | G35 | `ChildMonitorTest`, `ChildRerunCarryTest` | E1, E2 |
| F1 | `cancel h rewind to <cp> at most N times`: a request in the child's journal, a boundary by `RunTravel.resolve` and `Generations.start`, effects kept, the child re-run on the launch method (asleep or waiting: at once; running: stop at the next step, then re-run); cancel wins; a child that ends first lapses the request; the statement is idempotent over a replay | G25 (the limit) | `ChildRewindTest` (5), `ChildCheckDetailTest` | E2; the running-child case uses real threads |
| F2 | `wait -> r` binds `Unavailable` for an unfinished child; reading it raises the status error; `weave check` warns on an unguarded read | G27 | `ChildClosureTest`, `ChildTextGuardTest`, `StartWaitEngineTest` | E2 |
| F3 | a wait counts the injected clock | G28 | `ChildClosureTest.aWaitThatTimesOut...`, `ChildLauncherDetailTest` | E3 (real thread, held effect) |
| F4 | the worst case counts each start's budget and each judged supervise's budget; the typical case walks the child's steps | none named | `ChildCostTest` (5) | E1 |
| F5 | `children:` in `weave test` (states `working`, `completed`, `failed`, `waiting`; `after:`), `ScriptedChild`, `ChildScripts`, the checker | none named | `StubbedChildTest` (9) | E2 |
| F6 | a child folder carries `run.json`; `weave why` opens it; `weave resume` refuses it | none named | `FolderChildRunsTest` (3 new) | E1 |
| F7 | a handle is refused where text is built | G29 | `ChildTextGuardTest` | E1 |
| F8 | a sleeping child through `factsOf` | (broken once) | `ChildClosureTest.aSleepingChild...` | E2 |
| F9 | two warnings: starts at once past `max_children`, steers in a row or in one rule | none named | `ChildCheckDetailTest` (8 new) | E1 |
| F12 | pairing a `start` with its `supervise`; reservation covers the judge; an unpaired judged supervise reserves when it runs and says the child is unsupervised; `weave check` warns | G26 | `ChildClosureTest` (4), `ChildCheckDetailTest` | E2 |
| F13 | no code: `Trajectory` stays the judge's view; risk 7 of phase 33 is closed as "not followed, by choice" | | | decision |
| F14 | G7 and G13 broken on purpose and seen to fail (G7: stop honoured before the effect is journaled; G13: the skip, the 30 s floor and the `max` each removed); G2's resume half has an engine test (G2 broken: a non-deterministic child id) | G2, G7, G13 | `ChildCancelTest` (slow), `JudgeScheduleTest`, `ChildCheckTest`, `ChildClosureTest.aParentRunAgain...` | E2 |
| F15 | starter 18 has a case in which the judge passes then fails on virtual time and the steer rule fires (not delivered: see §0) | | `StartersTest` | E2; **E4 is the owner's** |
| P9, P10 | spend after a re-run is restored once, the parent is charged once; the trajectory is carried | | `ChildClosureTest.aChildRunAgainRestores...`, `ChildRerunCarryTest` | E2 |

**Acceptance rows.** A3 (F1 to F9): E2, tests above. A4 (judge reserved at `start`): E2, `ChildClosureTest`. A5 (G7 and G13 broken on purpose; G2's resume half): E2. **A14** (the `weave test` vocabulary): only the F5 part (`children:` with states); `signals:` and the progress and signal steps are 34b and 34c. **A20** (the demo): the judge case of D5 is at E2 on a stub; the extended demo and E4 are 34e and the owner's. A1 and A2 are partly touched (the F9 and F12 warnings, the F2 warning) and complete only when the new words exist. Everything else: `not built`.

**Documents updated in 34a:** `LOOM_GUIDE.md`, `LOOM_PROMPT.md`, `docs/guide/06`, the wiki page, `docs/RELEASE_NOTES_6_2_0.md`, `docs/API_COMPATIBILITY.md`. The rest of D16 moves in the sub-phase that builds each thing and in 34e.

**Risks of DESIGN §7 that turned out to be real in 34a.** Risk 1 (waking) was sized with the controlled scheduler on the rewind path: the real ones were an `Attempt` that must be marked done only after the child has let go of the console and the host (or a waiter returns early), a stale `Watch` that held the old executor (fixed by keeping `Live` stable and swapping the attempt), and a `forbid` that a new executor would have lost. Risk 2 (reading `startLocked`) was real and is answered by P1. Risk 3 (`wait any` ordering) and risk 4 (the contextual words) are for 34b and 34d. Risk 5 (Fabric) is for 34e. Risk 7 (what was not read): P2 in full, P6 and P7 remain.

**Not done in 34a, by design:** the wake by a signal, a mailbox (done in 34c), `wait any`, progress (done in 34b), the audit rule and the oracle cases (all later sub-phases); F10 and F11 (34e); `ChildRuns` does not yet tell a host that a re-run child is running again (a host's record says what it last heard, until the child ends).

### 34b, declared progress (built)

`make test-core` and `make docs-check` passed after the sub-phase. Not run: `make test-fast`, `make test-oracle`, the mutation profile and `make format-check` (end of the phase), `make test-full`.

| Item | What was built | Guard (broken on purpose once) | Tests | Level |
|---|---|---|---|---|
| Syntax (D6, D17) | `progress { field: type }` after a workflow's `)`, `report { field: value }` at a statement start, `assert progress.<f> changes within <d> { }`; `ProgressDecl`, `ReportStmt`, `AssertionKind.CHANGES_WITHIN`; flat types only | G30 (the word `report` reserved as a name: seen to fail) | `ProgressParseTest` (9: keyword, variable and text contexts; the load errors), `RepositoryScriptsTest`, `StartersTest` | E1 |
| Checks (D6) | undeclared field, a type that can be seen wrong, a `report` with no declaration (errors); declared and never reported (warning); a progress assertion needs the child to declare the field | | `ChildCheckDetailTest` (6 new) | E1 |
| Runtime (D6) | `report` validated and bounded, written to the child's journal under `<step>#progress`; dropped and recorded beyond 2000 characters or 1000 reports; read through `h.progress.<field>` from the journal | G21: no validation, no size bound, tag not unchecked (each seen to fail) | `ChildProgressTest` (8), `ChildProgressProvenanceTest` | E2 |
| Restart and rewind | a restarted parent reads the same progress and nothing is reported twice; after `cancel ... rewind to` the parent reads the value as of the checkpoint | | `ChildProgressTest`, `ChildCtkConformanceTest.childProgress...` | E2 |
| D7 assertion | the watch keeps, per field, the running time since it last changed (sleep does not count; the clock starts with the block); movement only | seen to fail with the clock stuck | `StubbedChildTest` (4 new) | E2 |
| D15 | a scripted child step takes `progress:`, checked against the declaration | | `StubbedChildTest` | E2 |
| D16 for progress | graph kind `child_report` (renderer synced), `weave explain`, `weave why`/`timeline` (names of the fields, never their values), the CTK scenario `child_progress.loom` | | `ChildGraphTest`, `ChildExplainAndRefusalTest`, `WhyProgressSummaryTest`, `ChildCtkConformanceTest` | E2 |

**Acceptance rows.** A1 (the new words are contextual): `report` and `progress` at E1; `notify`, `signal`, `any` and `parent` are 34c and 34d. A6 (declared progress): E2 (the restart case runs the parent again over its journals; there is no kill-point extension yet). A7 (the assertion): E2.

**Where I read the design differently.** (1) The key is `<step>#progress` (one entry for each execution of the statement, with a `seq` inside), not `#progress.<n>`: the step id carries the generation and makes the write idempotent over a replay; the latest report is the highest `seq` among the steps that are still the current generation, which is what makes a rewind show the value at the checkpoint. (2) A value is read at each supervise tick, so a change between two ticks is seen at the second (the "still" clock has the granularity of the tick). (3) A dropped report is written down for the first 100 only; later ones are counted in the audit (`progress_dropped`) so the journal stays bounded. (4) Found while building: the legacy bare-name substitution replaces the letter of a one-letter variable inside any text (`"reading"` with a variable `a`); it is the existing behaviour of scripts without `substitution: braces` and was not changed.

**Not in 34b:** Fabric's Children section and the live page (34e), the VS Code grammar and parity baseline (34e), the kill-point extension for the progress writes (34c, with the signal writes), the mutation profile (34e).

### 34c, signals (built)

`make test-core` and `make docs-check` passed after the sub-phase. The kill-point test and the concurrency tests are `slow`, so they are not in `make test-core`; they were run explicitly and pass. Not run: `make test-fast`, `make test-oracle` after the last change (it passed once, after the scripts were added), the mutation profile and `make format-check` (end of the phase), `make test-full`.

| Item | What was built | Guard (broken on purpose once) | Tests | Level |
|---|---|---|---|---|
| Mailbox (D8, D9, D13) | signals as journal entries in the receiver's run (`#sig.in/out.<name>.<n>`), messages FIFO per name, sticky latches, an epoch that keeps an old generation's signals out of a rewound child, an idempotent send keyed by the sender's step, idempotent "taken" markers, every limit failing loudly | G16, G20, G24, G31 (each seen to fail) | `MailboxTest` (19, including 8 racing senders) | E1 |
| Syntax (D17) | `notify`, `wait signal`, `wait h signal`, `when signal`; contextual in the three contexts | G30 | `SignalParseTest` (12) | E1 |
| Checks (D14, D8) | a handle not started, bad names, an oversized literal, no timeout, two waits for one name that can be active at once (a `parallel`, a `parallel for each`), warnings for a workflow that signals or waits for a parent nothing gives it, a steer whose text comes from a signal | G23, G32 | `SignalCheckTest` (17) | E1 |
| Audit (D14) | LA30 for reading, severity low when the text reaches a prompt or a task argument; no rule for sending | | `SignalAuditTest` (6), the audit and OWASP pages, `AuditCatalog` | E1 |
| Engine (D10, D12) | a child waiting holds no thread (`RunSuspended.SIGNAL`), the parent's `notify` wakes it single flight and idempotently, the tick enforces the timeout on the injected clock, cancel wins, a waiting child does not suspend the parent, the trajectory is carried, `when signal` fires once per arrival and takes the message, a send to a settled child is a recorded no-op, a wait with no parent fails, `notify parent` with no parent is a no-op | G17, G19, G33, G34, G35, G20, G22 (each seen to fail) | `SignalEngineTest` (31), `SignalProvenanceTest` (4) | E2 |
| Concurrency (D12, D15) | many racing notifies and a child that suspends and is woken 12 times: each message once, in order, never two runs at once | G19 | `SignalConcurrencyTest` (2 tests, each repeated 25 times, real threads, `slow`) | E3 |
| Kill-point (D15) | the whole tree killed before every non-effect journal write of an exchange (progress report, a suspending wait, a wake, a signal each way): each signal written and read once, the effect once, the answer the same | G16 (seen to fail with the idempotent send removed) | `SignalKillPointTest` (`slow`) | E3 |
| Oracle (D14) | `signal_to_child.loom`, `signal_to_parent.loom`, `signal_sticky.loom` in the kit; the sweep passes, and a direct test runs them and checks the runtime's belief at every exit | G22 | `CanaryProvenanceOracleTest` (`make test-oracle`), `SignalProvenanceTest` | E2 |
| D15 vocabulary | `signals:` (arrival on the virtual clock, stamped with its scripted time), a scripted child's `signal:` step, `expect: signals: { sent, received, timed_out }`, and a controlled scheduler that moves virtual time when a wait has nothing to run | | `SignalTestVocabularyTest` (5) | E2 |
| D16 | graph kinds `child_notify` and `child_signal_wait` (renderer synced), `weave explain`, `weave why`/`timeline`, the CTK scripts, starter 18 (a child asks for help, the supervisor steers it, two new cases) | | `ChildGraphTest`, `ChildExplainAndRefusalTest`, `WhyProgressSummaryTest`, `StartersTest` | E2 |

**What the kill-point test found.** On its first run it failed at a crash before the write `#effect-run-uid`: that is inside an effect's pending-to-done window, which the phase 33 kill-point test already excludes (a crash there leaves the effect's outcome unknown, which the effect rule owns, not a signal). The same exclusion is applied, and the test counts what it left out (at most 4 writes). Every other write is a kill point and passes.

**Acceptance rows.** A8 (exactly once across a crash, and not lost when sent before the wait): E3. A9 (a notify wakes a waiting child; one run at a time under racing notifies): E2 and E3. A9b (sticky and FIFO; same-name waits in a parallel refused): E2. A9c (idempotent and single flight; a waiting child does not suspend the parent; the trajectory survives a wake): E2. A10 (a settled child, an old generation, the limits): E2. A12 (`when signal`, a steer's text stays literal): E2. A13 (LA30; provenance both ways; who may signal whom): E2. A14 (the `weave test` vocabulary): `signals:`, the signal step and `expect.signals` are done, and `wait any` events are 34d. A1: `notify`, `signal` and `parent` at E1; `any` is 34d.

**Where I read the design differently.** (1) **Consumption is a "taken" marker in the mailbox's own journal**, written idempotently after the reader's journaled step (which is the authority), instead of a cursor journaled only in the reader. That lets a sender enforce "16 unread" without reading another run's journal, and a replay puts back a marker a crash left out. (2) A parent's `wait h signal` **ends at once when the child has settled and left nothing**, instead of waiting out the timeout: no signal can come. (3) A signal carries the time it was sent on the run's clock, and a wait does not take one that came **after its deadline**; without that a child driven on the controlled scheduler (one long scripted step) could hand a late signal to a wait that had timed out in virtual time. (4) A `once` rule that has fired takes no more messages, leaving them for a wait. (5) `wait signal` in a child ends in a `RunSuspended` even without a durable journal, because the wake is in the process; across a restart it relies on the parent's replay, as a sleeping child does.

**Not in 34c:** `wait any` (34d); Fabric's Children section, the live page, the VS Code grammar and parity baseline, the tutorial chapter, the skills and the root README (34e); the mutation profile (34e).

### 34d, `wait any` (built)

`make test-core` and `make docs-check` passed after the sub-phase; the kill-point test (`slow`) was run explicitly and passes. Not run: `make test-fast`, the mutation profile, `make format-check` (end of the phase), `make test-full`.

| Item | What was built | Guard (broken on purpose once) | Tests | Level |
|---|---|---|---|---|
| Syntax (D17) | `wait any handles timeout T -> event`, with `any` contextual (a handle called `any` is still waited for) | G30 | `WaitAnyParseTest` (4) | E1 |
| Checks | a timeout, an event variable and a started handle are required; no `on_timeout`/`on_failure`; reading event fields is not taken for a handle | | `SignalCheckTest` (3 new) | E1 |
| Engine (D11) | signals before finished children, then start order; a message taken once; a latch reported once; a finished child reported once; `remaining` counts the children not yet reported; a timeout event leaves the children running; empty handles return a timeout at once; asleep children suspend the tree; more than `max_children` handles fail naming it; the event is `unchecked`; the winner is journaled with the messages it took, and a replay returns the same event | G18: winner not journaled, message not taken, finish reported twice (each seen to fail) | `WaitAnyEngineTest` (13) | E2 |
| Kill-point (D15) | the tree killed before every non-effect write of a two-child `wait any` loop: the same four events in the same order, each signal taken once, each effect once | G18 | `SignalKillPointTest` (second test, `slow`) | E3 |
| Persistence | a mailbox read back from a file journal keeps its order, its taken markers and its limits | | `MailboxTest` | E1 |
| D15, D16 | `expect: events:` (in the order they came, from an ordinal in the journal entry); graph kind `child_wait_any` (renderer synced); `weave explain`; `weave why`/`timeline` | | `SignalTestVocabularyTest` (2 new), `ChildGraphTest`, `ChildExplainAndRefusalTest`, `WhyProgressSummaryTest` | E2 |

**Acceptance rows.** A11 (`wait any` returns signal, finished and timeout events; the winner and the cursor are journaled and a replay returns the same): E2 and E3. A1: `any` at E1; every new word of D17 is now contextual and tested in its three contexts. A14: `events:` done.

**Where I read the design differently.** (1) `name` is the handle's key for every event kind, and a signal's own name is `event.signal` (D11 gives both the handle's key and the signal's own name the word `name`). (2) `remaining` counts the children whose finish has **not been reported yet** (D11 says "not yet finished"): with the first meaning a collecting loop ends as soon as the last child finishes and can leave a finished child unreported when several finish before the first `wait any`. (3) A `wait any` over children that are all asleep or waiting for a person suspends the tree, as `wait` does, rather than returning a timeout.

### 34e, ecosystem and closure (built; mutation result below)

| Item | What was built | Guard (broken on purpose once) | Tests | Level |
|---|---|---|---|---|
| Fabric and the live page (F10, D13) | trace events `signal` (op, peer run id, name, sticky; never the text) and `progress`, mapped to `loom.signal` and `loom.progress`; `RunState` counts; the children listing gains `signals` and a child's `progress`; the page shows text-only cells | G29 (text reaching the page) | `ChildSignalsAndProgressTest`, `ChildMappingTest`, `SignalTraceTest`, `children.page.test.js` (browser) | E2 |
| VS Code (D16) | grammar rule that colours the new words only in their own shape, hover, four snippets checked by `weave check`, the parser-parity baseline regenerated | G30 (the three contexts: keyword, variable, text) | `grammar.test.ts`, `snippets.test.ts`, `contextualWords.test.ts`, `docs.test.ts` (793 tests) | E2 |
| CTK, starter | four scenarios (`child_progress`, `signal_to_child`, `signal_to_parent`, `signal_sticky`); starter 18 has a `need_help` signal rule and tests | | CTK runner, `StartersTest` | E2 |
| Tutorial chapter of its own (F11) | `docs/tutorial/14-supervising-and-talking.md` (the cookbook is now 15); every `loom` block loads without warnings; the web page is regenerated | | `TutorialExamplesTest`, `GuideTutorialPageTest` | E1 |
| Documents (D16) | guide, prompt, guide chapter 6, wiki, skills (workflow-guide, patterns, run-debugging), llms.txt, README tables, release notes, `API_COMPATIBILITY.md` | | `docs-check` | E1 |

## 2. How the report is written

At the end of the session the report states, for each row, the level reached and the evidence (the test names), **what was not run** (`make test-full`, the other `slow` tests, anything at E4), the risks of DESIGN §7 that turned out to be real, and any decision that was changed and why. A row is never marked at a higher level than its test.
