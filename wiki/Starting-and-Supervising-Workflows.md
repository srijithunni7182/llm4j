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

## What it does not do yet

- `start` needs a `LoomHost` with persistence, so it works from Java and from a service built on the host. **`weave run` does not start children yet.**
- The deadline on `start` is parsed but not enforced, and `cancel h rewind to ...` is refused.
- `weave graph`, `weave timeline`, Fabric and the live page, the editor extension and the compatibility kit do not show children yet, and `weave test` has no vocabulary for a scripted child.

[VERIFICATION.md](../docs/specs/33-async-workflows/VERIFICATION.md) lists exactly what is built and what is not.

## Who may do what

`weave audit` lists a script that can steer a started workflow as an input channel (LA28) and each judged rule (LA29). A child runs with the invoker's credentials or less, a handle is never given to a model, and a steer's text is literal. See [Securing a workflow](../docs/security/securing-workflows.md).
