# Requirements: Prompt Files for Loom Agents

## Introduction

Today an agent's prompt is either inline (`system: "…"` or a `persona { }` block) or a `system_template` id that
works only when a Java host calls `HarnessExecutor.setPromptRegistry(...)`. The `weave` CLI has no way to supply a
registry, so a script-only workflow cannot use versioned prompts, and the workflow guide's stages 3 and 4 (prompt
tests, prompt optimization) silently assume Java.

This feature lets each agent's prompt live in its own markdown file in a structured folder, referenced from the `.loom`
script. `weave`, the VS Code extension and Java hosts all read the same folder format, so there is one way to keep
prompts and one way to version and A/B them.

## Requirements

### Requirement 1: A prompt folder

**User story:** As a workflow author, I keep each agent's prompt in its own markdown file so I can read, review and
version prompts like code.

1. Prompts live under a prompt folder as `<id>/<version>.md` (for example `prompts/researcher/v2.md`), or as
   `<id>.md` for an id with one version, which is version `v1`.
2. An `<id>` is lower-case letters, digits, `-` and `_`; a `<version>` is `v` followed by a whole number. Anything else
   in the folder is ignored and reported as a warning by `weave check`.
3. A file is markdown. An optional YAML front matter block may give `description` and `variables`. The rest of the
   file is the prompt text, verbatim (leading and trailing blank lines trimmed).
4. The latest version of an id is its highest version number, unless a script pins one.
5. A file over 64 KB, or one that is not valid UTF-8, is refused with a message naming the file.

### Requirement 2: Referencing a prompt from a script

1. An agent can say `prompt: "researcher"` (latest) or `prompt: "researcher@v1"` (pinned).
2. `prompt:` replaces nothing that exists: `system:`, `persona:` and `system_template:` keep working. `system_template:`
   is an alias for `prompt:` with no version.
3. When an agent has both `prompt:` and `system:`, the prompt file is the base and `system:` follows it, the same way
   `persona:` and `system:` combine today.
4. A script can name its folder with `prompts: "./prompts"`, relative to the script file. Without it, a `prompts/`
   folder next to the script is used if it exists.
5. The command line can override the folder (`--prompts <dir>`) and can pin versions for one run
   (`--prompt researcher@v2`, repeatable). Order of precedence: command line, then the script, then the convention.

### Requirement 3: Checking

1. `weave check` reports, with file and line: a `prompt:` id that has no file (with the nearest existing ids as a
   suggestion), a pinned version that does not exist (with the versions that do), a `prompts:` folder that does not
   exist, and a prompt file that is refused.
2. `weave check` warns about a prompt file no agent uses.
3. A script that uses `prompt:` with no folder found is an error that says how to supply one, not a fall-back to an empty
   prompt.
4. `weave audit` lists, for each agent, the prompt id and the version it would run.

### Requirement 4: Versions, A/B and drift

1. The resolved prompt text (not only the id) is part of an agent's identity, so editing a prompt file starts a new
   evidence epoch exactly as editing an inline prompt does today.
2. A run records the prompt id and version each agent used, in the trace and the run record.
3. Running the same script with two `--prompt` pins gives a fair A/B: nothing else changes between the runs.
4. The eval4j report shows the prompt id and version beside each agent.

### Requirement 5: One format for Java hosts

1. `ai-agent4j` provides a registry that reads the same folder format and implements `PromptRegistry`, so a Java host
   and `weave` share the files. The existing YAML registry keeps working unchanged.
2. The registry reloads when files change when asked to watch, as the YAML one does.

### Requirement 6: Safety

1. An id or version taken from a script or the command line can never reach a file outside the prompt folder (no `..`,
   no absolute paths, no symlink that leaves the folder).
2. Prompt text is data. It is not interpreted as Loom, and `{name}` interpolation in it follows the same rules as an
   inline `system:` string.
3. Prompt text is never printed by `weave audit` or the graph, only its id, version and a hash.

### Requirement 7: Tooling and docs

1. The graph (JSON, Mermaid, panel, report) shows `prompt id@version` as a chip on an agent step, and the panel
   opens the prompt file from it.
2. The VS Code extension offers a command to create a prompt file for an agent that names one that does not exist.
3. `LOOM_GUIDE.md`, `llms.txt` and the workflow-guide skill describe both paths (script with prompt files, and Java
   embedding), say which stages need Java (none, after this feature), and use prompt files as the default.
4. Stages 3 and 4 of the guide say how to run prompt tests and A/B a prompt with `--prompt`.
