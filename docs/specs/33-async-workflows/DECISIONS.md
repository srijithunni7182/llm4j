# Phase 33: open decisions

Status: **D1 to D5, D8 to D12, D21, D28 and D29 taken; D19, D23, D25 and D26 partly taken; the rest open** (see the decision log). D21 to D30 were added after the first sketches (supervision of a started workflow). Each decision gives the question, why it matters, the options and a recommendation. The recommendation is a proposal, not a choice: the owner decides,
one at a time, and the answer is written under **Decision**. [PRINCIPLES.md](PRINCIPLES.md) holds the principles each option is checked against. Facts about the current code
are from the source on the day of writing; where something was not read, it says so.

Take them roughly in this order: D1 (scope) shapes everything; D2 to D5 are the foundation (what a handle is and how it survives a restart); D6 and D7 settle the monitor;
D8 to D14 are the behaviour; D15 to D18 are safety, visibility and the Java side; D19 and D20 are names and scope; D21 to D30 are supervision (where it is written, what it can see, assertions, the judge and time).

## Decision log

| | Decision | State |
|---|---|---|
| D1 | What is in the first version | **taken** (staged: v1 start/wait/cancel/supervise with counted rules; v2 assertions; v3 progress, signals, `wait any`; no `detach`) |
| D2 | What a handle is | **taken** (opaque; keyed variables and `call` arguments allowed; returning refused) |
| D3 | Where the child's journal lives | **taken** (a separate linked run, nested under the parent's folder) |
| D4 | Replay and the double start | **taken** (a deterministic id including the parent's attempt, journal first) |
| D5 | Where the child runs | **taken** (in the same process in v1; the trigger store is not ruled out) |
| D6 | What the monitor is | open (staged to v3 by D1; parked) |
| D7 | Monitor separate or part of the handle | open (staged to v3 by D1; parked; a question for the owner) |
| D8 | What `wait` does | **taken** (required timeout; returns on timeout; `wait all` records every outcome; no thread held) |
| D9 | How a child's outcome reaches the parent | **taken** (eight statuses including `sleeping`; `status`, `is ok`, `reason`, `failure`; the result is an error unless completed) |
| D10 | Children when the parent ends | **taken** (warn at check time; cancel with a reason at run time, 30 s grace; no `detach`) |
| D11 | Whose budget a child spends | **taken** (its own required budget, charged to the parent, with reservation) |
| D12 | What `steer` targets | **taken** (only `steerable` agents; literal text; named outside `supervise`) |
| D13 | What `cancel` means | open |
| D14 | Limits on the tree | open |
| D15 | Authority and security | open |
| D16 | Visibility | open |
| D17 | Tests | open |
| D18 | The Java side | open |
| D19 | Names | **partly taken** (`wait`, `notify` are the owner's) |
| D20 | What stays out | open |
| D21 | Where supervision is written | **taken** (the header names the handle) |
| D22 | How a child exposes progress | open (the owner's view recorded) |
| D23 | The counted conditions | **partly taken** (bare counters relative to the handle) |
| D24 | Assertions in code | open |
| D25 | Judged assertions | **partly taken** (a judge inside `supervise` is wanted) |
| D26 | Time: how long, how often | **partly taken** (time is in scope) |
| D27 | Who pays for a judge | open |
| D28 | What `elapsed` measures | **taken** (running time; `idle` excludes sleep and waiting; the `start` deadline is wall clock; `weave check` warns on a longer sleep) |
| D29 | A suspended parent | **taken** (a waiting parent does not pause children; a parent suspended otherwise does) |
| D30 | Overlap and testing time | open |

"Partly taken" means the owner has said what is wanted; the details under it are still open.

## The current sketch

Not valid Loom today; the `NEW` marks the proposed parts. Everything else is ordinary Loom. This is where the discussion stands, and it changes as decisions are taken.

```loom
evaluator TopicJudge { model: "gemini-2.5-flash" }

workflow Main(question) {
    delegate "Question: {question}" to Planner -> plan

    for each t in plan.topics max 3 {
        start Research(topic = t.topic) timeout 20m budget { tokens: 100000 } -> work[t.name]     // NEW
        supervise work[t.name] {                                                                   // existing form; the header names the handle (D21)
            when tokens > 90000 { cancel use partial }                                             // NEW condition (D23)
            when idle > 3m      { steer "You seem stuck. Narrow the topic and answer with what you have." once }   // NEW condition (D23)
            assert never calls shell { cancel }                                                    // NEW (D24)
            assert "searches only for the topic and reads at least two sources"
                using TopicJudge every 2m max 4 { steer "Go back to the topic." once }             // NEW (D25, D26)
        }
    }

    wait all work timeout 25m -> results                                                           // NEW
    delegate "Question: {question}\nFindings:\n{results}" to Editor -> report
    return report
}
```

The child is an ordinary workflow and needs no change to be supervised. What the surface adds: `start` (with `timeout`, `budget` and a handle), `wait`, the existing `supervise` block pointed at a handle,
three counted conditions, and two kinds of `assert`. What it leaves out for now: a declared `progress` shape and `report` (D22), signals and `notify` (D6, D7), and `wait any`.

## D1. What is in the first version?

**Why.** The surface is five verbs (start, wait, steer, cancel, notify) and each adds semantics. The riskiest part is replay, not the syntax.

- **A. Everything at once.**
- **B. Staged:** v1 = `start`, `wait` (one or all), `cancel`, a readable status; v2 = `notify` and signals; v3 = `steer`, `wait any`, `detach`.
- **C. Start with the 60-second demo and add only what it needs.** The demo needs start, wait, steer, cancel and a restart, so this is nearly A.

**Recommendation: B, with `supervise` as the way to watch and steer.** v1 = `start` (with `timeout`, `budget`, a handle), `wait` (one or all), `cancel`, readable facts (`h.status`, `h.tokens`, `h.elapsed`), and `supervise <handle> { when ... }` with counted conditions (D21, D23);
v2 = code assertions and judged assertions (D24, D25); v3 = declared progress, signals, `wait any` (no `detach`, see D10). Steering is already built for agents, so it is cheaper than it looks, and `supervise` already carries the journaling, tracing and audit.

**Decision (owner): staged, as in option B.**
- **v1:** `start` with `timeout`, `budget` and a handle; `wait` (one or all); `cancel`; readable facts (`h.status`, `h.tokens`, `h.elapsed`); `supervise <handle> { when ... }` with counted rules; the small keyed-variable additions it needs (iterate the keys, `wait all` over a keyed variable).
- **v2:** assertions in code, then judged assertions.
- **v3:** declared progress (`report`), signals (`notify`), `wait any`. (`detach` was removed by the owner under D10: a child never outlives its parent.)

The demo is built in this order: counted rules first (cancel an overspender, steer an idle child, survive a restart), the judged drift check as soon as v2 is in.

## D2. What is a handle?

**Why.** It is the central type. Today variables are strings or typed values; a handle is a reference to a run.

- **A. An opaque value** that names a child run (internally its run id). It can be tested (`h is running`), waited on, steered, cancelled; it cannot be printed into a prompt
  (a placeholder `{h}` is a load error), passed to a tool or agent, or returned from the workflow.
- **B. A string** holding the run id, usable anywhere. Simple, but it breaks principle 2: a model could be given, and could echo, a capability.
- **C. A record** `{ id, status, result }` readable with `h.status`.

**Open sub-questions.** May handles be put in a list (needed for `wait all` over a `for each`)? May a handle be passed to a `call`ed sub-workflow, or returned from one?
**Recommendation: A**, in a list allowed, passing into a `call` allowed (it stays inside the script), returning out of the workflow refused.

**Decision (owner): option A, opaque.** A handle is a value held in a variable that names a child run. It is used only in the handle operations (`wait`, `cancel`, `supervise`, readable facts such as `h.status`); a placeholder `{h}` in a prompt, or a handle given to a tool or an agent, is a load error.
It may live in a keyed variable (`work[t.name]`), and may be passed into a `call`ed sub-workflow. Returning a handle out of a workflow, or in the result of a `call`, is refused. There is no `detach` (D10), so the returning rule is permanent.

## D3. Where does the child's journal live?

**Why.** `call` shares the parent's journal; an asynchronous child cannot (two sequences writing one run's steps, and no way to inspect or cancel the child alone).

- **A. One journal, child steps namespaced under the start site** (for example `Main/s4/child/...`). Simple storage; the child cannot be seen as a run on its own.
- **B. A separate run (own folder or journal) for each child**, linked: the parent's journal holds a `start` entry with the child's run id, the child's run holds its parent's id.
  Cancel, `timeline`, `why`, Fabric and `weave resume` then work on the child as they do on any run.
- **C. Both:** B on disk, with the parent's `start` entry carrying a summary.

**Recommendation: B**, for the reason that every existing tool already understands "a run". Needs the check of how Fabric and `loom-inspect` identify a run (not yet read).

**Decision (owner): option C, nested on disk.** Each child is a separate run with its own journal, linked to its parent: the parent's `start` entry holds the child's run id and a summary, and the child records its parent's id. For the file journal the child lives under the parent's run folder (`<run folder>/children/<child id>/`), so backup, deletion and `weave fork` take the whole tree; the tools that take a run folder (`timeline`, `why`, `rewind`, `resume`) work on a child folder as they are.
For the JDBC journal the equivalent is rows keyed by the child's run id with the parent's id stored on each; the journal interface needs "open the journal of child X", with a file and a database implementation. How Fabric and `loom-inspect` show a child is left to D16.

## D4. How does replay avoid starting the child twice?

**Why.** A crash between "child began" and "parent's journal recorded it" must not produce two children. This is principle 4.

- **A. Deterministic child id:** derive it from the parent's run id and the start site (the stable step id, plus loop round and branch), so starting again attaches to the same run
  instead of making a new one.
- **B. Journal first, then start**, and on replay a missing child with a recorded `start` is started again under the recorded id. Needs a story for "recorded but never began".
- **C. Both:** deterministic id and journal-first.

**Recommendation: C.** The deterministic id makes `start` idempotent, which is what the existing effect rules (principle 8, and "effects are journaled") already ask of everything else.

**Decision (owner): option C, with the parent's attempt number in the id.** The child's id is derived from the parent's run, the parent's **attempt**, and the start site (the stable step id, which already includes the loop round and, for a `for each`, the item); the parent's journal entry for the start is written first.
A **resume** keeps the attempt and so re-attaches to the same child; a **rewind** increments it, so the script starts a fresh child, and the previous attempt's children are cancelled with a recorded reason (see D10) before it does.
The guard test (D17, D30): kill the parent at every point between `start` and `wait`, resume, and check that there is exactly one child and every effect happened once; break the guard on purpose once to see the test fail. This protects against a duplicate child, not against a tool whose own effect was not journaled (the effect rule's job).

## D5. Where does the child run?

**Why.** `LoomRun` runs a workflow on a thread in the same process. Loom also supports runs that suspend for days and are picked up by `weave tick` or `weave daemon` through
a trigger store (read: the trigger package exists; its details were not read for this draft).

- **A. In the same process, on a thread** (like `LoomRun`). A restart resumes it through the same path as any suspended run, because the child has its own journal (D3).
- **B. Through the trigger store**, so any process that ticks can run it. Survives the parent's process dying, but adds a scheduler to the surface.
- **C. A** in v1; **B** left open, nothing in the design may rule it out.

**Recommendation: C.** Different machines are out of scope until decided (see principles).

**Decision (owner): option C.** In v1 a child runs in the same process, on a thread (as `LoomRun` does). Because each child has its own journal (D3) and a deterministic id (D4), a restart re-attaches and resumes it like any suspended run; something has to restart the parent (a person, or `weave daemon`). Nothing in the design may rule out running children through the trigger store later (option B); children on other machines are out of scope for this phase.

## D6. What is the monitor?

**Why.** The idea: *pass a monitor that the sub-workflow uses; the locked monitor is part of the handle, and the parent can notify it.* A lock held in memory cannot survive a
suspend or a restart, so the idea needs a durable form. Principle 5.

- **A. A named signal with a payload (a mailbox).** `notify h "name" text` records an entry in the child's journal; `wait signal "name" timeout 10m -> payload` in the child
  suspends the run until it exists and then consumes it, once, in order. No lost wake-up: a signal that arrives before the wait is simply already there.
- **B. A latch** (a signal that is set once and stays set). Simpler; no payload; no repeated signals.
- **C. A counting semaphore.** Better for "wait for N workers", worse for the common case.

**Recommendation: A**, because a payload ("the new instruction", "the budget is raised") is what a supervisor mostly wants to send, and B is a special case of A. The payload is
untrusted data (principle 10).

**Decision:** open.

## D7. Is the monitor a separate value, or part of the handle?

**Why.** Your wording was "pass a monitor to the sub-workflow", and "the locked monitor is part of the handle". These are two different designs and I could not tell from the idea
which you meant, so this one is **yours to clarify**.

- **A. Signals are addressed through the handle.** The parent writes `notify h "go"`; the child writes `wait signal "go"`. There is no separate monitor object. Fewer concepts, and
  the child needs no argument for it.
- **B. An explicit monitor value.** The parent creates `monitor m`, passes it to `start Child(m = m) -> h`, and both sides use it (`notify m`, `wait m`). More general (it can
  be shared between several children or between siblings), and a monitor can exist before any child.
- **C. A for v1; B added when someone needs sibling-to-sibling signalling.**

**Recommendation: C.** A handle already identifies the one child to be signalled; B is only needed when two children must signal each other.

**Decision:** open. **The owner's wording:** "pass a monitor which the sub workflow uses; the locked monitor is part of the handle", which is a mix of A and B. **Question for the owner, still unanswered:** which of A or B, and is sibling-to-sibling signalling a requirement? Staged to v3 by the simplification (D1), so not needed for the demo.

## D8. What does `wait` do?

**Why.** It is the join, and the place where the parent suspends.

- Forms: `wait h -> result` (one), `wait all handles -> results`, `wait any handles -> first, rest`.
- A `timeout` on every wait, written on the statement, with `on_timeout { ... }`; no wait without a bound (principle 7).
- What it returns: the child's `result` and its status, or only the result with the status read separately.
- It suspends **without holding a thread** when the run is journaled, as `human_prompt` and `sleep` do.
- With `wait any`, which child finishes first is not deterministic, so the **winner is journaled the first time** and a replay returns the recorded one (the same way a person's
  answer is journaled).

**Recommendation:** all three forms in time (one and all first, `any` with D1's v3), a mandatory `timeout`, and `-> result` plus `h.status` readable.

**Decision (owner): accepted as proposed.**
- `wait h timeout T -> result` for one child and `wait all work timeout T -> results` for a keyed variable of handles. The `timeout` is **required** (`weave check` refuses a wait without one).
- On timeout the wait **returns**; the children keep running until the script decides, and the script reads `h.status` (an optional `on_timeout { ... }` block may act on it).
- A single wait returns the child's result, with the status readable on the handle. `wait all` returns a keyed variable with every outcome recorded (a failed or cancelled child is recorded as such, not left out), and waits for every child; fail-fast is written as a `supervise` rule that cancels the others.
- A wait **suspends without holding a thread** when the run is journaled, as `human_prompt` and `sleep` do.

**Amendment (owner, with D9 and D29):** a `wait` **parks while any child is running**; the children keep running and supervision stays active (in v1 the process stays alive, D5). The whole tree **suspends and the process may exit only when nothing can run** (every child sleeping or waiting on a person), and `weave tick` or `weave daemon` resumes it. The earlier line "suspends without holding a thread" applies to that case.

## D9. How does a child's outcome reach the parent?

**Why.** Principle 6: failure is a value.

- **Statuses:** completed, failed, stopped (by a person or `supervise`), cancelled, timed out, waiting on a person. These extend today's `LoomRun.Status`
  (`RUNNING`, `COMPLETED`, `SUSPENDED`, `STOPPED`, `FAILED`); a new one for "cancelled by the parent" may be needed.
- **A. At `wait`:** `wait h -> r on_failure { ... }`, reusing the existing handler.
- **B. At any time:** `h is failed`, in the style of `x is ok` for tasks.
- **C. Both.**

**Recommendation: C**, following how tasks already work (`run ... -> r` then `alt (r is ok)`, and `on_failure`).

**Decision (owner): accepted as proposed.**
- **Statuses:** `running`, `sleeping` (with its wake time readable as `h.wakes`), `waiting` (on a person), `completed`, `failed`, `stopped` (a person, or a `supervise` rule inside the child), `cancelled` (by the parent or its `supervise` rule, with a reason), `timed out` (the `start` deadline passed). `cancelled` and `timed out` stay separate from `stopped`.
- **Reading:** `h.status`; `h is ok` (true only when `completed`; **fails closed**, so a child that has not settled or was cancelled is not ok); `h.reason` (why it was cancelled or stopped); `h.failure` (what failed, with the existing failure category names).
- **The result** (`h.result`, or the `-> result` of a `wait`) exists only for a `completed` child. Reading it on any other status is a **runtime error that names the status**, and `weave check` warns about a read not guarded by `is ok` or a status test.
- **`on_failure`** is allowed on `wait` as shorthand for "when the status is `failed`"; a script may always read the status instead.

## D10. What happens to children when the parent ends, fails or is cancelled?

**Why.** Principle 3. This is where abandoned runs, and their bills, come from.

- **A. Check-time rule:** `weave check` refuses a script in which a handle can reach the end of the workflow without being waited for, cancelled or detached, on any path.
- **B. Run-time rule:** when the parent ends (any way), still-running children are cancelled with a recorded reason, and the parent's end waits for them to settle.
- **C. `detach h`** lets a child outlive the parent, explicitly. Deferred to v3 unless the demo needs it.
- **D. A and B together.**

**Recommendation: D.** Static where the script can tell, enforced at run time where it cannot.

**Decision (owner): both layers, and no `detach`.**
- **Check time:** `weave check` **warns** when a `start` result is never waited on, cancelled or supervised anywhere in the workflow (a warning, not an error: a handle in a keyed variable inside a loop cannot always be proven handled on every path).
- **Run time (the guarantee):** when a parent ends in any way (finishes, `halt`, fails, is cancelled, or is rewound), every child still running is **cancelled with a recorded reason** ("parent ended"), and the parent's end is not complete until they settle. Settling lets an in-flight effect finish and record (D13), within a **grace period**, a runtime setting with a default (proposed 30 seconds); a child still unsettled after it is recorded as `cancelled` with a note that it did not settle, and its journal shows where it stopped.
- After a process death nothing is cancelled: on resume the parent re-attaches to each child by id (D4).
- **There is no `detach`.** A child never outlives the run that started it. That also makes the D2 rule permanent: a handle is never returned out of a workflow.

## D11. Whose budget does a child spend?

**Why.** A parent that starts children in a loop could multiply cost. Budgets today are checked before every model call (`BudgetCoordinator`, not yet read for how scope is shared).

- **A. The child shares the parent's budget** (draws from the same pool).
- **B. The child has its own** limits, declared on the `start` statement, which may not exceed what the parent has left.
- **C. B, and the cost is also charged to the parent** so one run total still holds.

**Recommendation: C**, with an explicit child budget **required** (a `start` with no budget is a `weave check` error, as an `expose` with none is flagged by LA27).

**Decision (owner): option C, with a required budget and reservation.**
- Every `start` carries a `budget` (`start Research(...) budget { tokens: 100000 } -> h`); a `start` with none is a `weave check` error (as an `expose` with none is flagged by LA27).
- The child's cap may not exceed what the parent has left **minus what its other live children could still spend** (a reservation); if it would, the `start` fails at run time with a message that says so, instead of letting the child hit a wall mid-work.
- If the child's own script declares a `budget`, the **smaller** of the two applies. The child's spending also counts against the parent's budget, so the parent's `budget` stays the limit for the whole run.
- A child whose budget runs out ends `failed` with the budget as the failure category (D9). If the **parent's** budget runs out while children run, the run ends in the normal way and D10 cancels the children.
- The worst-case cost shown by `weave review` and `weave generate` is the sum of the child budgets times the most children the script can start (D14).
- Not yet read: `BudgetCoordinator`, to see how a budget is shared between scopes today.

## D12. What does `steer` target?

**Why.** `LoomRun.steer(agent, text)` steers one running agent by name; a child has several agents.

- **A. `steer h Researcher "text"`:** an agent of the child, which must be `steerable: true` (and gets the guard check).
- **B. `steer h "text"`:** whichever agents of the child are steerable and running.
- **C. A, with B as the form for a child with exactly one steerable agent.**

**Recommendation: A.** Explicit, and it extends the existing input-channel audit (LA18) to "a parent script can steer this agent".

**Decision (owner): option C, opt-in.** A parent can address, and steer, only the agents of a started workflow that are marked `steerable: true`. The child's author decides what is exposed; the parent still cannot see the child's prompts, variables or other agents.
- Inside a `supervise` block a bare `steer "text"` applies to the handle's running steerable agents; naming one (`steer Researcher "text"`) is optional there and **required** in a `steer` statement outside `supervise`: `steer work[name] Researcher "text"`.
- Naming an agent that is not `steerable` is a `weave check` error that says why. Renaming a steerable agent is a visible interface change.
- The steer text is **literal text written in the script**; a placeholder in it is a load error. (A table keyed by a judge's verdict may offer a choice among fixed texts later, D25.)
- If no steerable agent of the child is running when the steer fires, nothing is delivered; that is recorded in the journal and trace as "not delivered" and is not an error (as `LoomRun.steer` returns how many agents took it). `once` and the existing fire-when-it-becomes-true behaviour stop a rule steering on every tick.
- `weave audit` lists a script that can steer a started workflow as an input channel (in the manner of LA18); the new rule number follows LA27.
- This replaces the earlier proposal that a script-declared steer needs no `steerable`: that precedent holds inside one workflow, and a parent and a child are different workflows.

## D13. What does `cancel` mean?

**Why.** Cancelling must not cut an effect in half or repeat one (principle 8).

- **Reuse the existing outcomes:** `use partial`, `skip`, `discard`, `conclude`, `rewind to <checkpoint> at most N times carrying feedback`, and `wrap_up` as a gentler stop.
- **Rule:** an in-flight effect finishes and is journaled before the child stops; the cancel is recorded with its reason.
- Open: can a parent cancel a child back to a checkpoint (`LoomRun.cancel` can already)? **Recommendation:** yes, using the same rule that the checkpoint must be declared and
  `at most N times` is stated.

**Decision:** open. The owner's word for cancel is "end".

## D14. Limits on the tree

**Why.** Principle 7.

- A maximum number of **live** children per run, written in the script (a default and a hard ceiling).
- A **maximum depth** of nesting (a child that starts children).
- `start` inside a `loop` or `for each` needs the same kind of `max` as a fan-out over a list a model wrote (rule LR13), because the number of children is the number of rounds.

**Recommendation:** all three, with small defaults.

**Decision:** open.

## D15. Authority and security

**Why.** Principles 2, 9 and 10.

- **Authority:** the child receives the parent's invoker credentials, never more (read: invoker credentials already travel with `LoomRun.start`).
- **Audit:** a new rule for a script that can steer or cancel a started workflow (an input channel, like LA18); the trifecta analysis treats `start` as part of the parent, and a
  rule for a handle used where a model could see it. Numbers follow LA27.
- **Handles and models:** a handle can never be put in a prompt, a tool argument or an agent's output (a load error, D2).
- **Signals:** the payload carries `unchecked` provenance (the oracle must hold: a signal's text is never trusted by the script without a check).
- **A child's `human_prompt`:** goes through the same channel the parent would use, and which one is set where (open).

**Recommendation:** as listed. The audit rules and the oracle cases are written before the runtime, as for A2A.

**Decision:** open.

## D16. How is it made visible?

**Why.** Principle 11; the ecosystem list from A2A applies (graph, explain, audit, review, timeline, `why`, live page and Fabric, VS Code grammar and snippets, compatibility kit,
tutorial, wiki, README census, release notes).

- Graph: new node kinds (`start`, `wait`, `steer`, `cancel`, `notify`) and an edge from a `start` to its `wait`; `scripts/sync-graph-render.sh` keeps the renderer in step.
- `weave timeline` and `weave why` show the children as runs of their own and as steps of the parent.
- Fabric and the live page show a run tree.
- **Open:** whether a child is one more row in Fabric's run list (D3 option B makes it natural) or a nested view.

**Recommendation:** child runs appear as runs, linked to the parent; a tree view is a refinement.

**Decision:** open.

## D17. What does a test look like?

**Why.** Tests must stay hermetic, with no real clock and no real models.

- `weave test` needs a way to **script a child**: its outcome, its result and when it finishes, as it scripts a model or a stream today (`models: { Writer: { stream: [...] } }`).
- A fake clock for `timeout` and for the order of `wait any`.
- Replay tests: kill the parent at every point between `start` and `wait`, and resume; **there must be exactly one child and every effect once.** This is the guard test for
  principle 4, and it is broken on purpose once to check that it fails.
- The provenance oracle runs every script in the repository, so a new `.loom` example is checked automatically.

**Recommendation:** as listed, decided before the runtime is written.

**Decision:** open.

## D18. The Java side

**Why.** `LoomRun` is the embedding API. A script that starts children should be visible to a Java host, and a host should be able to steer a child by the same names.

- Does `LoomRun` expose a run's children? Does `LiveRuns` list them, so `weave run --steer-via console` and a chat can reach a child?
- **Recommendation:** yes to both; a child is reachable the way any run is. Details after D3 and D5.

**Decision:** open.

## D19. Names

**Why.** `run` is already the task statement; `call`, `delegate` and `handoff` exist; `sleep` is a wait on time.

- Candidates: `start` / `spawn` / `launch` for the verb; `wait` (as you proposed); `notify` (yours) or `signal`; `cancel`; `steer`.
- **Recommendation:** `start`, `wait`, `notify`, `cancel`, `steer`. They read as plain words, none clashes with an existing keyword (to be confirmed by the lexer check),
  and they match the Java names where one exists (`steer`, `cancel`).

**Decision:** partly taken. `wait` and `notify` are the owner's words, and the Java names `steer` and `cancel` already exist. `start` is a proposal (the owner said "invoke async"). To confirm with the lexer check.

## D20. What stays out of this phase

- `detach` (a child outliving its parent): not wanted (D10).
- Children on other machines, a scheduler or queue of children, a visual builder, and any change to `call`.
- Whether `weave generate` should be taught to produce supervisor workflows: after the language settles.

**Decision:** open.

## D21. Where is supervision written? **Taken by the owner**

**Question.** `supervise` can be attached to the `start` statement (the target implicit), or be a separate block whose header names the handle, as `supervise Researcher { ... }` names an agent today.

**Decision (owner):** the header names the handle: `supervise work[t.name] { when tokens > 90000 { ... } }`. Conditions inside are bare counters, relative to the named handle; they are not script expressions
(a condition that read arbitrary script variables would need the runtime to re-evaluate script expressions on every change, and `weave check` could no longer say which counters a rule depends on).
The same facts remain readable as plain values elsewhere in the script (`work[t.name].tokens`).

## D22. How does a child expose its progress?

**Why.** What a supervisor can do depends on what it can see.

- **A. Runtime facts**, free and trustworthy: `status`, `tokens`, `calls`, `cost`, `elapsed`, `idle`, the current step, the last checkpoint reached.
- **B. Declared progress:** the child declares a typed shape in its signature and updates it with `report`; the parent reads `h.progress.stage`. Written by the child, so it carries `unchecked` provenance and is for display and soft rules only.
- **C. Pushed events:** the child sends a signal to its starter (`notify parent ...`); needs the signal mechanism of D6 and D7.
- **D. Reading a child's variables directly.** Refused: it breaks encapsulation.

**The owner's stated view:** A, B and C are all wanted, with a simple syntax. **Proposal:** A in v1, because it needs no change to the child and `supervise` can act on it; B and C in a later stage, with the rules proposed in the earlier discussion
(progress is bounded in size and rate, journaled, restored by a rewind, readable only through the handle, Fabric and the live page).

**Decision:** open. **The owner's view:** A, B and C are all wanted, with a simple syntax. Proposal: A in v1, B and C later.

## D23. Which counted conditions are in v1?

**Why.** In `supervise` today a condition is one of a closed set of runtime counters (`looping(N)`, `tool_failing(Tool, N)`, `context > P%`, `steps > N`), so rules can be checked before a run and cost nothing to evaluate. This keeps that property.

- Proposed new counters for a handle: `tokens`, `cost`, `calls`, `elapsed`, `idle` (time with work to do but no progress: a child that is sleeping or waiting on a person is not idle, see D28), `status`.
- `idle` is the one that catches a child stuck in a single long step, which no step-based rule can see.
- Actions are the existing ones: `steer "text"`, `wrap_up`, `cancel [outcome]`. A steer text is **written in the script**, never taken from a child's output.
- Rules fire when the condition *becomes* true and again only if it goes false and true again; `once` limits a rule to one firing (existing behaviour).

**Recommendation:** `tokens`, `cost`, `calls`, `elapsed`, `idle`, `status` in v1. A condition on a child with several agents that names no agent applies to the child as a whole.

**Decision:** partly taken. The owner confirmed (D21) that conditions are bare counters relative to the named handle. The list of counters is open.

## D24. Assertions in code about a child's trajectory

**Why.** `weave test` already has path, round-count and budget tests on scripted models. The same checks could run live, as free, deterministic, checkable rules, which suits the project's principle that a rule lives in code.

- Candidates: `assert never calls <tool>`, `assert steps <= N`, `assert calls A before B`, `assert never reaches <checkpoint>`.
- The block after an assertion runs when it is **violated**, with the same actions as `when`.
- `weave check` verifies that the names exist; `weave explain` says them in words.

**Recommendation:** v2, and before any judged assertion (D25). **Open:** the exact list; whether they should reuse the `weave test` path syntax.

**Decision:** open.

## D25. Judged assertions: a judge agent asserts the trajectory

**Why.** Some things code cannot express ("stays on topic"). The owner wants a judge inside `supervise`. This is a model's opinion in a block whose rules were counts, so it is explicit and bounded.

```loom
assert "searches only for the topic and reads at least two sources" using TopicJudge every 2m max 4 { steer "Go back to the topic." once }
```

- **What the judge sees.** By default the **facts** of the trajectory (steps, agents and tools called, checkpoints, counts), the same record `weave why` and `weave capture` use; not the child's text. A rule may opt in to content (`seeing outputs`), and the audit then says so.
  (How the evaluation dataset's `expect:` lines render "what the run did" was not read; the design should reuse it.)
- **The verdict** is a fixed pass or fail, never free text.
- **The action** is text written in the script, or a pick from a table written in the script keyed by the verdict (`steer by verdict: { off_topic: "...", thin: "..." }`). A judge never writes an instruction for a child.
- **Replay.** A verdict is journaled with the child's position at the time; a resume returns the recorded verdict and never asks again.
- **Cost.** Each check is a model call, bounded by D26, charged to a budget (D27) and shown in the cost estimate.
- **Audit.** `weave audit` lists each judged rule, what the judge can see and what it can cause. A judge that can cancel work is a decision-maker: whether it comes under the earned-autonomy ladder is open.
- **Injection.** A trajectory may contain untrusted text; facts-only by default, a fixed verdict and fixed actions close that path. The provenance oracle gets cases for it.

**Recommendation:** v2, facts only by default, fixed text or a table, the verdict journaled.

**Decision:** partly taken. **The owner wants a judge agent inside `supervise` that asserts the trajectory of the sub-workflow.** Open: the `assert` spelling (a proposal), what the judge sees, where the action text comes from, whether the ladder applies.

## D26. Time: how long, and how often

**Why.** Time is a first-class part of supervision. An earlier proposal to judge "every N steps" was wrong: a child stuck in one long step never reaches step N.

| Question | Proposal |
|---|---|
| How long may the child run? | A deadline on `start`, written like the existing `timeout` of `delegate`: `start Research(...) timeout 20m`. When it passes the child is cancelled with the outcome `timed out`, readable as a status. |
| How long does supervision last? | As long as the child runs: it ends when the child completes, fails or is cancelled. No separate lifetime. |
| How often are counted rules checked? | When a counter changes, plus a runtime tick for time counters (about every 10 seconds). A tick is a local comparison and costs no model call. Not written by the script. |
| How often is a judged assertion run? | The script says: `every 2m` or `every 3 steps`, one unit per assertion, with `max N` **required** and no default. A **floor** (proposed 30 seconds) is enforced by `weave check`. A check is skipped if the child has done nothing since the last one. |
| Guidance | About 5 to 10 judgements over the child's expected lifetime (a heuristic, not a measurement). |

- **Replay.** A tick and its verdict are journaled with the child's position, as `sleep` journals its wake time, so a replay returns what was recorded.
- **Later, not v1:** back-off (`every 1m, then 3m`).

**Decision:** partly taken. **The owner said time has to be factored in** (how long to supervise, how often). Open: every specific in the table (the deadline spelling, the tick, the cadence unit, the floor, the default).

## D27. Who pays for a judge?

- **A.** The `supervise` statement has its own budget (`supervise work[...] budget { tokens: 20000 } { ... }`).
- **B.** The child's budget. **C.** The parent's budget. **D.** A, and also counted against the parent so the run total holds (as D11 option C).

**Recommendation: D**, with the `supervise` budget required when a judged assertion is present.

**Decision:** open.

## D28. What does `elapsed` measure when the process was down?

**Why.** After a restart two hours later, a rule `elapsed > 5m` fires at once if it counts wall time since the start.

- **A.** Wall time since the start: simple and honest, but a restart looks like a stall.
- **B.** Running time only (suspended and down time excluded); the `timeout` on `start` stays a wall-clock deadline as the hard limit.

**Recommendation: B**, with the child's `timeout` as the wall-clock limit. Needs the clock to be injected so the rule can be tested (D30).

**Decision (owner): accepted as proposed.** `elapsed` and `idle` exclude time the child spends sleeping or waiting on a person: `elapsed` counts running time, and `idle` is time **with work to do but no progress**. The deadline on `start` (D26) stays a **wall-clock** limit that **does** count sleep.
Because the started workflow's script is known, `weave check` **warns when that workflow contains a `sleep` or a wait longer than the `start` timeout**. Judged assertions skip a child that has done nothing since the last one, so a sleeping child is skipped. (Whether a `sleeping` status is added, and how a waiting parent treats its children, are still open: see the open points under D9, D8 and D29.)

## D29. Is a suspended parent still supervising?

**Why.** `supervise` needs something awake to tick. In v1 children run in the same process (D5 option A), so a parent that suspends (for a person, or a long `sleep`) is not ticking.

- **A.** Children are **paused with the parent**, and their deadlines are paused too (consistent with principle 3).
- **B.** Children keep running unsupervised: unsafe, refused.
- **C.** Supervision across suspended runs, through the trigger store and `weave daemon` (D5 option B): later.

**Recommendation: A for v1, C later.**

**Decision (owner): option A, narrowed.** A parent that **waits on its children** (`wait`) does not pause them (see the amendment under D8). A parent that suspends for **anything else** (`human_prompt`, its own `sleep`) **pauses its children too, with their deadlines paused**, because nothing is awake to supervise them. Supervision across suspended runs, through the trigger store and `weave daemon`, is later (option C).

## D30. Overlapping rules and testing time

- **Order.** If several rules fire in the same tick, they are applied in the order written, and a `cancel` ends the evaluation. A conflict (two rules that steer the same agent differently) is a `weave check` warning.
- **Tests.** `weave test` needs a fake clock and scripted children that take chosen amounts of time (their durations, their counters, their verdicts), so every timeout, `idle` and cadence rule can be tested with no real waiting. Designed with the feature, not after.
- **The guard test.** Kill the parent at every point between `start` and `wait`, resume, and check that there is exactly one child and every effect happened once (D17); break the guard on purpose once to see it fail.

**Decision:** open.

## Not yet read, to settle before the design is written

`BudgetCoordinator`, the trigger store and `weave tick`/`daemon`, `InterventionCoordinator`, `LiveRuns`, how `loom-inspect` and Fabric identify a run, and whether the lexer accepts
`start`, `wait` and `notify` as new keywords without breaking existing scripts (the repository's `.loom` files are the test: `RepositoryScriptsTest`).
