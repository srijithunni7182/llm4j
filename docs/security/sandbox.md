# The sandbox: what it stops and what it does not

[Security overview](../../SECURITY.md) · [Building blocks](building-blocks.md) · [Securing a workflow](securing-workflows.md) · [`weave audit`](weave-audit.md) · [OWASP Top 10 and gaps](owasp-llm-top-10.md) · [The sandbox](sandbox.md)

The `sandbox` tool runs a program a model chose, on the files of a `workspace`, so an agent can run tests, build a
project or try a script. It is the largest attack surface in the project: whatever the program does, it does with
whatever the sandbox gives it. This page says what each part of it stops, what it does not, and what is left to you.
How to use the tool is in [its page](../../src/tools/ai-agent4j-tools/docs/sandbox.md).

```loom
tool Data { use: workspace  root: "project"  allow: "**"  mode: readwrite  protect: "tests/**" }
tool Py   { use: sandbox  workspace: Data  isolation: container  image: "python:3.12-slim"  allow: "python3"
            network: allow  hosts: "pypi.org, files.pythonhosted.org"  env.PIP_INDEX_TOKEN: secret.PYPI }
```

## What the script declares, and what is checked

- **Isolation is declared, never assumed.** There is no default: a script writes `isolation: process`, `container` or
  `vm`. The backend reports the level it applied, the tool refuses at load a backend below the declared level, and a
  result reported below it is stopped and its output withheld. `container` never falls back to `process`: a missing
  Docker or Podman is a load error that says what to install.
- **The program is a name from the `allow` list and an argument list.** No shell reads it, so quoting, pipes and
  `$(…)` mean nothing. An interpreter on the list (`python3`, `sh`, `node`) can run any command *inside the sandbox*;
  the audit says so.
- **The environment is empty** but for the variables the script names. A secret reaches a program only through
  `env.NAME: secret.REF` (or `env.REF`): it is looked up on each call, never kept by the tool, passed to the container
  runtime by name rather than on its command line, and scrubbed from the program's output. The audit lists it by name.
- **Limits are relaxed but bounded:** ten minutes by default and an hour at most, which an agent can only lower; output
  keeps its start and its end (a build's errors are at the end) and says how much was cut; with `container`, memory,
  processes and the size of `/tmp` too.
- **Every run is an effect.** It is journaled, so a resumed or rewound run does not run it again; a run cut short by a
  crash is reported as of unknown outcome rather than repeated.

## `isolation: container`

The container runtime is driven through its command line (`docker` or `podman`, no client library). Each run gets:

| Setting | What it stops |
|---|---|
| The workspace as the **only mount**, at `/workspace` | The program sees none of the host's files but the workspace's |
| Protected paths (`protect:` on the workspace) **mounted read only** over it | A program that edits the tests it is judged by |
| Every `.git` folder **mounted read only** (only the `git` tool's own sandbox writes it) | A program that plants a hook, a filter driver or a URL rewrite in `.git/config` for git to run later |
| `--read-only` root and a small `/tmp` | Changes to the image that outlive the run |
| `--cap-drop ALL`, `no-new-privileges`, the workspace owner's user id | Privilege inside the container; files the host user can't remove |
| `--network none` (the default) | Any network at all |
| `network: allow`: an `--internal` network and an **egress proxy** | Any host but the listed ones, and any internal, loopback or metadata address even for a listed name |

**What it does not stop.**
- **A container is not a boundary against a kernel exploit.** The program shares the host's kernel. For code from
  people you don't trust at all, use a `vm` backend (an SPI you plug in) or a separate machine.
- **The image is yours.** The tool runs the image the script names and pulls it only with `pull: true`. Pin it by digest
  when it matters, and know who publishes it.
- **The workspace is writable.** Anything in it that is not protected, the program can change or delete; take a
  `workspace snapshot` first if you will want it back.
- **The egress proxy sees hosts, not paths.** It tunnels `CONNECT host:port` to listed hosts and never decrypts, so it
  cannot tell one repository on a host from another. A listed host that lets anyone publish (a package index, a code
  host, a wildcard such as `*.example.com`) is a way to send data out; LA33 calls a wildcard out as wide.
- **The proxy was verified on Docker for Linux.** A container on an internal network reaches the proxy on the
  network's gateway and nothing else (`EgressProxyDockerTest`). Docker Desktop and rootless Podman were not verified:
  where the proxy cannot start, `network: allow` is refused rather than opened.

## `isolation: process`

A child process of the run, with the run's user, its files and its network, a scrubbed environment, a working
directory inside the workspace, and limits on time and output. **That is not isolation.** It cannot keep the program
off the network, so the script must write `network: open` to say so; it cannot enforce memory, processes, disk or
read-only paths, so asking for any of them is a load error. It is there to try a workflow on your own machine. LA31
flags it: medium, and high when a secret is passed in.

## Programs that keep running: `serve`

`serve` starts a program and keeps it running under a **lease**: a handle, the loopback port the runtime published it
on, a deadline (the same bounded limit as a run) and its owner, recorded in the workspace's store, which is always outside the
workspace root, so a program in the sandbox cannot write a lease or a snapshot of its own. It is stopped on
`stop`, at its deadline, and, when a run crashed, when the tool is next built. `workspace restore` is refused while a
lease is live.

An `http` tool reaches it with `reaches: Py` in place of a `base_url`. It is granted **exactly `127.0.0.1` and that
one port, while the lease lives**, checked on every request and redirect; any other loopback port, `localhost`, a
private or a public host is refused. There is no `allow_loopback` switch: it would open every service on the machine.
Each reach is audited (`served_port_reached`), and LA32 names it. What the served program answers is unchecked model text,
like the sandbox's own output: a model may have written the program, so the answer cannot leave the workflow as the
tool's own data.

## The audit

| Rule | Finds |
|---|---|
| LA31 | A program run at `isolation: process` |
| LA32 | A workflow that reaches a process it started |
| LA33 | A sandbox that reaches the network (`allow` with its hosts, or `open`) |

No rule is needed for the lethal trifecta: a sandbox with a network can send, a workspace reaches private data, and
the existing check (LA01) sees an agent that holds both with something that reads the outside world.

---

[Security overview](../../SECURITY.md) · [Building blocks](building-blocks.md) · [Securing a workflow](securing-workflows.md) · [`weave audit`](weave-audit.md) · [OWASP Top 10 and gaps](owasp-llm-top-10.md) · [The sandbox](sandbox.md)
