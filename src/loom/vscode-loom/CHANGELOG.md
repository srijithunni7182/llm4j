# Changelog

## 1.2.0

- **Loom: Set Up My Coding Agent** is offered once. When you open a folder that has a `.loom` file and no coding-agent set-up, a notification asks whether to set it up (*Set up*, *Not now*, *Don't ask again*). *Not now* is remembered for that workspace, *Don't ask again* for all of them, and it writes nothing until you choose; *Set up* runs the same command as the welcome view's button, which shows what it will write first.
- The engine this extension carries now reports whether it is a release or a development build, and which commit (`weave --version`).

## 1.1.0

- `weave` works in VS Code's integrated terminals. The extension carries the Loom command line, and now adds it to the PATH of terminals (after your existing entries, so a `weave` you installed yourself still wins), so every `weave` command in the guides works there with nothing else installed. It applies to terminals opened after the extension has started. The setting `loom.terminal.addWeaveToPath` (default on) turns it off.
- The documentation says where `weave` comes from, for people who use it outside VS Code.

## 1.0.2

- The README opens with what Loom is (a short, checked sample script) and what the extension covers beyond the language, from describing a workflow to shipping it, and the package description says the same. The journey table comes right after it.

## 1.0.1

- The Marketplace page shows the settings as a list instead of a table that it failed to draw, and the three settings whose default is empty say so. The repository entry in the manifest names its type (`git`).

## 1.0.0

- The rest of the journey in the editor: **Loom: Run Tests**, **Loom: Evaluate Workflow...** (mock run, dataset check, starter dataset), **Loom: Run With Live Page...** (a real run, asks first, capped by `loom.observe.maxTokens`), **Loom: Write Deployment Files...** (Docker, Compose, Kubernetes), **Loom: What Should I Do Next?** and **Loom: Show All Commands (weave man)**. Each types one `weave` command into an integrated terminal.
- **Loom: Set Up My Coding Agent** (was *Install the Loom Skill in This Project*): choose Claude Code, `AGENTS.md` (Codex, Copilot agent mode, Gemini, Cursor and others), GitHub Copilot or Cursor; it writes the file each reads, keeping what you wrote in it. The text points the agent at `weave man` (the command reference) and the loop and rules for working with Loom. The welcome view has a button for it.
- Generate and change a workflow from the editor: **Loom: Generate Workflow...** asks what it should do, opens the plan for you to read and edit, then builds the project and shows which checks it passed; **Loom: Change Workflow...** shows the model's change as a diff with the review and cost difference, and applies it only when you say so (a change that makes things worse needs a second confirmation). It runs `weave generate` as a process of its own; settings `loom.generate.model`, `maxCost`, `maxTokens`, `prices`.
- Debugging: press F5 on a `.loom` file, or click **Debug** above a workflow (new command **Loom: Debug Workflow**), to step through it with breakpoints (a line, a condition, an agent), the variables with the statement that set each, step over / in / out, **step back**, and editing a variable. Models are mock and nothing is sent or written; live models ask first and run under a cap. It runs the engine's `weave dap`.
- Editing that knows your script: go to definition and find references for agents, workflows, tools, classifiers, decisions, checkpoints, prompt files, imports and variables; rename that changes every use in every file as one undoable change (and refuses, changing nothing, when it cannot be done safely); completion that offers only what fits and passes `weave check` (the fields of a variable after its dot, the labels of an enum after `==`, agents after `to`, the checkpoints of the workflow after `rewind to`); hover with models, tools, types and allowed values; an outline with checkpoints and variables; signature help for `call`. They come from the same parser that runs your workflows (the new `weave symbols`), also for a half-typed script and for an import you have changed but not saved. Setting `loom.semantic.enable`.
- Limits you can set and one clear message when something is limited: `loom.serve.memoryMb` and `loom.serve.timeoutMs`; a missing or too-old Java, an engine that keeps stopping, a slow engine and a protocol mismatch each say so once in the status bar (new command **Loom: Java Requirements**). An engine that stops answering is restarted by itself.
- The Review panel is easier to see and use: it keeps its controls readable in light, dark and high-contrast themes (and in forced-colour modes), nothing runs off the side at a 200 % zoom, and the summary strip wraps instead of overlapping.

- Loom Review, layouts and triage: the panel has three layouts (**Split**, **Outline**, **List**) over the same data, and every finding can be fixed (safe fixes at once, others after you have seen the change), accepted with a reason and a scope, dismissed on this machine, explained, or recorded in a baseline, each as one change you can undo. The lightbulb offers the same. New commands **Loom: Apply Safe Fixes**, **Explain Review Rule**, **Restore Dismissed Findings**, **Accept All Existing Findings (Baseline)**; setting `loom.review.layout`.

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

First release on the Visual Studio Marketplace.

- Syntax highlighting, snippets and language configuration for `.loom` and `.loot` files.
- Diagnostics, workflow outline and go-to-definition from the bundled language server.
- **Loom: Show Workflow Graph**: the workflows of a script as a flowchart that follows `import`s and `call`s, with hover details,
  refresh on save and *Copy Mermaid*.
- Commands: Run Workflow, Create Prompt File, Open Guide, and Install the Loom Skill in This Project (so a coding agent writes Loom
  correctly), all through the bundled `weave` command line, which needs Java 17 or newer on the machine.
