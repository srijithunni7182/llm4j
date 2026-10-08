# `file`

[← all tools](README.md)

## From Java

```java
Tool notes = new FileKind().create("Notes", Map.of("root", "notes", "mode", "readwrite"), Path.of("."));
notes.execute(Map.of("action", "append", "path", "log.md", "content", "- checked at 07:00\n"));
```

`create` takes the options as strings, with any secret already read from your environment or secret store. Before building,
`new FileKind().check(options, baseDir)` returns what is wrong with them (or `null`) without touching the network
or the files. The tool takes its call arguments as a `Map` and returns text; it never throws, and a refusal comes back as text
starting `Error:`.

## From a Loom script

```text
tool Notes   { use: file  root: "notes"  mode: readwrite }
tool Reports { use: file  root: "reports"  mode: write  allow: "*.md, *.json" }
tool Docs    { use: file  root: "docs"  mode: read }
```

## Options and arguments

| Option | Meaning |
|---|---|
| `root` | A directory inside the base directory (default `.`), created on the first write |
| `mode` | `read` (default), `write` (create files and append) or `readwrite` |
| `allow` | File name patterns (default `*.md, *.txt, *.json, *.jsonl, *.csv, *.log`) |
| `overwrite` | Whether `write` may replace an existing file (default false) |
| `max_bytes` | Read cap, default 256k; a write is at most 1 MB |
| `on_unknown` | see [the effect journal](concepts.md#the-effect-journal) |

The agent gives `action`: `read` (with optional `from_line` and `lines`), `list` (optional `path`, `pattern`),
`exists`, `write` or `append`, plus `path` (relative to `root`) and `content`. Writes are atomic (a temporary file,
then a move) and appends are serialised, so parallel branches never interleave lines. Paths that climb out, symbolic
links that lead out, hidden files and directories (`.env`, `.loom-triggers`), the run's own journal and trigger
store, names outside `allow`, and binary files are all refused. There is no way to delete a file.
