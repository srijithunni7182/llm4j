# Changelog

## Unreleased

- Loom Review: **Loom: Review Workflow** opens a panel with the findings of `weave review` and the workflow graph side by side, linked both ways (click a finding to see its step and line, click a step to list its findings, move the cursor to select the finding on that line), with a summary strip (findings, cost, time, effects, lenses, the lethal trifecta), filters and grouping, and a stale marker that appears the moment you edit. Findings also show as squiggles and Problems entries (source `loom-review`), a status-bar count and a CodeLens above each workflow. Open findings never stop Run. New settings `loom.review.*`.

- The Loom engine: the extension starts one `weave serve` process per window and asks it for checks and graphs, so feedback does not wait for a Java process to start (about 5 ms warm). It runs nothing and writes nothing and reads only the folders you have open. If it cannot start, or stops repeatedly, the extension runs `weave` for each request as before and the status bar says so. New commands **Loom: Restart Engine** and **Loom: Show Engine Log**; the new setting `loom.javaPath` (the older `loom.graph.javaPath` still works).

- Providers: the hover text of `provider` lists the new kinds (`openai`, `xai`, `kimi`, `qwen`, `perplexity`, `openai_compatible`) and their options (`api:` for OpenAI, `header:` and `query:` for the generic kind). `weave check` reports their mistakes on the line it names. 

- Web grounding: `grounding`, `recency`, `max_searches` and `searches` are highlighted, `grounding` and `searches` have hover text, and there is an `agent-grounded` snippet. `weave check` (which draws the squiggles) refuses a model or setting that cannot ground, on the line it names, and `weave audit` shows rule LA20.

- Classification: `intentclassifier`, `intentclassify`, `choose`, `samples` (and `rate` and `confidence` where they are a classifier's) are highlighted and have hover text, there are two snippets (`intentclassifier`, `intentclassify`), and the workflow graph draws a classify step with its backend (`Jev · calibrated` or `chat model · uncalibrated`) and its labels.

- `halt`, `set` and `return` are highlighted, `is ok` / `is not ok` read as a condition, and there are four snippets (`halt`, `set`, `return`, `isok`). The graph draws a halt as an "Ends the run" step that goes straight to the end, and `set` and `return` as steps of their own.

- `shared_prompt` is highlighted and has hover text.

- `supervise`, `when`, `looping`, `tool_failing` and `wrap_up` are highlighted with hover text, and there is a `supervise` snippet.

- Interventions: `on_stopped`, `intervention`, `steerable`, `streaming`, `reasoning`, the outcomes (`use partial`, `skip`, `discard`, `suspend`, `conclude`, `ask`) and `_stop.reason`, `_stop.note` and the other fields of a stop are highlighted and have hover text; three snippets (`agent-steerable`, `on-stopped`, `intervention`).
- Completion: the fields after `_stop.`, only the checkpoints declared above after `rewind to`, and the outcomes inside an `on_stopped { }` handler.
- The Flow view draws an `on_stopped` handler on a dotted *stopped* line and a rewind on a dashed *sent back* line, with a key (also in the exported picture). The shared renderer used by the eval4j report does the same.

- Workflow graph, redesigned: three views of the same graph (**Outline**, **Focus**, **Flow**; keys 1, 2, 3) over a main-path rail with the numbered `// 1. Title` comments of the script as sections, a breadcrumb that follows the selection, an inspector, and illustrated step kinds (agent, task, verdict, safety outcome, approval, branch, loop, person, note, exit, sleep as a shut eye, and more). Each agent's tools show on its steps, in the inspector and in an "Agents and their tools" list. A workflow with no `end` (a loop with a sleep) is shown and labelled "runs until stopped". **Export PNG** saves the current view as a picture beside the script. Copy Mermaid is unchanged. Clicking a step only selects it; **right-click** (or Shift+F10) opens a menu with Go to file, Open prompt, Open workflow and Focus. The graph shows no line numbers.
- Ladders: `ladder`, `rung`, `measure`, `climb`, `slip`, `sample`, `escalate`, `climbing`, `ceiling` and `proposes` are highlighted; durations (`30d`), percentages (`90%`) and decimals are numbers; the mode after `mode:` is told apart.
- Completion inside a ladder: the ladders of the file after `ladder:`, the rungs after `ceiling:` and `slip to`, the three modes after `mode:`, the measures (yours, `dangerous`, and the standard ones when `loom/autonomy` is imported) and the fields of a case inside the brackets of a collection method, the methods and fields of a collection after a dot, the parts of a ratio after `rate(...).`, and the words of a rung at the start of a line.
- A ladder's mistakes are underlined on the line `weave check` reports, with its guess.

## 1.0.0

First release on the Visual Studio Marketplace.

- Syntax highlighting, snippets and language configuration for `.loom` and `.loot` files.
- Diagnostics, workflow outline and go-to-definition from the bundled language server.
- **Loom: Show Workflow Graph**: the workflows of a script as a flowchart that follows `import`s and `call`s, with hover details,
  refresh on save and *Copy Mermaid*.
- Commands: Run Workflow, Create Prompt File, Open Guide, and Install the Loom Skill in This Project (so a coding agent writes Loom
  correctly), all through the bundled `weave` command line, which needs Java 17 or newer on the machine.
