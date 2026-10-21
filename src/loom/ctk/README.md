# Loom Conformance Test Kit (CTK)

Conformance Test Kit for Loom runtimes. Validates behavioral parity across Java and Python implementations.

## Overview

The CTK provides a canonical suite of `.loom` test scripts, expected execution traces, and mock agent fixtures that define the behavioral contract for any compliant Loom runtime implementation.

## Structure

- **`scripts/`** — Canonical `.loom` test scripts covering all statement types
- **`traces/`** — Expected execution trace JSON files for each test script
- **`mocks/`** — Mock agent fixture JSON files providing deterministic responses (for a task, what it returns for given arguments)
- **`interventions/`** — Fixtures for the intervention scenarios (see "Interventions" below)
- **`src/main/java/`** — CTK runner implementation
- **`src/test/java/`** — Unit and property tests for the CTK

## Task steps (`run`)

A `run Name(arg = value) -> result` statement runs a deterministic task, not an agent. Its trace step has `"kind": "task"`, a `taskName`, and a `payload` that is the
arguments as **sorted-key, compact JSON** (`{"amount":40,"order":"A-1"}`), the same text in every runtime. Unlike a model's payload it is deterministic, so
the comparator checks it, together with the task name and the output variable. `scripts/task_basic.loom` and `scripts/task_rejected.loom` are the canonical
scenarios; `mocks/task_basic.json` maps each task's name and canonical arguments to the result map it returns (`outcome`, `reason`, `value`, data), which a runtime's
test tasks implement. The Java runtime runs them in `TaskCtkConformanceTest` (module `ai-agent4j-loom`), because this kit's own runner is still a stub.

## Classification steps (`intentclassify`)

An `intentclassify Classifier on text -> result` statement puts typed questions (`choose`, `ask`, `rate`) to a classifier backend and binds one label per
question to the result. Its trace step has `"kind": "classify"`, the `classifier` name, the `outcome` (`ok`, or `unsure` when the confidence rule turned an
answer into the reserved label `unsure`) and the bound `labels` as `question=label` pairs joined by commas, in declaration order. The step carries no state and
no model text. `scripts/classify_basic.loom` and `scripts/classify_unsure.loom` are the canonical scenarios; `mocks/classify_*.json` gives the text the workflow
is started with and what the backend answers (`label` and `probability` for a choice, `yes` for a yes/no, `position` for a score), so a runtime needs no network
or key. The Java runtime runs them in `ClassifyCtkConformanceTest` (module `ai-agent4j-loom`).

## Interventions (steering, stopping, wrapping up)

Interventions are part of the conformance contract: a runtime that claims conformance passes these scenarios. They are **scripted, not timed**, so a
conformance run never depends on when a person acts.

Each scenario is `scripts/intervention_*.loom`, a fixture `interventions/intervention_*.json` and the canonical `traces/intervention_*.json`. The fixture says:

- `tool`: the tool the stoppable agent uses, `calculator` (only reads) or `MockTool` (not known to only read, so the agent *may have changed things*);
- `replies`: what each agent's model returns, in call order (`act` calls the tool, `final:TEXT` answers); `wrapUp`: the answer to the wrap-up call (tools off);
- `interventions`: "after call `atCall` of `agent`, issue `{kind: steer | cancel | wrap_up | cancel_back, reason, note, origin, outcome?, checkpoint?}`" (`outcome` is the one a person may name);
- `human`: the answers of the person who is asked, in order;
- `expect`: how the run ends (`completed`, `stopped` or `suspended`, or `loadError`), how many times a person was asked, and the variables afterwards.

A runtime issues each intervention as the stated model call returns, which makes every run the same run. The trace has three step kinds beyond the others, and
**their payload is compared exactly**:

| Kind | Payload |
|---|---|
| `stop` | the sorted-key compact JSON `{"kind":"cancel","outcome":"USE_PARTIAL","reason":"user"}`: `kind` is `cancel` or `wrap_up`; `outcome` is what the run decided (`USE_PARTIAL`, `SKIP`, `DISCARD`, `RETRY`, `SUSPEND`, `CONCLUDE`, `REWIND`); `reason` is the reason as a script sees it (`user`, `operator`, `timeout`, `budget`, `limit`, `sibling`, `supervision`, `safety`, `shutdown`; anything a person wrote is `user`) |
| `steer` | the note the person sent |
| `rewind` | `{"checkpoint":"collected","generation":2}` |

The scenarios are one per row of the design's table of defaults (a person's cancel asks; a wrap-up keeps its answer; a timeout retries only if nothing may have changed;
a sibling's stop is discarded; a shutdown suspends; a safety stop asks; a budget stop keeps the partial answer), plus the script's own `on_stopped` and `intervention`,
cancel-and-send-back to a declared checkpoint, and three negative scenarios: a retry is refused when the agent may have changed something, a cancelled step is
never recorded as a success, and a rewind to an undeclared checkpoint is refused before the run, and a person's send-back to a checkpoint the script does not declare is refused while the agent carries on. A `supervise` rule acts like a person but needs none: its steer is a `steer` step in the trace (`intervention_supervise_looping`). What is not tested: when exactly a streaming call is cut off, and wall-clock timing.
The Java runtime runs them in `InterventionCtkConformanceTest` (module `ai-agent4j-loom`), because this kit's own runner is still a stub.

## Usage

### Running the CTK

```bash
# Build the project
mvn clean package

# Run all conformance tests
mvn exec:java -Dexec.mainClass=io.github.loom.ctk.CtkMain

# Run tests in parallel
mvn exec:java -Dexec.mainClass=io.github.loom.ctk.CtkMain -Dexec.args="--parallel"
```

### Running Tests

```bash
mvn test
```

## Requirements

- Java 17 or higher
- Maven 3.6 or higher
- ai-agent4j runtime built and available at `../ai-agent4j/target/ai-agent4j-6.0.0.jar`

## License

Apache License 2.0
