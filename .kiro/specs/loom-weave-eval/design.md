# Design: `weave eval`

## Layout

```
my-workflow/
  main.loom
  prompts/…                 # optional, see loom-prompt-files
  eval/golden/
    dataset.yaml            # dimensions (optional)
    researcher.yaml         # scenarios for agent "researcher"
    workflow.yaml           # scenarios for the whole workflow
```

A scenario:

```yaml
- id: ref-001
  name: Refund over the limit
  input: "I was charged twice, 240 dollars"
  expected_tools: [LookupOrder]
  rubric:
    - Asks a person to approve before refunding
  expect:
    - Approval step ran before the refund step
  dimensions: [safety]
  tags: { kind: refund }
```

## Components

| Piece | Module | Responsibility |
|---|---|---|
| `EvalScenario` + readers | `eval4j` | New `rubric`, `expect`, typed tags; legacy `RUBRIC:`/`EXPECT:` and `key:value` read as the new fields; `EvalScenarios.fromDirectory` |
| `DatasetFolder` | `ai-agent4j-loom` | Finds the folder, matches files to agents and workflows, reads `dataset.yaml`, validates |
| `EvalCommand` | `loom/cli` | `weave eval` and `--check`, `--init`; exit codes; summary |
| `AgentEvalRunner` | `ai-agent4j-loom` | Runs one agent exactly as `HarnessExecutor` would build it (shared construction, no copy) |
| `WorkflowEvalRunner` | `ai-agent4j-loom` | Runs a workflow with the mock or a real model, records a trace, applies trajectory checks |
| Judge | existing eval4j judges | Scores rubric lines; absent judge gives `UNJUDGED` |
| Report | `eval4j-report` | Existing HTML report; passed, failed, unjudged separated |
| Guide | skill and `docs/guide` | Opt-out question, order of work, README record |

## Decisions

- **One scenario format for JUnit and the CLI**, so moving between the two costs nothing.
- **Unjudged is its own state.** A mock run can honestly say what it did not check.
- **Agent construction is shared with `HarnessExecutor`**, not re-implemented, so an eval measures the agent that runs.
- **No dataset is never an error outside `weave eval`.**
- **The README records the opt-out**, because it is already the file a newcomer reads; no new config file.

## Open questions for review

1. Default cap when `--max-cost` is not given and the run is real: proposed 2 dollars, with a message.
2. Judge model default: proposed the agent's own model unless `--judge` is given; revisit if self-judging proves weak.
3. Should `weave eval --init` call a model to draft scenarios? Proposed no; the guide drafts them with the user.
