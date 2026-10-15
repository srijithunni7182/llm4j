# vscode-loom

VS Code extension for Loom DSL (.loom and .loot files). Provides syntax highlighting, LSP diagnostics, workflow outline, a workflow graph (Outline, Focus and Flow views), and run command.

## Workflow graph

**Loom: Show Workflow Graph** draws the workflows of the active `.loom` file beside the editor. It follows `import`s, so a workflow that `call`s another file's workflow shows where that workflow is defined, and you can open it.

Open it from the Command Palette, from the graph icon in the editor title bar, or from the editor's right-click menu. The command is offered only for `.loom` files.

![The Outline view: the steps of a script as a tree, the selected step's section in the middle, its details on the right, and the main path along the bottom](https://raw.githubusercontent.com/srijithunni7182/llm4j/main/docs/images/vscode-loom/graph-outline.png)

### Three views of one graph

A real script is too long to read as one big flowchart, so the panel shows the same graph three ways. Switch with the buttons at the top right, or press **1**, **2** or **3**. The view you chose is remembered.

| View | Use it to |
|---|---|
| **Outline** (default) | Find your place. A tree of the steps on the left, grouped by section; the middle shows only the selected step's section at full size; the inspector on the right shows its settings. Blocks fold with the − and + buttons. |
| **Focus** | Follow the logic one step at a time. The selected step is large in the middle, with what it **comes from** on the left and what it **goes to** on the right, each edge labelled (`then`, `else`, `each round`, `when done`, `loops back`). Click a neighbour to move there. |
| **Flow** | See the whole shape. The main path runs left to right, with section bands above it; everything off the main path hangs below the step it belongs to. A loop's way round is a dashed line labelled `again ≤3`. Selecting a step scrolls it to the middle. |

![The Flow view: the main path left to right, a rewrite loop hanging below its safety check, and an exit that ends the run](https://raw.githubusercontent.com/srijithunni7182/llm4j/main/docs/images/vscode-loom/graph-flow.png)

![The Focus view: a step with what it comes from and goes to](https://raw.githubusercontent.com/srijithunni7182/llm4j/main/docs/images/vscode-loom/graph-focus.png)

### Where you are, and the main path

- **Main path.** The strip along the bottom has one segment for each step on the happy path, in every view. Done steps are light, the current one is dark, later ones are grey. Click a segment to jump to that step. The line above it says where the selected step sits ("Step 8 of 20 on the happy path", or "Off the main path, inside step 8").
- **Sections.** Number the parts of a workflow with comments (`// 1. Look and research`, then plain `//` lines under it for a one-line hint) and the panel groups the steps by them, labels the strip and the Flow bands, and shows the section in the breadcrumb. A script with no numbered comments is one section.
- **Breadcrumb.** `main.loom › Main(laptop_issue) › 3 · Safety check › outcome == "blocked"`. Every part is a button.
- **What the main path is.** At each step the path takes the way on that is not an exit and not another trip round a loop: `done` over `again`, the branch that carries on over the one that handles a case, and never a note that only ends the run.

### Kinds of step

Each kind of step has its own picture and colour, so you can read the shape of a script before the words.

| | Kind | | Kind |
|---|---|---|---|
| Agent | `delegate`, `handoff`, `broadcast` | Loop | `loop`, `for each` |
| Task | `run` | Ask a person | `ask a person` |
| Verdict | an `alt` on an agent's `expecting` field | Note | `note` |
| Safety outcome | an `alt` on a task's result | Ends the run | a note that ends a branch |
| Approval | an `alt` on a person's answer | Sleep | `sleep` (a shut eye) |
| Branch | any other `alt` | Call, parallel, guardrail, decide, checkpoint, rewind, observe | each has its own |

**Interventions.** A step with an `on_stopped { }` handler shows the handler under it on a **dotted** line labelled *stopped*; a `rewind` inside it (or anywhere) draws a **dashed** line back to its checkpoint labelled *sent back ≤N*. The two differ in pattern as well as colour, and a key under the Flow view (and in the exported picture) says which is which. Steering an agent never changes the workflow, so it draws nothing. In the editor, `_stop.` offers the fields of a stop, `rewind to` offers only the checkpoints declared above it, and the first word inside `on_stopped { }` offers the outcomes.

The strip's legend names the main ones. Settings show as chips on each step: `retry 3 · 2s`, `timeout 90s`, `budget 5k tok`, `expects {score, notes}`, a loop's `max 3`, `→ variable`.

### Agents and their tools

Each agent step shows the **tools** its agent has, as chips (a step in Flow shows a count, with the names on hover). A tool that needs approval is marked with a hand. The inspector adds the model, temperature, prompt file, persona, iteration limit, approval rules and budget. **Agents and their tools** under the toolbar lists every agent of the script with its model and tools; click an agent name to jump to a step that uses it.

### Workflows that never end

A workflow with a start, a loop and a `sleep` in it, and no `end`, is drawn as it is: the main path stops at the last step of the loop, the way round is labelled `again forever`, and the strip says "runs until stopped".

![An always-running workflow: a loop that checks, may notify, and sleeps, with no end](https://raw.githubusercontent.com/srijithunni7182/llm4j/main/docs/images/vscode-loom/graph-endless.png)

### Working with it

| You do | What happens |
|---|---|
| Click a step (or press Enter on it) | The step is selected. The editor stays where it is |
| Right-click a step (or Shift+F10) | A menu: **Go to main.loom:40** shows the line beside the panel, **Open prompt**, **Open workflow** and **Go to workflow** for a `call`, **Focus on this step**, **Copy step text** |
| ↑ / ↓ | Move through the steps (in Focus, ← and → go to what it comes from and goes to) |
| Double-click a `call` (or Shift+Enter) | The panel shows the called workflow; **Back** or the breadcrumb returns |
| Ctrl/Cmd-click a `call` | The editor opens the called workflow's source |
| Move the cursor in the editor | The step on that line is selected and brought into view |
| Save the script or a file it imports | The graph refreshes (after 300 ms); your view, selection and folded blocks stay |
| **Go to main.loom:40** (in the inspector or Focus card) | Shows that line in the editor |
| **Open prompt** | Opens the prompt file of the selected agent step |
| **Export PNG** | Saves the current view as a picture beside the script (`<script>-<workflow>-<view>.png`, at twice the size). Flow saves the whole main path, not only what is on screen |
| **Copy Mermaid** | Puts the workflow's Mermaid diagram on the clipboard (plain boxes: Mermaid has no step pictures) |

If a refresh fails (for example a syntax error), the last good graph stays and a banner shows the error with a link to the file. Missing imports, import cycles and calls to workflows that are not defined are listed under the warning strip and the rest of the graph is still drawn. The **Files** row lists the script and what it imports, with their workflows.

The panel follows your VS Code theme, including dark and high-contrast.

![The Flow view in a dark theme, selecting a loop](https://raw.githubusercontent.com/srijithunni7182/llm4j/main/docs/images/vscode-loom/graph-flow-dark.png)

The graph is built by `weave graph` from the bundled `weave.jar`, the same parser that runs your workflows, so it cannot drift from the language. It needs Java 17 or newer; nothing is run and no model is called.

### Prompt files

When an agent's prompt is a markdown file (`prompt: "researcher"` or `prompt: "researcher@v2"` in the script, from a `prompts/` folder beside it), its steps in the graph show the prompt as a chip (`researcher@v2`), the inspector shows it, and **Open prompt** on a selected step opens the file. The panel opens only the script, its imports and the prompt files of the agents it shows.

**Loom: Create Prompt File** (Command Palette, for `.loom` files) creates the file for a `prompt:` you have written and not yet made: the one on the cursor's line, or the one you pick. It never overwrites a file. Prompt files are described in the Loom guide.

## Snippets

Type a keyword in a `.loom` file and press Tab to get the whole statement with its blanks: `agent`, `agent-guard`, `delegate`, `delegate-expecting`, `loop`, `alt`, `alt-else`, `human-prompt`, `approval`, `run`, `call`, `workflow`, `budget`, `tool`, `tool-class`, `note`, `parallel`, `on-failure`, `import`, `agent-steerable`, `on-stopped`, `intervention`, `supervise`. Each one's description says the habit it encodes (a loop always has a `max`, variable names that are not ordinary words, prompts as files). A test types each snippet with its default values and checks the result with `weave`.

## The guide and the agent skill

**Loom: Open Guide** (Command Palette) shows a page of the written guide: where to start, the ten chapters, or the Loom reference. It comes from the `weave` jar inside this extension, so it works with no repository and no network.

**Loom: Install the Loom Skill in This Project** writes the agent skill and the guide into `.claude/skills/llm4j-workflow-guide` in the folder you have open, so an AI coding agent that reads the project knows the path. It asks before it replaces an older copy.

## Problems

Loom files are checked with `weave check`, the language's own parser (the one that runs your workflows), so the editor and the command line cannot disagree. Its findings show as errors and warnings in the Problems panel and as squiggles, under the source `weave check`, when a `.loom` file is opened and saved. It checks the saved file, so unsaved edits are checked at your next save. A warning means the script runs but something is probably a mistake, for example a result that is stored and never read, or a question to a person whose answer changes nothing.

A key that is not set yet (such as `GEMINI_API_KEY`) is not reported as a problem: the check does not need keys. If Java is missing you are told once, and the problems already shown stay as they were.

### Settings

| Setting | Default | What it does |
|---|---|---|
| `loom.graph.javaPath` | `java` | The Java executable used to run `weave.jar` |
| `loom.graph.timeoutMs` | `30000` | How long to wait for `weave graph` before stopping it |
| `loom.graph.autoRefresh` | `true` | Redraw when the script or a file it imports is saved |

### Command line

The same graph is available outside the editor: `weave graph script.loom` prints JSON, and `--format mermaid` prints a Mermaid flowchart. See the Loom guide.
