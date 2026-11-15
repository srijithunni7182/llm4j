# `command`

[← all tools](README.md)

Runs **one command line that you wrote**, with typed holes that the model fills. Where [`sandbox`](sandbox.md) lets the model
choose a program and its arguments, `command` lets it choose only values, each of a type you gave: a file in the workspace,
a number in a range, one word from a list. Use it when an agent should be able to do exactly one thing (resize a picture,
convert a file, run a formatter) and nothing near it. The reasons it is safe are in
[Securing integrations](../../../../docs/security/integrations.md).

## From Java

```java
Tool resize = new CommandKind().create("Resize", Map.of(
        "workspace.root", "photos", "isolation", "container", "image", "dpokidov/imagemagick:7",
        "run", "convert {input} -resize {percent}% {output}",
        "param.input", "path in", "param.output", "path out", "param.percent", "int 1..400"), Path.of("."));
resize.execute(Map.of("input", "cat.png", "output", "small.png", "percent", 50));
// the text the program printed, or "Error: ..." naming the rule that refused the call
```

## From a Loom script: resize a picture

```text
tool Photos { use: workspace  root: "photos"  allow: "**"  mode: readwrite }
tool Resize {
    use: command
    isolation: container
    image: "dpokidov/imagemagick:7"
    workspace: Photos
    run: "convert {input} -resize {percent}% {output}"
    param.input: "path in"
    param.output: "path out"
    param.percent: "int 1..400"
}
agent Editor { model: "claude-haiku-5-5"  system: "You resize photos."  tools: [Resize]  approve: [Resize] }
workflow Main(request) { delegate "{request}" to Editor -> done }
```

## Options

| Option | Meaning |
|---|---|
| `run` | **Required.** The command line. Split into words once, when the script loads; the first word is the program and cannot be a placeholder. Each `{name}` is filled inside its own word |
| `param.<name>` | The type of each `{name}` (below). Every placeholder needs one, and every parameter is required except a `bool` |
| `isolation` | **Required.** `container` (with `image`) or `process` (needs `network: open`, flagged by the audit). There is no `none` |
| `image`, `runtime`, `pull` | For `container`: the image, `docker` or `podman`, and whether to pull a missing image (never by default) |
| `workspace` | In a Loom script, the `workspace` tool whose folder the command works in (from Java, `workspace.root` and the other `workspace.` options) |
| `network` | `none` (the default), `allow` with `hosts`, or `open` |
| `timeout`, `max_output` | As for [`sandbox`](sandbox.md): ten minutes at most; the output keeps its start and its end |
| `output` | `text` (the default), `json` (the output must be JSON, else the call fails) or `lines` (a JSON list of the output's lines) |
| `ok_exit` | Exit codes that are not a failure, such as `"0,1"` |
| `on_unknown` | What a resumed run does with a call whose outcome is not known: `skip` (the default) or `retry` |

## Parameter types

| Type | Accepts |
|---|---|
| `path in` | A file that exists inside the workspace. No `..`, no absolute path, no symlink that leaves it |
| `path out` | A file name inside the workspace, to be written |
| `int 1..400` | A whole number in the range |
| `number 0.5..2` | A number in the range |
| `enum a\|b\|c` | One of the words |
| `string max=40 pattern=[a-z]+` | Text, at most `max` characters and matching `pattern` |
| `bool "--flag"` | `true` adds the flag, `false` adds nothing |

A missing, extra or mistyped argument is refused before the program runs, and the message names the rule.

## What keeps it safe

- **One argument per value.** A value that holds a space, `;`, `--flag`, `$(...)`, a backtick or a newline is still one
  argument: nothing re-reads it, and no shell is involved.
- **The template is never replaced by anything else.** The text of `run` is not passed through the `{variable}` replacement
  of a Loom script, so a workflow variable that shares a parameter's name changes nothing.
- **A path stays in the workspace**, and the workspace is the only thing a container mounts.
- **Isolation is required**, and a backend that cannot keep the command off the network refuses `network: none`.
- **It is journaled**, so a resumed or rewound run does not run it again, and an agent that has it is expected to carry `approve:`.

## What the audit sees

A `command` tool counts as changing something. At `isolation: process` the audit raises LA31 (a program runs without a
sandbox) and names the program, which is the first word of `run`; with `network: open` it raises LA33.
