# `shell`

[← all tools](README.md)

## From Java

```java
Tool ops = new ShellKind().create("Ops", Map.of("allow", "df, du", "timeout", "30s", "unattended", "true"), Path.of("."));
ops.execute(Map.of("program", "df", "args", List.of("-h")));
```

`unattended: "true"` says nobody approves the calls. If your host asks a person first (as Loom does with `approve:`),
leave it out and use `kind.agentProblem(options, toolName, agentName, approved)` to enforce the rule when you wire
an agent to the tool.

`create` takes the options as strings, with any secret already read from your environment or secret store. Before building,
`new ShellKind().check(options, baseDir)` returns what is wrong with them (or `null`) without touching the network
or the files. The tool takes its call arguments as a `Map` and returns text; it never throws, and a refusal comes back as text
starting `Error:`.

## From a Loom script

```text
tool Ops {
    use: shell
    allow: "df, du, ls"
    cwd: "work"
    timeout: 30s
}
agent Operator { model: "gemini-2.5-flash"  tools: [Ops]  approve: [Ops] }
```

## Options and arguments

| Option | Meaning |
|---|---|
| `allow` | The program names the agent may run. Required. |
| `cwd` | Working directory, inside the base directory |
| `env_pass` | Environment variables to pass on. The child gets only `PATH`, `LANG` and `TZ` otherwise, and their values are scrubbed from results. |
| `max_output` | Cap on standard output and on standard error, each (default 64k) |
| `unattended` | Acknowledges that nobody approves calls (below) |
| `allow_interpreters` | Allows shells and interpreters in `allow` (below) |
| `on_unknown`, `timeout` | see [concepts](concepts.md) |

The agent gives `program` (a name from `allow`, never a path) and `args` (a list of strings). The program is
started directly with those arguments and **no shell**, so quoting, `;`, `|`, `>`, `$( )` and wildcards mean
nothing. Each name in `allow` is looked up on the `PATH` when the tool is checked or built. Shells and anything that runs other
programs (`sh`, `bash`, `python`, `env`, `xargs`, `find`, `sed`, `awk`, `sudo`, `ssh`, `make`, …) are refused in
`allow` unless `allow_interpreters: true`, because allowing one allows everything. The result is `exit <code>`, then
standard output, then standard error. On a timeout the whole process tree is killed.

Because it runs code on this machine, **an agent must have a person approve its calls** (in Loom, by listing the tool under
`approve:`), or the tool must say `unattended: true`; otherwise `agentProblem` reports it (and a Loom script doesn't load). Supported on Linux and macOS; on Windows it is refused when the tool is checked.

Allowing a program trusts it with **any arguments the agent chooses**. `df`, `du` and `ls` only read; but `git` can
run hooks and aliases, and `tar`, `curl`, `rsync`, `cp` and `mv` can write anywhere the user can. Allow only programs
that are safe whatever they are given, keep `approve:` on for the rest, and run unattended workflows as a user that
can't harm anything it shouldn't.
