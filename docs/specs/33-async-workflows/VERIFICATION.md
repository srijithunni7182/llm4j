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

## 2. How the report is written

At the end of the session the report states, for each row, the level actually reached and the evidence (the test names), **what was not run** (`make test-full` and the other `slow` tests, anything at E4), the risks of §8 of DESIGN that turned out to be real, and any decision that was changed and why. A row is never marked verified at a higher level than its test.
