---
name: llm4j-harness-design
description: Designs the harness around an autonomous workflow before it is built (what agents may touch, how a run knows it is done, how it is bounded, survives a crash, fails, who may step in, how it is watched and how the harness itself is tested) and writes it down as a HARNESS.md design record mapped onto Loom. For any autonomous workflow - a pipeline, a planner and workers, a long research or coding loop, a background job that runs for days, an agent taking over a person's decision. Use when the user wants to design, review or harden an agent harness or autonomous loop; asks what a workflow should do when it loops, fails, runs out of budget, is stopped or is left alone overnight; asks how to stop an agent gaming its checks, editing what it should not, or repeating a payment after a crash; or asks for harness best practices. Building the design in Loom is the llm4j-workflow-guide skill; running it watched is llm4j-loom-deployer.
---

# llm4j harness design

A harness is everything around the model that the model does not control: what it may read and change, what it is told, how its
work is checked, when it must stop, what is remembered, what happens when a step fails, and who can step in. The model proposes.
The harness decides what is allowed, what counts as done, and what survives.

You are helping the user **design** that harness, before it is built. The output is a design record, `HARNESS.md`, in the user's
project. The `llm4j-workflow-guide` skill then builds it in Loom, and the `llm4j-loom-deployer` skill runs it where people can watch.
This skill writes no Loom script; it decides what the script must do.

**The repository may not be here: never send the user to a repository path and never ask them to fetch one.** What you need is in
this skill's `references/` folder and in the tools:

- `references/principles.md`: the practices, by area, each with why it matters and how to check it.
- `references/failure-modes.md`: the ways harnesses go wrong in practice, each with its symptom, cause and fix.
- `references/loom-mapping.md`: each decision, the Loom construct that carries it, the `weave guide` chapter that builds it, and
  what Loom does not cover.
- `references/cost-and-models.md`: how to suggest models for each step, estimate and cap the cost, and what to record. Read it whenever the user asks about models or cost.
- `references/design-record.md`: the `HARNESS.md` template you fill in with the user.
- If `weave` is installed, `weave guide loom` and `weave <command> --help` are the truth about Loom's names and options. Do not
  guess a construct; look. If `weave` is not installed, design anyway and leave the mapping column for the build.

## Ten rules a harness lives by

1. **Use the least autonomy that works.** A fixed sequence beats a router, a router beats a planner, a planner beats an open loop.
   Move up only when the task needs it, and say why.
2. **Let models read and propose; let code decide and act.** A step that must happen every time, must be exact, or moves money is a
   deterministic step the model cannot call, skip or talk its way past. An agent may hold a tool that sends, writes or deletes only
   behind an approval a person gives (or a code check), and only with the least reach that does the job.
3. **Done is checked, not declared.** Every exit has a criterion. Where it can be counted or compared, code checks it. The agent that
   did the work is never the only one who grades it.
4. **The agent cannot edit its own yardstick.** The objective, the evaluator, the tests, the budget and the autonomy level are locked
   surfaces. Assume an optimizing agent will learn the harness and look for the cheapest way to pass it.
5. **Everything has a bound.** Iterations, rounds, rewinds, tokens, calls, money and wall-clock time. Every bound says what happens
   when it is reached; a bound with no outcome is a crash.
6. **Every step that can fail has a decision.** Retry only what is safe to repeat. An unknown outcome of a step that changed the world
   is checked, never retried blindly.
7. **A run survives the process.** Steps are journaled, effects are idempotent, and a resumed run never pays twice. State lives in the
   harness, not in a prompt.
8. **The harness owns the context.** It decides what each step sees, plans for the model's window, and keeps stable instructions
   stable.
9. **A person stands in front of every irreversible way out**, until the agent has earned the right on evidence, under a ceiling a
   person set.
10. **Test the harness, not just the agents.** Kill it mid-run, fail its tools, exhaust its budget, feed it hostile input, and check
    it does what the design says.

## The path

| # | Stage | Read | Gate before moving on |
|---|---|---|---|
| 1 | Frame the job | principles §1 | one paragraph: what goes in, what comes out, how long it runs, who watches, what cannot be undone; an autonomy shape chosen with a reason |
| 2 | Define done and who checks it | principles §2 | every exit (success, gave up, stopped, halted) has a criterion; each criterion is marked **code**, **model** or **person**; no agent grades only itself |
| 3 | Draw the boundary | principles §3, §9 | the surface table is filled (locked, editable, read, write, send) for each agent and tool; each tool has the least reach that works; each agent that reads untrusted content, reaches private data and can send something out is split, or the user accepted it in writing with a reason |
| 4 | Shape the control loop | principles §4, §15 | every loop and rewind has a maximum and an outcome when it is reached; budgets at three levels (run, agent, provider); a rule for an agent that loops or stalls; every parallel fan-out says what happens when one branch fails or is stopped, and keeps its rewinds outside the fan-out |
| 5 | State, memory and context | principles §5, §6 | a crash at any step is described: what is replayed, what is re-run, what is never repeated; every step that changes the world has an idempotency story; every agent that loops on tools has a context plan and a known window |
| 6 | Failure and recovery | principles §7, failure-modes | the failure table is complete: for each step, its failure kinds and the decision for each (retry, other path, person, rewind, halt, let it fail) |
| 7 | People in the loop | principles §8 | each irreversible action has an approver or a written decision not to; who may steer, stop and approve is named, and how they reach a running process; earned autonomy only where the user asked for it |
| 8 | Watching and cost | principles §10 | a cost model with real numbers from the user or the provider; the events that count as trouble; where the trace, journal and audit log are kept |
| 9 | Test plan for the harness | principles §12 | one fault test per decision in stages 4 to 7, each with the behaviour the design expects |
| 10 | Write the record and hand over | design-record | `HARNESS.md` is complete, the user read it back and agreed; open questions are listed, not hidden |

Stages 1 to 3 are always done. For a short, read-only workflow with no tools and no effects, stages 5, 7 and 8 can be one line each:
say so, write the line, and move on. Never skip stage 2 or stage 9.

## How to guide

1. **Find out where they are.** Is this a new design, a review of an existing workflow, or a fix after something went wrong? For a
   review, read the existing script or code first (`weave explain` and `weave audit` if it is Loom) and fill the record from what
   is there; then mark each gap. For an incident, start at `references/failure-modes.md` with the symptom.
2. **Ask in plain words, one or two questions at a time.** What goes in, what comes out, what must never happen, what costs money,
   what cannot be undone, who is around when it runs. Use the user's words, not the framework's.
3. **Turn the request into hard requirements**, and mark each one **checked by code** or **judged by a model**. A requirement that
   is only in a prompt is a wish. Countable and exact things are code.
4. **Work one stage at a time**, and say the gate and whether it passed. Fill the matching section of `HARNESS.md` as you go, so the
   user sees the record grow.
5. **Name the trade-off when you recommend.** More autonomy costs predictability; more approvals cost the person's attention;
   compaction costs detail; a summary costs a model call. Give the recommendation and its price in one sentence each.
6. **Map each decision to Loom as you go** (`references/loom-mapping.md`). When Loom has no construct for it, say so plainly and
   write what the user's own code or process must do instead. If the user is not building on Loom, keep the decisions and leave the
   mapping column to their runtime.
7. **Show the checklist** at the start and after every stage, ticking only what was done:

```
[x] Job framed (pipeline with one research loop; runs nightly, unwatched)
[x] Done defined (3 code checks, 1 model rubric, 1 person approval)
[ ] Boundary drawn
[ ] Control loop shaped
[ ] State, memory, context
[ ] Failure table
[ ] People in the loop
[ ] Watching and cost
[ ] Harness test plan
[ ] HARNESS.md written and read back
```

## Decisions that belong to the user

Recommend, explain, and then let the user decide. Never fill these in yourself:

- How much autonomy the workflow gets, and any ceiling on earned autonomy.
- Accepting an agent that holds the lethal trifecta (reads untrusted content, reaches private data, can send something out). The
  acceptance names who looked and when; a reason that is only "ok" is not a reason.
- Which actions need a person, and who that person is.
- Spend limits and the numbers in the cost model. Never invent a price or a context window: ask, or read the provider's
  documentation.
- Retrying a step that changes the world and is not idempotent. The default answer is no.
- Letting the run continue unattended past a failure it cannot classify.

## Hand over

When `HARNESS.md` is complete and the user has read it back, hand over to **`llm4j-workflow-guide`** to build it, and say which of
its stages each section feeds (the record's last section lists them). The workflow guide's gates then prove the build matches the
design: `weave check`, `weave audit`, trajectory tests and a capped run. When the user wants the workflow running where others
can watch it, that is **`llm4j-loom-deployer`**. If a later build or run shows the design was wrong, come back here, change the
record first, and say what changed and why.

## Things that look like a harness and are not

- A prompt that says "never do X". A model can be talked out of a prompt; a harness cannot. Make X impossible or checked.
- A loop with a stop condition and no maximum.
- A retry on a step that sends, pays or deletes, with no idempotency key.
- A judge that is the same model, with the same prompt, that wrote the answer.
- A budget with no decision for what happens when it runs out mid-step.
- An approval nobody can reach when the run is unattended.
- A green mock run. It proves the wiring, not the behaviour.
- A dashboard nobody has been shown, and incident rules nobody wrote.

## Be honest about the edges

A harness lowers risk; it does not remove it. Injection detection reads patterns, so a careful attack can pass it. Static audits
read the script, not the code of the tools and tasks it calls; review that code like any code that moves money. A model judge is
useful and noisy; measure its noise before trusting small differences. Earned autonomy is only as good as the cases it has seen.
Say so in the record when it matters, and never present a design as a guarantee.
