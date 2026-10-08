# Changelog

## 1.0.0

First release on the Visual Studio Marketplace.

- Syntax highlighting, snippets and language configuration for `.loom` and `.loot` files.
- Diagnostics, workflow outline and go-to-definition from the bundled language server.
- **Loom: Show Workflow Graph**: the workflows of a script as a flowchart that follows `import`s and `call`s, with hover details,
  refresh on save and *Copy Mermaid*.
- Commands: Run Workflow, Create Prompt File, Open Guide, and Install the Loom Skill in This Project (so a coding agent writes Loom
  correctly), all through the bundled `weave` command line, which needs Java 17 or newer on the machine.
