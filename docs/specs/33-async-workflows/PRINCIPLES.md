# Phase 33: starting workflows and supervising them, the guiding principles

Status: **draft for the owner's decision.** Nothing here is built. The idea is the owner's; this draft states what the code already does, proposes principles, and
leaves every choice open in [DECISIONS.md](DECISIONS.md). A principle becomes final when the owner accepts it. A decision that conflicts with a principle is
settled here first.

## The idea

Today `call Child(...) -> result` runs a sub-workflow **to completion** before the next statement. The idea is to let a script **start** a workflow without waiting,
get a **handle** back in a variable, and then **wait** for it, **steer** it, **cancel** it and **notify** it, so that one workflow can run, watch and operate others
(a supervisor). The short statements replace the queues, run ids, polling, cleanup and restart handling that other stacks leave to the application.

## What the code does today (read, not assumed)

These facts were read in the source on the day this draft was written; each is a reason for, or a constraint on, a principle below.

- `call` runs the child **inside the same executor**: it pushes a variable frame, runs `executeWorkflow`, copies `result` back and pops the frame (`HarnessExecutor.executeCall`).
  Same thread, same journal, same budget. Making a child asynchronous therefore means giving it its own run, not just a new thread.
- The journal is keyed by **stable step ids** ("its position in the script, including loop round and branch"), not by a counter (`RunJournal`). That is what lets
  `parallel` branches replay deterministically, and it is the property an asynchronous child needs.
- `parallel` forks branch threads and **waits for all of them** before the run goes on or pauses (`HarnessExecutor`, "waits for every forked branch to finish").
  There is no construct today for a branch that outlives its block.
- `LoomRun` already gives a Java host a handle on a running workflow: `steer`, `wrapUp`, `cancel` (with an outcome, or back to a checkpoint), `pause`, `resume` and
  `await`, with statuses `RUNNING`, `COMPLETED`, `SUSPENDED`, `STOPPED`, `FAILED`, and it registers in `LiveRuns` so a console or chat can reach it. A script cannot use it.
- Steering and stopping are already audited (`steerable`, rule LA18) and journaled; `supervise` already acts on counts of what an agent did.

Not read yet, to be checked before the design: `BudgetCoordinator` (how a budget is shared), the trigger store and `weave tick`/`daemon` (how a suspended run is picked up),
`InterventionCoordinator`, and how Fabric and `loom-inspect` identify a run.

## The principles (proposed)

1. **A started workflow is a run, not a thread.** It has its own identity, its own journal, its own budget and its own status. The handle is a *reference* to it.
2. **A handle is a capability held by the script, never by a model.** It cannot appear in a prompt, a tool argument or an agent's output, so "who can steer or end this
   child?" is answered by reading the script. `weave audit` reports it.
3. **Structured by default.** A parent does not silently outlive or abandon a child. Every handle is waited for, cancelled or explicitly handed off, on every path;
   `weave check` says when one is not.
4. **Durable by construction.** Starting, waiting, steering, cancelling and notifying are journaled. Replay never starts a second child and never loses a notification.
5. **Signals, not locks.** What the parent and child share is a durable, named signal that a waiting run can sleep on without holding a thread, not an in-memory lock
   that dies with the process. (See D6 and D7: this is where the idea's *monitor* is settled.)
6. **Failure is a value.** A child's outcome (completed, failed, stopped, cancelled, timed out) is data the script reads and branches on. It is never an exception thrown
   into the middle of the parent.
7. **Bounded.** The number of live children, the depth of nesting, the budget and every wait have a limit written in the script. Nothing grows without one.
8. **Effects are never repeated or cut in half.** A child's effects are journaled in its own journal; steering or cancelling never repeats one, and cancelling lets an
   in-flight effect finish and record. A resume or a rewind of the parent must not repeat a child's effect.
9. **Authority only narrows.** A child runs with the invoker credentials of its parent or less, never more. The audit treats a started workflow as part of the parent: what
   it can read, know and send counts toward the lethal trifecta of the workflow that started it.
10. **What comes back is untrusted.** A child's result and a signal's payload carry provenance like any other model-written value, and a script's decision must not rest
    on them without a check.
11. **Visible everywhere.** A handle shows in `weave graph`, `explain`, `audit`, `review`, `timeline`, `why`, the live page and Fabric, the VS Code extension, the compatibility
    kit and the tutorial. The ecosystem moves together, as it did for A2A.
12. **One small surface.** A new verb has to earn its place against the 60-second demo (below). `call` stays synchronous and unchanged; everything here is opt-in.
13. **Same language, same guarantees.** The new constructs follow the existing style (plain words, `max`/`timeout` written on the statement, `on_failure` handlers) and the
    existing checks (`weave check` refuses what is ambiguous; a missing or unknown setting denies).

## The 60-second demo (the test of whether this is a feature or a distraction)

A supervisor workflow that starts three researchers at once, steers one that drifts, cancels one that overspends, and **survives a restart halfway**, with `weave audit`
and Fabric showing the children and who may steer them. It must fit on one screen and need no explanation of llm4j. If a decision makes this demo longer, it needs a reason.

## What is out of scope until decided

Distributing children across machines, a scheduler for children, a visual builder, and any change to `call`.
