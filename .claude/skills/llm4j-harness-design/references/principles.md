# Harness design: the practices

Each practice says what to do, why, and how to check it. They apply to any autonomous workflow; `loom-mapping.md` says how each
is carried in Loom. Sections follow the stages of the skill.

---

## §1. Frame the job and choose the autonomy shape

**Know the five facts before designing anything.** What goes in, what comes out, how long a run lasts (seconds, hours, days), who
is around while it runs, and what cannot be undone. Most later decisions follow from these.

**Pick the least autonomy that works.** The shapes, from most predictable to least:

| Shape | The model decides | Use when | Costs |
|---|---|---|---|
| Fixed pipeline | the content of each step | the steps are known in advance | nothing extra |
| Router | which of a few declared paths | inputs fall into a small number of kinds | a classification that can be wrong; test it |
| Planner and workers | a plan, from a declared menu of agents and steps | the steps vary per request but come from a known set | a plan to validate; bound its size |
| Open loop | its next action, until a stop condition | the path cannot be known in advance (research, coding, diagnosis) | the hardest to bound, test and pay for |
| Earned autonomy | a decision a person used to make | a repeated decision with a history to learn from | a ledger, a policy, and time |

Mix shapes: an open loop inside a fixed pipeline is common and good. Say for each part which shape it is and why the one below it
was not enough.

**Make dynamic work a plan, not a free hand.** When a model decides what to do, have it return a typed plan, validate it, and let
the harness carry it out. The plan may only name agents, tools and steps that are already declared. It chooses from the menu; it
cannot add to it. Journal the plan, so a resume runs the same plan and does not ask again.

**Long runs are a different job.** A run that lasts longer than one context window, one process, or one working day needs
everything in §5 and §6, and usually a person reachable from a phone (§8). Decide this now, not after the first overnight failure.

Check: the user can say the shape of each part in one sentence, and why a simpler shape would not do.

---

## §2. Define done, and who checks it

**Every exit is named.** A run ends in one of a few ways: succeeded, gave up after its bounds, stopped by a person, halted because a
stage could not go on, or failed. Each has a criterion and a result the caller can read. "It finished" is not a criterion.

**Checkable things are checked by code.** Lengths, counts, formats, required fields, forbidden text, totals that must add up, a test
suite that must pass, a file that must exist. A model is asked only for what code cannot judge (tone, relevance, correctness of an
argument), and that check is written as a rubric.

**Separate the doer from the checker.** The agent that did the work never grades itself alone. Use code first, then a different
model or a differently prompted judge, then a person. Measure the judge's noise on known cases before trusting small differences.

**Fail closed.** A check that did not run, an outcome nobody recognises, an empty answer, a parse failure: all mean *not done*.
Unjudged is never a pass.

**Write down what "good enough to ship" means** before the first run: which checks must pass, which may be waived, and who may
waive them.

Check: for each exit, the record names the criterion and who checks it, and no criterion depends only on the doer's opinion.

---

## §3. Draw the boundary: surfaces and action space

**List the surfaces.** For each agent: what it may read, what it may change, what it may send outside the run. Then mark what is
**locked** (no agent may change it, ever) and what is **editable** (the work product).

Locked, always: the objective, the evaluator and its tests, the budget, the autonomy level and its ledger, the harness's own code and
configuration, the audit log and the journal. An agent that can edit any of these will, eventually, because it is the cheapest way
to pass.

**Give each tool the least reach that works.** Read-only before read-write. A list of allowed paths, hosts, methods or programs
before a general tool. A read-only database user. No interpreters in a shell tool. Declare each tool's reach so a static audit and
a watched run can agree on it.

**Keep tools away from roles that do not need them.** An agent with no tools cannot be hijacked into acting. Writers, judges and
summarizers rarely need tools.

**Split the lethal trifecta.** An agent that reads untrusted content (the web, email, user uploads, tool output), reaches private
data, and can send something out can be turned against its user by text it reads. Split it: one agent reads and reports, another
acts, with an approval or a code check between them. If the user keeps it whole, they accept it in writing with a reason that
names who looked and when.

**Pass data, not instructions, across the boundary.** Text from a model or a user reaches a deterministic step only as an argument
value, never as the name of the step or the code to run. Validate it against a schema at every hand-off between agents.

**Secrets never enter the context.** Keys come from the environment or a secret store and are bound to the host they are for. They
are never in a prompt, a script, a log line or the chat.

Check: the surface table is complete; every way out of the run has a person or a code check in front of it; no agent can change a
locked surface.

---

## §4. Shape the control loop

**Every loop has a maximum and an outcome when it is reached.** A stop condition alone is not enough: a model that never satisfies
it spins and bills. The outcome on exhaustion is designed (keep the best attempt, ask a person, halt), not left to an error.

**Test the stop condition before the first round.** A condition that is already true at the start means the loop never runs. A
condition the body can never make true means it always exhausts.

**Bound at three levels.** The provider's own spending limit is the outer net. The run's budget sits below it. Each agent, and each
expensive stage, has its own budget below that. Set stage limits from measured costs with margin, not from guesses.

**Know where a budget bites.** Most budgets are checked before the next model call, so they do not stop a step that is already
changing the world. If something must happen even when the money runs out, do it early or make it a deterministic step that does
not need the model.

**Watch the agent's behaviour, not its opinion.** Rules that count what an agent did (the same tool call three times, a tool failing
repeatedly, the context nearly full, too many steps) and respond (nudge it, make it wrap up, cancel and keep the partial result) are
more reliable than asking the model whether it is stuck.

**Bound rewinds too.** Going back to an earlier point when a later check fails is powerful; it needs a maximum per rewind and a cap
across the run, and it carries the reason forward so the next attempt is different. A rewind goes back, never forward, and cannot
leave a parallel branch or a per-item loop body: put the checkpoint and the check that sends the run back outside the fan-out
(§15).

**Limits that lift at a known time are pauses, not failures.** A per-minute rate limit, a daily quota or a budget that refills
should suspend the run and resume it, holding no thread, with a maximum wait and a maximum number of resumes.

Check: list every loop, rewind and budget with its maximum and its outcome; the user agreed to each outcome.

---

## §5. State and durability

**Journal every step that costs money or touches the world.** A resumed run replays recorded steps and continues from the first one
that has not happened. No model is called twice for the same step.

**Make effects idempotent.** A step that changes the world carries an idempotency key that the receiving system honours, so running
it again does not do it again.

**Mark the crash window.** Write "about to do" before an effect and "done" after. If the process dies in between, a resumed run knows
the outcome is unknown. For an idempotent effect it repeats with the same key. For one that is not idempotent it stops and asks a
person to check, then resumes on their word. It never guesses.

**Keep state in the harness, not in the conversation.** Plans, decisions, progress and partial results are variables and records the
harness owns. For a run longer than one context window, keep a progress artifact (what is done, what is next, what was decided and
why) that a fresh agent can read to carry on.

**Name the points worth returning to.** Checkpoints let a late check send the run back to where the mistake was made, keeping what was
learned, instead of starting over. A rewind past an effect that already happened needs a decision: ask first by default.

**Wait for people without holding a thread.** A step that needs a person suspends the run and records the question. The answer,
whenever it comes, is written to the journal and the run is resumed, on any machine.

Check: walk a crash at three points (before the first effect, between "about to" and "done" of an effect, after the last effect) and
say what the resumed run does at each.

---

## §6. Context and memory

**The harness owns what each step sees.** Assemble the context for each call deliberately: the task, the relevant facts and decisions,
the tools. Many runtimes (Loom among them) give each agent the run's shared transcript by default; that is fine for a short run, but it
grows with every step. Bound it per agent, and for long runs hand agents selected facts rather than everything that happened.

**Plan for the window of every agent that loops on tools.** Each step adds to the prompt, and the whole prompt is sent again. Decide
the compaction in order of cost: trim long tool results, drop the oldest steps, and only then summarize (a model call that costs money
and can lose detail). Know each model's window from the provider, never from a guess.

**Keep stable instructions stable.** Rules that every agent needs go in one shared prompt with no dates or run ids in it, so it is
the same on every call and can be cached.

**Separate memory that informs from memory that decides.** Retrieved memories are context, ranked and possibly wrong. Decisions,
approvals and the ledger are records, exact and locked. Do not let an agent rewrite either kind about itself.

**Let stale memory be shadowed, not silently kept.** Long-lived memory needs a way to mark a fact as outdated or wrong, so it stops
reaching the context.

Check: for each agent, the record says what it sees, how big that can get, and what happens when it gets too big.

---

## §7. Failure and recovery

**Classify failures; each kind gets a different answer.**

| Kind | Example | Usual decision |
|---|---|---|
| Transient | timeout, rate limit, 5xx | retry with backoff, bounded |
| Limit with a known reset | quota, refilling budget | suspend and resume |
| Bad output | malformed JSON, schema mismatch, empty answer | re-ask with the error, bounded; then another path |
| Permanent | 4xx, a rule refused it, missing input | another path, a person, or halt |
| Unknown outcome of an effect | crash mid-payment | check, then resume on a person's word |
| Wrong but well-formed | a later check finds the work was wrong | rewind to the checkpoint, carrying the reason |
| Cannot go on | a stage's precondition is broken | halt with a reason; later stages must not run on broken input |

**Retry only what is safe to repeat.** Reading, idempotent effects, and steps that failed before doing anything. Never retry blindly a
step that sends, pays or deletes.

**A failure carries its reason forward.** The next attempt, the other path, or the person sees what went wrong. A retry with the same
input is a retry of the same mistake.

**Halting is not failing.** When a stage cannot go on, stop the whole run with a reason, so nothing downstream works on something
broken, nothing retries it, and a resume halts again.

**Name the failed step.** A failed run says which step, which kind, and why, in the journal and the audit log.

Check: the failure table has a row for every step that can fail and a decision for every kind that can happen there.

---

## §8. People in the loop

**Put a person in front of every irreversible way out**, until autonomy has been earned. Keep approvals few and meaningful: an
approval nobody reads is worse than none, because it looks like a control.

**Make approvals for irreversible actions hard to give by accident.** The person sees what will happen, in full. For a routine,
reversible step a plain yes or no is enough. For a payment, a deletion or a message to many people, ask for something specific that
shows they read it: the amount, the record id, or a code sent with the question.

**Plan for the unattended run.** An approval needs a way to reach the person (a chat, a phone, an application), an allowlist of who may
answer, and a decision for when nobody answers in time.

**Decide what a stop means, per step.** When a person stops an agent: keep the partial result, discard it, retry, ask, or send the run
back to a checkpoint with the person's reason. With no decision, the default should be to ask, and to suspend if nobody can be asked.

**Steering corrects reasoning; it never rewrites the workflow.** Mark on purpose which agents may be steered, and by whom. Each one is
an input channel and is guarded like one.

**Let autonomy be earned, per kind of case, under a ceiling.** The agent proposes, a person decides, a ledger of both moves the agent
up a ladder whose rules the user wrote: how many cases, how long, how often it must agree, which mistakes are dangerous, what makes it
slip back. Keep sampling decisions blind after promotion. The agent cannot see or change its own level. A ladder nobody has run is not
trust.

**Keep a stop for everything.** One action that freezes every autonomous decision at once.

Check: every irreversible action has an approver or a written decision; who may steer, stop and approve is named, with how they reach
the run.

---

## §9. Security

**Treat every input the agent reads as possibly hostile**: user text, web pages, email, files, tool results, the output of another
agent. Defence is architectural (§3): what an agent can do after it is fooled matters more than whether it is fooled.

**Mask or block personal data** before it reaches a hosted model, where the data or the law requires it.

**Review third-party tools and MCP servers as your own code.** Pin them, scope their credentials, and do not register them where any
agent could draw on them.

**Keep the audit trail.** An append-only record of what ran, what was approved, by whom, and what it cost. When something goes wrong
it is the only account of what happened.

**Run the static audit in CI** and fail the build on a high finding; explain every finding you keep.

Check: hostile-input cases exist for every agent that reads untrusted content, and they assert on what was *not* done (no tool call,
no send), not only on the text.

---

## §10. Watching and cost

**Write a cost model before the first real run**: calls per run, tokens per call, price per model, and the expected spend per run and
per day. Compare every real run against it; a run at more than about twice the model is a finding.

**Trace what the agents think and do**, live and afterwards: steps, tools, arguments, results, context size, compactions, cost.

**Write down what counts as trouble.** Looping, a tool failing repeatedly, the context nearly full, spend above the model, an approval
waiting too long, a trifecta in use, an injection pattern seen. Each with a threshold and who is told.

**Someone is shown the page.** A dashboard nobody has looked at is not watching.

Check: the cost model has real numbers from the user or the provider; the trouble list has thresholds and owners.

---

## §11. Gaming and drift

**Assume the agent will optimize against the harness.** Given a metric, it will find the cheapest way to move it: weaken a test,
special-case the examples, edit the rubric, claim done, loop until the judge relents.

- Lock the evaluator and the tests (§3). Keep a held-out set the agent never sees.
- Prefer behaviour checks (tools used, path taken, effects done) to text checks it can imitate.
- Check novelty: a change that repeats a rejected idea, or an output identical to a previous attempt, is not progress.
- Revalidate any change to the harness with the old and the new evaluator; a harness change that only makes the numbers better is
  suspect.

**Watch for drift.** Models, prompts and providers change. Keep the golden cases and run them on a schedule; replay past cases under
any changed prompt or model before it goes live.

Check: the record says which surfaces are locked against the agent, and where the held-out cases live.

---

## §12. Test the harness itself

Agents can each pass their tests while the harness is wrong: a branch never taken, a budget that does not stop, a resume that pays
twice. Test the harness with a scripted model first (free), then under a cap.

One fault test per design decision:

| Inject | Expect (from the design) |
|---|---|
| Kill the process before, during and after an effect | replay without repeat; unknown outcome stops and asks |
| A tool fails N times | the failure decision runs; the supervise rule fires at its threshold |
| The model returns malformed output | re-ask with the error, bounded; then the other path |
| The budget runs out mid-stage | the designed outcome, not a cascade of errors |
| A rate limit with a reset time | suspend and resume |
| The loop never satisfies its condition | exhaustion outcome after the maximum |
| A hostile input in every untrusted source | no forbidden tool call, no send, no leaked secret |
| A person stops each stoppable step | the stop decision for that step |
| Nobody answers an approval | the timeout decision |
| The agent tries to edit a locked surface | refused, and recorded |

**Pin the expected path** and the number of calls on known inputs. A refactor that changes either should be a conscious decision.

Check: the record's test plan has one row per decision in §4 to §8.

---

## §13. Changing a harness safely

- Version the harness with the workflow; a harness change is a code change and is reviewed like one.
- Try a change against real history without side effects: fork a past run with effects simulated, or replay past cases.
- When an agent's prompt or model changes, it inherits only the trust its old record supports for the new version.
- Keep the old evaluator running alongside the new one until they agree on known cases.

---

## §14. Unattended and long-running work

- Run on a machine you control under a scheduler or daemon, not a laptop session.
- Make every wait a suspension with a maximum (people, rate limits, refilling budgets, scheduled sleeps).
- Keep a progress artifact a fresh agent or a person can read in a minute: done, next, decided, blocked.
- Reach a person from their phone for questions and approvals, from an allowlist, in a private channel.
- Detect a stalled run (no step for longer than expected) as trouble, not as quiet.
- Keep one action that freezes autonomy for everything at once.
- A fixed wait (polling a job, a cooldown) is a durable sleep that holds no thread, inside a loop that has a maximum. Something must
  wake the run (a daemon, a system trigger); a sleep is not a schedule.

---

## §15. Concurrency and composition

Running work side by side, and splitting a workflow into pieces, change what the rest of the design must say.

**Decide what one branch's failure means for the others.** When several branches run at once (independent steps in parallel, the same
question to several agents, one item per branch), say for each fan-out: if one branch fails or is stopped, do the others finish, get
stopped, or have their results thrown away? And does the step after the fan-out run on partial results, or not at all?

**Each branch's effects are separate effects.** Every branch that changes the world needs its own idempotency key, and a crash must be
safe to resume with some branches done and some not.

**Keep checks and rewinds outside the fan-out.** A rewind cannot leave a parallel branch or a per-item loop body, and a branch cannot
hand back a value for the whole workflow. Put the checkpoint before the fan-out and the check that judges the combined result after
it. Inside a branch, use retries and failure handlers.

**Budgets and pauses see the whole fan-out.** Parallel branches spend from the same budget at the same time, so a fan-out can exhaust a
budget faster than a sequence would. When a limit or a sleep pauses the run, branches that can still work finish first, then the run
pauses once.

**Bound the width.** A fan-out over a list a model produced is as wide as the model made it. Cap the list's size where the plan is
checked (§1), and think about rate limits before running many branches against one provider.

**Combine with a schema.** The results of several agents are merged for the next step; each agent's answer is checked against its
schema before it is merged, so one malformed answer does not poison the combined result.

**Split long workflows into named pieces.** A sub-workflow that runs in its own scope and hands back one value is easier to read, test
and reuse. Budgets and run-wide settings belong to the entry point, not to the pieces.

**Wrap the sensitive stretch, not the whole run.** A guard around a block of steps (for example one that checks for personal data,
with a handler for a violation) is clearer than a rule on every agent, when only that stretch touches the data.

**Mark the points worth seeing.** Recording a named state at key points (inputs received, plan made, results merged) makes a trace
readable later; a short note on a decision path tells the reader why the run went that way.

Check: every fan-out in the record names its width bound, its failure decision, and where its checkpoint and its combined check sit.
