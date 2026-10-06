# Requirements: `weave eval` and First-Class Golden Datasets

## Introduction

Evaluating a Loom workflow today needs a Java and Maven module: the user writes a loader for the golden dataset,
encodes judged expectations as `RUBRIC:` and `EXPECT:` lines inside `context`, packs structured data into tags,
and keeps a hard-coded list of agents and dimensions. A script-only user cannot evaluate at all without writing Java.

This feature makes golden datasets a plain folder of YAML files that `weave eval` runs directly, with the same eval4j
report as before. It also fixes the order in which the workflow guide works (golden dataset first, then the script)
and makes evaluation **optional**: a user can skip it and still build and run the workflow.

Prompt files (`.kiro/specs/loom-prompt-files/`) and this feature fit together: `weave eval --prompt researcher@v2`
compares two prompt versions with nothing else changed.

## Requirements

### Requirement 1: Datasets are a folder

1. A dataset folder (default `eval/golden/` next to the script, or `--dataset <dir>`) holds one YAML file per target.
   `<agent>.yaml` evaluates the agent of that name; `workflow.yaml` (or `<workflow>.yaml`) evaluates a whole workflow.
2. An optional `dataset.yaml` in the folder lists the quality dimensions the dataset uses, with a one-line meaning
   each, so the report shows every dimension even when nothing evaluated it.
3. A file that is not a known agent or workflow of the script is reported by `weave eval --check` with a suggestion.
4. Existing scenario files that eval4j already reads keep working unchanged.

### Requirement 2: Scenario fields

1. `EvalScenario` gains first-class `rubric` (judged expectations, a list of sentences) and `expect` (what a workflow
   run should have done), and typed `tags` (`kind: fabricated-premise`).
2. Files that still use `RUBRIC:` and `EXPECT:` lines in `context`, and `key:value` tags, are read as the new fields,
   so datasets already written are not rewritten.
3. A scenario is written once and is usable from `weave eval` and from JUnit (the existing `EvalScenarios` loaders
   return the new fields).
4. `EvalScenarios.fromDirectory(Path)` loads a whole folder, so Java users lose their hand-written loaders too.

### Requirement 3: `weave eval`

1. `weave eval <script> [--dataset DIR] [--agent NAME] [--workflow NAME] [--prompts DIR] [--prompt id@vN]` runs the
   scenarios and prints a summary: passed, failed, unjudged, cost.
2. Agent scenarios call the agent as the workflow would (same prompt, model, tools, guards) with the scenario input and
   check `expected_output_contains`, `expected_output`, `expected_tools`, and the rubric.
3. Workflow scenarios run the workflow and check the expectations and the trajectory (which steps ran, in which
   order) with the existing trajectory checks.
4. Rubric lines are judged by a judge model named by `--judge` or the script's configuration. With `--mock` or no
   judge, rubric lines are reported as **unjudged**, never as passed.
5. `--mock` runs with the framework's mock model so a first run costs nothing. Without `--mock`, `--max-cost` is
   required, or a default cap applies, and the run stops with a clear message when the cap is reached.
6. The exit code is 0 when everything judged passed, 1 when a scenario failed, 2 for usage errors.
7. `--report FILE` writes the eval4j HTML report, including the workflow graph with the run overlay.
8. `weave eval --check` validates the dataset folder (names, fields, unknown dimensions, duplicate ids) without
   calling any model.

9. Tools that read the outside world (search, HTTP) can be answered from fixtures in `eval/golden/fixtures.yaml`
   (`RecordedSearchTool`'s format: `id`, `match`, `snippets`), so a script-only user gets deterministic, free
   evaluation without writing a fixture tool. A query with no match finds nothing.

### Requirement 4: Evaluation is optional

1. `weave run`, `weave check` and `weave audit` never require a dataset and never fail because there is none.
2. The workflow guide asks once, at the start, "Do you want tests first?" The default is yes. If the user says no,
   the guide skips the dataset, prompt tests and trajectory stages and goes straight to building the workflow. The
   choice is written in the project's README so it is not asked again.
3. Skipping is never silent at go-live: the guide's go-live stage reminds the user once, in one sentence, that no
   evaluation exists, says what would be tested, and carries on if the user still wants to.
4. Skipping evaluation does not change any safety rule. Cost caps, approvals, guards and the earned-autonomy ceilings
   work as they do today; a level that needs evidence still needs it.
5. The user can add evaluation later without redoing anything: `weave eval --init` creates the dataset folder with an
   empty `dataset.yaml` and one starter scenario per agent.

### Requirement 5: Order of work in the guide

1. When the user wants tests, the guide's order is: decide the agents, write the golden dataset, write the script,
   run `weave eval --check`, then `weave eval --mock`, then a capped real run.
2. The guide does not write the script before the dataset exists unless the user chose to skip evaluation.
3. The guide never writes a Java loader for a script-only project.

### Requirement 6: Safety and honesty

1. A real `weave eval` run says before it starts how many model calls it expects and the cap, and needs `--yes` or
   an interactive yes.
2. Scenario text is data: it is masked like any other text before it is written to a report or trace.
3. A report separates passed, failed and unjudged, and never counts unjudged as passed.

### Requirement 7: Tooling and docs

1. `LOOM_GUIDE.md`, `llms.txt` and the workflow-guide skill document `weave eval`, the dataset folder and the opt-out.
2. The VS Code extension offers "Evaluate this workflow" (mock) and shows the summary; the graph overlays a run
   from the last eval. (Optional for the first release.)
