# `sandbox`

[← all tools](README.md)

Runs a program on the files of a [`workspace`](workspace.md): the tests of the code an agent edits, a build, a script a
model wrote. The program is a name from an allow list and an argument list (no shell), it runs in the isolation the script
declares, and it sees the workspace and nothing else of the machine. It can also keep a program running (`serve`), such as a
web app another tool then checks. What it stops, and what it does not, is in the
[sandbox's threat model](../../../../docs/security/sandbox.md).

## From Java

```java
Path base = Path.of(".");
Map<String, String> options = Map.of(
        "workspace.root", "project", "isolation", "container", "image", "python:3.12-slim",
        "allow", "python3", "timeout", "5m");
Tool py = new SandboxKind().create("Py", options, base);
py.execute(Map.of("program", "python3", "args", List.of("-m", "pytest", "-q")));
// {"exit": 1, "timed_out": false, "isolation": "container", "stdout": "...", "stderr": "...", "truncated": false, ...}
```

From Java the workspace's options are given with a `workspace.` prefix (`workspace.root`, `workspace.protect`,
`workspace.store`); a Loom script names the workspace tool instead and its options are filled in. `check(options, baseDir)`
returns what is wrong with the options without asking the machine anything; building the tool asks for the container runtime
and fails if it is missing. A result is a JSON object; a refusal comes back as text starting `Error:`.

## From a Loom script: run the tests until they pass

```text
tool Code  { use: workspace  root: "project"  allow: "**"  mode: readwrite  overwrite: true  protect: "tests/**" }
tool Tests { use: sandbox  workspace: Code  isolation: container  image: "python:3.12-slim"  allow: "python3"  timeout: "5m" }
tool Check { use: verify  root: "project" }
agent Coder { model: "gemini-2.5-flash"  system: "You fix the code until the tests pass."  tools: [Code, Tests]  approve: [Code, Tests] }

workflow Fix(issue) {
    delegate "Fix this: {issue}" to Coder -> done
    use Tests(program = "python3", args = "[\"-m\", \"pytest\", \"--junitxml=report.xml\"]") -> run
    use Check(format = "junit", source = "report.xml") -> tests
    return tests.failed
}
```

A script has no list literal, so `args` from a script is the text of a JSON list; an agent passes a real list. Text that is
not a JSON list is refused, never split like a command line. Because `tests/**` is protected, the tests are mounted read
only: the agent cannot make them pass by editing them.

## Options

| Option | Meaning |
|---|---|
| `workspace` | **Required.** The [`workspace`](workspace.md) tool whose files the program works on; its root, protected paths and store are taken from it |
| `isolation` | **Required, no default.** `container` (Docker or Podman), `process` (a child process: not isolation, see below) or `vm` (a backend you plug in). A backend below the level asked for is refused, and `container` never falls back to `process` |
| `allow` | **Required.** The programs the agent may run, by name (`python3, node`). An interpreter can run any command inside the sandbox; the audit says so |
| `image` | With `container`, the image to run, e.g. `python:3.12-slim`. Pin it by digest when it matters |
| `runtime` | `docker` (default) or `podman` |
| `pull` | `true` lets the tool pull a missing image; by default a missing image is refused with the command to pull it |
| `network` | `none` (default), `allow` with `hosts`, or `open`. With `process` it must be `open`: a child process cannot be kept off the network |
| `hosts` | With `network: allow`: the hosts reachable through the egress proxy (`pypi.org, files.pythonhosted.org`, `*.example.com` for subdomains, `host:port` for a port other than 443). Addresses are refused |
| `env.NAME` | A variable the program gets, from a secret or a variable: `env.PIP_INDEX_TOKEN: secret.PYPI`. Looked up on each call, never kept; scrubbed from the output. Nothing else of the environment is passed |
| `timeout` | Time for one program, default 10m, at most 1h. An agent can ask for less, never more |
| `max_output` | Output kept from each of stdout and stderr, default 64k, at most 4m: the start and the end, with what was cut counted |
| `memory`, `pids`, `disk` | With `container`: memory (`512m`), processes (`64`) and the size of `/tmp` (`100m`). Asked of `process`, they are a load error |
| `unattended` | `true` when nobody approves the runs; otherwise the agent must have the tool in its `approve:` list |
| `serve_port` | The port a served program listens on inside the container; enables `serve` |
| `max_served` | Programs served at once, default 1 |

## Actions

| `action` | Arguments | Result |
|---|---|---|
| `run` (default) | `program`, `args`, optional `cwd` (a folder of the workspace), `stdin`, `timeout` | `exit`, `timed_out`, `isolation`, `stdout`, `stderr`, `truncated`, `cut_bytes`, `duration_ms` |
| `serve` | as `run` | `handle`, `port`, `url` (`http://127.0.0.1:<port>/`), `deadline` |
| `stop`, `status` | `handle` | `stopped`, or `running` and `deadline` |

A served program has a **lease**: its handle, port and deadline (the same limit as a run), kept in the workspace's store. It
is stopped on `stop`, at its deadline, and, after a crash, when the next run starts. An [`http`](http.md) tool with
`reaches: Tests` calls it on that one port while it runs; a `workspace restore` waits until it is stopped.

## Isolation

- **`container`**: the workspace is the only mount, protected paths are mounted read only over it, the root file system is
  read only, every capability is dropped, the program runs as the workspace's owner, and `network: none` means no network at
  all. `network: allow` puts the container on an internal network whose only way out is an egress proxy that tunnels to the
  listed hosts and refuses internal, loopback and metadata addresses. Verified on Docker for Linux; where the proxy cannot
  start, `allow` is refused. A container shares the host's kernel: it is not a boundary against a kernel exploit.
- **`process`**: the run's own user, files and network, with an empty environment, a working directory inside the
  workspace, and limits on time and output only. Use it to try a workflow on your own machine; the audit flags it (LA31).
- **`vm`**: a `SandboxBackend` you provide through `ServiceLoader`, for code you trust least.

## What keeps it safe

- **Declared, reported and checked:** the backend reports the level it applied on every result, and a result below the
  declared level is withheld.
- **Every run is journaled:** a resumed or rewound run does not run a program again; one cut short by a crash is reported
  as of unknown outcome, not repeated.
- **Secrets by name only:** a variable's value is looked up when the program runs, passed to the runtime through its own
  environment (never on its command line), and scrubbed from what the program prints.

## What the audit sees

A tool that runs programs (it changes things and is journaled) on private data (the workspace); with a network, it also
reads untrusted content and can send. The note names the isolation, the programs (and which of them can run any command),
the network and its hosts, and the secrets passed in, by name. Rules: LA31 (`process`), LA32 (a served program reached),
LA33 (the network). The program's output may have been written by a model, so `stdout` and `stderr` stay unchecked.
