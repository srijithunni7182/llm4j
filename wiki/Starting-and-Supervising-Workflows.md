# Starting and supervising workflows

`call` runs a workflow to the end before the next line. `start` gives the workflow a run of its own, hands back a handle at once, and the script goes on, so one workflow can run, watch and operate others. The full reference is a section of the [Loom guide](../src/loom/ai-agent4j-loom/LOOM_GUIDE.md#starting-workflows-and-supervising-them-start-wait-cancel-steer-supervise); this page is the short version and what to know before you rely on it.

## The shape

```loom
workflow Main(question) {
    start Research(topic = question) timeout 20m budget { tokens: 100000 } -> work
    supervise work budget { tokens: 20000 } {
        when tokens > 90000 { cancel use partial }
        when idle > 3m      { steer "You seem stuck. Narrow the topic." once }
    }
    wait work timeout 25m -> findings
    alt (work is ok) { return findings }
    halt "the research did not finish: {work.status}"
}
```

- **`start`** needs a `budget`, and a child's spending counts against its parent, so the parent's budget stays the limit for the whole run. A `start` is refused, with a message, when the parent could not pay for it after what its other children may still spend.
- **`wait`** needs a `timeout` and returns when the child settles or the time passes. A failed child is a value you read (`work is ok`, `work.status`, `work.failure`), not an exception.
- **`cancel`** lets an effect under way finish and be journaled, then stops the child at its next step. **`steer`** puts words you wrote in the script in front of an agent the child's author marked `steerable`.
- **`supervise`** watches the child while it runs: counted rules, assertions in code (`never calls` refuses the call before it runs) and judged assertions (a model's opinion, bounded, facts only unless you say otherwise).

## What a restart does

A child's run id is worked out from its parent's run and the step that started it. If the parent is killed anywhere and run again, it finds the same child, and no effect happens twice. If the parent waits for a child that is asleep or waiting for a person, the whole tree suspends and the process may exit; running the parent again carries on.

A child never outlives the run that started it: when the parent ends, children still going are cancelled and the end waits for them for a grace period.

## Where it runs, and what is not there yet

- `start` keeps each child's run somewhere: under `weave run --journal DIR` (a sibling folder for each child) or a `LoomHost` with persistence. Without either it fails closed. `weave fork` copies the children, `weave timeline` draws them as a tree, and Fabric shows a child under its parent with the cost of the whole tree.
- `weave test` runs children for real under scripted models and the virtual clock, with `judges:` and `starts:`, `children:`, `fired:` and `steers:` in `expect:`. Starter 18 is a worked example.
- `weave test` can also **script a child instead of running it** (`children: { Research: [ { state: working, after: 2m }, { state: completed, result: "..." } ] }`): no workflow, no model, on the virtual clock, so a supervisor's rules and a judge's looks are tested without a real clock.
- `cancel h rewind to <checkpoint> at most N times` sends a child back to a checkpoint it declared and lets it go on (a new generation; the old one stays as history).
- `weave review` adds each started child's budget, and each judged `supervise`'s budget, to the worst case of the parent.
- A child can **declare and report progress** (`workflow Research(t) progress { stage: string, done: number } { report { stage: "reading", done: 1 } }`); the parent reads `work.progress.stage` (unchecked, from the child's own journal, so it survives a restart), and `assert progress.done changes within 5m { ... }` watches whether it moves, never what it says.
- Not there yet: signals and `wait any` (phase 34, not yet built).

[VERIFICATION.md](../docs/specs/33-async-workflows/VERIFICATION.md) lists exactly what is built and what is not.

## Who may do what

`weave audit` lists a script that can steer a started workflow as an input channel (LA28) and each judged rule (LA29). A child runs with the invoker's credentials or less, a handle is never given to a model, and a steer's text is literal. See [Securing a workflow](../docs/security/securing-workflows.md).
