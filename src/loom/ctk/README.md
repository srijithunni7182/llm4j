# Loom Conformance Test Kit (CTK)

The conformance test kit for Loom runtimes. It validates behavioural parity across Java and Python implementations.

It is a canonical suite of `.loom` scripts, the execution traces each must produce, and mock agent fixtures that give deterministic answers. Together they define the behavioural contract any compliant Loom runtime must meet.

## Contents

- [What is in the kit](#what-is-in-the-kit)
- [Scenarios at a glance](#scenarios-at-a-glance)
- [How a scenario is built](#how-a-scenario-is-built)
- [The scenarios](#the-scenarios): [tasks](#task-steps-run) · [classification](#classification-steps-intentclassify) · [extraction](#extraction-steps-extract) · [fan-out](#bounded-fan-out-for-each--max-n) · [tools](#tools-that-compute-check-edit-and-run-phase-35) · [remote agents](#remote-agents-a2a) · [providers](#provider-declarations) · [grounding](#web-grounding) · [interventions](#interventions-steering-stopping-wrapping-up) · [attached media](#attached-media-attach) · [started workflows](#started-workflows-start-wait-cancel)
- [Running the kit](#running-the-kit)

## What is in the kit

| Folder | What it holds |
|---|---|
| `scripts/` | Canonical `.loom` test scripts covering all statement types |
| `traces/` | The expected execution trace (JSON) for each script |
| `mocks/` | Mock agent fixtures (JSON) with deterministic responses: for a task, what it returns for given arguments |
| `interventions/` | Fixtures for the [intervention scenarios](#interventions-steering-stopping-wrapping-up) |
| `src/main/java/` | The CTK runner |
| `src/test/java/` | Unit and property tests for the CTK |

The kit's own runner is still a stub, so the Java runtime runs each scenario in a test of module `ai-agent4j-loom`. No other runtime in this repository implements Loom, so there is nothing else to extend (verification P9).

## Scenarios at a glance

| Scenario | Statement | Canonical script | Java test (module `ai-agent4j-loom`) |
|---|---|---|---|
| [Tasks](#task-steps-run) | `run` | `task_basic`, `task_rejected` | `TaskCtkConformanceTest` |
| [Classification](#classification-steps-intentclassify) | `intentclassify` | `classify_basic`, `classify_unsure` | `ClassifyCtkConformanceTest` |
| [Extraction](#extraction-steps-extract) | `extract` | `extract_fields` | `ExtractCtkConformanceTest` |
| [Bounded fan-out](#bounded-fan-out-for-each--max-n) | `for each … max N` | `for_each_max` | `ForEachMaxCtkConformanceTest` |
| [Tool kinds](#tools-that-compute-check-edit-and-run-phase-35) | `compute`, `verify`, `scan`, `workspace`, `sandbox` | `tool_compute`, `tool_verify`, `tool_scan`, `tool_workspace`, `tool_sandbox` | `ToolKindsCtkConformanceTest` |
| [Remote agents](#remote-agents-a2a) | `delegate … to Remote`, `ask Channel` | `a2a_call` | `A2ACtkConformanceTest` |
| [Exposed workflow](#remote-agents-a2a) | `expose` | `a2a_expose` | `A2AExposeCtkConformanceTest` |
| [Providers](#provider-declarations) | `provider` | `provider_declarations` | `ProviderDeclarationsCtkTest` |
| [Web grounding](#web-grounding) | `grounding:` | `grounding_declarations` | `GroundingDeclarationsCtkTest`, `GroundingLoomTest` |
| [Interventions](#interventions-steering-stopping-wrapping-up) | steer, cancel, wrap up | `intervention_*` | `InterventionCtkConformanceTest` |
| [Attached media](#attached-media-attach) | `attach` | `attach_basic` | `AttachCtkConformanceTest` |
| [Started workflows](#started-workflows-start-wait-cancel) | `start`, `wait`, `cancel` | `child_start_wait`, `child_cancel`, `child_restart`, `child_progress` | `ChildCtkConformanceTest` |

## How a scenario is built

Every scenario is made of the same three parts, so a runtime needs no network and no key:

1. **A script** in `scripts/`: the `.loom` file the runtime runs.
2. **A mock** in `mocks/`: what each agent, task, classifier or extractor answers, keyed by its name and the text the workflow starts with.
3. **A trace** in `traces/`: what the run must emit, which the comparator checks.

Anything that varies from run to run (model text, timing) is kept out of the trace, so a conforming run is always the same run.

---

## The scenarios

### Task steps (`run`)

`run Name(arg = value) -> result` runs a deterministic task, not an agent.

| | |
|---|---|
| **Trace step** | `"kind": "task"`, a `taskName`, and a `payload` |
| **Payload** | The arguments as **sorted-key, compact JSON** (`{"amount":40,"order":"A-1"}`), the same text in every runtime |
| **What the comparator checks** | The payload, the task name and the output variable. Unlike a model's payload, the payload is deterministic |
| **Scenarios** | `scripts/task_basic.loom`, `scripts/task_rejected.loom` |
| **Mock** | `mocks/task_basic.json` maps each task's name and canonical arguments to the result map it returns (`outcome`, `reason`, `value`, data), which a runtime's test tasks implement |
| **Java test** | `TaskCtkConformanceTest` |

### Classification steps (`intentclassify`)

`intentclassify Classifier on text -> result` puts typed questions (`choose`, `ask`, `rate`) to a classifier backend and binds one label per question to the result.

| | |
|---|---|
| **Trace step** | `"kind": "classify"`, the `classifier` name, the `outcome` and the bound `labels` |
| **`outcome`** | `ok`, or `unsure` when the confidence rule turned an answer into the reserved label `unsure` |
| **`labels`** | `question=label` pairs joined by commas, in declaration order |
| **State** | The step carries no state and no model text |
| **Scenarios** | `scripts/classify_basic.loom`, `scripts/classify_unsure.loom` |
| **Mock** | `mocks/classify_*.json` maps the classifier's name and the text the workflow starts with to what the backend answers: `label` and `probability` for a choice, `yes` for a yes/no, `position` for a score |
| **Java test** | `ClassifyCtkConformanceTest` |

### Extraction steps (`extract`)

`extract Extractor from text -> result` is a stateless model step: it is handed only the text named in the statement and binds the fields it found.

| | |
|---|---|
| **Trace step** | `"kind": "extract"`, the `extractor` name, and `present` and `missing` |
| **`present`, `missing`** | The **names** of the fields found, and of the optional fields the text did not state, joined by commas in declaration order |
| **State** | The step carries no input and no value |
| **Scenario** | `scripts/extract_fields.loom` |
| **Mock** | `mocks/extract_fields.json` maps the extractor's name and the text the workflow starts with to the JSON the model answers |
| **Java test** | `ExtractCtkConformanceTest` |

### Bounded fan-out (`for each … max N`)

`for each item in list.path max N { … }` (and `parallel for each`) bounds a fan-out.

- A list longer than `N` makes the workflow **fail before any item starts**.
- The exception is a statement with `on_too_many { … }`, which runs instead, with `_count` and `_max` in scope.
- The failure names the agent or extractor that wrote the list.
- A runtime emits one `fanout_exceeded` trace event carrying `count`, `max`, `list`, `producer` and `producerLine`, and never an item.

| | |
|---|---|
| **Scenario** | `scripts/for_each_max.loom` |
| **Mock** | `mocks/for_each_max.json` holds a plan within the bound and one over it |
| **Trace** | `traces/for_each_max.json` holds the expected outcome of each |
| **Java test** | `ForEachMaxCtkConformanceTest` |

### Tools that compute, check, edit and run (phase 35)

`scripts/tool_compute.loom`, `tool_verify.loom`, `tool_scan.loom`, `tool_workspace.loom` and `tool_sandbox.loom` are the canonical scenarios of the `compute`, `verify`, `scan`, `workspace` and `sandbox` kinds. They run with the real kinds and the inputs in `mocks/tool_*.json`.

`traces/` holds the tools used, in order, and the note each script writes from the results:

| Kind | What the note must show |
|---|---|
| `compute` | Exact decimals: `0.1 + 0.2` is `0.3` |
| `verify` | A report's counts, and an unreadable report counted as one failure |
| `scan` | A finding counted without its match |
| `workspace` | A restore that puts a file back exactly |
| `sandbox` | A program run by name with its input, at the isolation it reports |

The sandbox scenario uses `isolation: process`, so the kit runs without a container runtime. Java test: `ToolKindsCtkConformanceTest`.

### Remote agents (A2A)

`delegate "…" to Remote -> result` sends a task to an agent reached over A2A, and `ask Channel "…" -> result` asks the people behind an agent channel.

**What a runtime emits.** `remote` trace events (`send`, `state`, `done`, a cancel) and `exchange` events (`question`, `reply`, `authorization`, `file`). They hold a digest and sizes, never the remote's text.

**The call scenario**, `scripts/a2a_call.loom`:

- `mocks/a2a_call.json` holds three cases: a remote that completes, one that asks a question first, and a card that names another host. Each is given as the states the remote moves through and what the channel answers.
- `traces/a2a_call.json` holds what each case must give: the bound text, the number of messages sent (a resume never sends again), the questions asked and the trace kinds.
- A card on another host is refused before anything is sent.
- Java test: `A2ACtkConformanceTest`, against the mock A2A agent of `src/a2a-testkit`.

**The exposed-workflow scenario**, `scripts/a2a_expose.loom` and `traces/a2a_expose.json`: the card an `expose` block shows outsiders, built from the script alone, in terms of what a client reads (name, description, accepted media types, skills, address). It also checks:

- words that must not appear in the card (the channel, the workflow's text);
- an address that is not https, which is refused.

Java test: `A2AExposeCtkConformanceTest`.

### Provider declarations

`scripts/provider_declarations.loom` declares one provider of every kind the language accepts:

- `openai` (with `api: responses`), `xai`, `kimi`, `qwen`, `perplexity`, `perplexity_router`, `perplexity_decisions` (a classifier backend) and `openai_compatible` (with `header:` and `query:`, and a local server with no key);
- beside the older `gemini`, `anthropic`, `ollama`, `sarvam` and `jev`.

A conforming runtime accepts the script at load time and refuses a bad option by name:

| Rule |
|---|
| `api` is `chat` or `responses` |
| `header` and `query` are only for `openai_compatible` |
| `openai_compatible` needs `base_url` |
| A key is `env.NAME` or `secret.NAME`, never a literal |

The scenario has no trace, because no model is called. The Java runtime checks it in `ProviderDeclarationsCtkTest`, and `RepositoryScriptsTest` loads it with every other script.

### Web grounding

`scripts/grounding_declarations.loom` has:

- one grounded agent per provider that can search (Perplexity, Claude, Gemini, OpenAI on the Responses API);
- a `budget { searches }` at run, agent and step level;
- a step that reads `{news_citations}`.

A conforming runtime accepts it at load time and refuses, by name and line:

| Refused | Example |
|---|---|
| `grounding` on a model whose provider cannot search | Ollama, Sarvam, OpenAI or xAI on chat completions |
| A setting the provider cannot honour | `max_searches` on Perplexity or Gemini, `recency` on Claude or OpenAI, `country` on xAI or Gemini |
| `allow` and `block` together | |
| A `recency` that is not `day`, `week`, `month` or `year` | |
| A `country` that is not two letters | |
| A `max_searches` below 1 | |
| An unknown setting, or a second `grounding:` | |

The scenario has no trace, because no model is called. The Java runtime checks it in `GroundingDeclarationsCtkTest` and `GroundingLoomTest`.

### Interventions (steering, stopping, wrapping up)

Interventions are part of the conformance contract: a runtime that claims conformance passes these scenarios. They are **scripted, not timed**, so a conformance run never depends on when a person acts.

Each scenario is `scripts/intervention_*.loom`, a fixture `interventions/intervention_*.json` and the canonical `traces/intervention_*.json`.

**The fixture says:**

| Field | Meaning |
|---|---|
| `tool` | The tool the stoppable agent uses: `calculator` (only reads) or `MockTool` (not known to only read, so the agent *may have changed things*) |
| `replies` | What each agent's model returns, in call order (`act` calls the tool, `final:TEXT` answers) |
| `wrapUp` | The answer to the wrap-up call (tools off) |
| `interventions` | "After call `atCall` of `agent`, issue `{kind: steer \| cancel \| wrap_up \| cancel_back, reason, note, origin, outcome?, checkpoint?}`". `outcome` is the one a person may name |
| `human` | The answers of the person who is asked, in order |
| `expect` | How the run ends (`completed`, `stopped`, `suspended` or `loadError`), how many times a person was asked, and the variables afterwards |

A runtime issues each intervention as the stated model call returns, which makes every run the same run.

**Three trace step kinds, beyond the others, whose payload is compared exactly:**

| Kind | Payload |
|---|---|
| `stop` | The sorted-key compact JSON `{"kind":"cancel","outcome":"USE_PARTIAL","reason":"user"}` (see below) |
| `steer` | The note the person sent |
| `rewind` | `{"checkpoint":"collected","generation":2}` |

For a `stop` payload:

- `kind` is `cancel` or `wrap_up`.
- `outcome` is what the run decided: `USE_PARTIAL`, `SKIP`, `DISCARD`, `RETRY`, `SUSPEND`, `CONCLUDE` or `REWIND`.
- `reason` is the reason as a script sees it: `user`, `operator`, `timeout`, `budget`, `limit`, `sibling`, `supervision`, `safety` or `shutdown`. Anything a person wrote is `user`.

**The scenarios.** One per row of the design's table of defaults:

- a person's cancel asks;
- a wrap-up keeps its answer;
- a timeout retries only if nothing may have changed;
- a sibling's stop is discarded;
- a shutdown suspends;
- a safety stop asks;
- a budget stop keeps the partial answer.

Beyond those: the script's own `on_stopped` and `intervention`, and cancel-and-send-back to a declared checkpoint.

**Three negative scenarios:**

- a retry is refused when the agent may have changed something;
- a cancelled step is never recorded as a success;
- a rewind to an undeclared checkpoint is refused before the run, and a person's send-back to a checkpoint the script does not declare is refused while the agent carries on.

A `supervise` rule acts like a person but needs none: its steer is a `steer` step in the trace (`intervention_supervise_looping`).

**What is not tested:** when exactly a streaming call is cut off, and wall-clock timing.

Java test: `InterventionCtkConformanceTest`.

### Attached media (`attach`)

`delegate … attach image "{picture}" -> reading` (also `broadcast`, `handoff`, `human_prompt`, and `extract … from image "…"`) sends a file with the task.

- The file is read from the script's `media_root` only, its kind is decided from its bytes, and the agent's model must be able to take it.
- A runtime must refuse **before** the call and must not record the bytes.

`scripts/attach_basic.loom` is the canonical scenario and `scripts/media/pixel.png` the file it reads. `traces/attach_basic.json` lists three runs of it:

| Run | Outcome | Model call |
|---|---|---|
| A file under the root | `ok` | Once, with the attachment's type (`image/png`) |
| A path that climbs out of the root | `refused` | **None** |
| A file that does not exist | `refused` | **None** |

`mocks/attach_basic.json` maps the agent's name and task to its answer. Java test: `AttachCtkConformanceTest`.

### Started workflows (`start`, `wait`, `cancel`)

`start Child(args) budget { ... } -> handle` runs a workflow as a child run of its own, and `wait handle timeout D -> result` returns when it settles.

- A child has its own journal and a run id worked out from its parent's run and the step that started it, so a parent that is killed and run again finds the same child and repeats no effect.
- Its status is a value: `completed`, `failed`, `cancelled`, `timed out`, `stopped`, `running`, `sleeping` or `waiting`. `handle is ok` is true only for `completed`.

**Scenarios:**

| Script | Shows |
|---|---|
| `child_start_wait.loom` | Start a child and wait for it |
| `child_cancel.loom` | Cancel a child |
| `child_restart.loom` | A restart in the middle (the task `Lookup` is code the runtime supplies) |
| `child_progress.loom` | Declared progress (below) |

For declared progress: a workflow declares `progress { stage: string, done: number }`, reports it with `report { ... }`, and its parent reads the latest report as `h.progress.stage`. That is the child's own words, so it is unchecked; it is kept in the child's journal, so it survives a run again.

Java test: `ChildCtkConformanceTest`, including the restart in the middle.

---

## Running the kit

```bash
# Build the project
mvn clean package

# Run all conformance tests
mvn exec:java -Dexec.mainClass=io.github.loom.ctk.CtkMain

# Run tests in parallel
mvn exec:java -Dexec.mainClass=io.github.loom.ctk.CtkMain -Dexec.args="--parallel"
```

Run the unit tests with `mvn test`.

### Requirements

- Java 17 or higher
- Maven 3.6 or higher
- ai-agent4j runtime built and available at `../ai-agent4j/target/ai-agent4j-6.2.0.jar`

## License

Apache License 2.0
