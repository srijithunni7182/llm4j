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
| `user` | The person the workflow serves cancels or steers their own request | Per the cancel/steer given (drop, or continue with new direction) |
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
| `suspend` | End the run cleanly; resume later | `RunSuspended`, the journal |
| `conclude` | Run a declared wrap-up path (summarise, notify, compensate) and end | new, see 6.3 |
| `ask` | Put the decision to a person | `human_prompt` and the chat channel |

### 5.1 Defaults when a script says nothing

| Reason | Default outcome | Condition |
|---|---|---|
| `operator` | `ask` | the operator may name the outcome in the command |
| `user` cancel | `skip` the step and `conclude` the run | the person dropped the request |
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

Outcomes available inside a handler: `use partial`, `skip`, `retry` (optionally `with <Agent>`), `suspend`, `conclude`, `ask "<question>" -> var`.

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
```

By the CLI for a local run (`weave run ... --steer-via console`), and by an embedding application:

```java
LoomRun run = engine.start(script, "Digest", Map.of("topic", "AI agents"));
run.steer("Researcher", "focus on the EU market");     // by agent name or step id
run.wrapUp("Writer");
run.cancel("Writer", Outcome.USE_PARTIAL);             // the person may name the outcome
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
| Checkpoints / rewind | An interrupted step can be a rewind target like any other. |
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
- Fabric gains no ability to act, so its attack surface is unchanged.

## 12. Phasing

1. **Safety fixes** (independent of every other decision): a cancelled result is never journaled as success (F3); a timeout cancels the agent through its handle (F4).
2. **Library**: `cancel(reason)`, `steer(text, origin)`, `wrapUp()`, `StopReport`.
3. **Runtime**: a handle per active step; the interpreter (section 5) with the default table; journal entries; trace events.
4. **Language**: `steerable`, `streaming`, `reasoning`, `on_stopped`, `intervention { }`, `on_conclude`; `weave check` and `weave audit` rules.
5. **Reaching a run**: the in-process listener for the chat channel and console; the embedding API.
6. **Supervision**: `supervise` rules, ideally under the earned-autonomy ladder.

Each phase is testable on its own with a scripted model, and a mock run (`weave run --mock`) reports the interventions it would take.

## 13. Decisions

| # | Decision | Status |
|---|---|---|
| D1 | Policy lives in Loom (defaults plus script), not in Fabric | **Decided** |
| D2 | Fabric stays observational; its three existing controls stay, none added | **Decided** |
| D3 | Detectors in Fabric do not drive interventions; supervision uses Loom's own signals | **Decided** |
| D4 | Steer and cancel reach a run through Loom's channels (chat, console, embedding API), not Fabric | **Decided** |
| Q1 | Restart or continue a stopped step on resume (section 10) | Open |
| Q2 | Default outcome for a user's cancel: `skip` the step and `conclude`, as in 5.1, or stop and ask | Open |
| Q3 | Should `supervise` rules climb watch, suggest, act under an earned-autonomy ladder | Open |
| Q4 | Keywords (`on_stopped`, `intervention`, `supervise`, `wrap_up`, `steerable`): names to settle before any parser work | Open |
