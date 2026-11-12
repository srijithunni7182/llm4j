# Phase 33 verification plan: starting and supervising workflows

Status: **draft for review. Nothing is built; every row is `not built`.** This is the list of things to **read** before the design is final (§0) and the list of things to **prove** when the phase is built (§1). Companions: [DESIGN.md](DESIGN.md), [TEST_STRATEGY.md](TEST_STRATEGY.md), [DECISIONS.md](DECISIONS.md).

**Evidence levels** (the one-liner used in the report): **E0** read in the source or documents; **E1** a unit test; **E2** an engine test with scripted models on the controlled executor; **E3** a kill-and-resume or concurrency test; **E4** run by hand on a real provider or a real process (the owner's gate).
No live provider key is needed for this phase and `make test-live` is never run.

## 0. Survey: facts read, and what is still to read

### Read already (E0), with where

| # | Fact | Where |
|---|---|---|
| F1 | `call` runs the child inside the **same executor** (a pushed variable frame, `executeWorkflow`, the result copied back); the arguments are resolved with a provenance tag and the result's tag comes back. | `HarnessExecutor.executeCall` |
| F2 | The journal is keyed by **stable step ids** (script position, loop round, branch) and a rewind adds a **generation** suffix (`Main/s5~2`); `Generations` keeps the boundaries in the journal. | `RunJournal`, `Generations` |
| F3 | `parallel` forks branch threads (`loom-branch-N`) and **waits for every branch**. | `HarnessExecutor` |
| F4 | `LoomRun` has steer, wrap up, cancel (an outcome or a checkpoint), pause, resume, await and the statuses `RUNNING`, `COMPLETED`, `SUSPENDED`, `STOPPED`, `FAILED`; it registers in `LiveRuns` by label. | `LoomRun`, `LiveRuns` |
| F5 | The parser recognises a statement by `isWord("run")` plus lookahead; top-level `supervise <Agent> {` is parsed as a `SuperviseDef` (`addSupervision`); the rule signals are a closed enum (`looping`, `tool_failing`, `context`, `steps`); actions are `steer`, `wrap_up`, `cancel`. | `LoomParser`, `SuperviseDef` |
| F6 | `Persistence` has none, file (`<dir>/runs/<id>/journal.json` and `run.json`) and JDBC (`loom_journal(run_id, step_id, kind, value_json)` plus registry, answers, triggers, ledgers); `LoomHost.newExecutor(runId)` assembles an executor with that run's journal. | `Persistence`, `LoomHost` |
| F7 | `weave run --journal DIR` builds its executor **directly** with a `FileRunJournal(runDir/journal.json)`, not through `LoomHost`. | `Runs.java` |
| F8 | Budgets: a `Budget` holds limits, spend and `reserved*` counters under a lock; calls reserve through a `BudgetSet` and settle after; `BudgetCoordinator` keeps the run, agent and step budgets and journals spend. | `Budget`, `BudgetCoordinator` |
| F9 | `InterventionCoordinator` holds the running agents with their handles, targets a step id, an agent name or `*`, journals `<step>#steer.N`, checks the guard and limits a steer to 2000 characters; `steerable` comes from the script. | `InterventionCoordinator` |
| F10 | Fabric and `loom-inspect-core` keep a **flat** `RunRegistry` keyed by process name and trace id; `RunState` has a run id, workflow, status, tokens, calls and cost and **no parent link**. | `RunRegistry`, `RunState` |
| F11 | `weave test` already has `clock:` (virtual time, `elapsed_virtual`), `remotes:` (a remote scripted one step per look), `fail:`, `steer:`/`stop:`, `humans:`/`resume:`. | `LOOM_GUIDE.md` |
| F12 | **17 files** switch over statement kinds (`StatementWalker`, the validator, the explainer, the graph, the cost estimator and others); `HaltStmt` is in 10. | `grep` |
| F13 | `start` and `wait` are not reserved words; they occur in the ladder language and in `rate_limits`; no repository script uses them as variable or agent names. | `TokenType`, the `.loom` files |
| F14 | A journaled run resumes by running the script again over its journal; a suspension ends execution with `RunSuspended`. | `LoomHost`, `RunJournal` |

### To read before the design is final (each ends in a short note under §0)

| # | Read | Why |
|---|---|---|
| P1 | `weave resume`, `tick` and `daemon`: how a run is found and resumed, for the CLI layout **and** the host layout, and how a child would be found. | Risk 2: two ways a run is built. |
| P2 | `parallel`'s join and `RunSuspended`, and the thread-local `scopes` in `BudgetCoordinator`. | Risk 4: a parking `wait`; the child's thread. |
| P3 | `BudgetSet` and the rest of `Budget` (the lock order, `reserve` and `settle`). | Risk 1: the hold. |
| P4 | `RunRegistry` (memory, files, JDBC) and the JDBC DDL; how an existing database is upgraded. | Risk 3: the parent column. |
| P5 | `PolicyToolHook`, `ApprovalGate` and the tool-call hook chain. | Risk 5: a preventive `never calls`. |
| P6 | `loom-inspect-core` `Snapshot` and the OTLP-shaped records; `loom-fabric` `FabricLoop`; compatibility of an old console with a new process. | Risk 6: the parent attribute. |
| P7 | How the evaluation dataset's `expect:` renders "what the run did". | Risk 7: the judge's facts view. |
| P8 | The parser's handling of `-> results[key]` for `delegate` and the key rule. | Risk 8: keyed handles. |
| P9 | The controlled-executor shape: how `ex.setSleeper` and `setClock` are used by the existing virtual-time tests. | Risk 9: determinism. |

### Survey notes (read in the build session, E0)

Depth is stated per note. "Read" means the code named was opened; "grep only" means the mechanism was located but not read line by line.

- **P1 resume, tick, daemon.** *Partly read.* `weave resume <runDir>` reads a `RunSpec` from the run directory and calls `Runs.execute` with the CLI layout; it removes the run's resume trigger from a trigger store on success (`WeaveCLI.resume`). `tick` fires due triggers from a store, `daemon` loops over it. All three find a run **by its run directory**, not by id, so a child under the CLI layout has to be a sibling folder with its own `RunSpec`; under `LoomHost` the registry (`RunRegistry`: memory, file, JDBC) finds a run by id. Not read: `RunSpec` fields and the daemon loop in full. **Consequence:** risk 2 is real: `ChildRuns` needs two implementations, and a child folder must carry a `RunSpec` that names the child workflow and its arguments.
- **P2 parallel's join, RunSuspended, scopes.** *Read.* `joinAll` waits for every branch, rethrows a halt or failure, and for pauses throws the one `RunSuspended` with the **latest** reset (or the first that needs a person). Branches run on a shared cached daemon pool (`loom-branch-N`) and copy the caller's thread state with `ThreadScope.capture().at(...).install()`; `scopes` is a `ThreadLocal<List<Budget>>` inside `ThreadScope`, set and restored around loops and `for each`. **Consequence:** a parking `wait` is not `joinAll` (it must also keep running supervision ticks and must not throw while a child can still run); a child needs its own `ThreadScope`, never the parent's. Risk 4 is real.
- **P3 Budget and BudgetSet.** *Partly read.* `Budget` already has `reservedTokens/Calls/Searches/Cost`, a global lock `order()` and a `BudgetSet` that locks members in that order, sorted and de-duplicated; `reserve` and `release` are package-level helpers inside `Budget`. There is no public way to hold part of one budget for another. **Consequence:** risk 1 is real; the hold is a core addition (33c). Not yet read: the full `reserve`/`settle` path of `BudgetSet`.
- **P4 RunRegistry and the JDBC DDL.** *Read.* There are two things called `RunRegistry`: `loom/host/RunRegistry` (memory, file, JDBC `llm4j_loom_runs`, `CREATE TABLE IF NOT EXISTS`, no `ALTER` anywhere in the module) and `loom-inspect-core`'s `RunRegistry` (flat, in memory). `RunRecord` is a public record of 12 components. **Consequence:** risk 3 is real: an existing `llm4j_loom_runs` table has no `parent_run_id` column and `CREATE TABLE IF NOT EXISTS` will not add one; the migration (an `ALTER TABLE ... ADD COLUMN` attempted once, tolerating "already exists") is new code in 33b. The old 12-argument `RunRecord` constructor is kept.
- **P5 tool-call hook chain.** *Located, not read in full.* `ToolHook.beforeCall(name, args)` returns allow, reject or replace; `HarnessExecutor` registers `PolicyToolHook` with `agentBuilder.addToolHook(...)`, and `ApprovalGate` is a separate class in `execution`. A `never calls` hook can be one more `ToolHook` added to the child's agent builder; order against the policy hook is to be read in 33f before the code. Risk 5 not yet proven either way.
- **P6 Fabric wire format.** *Located only.* `loom-inspect-core` `wire/Snapshot`, `Hello`, `Heartbeat`; `state/RunState` (status RUNNING, SLEEPING, PAUSED, WAITING, FINISHED, FAILED, STOPPED, no parent field); `loom-fabric` `FabricLoop`. Not read; to be read in 33h before a field is added. Risk 6 stays open.
- **P7 how `expect:` renders what a run did.** *Located only* (`wtest/Assertions`, `CaseProbe`, `LoomRunAssert`, `Matchers`). Not read; to be read in 33g. Risk 7 stays open.
- **P8 keyed targets.** *Read.* `nameOrReference` accepts `name[key]` where the key is one IDENTIFIER token (a dotted path such as `t.name` is one token in this lexer, which is what `-> results[task.name]` relies on). It returns the string `name[key]`; the same helper can serve `start ... -> work[t.name]`, no change to the key rule is needed.
- **P9 controlled executor and virtual time.** *Located, partly read.* Tests inject time with `executor.setClock(clock)` and `executor.setSleeper(sleeps::add)` (for example `LimitScript`, `TaskHarness`). There is no controlled task executor yet; branch threads are a static pool. The tick and children need an injected executor, as designed. Risk 9 is real.
- **Statement recognition.** `parseStatementBody` is an `if/else` chain on token types and `isWord(...)` lookahead; `halt` is `isWord("halt")`. Words used in the lexer as keywords (`TokenType.CALL`, `ALT`, `LOOP`) are not candidates; `start`, `wait`, `cancel` and `steer` lex as plain IDENTIFIERs, so a contextual check works as D19 says. Top-level `supervise` is handled in the script-level parser (`parseSupervise`); inside a workflow body there is no `supervise` statement yet.
- **Repository layout note.** The spec lived only on the unmerged branch `ccr-08cda518-w8aqfn`, not on `main`. That branch was merged into this one (docs only) to get it.

### Where the code contradicts a decision or the design (stopped, for the owner)

1. **Graph node kind `start` (D16, DESIGN 4.11) collides with the existing entry node.** `Kinds.START = "start"` is already the kind of the graph's entry node (`graph/Kinds.java`, drawn by the shared renderer). D16 names the new node kinds `start`, `wait` and `cancel`. Both cannot be called `start`. I did **not** pick another name. The graph item of D16 (node kinds, the dotted edge from a `start` to its `wait` or `supervise`, `sync-graph-render.sh`) is **not done**; a child statement is drawn as the existing `unknown` kind until the owner says what the new kind is called (for example `child_start`) or renames the entry node. `wait` and `cancel` have no clash.
2. **D4 says the child id carries "the parent's attempt number"; DESIGN 4.2 says the generation already in the step id is that number and no new counter is needed.** I follow DESIGN 4.2 (it is the later text and is consistent with D4's meaning: a resume keeps it, a rewind changes it). Raised here so it is not a silent choice.
3. **"A cost rule with no price table" is a `weave check` finding (D23), but the validator's `Context` carries no price table** (prices arrive at run and review time, `CostEstimator`). The check is therefore a warning that always fires for a `cost` rule and says what to run with (`--prices FILE`). If the owner wants it to be silent when a table is given, the validator needs the price table in its context.

### Build status by sub-phase

| Sub-phase | State | Notes |
|---|---|---|
| 33c Budgets and limits | **built, except the worst-case cost in `weave review`/`generate`** | The child's cap is the smaller of the start's and the script's, its spend is charged to the parent's budgets, a start is refused when the parent cannot cover it after the other live children (`reserve`, under one lock), `max_children`/`max_depth` and the host ceilings (`LoomHost.Builder.childLimits`), a child out of budget ends `failed` with `budget:`. **Departure from DESIGN risk 1:** the reservation is computed from the children's own budgets, so no new `Budget` hold in the core was needed; the core is unchanged. Not built: the judge budget reservation (33g), the review/generate worst case. |
| 33d Cancel and steer | **built, except rewind of a child, the rewind-cancels-previous-generation rule, `start ... timeout` (TIMED_OUT), and delivering a steer inside `supervise` (33e)** | `cancel` and its outcomes (marker in the child's journal, `h.partial`, a settled child is a no-op, a sleeping or waiting child settles at once, a running child stops at its next step so an effect finishes first), a parent that ends cancels its children with the grace period (not on a suspension or a crash), a parent suspended for anything but `wait` pauses its children, the `steer` statement (journaled once, "not delivered" is not an error), LA28 (`ChildAudit`), `A2AAudit.reach` follows `start`. **Not done:** `cancel h rewind to cp` refuses at run time ("cannot run yet"); the previous generation's children are not cancelled on a rewind; the `start` deadline is parsed but nothing enforces it; `weave audit` lists the child as part of its parent only through `A2AAudit.reach` (the trifecta itself is per agent and a child does not share the parent's transcript, so `TranscriptTaint` is deliberately unchanged). |
| 33b Child runs | **partly built** (see below) | `ChildRuns` SPI, `ChildIds`, `ChildHandle`/`ChildStatus`, `ChildScheduler` and the controlled one, `ChildLauncher` (journal-first `start`, parking `wait`, tree suspension when a child sleeps or waits for a person), `LoomHost`'s `ChildRuns`, `RunRecord.parentRunId` with the old constructor kept, the JDBC column and its migration. **Not built in 33b yet:** the CLI sibling-folder `ChildRuns` for `weave run --journal`, `weave fork` following links, `LiveRuns` labels, `LoomRun` children and the `LoomRun.Status` mapping, a `wait` timeout on the injected clock (it uses wall time), a read of `-> r` after a non-completed `wait` is empty, not an error (only `h.result` errors). |
| 33a The language | **built, except the graph kinds (finding 1), the keyed-variable additions and the fact reads in conditions, which need the runtime (33b)** | Parser, AST, walker, `StatementText`, `SubstitutedText`, explain, the checks (`ChildChecks`), a fail-closed refusal in the executor. |

## 1. Acceptance: what must be true when the phase is built

Every row is `not built`. "How" says what proves it; the level is the minimum accepted.

| # | The claim | How | Level |
|---|---|---|---|
| A1 | `start`, `wait`, `cancel`, `steer` and `supervise` parse as contextual words and **nothing else in the repository changes meaning** | the parse tests; `RepositoryScriptsTest` on every script | E1 |
| A2 | Each load error and each `weave check` error or warning of §3 of TEST_STRATEGY fires, with a message that says what to do | one test each | E1 |
| A3 | A started workflow is a **run of its own**: own executor, journal, budget, status, `LiveRuns` entry | engine tests over file, JDBC and the CLI layout | E2 |
| A4 | **Exactly one child and every effect once** across a crash at every point (G1) | the kill-point test; the guard broken on purpose | E3 |
| A5 | A resume re-attaches and a rewind starts a fresh child (G2) | engine tests | E2 |
| A6 | Every status and transition of D9; `is ok` fails closed; the result is an error unless completed | tests per status | E2 |
| A7 | `wait` parks while a child runs, suspends the tree only when nothing can run, returns on timeout, and `wait all` records every outcome (D8) | engine tests with the virtual clock | E2 |
| A8 | A parent ending cancels its children within the grace period (G3) | engine tests | E2 |
| A9 | A child has its own required budget charged to the parent, with reservation (G4); a child out of budget ends `failed` | engine tests; a concurrency test | E2 and E3 |
| A10 | `max_children` and `max_depth` hold and a start past them fails (G5); starts in loops are bounded | tests | E2 |
| A11 | Only `steerable` agents can be steered, with literal text (G6); not delivered is recorded | tests | E2 |
| A12 | A cancel finishes an in-flight effect first (G7); outcomes, `h.partial` and a rewind to a declared checkpoint work | tests | E2 |
| A13 | A handle never reaches a prompt, a tool, an agent or a return (G8) | tests | E1 |
| A14 | Provenance crosses the boundary (G9); the audit counts the child in the trifecta; LA28 fires | the oracle and audit tests | E2 |
| A15 | Every counter and comparison, `once`, fire-on-becoming-true, the tick, ordering, one steer per agent per tick (G15), a `cancel` ending evaluation | supervise tests on the virtual clock | E2 |
| A16 | `idle` and `elapsed` exclude sleep and waiting; the deadline counts it; `weave check` warns on a longer child sleep | tests | E2 |
| A17 | The four assertions work, and `never calls` is preventive (G10) | tests; the effect counter stays zero | E2 |
| A18 | The judge sees facts only by default (G11); the verdict is journaled and not re-asked on replay (G12); cadence, floor, `max`, skip and the judge budget hold (G13); only `steer`, `wrap_up` and `cancel use partial` are allowed | tests with `judges:` | E2 |
| A19 | A waiting parent keeps children running; another suspension pauses them (G14) | tests | E2 |
| A20 | `weave test` runs real and stubbed children with `children:`, `judges:`, `fired:`, `starts:` | the testing vocabulary tests | E2 |
| A21 | `weave graph`, `explain`, `review`, `generate`, `timeline`, `why` and `fork` handle children; the renderer is in sync | snapshot and command tests; `sync-graph-render` check | E2 |
| A22 | Fabric and the live page show the parent link, the grouping, a Children section and the cost total | the `loom-inspect` and `loom-fabric` tests | E2 |
| A23 | `LoomRun` lists children and `#label` reaches one through `--steer-via`; the host ceilings apply | tests | E2 |
| A24 | The VS Code grammar, snippets, hover and parity baseline; the CTK scenarios; starter 18 and its tests and picture; the tutorial chapter | `docs-check`, `StartersTest`, the extension tests, CTK | E2 |
| A25 | Every document in D16 is updated and consistent: the guide, the prompt, both READMEs' tables, the wiki, `llms.txt`, the audit page and OWASP map, the release notes, the workflow guide, the two skills, `docs/API_COMPATIBILITY.md` | `docs-check`; a grep of the repository for each new word | E1 |
| A26 | The mutation profile `mutation-children` is at 85% or more; new classes meet the coverage gates | the profile run; `make test-fast` | E1 |
| A27 | **The 60-second demo** runs: three researchers started, one steered, one cancelled, a restart in the middle, and `weave audit` and Fabric show the children and who may steer them | the starter's tests, plus the owner running it | E2 then **E4** |
| A28 | The provenance oracle passes with the new canary cases | `make test-oracle` | E2 |

## 1b. What the build reached (written at the end of the build session)

Levels: E0 read, E1 unit test, E2 engine test on scripted models, E3 kill-and-resume or concurrency test, E4 by hand. **Nothing here is at E4.** `make test-full` and the other `slow` tests were not run (CI runs them); the `slow` tests of this phase (`KillPointTest`, `ChildPauseTest`, `ChildJudgeTest`, and two in `ChildCancelTest` and `ChildSteerTest`) were run by name while building.

| # | Level reached | Evidence (tests in `src/loom/ai-agent4j-loom/src/test/java/io/github/llm4j/loom/`) |
|---|---|---|
| A1 | E1 | `children/StartParseTest` (17), including `theNewWordsStayOrdinaryWordsEverywhereElse`; `RepositoryScriptsTest` runs in the fast tier |
| A2 | E1, partly | `children/ChildCheckTest` (19). **Not built:** the warning for a start that exceeds `max_children` where it can be proven, a conflict between two steers (D30) |
| A3 | E2 for `LoomHost` file and JDBC; **not built** for the CLI sibling layout | `host/LoomHostChildrenTest`, `children/StartWaitEngineTest` |
| A4 | **E3** | `children/KillPointTest` (`@Tag("slow")`): kills before every journal write of the tree. Broken on purpose (child id from `UUID.randomUUID()`): the test fails. Writes inside an effect (`effect_run`, `effect_done`) are not kill points, as D4 says |
| A5 | E1 only | `children/ChildIdsTest` (a new generation gives another id). **Not built:** an engine test of a real rewind past a `start`, and cancelling the previous generation's children |
| A6 | E2, partly | `StartWaitEngineTest`, `ChildCancelTest`; statuses `running`, `sleeping`, `completed`, `failed`, `cancelled`, `stopped` exist. **`timed out` is not built** (the `start` deadline is parsed, not enforced). A `wait -> r` on a child that did not complete binds nothing, so `{r}` reads empty; only `h.result` is the error D9 asks for |
| A7 | E2, partly | tree suspension and restart: `StartWaitEngineTest.aParentWaitingOnASleepingChild...`; `wait all` records every outcome; parking with real threads in `ChildPauseTest`/`ChildSteerTest`. **Not tested:** a `wait` that times out while a child still runs, and the timeout uses wall time, not the injected clock |
| A8 | E2 for finish, halt, fail and the grace period; a stop was not tested; **rewind not built** | `children/ChildCancelTest`. Broken on purpose (cancel-on-end removed): 3 tests fail |
| A9 | E2 and E3 | `children/ChildBudgetTest` (25 rounds of parallel starts). Broken on purpose (reservation ignores other children): fails. The judge budget is **not** reserved at `start` (D27): only charged |
| A10 | E2 | `children/ChildLimitsTest`; broken on purpose (ceiling ignored): fails |
| A11 | E2 | `execution/ChildSteerTest` (real threads, delivered; and not delivered); the static refusal in `ChildCheckTest`; the runtime refusal of a non-steerable agent is code only, untested (the validator stops the script first) |
| A12 | E2, partly | `ChildCancelTest.aCancelNeverCutsAnEffectInHalf...` (`slow`), `use partial` keeps `h.partial`. `skip`, `discard` and `conclude` are all just `cancelled`; **`rewind to` is refused at run time** |
| A13 | E1, static only | `ChildCheckTest`; `ChildHandle.toString` prints a fixed word. **There is no runtime check** that stops `{h}` reaching a prompt if the static check were bypassed |
| A14 | E2 for LA28 and the child in `A2AAudit.reach` | `security/ChildAuditTest`. **Not built:** the oracle canary cases for `start` arguments and results (G9), so provenance across the boundary is implemented (arguments carry their tag, results come back `unchecked`, `StartWaitEngineTest.aChildsResultComesBackUnchecked...`) but **not proven by the oracle** |
| A15 | E1 and E2 | `children/SuperviseEvaluatorTest` (18), `children/ChildSuperviseTest`; G15 broken on purpose: 2 tests fail |
| A16 | E0/E1 | the counters are built from running time (`factsOf`), but no test drives a sleeping child; the sleep-versus-deadline warning is tested (`ChildCheckTest`) |
| A17 | E2 | `children/ChildAssertionTest`, `execution/ForbiddenCallsTest`; G10 broken on purpose (check removed): fails. A forbidden agent or task fails the step with a message; a forbidden tool is rejected by a hook on every agent of the child |
| A18 | E1 and E2 | `execution/JudgeTest`, `children/JudgeScheduleTest`, `children/ChildJudgeTest` (`slow`); G11 and G12 broken on purpose: fail. The facts view is built from the child's own trace (`Trajectory`), **not** from the evaluation dataset's rendering (risk 7 was not followed) |
| A19 | E2 | `children/ChildPauseTest` (`slow`); broken on purpose: fails |
| A20 | **not built** | `weave test` has no `children:`, `judges:`, `fired:` or `starts:` yet; tests drive the executor directly |
| A21 | **not built** | the graph (blocked, finding 1), `timeline`, `why`, `fork`, `review` worst case |
| A22 | **not built** | Fabric, `loom-inspect-core` and the live page (P6 was not read) |
| A23 | partly | the host ceilings exist (`LoomHost.Builder.childLimits`). **Not built:** `LoomRun` children, `LiveRuns` labels |
| A24 | **not built** | VS Code, CTK, starter 18, the tutorial |
| A25 | partly | done: the guide section (with `children/ChildDocsTest`), the prompt, the README table of the Loom module, `llms.txt`, the release notes, `docs/API_COMPATIBILITY.md`, the audit page and the OWASP map. **Not done:** the wiki page, the root README, `docs/guide` chapter 6, the tutorial, the workflow guide and the two skills' rows |
| A26 | **not built** | no `mutation-children` profile was written or run; the coverage gates are reported below |
| A27 | **not built** | starter 18, the 60-second demo, is **not built. The phase is not done**, and the owner's hand run has nothing to run yet |
| A28 | **not built** | the oracle cases were not written; `make test-oracle` was not run |

**Risks of DESIGN section 8:** 1 did not need a core change (the reservation is computed from the children's own budgets, so `Budget` is untouched); 2 was real and only half answered (`LoomHost` yes, the CLI layout no); 3 was real and done (column and migration, `LoomHostChildrenTest`); 4 was real: a parking `wait` is its own mechanism, and building it found a race (a child's record was written after its handle settled, so a parent could finish first; `wait` now always awaits the child's task); 5 held: a `ToolHook` per agent plus a guard before a task, a `use` step and a delegate, with no reordering of the policy hook; 6 was not read; 7 was not followed (see A18); 8 held (the key rule did not change); 9 was real: a tick and a controlled scheduler were new, and the wait and `start` deadlines still use wall time.

**What was stopped on:** only finding 1 (the graph node kind `start`), above. No decision was changed.

## 2. How the report is written

At the end of the session the report states, for each row, the level actually reached and the evidence (the test names), **what was not run** (`make test-full` and the other `slow` tests, anything at E4), the risks of §8 of DESIGN that turned out to be real, and any decision that was changed and why. A row is never marked verified at a higher level than its test.
