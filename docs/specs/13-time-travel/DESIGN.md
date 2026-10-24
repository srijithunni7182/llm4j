# Phase 13 design: time-travel debugging (`why`, the stepper, `whatif`, the run overlay)

Status: **draft for review**. Layer: Loom (`ai-agent4j-loom`: packages `travel`, `debug`, `explain`; commands `weave why`, `weave step`, `weave whatif`, `weave graph --run`; a debug adapter `weave dap`). Companion documents:
[TEST_STRATEGY.md](TEST_STRATEGY.md), [VERIFICATION.md](VERIFICATION.md), [program overview](../LOOM_DX.md). Builds on the run journal (`RunJournal`, `FileRunJournal`, `JdbcRunJournal`),
`OverlayJournal`, `RunTravel` and the `weave timeline | rewind | reset | fork` commands, `weave replay` (decisions), `weave graph`, the trace events behind `--trace`, and `ConditionEvaluator`.

## 1. Problem

When a workflow does something unexpected the developer's questions are always the same:

1. **Why did it go there?** Which condition, over which values, took the `else`? Why did the classifier route to a person?
2. **What did it see?** What were the variables at that step, where did each come from, what exactly was the agent asked?
3. **What if?** What would have happened if the classifier had said `refund`, or the prompt had been different, or a tool had failed?
4. **Show me.** Which part of the graph actually ran, how often, at what cost and time?

Loom has the material for all of it. The run journal records every step with side effects under a stable step id; `fork` and `OverlayJournal` can lay a changed run over a copy of the past without touching it; the language is total, so a condition is a pure function of variables.
But the answers are scattered: `weave timeline` lists journal entries, `--trace` streams events live, Fabric shows state, and nothing says *"`alt (intent.urgent == "yes")` was false because `intent.urgent` was `"no"` (bound at step n3 by the classifier at 10:42:03)"*.

The journal does not record the conditions themselves: it records only steps with effects (`delegate`, `failed`, `human`, `broadcast`, `handoff`, `sleep`, `halt`, the `decide-*` entries). That is not a problem; it is the design opportunity: **because the language is total and effects are journaled,
a run can be re-executed over its own journal with no model, no tool and no spend, and every decision re-derived exactly.** Explaining a run is replaying it in observe-only mode.

## 2. Goals and non-goals

Goals:

- **`weave why <run> [step]`**: explain each routing decision of a finished or paused run (condition, operand values with provenance, result, branch taken and not taken), from the journal, without calling anything.
- **A stepper**: run a workflow under a debugger-like loop: pause before each step, inspect and edit variables, set breakpoints, step, continue, step *back*; effects simulated by default.
- **`weave whatif`**: take a past run, change something (a classifier answer, a variable, a prompt, a model, a script, a tool result) at a chosen step, re-run from there on a copy, and show how the trajectory, result and cost differ.
- **A run overlay**: the graph with the executed path, skipped branches, iteration counts, cost and time per node, for one run or an aggregate of runs.
- **A debug adapter** so VS Code (and any DAP client) offers F5, breakpoints in the `.loom` file, a variables view and step controls.
- **Nothing spends or sends by default** in any of these; real execution is an explicit flag.

Non-goals:

- Not a profiler for the Java side (tools, models). Latency per step is reported from the journal/trace; deep tracing stays in Fabric/OpenTelemetry.
- Not modifying a live run. Everything here works on a finished or paused run's journal, or on a fresh run under the stepper.
- Not a change to journal format for existing runs. Old journals remain readable; new optional records improve the answers (§4.2).
- Not natural-language explanation by a model. `weave why` is exact and deterministic; `weave explain` (plain words) remains the script-level narrator.

## 3. Principle: re-derive, do not re-run

All of §4 to §7 stand on one primitive:

> **Observe-replay**: execute the script from the start over a given journal in *observe mode*. Where the journal has a step result, use it; where it does not (the point the real run stopped), stop. Emit an event at every decision point. Perform no effect: no model call, no tool, no task with `reach` beyond `none`, no sleep, no send.

- **R1** Observe-replay uses the same interpreter (`HarnessExecutor`) with an observing collaborator set; there is no second implementation of the language. A test proves that for every run in the corpus the observe-replay's step sequence equals the original run's step sequence.
- **R2** A run whose journal lacks an entry the replay needs (journal pruned, or the script changed so a new step appears before the stop point) is reported as **diverged at step X**, with what differs; `why` then explains up to the divergence and says so. It never invents a value.
- **R3** The script used for replay is the one the run used. `RunSpec`/`ScriptDrift` already detect a changed script (hash recorded with the run); `why` refuses to explain against a drifted script unless `--script old.loom` is given, and says exactly that.
- **R4** Tasks (`run`) that are deterministic and `reach: none` may be re-executed during observe-replay (their journaled result is used when present; the result is verified equal when re-executed in `--verify` mode, to detect a non-deterministic task, which is itself reported).

### 3.1 The decision-point event (R5, R6)

A new opt-in trace event, `DECISION_POINT`, emitted by the interpreter **only when a listener has asked for it** (zero cost and zero change otherwise; pinned by a test that `weave run` output and journal are byte-identical):

```json
{ "type": "decision", "step": "Main/s3", "node": "n7", "kind": "alt", "line": 37,
  "condition": "intent.urgent == \"yes\"",
  "operands": [ { "expr": "intent.urgent", "value": "no", "boundAt": "n3", "boundBy": "intentclassify Intent" } ],
  "result": false, "taken": "else", "notTaken": ["then"] }
```

- **R5** Kinds: `alt`, `loop` (each iteration's check; and exit reason `condition | max | on_exhausted`), `for each` (list size, item), `intentclassify` routing (the answer and the confidence rule outcome, including `unsure`), `retry` (attempt n of N, error category, backoff), `on_failure` takeover, `rewind` (checkpoint, count, carried values), `decide` (proposal, rung, who decides), `guard` hits (masked field names, never values).
- **R6** `operands[].boundAt/boundBy` give **provenance**: the graph node that last bound the variable. Values are truncated to a display limit (default 200 chars) with their length; values of variables marked secret or masked by a `guard` are shown as `‹masked›`.

## 4. `weave why` (R7 to R13)

```
weave why <run-dir|run-id> [step|node|line] [options]
  --store <dir>             where runs are kept (default as other run commands)
  --format text|json|md
  --all                     every decision of the run (default: the decisions on the path to the end, most recent last)
  --step <id> | --node <n> | --line <n>
  --since <step>            only decisions after this step
  --verify                  also re-execute deterministic tasks and compare (R4)
  --script <file>           explain against this script (R3)
```

Example:

```
Run r18f3a2c-9b1 · TicketTriage · completed 10:42:07 · 1 delegate, 1 classifier · $0.0021

 1  line 29  intentclassify Intent on message
      kind = order_status (0.93)   urgent = "no" (0.88)   tone = calm        message = "where is my parcel?"
 2  line 36  alt (intent.kind == "unsure")                  → false  → skipped: the human_prompt
 3  line 40  alt (intent.urgent == "yes")                   → false
      intent.urgent = "no"  (bound at step 1 by intentclassify Intent)
 4  line 44  alt (intent.kind == "order_status")            → TRUE   → delegate Replier
      intent.kind = "order_status"  (bound at step 1)
 5  line 45  delegate Replier                               → reply (61 chars, 1 attempt, 0.9 s)
 6  line 46  return { team: "support", route: "reply", reply: … }

 Why not 'retention'? line 40 was false because intent.urgent was "no". It would have been taken if "yes".
```

- **R7** The default output is the decisions on the *path actually taken*, each with condition text (as written), operand values with provenance, result, branch taken and the branches skipped (with the first statement of each).
- **R8** `why <step>` answers "why did this step run / not run": it names the nearest enclosing decision and the value that made it so; for a step that never ran it names the decision that excluded it and what would have had to be true (for simple comparisons against a literal or an enum; otherwise it shows the condition and the values).
- **R9** `why` for a suspended run shows the question it is waiting on and every decision up to it. For a failed run it shows the failing step, the error category, the attempts, the handler that took over or that none existed (`BROKEN` semantics from phase 4).
- **R10** `why` for a run with `rewind`s explains each generation: why each rewind fired, the carried values, and which attempt each decision belongs to (`Generations`).
- **R11** JSON output (version 1) is documented and is the contract used by Fabric (a run page link "Why?") and the editor.
- **R12** Everything in `why` is computed from the journal and the script: running it twice gives identical output, and it can run on a machine that has none of the run's secrets, models, tools or network.
- **R13** `why` of a 5,000-step run completes in under 2 s (observe-replay is cheap: no I/O but the journal read).

## 5. The stepper (R14 to R24)

`weave step <script> [--input k=v]… [--journal dir] [--effects simulate|real] [--break <line|node|agent>…]`

An interactive loop (and, via the debug adapter, a graphical one). Before each statement the stepper pauses and shows: the statement (with line), the graph node, the variables in scope (with provenance), and what the next step would be sent.

| Command | Does |
|---|---|
| `next` / `n` | Execute this statement, pause at the next |
| `into` / `i` | Enter a `call`, a `loop` body or a branch |
| `out` / `o` | Run to the end of the current block |
| `continue` / `c` | Run to the next breakpoint or the end |
| `break <line\|node\|Agent\|tool X>` / `unbreak` | Breakpoints; `break when <expr>` (a Loom condition over variables) |
| `vars` / `p <expr>` | Show variables / evaluate a Loom expression |
| `set <var> = <value>` | Edit a variable (recorded as an operator edit in the session transcript, R20) |
| `answer <text>` | Provide the answer for a pending `human_prompt` |
| `model <Agent> "<scripted reply>"` | Script the next model answer (phase 11 form) so a step can be taken without a model |
| `back` / `b` | Step back to the previous statement (§5.2) |
| `why` | Explain the last decision (§4) |
| `where` | Show the position in the graph (ASCII) and the call stack |
| `cost` | Spend so far and the phase 9 estimate for what remains |
| `quit` | End, keeping the journal if `--journal` is given |

- **R14** **Effects are simulated by default** (`--effects simulate`): models are scripted or answered by the mock; tools and tasks with `reach` beyond `none` record the effect and return a stub; humans are prompted in the terminal. `--effects real` runs for real, prints a banner, requires `--yes` on a non-terminal, and respects `approve:` and budgets exactly as `weave run`.
- **R15** The stepper uses a new interpreter seam, **`StepGate`**: a callback invoked before each statement with a read-only view of the state; its return value says proceed, skip or abort. The default gate is a no-op; a test pins that `weave run` is unchanged with the seam present (as phase 11's R38).
- **R16** `step` runs on the same journal rules as `run`: with `--journal` it is resumable and `quit` leaves a run that `weave resume` or `weave why` can use.
- **R17** Variables shown honour guards: masked fields display as `‹masked›`, secrets never appear, and large values are truncated with a way to page (`p <var> --full`).
- **R18** `break when` uses the existing condition language (total, no I/O); an invalid expression is rejected with the checker's message.
- **R19** The stepper never changes the script on disk and never writes outside `--journal`.
- **R20** A session transcript (`--record file`) captures commands and edits so a debugging session can be replayed and shared (`weave step --replay file`), which also makes the stepper testable.

### 5.1 State view

- **R21** `vars` shows each variable with: name, value (truncated), type (from phase 10's index when available), the node that bound it, and for agent results the attempt count, model, tokens and latency.
- **R22** `where` shows the enclosing blocks (workflow, loop round, branch, `call` stack), the next statement, and the *planned* statements after it in the current block.

### 5.2 Stepping back (R23, R24)

- **R23** `back` is implemented as **observe-replay to the previous statement**: the state at any earlier statement is re-derived from the journal, not stored, so stepping back is cheap and exact. If the step being left behind had an effect that was `real`, `back` says that the effect already happened and shows the `--effects` policy (`keep` is the default; `repeat` is refused in the stepper).
- **R24** Edits made with `set` are journaled in an overlay (`OverlayJournal`), so `back` followed by `next` re-applies them; the original journal is never modified.

## 6. `weave whatif` (R25 to R33)

```
weave whatif <run> --at <step|node|line> [change…] [options]
  change (repeatable):
    --set <var>=<value>                   a variable at that step
    --answer <Agent|Classifier>=<json|text>   what a model/classifier says there (scripted)
    --tool <Name>=<json>                  what a tool returns
    --prompt <Agent>=<file|@version>      a different prompt for the agent
    --model <Agent>=<model-id>            a different model (needs --models live)
    --script <candidate.loom>             a changed script (compared from --at)
    --fail <Target>=<category>[:times]    inject a failure
    --human <text>                        a different human answer
  --models recorded|live|scripted         recorded (default): reuse recorded replies where the call is unchanged; live: call models (needs a spend cap); scripted: only what the changes provide
  --effects simulate|real                 default simulate (never real in whatif without --yes and a banner)
  --max-cost <usd> --max-tokens <n>       required with --models live
  --until <step|end>                      stop here
  --format text|json|md
```

- **R25** `whatif` = `fork` the run's journal into an overlay, truncate the overlay at `--at` (the same machinery as `rewind`), apply the change, run the script forward from there, and compare the new trajectory with the original from `--at` on. Nothing in the original run directory is written (a test checks the directory's hash before and after).
- **R26** **Recorded models.** With `--models recorded`, a model call whose *inputs are unchanged* (agent, prompt hash, model id, task text) reuses the recorded reply; a call whose inputs changed (because an earlier change flowed into it) is **not** guessed: the report lists it as `needs a model` and stops the branch there, unless `--models live` or a scripted answer is given.
  This makes the useful case (a *logic* change: a different classifier answer, a different script condition) free and exact, and refuses to fabricate model behaviour.
- **R27** The report shows: the first point of divergence; the trajectories side by side (steps that ran only in the original, only in the candidate, in both); the result (or result diff); cost and token deltas, both measured (live) and estimated (phase 9) with each labelled; and the effects the candidate would have caused that the original did not (and vice versa), with unsafe flips marked (a new `sends`/`writes` effect).
- **R28** `--script candidate.loom` compares scripts on a single run; it is the single-run form of the phase 14 `regress`. The script drift check (R3) is deliberately bypassed here and the report states both script hashes.
- **R29** `--prompt <Agent>=@v2` uses the versioned prompt catalog the repo already has (`PromptCatalog`, `--prompt agent@v1` in `weave eval`).
- **R30** With `--models live`, spend is capped and printed first ("will call up to 3 model steps, estimated $0.004, cap $0.05; continue?"), reusing the phase 9 estimator for the part of the workflow after `--at`.
- **R31** Side effects: simulated effects are recorded; `--effects real` additionally requires that the run's earlier effects be treated by the existing `--effects ask-first|keep|repeat` policy (fork already does this).
- **R32** The result of a `whatif` can be saved as a new run directory (`--save <dir>`, the overlay materialised) so it can be opened with `why`, the overlay graph, Fabric or turned into a test (phase 14).
- **R33** `whatif` of an unrepairable divergence (the script no longer has the step) says so and offers `--script old.loom`.

## 7. The run overlay on the graph (R34 to R37)

`weave graph <script> --run <run> [--run <run>…] [--format json|mermaid]` adds to the existing graph JSON:

```json
{ "overlay": { "runs": ["r18f3a2c-9b1"],
    "nodes": { "n3": { "ran": 1, "skipped": 0, "ms": 920, "tokens": {"in": 210, "out": 31}, "cost": 0.0006, "attempts": 1, "failures": 0 },
               "n7": { "ran": 0, "skipped": 1 } },
    "edges": { "n3->n4": { "taken": 1 }, "n3->n9": { "taken": 0 } },
    "decisions": [ { "node": "n4", "condition": "intent.kind == \"order_status\"", "result": true } ] } }
```

- **R34** Per node: ran / skipped counts, attempts, failures, wall time, tokens, cost. Per edge: taken counts. For one run, also its decisions.
- **R35** With several runs it aggregates (counts, p50/p95 time, average cost) and lists nodes **never reached** by any of them — the production counterpart of phase 11's coverage.
- **R36** Mermaid output styles taken edges bold and untaken edges dotted, so the path appears in docs and issues without the editor.
- **R37** The phase 10 graph panel and Fabric's run page consume this JSON (their work is in their phases; the JSON is the contract and is versioned).

## 8. The debug adapter (R38 to R42)

`weave dap` speaks the Debug Adapter Protocol over stdio, wrapping the stepper's engine, so F5 in VS Code (and in any DAP editor) debugs a `.loom` file.

| DAP concept | Loom mapping |
|---|---|
| Launch config | `{ "type": "loom", "request": "launch", "script": "${file}", "workflow": "Main", "input": {...}, "effects": "simulate", "models": "scripted\|mock\|live" }` |
| Breakpoints | Lines (verified to the nearest statement), conditional (`break when` expression), function breakpoints on agent/tool names |
| Threads | One per `parallel`/`broadcast` branch |
| Stack | Workflow → `call` chain → block (loop round / branch) |
| Scopes / variables | Variables in scope with provenance in the value's description; agent results expand to attempts, model, tokens |
| Step in / over / out | `into` / `next` / `out` |
| Evaluate | `p <expr>` with the Loom condition language |
| Set variable | `set` (R24 overlay) |
| Custom requests | `loom/why`, `loom/whatif`, `loom/stepBack` |
| Reverse debugging | `stepBack` / `reverseContinue` capability advertised (R23) |

- **R38** The adapter reuses the stepper's engine; there is no debug logic in the adapter beyond protocol mapping.
- **R39** It supports `supportsStepBack`, `supportsConditionalBreakpoints`, `supportsSetVariable`, `supportsEvaluateForHovers`.
- **R40** Defaults to simulated effects and scripted/mock models; launching with `live` models or real effects shows a confirmation with the estimated cost.
- **R41** The VS Code extension contributes the `loom` debugger type, a launch-config provider (F5 on a `.loom` with no config creates one), and a "Debug workflow" CodeLens (phase 10 reserves the command id).
- **R42** A run stopped on a breakpoint shows the phase 9 cost so far and the estimate for the remainder in the debug console.

## 9. Architecture

```
travel/        RunTravel, OverlayJournal (existing)  + ObserveReplay, Divergence, Provenance (new)
explain/       ScriptExplainer (existing)            + DecisionLog, WhyCommand model, WhyRenderer (text/md/json)
debug/         StepGate (seam), Stepper, Breakpoints, Session (record/replay), VarsView, DapServer, DapMapping
whatif/        WhatIf (fork+truncate+change+forward), Changes, TrajectoryDiff, EffectDelta, Report
graph/         RunOverlay (counts from decisions/journal/trace), overlay JSON, Mermaid styling
cli/           WhyCommand, StepCommand, WhatIfCommand, DapCommand; GraphCommand --run
```

- **R43** `ObserveReplay` is the only new way to "execute" a script without effects; `why`, `back`, `whatif`'s forward run and the overlay all use it, so one component carries the "no effects, exact decisions" guarantee and one test suite proves it.
- **R44** No change to the runtime's default behaviour: the `DECISION_POINT` event and `StepGate` are optional collaborators with no-op defaults (R5, R15).

## 10. Sub-phases

| | Delivers | Tests |
|---|---|---|
| 13a | `ObserveReplay`, decision-point event, `Provenance`, `weave why` (path, step, JSON), divergence reporting | `ObserveReplayEqualsRunTest`, `DecisionEventTest`, `WhyTest`, `DivergenceTest`, `RunUnchangedTest` |
| 13b | `weave whatif` (variable/answer/tool/human/failure changes, recorded & scripted models, trajectory/effect diff, `--save`) | `WhatIfTest`, `RecordedModelsReuseTest`, `NeedsModelStopsTest`, `OriginalUntouchedTest` |
| 13c | `--script` and `--prompt`/`--model` changes with live models and spend cap; cost estimate integration (needs phase 9c) | `WhatIfCandidateScriptTest`, `WhatIfLiveCapTest` |
| 13d | Run overlay JSON and Mermaid; aggregate over runs; never-reached list | `OverlayCountsTest`, `OverlayAggregateTest`, `OverlayMermaidTest` |
| 13e | Stepper CLI with `StepGate`, breakpoints, vars, `set`, `back`, session record/replay | `StepperTest`, `BreakpointTest`, `StepBackTest`, `SessionReplayTest` |
| 13f | Debug adapter, extension debugger contribution, docs | `DapProtocolTest`, `DapStepBackTest`, `debugConfig.test.ts` |

## 11. Decisions to confirm (open questions)

1. **Are all values needed for observe-replay in the journal?** `delegate`, `human`, `broadcast`, `handoff`, `sleep`, `halt`, `decide-*` are. Classifier answers (`intentclassify`), `run` task results and tool results must be confirmed to be journaled under step ids (the file `FileRunJournal` and the `Decider`/executor code suggest so for effects; 13a opens with an audit of every statement kind, and adds a **compact optional `trace` record** only where something is missing). Recommendation: do the audit first; add records only for gaps.
2. **Journal size.** Adding `DECISION_POINT` records to every journal would grow it. This spec re-derives instead of storing (R1/R43). Confirm that no decision records are persisted by default.
3. **Stepper default for models.** `mock` (neutral replies) vs prompting the developer for each reply vs `live`. Recommendation: scripted/mock by default with `model <Agent> "<reply>"` to override, and an explicit `--models live` with a cap.
4. **Name.** `whatif` vs `weave replay-run`. Recommendation: `whatif`; leave `replay` for decision-ladder replay as is.
5. **DAP as a later sub-phase.** The CLI stepper already delivers the value; 13f can be deferred. Confirm.
