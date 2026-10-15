# 15. Steer, stop and supervise a running workflow

*Optional. Read it when a person will watch a run and may need to step in, or when an agent can loop, run long or spend too much.*

A run is not always right the first time. This chapter is about what you can do **while it is going**, and what the workflow does next. There are four ways to step in, and they are not interchangeable:

| What is wrong | Use | What happens to the run |
|---|---|---|
| The agent's **thinking** is off | **steer** | The same conversation is corrected; the workflow is untouched. |
| It should **finish now** with what it has | **wrap up** | One last model call with tools off; that answer is its result. |
| This attempt is **not wanted** | **cancel** | The step stops, and the script (or the person) says what happens next. |
| The **input** to this step was wrong (an earlier step's output) | **cancel, sent back** | The run goes back to a checkpoint the script declared, carrying the person's reason; the earlier steps run again. |

A steer corrects reasoning and never changes the workflow. Changing the workflow is what a cancel with an outcome is for.

## 1. Mark the agents a person may steer

<!-- weave check -->
```loom
agent Researcher {
    model: "claude-sonnet-5-5"
    tools: [calculator]
    streaming: true      // the reply is shown as it is written; a steer or stop ends a model call in progress
    reasoning: true      // ask the model to show its reasoning where it can
    steerable: true      // the person this run serves may steer it. Off unless you say so.
}

workflow Brief(topic) {
    delegate "Research {topic}" to Researcher -> findings
    note "{findings}"
}
```

- `steerable` is **off by default** and applies to a person's steer only. Wrap up, cancel and pause do not need it, and neither does a `supervise` rule (the script declared it).
- `weave check` warns about `steerable` without `streaming` (a steer then waits for the model call in progress to finish) and about `reasoning` without `streaming`.
- A steer is checked by the agent's `guard`, limited to 2,000 characters, journaled under the step, traced and audited. A step that restarts after a suspension is told what the person said.
- `weave audit` lists every steerable agent as an input channel (`LA18`), and rates it medium when that agent also reaches private data and can act. Say who may steer, and leave `steerable` off where nobody needs it.

## 2. Say what happens when an agent is stopped

A stopped agent is never a silent failure and never counted as a success by accident. The run chooses one outcome:

| Outcome | Meaning |
|---|---|
| `use partial` | Keep what the agent had, marked as incomplete. |
| `skip` | The step produces nothing; the run goes on (its `on_failure` runs). |
| `discard` | Drop the step quietly. |
| `retry` | Run it again, once, and only if the agent changed nothing outside the run (it only read). |
| `suspend` | Pause the run; the stopped step starts again on resume. |
| `conclude` | End the run. |
| `ask` | Put it to a person. |
| `rewind to <checkpoint>` | Go back to a checkpoint the script declared. |

Order of precedence: the outcome a person names → the step's own `on_stopped` → the script's `intervention` default → the built-in table. With nothing said, a stop by a person is put to a person, and if nobody can be asked the run is suspended (resume it later).

<!-- weave check -->
```loom
intervention {
    timeout:  retry          // reasons: user, operator, timeout, budget, limit, sibling, supervision, safety, shutdown
    sibling:  discard
}

agent Collector { model: "claude-sonnet-5-5"  system: "Collect sources on the topic." }
agent Writer    { model: "claude-sonnet-5-5"  system: "Write the report."  streaming: true  steerable: true }

workflow Report(topic) {
    checkpoint collected  starting with feedback = "none"

    delegate "Collect sources on {topic}. Notes so far: {feedback}" to Collector -> data
    delegate "Write the report from {data}" to Writer -> draft
        timeout 90s
        on_stopped {
            alt (_stop.reason == "user") {
                rewind to collected at most 2 times carrying feedback = "{_stop.note}" side effects: keep
            }
            alt (_stop.changed == "no") { retry } else { use partial }
        }
}
```

Inside `on_stopped`, `_stop` has `reason`, `note` (the person's own words), `kind` (`cancel` or `wrap_up`), `phase`, `partial` (the last thing the agent said), `changed` (`yes` if it may already have changed something outside the run) and `origin`. The first outcome reached decides. A `rewind` there may name only a checkpoint the script declared and needs `at most N times`; `weave check` rejects a misspelt `_stop` field and warns about `retry` for an agent with tools that does not test `_stop.changed`.

Decide with the user, for each step that matters: if it is stopped, is the partial answer useful (`use partial`), is the step optional (`skip`), can it be run again safely (`retry` only for read-only agents), or must the earlier input be reworked (`rewind`)?

## 3. Rules that watch an agent: `supervise`

When something an agent *does* can be counted, a rule can act without a person. Every signal is a count, never a model's opinion.

<!-- weave check -->
```loom
agent Researcher { model: "claude-sonnet-5-5"  tools: [calculator]  max_iterations: 20 }

supervise Researcher {
    when looping(3)                   { steer "You are repeating yourself. Try something else." once }
    when tool_failing(calculator, 3)  { wrap_up }
    when context > 90%                { wrap_up }
    when steps > 15                   { cancel use partial }
}

workflow Brief(topic) { delegate "Research {topic}" to Researcher -> findings }
```

`looping(N)`: the same tool with the same input N times in a row. `tool_failing(Tool, N)`: N failures of that tool in a row. `context > P%`: how full the model's window is, when it is known. `steps > N`: model calls in the step. A rule fires when its condition *becomes* true and again only after it went false and true; `once` limits it to one firing per step. Not supported: `retry with <Agent>`. Rules are not under the earned-autonomy ladder.

## 4. Reaching a running workflow

The script says what may happen; a person reaches the **process that is running it**. A suspended or finished run cannot be steered (its stops and answers go through the journal).

| From | How |
|---|---|
| The console | `weave run script.loom --steer-via console`, then type commands. A line typed while a question is open is the answer to it. |
| A chat | A process with a channel (`weave daemon --ask-via telegram`, or your application's own) lets the people on the channel's allowlist send the same commands. A reply to a question's message, one that carries its code, or one of its choices is always an answer, never a command. |
| An application | `LoomRun.start(executor, "Brief", inputs)` gives `steer`, `wrapUp`, `cancel(agent, Outcome, reason)`, `pause`, `resume`; `Outcome.rewindTo("collected")` sends the run back. |

```text
agents                                          who is working
steer Researcher focus on the EU market         correct its reasoning
wrap up Writer : that is enough                 finish now with what it has
cancel Writer skip : not needed                 outcome: use partial | skip | discard | retry | suspend | conclude | ask
cancel Writer back to collected : sources stale send the run back to a checkpoint the script declared
pause Writer / resume Writer
#brief steer Researcher ...                     #label says which run, when several are going
```

Sending a run back needs two things from the script: the checkpoint must be declared, and the step must have an `on_stopped { rewind … }` to take its limit and side-effects policy from; otherwise the command is refused and says why. **Fabric stays a window:** it shows these events in a run's timeline and has no control for them. The pause, suspend and stop it already has remain the operator's.

## 5. In Java

The same controls are in the library, without Loom:

- `RunHandle` (pass it to `agent.run(question, handle)`): `steer`, `pause`, `resume`, `cancel(reason)`, `wrapUp(reason)`; the result says `isCancelled()`, `isWrappedUp()` and carries a `StopReport` (kind, reason, who, phase, the last thing said, and whether it may have changed anything).
- `ToolHook` checks tool calls; `RunHook` checks the question, each model call and the final answer (see [chapter 12](12-java-quick-reference.md)).
- `DelegateTaskTool` bounds delegation: a depth limit (default 2) and the tools a helper may be given.

## 6. Test it

- The CTK has a scripted scenario for each row of the defaults (`src/loom/ctk/interventions/`); an intervention there is issued as a given model call returns, so a run is the same every time. Do the same for your own script: script the model, issue `steer`, `cancel` or `wrap_up` from inside it, and assert the trace and the variables.
- `weave graph` draws an `on_stopped` handler on a dotted *stopped* line and a rewind on a dashed *sent back* line; `weave explain` says the rules in words.

## Gate

- Every agent a person may steer is marked `steerable` on purpose, has `streaming: true`, and has a `guard` that fits what the person might type.
- Every step whose stop matters has an `on_stopped` or an `intervention` default the user agreed to; no `retry` for an agent that changes things unless it tests `_stop.changed`.
- Every rewind names a declared checkpoint and a limit.
- An agent that can loop has a `supervise` rule, or the user decided it does not need one.
- `weave check` and `weave audit` pass, and the user knows how a person reaches the run (console, chat or application) and who is allowed to.
