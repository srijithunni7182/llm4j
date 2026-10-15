# Design Document: Intervening in a running agent from Loom

Status: **draft for review**. Nothing in sections 5 to 9 is implemented. The agent-library half (section 4.1) is built; the Loom half is not.
Syntax shown in `loom` blocks is **proposed and illustrative**. `weave check` would validate it as it validates the rest of the language.

## 1. Overview

ai-agent4j can now be told, from another thread, to **steer**, **pause**, **resume** or **cancel** an agent that is running (`RunHandle`), and, with
`streaming(true)`, to end a model call that is still in progress. Loom does not use any of this yet.

An agent never decides to be cancelled. A stop or a steer always comes from somewhere, for a reason, and the right response depends on that reason. So
the design is not "add a cancel button". It is:

> Loom **issues** an intervention with a **reason**. The agent **reports the state** it was in when it stopped. Loom **interprets** the two together and
> decides what the workflow does next: carry on, retry, resume later, or bring the run to a logical end.

### Principles

1. **The model reasons; Loom governs.** Interventions and their outcomes are decided by declared rules and fixed defaults, never by another model.
2. **Fabric observes.** Removing Fabric must not change what a run does. Fabric shows interventions and their outcomes; it makes none and decides none.
   Its existing ops controls (Pause, Suspend, Stop) stay exactly as they are, and none are added.
3. **Two audiences.** *Ops* care about cost, security and incidents (Fabric). *The people a workflow serves* care about their own request: steering it,
   or dropping it. Steer and cancel belong to the second group and reach a run through the channels Loom already has for talking to a person.
4. **Safe by default.** An unknown situation stops the run in a resumable state or asks a person. It never carries on silently with a missing value.
5. **Nothing in the script says how a person is reached** (as with `human_prompt`): the same script runs with a console, a chat, or an embedding app.

### Out of scope

New Fabric controls or pages that act; detectors in Fabric driving anything; a model deciding how to supervise another model.

## 2. What happens today (read from the code; not run)

| # | Finding | Where |
|---|---|---|
| F1 | `RunControl` (pause, resume, suspend, stop) is checked **between steps** only. A running agent is not interrupted. | `RunControl`, `HarnessExecutor` (`control.checkpoint`) |
| F2 | Nothing in Loom passes a `RunHandle` to an agent, so the new cancel and steer cannot be reached from a workflow. | `HarnessExecutor` (`runAgent.run(contextBriefing)`) |
| F3 | If a cancelled result did come back, the delegate success path would take its final answer ("Run cancelled.") as the step's value and **journal it**; every resume would replay it. | `HarnessExecutor` (success handler) |
| F4 | `runWithTimeout` ends a timed-out step with `future.cancel(true)` on a `CompletableFuture`, which does not interrupt the running thread. The agent likely **keeps running and spending** after "Step timed out". | `HarnessExecutor.runWithTimeout` |
| F5 | A budget that runs out part-way already has a **partial** path: the step keeps its best answer and `on_failure` can handle it. | `HarnessExecutor` (`partial`) |
| F6 | The chat channel (`--ask-via telegram`) is built for **suspended** runs answered at the next `weave tick`; a daemon acts within seconds. Steering a *live* run needs a listener inside the running process. | LOOM_GUIDE, "Answering from Your Phone" |
| F7 | Stop and suspend stay *pending* across parallel branches so every branch ends the same way. | `RunControl.checkpoint` |

F3 and F4 are real hazards the moment anything passes a handle. They are fixed in phase 1 whatever else is decided.

## 3. Why an agent is stopped or steered

Every origin is Loom (the runtime, a declared rule, or a person acting through Loom).

| Reason | Examples | Natural outcome |
|---|---|---|
| `operator` | Pause, Suspend, Stop from Fabric, the CLI, a chat | Whatever the operator chose when asking |
| `user` | The person the workflow serves cancels or steers their own request. A cancel is either *drop it* or *the input was wrong: send it back* (with a reason) | Drop: `skip`/`conclude`. Send back: `rewind` to a declared checkpoint carrying the reason |
| `timeout` | Step `timeout`, a workflow deadline, a schedule window closing | Retry if safe, otherwise wrap up |
| `budget` | Budget low or exhausted (exhausted exists today) | Wrap up with the best answer so far |
| `limit` | Provider rate limit or quota | Pause the run until the reset (exists today) |
| `sibling` | A parallel branch failed (fail-fast), or a race has a winner | Discard the loser's work |
| `supervision` | Loom's own signals: looping, a tool failing repeatedly, context nearly full | Steer once, then swap agent or model, or ask a person |
| `safety` | Injection suspected; the lethal trifecta would complete | Ask a person; never continue silently |
| `shutdown` | Deploy or SIGTERM of the process | Suspend cleanly so nothing is lost |

Supervision uses signals Loom already has in-process (step counters, retries, budgets, the effect journal). It does **not** read Fabric's detectors.

## 4. What the agent reports

### 4.1 The library (ai-agent4j)

Built (commits on `ccr-dcc3f74a-sevyba`): `RunHandle.steer / pause / resume / cancel`; `AgentResult.isCancelled()`; `ReActAgent.Builder.streaming(true)`
so a steer or cancel ends a call in progress at once; listener events `onSteered`, `onPaused`, `onResumed`, `onCancelled`, and the streaming deltas.

To be added, so that Loom can interpret a stop:

- `RunHandle.cancel(reason)` and `steer(text, origin)`, recording who asked and why.
- `RunHandle.wrapUp()`: the agent makes **one more model call with tools disabled** (`ToolChoice.NONE`) and returns its best answer. This is the graceful
  way to end a step without discarding its work.
- `AgentResult.stop()`, a `StopReport`, present when the run ended through its handle:

```java
record StopReport(
    String reason,          // "operator", "timeout", "budget", ... as given to cancel(reason)
    String origin,          // who or what asked
    Phase phase,            // MODEL_CALL, TOOL_CALL, AWAITING_APPROVAL, BETWEEN_STEPS
    String partialAnswer,   // the last thing the agent said or saw
    List<AgentStep> steps,  // what it had done
    boolean mayHaveChangedThings)   // a tool that changes state ran, or was running
```

`mayHaveChangedThings` is what makes a retry safe or unsafe. It uses the same effect knowledge Loom already keeps for tasks (`TaskEffect`) and approved tool calls.

## 5. How Loom interprets a stop

Loom receives (reason, StopReport) and chooses one **outcome**:

| Outcome | Meaning | Reuses |
|---|---|---|
| `use partial` | Carry on with the partial answer, marked degraded | the budget `partial` path (F5) |
| `skip` | The step produces no value; `on_failure`/a declared default supplies one | `on_failure` |
| `retry` | Run the step again (same agent, or an alternate agent or model) | `retry`, `backoff` |
| `rewind` | Cancel this step and send the run back to an earlier **declared checkpoint**, carrying a reason as a hint, so the earlier steps rework their output | `checkpoint`, `rewind`, `carrying` (see 6.4) |
| `suspend` | End the run cleanly; resume later | `RunSuspended`, the journal |
| `conclude` | Run a declared wrap-up path (summarise, notify, compensate) and end | new, see 6.3 |
| `ask` | Put the decision to a person | `human_prompt` and the chat channel |

### 5.1 Defaults when a script says nothing

| Reason | Default outcome | Condition |
|---|---|---|
| `operator` | `ask` | the operator may name the outcome in the command |
| `user` cancel, no target | `skip` the step and `conclude` the run | the person dropped the request |
| `user` cancel with `back to <checkpoint>` | `rewind` to that checkpoint, carrying the person's note | only a checkpoint the script declared; within `at most N times` |
| `timeout` | `retry` | only if `mayHaveChangedThings` is false; else `use partial` |
| `budget` | `use partial` | exists today |
| `limit` | `suspend` until reset | exists today |
| `sibling` | discard | no value, no failure |
| `supervision` | per rule; with no rule, none | |
| `safety` | `ask` | never `use partial` |
| `shutdown` | `suspend` | |

A stop that carries `mayHaveChangedThings = true` is **never retried automatically**; it goes to `ask` or `use partial`, as a task whose outcome is unknown does today.

### 5.2 Three layers of policy

1. **System defaults** (the table above).
2. **The script**, declared and reviewable like `budget` (section 7).
3. **The person at the moment**, who can name an outcome in the command. The choice is recorded.

### 5.3 Four problems, four tools

| What is wrong | Tool | Effect on the run |
|---|---|---|
| The agent's **reasoning** is off | **steer** | Same conversation, corrected. The workflow is untouched. |
| This **attempt** is bad but its input is fine | **cancel, then `retry`** this step with a hint | Only this step runs again. |
| The **input** to this step (an earlier step's output) is wrong | **cancel, then `rewind`** to a declared checkpoint, carrying the reason | The earlier steps run again with the reason; this step runs again after them. |
| The request is **not wanted** | **cancel, then `skip` or `conclude`** | The step produces nothing; the run ends tidily or carries on. |

Steer corrects an agent's thinking. It never alters the workflow. Changing the workflow is what a cancel with an outcome is for.

## 6. Stopping and steering

### 6.1 Two kinds of stop

- **abort**: the work is no longer wanted. The call is closed; partial output may be kept or dropped per the outcome.
- **wrap up**: finish now with what exists. One final model call, tools off. Used for `budget`, `timeout`, deadline-near, and "that is enough".

### 6.2 Steering

- **Sources:** a person (chat, console, embedding app), and script-declared supervision. Never Fabric.
- **Targeting:** parallel branches run several agents at once, so Loom keeps a map from step id to handle. A steer names an agent or a step. A steer sent
  between steps waits for the next step that matches.
- **Persistence:** a steer is **journaled under the step** (`<stepId>#steer.N`). If the process dies and the step re-runs, the steers are replayed into the
  retry's briefing. Otherwise a resumed step would silently forget the person's direction.
- **Trust:** a steer is a new input to the agent. It arrives only over an authenticated channel (the chat allowlist, the signed admin command, the embedding
  app), is size-limited, passes the agent's own `guard` checks (PII, bias) like any task text, and is audited.
- **Pause** also pauses running agents at their next checkpoint (so spending stops), and paused time does not count against a step's `timeout`.

### 6.3 Concluding a run

`conclude` runs a declared path when a run is ending for a reason other than success, so a stopped run ends tidily:

```loom
workflow Digest(topic) {
    delegate "Find today's news on {topic}" to Researcher -> findings
    delegate "Write the digest"             to Writer     -> draft
    on_conclude {                       // runs when the run is being brought to an end, with _stop in scope
        note "Digest ended early ({_stop.reason}); sending what exists"
        alt (draft != "") { handoff "Publish as partial" to Publisher }
    }
}
```

### 6.4 Cancel and send back (rewind)

A person who sees that the *input* to an agent is wrong can cancel its loop and send the run back to an earlier step with a reason, so the earlier agents rework
their output. This is the existing rewind, with a new trigger: today a rewind fires from a `when (condition)` after a step, from an `on_failure` block, or from
`weave rewind` on a run at rest. Here it fires from a **live cancel**.

Rules, all inherited from the existing rewind unless stated:

- **Only declared checkpoints.** The person may name only a `checkpoint` the script declared (or `start`, which every workflow has). Naming anything else is
  refused, and the refusal lists the checkpoints available. (`weave rewind` from the command line, an operator acting on a run at rest, still accepts any step id.)
- **`at most N times` is required**, and the run-wide cap (`--max-rewinds`) still applies.
- **The reason is carried** into the next attempt as a variable (`carrying feedback = "{_stop.note}"`). Loom does not inject it into a prompt. The earlier step's
  prompt must mention the variable, as in the existing example (`Feedback so far: {feedback}`).
- **`side effects: ask first | keep | repeat`** decides what happens to anything the replaced attempt already did. The cancelled step reports whether it may have
  changed something (`_stop.changed`), and that feeds the policy.
- The old attempt stays in the journal as history. Model calls run again as a new attempt; identical side effects are not repeated. Tokens already spent stay counted.
- A rewind cannot leave a `parallel` branch or a `for each` body, and cannot point at a later checkpoint. `weave check` rejects both, with the line.

The person's note is an **input to a different agent** (the earlier one). It is therefore checked by that agent's `guard`, size-limited and audited (section 11).

## 7. The language (proposed)

### 7.1 Marking an agent

```loom
agent Researcher {
    model: "gemini-2.5-flash"
    tools: [Search]
    streaming: true            // the reply is shown as it is written; a steer or cancel ends a call in progress
    reasoning: true            // ask the model to show its reasoning, where it can (shown as a summary)
    steerable: true            // the person this run serves may steer it. Off by default.
}
```

`steerable` is opt-in so that today's scripts behave exactly as before and `weave audit` can list every input channel an agent has.

### 7.2 Script-level defaults

```loom
intervention {                                   // overrides the system defaults of section 5.1
    timeout:     wrap_up                         // finish with what it has
    budget:      wrap_up when (_budget.remaining < 10%)
    sibling:     discard
    shutdown:    suspend
    safety:      ask
    operator:    ask
}
```

### 7.3 A step's own handler

A handler hangs off the step like `on_failure` and `on_exhausted`. `_stop` is in scope: `_stop.reason`, `_stop.phase`, `_stop.partial`, `_stop.changed`.

```loom
workflow Digest(topic) {
    delegate "Summarise {item.url}" to Writer -> summary
        timeout 90s
        retry 2 backoff 2s
        on_stopped {
            alt (_stop.reason == "timeout") {
                alt (_stop.changed == "no") { retry }          // nothing happened yet; safe to run again
                else                        { use partial }    // something already changed; do not repeat it
            }
            alt (_stop.reason == "user") { skip }
        }
}
```

Outcomes available inside a handler: `use partial`, `skip`, `retry` (optionally `with <Agent>` or `carrying name = "value"`), `rewind to <checkpoint> ...`,
`suspend`, `conclude`, `ask "<question>" -> var`.

Sending the run back when a person says the input was wrong (the `rewind` phrase is the existing one, with a new place to sit):

```loom
workflow Report(topic) {
    checkpoint collected  starting with feedback = "none"

    delegate "Collect sources on {topic}. Feedback so far: {feedback}" to Collector -> data
    delegate "Write the report from {data}" to Writer -> draft
        on_stopped {
            alt (_stop.reason == "user") {
                rewind to collected
                    at most 2 times
                    carrying feedback = "{_stop.note}"        // what the person said was wrong with the input
                    side effects: ask first
                    if it still fails { human_prompt "Still not right. Continue anyway? (yes/no)" -> go }
            }
        }
}
```

`_stop.note` is the person's text. `rewind to` here may name only a checkpoint declared in the script (`weave check` enforces it).

### 7.4 Supervision rules (symbolic, never a model)

```loom
supervise Researcher {
    when looping(3)               { steer "You are repeating yourself. Use a different source." once }
    when tool_failing(Search, 3)  { retry with BackupResearcher }
    when context > 90%            { wrap_up }
}
```

The signals (`looping`, `tool_failing`, `context`) come from the runtime, not from Fabric. Open question 3 asks whether a `supervise` rule should start
in `watch` (record what it would have done), then `suggest`, then `act`, using the existing earned-autonomy ladder.

### 7.5 How a person reaches a run (not in the script)

By the chat channel, the same as questions today:

```text
[today/Researcher] working: "Find today's news on AI agents"

Reply: K7F3QX steer <text> | K7F3QX wrap up | K7F3QX cancel
       K7F3QX cancel back to collected: the sources were stale
```

`cancel back to <checkpoint>: <reason>` sends the run back to a declared checkpoint, carrying the reason. The reply is refused, with the list of checkpoints, if the
name is not one the script declared or if no `on_stopped` handler (or `intervention` default) allows a rewind from this step.

By the CLI for a local run (`weave run ... --steer-via console`), and by an embedding application:

```java
LoomRun run = engine.start(script, "Digest", Map.of("topic", "AI agents"));
run.steer("Researcher", "focus on the EU market");     // by agent name or step id
run.wrapUp("Writer");
run.cancel("Writer", Outcome.USE_PARTIAL);             // the person may name the outcome
run.cancel("Writer", Outcome.rewindTo("collected", "the sources were stale"));   // declared checkpoints only
```

(`LoomRun` and `Outcome` are proposed names.) Steering a live run needs a listener in the running process, so this works with `weave daemon` or an
embedding app, not only with the heartbeat (F6).

## 8. Interaction with existing features

| Feature | Behaviour |
|---|---|
| `retry` / `backoff` | A stop from `timeout` is retryable per 5.1; a stop from `user`, `sibling`, `safety` and `shutdown` is **never** retried. |
| `timeout` | Fires `cancel("timeout")` through the handle (fixes F4), then the handler decides. |
| `budget` | An aborted call is billed for what was generated. Wrap-up is one more call and is charged and checked like any other. |
| Rate limit / quota | Unchanged: suspend until the reset. |
| Approvals, `human_prompt` | Waiting for a person suspends the run and holds no thread, so a stop applies at resume. A cancel for a step awaiting approval ends that wait. |
| `parallel` | Each branch has its own handle. `sibling` stops follow F7: every branch ends the same way. |
| Loops | A stop inside a loop body ends that iteration per its outcome; `max` still bounds the loop. |
| Earned autonomy | A steered, wrapped-up or cancelled case is recorded as an **intervention**, not as the agent's own proposal, so the measurement stays honest. |
| Checkpoints / rewind | A cancel can trigger a rewind (6.4). The cancelled step is part of the replaced attempt. The rewind caps (`at most N times`, `--max-rewinds`) apply. |
| Journal | A stopped step is **never** journaled as a success. It records the stop (reason, outcome) and any steers. |

## 9. Observability (Fabric, read-only)

New trace events: `agent.steered`, `agent.paused`, `agent.resumed`, `agent.wrapup`, `agent.cancelled`, and `step.stopped` (with reason and chosen outcome).
Fabric displays them in the run's timeline and counts them. **No Fabric controls change.** The existing Pause, Suspend and Stop stay as the ops channel.

## 10. Journal and resume

A stopped step records its stop. On resume Loom either:

- **restarts** the step from the beginning (today's behaviour for any unfinished step), with any journaled steers added to its briefing; or
- **continues** from the agent's saved conversation, which needs the agent's steps persisted in the journal.

| | Restart | Continue |
|---|---|---|
| Cost on resume | Pays again for the work done | Pays only for what remains |
| Correctness | Always consistent with current data | Earlier tool results may be stale |
| Complexity | None (exists) | Persist and reload the agent conversation, version it |
| Steers | Replayed into the briefing | Already in the conversation |

**Open question 2.** Likely answer: restart by default, `continue` opt-in per step.

## 11. Security

- Steering and cancelling are authenticated, allowlisted and audited. Steer text is checked by the agent's guards.
- `weave audit` lists every `steerable` agent as an input channel and flags a steerable agent that also holds private data and an outbound tool (the lethal trifecta).
- `safety` stops never resolve to `use partial` silently.
- A cancel-back note is text from a person that reaches **another** agent. It passes that agent's `guard`, is size-limited and audited, and may name only declared checkpoints, so a person cannot make a run revisit a step the script author did not allow.
- Fabric gains no ability to act, so its attack surface is unchanged.

## 12. Phasing

0. **Make room** (behaviour-preserving, no feature): characterization tests, then split `HarnessExecutor` along the seams in section 15. New intervention code must not land in
   a 3,000-line class.
1. **Safety fixes** (independent of every other decision): a cancelled result is never journaled as success (F3); a timeout cancels the agent through its handle (F4).
2. **Library**: `cancel(reason)`, `steer(text, origin)`, `wrapUp()`, `StopReport`.
3. **Runtime**: a handle per active step; the interpreter (section 5) with the default table; journal entries; trace events; cancel-and-rewind (6.4).
4. **Language**: `steerable`, `streaming`, `reasoning`, `on_stopped`, `intervention { }`, `on_conclude`; `weave check`, `weave audit`, `weave graph`, `weave explain`.
5. **Reaching a run**: the in-process listener for the chat channel and console; the embedding API.
6. **Supervision**: `supervise` rules, ideally under the earned-autonomy ladder.

**Definition of done for every phase:** the code, the tests of section 14 for that phase, and the ecosystem rows of section 13 that the phase touches are all green.
Docs and tooling ship *with* the parser change (phase 4), never before it: the guide's examples are parse-tested, so documenting syntax that does not parse yet would fail the build.

## 13. The wider Loom ecosystem

Loom is more than the runtime. Each piece below tracks the language or the run's events, and each has a guard (a test) that fails when it drifts.

### 13.1 Inventory

| Piece | Where | What changes | Phase |
|---|---|---|---|
| Lexer, parser, AST | `loom/lexer`, `parser`, `ast` | New keywords and statements (`on_stopped`, `on_conclude`, `intervention`, `supervise`, `steerable`, `streaming`, `reasoning`, `wrap_up`, the outcomes). A rewind may sit in `on_stopped`. | 4 |
| `weave check` | `execution/ScriptValidator`, `WorkflowLint` | New rules (13.2) | 4 |
| `weave audit` | `security/SecurityAudit`, `docs/security/weave-audit.md` | Steerable agents listed as an input channel; steerable plus private data plus an outbound tool is a lethal-trifecta finding; OWASP mapping | 4 |
| `weave graph` | `graph/GraphBuilder`, `Kinds`, `NodeDescriber`, `MermaidRenderer`, `GraphJson` | Draw `on_stopped` and `on_conclude` blocks and a dashed cancel-back edge to the checkpoint; mark steerable agents; bump the graph JSON schema version | 4 |
| `weave explain` | `explain/ScriptExplainer` | Plain-language lines for the new constructs | 4 |
| `weave run`, trace | `execution/TraceEvent`, `cli` | New events (section 9); `--steer-via`; a mock run reports the interventions it would take | 3, 5 |
| Journal, `weave timeline`, `weave rewind` | `runtime/RunJournal`, `FileRunJournal`, `JdbcRunJournal` | New entry kinds (stop, steer); a journal format stamp; `timeline` shows stops, steers and cancel-backs. A journal written with these entries cannot be read by an older `weave` (as with rewind), so the format is versioned and an old build refuses it | 3 |
| `weave eval`, eval4j | `loom/eval`, `eval4j` assertions | Scenarios can script interventions; `AgentResultAssert` gains `wasCancelled` and the `REJECTED_BY_HOOK` outcome; the dashboard's outcome counts and `WorkflowGraph` follow | 3, 4 |
| Conformance Test Kit | `src/loom/ctk` | See 13.3 | 3, 4 |
| VS Code extension | `src/loom/vscode-loom` | See 13.4 | 4 |
| Observability (read-only) | `loom-inspect-core` (`Semconv`, `RunState`), Fabric pages, `docs/OBSERVABILITY.md` | New attributes and events are displayed in the run timeline and counted. **No new Fabric action**; a test pins the set of controls. Detectors unchanged | 3 |
| Guides | `LOOM_GUIDE.md`, `LOOM_PROMPT.md`, `docs/guide/` (a new chapter; 06, 07 and 09 touched), `docs/security/securing-workflows.md`, `llms.txt`, READMEs, wiki, changelogs | Each new example is added to the parse-tested example resources | 4 |
| Agent skills | `.claude/skills/llm4j-workflow-guide`, `llm4j-loom-deployer`, and the copies bundled into the jar | Teach the new constructs and the steer/cancel/rewind choice; the drift tests keep the bundled copies equal | 4 |
| `weave init` templates | `init/Templates` | One starter that shows `on_stopped` (optional) | 4 |
| Compatibility docs | `docs/API_COMPATIBILITY.md`, `VERSION_MATRIX.md`, migration notes | Journal format, graph schema, new trace events | 3, 4 |

### 13.2 New `weave check` rules

- A `rewind` that sits in `on_stopped` may name only a **declared checkpoint** (or `start`), never a later one, and never leave a `parallel` branch or a `for each` body. The message lists the checkpoints available.
- `at most N times` is required on every rewind, as today.
- `_stop` is in scope only inside `on_stopped` and `on_conclude`; its fields are checked.
- Outcomes are valid in the place used (`use partial` needs a value-producing step).
- Warn: a checkpoint is a rewind target but the steps after it never read the variable it carries (the hint would be dropped).
- Warn: `retry` in `on_stopped` for a step whose agent holds a tool that changes state, without a `_stop.changed` test.
- Warn: `steerable: true` without `streaming: true` (a steer is then slow to take effect).
- `supervise` signals exist and name agents and tools that exist.

### 13.3 Conformance Test Kit (CTK)

Current state, read from the repo: the CTK holds canonical `.loom` scripts, expected trace JSON and mock fixtures. A trace step is `TraceStep(kind, taskName, agentName,
payload, outputVariable, outputValue, subSteps, timestamp)`; the comparator checks kind, names and variable, and ignores `outputValue` and timestamps. Its own runner is
still a stub; the Java runtime is checked against it by `TaskCtkConformanceTest` in the loom module. Two things to confirm before adding scenarios: that the existing
canonical scripts parse with the current parser (several use an older `delegate ... -> Agent -> var` form), and who consumes the CTK besides the Java runtime.

Plan:

- **Interventions must be scripted, not timed.** A conformance run cannot depend on when a human acts. Mock fixtures gain an `interventions` list: "at step N, in phase P,
  issue {kind, reason, note}", and the mock agent returns the `StopReport` the fixture states (phase, partial answer, `changed`). This makes every scenario deterministic.
- **Trace model.** New step kinds: `stop` (payload: reason and outcome as sorted-key compact JSON, like a task's arguments), `steer` (the note), and `rewind` (the checkpoint and the
  attempt number). A retry or rewind adds a new attempt as `subSteps`. The comparator checks kind, reason, outcome, checkpoint and attempt, and compares the note exactly because
  it comes from the fixture.
- **One scenario per row of the default table (5.1)**, so the table is normative across runtimes: timeout retries when nothing changed and uses the partial when something did;
  user drop; user cancel-back; sibling discard; shutdown suspend; safety asks; budget wrap-up; steer continues; wrap-up; conclude.
- **Negative scenarios:** a rewind to an undeclared checkpoint is refused; a cancelled step is never recorded as a success; a retry is refused when `changed` is true.
- **What is implementation-defined and not tested:** when exactly a streaming call is cut off, and wall-clock timing.
- **Feature tags.** A runtime declares which sets it supports (`core`, `tasks`, `intervention`), so a runtime without interventions is not failed by them.
- **A sabotage check** (as `loom-vscode-graph` did in its evidence): change one default and confirm a CTK scenario fails.

### 13.4 VS Code extension (`vscode-loom`)

| Area | Files | Change | Guard |
|---|---|---|---|
| Grammar | `syntaxes/loom.tmLanguage.json` | Add the new keywords to the keyword pattern; highlight `_stop` and its fields | `grammar.test.ts` |
| Snippets | `snippets/loom.code-snippets` | A steerable agent, `on_stopped` with a rewind, an `intervention` block, `supervise` | `snippets.test.ts` (each snippet must parse) |
| Language server | `src/lsp/server.ts`, `parseDocument.ts`, `ladderCompletion.ts` | Hover text for the new keywords; completion of `_stop.` fields, of the outcomes inside a handler, and of **only the declared checkpoints** after `rewind to`; outline entries | `parseDocument.test.ts`, a new completion test |
| Diagnostics | `src/check/*` | New `weave check` rules arrive through the existing runner; confirm the line mapping and the severity of the new warnings | `check.test.ts`, `diagnosticsWiring.test.ts` |
| Graph | `src/graph/*`, `media/graph-render.js` (synced from `src/loom/graph-render`), `views` | Draw handler blocks and the cancel-back edge; the legend and inspector explain them; PNG export includes them | `viewmodel.unit.test.js`, `webview.browser.test.js`, `sync.test.ts` |
| Commands | `src/commands/runWorkflow.ts` | Optional: pass `--steer-via console` for a local run. No new ops UI | `extension.integration.test.ts` |
| Packaging | `package.json`, `CHANGELOG.md`, `README.md`, `docs/PUBLISHING_VSCODE.md`, `scripts/build-vsix.sh`, `verify-vsix.sh` | Version, changelog, README examples, the bundled `weave` rebuilt | `manifest.test.ts`, `assets.test.ts`, `docs.test.ts`, `verify-vsix.sh` |

The extension bundles its own `weave`, so extension and runtime stay matched. It should still tolerate unknown fields in the graph JSON, so an older extension does not break on a newer graph.

### 13.5 Compatibility

- **Journal:** a format stamp; an older `weave` refuses a journal with intervention entries rather than skipping past them. Old journals read as before.
- **Graph JSON:** a schema version; additive fields.
- **Trace events and OTel conventions:** additive. Consumers ignore unknown events.
- **Scripts:** every new construct is opt-in. A script without them behaves exactly as today.

## 14. Test plan

Rules: tests come with the code, in the same change. Everything runs without a network (scripted models, mock servers, the existing `FakeTelegram`). Interventions in tests use
latches and fixtures, never sleeps. Where a bug is fixed (F3, F4) a regression test is written first and seen to fail.

| Level | What is tested | Where |
|---|---|---|
| Library | `cancel(reason)`, `steer(text, origin)`, `wrapUp()` (one last call, tools off), `StopReport` fields and phases, `mayHaveChangedThings` | `ai-agent4j` (extends `RunHandleTest`, `StreamingAgentTest`) |
| Parser | Every new construct parses; round-trip; errors carry the line; negative cases | `loom/parser` |
| `weave check` | A positive and a negative case per rule in 13.2, with the exact message | `loom/validation` |
| Interpreter table | Parameterized over reason, phase and `changed`: every row of 5.1; and "`changed` is never retried" | `loom/execution` (new `StopInterpreterTest`) |
| Runtime, with a handle | Cancel mid-step reaches the handler; cancel-back rewinds to a declared checkpoint carrying the note; the attempt cap; a refused undeclared checkpoint; side-effects `ask first` with `changed`; **F3** a cancelled result is not journaled as success; **F4** a timeout stops the agent (no further model calls after the timeout); sibling stop in `parallel`; wrap-up returns a final answer | `loom/resume`, `loom/rewind`, new `loom/intervention` |
| Journal and resume | Steers and stops are journaled; a crash mid-step then resume replays the steers into the retry; the format stamp; an old build refuses a new journal; `timeline` output | `loom/resume` |
| Concurrency | Randomized interleavings of steer, pause, resume and cancel against a scripted model. Invariants: the result is exactly completed or cancelled; no tool runs after a cancel; no deadlock; state transitions are legal | new `loom/intervention` property test |
| Security | Only allowlisted senders can steer or cancel; steer text and the cancel note pass the agent's `guard`; oversized text is rejected; every intervention is audited; `weave audit` golden output for a steerable agent and for the trifecta case; **Fabric exposes no new action** (the set of controls is pinned) | `loom/security`, `loom/channel`, `loom-fabric` |
| Channel | `K7F3QX steer ...`, `wrap up`, `cancel`, `cancel back to X: reason` are parsed; a bad checkpoint is refused with the list; a live listener (daemon) acts without a heartbeat | `loom/channel` (with `FakeTelegram`) |
| Observability | Events are emitted in order with reason and outcome; the OTel mapping; `RunState` shows them; the Fabric page renders them read-only | `loom-inspect-core`, `loom-fabric` |
| Graph | Golden JSON and Mermaid for handlers and the cancel-back edge; the JS view-model and browser tests | `loom/graph`, `graph-render/test` |
| eval4j | The new assertions; dashboard outcome counts include `REJECTED_BY_HOOK` | `eval4j`, `eval4j-report` |
| CTK | The scenarios of 13.3 run against the Java runtime and compare to the stored traces; comparator unit tests for the new kinds; the sabotage check | `ctk`, `loom` (`...CtkConformanceTest`) |
| VS Code | Grammar tokens, snippets parse, completion, hover, diagnostics wiring, graph view-model, packaging | `vscode-loom/test` |
| Docs and skills | Each new guide example is in the parse-tested resources; `LOOM_PROMPT.md` examples parse; the skill drift tests pass | `DocumentedExamplesTest`, `DeployerFabricDriftTest`, skill tests |
| Regression for the refactor | Golden traces for areas thin on tests before any move (parallel, rate-limit suspend, budget windows, rewind); the whole module suite before and after each step | section 15 |

## 15. Refactoring `HarnessExecutor`

### 15.1 Why

`HarnessExecutor` is 3,171 lines and about 190 methods. Reading it, it holds at least ten separate jobs: configuration (about 40 setters), script validation, tracing, budgets and rate limits,
context wiring, knowledge indexing, tool and client resolution, the statement interpreter, the delegate loop with its retries and failure handling, the run lifecycle (start, suspend,
resume), and autonomy wiring. Intervention touches the delegate loop, tracing, the journal and validation, so adding it to this class would make it worse and hard to review.

What is at stake: 121 files reference it; the loom module has about 1,777 tests; `GetViralExecutor` (in the examples) extends it and relies on the protected hooks `customizeAgent`,
`agentForDelegate`, `beforeDelegateExecution`, `afterDelegateExecution`, `onDelegateError`, `onDelegateReplayed` and `getMemoryEngine`.

### 15.2 Approach

- **Behaviour-preserving.** No public or protected signature changes. The existing tests are the safety net, plus new characterization tests (section 14) where coverage is thin.
- **Follow the pattern already in this package.** `TaskRunner`, `Rewinder`, `Decider`/`LadderDecider`, `ApprovalGate`, `AgentMemory`, `AgentGuard`, `ContextWiring` and `RunControl`
  are already collaborators that the executor owns.
- **Narrow dependencies.** A collaborator gets a small interface (journal, trace, clock, variable context) and not the whole executor. Otherwise the god class is only moved.
- **One extraction per change**, each with the full loom suite green, the CTK, and the examples compiling (`GetViralExecutor`).
- **New intervention code goes into new classes** (`InterventionCoordinator`: the handle registry and the steer/cancel API; `StopInterpreter`: the table and the handlers), never into `HarnessExecutor`.

### 15.3 Proposed extractions, in order

| # | New class | Takes from `HarnessExecutor` | Approx. lines | Risk |
|---|---|---|---|---|
| 1 | `ExecutorValidation` | `checkProviders`, `checkAgentSettings`, `checkMemory`, `checkVoice`, `checkCompactors`, `checkGuard`, `checkKnowledge`, `checkToolsAndApprovals`, `validationContext` | 480 | Low: pure checks |
| 2 | `RunTracing` | `trace`, `traceAdapter`, `statsAdapter`, `traceControl`, `observable`, trace and suspension listeners | 300 | Low |
| 3 | `BudgetCoordinator` | `setUpBudgets`, `metered`, `journalUsage`, `restoreSpend`, `newBudget`, `budgetsFor`, `onLimit`, `askForMore`, `refills`, `runUnderLimits`, `spend` | 340 | Medium: shared state, synchronized |
| 4 | `AgentAssembler` | prompt, persona and skill resolution, `applyContext`, tool and client resolution, `customizeAgent` | 350 | Medium: protected hooks |
| 5 | `DelegateRunner` | `executeDelegate`, the retry/backoff/timeout loop, failure handling, `runWithTimeout`, `handleExhausted` | 420 | High: the heart of the run, and where intervention lands |
| 6 | `StatementInterpreter` | `executeStatement`, `runBlock`, `forkWith`, `executeForEach`, `executeCall` | 400 | High |
| 7 | `RunLifecycle` | `initialize`, `beginRun`, `recordSuspension`, `shutdown`, replay and restore | 450 | High |
| 8 | `ExecutorConfig` | the setters, behind the same public methods | 250 | Low |

The target is a coordinator of about 600 lines that wires these together and keeps the public and protected API. Steps 1 to 3 and 8 are independent of intervention and can be done first.
Steps 4 to 6 must be done before phase 3, because they are where the intervention code lands.

### 15.4 Safeguards

- Before step 3 and again before steps 5 to 7, add golden-trace characterization tests for parallel branches, rate-limit suspend and resume, budget windows, rewind and a failing step with `on_failure`.
- Run the full loom module, the CTK, `eval4j`, and compile the examples after every step.
- Track the line count of `HarnessExecutor` and of each new class in the PR description, so progress is visible. No new class over about 500 lines.
- If a step cannot be made behaviour-preserving, stop and write down why before going on.

## 16. Decisions

| # | Decision | Status |
|---|---|---|
| D1 | Policy lives in Loom (defaults plus script), not in Fabric | **Decided** |
| D2 | Fabric stays observational; its three existing controls stay, none added | **Decided** |
| D3 | Detectors in Fabric do not drive interventions; supervision uses Loom's own signals | **Decided** |
| D4 | Steer and cancel reach a run through Loom's channels (chat, console, embedding API), not Fabric | **Decided** |
| D5 | A cancel-and-send-back may name only checkpoints the script declared | **Decided** |
| D6 | Fabric exposes no new action; steer and cancel reach a run through Loom's channels | **Decided** |
| Q1 | Restart or continue a stopped step on resume (section 10) | Open |
| Q2 | Default outcome for a user's cancel: `skip` the step and `conclude`, as in 5.1, or stop and ask | Open |
| Q3 | Should `supervise` rules climb watch, suggest, act under an earned-autonomy ladder | Open |
| Q4 | Keywords (`on_stopped`, `intervention`, `supervise`, `wrap_up`, `steerable`): names to settle before any parser work | Open |
| Q5 | Do interventions belong to the conformance contract (section 13.3) as a tagged feature set, or only to the Java runtime for now | Open |
| Q6 | Confirm that a live steer/cancel UI in VS Code is out of scope (the extension only highlights, completes, draws and checks) | Open |
| Q7 | Do the `HarnessExecutor` refactor (phase 0) before any intervention runtime work, as one reviewed series of small changes | Proposed: yes |
