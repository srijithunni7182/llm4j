# Phase 33: open decisions

Status: **all open.** Each decision gives the question, why it matters, the options and a recommendation. The recommendation is a proposal, not a choice: the owner decides,
one at a time, and the answer is written under **Decision**. [PRINCIPLES.md](PRINCIPLES.md) holds the principles each option is checked against. Facts about the current code
are from the source on the day of writing; where something was not read, it says so.

Take them roughly in this order: D1 (scope) shapes everything; D2 to D5 are the foundation (what a handle is and how it survives a restart); D6 and D7 settle the monitor;
D8 to D14 are the behaviour; D15 to D18 are safety, visibility and the Java side.

## D1. What is in the first version?

**Why.** The surface is five verbs (start, wait, steer, cancel, notify) and each adds semantics. The riskiest part is replay, not the syntax.

- **A. Everything at once.**
- **B. Staged:** v1 = `start`, `wait` (one or all), `cancel`, a readable status; v2 = `notify` and signals; v3 = `steer`, `wait any`, `detach`.
- **C. Start with the 60-second demo and add only what it needs.** The demo needs start, wait, steer, cancel and a restart, so this is nearly A.

**Recommendation: B**, but with the demo as the acceptance test for v2/v3, so steering is not left too long. Steering is already built for agents, so it may be cheaper than it looks.

**Decision:** open.

## D2. What is a handle?

**Why.** It is the central type. Today variables are strings or typed values; a handle is a reference to a run.

- **A. An opaque value** that names a child run (internally its run id). It can be tested (`h is running`), waited on, steered, cancelled; it cannot be printed into a prompt
  (a placeholder `{h}` is a load error), passed to a tool or agent, or returned from the workflow.
- **B. A string** holding the run id, usable anywhere. Simple, but it breaks principle 2: a model could be given, and could echo, a capability.
- **C. A record** `{ id, status, result }` readable with `h.status`.

**Open sub-questions.** May handles be put in a list (needed for `wait all` over a `for each`)? May a handle be passed to a `call`ed sub-workflow, or returned from one?
**Recommendation: A**, in a list allowed, passing into a `call` allowed (it stays inside the script), returning out of the workflow refused.

**Decision:** open.

## D3. Where does the child's journal live?

**Why.** `call` shares the parent's journal; an asynchronous child cannot (two sequences writing one run's steps, and no way to inspect or cancel the child alone).

- **A. One journal, child steps namespaced under the start site** (for example `Main/s4/child/...`). Simple storage; the child cannot be seen as a run on its own.
- **B. A separate run (own folder or journal) for each child**, linked: the parent's journal holds a `start` entry with the child's run id, the child's run holds its parent's id.
  Cancel, `timeline`, `why`, Fabric and `weave resume` then work on the child as they do on any run.
- **C. Both:** B on disk, with the parent's `start` entry carrying a summary.

**Recommendation: B**, for the reason that every existing tool already understands "a run". Needs the check of how Fabric and `loom-inspect` identify a run (not yet read).

**Decision:** open.

## D4. How does replay avoid starting the child twice?

**Why.** A crash between "child began" and "parent's journal recorded it" must not produce two children. This is principle 4.

- **A. Deterministic child id:** derive it from the parent's run id and the start site (the stable step id, plus loop round and branch), so starting again attaches to the same run
  instead of making a new one.
- **B. Journal first, then start**, and on replay a missing child with a recorded `start` is started again under the recorded id. Needs a story for "recorded but never began".
- **C. Both:** deterministic id and journal-first.

**Recommendation: C.** The deterministic id makes `start` idempotent, which is what the existing effect rules (principle 8, and "effects are journaled") already ask of everything else.

**Decision:** open.

## D5. Where does the child run?

**Why.** `LoomRun` runs a workflow on a thread in the same process. Loom also supports runs that suspend for days and are picked up by `weave tick` or `weave daemon` through
a trigger store (read: the trigger package exists; its details were not read for this draft).

- **A. In the same process, on a thread** (like `LoomRun`). A restart resumes it through the same path as any suspended run, because the child has its own journal (D3).
- **B. Through the trigger store**, so any process that ticks can run it. Survives the parent's process dying, but adds a scheduler to the surface.
- **C. A** in v1; **B** left open, nothing in the design may rule it out.

**Recommendation: C.** Different machines are out of scope until decided (see principles).

**Decision:** open.

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

**Decision:** open. **Question for the owner:** which of A or B did you have in mind, and is sibling-to-sibling signalling a requirement?

## D8. What does `wait` do?

**Why.** It is the join, and the place where the parent suspends.

- Forms: `wait h -> result` (one), `wait all handles -> results`, `wait any handles -> first, rest`.
- A `timeout` on every wait, written on the statement, with `on_timeout { ... }`; no wait without a bound (principle 7).
- What it returns: the child's `result` and its status, or only the result with the status read separately.
- It suspends **without holding a thread** when the run is journaled, as `human_prompt` and `sleep` do.
- With `wait any`, which child finishes first is not deterministic, so the **winner is journaled the first time** and a replay returns the recorded one (the same way a person's
  answer is journaled).

**Recommendation:** all three forms in time (one and all first, `any` with D1's v3), a mandatory `timeout`, and `-> result` plus `h.status` readable.

**Decision:** open.

## D9. How does a child's outcome reach the parent?

**Why.** Principle 6: failure is a value.

- **Statuses:** completed, failed, stopped (by a person or `supervise`), cancelled, timed out, waiting on a person. These extend today's `LoomRun.Status`
  (`RUNNING`, `COMPLETED`, `SUSPENDED`, `STOPPED`, `FAILED`); a new one for "cancelled by the parent" may be needed.
- **A. At `wait`:** `wait h -> r on_failure { ... }`, reusing the existing handler.
- **B. At any time:** `h is failed`, in the style of `x is ok` for tasks.
- **C. Both.**

**Recommendation: C**, following how tasks already work (`run ... -> r` then `alt (r is ok)`, and `on_failure`).

**Decision:** open.

## D10. What happens to children when the parent ends, fails or is cancelled?

**Why.** Principle 3. This is where abandoned runs, and their bills, come from.

- **A. Check-time rule:** `weave check` refuses a script in which a handle can reach the end of the workflow without being waited for, cancelled or detached, on any path.
- **B. Run-time rule:** when the parent ends (any way), still-running children are cancelled with a recorded reason, and the parent's end waits for them to settle.
- **C. `detach h`** lets a child outlive the parent, explicitly. Deferred to v3 unless the demo needs it.
- **D. A and B together.**

**Recommendation: D.** Static where the script can tell, enforced at run time where it cannot.

**Decision:** open.

## D11. Whose budget does a child spend?

**Why.** A parent that starts children in a loop could multiply cost. Budgets today are checked before every model call (`BudgetCoordinator`, not yet read for how scope is shared).

- **A. The child shares the parent's budget** (draws from the same pool).
- **B. The child has its own** limits, declared on the `start` statement, which may not exceed what the parent has left.
- **C. B, and the cost is also charged to the parent** so one run total still holds.

**Recommendation: C**, with an explicit child budget **required** (a `start` with no budget is a `weave check` error, as an `expose` with none is flagged by LA27).

**Decision:** open.

## D12. What does `steer` target?

**Why.** `LoomRun.steer(agent, text)` steers one running agent by name; a child has several agents.

- **A. `steer h Researcher "text"`:** an agent of the child, which must be `steerable: true` (and gets the guard check).
- **B. `steer h "text"`:** whichever agents of the child are steerable and running.
- **C. A, with B as the form for a child with exactly one steerable agent.**

**Recommendation: A.** Explicit, and it extends the existing input-channel audit (LA18) to "a parent script can steer this agent".

**Decision:** open.

## D13. What does `cancel` mean?

**Why.** Cancelling must not cut an effect in half or repeat one (principle 8).

- **Reuse the existing outcomes:** `use partial`, `skip`, `discard`, `conclude`, `rewind to <checkpoint> at most N times carrying feedback`, and `wrap_up` as a gentler stop.
- **Rule:** an in-flight effect finishes and is journaled before the child stops; the cancel is recorded with its reason.
- Open: can a parent cancel a child back to a checkpoint (`LoomRun.cancel` can already)? **Recommendation:** yes, using the same rule that the checkpoint must be declared and
  `at most N times` is stated.

**Decision:** open.

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

**Decision:** open.

## D20. What stays out of this phase

- Children on other machines, a scheduler or queue of children, a visual builder, and any change to `call`.
- Whether `weave generate` should be taught to produce supervisor workflows: after the language settles.

**Decision:** open.

## Not yet read, to settle before the design is written

`BudgetCoordinator`, the trigger store and `weave tick`/`daemon`, `InterventionCoordinator`, `LiveRuns`, how `loom-inspect` and Fabric identify a run, and whether the lexer accepts
`start`, `wait` and `notify` as new keywords without breaking existing scripts (the repository's `.loom` files are the test: `RepositoryScriptsTest`).
