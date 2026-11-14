# `workspace`

[← all tools](README.md)

A directory an agent reads and edits: a report it revises, a configuration it changes, code it fixes. Beside
[`file`](file.md), which stays as it is, `workspace` adds exact edits (`patch`), search (`find`, `grep`), and snapshots a
script can restore to try again from a clean state. Use it for any "edit, check, try again" loop.

## From Java

```java
Tool data = new WorkspaceKind().create("Data", Map.of(
        "root", "work", "allow", "**", "mode", "readwrite", "protect", "tests/**", "store", "snapshots"), Path.of("."));
data.execute(Map.of("action", "read", "path", "report.md"));   // {"path": ..., "version": "3f2a...", "content": ...}
```

`create` takes the options as strings; `new WorkspaceKind().check(options, baseDir)` returns what is wrong with them (or
`null`). Every result is a JSON object; a refusal comes back as text starting `Error:`.

## From a Loom script: trying again from a clean state

```text
tool Data  { use: workspace  root: "work"  allow: "**"  mode: readwrite  overwrite: true  store: "snapshots" }
tool Check { use: verify  root: "work" }
agent Writer { model: "gemini-2.5-flash"  system: "You fix the report."  tools: [Data]  approve: [Data] }

workflow Fix() {
    use Data(action = "snapshot", name = "clean")
    checkpoint Clean starting with feedback = "none"
    use Data(action = "restore", name = "clean")
    delegate "Make report.json valid. Feedback: {feedback}" to Writer -> done
    use Check(format = "schema", schema = "report.schema.json", source = "report.json") -> result
    rewind to Clean when (result.failed > 0) at most 2 times carrying feedback = "{result.items}" side effects: repeat
}
```

Restore is something the script does, not something a rewind does by itself: the snapshot is taken once, before the
checkpoint; every attempt starts by restoring it (the first time it changes nothing); and `side effects: repeat` lets the
attempt's writes run again. With `keep`, a rewind would take the second restore for the first one already done and skip it.
Use this when an attempt touches only the workspace (and a sandbox); an attempt that also sends a message keeps `ask first`.

## Options

| Option | Meaning |
|---|---|
| `allow` | **Required.** Path globs the agent may read and write: `**` for all, `*.md` for a name at any depth, `docs/**` for a folder |
| `root` | The directory, inside the script's, default `.` |
| `mode` | `read` (default), `write` or `readwrite` |
| `overwrite` | Whether `write` may replace a file, default `false` |
| `protect` | Path globs that are read only (`tests/**, oracle/**`): what judges the agent's work stays out of its reach |
| `ignore` | Path globs left out of `list`, `find`, `grep`, snapshots and `diff`. Default `node_modules/**, target/**, build/**, dist/**, .git/**, __pycache__/**, .venv/**` |
| `store` | Where snapshots (and a sandbox's leases) are kept, inside the script's directory but always outside `root`, since a sandbox mounts the root read-write. Default `.loom-workspace/<tool>` beside the script; when that would be inside `root` (as with `root: "."`), the tool does not load and asks for a `root:` of its own |
| `max_bytes` | Largest file read, written or searched, default 256k, at most 8m |
| `max_files`, `max_snapshot_bytes` | The snapshot cap: default 5000 files and 64m, at most 100000 and 2g |
| `snapshots` | How many snapshots may be kept, default 8, at most 32; the same name replaces the older one |
| `max_matches` | Most entries, paths, matches or differences returned, default 200, at most 5000 (`truncated` says when there were more) |
| `on_unknown` | `skip` (default) or `retry`: what a resumed run does with a write whose outcome is unknown |

## Actions

| `action` | Arguments | Result |
|---|---|---|
| `read` | `path`, `from_line`, `lines` | `content` and `version` (a hash of the whole file); a file that is not text is refused with its size |
| `list`, `exists` | `path` | `entries` (directories, and only the files that may be read), or `exists` |
| `write`, `append` | `path`, `content` | `version`, `bytes`; `append` adds a line break where one is missing |
| `patch` | `path`, `edits` (a list of `{old, new}`), `version` (optional) | Each `old` must appear exactly once in the file; all edits apply, against the original, or none do. Overlapping edits and a `version` that does not match are refused |
| `find` | `pattern` (a glob) | `paths`: only files that may be read (inside `allow`, not a key or `.env` file), so a name the agent may not read is never shown |
| `grep` | `text` (a literal) or `regex`, optional `path` (a glob) and `context` (lines, at most 5) | `matches`: path, line, text |
| `diff` | `name` (the latest snapshot when left out) | `added`, `changed`, `deleted` |
| `snapshot`, `restore` | `name` | A restore puts the snapshot's files back and deletes files made since; it leaves ignored and protected paths alone, and restoring twice gives the same state. A restore is refused while a [`sandbox`](sandbox.md) serves a program from the workspace |

## What keeps it safe

- **Inside the root, and no link is ever followed:** a program run in a sandbox can make a link in the workspace, and a tool
  on the host that followed it would read or write the host. A path through a link is refused; a snapshot of a workspace
  that holds a link is refused; a restore removes a link made since instead of writing through it.
- **A deny list `allow` cannot override:** nothing under `.git` is written (a hook there would run on the host the next time
  `git` does), and `.env*`, `*.pem`, `*.key` and `id_*` are never read.
- **Protected paths are read only** through every action, so a model cannot edit the test that judges it.
- **Snapshots are checked:** files are stored once by their SHA-256, and a restore checks every stored file before it changes
  anything; a store changed since fails the restore and names the file. Every path in a snapshot is checked too: a path no
  snapshot of this workspace could hold (outside the root, not in plain form, under `.git`, protected or ignored) refuses
  the whole restore. A snapshot never holds `.git`, whatever `ignore:` says. A workspace over the cap takes no snapshot at all.
- **Exactly once:** writes, patches, snapshots and restores are journaled, so a resumed run does not do them twice and reuses
  the recorded snapshot; a restore cut short by a crash is run again (it is safe to repeat).
- **Bounded:** a regular expression longer than 200 characters is refused, and a search that takes too long stops.

## What the audit sees

A tool that reaches private data (the workspace) and changes it unless `mode: read`; no network. The note names the root and
the protected paths. The text it returns (contents, names, matches) may have been written by a model, so it stays unchecked.
