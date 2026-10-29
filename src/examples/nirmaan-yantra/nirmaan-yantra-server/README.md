# nirmaan-yantra-server: the software factory, as a Loom workflow

The whole factory is one short Loom workflow. Open `src/main/resources/main.loom` in VS Code and run **Loom: Show Workflow Graph**.
It reads top to bottom, in six phases (the numbered comments), with a single loop:

```
1 Plan      Aditi (PRD) -> Rishi (spec with the exact commands)
2 Prepare   Vihaan: first failing test  |  Drishti: end to end suite        (written at the same time)
3 Build     red -> Vihaan writes the code -> Fix the unit tests
4 Review    code checks tests exist -> Vishnu -> (one polish if rejected, then Fix again)
5 Assure    clean build (one command, no model) -> Fix the end to end suite -> Fix the unit tests again
6 Release   Vishnu signs off (a person decides if he refuses) -> zip
```

`Fix` (`flows/fix.loom`) is the only place anything is retried: run a command, let Vihaan fix the code, up to 3 times, then ask a
person whether to carry on or stop. It is used for the unit tests and the end to end suite, not for the clean build (one command, no
model; a person decides if it fails).

| File | What it is |
|---|---|
| `src/main/resources/main.loom` | The story. Holds the run budget and the audit log. |
| `agents/crew.loom` | The five agents: model, temperature, tools and the shape of each answer. |
| `flows/fix.loom` | The shared "make this command pass" step. |
| `prompts/*.md` | One prompt per agent, versioned files. |
| `src/main/java/.../tasks` | The steps that must be exact, as plain Java: they never involve a model. |
| `src/main/java/.../host` | The web host: runs the workflow for the UI and streams what happens. |
| `src/test/resources/eval/golden` | The golden dataset (a first draft: review it with the people who own this factory). |
| `COST.md` | What a run costs and what stops it. |
| `sandbox/Dockerfile` | The image for the optional container sandbox (not yet built or run). |

## Agents and code

Models read and write; code decides and acts.

- **Dhruv is not an agent.** Building and running tests is the `RunCommand` task. Commands are checked against an allow list
  (`CommandPolicy`), started with no shell and a clean environment (no API keys; a private tool home instead of your real `HOME`), and
  cut off after 5 minutes. A command it cannot vouch for waits for a person; a destructive one is refused.
- **Every check fails closed.** The workflow asks `x is ok`, which Loom answers true only when the step's task calls its outcome ok
  (`RunCommand` says `passed`). A timeout, a refused command, an unexpected word or a step that never ran is not ok. A step Loom only
  *described* in a simulated run (`weave eval --mock`) counts as ok, so the free tests reach the end without weakening a real run.
  Stopping on purpose is `halt "reason"`, handing a result back is `return { ... }`: both are Loom statements, not tasks.
- **Tests are locked.** `SaveFiles` refuses a batch that changes an existing test, and nothing but Drishti may write under `e2e-tests/`.
  A fix cannot make a command pass by weakening its test. A test that is truly wrong is a person's call at the end of `Fix`.
- Files are written by `SaveFiles` (paths cannot leave the project), the spec is stored as data by `SaveSpec`, and "do unit tests
  exist?" is `CheckTestsExist`.

## Checking it (free, no key needed)

```bash
mvn compile            # the tasks must be compiled first: weave finds them in target/classes
W="java -cp target/classes:<path to ai-agent4j-loom-6.1.0-cli.jar> io.github.llm4j.loom.cli.WeaveCLI"
$W check src/main/resources/main.loom --no-env
$W explain src/main/resources/main.loom
$W audit src/main/resources/main.loom
$W eval src/main/resources/main.loom --dataset src/test/resources/eval/golden --mock
mvn test               # unit tests for the tasks + the golden scenarios on a mock model + end to end trajectory tests
```

`FactoryTrajectoryTest` runs the real workflow and the real tasks (real `python3` builds in a temporary workspace) against a scripted
model, and checks the paths the mock run never takes: a fix loop that succeeds on its third try, a refused edit to a test, a person
stopping or overriding after three failed fixes, a locked end to end suite, a polish after a rejected review, a refused release, an
approval answered yes or no, and the budget question answered yes or no.

## Running it for real

Nothing in this project has run against a real model yet. The first run should be small and capped (see `COST.md`):
set `GEMINI_API_KEY` (see `.env.example`), then run with `--max-cost` and a `--journal`.

Generated code runs on your machine. The command policy and the project folder keep the *factory* honest; they are not a jail.
For ideas you do not trust, use the container sandbox:

```bash
docker build -t nirmaan-sandbox:latest sandbox/
NIRMAAN_SANDBOX=docker NIRMAAN_REQUIRE_CONTAINER=true ...
```

| Setting | Meaning |
|---|---|
| `NIRMAAN_WORKSPACE` | Where projects are built (default `./workspace`). |
| `NIRMAAN_SANDBOX` | `local` (default), `docker` or `podman`. |
| `NIRMAAN_SANDBOX_IMAGE` / `_NETWORK` / `_MEMORY` / `_CPUS` | The image (default `nirmaan-sandbox:latest`), network (`bridge`; `none` for no downloads), 4g, 2 cpus. |
| `NIRMAAN_REQUIRE_CONTAINER` | `true` refuses to run any command outside a container. |
| `NIRMAAN_TOOL_HOME` | Where tools keep caches: a folder, or `per-project` (default: one shared folder inside the workspace). |
| `NIRMAAN_COMMAND_TIMEOUT_SECONDS` | Per command (default 300). |

## Things to know

- Loom does not process `\n` in string literals: it reaches the model as the two characters backslash and `n`. The prompts tell the
  agents to read it as a line break.
- `weave check` warns "set and never used" for results consumed only as `run` arguments (for example `red_out.files` passed to
  `SaveFiles`). That is the linter not reading `run` arguments, not a mistake in the workflow.
- Python caches compiled code by source size and whole-second timestamp, so a fix that rewrites a file to the same size within a second
  would run the old code. The tool environment sets `PYTHONDONTWRITEBYTECODE=1` for that reason (the trajectory tests found it).

## The web UI

`../start_nirmaan.sh` builds what is out of date and starts the web host; then open **http://127.0.0.1:8080** (`NIRMAAN_PORT` changes it).
The page is the existing React UI, now driven by the new workflow:

- the crew narrates the run in the chat (Aditi, Rishi, Vihaan, Drishti, Vishnu; **Dhruv** is the command runner, so his lines are the
  commands that ran and whether they passed);
- when the factory needs a person (approve an unfamiliar command, "carry on anyway?", extend the budget) a decision panel appears with
  the question and its exact choices; answer there;
- the header shows the status, what the run has cost so far and calls made, and a **Stop** button;
- the Artifacts panel lists the project's files as they appear, and the finished project downloads as a zip;
- a warning appears in the chat if an agent's answer was cut off at the output limit.

How it is built (`src/main/java/.../host`): the JDK's own HTTP server, no web framework. `FactoryHost` starts a run on a thread
(`HostRun`) with the same executor, tasks, journal and price table as `weave run`, and turns the trace into chat lines (`UiEvents`).
Every run is also reported to a Fabric server when `NIRMAAN_FABRIC_URL` is set (it uses the secret store's Fabric client key).

What keeps it safe: it listens on loopback only (a non-loopback address is refused, because there is no sign-in); the `Host` header
must be its own address (DNS rebinding) and an `Origin` header must be its own origin (another website cannot start a run, which spends
money); one run at a time; an idea is at most 4000 characters; project files are served as plain text with `nosniff`, so code an agent
wrote is never run by your browser; a path cannot leave the project folder.

The model key is read from `.loom/secrets.store` (see "Deploying and observing") or the environment. Nothing about the key reaches the browser.

Status: the workflow, tasks, prompts, tests and the web host are done. Known gap: Rishi chooses a toolchain without knowing what this
machine has (the first real run chose `pytest` and `pip install`, neither usable here). Until a toolchain step exists, say what to use in
the idea, for example "use only the Python standard library and unittest; no pip".

## Deploying and observing (decided 2026-10-15)

- **Observing:** Fabric on this computer only (`http://127.0.0.1:9091`, loopback, no TLS needed), process name `nirmaan`, detail `truncated`
  (the first 500 characters of what agents say; it never leaves this machine).
- **Keys:** the Gemini key lives in an encrypted store, `.loom/secrets.store`, with its master key in `.loom/secrets.key` (mode 600; the
  folder is mode 700 and git-ignored). The key is locked to `generativelanguage.googleapis.com`. It is never in a script, a `.env`, a command
  line or the chat. The same store holds the process's Fabric client key. Anyone who can read both files can decrypt the store, so this
  protects against leaks into git, backups of the store alone and chat history, not against someone with read access to your account.
- **Fabric:** data in `fabric-data/` (git-ignored). The admin password was generated into `fabric-data/admin-password.txt` (mode 600).
  `master.key` sits next to the vault for development; move it before any permanent deployment.
- **Spend:** the run budget in `main.loom` is 1,500,000 tokens, 100 calls and **$3.00** (`cost` needs `--prices prices.properties`, whose model names must match the script's exactly),
  and asks a person to extend it when used up. Also set a spending limit with the provider: the script's budget is checked before each
  model call, not against your invoice.
- **Code sandbox:** generated code is built and run on this machine, in `workspace/<project id>/`, as your user (a clean environment and a
  private tool home, not a jail). Decided by the project owner: acceptable for small ideas they trust. Not for ideas from strangers.
- **Security audit:** `weave audit` reports 0 findings and no agent holds the lethal trifecta. The audit does not read task code
  (`RunCommand` executes what the agents write), so that is covered by the command policy and its tests, not by the audit.
- **Rules, windows, prices:** default rules (no `.inspect` file). Gemini context windows are not in Loom's catalogue, so the context gauge
  shows a hatched bar until you give it a `main.windows` file. Prices come from `prices.properties`.
