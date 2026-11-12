# Phase 33 design: starting and supervising workflows (Loom)

Status: **draft for review.** Every decision in [DECISIONS.md](DECISIONS.md) was taken by the owner in discussion (D6, D7 and the declared-progress and pushed-event halves of D22 are deferred to a later phase). The facts about the current code
were read in the source on the days this design was written; what was not read is listed in §8 and in [VERIFICATION.md](VERIFICATION.md). Nothing is built.
Companion documents: [PRINCIPLES.md](PRINCIPLES.md) (**every choice here is checked against it, and a conflict is settled there first**), [DECISIONS.md](DECISIONS.md), [TEST_STRATEGY.md](TEST_STRATEGY.md), [VERIFICATION.md](VERIFICATION.md), [roadmap](../README.md).
Builds on the run journal and its generations, `LoomHost` and `Persistence`, the budgets, `supervise`, `steerable` agents and `InterventionCoordinator`, `LiveRuns`, the provenance tags and the audit.

## 1. Problem

`call Child(...) -> result` runs a sub-workflow **inside the parent's executor, to completion**, before the next statement: the same thread, the same journal and the same budget (`HarnessExecutor.executeCall` pushes a variable frame, runs `executeWorkflow` and pops it).
A workflow therefore cannot run several workflows at once and watch, steer or stop them. `parallel` runs branches at once, but it **joins every branch** before the run goes on, and the branches share the run's journal, budget and controls.
A Java host can already steer, wrap up, cancel, pause and resume a run (`LoomRun`); a script cannot.

The feature: a script **starts** a workflow without waiting and gets a **handle**; it can then **wait** for it, **cancel** it, read facts about it, and **supervise** it with rules that steer or stop it. One workflow can run, watch and operate others. In other stacks this is queues, run ids,
polling, cleanup and restart handling in application code; here it is a few statements whose guarantees (replay, budgets, audit) come with them.

## 2. Goals and non-goals

**Goals (this phase = v1 and v2, D20):** `start`, `wait`, `cancel`, readable facts on a handle, and `supervise <handle> { ... }` with counted rules, assertions in code and judged assertions; every child a run of its own; the whole ecosystem moving together (D16).

**Non-goals:** `detach` (not wanted), children on other machines, a scheduler or queue of children, a visual builder, a change to `call`, `weave generate` for supervisors, collections and a type system (their own phase), supervision across suspended runs, a `remote` agent as a child,
and the v3 items: declared progress (`report`), signals (`notify`) and `wait any` (D6, D7, D22 B and C).

## 3. Principles

Fifteen, in [PRINCIPLES.md](PRINCIPLES.md). The ones that decide the shape: a started workflow is a run, not a thread (1); a handle is a capability held by the script, never a model (2); structured by default (3); durable by construction (4); failure is a value (6); bounded (7);
effects are never repeated or cut in half (8); authority only narrows (9); counts before opinions (13); time is part of supervision (14).

## 4. The design

### 4.1 The language

Contextual keywords (D19): a word is a verb only at the start of a statement in a workflow body followed by the shape the statement needs, **the way the parser already recognises `run`** (`isWord("run") && isNameToken(peekAt(1)) && peekAt(2) == LPAREN`). Everywhere else it is an ordinary word.
`start` and `wait` already occur in the ladder language (`start at watch`) and in `rate_limits { on_limit: wait }`; `RepositoryScriptsTest` guards every `.loom` file.

```loom
start Research(topic = t.topic) timeout 20m budget { tokens: 100000 } -> work[t.name]
supervise work[t.name] budget { tokens: 20000 } {
    when tokens > 90000 { cancel use partial }
    when idle > 3m      { steer "You seem stuck. Narrow the topic." once }
    assert never calls shell { cancel }
    assert reaches "collected" within 10m { steer "Narrow the topic." once }
    assert "searches only for the topic" using TopicJudge every 2m max 4 { steer "Go back to the topic." once }
}
wait all work timeout 25m -> results
alt (work["a"] is ok) { ... }
cancel work["b"] use partial reason "not needed"
```

| Construct | Meaning |
|---|---|
| `start W(args) [timeout D] budget { ... } -> h` | Start workflow `W` as a child run; bind a **handle** to `h` (a variable, or a keyed variable `h[key]`). `budget` is required (D11); `timeout` is a wall-clock deadline (D26). |
| `wait h timeout D -> r` / `wait all hs timeout D -> rs` | Park until the child (or every child) settles or the timeout passes. `timeout` is required. Returns even on timeout (D8); `on_timeout { }` and `on_failure { }` are optional blocks. |
| `cancel h [outcome] [reason "..."]` | Stop a child with an existing outcome (`use partial`, `skip`, `discard`, `conclude`, `rewind to <checkpoint> at most N times`). |
| `steer h Agent "text"` | A statement form of the steer (agent named, literal text, D12). |
| `supervise <handle> [budget {...}] { rules }` | A **statement** that watches a handle (D21). Distinct from today's top-level `supervise <Agent> { }`, which is a `SuperviseDef`; it is a new `SuperviseStmt`. One block per handle (D30). |
| Facts: `h.status`, `h.result`, `h.partial`, `h.reason`, `h.failure`, `h.tokens`, `h.cost`, `h.calls`, `h.elapsed`, `h.idle`, `h.wakes`; `h is ok` | Read-only values about a handle (D9, D23). |
| Settings `max_children: N`, `max_depth: N` | Top-level, in the style of `substitution:` and `shared_prompt:` (D14). |

A handle is **opaque** (D2): usable only in these constructs; a `{h}` placeholder, a handle given to a tool or an agent, or returning one from a workflow or a `call` is a load error. It may live in a keyed variable and be passed into a `call`.
The two additions to the keyed variable that D1 needs (iterate its keys; `wait all` over it) are part of 33a.

### 4.2 A child is a run

Principle 1. Each child gets **its own executor** (`new HarnessExecutor(script, tools, models)` as `LoomHost.assemble` builds one), its own journal, its own budget, its own status and its own `LiveRuns` entry.

- **Identity (D4).** The child's run id is derived from the parent's run id and the **step id of the `start` statement**. Step ids already carry the generation of a rewind (`Main/s5~2`, `Generations`), so a **resume** (same step id) re-attaches to the same child and a **rewind** (a new generation, so a new step id) starts a fresh one.
  The earlier "attempt number" is therefore the generation already in the step id; no new counter is needed. For a `for each`, the item is part of the step id already (a statement's id includes its loop round and branch, `RunJournal`).
- **Journal first (D4).** The parent writes its `start` entry (`{ childRunId, workflow, arguments summary }`) before the child begins; on replay a present entry is attached to, never started again.
- **Where it lives (D3).** A sibling run: `runs/<childId>/` under `LoomHost`'s `Persistence.file` layout, rows keyed by `childId` under `Persistence.jdbc`, and **next to the parent's run folder** under `weave run --journal DIR` (the CLI builds its executor directly with a `FileRunJournal(runDir/journal.json)`, not through `LoomHost`; see §8). Its record names its **parent's id**.
  `weave fork` follows the links so copying a run copies its children.
- **How it gets its parts (D18).** A new small SPI, **`ChildRuns`**, injected into the executor by whatever builds it: `openJournal(childId)`, `locate(childId)`, `record(childId, parentId, ...)`. `LoomHost` implements it with `Persistence.journal(runId)` and `newExecutor(runId)`; the CLI implements it with sibling folders;
  an executor given one bare `RunJournal` has no `ChildRuns`, so `start` there **fails closed** with a clear message; with no persistence a child runs in memory like any run (nothing resumes after a restart, as today).
- **What it inherits (D15, D18).** The invoker credentials (never more), the secret store, the human interface, the call and tool hooks, the clock and sleeper, the audit logger, the observer. A child's `human_prompt` and `approve` go through the parent's channel and are listed under the child's run id with a link to the parent.
- **Where it runs (D5).** In the same process, on a thread; a **controlled executor** in tests (§4.9). Because the child has its own journal and a deterministic id, a restart re-attaches and resumes it like any suspended run. Something has to restart the parent (a person, or `weave daemon`). Nothing here rules out running children through the trigger store later.

### 4.3 The status model (D9)

`running`, `sleeping` (with `wakes`), `waiting` (on a person), `completed`, `failed` (a step with no handler failed, a `halt`, or a budget category), `stopped` (a person, or a rule inside the child), `cancelled` (by the parent or its rule, with `reason`), `timed out` (the `start` deadline).
`h is ok` is true only when `completed` and **fails closed**. The result is readable only when `completed`; reading it otherwise is a runtime error naming the status, and `weave check` warns about a read not guarded by `is ok` or a status test. After a `use partial` cancel, `h.partial` holds what the child produced, marked `unchecked`.
The existing `LoomRun.Status` (`RUNNING`, `COMPLETED`, `SUSPENDED`, `STOPPED`, `FAILED`) is extended, not replaced; the mapping is part of 33b.

### 4.4 `wait` and the tree's life (D8, D10, D29)

- `wait` **parks while any child is running**: the children keep running and supervision stays active (the process stays alive, D5). The whole tree **suspends and the process may exit only when nothing can run** (every child sleeping or waiting on a person); `weave tick` or `weave daemon` resumes it.
- A parent suspended on anything **other than `wait`** (`human_prompt`, its own `sleep`) **pauses its children, with their deadlines paused** (D29).
- On timeout `wait` **returns**: the children keep running until the script decides, and the script reads `h.status`.
- `wait all` returns a keyed variable with **every outcome recorded** (a failed or cancelled child is recorded as such); it waits for every child. Fail-fast is a `supervise` rule that cancels the others.
- **When a parent ends** in any way (finishes, `halt`, fails, is cancelled, or is rewound), every child still running is **cancelled with the reason "parent ended"** and the end waits for them to settle within a **grace period** (a host setting, default 30 s); one still unsettled is recorded as `cancelled` with a note. A rewind cancels the previous generation's children before the new generation starts its own.
  After a process death nothing is cancelled; the parent re-attaches by id (D4). `weave check` warns when a `start` result is never waited on, cancelled or supervised.

### 4.5 Budgets (D11, D27)

A child has **its own required budget**; its spending also counts against the parent, so the parent's budget stays the limit for the whole run. The core already composes budgets: a call reserves its estimate **through a `BudgetSet`** and settles after, and a `Budget` has `reserved*` fields.
So a child's calls charge a `BudgetSet` of **[the child's budget, the parent's budgets]**. **Reservation:** a `start` is refused at run time with a clear message unless the parent has left, **after the other live children's and judges' reservations**, at least the child's cap (plus its `supervise` judge budget).
That needs a new way to hold part of a parent budget for a child and release it as the child settles, in **`ai-agent4j`'s `Budget`** (a public addition to the core: it goes in `docs/API_COMPATIBILITY.md`; §8). The smaller of the child's declared `budget` and the script's own applies. A child whose budget runs out ends `failed` (category budget). The worst case in `weave review` and `weave generate`
is the sum of the child budgets times `max_children` (and each judged rule's `max` times that).

### 4.6 Limits (D14)

`max_children` (default 8, a host ceiling, a script may lower it or raise it up to the ceiling) and `max_depth` (default 2 levels below the top workflow, same rule); a `start` past either **fails** at run time naming the limit; it does not queue. A `start` inside `loop until ... max N` or `for each ... max N` is bounded by that `max` (the rule LR13 covers for a list a model wrote);
a `start` in a construct with no bound is a `weave check` error; `weave check` warns where it can prove a contradiction; a workflow that starts itself directly is warned about.

### 4.7 Control: steer and cancel (D12, D13)

A parent's control reaches the **child's own `InterventionCoordinator`** (every executor has one; it holds the agents running now, targets a step id, an agent name or `*`, journals each steer as `<step>#steer.N`, checks the agent's guard and limits a steer to 2000 characters).
- **Steer:** only agents marked **`steerable: true`** in the child (opt-in, D12); inside `supervise` a bare `steer "text"` reaches the handle's running steerable agents, naming one is optional there and required in a `steer` statement; the text is **literal**, and a placeholder is a load error. If no steerable agent is running the steer is recorded as "not delivered", not an error. `weave audit` gets a new rule for a script that can steer a started workflow.
- **Cancel:** an in-flight **effect finishes and is journaled** first; otherwise it takes effect at the next step boundary, or ends a streaming agent's model call in progress; there is no forced kill. A rewind outcome needs a checkpoint **the child declared** and `at most N times`. Cancelling a settled child is a recorded no-op; cancelling a sleeping or waiting child settles it at once and withdraws the pending question.

### 4.8 Supervision (D21 to D26, D30)

A `supervise <handle>` statement attaches rules to a child for as long as it runs. Three kinds, evaluated in this order each tick (**counted, then code, then judged**), as written within each kind; a `cancel` or `wrap_up` ends the evaluation for that child and skips judged rules; **at most one steer per agent per tick**; everything fired or skipped is journaled with the child's position.

- **Counted rules** (`when`): conditions are a **closed set of runtime counters**, not script expressions: `tokens`, `cost` (needs a price table), `calls`, `elapsed` (running time), `idle` (time with work to do but no progress; sleeping and waiting excluded). Actions: `steer`, `wrap_up`, `cancel [outcome]`. A rule fires when its condition **becomes** true and again only if it goes false and true again; `once` limits it to one firing. The tick is about 10 seconds (a runtime setting).
- **Assertions in code** (D24): `never calls <name>` (**preventive**: the call is refused before it runs, recorded as a violation, then the block runs, through the hooks that already check each tool call), `calls <name> at most N times`, `calls A before B`, `reaches "<checkpoint>" within <duration>`. Names are checked against the child's script.
- **Judged assertions** (D25): `assert "<text>" using <Evaluator> every <duration | N steps> max <N> { actions }`. The judge sees **facts** about the trajectory by default (the steps with the agent, tool or task and the outcome, the checkpoints, counts and durations), **not content**, unless the rule says `seeing content`. The verdict is **pass or fail**, the action text is **fixed in the script**,
  and only `steer`, `wrap_up` and `cancel use partial` are allowed. The cadence has a **30 s floor** and a **required `max`**, a judgement is skipped if the child has done nothing since the last, the verdict is **journaled with the child's position** (a resume never asks again), and it is paid from the `supervise` statement's own budget (required), charged to the parent with reservation.
  Not under the earned-autonomy ladder; the audit lists each judged rule.
- **Time** (D26, D28): `elapsed` and a duration cadence count running time; the `start` deadline is wall clock and counts sleep, so `weave check` **warns when the child's own sleep or wait is longer than its `start` timeout** (the child's script is known).

### 4.9 Testing in the language (D17)

`weave test` gains: **a real child with scripted models** (the default, as `call` works), and **a stubbed child** scripted in the style of `remotes:` (`children: { Research: [ { state: working, tokens: 40000, after: 1m }, { state: completed, result: "..." } ] }`); `judges: { TopicJudge: [ pass, fail ] }`;
new `expect:` keys `children:`, `fired:`, `starts:` and steers `delivered`/`not delivered`; the existing `clock:` (virtual time, `elapsed_virtual`). Children run on a **controlled executor driven by the virtual clock**, so no threads race; the clock and the executor are injected. Details in [TEST_STRATEGY.md](TEST_STRATEGY.md).

### 4.10 Security (D15)

Authority only narrows; the **audit counts the child as part of the parent** (the union of what it can read, know and send, from the child's script) in the trifecta analysis; a new audit rule (proposed **LA28**, after the A2A rules) for steering a started workflow; **provenance crosses the boundary as it does for `call`**
(arguments resolved with a provenance tag as `executeCall` does; a child's result and `h.partial` come back `unchecked`); a child's questions go through the parent's channel; a workflow stays tenant-agnostic and a child inherits the invoker identity. The audit rule goes in `docs/security/weave-audit.md` and the OWASP mapping; the oracle gets canary cases for both directions; they are written **before the runtime**.

### 4.11 The ecosystem (D16)

Everything moves together; each sub-phase updates the surfaces it touches and 33h closes the rest.

| Surface | Change |
|---|---|
| Parser, AST, `StatementWalker` and the **17 files that switch over statement kinds** (`ScriptValidator`, `StatementText`, `ScriptExplainer`, `GraphBuilder`, `NodeDescriber`, `CostEstimator`, `RunProfile`, `ConditionFacts`, `RewindChecks`, `ControlChecks`, `StaticSinkChecks`, `Capture`, `SplitFix`, `BudgetCoordinator`, ...) | A `StartStmt`, `WaitStmt`, `CancelStmt`, `SteerStmt` and `SuperviseStmt`; `HaltStmt`, the most recent small statement, is in 10 files. |
| `weave graph` and the shared renderer | Node kinds `start`, `wait`, `cancel`; a dotted edge from a `start` to the `wait` or `supervise` that uses its handle; `scripts/sync-graph-render.sh` and `check-graph-render-sync.sh`. |
| `weave explain`, `review`, `generate`, `audit`, `check` | Plain sentences; the check errors and warnings above; the cost worst case; LA28; the trifecta union. |
| `weave timeline`, `why`, `fork` | A child is a run of its own; the parent's `start` step shows its id and outcome; a tree view in `timeline`; `fork` follows the links. |
| Fabric, `loom-inspect-core` and the live page | `RunRegistry` is flat, keyed by process name and trace id, and `RunState` has no parent link today: add a **parent run attribute** on a child's records, grouping in the run list, a **Children** section with status, tokens and elapsed, and the parent's cost total including children. |
| `LoomRun`, `LiveRuns`, `LoomHost` | `LoomRun` lists children, each a `LoomRun`; each child registers under a hierarchical label (`digest/work/Research`), so the `#label` prefix of `--steer-via` reaches it; host settings for the ceilings and the grace period; the `ChildRuns` SPI. |
| VS Code, the compatibility kit, the starter and tutorial, docs and skills | Grammar, snippets, hover, the parser-parity baseline, a `CHANGELOG` line; CTK scenarios including restart and replay; **starter 18** with tests and its picture; a tutorial chapter; `LOOM_GUIDE.md` (and its primitive tables in both READMEs), `LOOM_PROMPT.md`, a wiki page, `llms.txt`, the release notes, the workflow guide's chapter, `llm4j-workflow-patterns` (a supervisor pattern) and `llm4j-run-debugging` (a row for children). |

## 5. Sub-phases

Each ends with `make test-core` (and `make docs-check` where docs, a command, a starter or a review rule changed); the whole phase ends with `make test-fast` on every module touched, then `make test-oracle` once. The guard tests are written **before** the code they guard.

| | Sub-phase | Contents |
|---|---|---|
| 33a | The language | Contextual parsing; the AST nodes; `StatementWalker` and the statement consumers; the keyed-variable additions; handle values and their load errors; `weave check` errors and warnings; `explain`, `graph` and the node describer; cost-estimator facts. **No runtime.** |
| 33b | Child runs | `ChildRuns` SPI; the deterministic id from the step id; journal-first `start`; the child executor and its parts; the status model; `wait` (one and all) with parking and tree suspension; re-attach on replay; the run record's parent link; `fork` following links; `LiveRuns` labels; `LoomRun` children. **The kill-point test first.** |
| 33c | Budgets and limits | The `Budget` hold, the `BudgetSet` charging, required budgets, reservation, `max_children`, `max_depth`, bounded starts; the worst-case cost. |
| 33d | Cancel and steer | `cancel` and its outcomes, effects finish first, `h.partial`, rewind of a child; the parent-ends rule and the grace period; the rewind-cancels-previous-generation rule; the `steer` statement and the `steerable` opt-in; LA28. |
| 33e | Supervise, counted | `SuperviseStmt`; the counters; the tick; ordering, `once`, one steer per agent per tick; `idle` and `elapsed` semantics; the sleep-versus-deadline warning; the controlled executor and the testing vocabulary (`children:`, `fired:`, `starts:`, the virtual clock). |
| 33f | Assertions in code | The four assertions; the preventive `never calls` through the tool-call hooks; name checks against the child's script. |
| 33g | Judged assertions | The facts view; the evaluator call; cadence, floor, `max`, skip-if-unchanged; the journaled verdict; the judge budget and reservation; `judges:` scripting; the audit listing. |
| 33h | The ecosystem and closure | Fabric and `loom-inspect-core`; `timeline` and `why`; VS Code; CTK; starter 18 and the tutorial; docs and skills; the oracle cases; the mutation profile; `docs/API_COMPATIBILITY.md`; the release notes. |

## 6. Decisions

All taken; the log is in [DECISIONS.md](DECISIONS.md). Deferred to the later phase: D6, D7, and the declared-progress and pushed-event halves of D22.

## 7. Compatibility

- `call`, `delegate`, `parallel` and every existing script are unchanged; the new words are contextual (D19), and `RepositoryScriptsTest` guards it.
- **Additive public API** (`docs/API_COMPATIBILITY.md`): `LoomRun` listing children; `LiveRuns` hierarchical labels; the host settings; the `ChildRuns` SPI; a parent attribute on the run record (**`RunRecord` is public: when a component is added the old constructor is kept**); the `Budget` hold in the core.
- The JDBC run registry gains a parent reference: a **schema change** (§8), additive and nullable, needing the existing `createSchema` path to apply it safely to an existing database.

## 8. Risks and what is not yet known

1. **The `Budget` hold** is a change to a core public type, and reservation has to be right under concurrency (`Budget` has its own lock order and reservations). The largest technical risk of 33c.
2. **Two ways a run is built.** `weave run` builds its executor directly (`Runs.java`), `LoomHost` assembles one through `Persistence`; `ChildRuns` must be implemented for both, and the CLI's sibling-folder layout differs from the host's `runs/<id>/`. Which runs `weave resume`, `tick` and `daemon` find, and how they find children, is **not yet read**.
3. **The JDBC registry schema** (`RunRegistry.Jdbc.DDL`) and its migration for an existing database.
4. **The parking `wait`.** Today a suspension is a thrown `RunSuspended` that ends the run's execution; a `wait` that **parks while children run, and suspends only when nothing can run**, is a new state of the executor. Not yet read: how `parallel`'s join and `RunSuspended` interact, which is the nearest existing mechanism.
5. **The preventive `never calls`** has to sit in the same hook chain as the policy and approval hooks without reordering them (`PolicyToolHook`, `ApprovalGate`); not yet read in detail.
6. **Fabric's wire format.** A parent attribute on a child's records is a change to what a process reports (`Snapshot`, the OTLP-shaped logs and spans, and `loom-fabric`'s `FabricLoop`); the compatibility of an older console with a newer process is not yet checked.
7. **A judge sees "the trajectory".** How the evaluation dataset's `expect:` lines render "what the run did" was not read; the facts view should reuse it.
8. **Statement recognition for a keyed handle target** (`-> work[t.name]`): the parser already accepts `-> results[key]` for `delegate` (a key must be a plain word); extending it to `start` and handles must not loosen that.
9. **Time.** A tick, a deadline and a cadence all need the injected clock; the existing `clock:` makes `sleep` virtual, but a **tick** and a **controlled executor** are new, and their determinism is what the tests stand on.
