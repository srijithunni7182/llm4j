# Phase 33 verification plan: starting and supervising workflows

Status: **the phase is built (PR 118); §1b records what the build reached, and §3 lists what it did not do. Nothing is at E4.** This is the list of things to **read** before the design is final (§0) and the list of things to **prove** when the phase is built (§1). Companions: [DESIGN.md](DESIGN.md), [TEST_STRATEGY.md](TEST_STRATEGY.md), [DECISIONS.md](DECISIONS.md).

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

### Scope and build state (v1, v2, v3)

The plan names three stages (D1, D20); the build worked to the sub-phases 33a to 33h and the acceptance rows, so the two are mapped here. **This replaces a mid-build status table that had gone stale** (it said timeouts were not enforced and the CLI layout was not built; §1b and the code say otherwise).

| Stage | What | Sub-phases | State |
|---|---|---|---|
| **v1** | `start`, `wait` (one and all), `cancel`, readable facts, `steer`, `supervise` with counted rules, budgets and limits, the ecosystem | 33a to 33e, 33h | **Built**, with the gaps in §3 (F1 to F11) |
| **v2** | Assertions in code (preventive `never calls`), judged assertions | 33f, 33g | **Built**, with the gaps in §3 (F12 to F13) |
| **v3** | Declared progress (`report`), signals (`notify`), `wait any` | none | **Not started, by design** (a later phase; D6, D7 and D22 B and C are still open) |

Spot-checked in the code after the merge (E0): none of `notify`, `report`, `wait any` or `detach` appears in the parser or the AST; the `start` deadline is enforced (`ChildLauncher`) and `TIMED_OUT` exists; `cancel ... rewind to` is refused by `weave check` and at run time; `children:` exists only as an `expect:` key, not as a way to script a child.

## 1. Acceptance: what must be true when the phase is built

This table is the **plan**: "How" says what proves each claim and the level is the minimum accepted. What the build actually reached is in §1b.

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

Levels: E0 read, E1 unit test, E2 engine test on scripted models, E3 kill-and-resume or concurrency test, E4 by hand. **Nothing here is at E4.** `make test-full` and the other `slow` tests were not run (CI runs them); the `slow` tests of this phase (`KillPointTest`, `ChildPauseTest`, `ChildJudgeTest`, and one each in `ChildCancelTest` and `ChildSteerTest`) were run by name while building. Test paths are under `src/loom/ai-agent4j-loom/src/test/java/io/github/llm4j/loom/` unless another module is named.

| # | Level reached | Evidence |
|---|---|---|
| A1 | E1 | `children/StartParseTest`, including `theNewWordsStayOrdinaryWordsEverywhereElse`; `parity/RepositoryScriptsTest` (every script, starter 18 and the three CTK scenarios included) |
| A2 | E1, partly | `children/ChildCheckTest`. **Not built:** the warning for a start that exceeds `max_children` where it can be proven, and a conflict between two steers (D30) |
| A3 | E2 on `LoomHost` file and JDBC and on `weave run --journal` | `host/LoomHostChildrenTest`, `children/StartWaitEngineTest`, `cli/FolderChildRunsTest`, `cli/ChildTestVocabularyTest` |
| A4 | **E3** | `children/KillPointTest` (`slow`): kills before every journal write of the tree. Broken on purpose (child id from `UUID.randomUUID()`): fails. Writes inside an effect (`effect_run`, `effect_done`) are not kill points, as D4 says |
| A5 | E2 | `children/ChildIdsTest`; `children/ChildCancelTest.aRewindCancelsTheChildrenOfThePreviousGeneration...` (a rewind past a `start` cancels the old child and starts a fresh one) |
| A6 | E2 | `StartWaitEngineTest`, `ChildCancelTest`, `ChildSuperviseTest` (timed out), `ChildCtkConformanceTest` (sleeping): every status of D9 is reached. **Gap:** a `wait -> r` on a child that did not complete binds nothing, so `{r}` reads empty; only `h.result` is the error D9 asks for |
| A7 | E2, partly | tree suspension and restart (`StartWaitEngineTest`, `ChildCtkConformanceTest`), `wait all` records every outcome (also over an empty loop), parking with real threads (`ChildPauseTest`, `ChildSteerTest`). **Not tested:** a `wait` that times out while a child still runs; the wait timeout uses wall time, not the injected clock |
| A8 | E2 | `ChildCancelTest` (finish, halt, fail, grace period, rewind); broken on purpose (cancel-on-end removed): 3 tests fail. A stop of the parent is the same code path as a failure and has no test of its own |
| A9 | E2 and E3 | `children/ChildBudgetTest` (25 rounds of parallel starts); broken on purpose (reservation ignores the other children): fails. **Not built:** the judge budget is charged but not reserved at `start` (D27) |
| A10 | E2 | `children/ChildLimitsTest`; broken on purpose (ceiling ignored): fails |
| A11 | E2 | `execution/ChildSteerTest` (delivered, real threads; and not delivered), the static refusal in `ChildCheckTest`. The runtime refusal of a non-steerable agent is code only (the validator stops the script first) |
| A12 | E2, partly | `ChildCancelTest.aCancelNeverCutsAnEffectInHalf...` (`slow`), `use partial` keeps `h.partial`. `skip`, `discard` and `conclude` all end `cancelled`. **`cancel ... rewind to` is refused by `weave check`** |
| A13 | E1, static only | `ChildCheckTest`; `ChildHandle.toString` prints a fixed word. **There is no runtime check** that would stop `{h}` reaching a prompt if the static check were bypassed |
| A14 | E2 | audit: `security/ChildAuditTest` (LA28, LA29). Provenance both ways: `children/ChildProvenanceTest` (arguments carry their tag, results come back `unchecked`; breaking the result tag makes it fail) and the repository oracle, which now runs scripts that start workflows with children kept in memory and watched at their exits (`policy/CanaryProvenanceOracleTest`, `make test-oracle` passed) |
| A15 | E1 and E2 | `children/SuperviseEvaluatorTest` (18), `children/ChildSuperviseTest`; G15 broken on purpose: 2 tests fail |
| A16 | E0/E1 | the counters are built from running time (`factsOf`), but no test drives a sleeping child; the sleep-versus-deadline warning is tested (`ChildCheckTest`) |
| A17 | E2 | `children/ChildAssertionTest`, `execution/ForbiddenCallsTest`; G10 broken on purpose (check removed): fails |
| A18 | E1 and E2 | `execution/JudgeTest`, `children/JudgeScheduleTest`, `children/ChildJudgeTest` (`slow`); G11 and G12 broken on purpose: fail. The facts view is built from the child's own trace (`Trajectory`), **not** from the evaluation dataset's rendering (risk 7 was not followed) |
| A19 | E2 | `children/ChildPauseTest` (`slow`); broken on purpose: fails |
| A20 | E2, partly | `cli/ChildTestVocabularyTest`, starter 18's tests: real children under scripted models and the virtual clock, `judges:`, `expect:` `starts`, `children`, `fired`, `steers`. **Not built:** a stubbed child (`children:` as a script of states) |
| A21 | E2, partly | graph: `graph/ChildGraphTest` and the renderer's tests (`npm test` in `graph-render`, `make` sync check); `timeline` tree and `fork`: `cli/ChildForkTest`; `why` and `weave explain` know the journal kinds. **Not built:** `why` on a child folder (a child folder has no `run.json`), the worst-case cost of a supervisor in `weave review` and `generate` |
| A22 | E2 | `loom-inspect-core` `state/ChildRunsTest` (parent link, children counts, the page's child list, cost with children), `loom-inspect` `map/ChildMappingTest`. **Not tested:** the page's JavaScript (the Children section and the indented rows), and an old console against a new process (P6 was read only as far as the wire record) |
| A23 | E2 | `execution/ChildLoomRunTest` (`LoomRun.children()`, the longer `LiveRuns` label, steering a child, unregistering), `ChildLimitsTest` (host ceilings) |
| A24 | E2 | VS Code: grammar, hover, snippet (typed and checked by `weave`), `panel.css` tone, CHANGELOG 1.5.0 (`npm test` in `vscode-loom`: 784 pass); compatibility kit: three scenarios and `children/ChildCtkConformanceTest` including the restart; starter 18 with `weave test` at 100 percent of its steps and `StartersTest`; the tutorial section in chapter 11 (`TutorialExamplesTest`, `GuideTutorialPageTest`). **Not done:** the parser-parity baseline was not regenerated (its test passes unchanged), and the tutorial section lives in chapter 11 instead of a chapter of its own (a new chapter would renumber the rest) |
| A25 | E1, mostly | done: the guide section (`children/ChildDocsTest`), the prompt, both README rows that exist, `llms.txt`, the release notes, `docs/API_COMPATIBILITY.md`, the audit page and the OWASP map, the wiki page and `Home.md`, `docs/guide` chapters 6 and 15, the patterns skill (pattern 12, tested against starter 18) and the debugging skill's triage row, `docs/QUICKSTART.md`. **Not done:** the root `README.md` overview and the `llm4j-workflow-guide` skill |
| A26 | E1 | `mvn -Pmutation-children org.pitest:pitest-maven:mutationCoverage` in `ai-agent4j-loom`: 643 mutants, 552 killed (**86 percent**, gate 85) over `SuperviseEvaluator`, `JudgeSchedule`, `ChildIds`, `ChildLimits`, `ChildLauncher`, `ForbiddenCalls`, `Judge`, `ChildChecks` and `ChildHandle`. Nothing was excluded to get there; the first run scored 75 and the survivors were killed with assertions (`ChildLauncherDetailTest`, `ChildCheckDetailTest`, `ChildHandleTest` and more). `KillPointTest` is the only phase test left out of the profile, for its run time. The coverage gates passed in `make test-fast` |
| A27 | **E2** | starter 18 (`src/loom/starters/18-supervise-workflows`): `weave check` clean, `weave test` 4 cases at 100 percent of its steps, `StartersTest` (script and picture in its README), `make test-oracle`. **E4 is still the owner's:** run it by hand with a real key: `weave run main.loom --journal runs/r1 ...`, stop it, `weave resume runs/r1`, then `weave audit main.loom` and Fabric. In `weave test` the supervisor looks after each model call and each virtual sleep, so "steer one that drifts" is shown by a rule on tokens, not by the judge, which needs time to pass |
| A28 | E2 | `make test-oracle` passed with the new cases (above) |

**Risks of DESIGN section 8:** 1 did not need a core change (the reservation is computed from the children's own budgets, so `Budget` is untouched); 2 was real and is answered for `LoomHost` and `weave run --journal` (`ChildRuns` has two implementations); 3 was real and done (column and migration, `LoomHostChildrenTest`); 4 was real: a parking `wait` is its own mechanism, and building it found a race (a child's record was written after its handle settled, so a parent could finish first; `wait` now always awaits the child's task); 5 held: a `ToolHook` per agent plus a guard before a task, a `use` step and a delegate, with no reordering of the policy hook; 6 was read only as far as the wire record (a parent attribute on `loom.run.start`, additive); 7 was not followed (see A18); 8 held; 9 was real: a tick and a controlled scheduler were new, the virtual clock ticks the supervisor, and the wait and `start` deadlines still use wall time. Two further findings: a key written as a path (`work[t.name]`) had to be read when the handle is bound, and a child's trace and step listeners, sink oracle and observer had to be given to it (`deriveChild`, `ChildRuns.prepared`), or the oracle, coverage and Fabric would not see it.

**What was stopped on:** finding 1 (the graph node kind `start`), above, which the owner then settled: `child_start`, `child_wait`, `child_cancel` and `child_watch`. No decision was changed.

## 2. How the report is written

At the end of the session the report states, for each row, the level actually reached and the evidence (the test names), **what was not run** (`make test-full` and the other `slow` tests, anything at E4), the risks of §8 of DESIGN that turned out to be real, and any decision that was changed and why. A row is never marked verified at a higher level than its test.

## 3. What the build did not do, and the departures from decisions

Taken from the build report (§1b) and spot-checked where noted. Each row names the decision it departs from; none changed a decision, so each is a **follow-up**, not a new decision. F1 to F3 and F5 depart from what the owner accepted and are the ones to fix first.

| # | Gap | Departs from | Stage |
|---|---|---|---|
| F1 | `cancel ... rewind to <checkpoint>` is refused by `weave check` and at run time ("cannot run yet") | D13 | v1 |
| F2 | `wait -> r` on a child that did not complete binds nothing, so `{r}` reads empty; only `h.result` raises the error D9 asks for | D9 | v1 |
| F3 | The wait timeout uses wall time, not the injected clock, and a `wait` that times out while a child is still running has no test | D8, D17 | v1 |
| F4 | The worst-case cost of a supervisor is not shown by `weave review` or `weave generate` | D11, D26 | v1 |
| F5 | A stubbed child (`children:` as a script of states) in `weave test` is not built | D17 | v1 |
| F6 | `weave why` on a child folder does not work (a child folder has no `run.json`) | D16 | v1 |
| F7 | No runtime check stops a handle reaching a prompt if the static check were bypassed | D2, G8 | v1 |
| F8 | No test drives a sleeping child, so the exclusion of sleep from `idle` and `elapsed` is untested | D28 | v1 |
| F9 | The warning for a `start` that exceeds `max_children` where it can be proven, and the warning for a conflict between two steers, are not built | D14, D30 | v1 |
| F10 | Not tested: the live page's JavaScript (the Children section), and an old console against a new process (P6) | D16 | v1 |
| F11 | Not done in the ecosystem: the root `README.md` overview, the `llm4j-workflow-guide` skill row, the VS Code parser-parity baseline; the tutorial section is in chapter 11, not a chapter of its own | D16 | v1 |
| F12 | The judge budget is charged but not reserved at `start` | D27 | v2 |
| F13 | The judge's facts view comes from the child's own trace (`Trajectory`), not from the evaluation dataset's rendering of a trajectory (risk 7 was not followed) | DESIGN §8 risk 7 | v2 |
| F14 | Guards G7 and G13 were not broken on purpose, and G2 has no engine test of its own | TEST_STRATEGY §4 | both |
| F15 | The 60-second demo (A27, starter 18) is at E2: "steer one that drifts" is shown by a token rule, not by the judge, which needs time to pass. **E4, running it by hand with a real key, is the owner's** | A27 | both |

**Departures that are improvements.** The reservation is computed from the children's own budgets, so the core `Budget` did not change (DESIGN §4.5 expected a hold in the core); the graph node kinds are `child_start`, `child_wait`, `child_cancel` and `child_watch` because `start` already names the graph's entry node (settled by the owner).
