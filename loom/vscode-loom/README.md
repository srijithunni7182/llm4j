# vscode-loom

VS Code extension for Loom DSL (.loom and .loot files). Provides syntax highlighting, LSP diagnostics, workflow outline, a workflow graph, and run command.

## Workflow graph

**Loom: Show Workflow Graph** draws the workflows of the active `.loom` file as a flowchart, beside the editor. It follows `import`s, so a workflow that `call`s another file's workflow shows where that workflow is defined, and you can open it.

Open it from the Command Palette, from the graph icon in the editor title bar, or from the editor's right-click menu. The command is offered only for `.loom` files.

What you see:

- **One node per step**, drawn by kind: agent steps (`delegate`, `handoff`, `broadcast`), tasks (`run`), `parallel` rounds, `alt` branches, `loop` and `for each`, `call`, `ask a person`, `checkpoint` and `rewind`, `guardrail`, `decide`, `observe` and `note`. A **Legend** button lists the colours.
- **Settings as chips** on each step: `retry 3 · 2s`, `timeout 90s`, `budget 5k tok`, `expects {score, notes}`, a loop's `max 3`, `→ variable`, and so on (at most three, then `+n`).
- **Agent details** when you hover or focus a step that names an agent: model, tools, approval rules, budget. A step whose agent needs approval shows a hand.
- **Edges** with labels: `then` and `else`, `again` and `done` on loops, `failure` and `exhausted` for handler blocks.

Working with it:

| You do | What happens |
|---|---|
| Click a step | The editor shows its line, beside the panel |
| Double-click a `call` (or Shift+Enter) | The panel shows the called workflow; **Back** returns |
| Ctrl/Cmd-click a `call` | The editor opens the called workflow's source |
| Move the cursor in the editor | The step on that line is highlighted |
| Save the script or a file it imports | The graph refreshes (after 300 ms); your zoom and selection stay |
| **Copy Mermaid** | Puts the workflow's Mermaid diagram on the clipboard |

If a refresh fails (for example a syntax error), the last good graph stays and a banner shows the error with a link to the file. Missing imports, import cycles and calls to workflows that are not defined are listed under the warning strip and the rest of the graph is still drawn.

The graph is built by `weave graph` from the bundled `weave.jar`, the same parser that runs your workflows, so it cannot drift from the language. It needs Java 17 or newer; nothing is run and no model is called.

### Prompt files

When an agent's prompt is a markdown file (`prompt: "researcher"` or `prompt: "researcher@v2"` in the script, from a `prompts/` folder beside it), its steps in the graph show the prompt as a chip (`researcher@v2`), the agent's details card shows it, and **Open prompt** on a selected step opens the file. The panel opens only the script, its imports and the prompt files of the agents it shows.

**Loom: Create Prompt File** (Command Palette, for `.loom` files) creates the file for a `prompt:` you have written and not yet made: the one on the cursor's line, or the one you pick. It never overwrites a file. Prompt files are described in the Loom guide.

### Settings

| Setting | Default | What it does |
|---|---|---|
| `loom.graph.javaPath` | `java` | The Java executable used to run `weave.jar` |
| `loom.graph.timeoutMs` | `30000` | How long to wait for `weave graph` before stopping it |
| `loom.graph.autoRefresh` | `true` | Redraw when the script or a file it imports is saved |

### Command line

The same graph is available outside the editor: `weave graph script.loom` prints JSON, and `--format mermaid` prints a Mermaid flowchart. See the Loom guide.
