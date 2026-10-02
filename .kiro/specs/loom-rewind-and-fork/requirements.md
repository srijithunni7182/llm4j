# Requirements Document

## Introduction

A Loom run can stop and carry on: a crash, a restart, a person who answers tomorrow, a rate limit. It can only
carry on **forward**. If step 7 shows that step 3 was wrong, or a person wants to try another model from step 5,
the only choice today is to throw the run away and pay again for everything before.

This spec adds **time travel to the run**, in two layers on one mechanism:

| Layer | Who decides | What it is |
|---|---|---|
| **Declared** | the workflow | `checkpoint` names a point; `rewind to <checkpoint> when <condition> max N with { … }` goes back to it, carrying forward what was learned. The script defines what "going bad" means and what to do about it |
| **Operated** | a person | `weave rewind`, `weave fork`, `weave reset`, `weave timeline`: go back, branch off to try something, clear a stuck run, see what happened |

A workflow that can only fail forward has to be right the first time. One that can go back can check its own
work late, try again with what it learned, and leave the history of both attempts behind.

The mechanism is small because the run journal is already keyed by step id and is append-only in spirit: a
rewind does not delete anything; it starts a new **generation** of the steps after a point, and the old
generation stays as history. A fork copies (or overlays) a journal under a new run id. Neither needs a new
storage engine.

The part that needs care is **side effects**. A rewind that crosses a Slack send must not send it again by
accident. So the spec fixes one principle and builds on it:

> **A model call is identified by where it is *and which attempt* it belongs to, so it runs again. A side
> effect, or a person's answer, is identified by where it is *and what it is*, so an identical one is never
> repeated.**

The standing rules from earlier Loom specs still apply:

- **Nothing is silently ignored.** Anything the runtime can't honour is a load error that names the line.
- **Existing valid scripts and journals keep working.** `checkpoint` and `rewind` are contextual words; a journal written by the previous build resumes unchanged.
- **Logic lives in the framework**, with the budgets, approvals, guards, audit and journal that exist.
- **Agents are not given the power to rewind.** Only the script and the operator do.

**Out of scope** (each a follow-up, not a gap in this one):

- Undoing a side effect that has already happened (un-sending a message). A rewind keeps, holds or repeats effects; it never reverses them. A compensation action is something the script can do itself in an `on_exhausted` handler.
- Rewinding into the middle of an agent's own reasoning loop. The unit is a workflow statement.
- Rewinding across a `parallel` or `for each` branch boundary (R2.7).
- Merging two forks back into one run.
- Rewinding the memory an agent keeps (`memory { facts }`), which lives outside the journal (R4.7).

## Glossary

- **Checkpoint**: a named point between two statements of a workflow.
- **Boundary**: a point at which a new generation starts: the named statement is the **first** one of the new generation. A checkpoint's boundary is the statement after it; an operator can name any statement.
- **Generation**: the attempt number of the steps after a boundary. The first is 1.
- **Rewind**: starting generation n+1 of the steps after a boundary, with the variables as they were at the boundary plus any carried values.
- **Fork**: a new run that begins as a copy of another run's journal up to a point, and may then differ (a script, an input, an answer).
- **Materialized / ephemeral fork**: a fork kept as a run directory that can be resumed, or an in-memory overlay that is discarded when done.

## Requirements

### Requirement 1: Checkpoints

**User Story:** As a workflow author, I want to name the points I may need to return to, so that going back is something the script says in words.

#### Acceptance Criteria

1. THE grammar SHALL accept `checkpoint Name` as a statement, and `checkpoint Name { var: value, … }`, where each entry gives a variable a value that holds at this point (and so a default for values that a later `rewind … with` replaces). Values are strings or `{variable}` templates, resolved as other payloads are.
2. EVERY workflow SHALL have an implicit checkpoint named `start` before its first statement.
3. WHEN a script is loaded, THE loader SHALL report, naming the line: a duplicate name in a workflow; a name that is not an identifier; a checkpoint inside a `parallel` branch or `for each` body that has a rewind outside it (R2.7).
4. REACHING a checkpoint SHALL record it in the run journal with the time and the generation it belongs to, trace it, and audit it (`checkpoint_reached`). It SHALL cost nothing and call no model.
5. A checkpoint SHALL be usable in a loop body: each round has its own, identified by its round.

### Requirement 2: Declared rewind

**User Story:** As a workflow author, I want to say "if this turns out bad, go back to there and try again, bounded", so that a run can correct itself late.

#### Acceptance Criteria

1. THE grammar SHALL accept
   `rewind to Name [when <condition>] max N [effects: hold|keep|redo] [with { var: value, … }] [on_exhausted { … }] [if blocked { … }]`
   as a statement, where the optional condition is the existing condition language over the run's variables (one comparison, or a bare true/false variable; a compound test is computed into a variable first, for example by an agent's `expecting` field), no `when` means always (used in `on_failure`), and `max` is required and positive.
2. WHEN the condition is true and fewer than `max` rewinds of this statement have happened in this run, THE executor SHALL discard the effect of the statements after the checkpoint (R3), set the variables to those at the checkpoint plus the `with` values, increment the generation, and continue from the statement after the checkpoint.
3. THE `with` values SHALL be resolved at the moment of the rewind from the variables of the discarded generation (so `feedback: "{review.notes}"` carries the reviewer's notes into the next attempt), and SHALL be bound as ordinary variables, together with `_rewind` (the number of rewinds so far for this statement), `_rewindReason` (the condition text) and `_rewindTo` (the checkpoint).
4. WHEN the condition is true and `max` rewinds have been used, THE executor SHALL run `on_exhausted` once and continue after the `rewind` statement, as `loop … max` does; without `on_exhausted` the run SHALL fail with a message naming the statement and the count.
5. WHEN the effects policy forbids the rewind (R4.2), THE executor SHALL run `if blocked`; without it the run SHALL pause for a person (R4.3) rather than fail.
6. THE run SHALL also have a cap on all rewinds together (`--max-rewinds`, default 20, also settable in the script's run settings); exceeding it SHALL fail the run with a message naming the cap and the three statements that rewound most.
7. A `rewind` MAY be written in the same block as its checkpoint (after it), or in a block nested inside that block (an `alt` branch, a loop body, an `on_failure`, `on_exhausted` or `if blocked` handler). It SHALL NOT target a checkpoint in a sibling or nested block, one that comes later, or one outside the `parallel` branch or `for each` body that contains the `rewind`. The loader SHALL report each, naming the line.
8. A `rewind` SHALL be usable inside `on_failure`, so a failed step can send the run back; the failure that caused it is available as `_error`.
9. THE loader SHALL warn, naming the line, when the statements between the checkpoint and the `rewind` can reach an effect tool (an `Effectful` tool that is not a read, a tool whose class is unknown, an agent with `memory` facts) and the policy is not stated, and SHALL report an error when `side effects: repeat` is used with such a tool that is not approved or `unattended`.
10. THE rewind decision SHALL be journaled (R3.4), so that a resume takes the same decision without re-evaluating the condition against data that could differ.

### Requirement 3: Generations in the journal

**User Story:** As an operator, I want a rewind to leave the history intact and survive a crash at any moment, so that I can always see what was tried and trust the run to resume correctly.

#### Acceptance Criteria

1. A REWIND SHALL write only new journal entries: nothing already in the journal SHALL be deleted or changed. This SHALL hold for every journal Loom supports (memory, file, JDBC) with no new method on `RunJournal`.
2. THE step ids of statements after a boundary, in generation n greater than 1, SHALL carry the generation (`…/s5~2`), and ids nested under them SHALL inherit it, so no two generations can collide. Generation 1 ids SHALL be exactly the ids the current build writes, so existing journals resume unchanged (R9.2).
3. THE journal SHALL record boundaries as one entry per boundary and rewind: boundary (checkpoint name or step), generation, who (`script` or an operator's name), reason, the carried values, the effects policy, and time.
4. ON a resume, THE executor SHALL read the boundaries first, and on reaching a boundary SHALL continue directly in the latest generation recorded for it, so the discarded generations' steps are not run again and their variables are not set.
5. A crash after any single journal write during a rewind, and a resume, SHALL end in the same state as an uninterrupted rewind: exactly one new generation, no duplicate model calls for steps already completed in it, no repeated effect.
6. `RunJournal.all()` consumers (usage restore, spend report, audit) SHALL count every generation: a discarded generation's tokens and money were spent.
7. A run directory SHALL carry a format marker once it has rewound, and this build and later ones SHALL refuse a marker they don't understand. Builds that predate this feature cannot know the marker: running one on a rewound run is unsupported and would silently continue past the rewound region, which the documentation SHALL say plainly (a run that never rewound is unaffected).

### Requirement 4: Side effects and people across a rewind

**User Story:** As an operator, I want a rewind never to cause a duplicate send or a second question by accident, so that going back is safe to use.

#### Acceptance Criteria

1. IN a later generation, THE key of a model call SHALL include the generation (it runs again); THE key of an effect (the effect journal's `<step>#effect:<tool>:<hash>#<n>`), of an approval (`<step>#approve:…`) and of a person's answer SHALL NOT, so an identical call or an identical question finds its earlier record.
2. THE `effects:` policy SHALL decide what a rewind does about effects that happened in the generation being discarded:
   - `ask first` (default): if any effect in the discarded generation was performed or may have been (done, or pending), the rewind SHALL NOT happen and `if blocked` SHALL run (or the run SHALL pause, R4.3); effects that provably did not happen (failed) don't block;
   - `keep`: the rewind happens; an identical effect in the new generation is found in the journal and not repeated; a different one runs, and the trace SHALL say "a different effect after a rewind" with the tool and target;
   - `repeat`: the rewind happens and effects are keyed with the generation, so all run again. Needs R2.9's condition on approval.
3. WHEN a rewind is held and there is no `if blocked`, THE run SHALL pause for a person through the existing durable approval path, with a question that lists each blocking effect (tool, target, time, outcome) and offers `keep`, `repeat` or `cancel`; the answer is journaled, and the rewind then proceeds accordingly or is dropped.
4. A PERSON'S answer in a discarded generation SHALL be reused when the same step in the new generation asks the same resolved question (the runtime stores a hash of the question with the answer, as it does not today); a changed question SHALL be asked again. An operator MAY force asking again (`--ask-again`).
5. A run in **simulate** mode (R5.6) SHALL perform no effect in any generation: the effect journal returns the simulated text and records nothing as done.
6. WHEN a rewind discards a generation that contained an approved call that was run, THE trace SHALL list it, so the operator can see what was approved and not repeated.
7. AGENT memory facts, knowledge indexes and anything else a tool writes outside the journal SHALL NOT be rewound; the loader SHALL warn (R2.9) and the documentation SHALL say so.

### Requirement 5: The operator's commands

**User Story:** As an operator, I want to look at a run's history and go back, branch off or clean up from outside the script, so that I can recover and experiment without editing anything.

#### Acceptance Criteria

1. `weave timeline <run-dir>` SHALL print, in order: each step with its id, generation, kind, agent, state (done, failed, replayed, discarded, pending), tokens and cost; the checkpoints reached; each rewind with its reason, who, carried values and policy; and the run's total spend split into kept and discarded generations. `--json` SHALL give the same as data.
2. `weave rewind <run-dir> --to <checkpoint|step-id>  (a step id names the first step to run again) [--set name=value]… [--effects ask-first|keep|repeat] [--ask-again] --reason "…" [--resume]` SHALL append a rewind (an operator one) exactly as a scripted rewind would, subject to the same effects policy, and with `--resume` run the workflow on from there. A step id that is not a statement boundary, or one inside a `parallel` branch, SHALL be refused with the nearest valid ids.
3. `weave reset <run-dir> --reason "…" [--failed] [--resume]` SHALL: with no flag, rewind to `start` and clear any recorded suspension, so the run starts again under a new generation with its spend history kept; with `--failed`, give every step whose journal entry is a failure a new generation (so it is tried again, and its `on_failure` runs afresh) and leave the rest.
4. `weave fork <run-dir> --to <new-run-dir> [--at <checkpoint|step>] [--script <file>] [--set name=value]… [--effects keep|simulate|repeat] [--until <checkpoint|step>] [--allow-drift] --reason "…"` SHALL create a **materialized** fork: a new run directory whose journal holds every entry of the parent's, a rewind at `--at` if given, and a `run.json` that records the parent run, the point, the parent's journal size at that moment and the reason. The parent SHALL NOT be changed.
5. `--script` SHALL run the fork under a different script. Before doing so, THE command SHALL compare a **prefix signature** of the two scripts (the statements before the fork point, by kind, agent, task text, tools) and refuse when it differs unless `--allow-drift`, saying where, because the journal would otherwise supply results the new script would not have asked for.
6. `--effects simulate` SHALL make the fork run in simulate mode (R4.5) in every generation. THE mode SHALL be recorded in the fork's `run.json` and shown by `timeline`, and SHALL NOT be changeable by a later `resume` without an explicit `weave fork`/`rewind --effects`.
7. `--until` SHALL end the run cleanly once the named step has completed (exit code 5, "stopped at"), so a fork can be used to look at one step's result.
8. `weave run` and `weave resume` SHALL accept `--stop-at <checkpoint|step>` with the same effect.
9. AN **ephemeral** fork SHALL be available to code (and to `weave replay` in the earned-autonomy spec): the parent journal read-only underneath, a writable overlay on top, nothing written to the parent, nothing kept after.
10. EVERY command SHALL require `--reason` where it changes something, SHALL write an audit event (`run_rewound`, `run_forked`, `run_reset`), and SHALL refuse a run directory with a live process holding it (the existing run lock) unless `--force`.
11. WHEN a trigger store is in use, `--resume` SHALL write the run's resume trigger as pausing for a limit does, so a scheduled run carries on by itself.

### Requirement 6: Cost and bounds

**User Story:** As an operator, I want a run that rewinds to stay within its budgets, so that self-correction can't become an expensive loop.

#### Acceptance Criteria

1. EVERY `rewind` SHALL be bounded by its `max` and the run by its cap (R2.6); both SHALL be shown in `timeline`.
2. Budgets in force when a step runs (per run, per agent, per step) SHALL apply to later generations as to the first, and tokens and money spent in discarded generations SHALL stay counted against them (R3.6).
3. WHEN a budget refuses a call in a later generation, THE existing budget behaviour SHALL apply unchanged (`on_failure`, `when_exhausted`, suspension), and a rewind SHALL NOT be taken by a run that is already over a budget it would spend again.
4. `weave run --max-tokens/--max-cost` and the spend report SHALL include rewound spend, shown separately.

### Requirement 7: Observability

**User Story:** As someone watching a long run, I want to see that it went back and why, so that a self-correcting run is understandable rather than mysterious.

#### Acceptance Criteria

1. THE trace SHALL show checkpoints, rewinds (to where, why, with what, which generation), blocked rewinds, forks and resets, in `--trace` and `--trace=json`.
2. THE audit log SHALL carry `checkpoint_reached`, `run_rewound`, `rewind_blocked`, `rewind_exhausted`, `run_forked`, `run_reset`, each with the run, generation, boundary, reason and who.
3. THE console SHALL mark steps of a later generation (`~2`) so the log can be read.
4. THE variables `_rewind`, `_rewindReason`, `_rewindTo` SHALL be visible to the script (R2.3), and `_generation` SHALL give the current generation of the statement running.

### Requirement 8: Safety

**User Story:** As a security reviewer, I want only the script and the operator to be able to go back, so that data and models can't talk a run into replaying itself.

#### Acceptance Criteria

1. NO tool, agent output, case field or carried value SHALL be able to cause a rewind, a fork or a reset; the journal's boundary entries and the run's format marker are reserved paths for the `file` tool and unreachable from agents.
2. THE condition of a `rewind` SHALL be evaluated by the existing condition language on journaled variables only; it SHALL NOT call a model or a tool.
3. `with` values are untrusted text like any variable; they SHALL be shown to a person with control characters neutralised.
4. A FORK SHALL never write to its parent's journal, run directory, trigger store entry or ledger; an ephemeral fork SHALL never write any file other than its own log.
5. `--force` on an operator command SHALL be recorded in the journal and audit, and shown by `timeline`.
6. A rewind that would cross an effect it cannot classify (a tool of an unknown class) SHALL be treated as holding.
7. THE hostile-model suite SHALL include an agent output that tries to look like a rewind or a boundary entry, and field values designed to make a `when` condition true, and neither SHALL be able to do more than the condition already allows.

### Requirement 9: Compatibility, documentation and tooling

**User Story:** As an existing user, I want all of this to be additive, and as a new one I want it explained.

#### Acceptance Criteria

1. A script that uses `checkpoint` or `rewind` as a variable, agent or tool name SHALL keep loading and running; they are recognised only where the grammar expects them. A test SHALL cover every script in the repository.
2. A JOURNAL written by the previous build SHALL resume under this build with the same results, and a journal that never rewound SHALL be byte-for-byte what the previous build would have written (the feature adds no journal entries to a run that does not use it).
3. `LOOM_GUIDE.md` SHALL have a "Checkpoints, rewind and fork" section, with every `loom` block validated by the guide-examples test, the commands run by a test, and the effects principle stated first; `LOOM_PROMPT.md`, the READMEs, the VS Code grammar and hover text SHALL mention `checkpoint` and `rewind`.
4. THE feature SHALL work with every journal and trigger store Loom supports and with scheduled, resumed and paused runs.
