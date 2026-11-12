# Phase 34: decisions

Status: **D1 to D14 taken; the rest open.** Each decision gives the question, why it matters, the options and a recommendation. The recommendation is a proposal, not a choice: the owner decides one at a time and the answer is written under **Decision**. [PRINCIPLES.md](PRINCIPLES.md) holds the principles each option is checked against.
References: "33-D6" is decision D6 of [phase 33](../33-async-workflows/DECISIONS.md). Facts about the code are from the merged source and are marked (read) or (not read).

## Decision log

| | Decision | State |
|---|---|---|
| D1 | The scope and staging of this phase | **taken** (one phase, five sub-phases, a review gate after 34a) |
| D2 | Which v1 gaps are carried into this phase | **taken** (all of F1 to F11) |
| D3 | The judge budget: reserved at `start` | **taken** (paired statically; a warning for a supervise that cannot be paired) |
| D4 | The judge's facts view | **taken** (keep `Trajectory`; risk 7 closed as not followed, by choice) |
| D5 | A judge-driven demo test | **taken** (a case in starter 18's tests where the judge fires on virtual time) |
| D6 | Declared progress: the syntax and the typing | **taken** (flat typed shape, enum and mixed allowed; contextual `report {` and `progress`) |
| D7 | Declared progress: what a script may do with it | **taken** (readable; one assertion `progress.<field> changes within`; no rule on content) |
| D8 | The signal model | **taken** (a mailbox as journal entries, plus `sticky` for state-like signals; no siblings; no explicit monitor; a check for same-name waits in parallel) |
| D9 | `notify`: forms, payload, provenance | **taken** (placeholders allowed; always `unchecked` on receipt) |
| D10 | How a signal is received | **taken** (a child's `wait signal`; in a parent `wait any`, `when signal` and `wait <handle> signal`) |
| D11 | `wait any`: events and the journaled winner | **taken** (three event kinds; `remaining`; the first return journaled) |
| D12 | Waking a suspended child | **taken** (a durable wake in two steps: the launch factor and `cancel ... rewind` in 34a, the mailbox wake in 34c) |
| D13 | Limits and edge cases of signals | **taken** (limits fail loudly; no-ops for settled children and a missing parent; a clean mailbox after a rewind) |
| D14 | Security: signals as an input channel | **taken** (LA30 for reading only; no signal steers an agent; a child signals only its parent) |
| D15 | Testing: signals, a stubbed child, time | open |
| D16 | Visibility: the ecosystem for v3 | open |
| D17 | Names: `report`, `progress`, `notify`, `signal`, `any` | open |
| D18 | What stays out of this phase | open |

## D1. The scope and staging of this phase

**Why.** Three kinds of work are mixed: closing gaps, a first-time feature (progress), and two features that change the runtime (signals, `wait any`). The riskiest part of v3 is waking a suspended child (D12).

- **A. One phase, five sub-phases:** 34a closure of the gaps; 34b declared progress; 34c signals; 34d `wait any`; 34e the ecosystem and closure (the same one-scope rule as phase 33, D16).
- **B. Two phases:** the closure first and alone (a small phase), then v3.
- **C. v3 only;** the gaps stay as a list.

**Recommendation: A, with a review gate after 34a.** The closure fixes departures from decisions already taken, so it should land and be looked at before signals are built on top of the same launcher. The ecosystem moves together at the end, as in phase 33.

**Decision (owner): option A, with a review gate after 34a.** One phase with five sub-phases: 34a closure of the gaps; 34b declared progress; 34c signals; 34d `wait any`; 34e the ecosystem and closure (one ecosystem pass at the end, as in phase 33, D16).
**The gate:** the implementation session **stops after 34a**, reports what it built and what it found, and waits for the owner, who then tells it to continue to 34b or to change something. Nothing is merged in between; the gate is about the owner's review of the branch.

## D2. Which v1 gaps are carried into this phase

**Why.** The owner asked for the v2 gaps and v3, but eleven v1 gaps are in [phase 33 VERIFICATION §3](../33-async-workflows/VERIFICATION.md): F1 to F11. Four depart from accepted decisions and touch the code v3 builds on.

- **Must carry (they depart from a decision and sit in the launcher):** F1 `cancel ... rewind to` (33-D13); F2 `wait -> r` on an unfinished child binds nothing, so `{r}` reads empty, where 33-D9 asks for an error; F3 the wait timeout on wall time instead of the injected clock, plus the missing test of a wait that times out while a child still runs (33-D8); F5 a stubbed child in `weave test` (33-D17), because D15 here needs it.
- **Should carry (small and in the same files):** F4 the supervisor's worst-case cost in `weave review` and `generate` (more important once judges and signals add cost); F6 `weave why` on a child folder; F7 a runtime guard that stops a handle reaching a prompt; F8 a test that drives a sleeping child; F9 the two warnings.
- **Carry with the ecosystem (34e):** F10 the live page's JavaScript and an old console against a new process; F11 the root README, the workflow-guide skill row, the parser-parity baseline and the tutorial chapter.

**Recommendation:** carry all of F1 to F11 as sub-phase 34a (F10 and F11 in 34e), so nothing from phase 33 stays on a list.

**Decision (owner): carry all eleven (F1 to F11).** F1 to F9 are in 34a (the reviewed foundation): **F1** `cancel ... rewind to`, **F2** the status error for an unfinished `wait -> r`, **F3** the wait timeout on the injected clock and the test of a timeout while a child still runs, **F4** the supervisor's worst-case cost, **F5** the stubbed child, **F6** `weave why` on a child folder, **F7** the runtime handle guard, **F8** the sleeping-child test, **F9** the two warnings. **F10** (the live page's JavaScript; an old console against a new process) and **F11** (the root README, the workflow-guide skill row, the VS Code parser-parity baseline, a tutorial chapter of its own) are in 34e with the ecosystem. Nothing from phase 33 stays on a list. F12 to F15 are decided in D3 to D5.

## D3. The judge budget: reserved at `start` (F12, 33-D27)

**Why.** 33-D27 said the judge's budget is reserved from the parent together with the child's, so a `start` is refused if the parent cannot cover both. The build charges the judge budget but does not reserve it at `start` (read: `ChildLauncher.supervise` builds the judge budget when the `supervise` statement runs, after the `start`).

- **A. Pair statically.** `weave check` pairs a `start` with the `supervise` of the same handle in the same block; the reservation at `start` includes that `supervise`'s judge budget. A `supervise` it cannot pair (a handle supervised elsewhere) reserves at its own execution and may fail then, with a message.
- **B. Reserve at the `supervise` only** (the present behaviour, made explicit): a parent can start children it cannot afford to supervise.
- **C. Require the budget on the `start`** (`start ... budget { ... } judge { tokens: 20000 }`): explicit, but a second place to write the same number.

**Recommendation: A.** It matches what was accepted, adds no syntax, and uses the fact that the script is known.

**Decision (owner): option A, with the warning.** `weave check` pairs a `start` with the `supervise` of the same handle in the same block (matched by the handle's written form, for example `work[t.name]` inside one `for each` body); the reservation at `start` includes that `supervise`'s judge budget, so a `start` is refused when the parent cannot cover the child and its judge (33-D27, as accepted).
A `supervise` that cannot be paired (a handle started in one place and supervised elsewhere, for example through a `call`ed helper) reserves when it runs and may fail then with a message that says the child is already running and now unsupervised; the child still has its own budget and the parent's limit still holds. **`weave check` warns about a `supervise` it cannot pair**, so the author sees it before a run.

## D4. The judge's facts view (F13, risk 7 of phase 33)

**Why.** The design expected the judge's facts to come from the evaluation dataset's rendering of "what the run did". The build instead reads the child's own trace (`Trajectory`: calls by name and count, the order each was first called, checkpoints reached, refused calls) plus the counters (read: `Judge.facts`). It is live, needs no dataset, and holds no tool argument or result.

- **A. Keep `Trajectory` and record it as the decision.** The evaluation's rendering is for finished runs under test; a supervisor needs a live view.
- **B. Add the evaluation's rendering as a second source** so a judged assertion and an `expect:` line describe a run in the same words.
- **C. Replace `Trajectory` with the evaluation's rendering.**

**Recommendation: A.** It is already built, tested and facts-only; B and C add coupling to the eval module for no gain a user has asked for. Close risk 7 as "not followed, by choice".

**Decision (owner): option A.** `Trajectory` stays the judge's facts view: the child's own trace (each tool, agent or task called with its count and the order first called, the checkpoints reached, the refused calls) plus the runtime counters; no tool argument or result, and content only with `seeing content` (limited to 4000 characters). It is live, needs no dataset and is already built and tested.
Risk 7 of phase 33 is closed as **"not followed, by choice"**, with the reason recorded in the design: the evaluation's rendering describes a *finished* run, a supervisor needs a *live* one. A shared wording between a supervisor's assertion and an `expect:` line would be a deliberate later step, not added speculatively.

## D5. A judge-driven demo test (F15)

**Why.** In starter 18 "steer one that drifts" is shown with a token rule, because the judge needs time to pass; the headline of the 60-second demo uses a judge.

- **A. Add a case to starter 18's tests in which the virtual clock advances and the judge fires** (`judges: { TopicJudge: [ pass, fail ] }`, a `clock:`), so the demo's headline is tested, not just built.
- **B. Leave as is** and say so.

**Recommendation: A.** E4, running starter 18 by hand with a real key, stays the owner's.

**Decision (owner): option A.** Starter 18's tests gain a case in which the virtual clock advances and the judge fires (`judges: { TopicJudge: [ pass, fail ] }` and a `clock:`): a researcher passes the first judgement, fails the second, and the supervisor's steer is delivered, all on virtual time with no real model. If the starter's script has no judged assertion to drive, one is added and the starter's picture is redrawn (`scripts/make-starter-diagrams.sh`); the first step of the session is to read the starter's script.
A scripted judge proves the supervisor reacts correctly to a verdict, not that a real judge is good at spotting drift: that stays the owner's by-hand run with a real key (E4), and the documentation keeps saying so.

## D6. Declared progress: the syntax and the typing

**Why.** A child can say what it is doing, typed, so a parent (or a person) can read it. It must not break the 37 scripts that use `report` as a variable and the two that use `progress`.

```loom
workflow Research(topic) progress { stage: string, done: number, of: number } {
    report { stage: "searching", done: 0, of: 3 }
    ...
}
```

- **The declaration** `progress { field: type, ... }` after the parameter list, with the same types as `output_schema` (`string`, `number`, `boolean`, `enum[...]`); one flat level in v3 (no lists or nested objects).
- **The statement** `report { field: value, ... }`: recognised only as `report` followed by `{` at the start of a statement; `report` stays an ordinary word everywhere else (`-> report`), and `progress` is only a keyword directly after a workflow's `)`.
- **Storage.** Each `report` is a journal entry in the child's own journal (`#progress.<n>`, the step id's generation applying), last value wins for reading, earlier values kept as history; so it survives a restart and a rewind shows the value as of the checkpoint.
- **Bounds.** A report's fields may not exceed 2000 characters in all, and a run may make at most 1000 reports (proposed); a report beyond either bound is **dropped and recorded**, not silently ignored and not an error that stops the child.
- **Provenance:** `unchecked`. Fields are checked against the declared types at the statement (a mismatch is a failed step), and an undeclared field is a load error.

**Recommendation:** as above.

**Decision (owner): accepted as proposed, with enum and mixed shapes allowed.**
- **Declaration:** `workflow W(params) progress { field: type, ... } { ... }`; types are `string`, `number`, `boolean` and `enum[...]`, one flat level (no lists or nested objects); an enum-only shape and a mixed shape are both allowed, the choice is the workflow author's.
- **Statement:** `report { field: value, ... }`, a statement only when `report` is directly followed by `{` at the start of a statement; `report` stays an ordinary word everywhere else (`-> report`), and `progress` is a keyword only directly after a workflow's `)` and before `{`.
- **Checks:** `weave check` refuses an undeclared field, a mismatched type it can see, and a `report` in a workflow with no declaration, and warns about a workflow that declares progress but never reports.
- **Storage:** each report is an entry in the **child's own journal** (`#progress.<n>`, the step id's generation applying); the latest is what a parent reads, earlier ones stay as history, so it survives a restart and a rewind shows the value at the checkpoint.
- **Bounds (proposed numbers):** at most 2000 characters per report and 1000 reports per run; a report beyond either bound is dropped and recorded, not silently ignored and not an error that stops the child. **Provenance** is `unchecked`; a mismatch at the `report` statement fails the step.

## D7. Declared progress: what a script may do with it

**Why.** In phase 33 `supervise` conditions are a closed set of runtime counters (33-D23), which is what makes a rule checkable and free. Progress is written by the child, so it is not a counter.

- **A. Readable only:** `h.progress.stage`, `h.progress.done`, in ordinary expressions (`alt`), after a `wait any` or inside a loop; **not** a `supervise` condition.
- **B. Also a `supervise` condition**, `when progress.done == 0 for 5m { ... }`: convenient, but a rule would rest on a model-written value (principle 17).
- **C. A code assertion over progress:** `assert progress.done increases within 5m { steer "..." once }`: no model, a rule over *whether it moves*, not over its content.

**Recommendation: A in this phase, C as the one supervise-side use** (it is the progress analogue of `reaches "<checkpoint>" within`, and it never trusts the value, only that it changed). B is not proposed.

**Decision (owner): A plus C, with `changes` instead of `increases`.**
- **Readable only (A):** `h.progress.stage`, `h.progress.done` and so on, in ordinary script expressions (`alt`, a loop, after a `wait any` timeout). It is **not** a `supervise` condition on its content (B is not taken): a rule that cancels or steers never rests on a value the child wrote (principle 17).
- **One code assertion about movement (C):** `assert progress.<field> changes within <duration> { actions }`. It fires when the field has not changed within the duration, and never reads what the value says. `changes` works for every type D6 allows (the spec's earlier `increases` only fits numbers; a script that needs a number to go up adds its own check, since progress is readable).
- **Behaviour:** the clock starts when the `supervise` begins and resets each time the field changes; it counts running time, not sleeping (as `idle` and `elapsed`); `weave check` refuses the assertion when the child declares no progress or no such field; the block uses the same actions as `when` (`steer`, `wrap_up`, `cancel`) and the same ordering as the other code assertions (D30 of phase 33).

## D8. The signal model

**Why.** 33-D6 and 33-D7 were deferred: what the "monitor" is and whether it is a value or part of the handle. The code answer is already visible: `RunJournal.answer(stepId, value)` is how a person's reply reaches a suspended run, so **a signal can be an entry written into the receiver's journal.**

- **A. A named mailbox per child, in journal entries.** Parent to child: `#sig.in.<name>.<n>` written into the child's journal; child to parent: `#sig.out.<name>.<n>` written into the child's journal and read by the parent through the handle. Consumption is journaled where it happens. Addressed through the handle and the word `parent`.
- **B. An explicit monitor value** created by the parent and passed to the child (the owner's first wording, 33-D7 B): needed only for sibling-to-sibling signals.
- **C. A for this phase; B only if siblings must signal each other.**

**Recommendation: C.** One address (principle 18), no new value type, and the journal gives replay and crash safety (principle 16) for free.

**Decision (owner): option C: the mailbox of option A, and no signalling between siblings.**
- A signal is a **named entry in the receiver's journal**: parent to child, an entry in the child's journal; child to parent, an entry in the child's journal that the parent reads through the handle. Consumption is journaled where it happens. A parent addresses a child through its handle; a child addresses its starter by the fixed word `parent`. There is no registry and no discovery.
- **Siblings do not signal each other** (the owner's answer to the question standing from 33-D7). A child that needs another child to know something tells its parent, and the parent decides whether to signal the other: one extra hop, visible in the script and checkable by the audit.
- **An explicit monitor value (option B) is not taken.** The owner noted that B is the more elegant construct; without a need for sibling signals its extra generality buys nothing now, and it can be revisited if a requirement appears (for example more than two parties on one channel). The precedent to confirm when building is `RunJournal.answer` and the human-answer machinery (P7 in VERIFICATION).

**Amendment (owner, after raising a child with two waits): messages and sticky signals.** A mailbox is message passing: each message is for one receiver, once, and a child with two `wait signal` states for the same name consumes them in order (FIFO per name, each consumption journaled at its own step). That fails for a **state-like** signal ("approved") that two waits need: the first consumes it and the second hangs. So:
- **Messages stay consume-once and FIFO per name** (the default).
- **`notify h "approved" sticky`** leaves the signal **set**: any `wait signal "approved"`, now or later, returns at once **without consuming it**; the receiving code does not change, the sender says what kind of signal it is. A later `notify` of the same name replaces a sticky value. It is still a journal entry, and each wait journals what it read, so replay is unchanged. (This is the latch of 33-D6 option B.)
- **`weave check` refuses two `wait signal` statements for the same name that can be active at once** (in different branches of one `parallel`), because which branch would get a message on the first run is not deterministic; one `wait signal` per name per branch removes it.
- **No way to unset** a sticky signal in this phase (a new `notify` of the same name replaces it, and the child's script can ignore an old state); it can be added if a need appears.

## D9. `notify`: forms, payload, provenance

```loom
notify work["a"] "go"                   // parent to a child
notify work["a"] "go" "use the 2025 data"
notify work["a"] "approved" sticky          // state: stays set, not consumed (D8 amendment)
notify parent "stuck" "no reliable sources for {topic}"     // child to its starter
```

- **Name:** a plain word (letters, digits, `_`), up to 40 characters. **Payload:** optional text, up to 2000 characters (the size of a steer, `InterventionCoordinator.MAX_STEER_CHARS`).
- **Placeholders.** A steer's text is literal because it goes into an agent's context as an instruction. A signal's payload goes into a **variable**, so it can carry values with their **provenance** (`unchecked` if model-written), which `weave audit` follows. **Recommendation:** allow placeholders in a signal's payload, forbid them in nothing else; the receiving side reads it as `unchecked`.
- **A signal never causes an action by itself.** Receiving one binds a value or fires a `supervise` rule; what then happens is written in the script.

**Decision (owner): option A, placeholders allowed.**
- **Forms:** `notify <handle> "name" ["text"] [sticky]` (parent to a child) and `notify parent "name" ["text"] [sticky]` (child to its starter). A name is a plain word of up to 40 characters; a payload is optional text of up to 2000 characters (the size of a steer).
- **Placeholders** in the payload are resolved as `start` arguments are, with a provenance tag. The **receiver always reads the payload as `unchecked`**, even if the sender's values were checked, because it cannot verify what the sender did. A sticky signal's payload follows the same rules, and a later `notify` of the same name replaces the payload too.
- **A signal never causes an action by itself:** receiving one binds a value or fires a `supervise` rule; what happens next is written in the script.
- **A signal's payload can never become a steer's text** (a load error, D14): that keeps the injection path closed, since a steer's text stays literal.

## D10. How a signal is received

- **In a child:** `wait signal "go" timeout 10m -> text` (a timeout is required, as for every wait); the child is `waiting` (not `sleeping`) and its status carries `h.waits == "signal go"`.
- **In a parent, three ways:** (1) **`wait any`** returns an event of kind `signal` (D11); (2) a **`supervise` rule** `when signal "stuck" { steer "..." once }` (declarative, matches the simplification the owner asked for in phase 33; **the payload is not available to the rule's actions**, which stay fixed text); (3) `wait h signal "stuck" timeout T -> text` for one named signal from one child.

**Recommendation:** all three, because they serve different scripts: (2) for a supervisor that reacts, (1) for one that collects, (3) for a simple handshake. Each is small once the mailbox exists.

**Decision (owner): all three ways to receive in a parent, plus the child's wait.**
- **In a child:** `wait signal "go" timeout T -> text`; a timeout is required; while it waits the child is `waiting` with a readable fact `h.waits` naming what it waits for (for example "signal go"); a message is consumed, a sticky signal is read and stays set (and a set sticky signal returns at once).
- **In a parent:** (1) **`wait any`** returns an event of kind `signal` (D11); (2) a **`supervise` rule** `when signal "stuck" { actions }`, whose actions stay fixed text and **cannot use the payload**; (3) **`wait <handle> signal "name" timeout T -> text`** for one named signal from one child.
- **A `when signal` rule fires once per arrival** (a signal is an event, not a condition that becomes true); `once` limits it to a single firing; for a sticky signal, setting it counts as an arrival and replacing it counts as another. A `when signal` rule **consumes** a message as a `wait` does, and a sticky signal is read by it and by a wait alike without being consumed, so a rule and a `wait any` never take the same message twice.

## D11. `wait any`: events and the journaled winner

```loom
wait any work timeout 2m -> event
alt (event.kind == "signal") { ... event.text ... }
alt (event.kind == "finished") { set results[event.name] = event.result }
```

- **An event** is a keyed value: `kind` (`finished`, `signal`, `timeout`), `name` (the handle's key), and for `finished` the `status` and `result` (readable only when completed, as 33-D9), for `signal` the signal's `name` and `text`.
- **Which one wins** when several are ready is not deterministic across runs, so **the first return is journaled** (the winner, and the consumption cursor of every mailbox it read) in the same way `wait` journals its outcome (`ChildLauncher.await`), and a replay returns the recorded event.
- **Order when several are ready at once:** a fixed rule: signals before finished, then the order the handles were started; recorded either way.
- **Unconsumed signals stay pending** for the next `wait any` or a `wait signal`; a **finished** child is reported once.
- **A `timeout` event** returns with the children still running (as `wait`, 33-D8), and is required on every `wait any`.
- **Limit:** at most `max_children` handles in one `wait any`.

**Recommendation:** as above.

**Decision (owner): accepted as proposed, with `event.remaining`.**
- **An event** is a keyed value: `kind` (`finished`, `signal` or `timeout`), `name` (the handle's key), for `finished` the `status` and the `result` (readable only when completed, as 33-D9), for `signal` the signal's own `name` and its `text` (always `unchecked`), and **`remaining`**: the number of children not yet finished, a count the runtime keeps, so a loop can end with `loop until (event.remaining == 0)`.
- **The first return is journaled**: one entry holds the event and the position of every mailbox it read, and a replay returns the recorded event and never decides again (the pattern of `wait`, which journals its outcome).
- **Order when several are ready at once:** signals before finished children, then the order the handles were started; recorded either way. A finished child is reported **once**; a signal stays pending until consumed, so the next `wait any` or `wait signal` sees what the last one did not take; a sticky signal is read, not consumed.
- A **timeout is required** and a `timeout` event returns with the children still running (as `wait`, 33-D8); at most `max_children` handles in one `wait any`; a loop reading events is bounded by its own `max`.

## D12. Waking a suspended child

**Why.** A child that is waiting for a signal has **no thread**: `runChild` catches `RunSuspended` and records `WAITING`, and nothing in the code read resumes it in the same process (read: `ChildHandle.resume()` is called only from `InterventionCoordinator`, for a run-control pause, a different thing). For a signal to reach a waiting child the parent must be able to run it again.

- **A. A resume path in `ChildLauncher`:** a `notify` to a `waiting` child writes the entry (D8) and then **re-runs the child over its journal** on the scheduler, as `weave resume` does for a run, with a single-flight rule (one run of a child at a time, a state machine on the handle: `waiting` to `running` to `waiting` or settled).
- **B. Only when the parent next waits or replays:** the entry is written and the child resumes at the parent's next `wait`. Simpler, but a `notify` to a waiting child has no effect until then, which defeats the point.
- **C. A, and also a timer to wake a sleeping child at its wake time in-process** (today a sleeping child is only resumed by the parent's replay).

**Recommendation: A**, and C is **not** proposed here: a sleeping child's wake-by-timer is a separate gap (read: not present today) and the trigger store and `weave daemon` are the designed route for it; this phase must not widen it. State this plainly in the documentation.

**Decision (owner): option A, a durable wake, built in two steps ("durability is important").** Option D (park the child's thread) was weighed and not taken: it would hold a thread per waiting child and stop the process from exiting, against the principle that a started workflow is a run and not a thread.
**What was read to decide (E0):** the wake pattern already exists for top-level runs: `LoomHost.answer` writes the answer, then `resume` **claims** the run (a second claim is refused), builds a **new executor over the same journal** and re-runs the workflow with the run's recorded inputs; `RemoteTasks.taskChanged` is an **idempotent event wake** (a repeat or stale event does nothing; a wake happens only when the run is suspended for that task). A replayed `start` already does a re-run: `startLocked` builds a new child executor over the child's journal (`deriveChild`) and runs the workflow, which replays what was recorded. `RunSuspended` has the reasons `HUMAN`, `RATE_LIMIT`, `BUDGET_WINDOW`, `SLEEP`, `REMOTE` and `OPERATOR`; a signal wait needs its own.
- **Step 1, in 34a (reviewed at the gate):** factor the child-launch code out of `startLocked` into one method used by `start` and by a wake; add a **launcher-level monitor** (a condition variable) so the parent's `wait` can wait on child state changes, arrivals and timeouts, not only on a child's task ending; build **`cancel ... rewind to`** (F1) on that, reusing `Rewinder` (the engine behind `weave rewind`) where a child is suspended. This exercises re-running a child with no signals, so the risky machinery is proven before signals use it. *(Correction: DESIGN said F1 needs the re-run path; that is plausible and not yet verified end to end, since a running child may rewind through its own `on_stopped` machinery.)*
- **Step 2, in 34c:** the mailbox (D8), a new `RunSuspended` reason for a signal wait (about ten files mention the enum, mostly mechanical), and the wake.
- **Protocol rules:** (1) the wake is **idempotent**, like `taskChanged`: a repeat or stale event does nothing; (2) **single flight**: a wake happens only when the child is `waiting` for that name, claimed under one lock; a `notify` to a running child only writes its entry; (3) **no lost wake-up**: the entry is written first, and after the child records `WAITING` it re-checks its mailbox and wakes itself if an entry is there, so one side always sees it; (4) a signal wait's **timeout** is enforced by the launcher's tick waking the child at its deadline, as `start` deadlines are enforced today; (5) **cancel wins**, through the marker the child already checks before it runs; (6) a child waiting on a signal does **not** make the parent suspend when every child is paused (nothing will wake it but the parent's own timeout): the parent parks until its `wait` times out; (7) the old `Trajectory` is **carried into the new executor** for an in-process wake (after a restart that history is rebuilt only as far as replay allows: not yet read); (8) a `wait signal` in a run with no parent fails with a message.
- **Not in this phase:** a timer that wakes a *sleeping* child in-process; a sleeping child is still resumed by a replay or `weave daemon`.
- **Still to confirm by reading before trusting it (P8 to P10 in VERIFICATION):** that a re-run over a child suspended mid-journal behaves as a top-level resume does; that spend is restored for a re-derived child executor; how trace events behave during replay, for the trajectory.

## D13. Limits and edge cases of signals

- **Pending signals per child:** at most 16 per name and 64 in all (proposed); a `notify` beyond the limit **fails** with a message naming the limit (not dropped silently).
- **`notify` to a settled child:** a **recorded no-op** (not an error), as a cancel of a settled child is (33-D13).
- **`notify parent` in a workflow that was not started by a parent** (run directly): the script cannot know at load. **Recommendation:** a **recorded no-op at run time**, and a `weave check` warning when a workflow contains `notify parent` and is also the script's entry workflow.
- **`wait signal` with a name nobody sends:** it times out like any wait.
- **Signals and a rewind:** the mailbox follows the step id's generation, so a rewind does not replay an old generation's signals into the new one.
- **Parent ends:** the child is cancelled (33-D10), so unread signals are moot; they stay in the journal as history.

**Recommendation:** as above.

**Decision (owner): accepted as proposed, all seven points.**
1. **Limits:** at most 16 pending messages per name and 64 in all, per child; a `notify` beyond a limit **fails** naming the limit; a sticky signal counts as one per name (a new one replaces it); a name is a plain word of up to 40 characters; a payload up to 2000 characters.
2. **A `notify` to a settled child** is a **recorded no-op**, not an error (as cancelling a settled child, 33-D13).
3. **`notify parent` in a workflow with no parent** is a **recorded no-op at run time**, with a `weave check` warning when a workflow containing it is also the script's entry workflow. (A `wait signal` in a run with no parent **fails** with a message, D12 rule 8, because it would wait forever; sending to nobody is harmless, waiting for nobody is not.)
4. **A `wait signal` for a name nobody sends** times out like any wait; a timeout is required.
5. **A rewind:** mailbox entries carry the child's generation (the `~N` suffix of step ids); a signal sent to an earlier generation is not delivered to a rewound one; the old generation's entries stay in the journal as history; **the new generation starts with an empty mailbox**, so a sticky signal sent before the rewind must be sent again (a deliberate simplification, stated in the documentation).
6. **The parent ends:** the child is cancelled (33-D10), so unread signals are moot and stay in the journal as history.
7. **A `notify` to a child that is cancelled but not yet settled:** the entry is written (the child's journal is the record) and never delivered, because the cancel marker wins (D12 rule 5).

## D14. Security: signals as an input channel

- **An input channel in the audit.** A workflow that reads a signal (`wait signal`, `when signal`, `wait any` with a signal) can have text from another run enter its variables: a new audit rule (proposed **LA30**, after LA28 and LA29), and the oracle gets canary cases in both directions.
- **Provenance:** a signal's text is `unchecked` on the receiving side; the oracle must show that a parent's model-written text sent to a child is treated as untrusted there, and the reverse.
- **No signal steers an agent:** a signal's payload cannot become a steer's text (a steer's text stays literal); a rule fires on the *arrival* of a named signal, not on its content.
- **Authority only narrows:** a child cannot signal anything but its own parent; a parent can signal only handles it started (checked statically and at run time).
- Declared progress is read-only for the parent and carries `unchecked`; it adds no channel into the child.

**Recommendation:** as above, written before the runtime (the rule and the oracle cases first).

**Decision (owner): accepted as proposed, with LA30 for reading only.**
1. **LA30 (proposed number, after LA28 and LA29):** a workflow that **reads** a signal (`wait signal`, `when signal`, or a `wait any` that can return one) gets a finding that names the signal and the side that can send it, in the shape of LA18 for `steerable` agents. **There is no rule for sending** (the parent already gets the child's result, so the channel is not new); a rule on sending can be added if a case appears.
2. **Provenance:** a signal's payload is `unchecked` on the receiving side in both directions (D9); the provenance oracle gets canary cases for a parent's model-written text sent to a child and for a child's text sent to the parent.
3. **No signal steers an agent:** routing a received payload into a `steer` is a **load error**; a `when signal` rule fires on the arrival of a named signal, never on its content.
4. **Who may signal whom:** a child only its parent (the word `parent`; there is no way to name another run); a parent only handles it started, checked at load and at run time. This makes "no siblings" (D8) enforceable, not a convention.
5. **A signal carries no authority:** it cannot grant a tool, widen a budget or lift a limit.
6. **Declared progress** is read-only for the parent and `unchecked`; it adds no channel into the child and needs no rule of its own beyond what the audit already lists.
7. **Order of work:** the audit rule and the oracle cases are written **before the runtime**; each guard (G22, G23) is broken on purpose once. The rule goes in `docs/security/weave-audit.md` and the OWASP mapping.

## D15. Testing: signals, a stubbed child, time

- **`weave test` gains `signals:`**, scripting a signal's arrival on the virtual clock (`signals: { work_a: [ { at: 2m, name: stuck, text: "no sources" } ] }`), and **`expect:`** keys for signals sent and received; the stubbed child (F5) scripts `progress` and `signal` steps in the same style as `remotes:`: `children: { Research: [ { state: working, progress: { stage: reading, done: 1, of: 3 }, after: 1m }, { signal: stuck, text: "..." }, { state: completed, result: "..." } ] }`.
- **Replay and kill-point:** the existing kill-point test is extended to the new journal writes (`#sig.*`, `#progress.*`, the `wait any` entry): kill before every one, resume, and check each signal is received **exactly once** and the winner of a `wait any` is the same.
- **The controlled scheduler** (`ControlledChildScheduler`, read: children run one at a time when waited for) is extended so a woken child re-runs deterministically.

**Recommendation:** as above.

**Decision:** open.

## D16. Visibility: the ecosystem for v3

The whole ecosystem moves together (the rule of phase 33, D16): graph kinds (`child_notify`, `child_signal_wait`, `child_wait_any`, following the `child_*` naming the owner settled), `weave explain` sentences, `weave timeline` and `why` (signals and progress as events of the child's run), Fabric and the live page (pending signal count and the latest progress in the Children section; an additive attribute, with the old-console compatibility test that phase 33 did not do, F10), VS Code (grammar, snippets, hover, the parity baseline, `CHANGELOG`), the compatibility kit, starter 18 extended (a child that asks for help and a supervisor that reacts) or a starter 19, the tutorial (a chapter of its own this time, F11), `LOOM_GUIDE.md` and both README tables, `LOOM_PROMPT.md`, the wiki, `llms.txt`, release notes, `docs/API_COMPATIBILITY.md`, the audit page and the OWASP map, the workflow-guide skill (F11), `llm4j-workflow-patterns` and `llm4j-run-debugging`.

**Recommendation:** as listed, nothing held back; the closure of F10 and F11 happens here.

**Decision:** open.

## D17. Names: `report`, `progress`, `notify`, `signal`, `any`

**Read:** `report` appears as a word in 37 repository scripts, almost always as a variable (`-> report`); `progress` in two (starter 16: `set progress = ...`, `-> progress`); `notify`, `signal` and `event` once each; `any` in 17 (mostly prose); `parent` in 5.

- **`report`** is a statement only as `report {` at the start of a statement; **`progress`** is a keyword only directly after a workflow's `)` and before `{`; **`notify`** only as `notify <handle|parent> "name"`; **`signal`** only after `wait` or inside `supervise` after `when`; **`any`** only after `wait`; **`parent`** only as the first operand of `notify`. All contextual, as `start` and `wait` were (33-D19); `RepositoryScriptsTest` guards every script, and a new test names the cases that must stay ordinary words (`-> report`, `set progress`).
- **Alternative spellings** if the owner prefers: `status` for `report`; `signal` for `notify`; `send` for `notify`.

**Recommendation:** `report`, `progress`, `notify`, `signal`, `any`, contextual.

**Decision:** open.

## D18. What stays out of this phase

- Signalling between siblings and an explicit monitor value (D8 option B).
- Waking a *sleeping* child by a timer in-process (D12 option C); the trigger store and `weave daemon` remain the route.
- `detach` (not wanted), children on other machines, a scheduler or queue of children, a visual builder, collections and a type system (their own phase), a `remote` agent as a child, and `weave generate` for supervisors.
- Progress as a `supervise` condition on its content (D7 option B).
- Streaming progress to Fabric as a time series (a refinement of the Children section).

**Decision:** open.
