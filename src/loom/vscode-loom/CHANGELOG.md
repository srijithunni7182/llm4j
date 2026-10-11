# Changelog

## Unreleased

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
