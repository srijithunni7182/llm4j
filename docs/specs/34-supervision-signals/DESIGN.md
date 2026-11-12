# Phase 34 design: closing the gaps, progress, signals and `wait any` (Loom)

Status: **draft for review. Every choice below is a recommendation from [DECISIONS.md](DECISIONS.md), which is still open;** each section names the decision it rests on, and if the owner decides otherwise the section changes. Nothing is built.
The facts were read in the merged phase 33 source (`ChildLauncher`, `ChildHandle`, `ChildStatus`, `Judge`, `Trajectory`, `SuperviseEvaluator`, `ControlledChildScheduler`, `CaseRunner`, `LoomParser`) on the day this was written; what was not read is in §7 and in [VERIFICATION.md](VERIFICATION.md).
Companions: [PRINCIPLES.md](PRINCIPLES.md), [DECISIONS.md](DECISIONS.md), [TEST_STRATEGY.md](TEST_STRATEGY.md), [VERIFICATION.md](VERIFICATION.md), and for what came before [phase 33](../33-async-workflows/DESIGN.md).

## 1. Problem

Phase 33 lets a workflow start others, wait for them, cancel them and supervise them with rules. Three things remain.

1. **Gaps.** Fifteen things the build did not do, several of which depart from decisions the owner accepted ([phase 33 VERIFICATION §3](../33-async-workflows/VERIFICATION.md)).
2. **A child cannot speak.** A parent can only read runtime facts about a child (tokens, time, status). It cannot be told "I am stuck" or "I found a second source", and a child cannot be told "go" or "use this data".
3. **A parent can only join.** `wait` returns when one child (or all) settle or the timeout passes. A supervisor that wants to react to *whichever child needs it first* has no construct.

## 2. Goals and non-goals

**Goals:** close the gaps (34a); let a child declare typed progress that a parent can read (34b); let a parent and a child exchange named, durable signals (34c); let a parent wait for the first of several children or signals (34d); move the ecosystem together (34e).
**Non-goals (D18):** signals between siblings, an explicit monitor value, waking a *sleeping* child by a timer in-process, `detach`, other machines, a visual builder, collections and types, a `remote` as a child, `weave generate` for supervisors, progress as a supervise condition on its content.

## 3. What the code does today

Read in the merged source:

- **A child is a run of its own.** `ChildLauncher.start` makes an executor, a journal and a handle; `runChild` runs the workflow and maps the way it ends onto the handle: completion, `RunSuspended` (sleeping or waiting: **the thread ends**), `RunStopped`, `RunHalted`, `BudgetExceeded` and any other failure.
- **`wait` is a journaled join.** `await` journals what it returned under the statement's step id (kind `WAIT`, the outcomes of the handles), and a replay applies the recorded outcome instead of waiting again; the tree suspends only when no handle is `RUNNING`. A single `wait -> r` binds `r` only for a completed child (gap F2).
- **`supervise` is a ticking watch.** A `Watch` per handle on a single-thread scheduled executor; each tick computes facts (`factsOf`: tokens, calls, cost, running time, idle time, the trajectory), evaluates counted then code rules, then judges; what it did is journaled in the **parent's** journal (`<step>#fire.N`, `<step>#judge.<rule>.<n>`, intent entries before a steer, wrap-up or cancel).
- **"Did anything change"** is one number, `signatureOf`: tokens and calls (weighted) plus the number of entries in the child's journal. Idle time and the judge's skip-if-unchanged rule both use it.
- **The judge** sees `Judge.facts`: the counters and `Trajectory` (calls by name and count, the order first called, checkpoints, refused calls), and content only with `seeing content`; its verdict is pass, fail or unreadable, journaled with the child's position.
- **Tests** use `ControlledChildScheduler` (children run one at a time when waited for) and the injected clock; `judges:` is scripted like `models:`.
- **A suspended child is not woken by anything in the process.** `ChildHandle.resume()` is called only from `InterventionCoordinator` (a run-control pause). A child that slept or waited is run again when its parent replays and re-attaches. *(To confirm by reading `startLocked`: §7, P1.)*

## 4. The design

### 4.1 Closing the gaps (34a; D2 to D5)

| Gap | Change |
|---|---|
| **F1** `cancel ... rewind to` | Implement the outcome: a marker in the child's journal naming the checkpoint (the child declared it; `at most N times` required), the child's generation increases (`Generations`), and the child **re-runs from the checkpoint**, which needs the same re-run path as D12. Remove the refusal in `ChildChecks` and `ChildLauncher`. *Depends on 4.3's resume path, so 34a builds the re-run path first and 34c reuses it.* |
| **F2** `wait -> r` on an unfinished child | Bind `r` so that **reading it** raises the status error of 33-D9 (a typed absent value that fails closed), and `weave check` warns on an unguarded read. |
| **F3** wait timeout | `settle` uses `executor.nowMillis()` already; route it through the injected clock the supervisor tick uses, and add the test of a `wait` that times out while a child still runs. |
| **F4** worst-case cost | `CostEstimator` adds, per `start`, the child's budget times the live cap, and per judged `supervise`, its budget (D3). |
| **F5** stubbed child | `children:` in `weave test` as a script of states (D15), run by `ControlledChildScheduler` without the child's script. |
| **F6** `weave why` on a child folder | A child folder carries a small `run.json` (workflow, parent id, inputs summary) written by `ChildRuns.record`, so `why` and `timeline` open it as any run. |
| **F7** runtime handle guard | `ChildHandle.toString` already prints a fixed word; add the check at the point text is assembled for a prompt, tool argument or return, failing closed. |
| **F8** a sleeping child | A test that drives a sleeping child through `factsOf` (running time and idle exclude sleep). |
| **F9** two warnings | A start that exceeds `max_children` where it can be proven; two steers to one agent in one block. |
| **F12** judge reservation | `weave check` pairs a `start` with the `supervise` of the same handle in the same block; the reservation at `start` includes the paired judge budget (D3); an unpaired `supervise` reserves when it runs and may fail with a message. |
| **F13** the facts view | No code: `Trajectory` is the decision (D4, taken): risk 7 of phase 33 is closed as "not followed, by choice", because the evaluation's rendering describes a finished run and a supervisor needs a live one. |
| **F14, F15** guards and demo | Break G7 and G13 on purpose once; an engine test for G2's resume half; a judge case in starter 18's tests (D5). |

### 4.2 Declared progress (34b; D6, D7)

**Syntax.** `workflow W(params) progress { field: type, ... } { ... }` and `report { field: value, ... }`, contextual (D17): `report` only as `report {` at statement start, `progress` only after a workflow's `)`. Types are `string`, `number`, `boolean`, `enum[...]`, one flat level.
**Parse and check.** New AST pieces (`ProgressDecl` on `WorkflowDef`, `ReportStmt`); `weave check` refuses an undeclared field, a type mismatch it can see, a `report` in a workflow with no `progress` declaration, and reports **a workflow that declares progress but never reports**.
**Runtime.** `report` writes `#progress.<n>` into the **child's own journal** (the step id's generation applying), validates the fields against the declaration (a mismatch fails the step), enforces the size and count bounds (dropped and recorded beyond them), and tags the value `unchecked`.
**Reading.** `ChildHandle.fact("progress")` reads the child's journal for the latest entry of the latest generation, so it survives a restart and a rewind shows the value at the checkpoint; `h.progress.field` is an ordinary expression in the parent.
**Use in supervision (D7).** Readable only, plus one code assertion `assert progress.<field> changes within <duration> { ... }` (it triggers when the field has not changed within the duration; it never reads the value's meaning). Not a `supervise` condition on content.

### 4.3 Signals (34c; D8 to D10, D12, D13)

**Storage.** A signal is a journal entry in the **receiver's** run: parent to child, `#sig.in.<name>.<n>` in the child's journal; child to parent, `#sig.out.<name>.<n>` in the child's journal, which the parent reads through the handle. The entry holds the name, the payload, a sequence number and the sender. **Consumption** is journaled where it happens: the child's `wait signal` step, or the parent's `wait any` entry, records the cursor it advanced to, so a replay re-reads exactly what it read.
**Statements.** `notify h "name" ["text"] [sticky]` (a message is consumed once, in order; a `sticky` signal stays set and is read without being consumed, and a later `notify` of the same name replaces it), `notify parent "name" ["text"]`, `wait signal "name" timeout T -> text` (child), `wait h signal "name" timeout T -> text` (parent), and in a `supervise` block `when signal "name" { actions }` (the actions' text stays fixed; the payload is not available to them).
**Waking a waiting child (D12).** A child in `wait signal` ends its thread (`RunSuspended`, recorded `WAITING`, with a fact `waits` naming the signal). `ChildLauncher` gains `wake(handle)`: it re-submits `runChild` on the scheduler, which re-executes the workflow over the child's journal (as `weave resume` does for a run) with a **single-flight state machine on the handle** (`waiting` to `running` to `waiting` or settled; a second `notify` while it is running only writes its entry; a `notify` to a settled child is a recorded no-op).
The same path serves `cancel ... rewind to` (F1).
**Two waits in one child.** Messages are consumed FIFO per name and each consumption is journaled at its own step, so a child with two waits for the same name in sequence gets two messages; a state-like signal is sent `sticky`. `weave check` refuses two `wait signal` statements for the same name that can be active at once (different branches of one `parallel`). There is no unset in this phase.
**Limits (D13).** 16 pending per name and 64 in all per child, 40 characters per name, 2000 per payload; a `notify` beyond a limit fails naming it. A signal to a child of a previous generation is not delivered to the new one.
**Sending without a parent (D13).** A `notify parent` in a workflow that was not started is a recorded no-op at run time; `weave check` warns when a workflow containing it is also the entry workflow.

### 4.4 `wait any` (34d; D11)

`wait any <handles> timeout T -> event` returns on the first of: a signal from any handle, a handle settling, the timeout. The event is a keyed value `{ kind, name, status?, result?, text? }`. **Ordering when several are ready:** signals before finished, then the order the handles were started. **Journaling:** the first return writes one entry under the statement's step id holding the event **and the consumption cursor of every mailbox read** (the pattern of `await`), and a replay returns the recorded event; a `finished` child is reported once; unconsumed signals stay pending. A `timeout` event leaves the children running. At most `max_children` handles.

### 4.5 Security (D14)

A new audit rule (proposed LA30) for a workflow that reads a signal; the existing provenance flow carries a signal's text as `unchecked` on the receiving side in both directions; a signal's payload cannot become a steer's text; a child can signal only its parent and a parent only handles it started (checked at load and at run time). The audit rule goes in `docs/security/weave-audit.md` and the OWASP mapping; the oracle gets canary cases for a signal's payload in both directions; they are written **before the runtime**.

### 4.6 Testing in the language (D15)

`weave test` gains `signals:` (a signal's arrival on the virtual clock), `expect:` keys for signals sent and received, and the stubbed child with `progress` and `signal` steps; `ControlledChildScheduler` runs a woken child deterministically. Details in [TEST_STRATEGY.md](TEST_STRATEGY.md).

### 4.7 The ecosystem (D16)

| Surface | Change |
|---|---|
| Parser, AST, `StatementWalker` and the statement consumers | `ProgressDecl`, `ReportStmt`, `NotifyStmt`, signal waits, `wait any`, `supervise` signal rules and the progress assertion (the 17 files that switch over statement kinds, as in phase 33). |
| `weave graph` and the shared renderer | `child_notify`, `child_signal_wait`, `child_wait_any`; `sync-graph-render.sh`. |
| `weave explain`, `check`, `review`, `audit`, `generate`, `timeline`, `why`, `fork` | Sentences; the checks above; LA30; signals and progress as events of a child's run; `fork` copies the mailbox entries with the journal. |
| Fabric, `loom-inspect-core`, the live page | Pending signals and the latest progress in the Children section (additive attributes) and **the old-console compatibility test phase 33 left undone** (F10). |
| VS Code, CTK, starters, tutorial | Grammar, snippets, hover, the parity baseline, `CHANGELOG`; scenarios including a woken child across a restart; starter 18 extended or a starter 19; **a tutorial chapter of its own** (F11). |
| Docs and skills | `LOOM_GUIDE.md`, `LOOM_PROMPT.md`, both README tables, the wiki, `llms.txt`, the release notes, `docs/API_COMPATIBILITY.md`, the audit page, the OWASP map, **the workflow-guide skill and the root README (F11)**, `llm4j-workflow-patterns`, `llm4j-run-debugging`. |

## 5. Sub-phases

Each ends with `make test-core` (and `make docs-check` where docs, a command, a starter or a review rule changed); the phase ends with `make test-fast` on every module touched and `make test-oracle` once. Guard tests first.

| | Sub-phase | Contents |
|---|---|---|
| 34a | Closure | §4.1: F1 to F9, F12 to F15 (F10 and F11 in 34e). **The re-run path** (shared with signals) is built here for F1. A review gate follows. |
| 34b | Declared progress | §4.2. |
| 34c | Signals | §4.3; the audit rule and the oracle cases first; the kill-point test extended. |
| 34d | `wait any` | §4.4; the winner and the cursor journaled; replay tests. |
| 34e | Ecosystem and closure | §4.7, F10 and F11, the mutation profile (`mutation-children` extended to the new classes, at 85% or more), `docs/API_COMPATIBILITY.md`, the release notes. |

## 6. Compatibility

Additive only. New contextual words (D17) guarded by `RepositoryScriptsTest` and a named test for the cases that stay ordinary words; no change to `call`, `delegate`, `parallel` or to what phase 33 accepts; new journal keys (`#sig.*`, `#progress.*`) and a new `WAIT`-style entry for `wait any`; `ChildHandle` gains facts; `RunRecord` needs no change; the `ChildRuns` SPI gains **default** methods only (so an existing implementation compiles); an older console reads a newer process's records (tested, F10).

## 7. Risks and what is not yet known

1. **Waking a child (D12)** is the riskiest part: a re-run over a journal on the scheduler, with a single-flight rule, a race between `notify`, `wake` and a child that is about to suspend again, and the interaction with the parent's grace-period cancel. Phase 33's launcher already had one such race (a record written after the handle settled); expect more. Size it first, with the controlled scheduler.
2. **How a suspended child is re-run today** (`startLocked` on replay) is not read: P1. The wake path should reuse it, not copy it.
3. **`wait any` with signals and settles** needs one consistent ordering and one journaled cursor; a bug here duplicates or loses a signal. The kill-point test extension is the guard.
4. **The 37 scripts that use `report` as a variable.** A contextual rule that is too loose breaks them; too tight and `report { }` fails to parse in an odd place. The named test of cases that must stay words is the guard.
5. **Fabric's wire format** gains attributes; the old-console compatibility test was not done in phase 33 and is owed (F10).
6. **Cost.** Signals and progress add no model calls; a judge's cost is unchanged; the worst-case estimate (F4) is the place a user will see it.
7. **What was not read:** `startLocked`, `InterventionCoordinator`'s pause path in detail, `ChildChecks`, the trace-to-`Trajectory` wiring, `wtest/CaseRunner`'s child handling beyond the scheduler line, and the Fabric wire code (P1 to P6).
