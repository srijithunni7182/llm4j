# Design: Prompt Files for Loom Agents

## Layout

```
my-workflow/
  main.loom
  prompts/
    researcher/
      v1.md
      v2.md
    writer.md            # one version, treated as v1
```

A prompt file:

```markdown
---
description: Finds background and recent news for a topic
variables: [topic]
---
You are a careful researcher. Find three reliable sources on {topic} …
```

## Components

| Piece | Module | Responsibility |
|---|---|---|
| `MarkdownFolderPromptRegistry` | `ai-agent4j` (`agent.prompt`) | Reads the folder, implements `PromptRegistry` (`get(id)`, `get(id, version)`, `reload()`); optional watch. Pure, no Loom dependency |
| `PromptFolderResolver` | `ai-agent4j-loom` | Decides the folder: `--prompts`, else script `prompts:`, else `./prompts` beside the script |
| `prompt:` / `prompts:` syntax | parser, `AgentDef`, `LoomScript` | New attribute and top-level declaration; `system_template:` becomes an alias |
| Resolution | `HarnessExecutor.basePrompt` | `prompt` id and pin, then registry, then combine with `system:` |
| Pins | `RunSpec`, `WeaveCLI` | `--prompts`, `--prompt id@vN` on `run`, `check`, `audit`, `graph`, `replay` |
| Validation | `ScriptValidator` | Missing id or version, missing folder, unused files, refused files; messages with suggestions |
| Identity | `AgentIdentity` | Hashes the resolved text and the version |
| Trace | run record and `WorkflowTrace` | `prompt` and `promptVersion` on each agent step |
| Graph | `GraphBuilder`, `graph-render` | Chip `researcher@v2`; `source` of the prompt file for navigation |
| Extension | `vscode-loom` | Open the prompt file from the chip; "Create prompt file" quick fix |

## Decisions

- **Folder and file per prompt, not one YAML.** Reviewable diffs, one owner per file, easy to copy between projects.
  The YAML registry stays for existing hosts.
- **`weave` supplies the registry itself.** The script-only path then has the same abilities as the Java path, so
  the guide does not need two stories.
- **Resolved text in identity.** Otherwise a prompt edit would not start a new evidence epoch, which would break the
  earned-autonomy rules.
- **`system_template:` stays.** It is an alias, so old scripts and Java hosts do not change.
- **No variable substitution beyond what `system:` already does.** `variables` in the front matter is documentation
  and is checked: `weave check` warns when the text uses `{name}` that the workflow does not provide. (Open question 2.)

## Open questions for review

1. Latest = highest `vN`, or an explicit `latest` marker file? Proposed: highest, pins for anything else.
2. Should `variables` be enforced (error) or only checked (warning)? Proposed: warning.
3. Should `--prompt` pins also be allowed in a script (`prompt: "x@v2"` already does this)? Proposed: yes, as it is.
