# Design Document

## Overview

Today `HarnessExecutor.executeWorkflow` runs a workflow's statements through `runBlock`, which names each statement
`<parent>/<key><index>` (`Main/s2`, `Main/s3/r2.0`, `Main/s1/a0`) and, for each model call, human answer, effect and
approval, looks the step id up in the run journal first. A resumed run is the same code run again: every step already in the
journal returns its stored value, the variables are rebuilt as a side effect, and execution continues at the first step that
has no entry.

That is already time travel in one direction. This design adds the other direction with three small ideas:

1. **Generations in step ids.** After a boundary, steps are named with a generation suffix. A new generation therefore finds no journal entries and runs; the old ones stay.
2. **A boundary list in the journal**, read first on every start, so a resume knows which generation is current at each boundary and doesn't re-run discarded ones.
3. **An identity rule for effects and human answers** that ignores the generation, so those are found again rather than repeated.

A fork is a journal copy (or an overlay) with the same machinery on top. Nothing is deleted anywhere, so there is no
new method on `RunJournal`, and the JDBC, file and memory journals all work as they are.

```
 statements:   s0   s1   [checkpoint A]  s3   s4   s5  ─ rewind to A when … ─▶
 generation 1: s0   s1        A          s3   s4   s5     (journal: Main/s3, Main/s4, Main/s5 …)
 generation 2:                 A          s3~2 s4~2 s5~2  (journal: Main/s3~2 …)   old entries stay
 ids under s3~2 (Main/s3~2/a0 …) inherit the suffix, so nothing collides
```

## 1. Language

### 1.1 Grammar

```loom
workflow Report(topic) {
    checkpoint collected { feedback: "none" }
    delegate "Collect sources on {topic}. Reviewer feedback so far: {feedback}" to Collector -> data
    delegate "Analyse {data}" to Analyst -> analysis
    delegate "Write the report from {analysis}" to Writer -> draft
    delegate "Review {draft}" to Reviewer -> review expecting { score: number, notes: string }

    rewind to collected when review.score < 7  max 2
        effects: hold
        with { feedback: "{review.notes}" }
        on_exhausted { human_prompt "Three drafts failed review. Publish the last one anyway? (yes/no)" -> go }
    alt (go == "no") { handoff "Abandon" to Archivist }
    delegate "Publish {draft}" to Publisher
}
```

`checkpoint start` exists in every workflow without being written. A rewind may also sit in an `on_failure`:

```loom
delegate "Fetch the price list" to Fetcher -> prices
    on_failure { rewind to start max 1 with { hint: "{_error}" } }
```

### 1.2 Parsing

`checkpoint` and `rewind` are **contextual**: the parser treats them as statement keywords only when they start a statement
and are followed by an identifier (`checkpoint Name`) or `to` (`rewind to`). Elsewhere they are identifiers, so existing
scripts that use them as names keep working (R9.1). `with`, `max`, `effects`, `on_exhausted`, `on_blocked` are read inside the
statement in the way `loop … max … on_exhausted` is read today. New AST nodes: `CheckpointStmt(name, initial)` and
`RewindStmt(target, condition, max, effects, carried, onExhausted, onBlocked)`; `Statement` is a plain interface, so nothing
else changes.

### 1.3 Load-time checks (`ScriptValidator`)

Each is a problem with a line (R1.3, R2.7, R2.9):

- duplicate checkpoint name in a workflow; unknown target; target later than the `rewind`; target in a sibling or nested block; target outside the branch that contains the rewind (a `parallel` branch, a `for each` body);
- `max` missing or not positive; `effects:` not one of the three; `with` entries that are not `name: value`;
- the condition is one comparison or a bare boolean variable, as the existing evaluator accepts (no `and`/`or`); anything else is a load error pointing at that limit;
- the **effect reach** of the region between the checkpoint and the rewind: the validator already knows which agents a statement uses and which tools each agent has; it takes the union of their tools and classifies each by the `ToolKind`/`Effectful` information the tool factory has (a built-in pure tool: none; a generic tool: its `isEffect` possibilities; a tool of a class it can't classify: unknown). Unknown and effectful, with no stated policy, is a warning; with `effects: redo` and not approved or `unattended`, an error; an agent with `memory` facts in the region is a warning (R4.7).

## 2. The generation model

### 2.1 Step ids

`runBlock(statements, key)` sets `step = parent + "/" + key + i`. It becomes:

```java
for (int i = 0; i < statements.size(); i++) {
    int generation = generations.current(parent, key, i);        // 1 unless a boundary before i has been rewound
    step.set(parent + "/" + key + i + (generation > 1 ? "~" + generation : ""));
    executeStatement(statements.get(i));
}
```

`generations.current(parent, key, i)` is the generation of the latest boundary at or before statement `i` in this block that applies
to it (§2.3). Nested blocks derive their parent from `step`, which already carries the suffix, so everything beneath inherits it
with no further code.

### 2.2 The boundary list

One journal key, `__boundaries`, holds the list; one key per boundary entry would race. A run is single-writer for this key (the
executor thread that reaches a boundary, or an operator command with the run lock held), and the existing
`FileRunJournal.put` is atomic per key, so appending means read, add, write. Each element:

```json
{ "from": "Main/s3", "name": "collected", "generation": 2, "by": "script", "statement": "Main/s7",
  "reason": "review.score < 7", "with": {"feedback": "…"}, "effects": "hold", "time": "…" }
```

`from` is the id of the **first statement of the new generation** (inclusive): for a checkpoint, the statement after it; for an operator rewind, the statement named. `name` is the checkpoint's name when there is one. `statement` is the `rewind` statement's own id **without any generation suffix**, so the count of rewinds per statement is the same across generations.

### 2.3 What "current generation" means

For a step id `P/k<i>` the generation is the highest `generation` among boundaries whose `from` is a statement of the same
block `P/k` with index ≤ i. A boundary's generation applies to every later statement of its block and everything
nested in them, and to nothing in an enclosing block (a rewind to a checkpoint in an outer block increments the generation of
the outer block's later statements, and nested statements inherit through their ids). That makes each id's generation a function of the boundary list and the id's position alone, so it is the same on every resume.

### 2.3.1 A rewind in a loop

A loop body's ids carry the round (`r2.`); a checkpoint inside the body is `Main/s3/r2.0`, so each round has its own boundaries
(R1.5) and a rewind inside the body targets that round's checkpoint. A rewind that targets a checkpoint outside the loop (an
enclosing block) discards the whole loop's later rounds as part of the later statements of that block, and the loop starts again
in the new generation from round 1.

### 2.4 Executing a rewind

```
executeStatement(RewindStmt r):
    if journal has decision for this statement in this generation  -> follow it (replay)
    cond = evaluate(r.condition, view())            // existing evaluator, journaled variables only
    if !cond: journal "no rewind" decision; return
    used = count of rewinds by this statement (from boundaries.statement)
    if used >= r.max: run onExhausted (or fail); journal; return
    if total >= runCap: fail with the summary
    blockers = effectsPerformedIn(region(r.target, r), policy)
    if blockers non-empty and policy == hold: run onBlocked or pause for a person (§3.3); return
    snapshot = checkpointVariables(r.target, generation)        // §2.5
    carried = resolve(r.carried, view())                        // from the generation being discarded
    append boundary {from: statement after target, generation: g+1, with: carried, …}  // ONE journal write; the decision
    throw RewindSignal(target, generation g+1)
```

`RewindSignal` is a private control-flow exception, like `HandoffSignal`. It is caught by the `runBlock` frame that owns the
checkpoint (found by id), which restores the variables (§2.5), applies the carried values, and continues its loop at the statement
after the checkpoint with the new generation. Frames in between unwind normally (`finally` blocks restore step, scopes and
locals). The single boundary write is the commit point: a crash before it leaves the run exactly as it was; a crash after it
resumes into the new generation (§2.6).

### 2.5 Variables

Variables are rebuilt on every resume by re-running statements that read their results from the journal. At a rewind within a
live process the executor must go back too. At each `checkpoint` the executor stores a **snapshot** of the variable map in
memory (and the initial values of the checkpoint's block) keyed by the checkpoint's id and generation; a rewind restores it. The
snapshot isn't journaled, because on resume the snapshot is simply the result of replaying statements up to the checkpoint, which
the executor does anyway. The carried values are journaled (in the boundary) because they are the one input that can't be
rebuilt. So after a resume the variables at the boundary are `replay-up-to-boundary` + `boundary.with`, and in a live process
the same by construction; a test (V3.5) compares them.

### 2.6 Resume

On start, `generations` loads `__boundaries`. During replay, when `runBlock` reaches a boundary's statement it finds a later
generation for the statements after it and numbers them accordingly, so the generation-1 steps (still in the journal) are never
visited; their variables are never set. The first statement of the newest generation without a journal entry is where
execution resumes. A resumed run that crashed *between* the boundary write and the first model call of the new generation
simply starts that call.

## 3. Effects, approvals and people

### 3.1 The identity rule

Where each kind of record gets its key today, and what changes:

| Record | Key today | Under generations |
|---|---|---|
| Model call (delegate, handoff, broadcast) | `<step>` | `<step>` with its `~g` suffix: **new generation, new call** |
| Human answer (`human_prompt`, approval answer) | `<step>` / `<step>#approve:<tool>:<hash>` | the **generation-free** step. For `human_prompt` the question's hash is stored beside the answer (`<step>#asked`, new), so the same question in the same place is not re-asked and a changed one is; approvals already include the arguments' hash |
| Effect (`EffectTool`) | `<step>#effect:<tool>:<hash>#<n>` | the **generation-free** step, so an identical call finds its record: this is `effects: keep` |
| Usage and spend | `<step>#usage:<agent>` | with the suffix, so every generation's spend is its own and all are counted |

`EffectContext.currentStep()` returns the step with its suffix; a new `EffectContext.identityStep()` (additive, default =
`currentStep()`) returns it without. `EffectTool` builds keys from `identityStep()`, and its per-attempt call ordinal is counted against `identityStep()` too (today it is keyed by `currentStep()`, which would number a repeated call differently in generation 2). `max_per_run` counts effect records of every generation, which is correct (they happened) and means a `redo` spends the allowance again. Loom's `RunEffectContext` implements it by
stripping `~<digits>` segments. With `effects: redo` the executor sets a per-statement flag that makes `identityStep()` keep the
suffix for steps beneath that rewind, so all effects have new keys.

### 3.2 Which effects block a rewind (`hold`)

For the region of statement ids the rewind discards (the statements after the checkpoint in its block up to the `rewind`, in the
current generation, and everything nested), the executor scans `journal.all()` for effect keys whose step part is in the
region and whose kind is `effect_done` or `effect_pending` (`effect_failed` is provably not done and does not block). It also
lists approved tool calls that ran. This is a read of one map, bounded by the journal size. The result is the list shown to the
person and the trace.

### 3.3 Pausing instead of failing

A held rewind with no `on_blocked` pauses through the same path as a tool approval:
`humanInterface.promptHuman(key, question)` with key `<rewind step>#rewind-blocked#<n>`, the question listing the blocking
effects and offering `keep`, `redo`, `cancel`. The answer is journaled with the key, so a resume reads it and acts without asking
again; `keep` and `redo` re-enter §2.4 with that policy for this one rewind, `cancel` records "no rewind" and the run continues
past the statement.

### 3.4 Simulate

Simulate mode (forks) is a property of the run, recorded in `run.json` and set on `RunEffectContext`. It needs the
`EffectContext.simulate()` default-false method in `ai-agent4j`, honoured by `EffectTool` (returns the simulated text, writes
nothing). Tools that aren't `Effectful` (OpenAPI, MCP, class tools) are not covered by that and are therefore wrapped by a
`SimulatingTool` in simulate mode: anything not provably read-only returns `(simulated: not performed)`. The classification is
by class and defaults to simulated, so a new tool kind is safe until it declares itself a read.

## 4. Operator commands

Each command is a function in a new class `RunTravel` over `RunJournal` (a run directory or JDBC run id), `RunSpec`/`run.json`, the
run lock, and the validator; the picocli commands in `cli/` are thin.

| Command | What it does |
|---|---|
| `timeline` | reads `all()`, the boundary list and `run.json`; groups steps by generation; marks each `done`, `failed`, `replayed`, `discarded` (belongs to a generation that has been superseded at its boundary), `pending` |
| `rewind --to` | validates the target is a statement boundary (an id the script can produce for a statement in a block outside any `parallel`/`for each` branch; it re-parses the script from `run.json`), applies the effects policy as §2.4 (using the interactive confirmation when held and a terminal is present, `--effects` otherwise), appends the boundary |
| `reset` | `--to start`, and clears the suspension record (`__suspension`); with `--failed`, one boundary per failed step's block position (each with `statement` = that step) so only those regions get a new generation |
| `fork` | §5 |

All take the run lock (the existing per-run-directory lock used by `resume`) and refuse a held lock without `--force`.

`--stop-at` is a run option: the executor checks after each statement completes whether `stepId == stopAt` (or the checkpoint's
id), and at the **named stop points a step publishes inside itself** (a step kind may register one: `decide` registers
`<step>#decide-proposal`, right after the proposal is journaled and before anyone is asked), throws `RunStopped` (a sibling of `RunSuspended`), and `Runs.execute` maps it to exit code 5.

## 5. Fork

### 5.1 Materialized

1. take the run lock on the parent for the copy only;
2. a new run directory (file journal: copy `journal.json`; JDBC: `journal.all()` then `put` into a journal with the new run id; memory: copy the map);
3. write `run.json` for the child with `forkOf: {run, at, entries, reason}`, the script (the parent's, or `--script`), inputs with `--set` applied as carried values on the boundary, `mode: simulate` if requested;
4. if `--at`, append a boundary (as `rewind` would, without the effects check when the mode is simulate);
5. report the child path.

The parent is only ever read.

### 5.2 Prefix signature

For `--script`, the signature of a script up to a boundary is the SHA-256 of the canonical list, for each statement before the
boundary in execution order, of `(kind, agent, task or payload text, expecting schema, tools of the agent by name and kind)`.
Equal signatures mean the new script would have asked for what the journal holds. A difference is reported with the first
differing statement (`Main/s1: task text differs`), and `--allow-drift` accepts it and records that in `forkOf`.

### 5.3 Ephemeral

`OverlayJournal(parent, overlay)`: `get` consults the overlay then the parent; `put` writes the overlay only; `all()` merges. It
implements `RunJournal`, so the executor runs on it unchanged. It's the same code path as a materialized fork without the copy,
which makes forking thousands of runs cheap (the parent is read, not copied). An ephemeral fork has no `run.json` and no lock.

## 6. Interaction with what exists

| Existing feature | Behaviour |
|---|---|
| Resume after a pause (rate limit, budget window) | unchanged; a pause inside a later generation resumes in it; the suspension record names the step with its suffix |
| `loop … max` | unchanged; a loop is the right tool for "repeat this block until"; a rewind is for a late check that sends the run back to an *earlier, outer* point, with carried data |
| `parallel`, `for each` | branches run as today; checkpoints inside a branch are local to it; a rewind can't leave a branch (R2.7); a rewind outside the construct to a checkpoint before it discards the whole construct's later statements |
| Budgets | steps' usage keys carry the generation (§3.1); `restoreSpend` reads all of them; per-run limits include every generation |
| Triggers | `--resume` writes the same `resume:<runId>` trigger the pause path writes |
| Agent memory (`facts`) | not rewound (R4.7); warned at load |
| Audit | new events, same logger |
| Earned autonomy (separate spec) | uses the ephemeral fork + simulate to replay past decisions |

## 7. Failure paths

| Situation | Behaviour |
|---|---|
| Crash before the boundary write | no rewind happened; resume re-evaluates the condition on the same journal and decides the same |
| Crash after the boundary write | resume runs the new generation; no step of the old one runs |
| Two writers to `__boundaries` | the run lock prevents it; a conflicting write is detected by reading back the list's length after the write and reported |
| `weave rewind` on a run with a live process | refused unless `--force` |
| Rewind target no longer exists (script changed since) | refused: the target must resolve in the script the run's `run.json` names |
| Script edited after a run started, then resumed | existing behaviour (journal supplies recorded steps); `timeline` flags it when the prefix signature of the current script differs from the one recorded at the start |
| Held rewind, nobody answers | the run stays paused like any approval; the trigger store shows it |
| Journal write fails during a rewind | the exception ends the step as any journal failure does; the boundary is not in the journal, so no rewind happened |
| Old `weave` on a rewound run | refused by the format marker (R3.7) |

## 8. Decisions and alternatives

| Decision | Alternative | Why |
|---|---|---|
| Generations in step ids, nothing deleted | delete the entries after the checkpoint | Deletion loses the history and the audit trail, needs a new journal method, and a crash halfway leaves a half-deleted run; appending is atomic per key and resumable |
| Effects and answers keyed without the generation | key everything with the generation | The whole point of safe rewinding: an identical send is not repeated. It also makes `keep` the natural default behaviour of the keys, and `hold`/`redo` the explicit variations |
| `hold` as the default policy | `keep` as the default | A rewind that sends a *different* message is a second message. The default must not do that silently; the author says `keep`, or fixes the placement |
| Statement-level rewind with carried values | a retry of the whole workflow | Carried values are what makes the second attempt different from the first |
| A bounded `max` on every rewind plus a run cap | unbounded with a budget | Budgets are in tokens or money; a runaway rewind loop should stop on a count too, and be obvious in the script |
| Operator rewind to any statement boundary | to checkpoints only | Operators need to go back to where the problem started, which the author may not have named |
| Ephemeral forks as an overlay | copy the journal | Replay forks thousands of runs; an overlay costs nothing and can't touch the parent |
| `--allow-drift` guarded by a prefix signature | silently run the new script on the old journal | The failure would be silent: the journal supplying results the new script never asked for |
| Contextual keywords | reserved words | Existing scripts keep loading |

## 9. Open questions

- **Compound conditions.** The condition evaluator takes one comparison. `rewind … when score < 7 or empty(data)` needs either an evaluator extension (benefits `alt` and `loop until` too) or a computed flag; v1 uses the flag.

- **Rewinding into a `for each` iteration** from outside is allowed as a whole; rewinding a *single* iteration's branch is a natural follow-up.
- **Compensation.** Undoing an effect (delete the message, void the refund) could be declared next to the tool (`undo:`), so `redo` could first undo. Out of scope; the script can do it in `on_exhausted`/`on_blocked` today.
- **Selective carry from agent memory.** Memory written in a discarded generation stays; a journaled memory mode would fix it.
- **Merging forks** (take the better of two). Not in this version.
