# Verification Plan

The work is done when every check passes in automated tests, the gates under *Completion* pass at one commit, and the evidence is
committed. Tests carry their check id as `@Tag("V3.4")` so a script can prove every check has a passing test and no test has an
unknown id (the approach used for the generic tools).

Unless a check says otherwise it uses these stand-ins:

| Stand-in for | What |
|---|---|
| Models | a scripted `LLMClient` that answers per call from a list, and records every call it receives (so "no model call was made" and "this call was made again" are assertions) |
| Effects | a recording tool: `Effectful`, counts every `perform`, can be a read or an effect per call |
| People | a scripted `HumanInterface` that answers per key, records every question, and can pause (`RunSuspended`) |
| Journals | memory, `FileRunJournal` on `@TempDir`, `JdbcRunJournal` on H2; the **journal matrix**: every journal-level check runs on all three |
| Crashes | `FaultJournal`: throws after the Nth `put` (before or after the write); also an executor abandoned mid-step |
| Time | injected `Clock` |

## V1: Checkpoints (R1)

| # | Check |
|---|---|
| V1.1 | `checkpoint a`, `checkpoint a { x: "1", y: "{topic}" }` parse; `start` exists without being written and `rewind to start` loads. |
| V1.2 | Load errors, with the line: duplicate name; non-identifier name; unknown target. |
| V1.3 | Reaching a checkpoint writes one journal entry with time and generation, one trace event, one audit event (`checkpoint_reached`), and makes no model call and spends no tokens. |
| V1.4 | Initial values are set as variables when the checkpoint is reached; a `{variable}` value resolves at that point. |
| V1.5 | A checkpoint in a loop body creates one per round (`r1.`, `r2.`), each rewindable to within its round. |

## V2: Declared rewind (R2)

| # | Check |
|---|---|
| V2.1 | The example in design §1.1 loads; with a scripted reviewer scoring 5, 6, 8, the run makes three collector/analyst/writer/reviewer passes and publishes the third draft; with scores 5, 5, 5 it runs `if it still fails` after two rewinds. |
| V2.2 | `carrying feedback = "{review.notes}"` carries the notes of the discarded generation into the next: the collector's second task text contains them, its first contains the checkpoint's default. `_rewind`, `_rewindReason`, `_rewindTo` are set. |
| V2.3 | `at most N times` is required and positive; exhaustion without `if it still fails` fails the run naming the statement and count; with it, the handler runs once and execution continues after the statement. |
| V2.4 | The run cap (`--max-rewinds 3`) stops a script whose three statements rewind four times in total, naming the cap and the three busiest statements. |
| V2.5 | Variables set in the discarded generation are gone after the rewind (a variable set only by `s4` is unset when `s4`'s second run is read before it executes), and variables from before the checkpoint are intact. |
| V2.6 | Target placement: errors for a target in a sibling block, in a nested block, later than the rewind, and (separately) outside the `parallel` branch or `for each` body containing the rewind; accepted for the same block, an enclosing block, and from `alt`, loop body, `on_failure`, `if it still fails` and `if blocked`. |
| V2.7 | `rewind` in `on_failure` with `_error` carried: a failing step sends the run back once and the second attempt succeeds. |
| V2.8 | A condition that is false makes no rewind and no journal boundary; the decision "no rewind" is journaled so a resume takes it. |
| V2.9 | The effect-reach warning appears for an effect tool in the region with no policy stated; an error for `side effects: repeat` with an unapproved effect tool; none for a pure region. |
| V2.10 | The condition is evaluated on journaled variables only: a test with a model that would behave differently on a second evaluation proves the second evaluation (on resume) takes the journaled decision. |
| V2.12 | `rewind … max 1` with no `when` always rewinds (in `on_failure`); a compound condition (`a < 1 or b > 2`) is a load error naming the limit. |
| V2.11 | A rewind to an outer checkpoint from inside a loop discards the loop's later rounds and restarts it from round 1 in the new generation. |

## V3: Generations in the journal (R3)

| # | Check |
|---|---|
| V3.1 | After a rewind, **no entry that existed before it is missing or different** (byte comparison of the old entries), on all three journals; only new keys were added. |
| V3.2 | Generation-2 ids are `…~2` and nested ids inherit; no id collides with a generation-1 id; generation-1 ids in a run that never rewinds are byte-identical to the previous build's (golden file recorded from the baseline commit). |
| V3.3 | The boundary entry has every field of R3.3. |
| V3.4 | **Crash at every write.** For the V2.1 script, crash after (and separately instead of) each single journal write during a rewind, resume, and compare to an uninterrupted run: same final variables, same journal keys, exactly one new generation, the scripted model made no call for a step already completed in generation 2, the recording effect tool performed no effect twice. All three journals. |
| V3.5 | The variables at a boundary are the same in a live rewind and after a resume (compare maps). |
| V3.6 | Usage, spend report and `--max-cost` count discarded generations: the sum of the report equals the sum of all `usage` entries. |
| V3.7 | The run directory carries a format marker once it has rewound; this build refuses a marker it doesn't understand; a run that never rewound carries none; the guide states that pre-feature builds can't read a rewound run. |
| V3.8 | Resuming a journal written by the baseline build (a golden journal from the previous commit, including a paused run) gives the same results as before the change. |

## V4: Effects and people (R4)

| # | Check |
|---|---|
| V4.1 | **Identity rule.** After a rewind with `side effects: keep`, a model call in generation 2 is made again, an identical effect call is not performed again (found, "already done"), an identical human question is not asked again. |
| V4.2 | `keep` with a *different* effect argument performs it and the trace says "a different effect after a rewind" with tool and target; the first is still recorded as done. |
| V4.3 | `ask first` (default): a done effect in the discarded generation blocks the rewind and runs `if blocked`; a `pending` effect blocks; a `failed` effect does not; a read does not. |
| V4.4 | A held rewind with no `if blocked` pauses with a question listing each blocking effect (tool, target, time, outcome) and offering keep/redo/cancel; answering `keep` rewinds under keep; `repeat` under redo; `cancel` records no rewind and the run goes on; the answer is journaled and a resume doesn't ask again. |
| V4.5 | `repeat`: the same effect call is performed again in generation 2 (keyed with the generation) and recorded separately; an unapproved effect tool is a load error. |
| V4.11 | An effect repeated identically within one step after a rewind gets the same ordinal as in generation 1 (counted against the generation-free step); `max_per_run` counts every generation, so a `repeat` spends it again. |
| V4.6 | A person's answer is reused for an identical question and the question is re-asked when the resolved text differs; `--ask-again` forces it. |
| V4.7 | Approved tool calls (`approve:`) with identical arguments are not re-asked after a rewind; changed arguments are. |
| V4.8 | Simulate mode: no effect is performed in any generation, none is recorded as done; non-`Effectful` tools return the simulated text; a new tool class added by the test is simulated by default. |
| V4.9 | The trace lists the approved calls that ran in a discarded generation. |
| V4.10 | An agent with `memory { facts }` in the region produces the load warning; the documentation sentence exists (checked by the docs test). |

## V5: Operator commands (R5)

| # | Check |
|---|---|
| V5.1 | `timeline` on a run with two rewinds shows steps by generation, states, checkpoints, rewinds with reason/who/with/policy, spend split kept/discarded; `--json` matches the text (golden files). |
| V5.2 | `rewind --to <checkpoint>` and `--to <step-id>` append a boundary identical in shape to a scripted one with `by` = the operator's name; a step id inside a `parallel` branch or not a boundary is refused listing the nearest valid ids; the effects policy applies as in V4.3 (a terminal confirmation, or `--effects`). |
| V5.3 | `--set name=value` is carried; `--resume` continues the run and the result equals a scripted rewind of the same shape. |
| V5.4 | `reset` rewinds to `start`, clears the suspension record, keeps spend; `reset --failed` gives only failed steps' regions a new generation and leaves the rest replayed. |
| V5.5 | `fork --to` makes a new run directory with every parent entry, a `run.json` with `forkOf`, and an unchanged parent (byte comparison of the parent's directory and, for JDBC, its rows); the fork resumes and runs on independently. |
| V5.6 | `fork --at <checkpoint> --set k=v` begins a new generation in the fork only. |
| V5.7 | `fork --script other.loom`: a changed prefix is refused naming the first differing statement; an identical prefix is accepted; `--allow-drift` accepts a different one and records it; a change *after* the fork point is accepted. |
| V5.8 | `fork --effects simulate` records the mode; a later plain `resume` of that fork stays in simulate; the recording effect tool performs nothing across the whole fork. |
| V5.9 | `--until` and `--stop-at` stop cleanly after the named step with exit code 5, leave a resumable run, and `resume` finishes. |
| V5.10 | Ephemeral fork: run on an `OverlayJournal`; the parent's bytes are unchanged afterwards; nothing but the overlay's own memory/log is written; 2 000 ephemeral forks of one parent complete within the performance bound in V9.2. |
| V5.11 | Every changing command requires `--reason`; each writes its audit event; each refuses a run with a live lock unless `--force`, and `--force` is recorded and shown by `timeline`. |
| V5.12 | With a trigger store, `--resume` writes the run's resume trigger; `weave tick` then continues it. |

## V6: Cost and bounds (R6)

| # | Check |
|---|---|
| V6.1 | Per-run token and cost budgets include discarded generations: a run whose first attempt spends 60% of the budget stops its second at the budget, not at 160%. |
| V6.2 | A budget refusal in generation 2 behaves as in generation 1 (`on_failure`, `when_exhausted: suspend`), and a rewind is not taken by a run already over a budget the region would spend again. |
| V6.3 | The spend report shows kept and discarded spend separately and adds up. |

## V7: Observability (R7)

| # | Check |
|---|---|
| V7.1 | `--trace` and `--trace=json` show checkpoints, rewinds (target, reason, with, generation), blocked rewinds, forks and resets. |
| V7.2 | Audit events with the fields of R7.2 exist for each. |
| V7.3 | Console output marks `~2` steps; `_generation` is readable by the script. |

## V8: Safety (R8)

| # | Check |
|---|---|
| V8.1 | Hostile-model suite: an agent answer containing JSON that looks like a boundary entry; a tool result containing `__boundaries`; a field value designed to make a `when` true; the `file` and `shell` tools pointed at the run directory and journal: none causes a rewind, a fork, a reset or a journal change (reserved paths are refused). |
| V8.2 | The `when` condition can't call a model or a tool (grammar and evaluator checks). |
| V8.3 | Carried values shown to a person have control and line-break characters neutralised. |
| V8.4 | A fork never writes to its parent (V5.5, V5.10 byte comparisons, including with a fault injected mid-fork). |
| V8.5 | A tool of an unknown class in the region blocks under `ask first` and is simulated in simulate mode. |
| V8.6 | The secrets sweep: run V2 and V5 scenarios with a recognisable fake secret in a tool option and in a carried value; `grep` the journal, `run.json`, trace, audit, timeline output and reports: no occurrence. |

## V9: Compatibility, quality, documentation (R9)

| # | Check |
|---|---|
| V9.1 | Every `.loom` file in the repository, with its variables renamed to `checkpoint` and `rewind` in turn, still parses; the existing repository-wide script check passes unchanged. |
| V9.2 | Performance: `generations.current` adds under 1 µs per statement on a 10 000-statement script; 2 000 ephemeral forks of a 50-step journal finish in under 20 s; a 100 MB-equivalent journal's `timeline` renders in under 5 s. |
| V9.3 | Guide blocks are validated by the guide-examples test; the guide's commands run against a seeded run and print what the guide says; the principle sentence from the introduction is in the guide; `LOOM_PROMPT.md`, the READMEs, the VS Code grammar and hover text mention `checkpoint` and `rewind` (a test fails if one is missing). |
| V9.4 | The whole existing Loom, ai-agent4j and tools suites pass unchanged; none of their tests is removed or edited except the additive `EffectContext` implementers. |
| V9.5 | Property test: for random scripts (a generator of nested blocks with checkpoints, rewinds, effects, human prompts) and random crash points, a run with crashes and resumes ends in the same state as the same run without crashes; 5 000 cases on a seed, 50 000 in the long run. |
| V9.6 | Coverage: the generation code, `RunTravel`, `OverlayJournal` and the effects-blocking scan ≥ 90% branches; the new executor code ≥ 85% lines (JaCoCo `check`). |
| V9.7 | Mutation pass (sabotage list), each must make a named test fail: key effects with the generation; key a model call without it; delete old entries on rewind; skip the boundary write; write the boundary after the first call of the new generation; ignore `ask first`; let a fork write the parent; take `reset --failed` to start; let `max` default to unbounded; treat `effect_pending` as not blocking; drop discarded spend from the budget; let simulate perform effects of non-`Effectful` tools. |

# Completion

Gates in a fixed order, each with a command, an evidence file (commit hash at the top; evidence from another commit is void) and a pass
rule, as for the generic tools.

| Gate | What | Pass rule |
|---|---|---|
| G0 | Clean tree; every task ticked | none unticked |
| G1 | Clean build of `ai-agent4j`, `ai-agent4j-tools`, Loom, three times (plain, plain, random order with long property runs) | all succeed; plain runs have equal counts |
| G2 | Traceability: every V-check has a passing tagged test; no unknown ids; every requirement in the matrix | clean |
| G3 | Sabotage: each V9.7 change made in the real source; a named test fails | all detected |
| G4 | Regression against the commit before the work, and old journals resume | zero |
| G5 | Real runs with a stand-in model server: a self-correcting report script (V2.1 shape) rewinds twice and publishes; `kill -9` during a rewind then `resume` gives one new generation and no repeated Slack post (stand-in webhook counts); `weave timeline`, `rewind`, `fork --effects simulate`, `reset --failed`, `fork --script` with and without drift | transcripts as expected |
| G6 | The packaged `weave` JAR runs `timeline`, `rewind`, `fork` | works with only the JAR |
| G7 | Docs: guide blocks validated, commands run, hover text present | clean |
| G8 | Coverage gates | met |
| G9 | Safety: V8, the secrets sweep, `/security-review` of the diff | no hostile case has an effect; findings fixed or explained |

## Requirement to check

| Requirement | Checks |
|---|---|
| R1 Checkpoints | V1.1–V1.5 |
| R2 Declared rewind | V2.1–V2.12, V3.4 |
| R3 Generations in the journal | V3.1–V3.8, V9.5 |
| R4 Side effects and people | V4.1–V4.11 |
| R5 Operator commands | V5.1–V5.12 |
| R6 Cost and bounds | V6.1–V6.3, V3.6 |
| R7 Observability | V7.1–V7.3 |
| R8 Safety | V8.1–V8.6 |
| R9 Compatibility, documentation | V9.1–V9.7, V3.2, V3.8 |
