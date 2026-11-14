# ai-agent4j-tools-git

The `git` tool kind: `status`, `diff`, `log`, `branches`, `branch`, `commit`, and a `push` only where the script allows it,
on a workspace's repository. Add it when an agent edits code in a [`workspace`](../../ai-agent4j-tools/docs/workspace.md)
and the workflow should record or share its work as commits.

It runs the `git` program through the [`sandbox`](../../ai-agent4j-tools/docs/sandbox.md), so it keeps the sandbox's
isolation, limits and journal, and takes the same options for them. It adds no dependency beyond the core, the light
`ai-agent4j-tools` module and Jackson.

## From a Loom script

```text
tool Code { use: workspace  root: "project"  allow: "**"  mode: readwrite  overwrite: true }
tool Repo { use: git  workspace: Code  isolation: container  image: "alpine/git:2.47.2"  network: allow  hosts: "github.com"  push: true  push_branches: "agent/*"  env.GIT_TOKEN: secret.GITHUB_TOKEN }
agent Coder { model: "gemini-2.5-flash"  system: "Fix the issue, commit it on a new branch agent/<short-name> and push it."  tools: [Code, Repo]  approve: [Code, Repo] }
```

## From Java

```java
Tool repo = new GitKind().create("Repo", Map.of(
        "workspace.root", "project", "isolation", "container", "image", "alpine/git:2.47.2"), Path.of("."));
String status = repo.execute(Map.of("action", "status"));
```

## Options and arguments

The sandbox's options work as they do there: `workspace`, `isolation` (required, no default), `image`, `runtime`, `pull`,
`network`, `hosts`, `timeout`, `max_output`, `memory`, `pids`, `disk`, `unattended` and `env.NAME`. The program is always
`git`. Its own options:

| Option | Meaning |
|---|---|
| `push` | `true` lets the agent push; default `false`. A tool that can push must be in the agent's `approve:` list, or `unattended: true` |
| `push_branches` | Required with `push`. The branches a push may go to: `agent/*` (`*` within a part of the name, `**` across). Keep it off the branches people work on |
| `remote` | The remote pushed to, default `origin` |
| `author_name`, `author_email` | Who commits are by, default `llm4j agent` and `agent@localhost` |
| `env.GIT_TOKEN` | A token for an https remote, as a secret (`secret.NAME`). Git reads it from its environment through a credential helper; it is never on a command line or in a result |

| `action` | Arguments | What runs |
|---|---|---|
| `status` (default) | | `git status --porcelain=v1 --branch` |
| `diff` | `staged`, `path` | `git diff --no-ext-diff --no-textconv` |
| `log` | `max` (default 10, at most 50) | one line per commit: hash, author, date, subject |
| `branches` | | the local branches |
| `branch` | `name` | creates the branch and switches to it |
| `commit` | `message`, `paths` (a list; all changes by default) | `git add` then `git commit --no-verify` |
| `push` | `branch` | `git push --no-verify <remote> HEAD:refs/heads/<branch>`, never forced |

The result is `{"action", "exit", "isolation", "stdout", "stderr", "truncated"}`; a branch, commit or push that git refuses
is an error, so it is not recorded as done.

## What it reaches, and what the audit sees

- **A repository's configuration runs nothing:** every call turns off hooks (`core.hooksPath=/dev/null`), fsmonitor, the
  pager, the editor, external diffs and text conversion, and every transport but https. A hook the repository holds never
  runs, even with `isolation: process` (a test proves it with a real `git`). Signing is off, and global attribute files
  are not read. A driver a repository defines (`filter`, a named `diff` or `merge`), a credential helper for one URL,
  a URL rewrite or an included file cannot be turned off for every name it might use, so a `.git/config` with one of
  those sections is refused before git runs, and so is a `.git` that is a file or a link. Code in the sandbox cannot
  add one: every other sandbox mounts `.git` read only, and the `workspace` tool never writes it.
- **Names and paths are checked:** a branch name or path that is an option (`--force`), climbs out (`..`) or uses git's
  path magic (`:(top)`) is refused.
- **Exactly once:** a commit, a new branch and a push are journaled effects; a resumed run does not push again.
- **The audit** sees a tool that changes private data (the workspace), at the sandbox's isolation, and, with `push: true`,
  that can send outward ("pushes to origin, branches agent/*"). Diffs and logs show what is in the repository, which a model
  may have written, so `stdout` and `stderr` stay unchecked.

## Adding it

To a Maven project:

```xml
<dependency>
    <groupId>io.github.srijithunni7182</groupId>
    <artifactId>ai-agent4j-tools-git</artifactId>
    <version>6.2.0</version>
</dependency>
```

To `weave`: `weave run flow.loom --classes ai-agent4j-tools-git-6.2.0.jar`. The image (or, with `isolation: process`, the
machine) must have `git`; `alpine/git` is a small image that does.
