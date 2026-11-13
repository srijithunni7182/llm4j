# Phase 35: design

Status: **final for phase 35 (all decisions taken; D23 deferred); not yet built.** Facts marked (read) come from the merged source on `main` after PR 119. Anything marked (not read) must be read before the work that depends on it starts. Principles: [PRINCIPLES.md](PRINCIPLES.md). Choices: [DECISIONS.md](DECISIONS.md).

## 1. What the code does today (read)

**A tool kind.** `ToolKind` (core, `agent/tool`) is `name`, `required`, `optional`, `prefixes`, `secrets`, `agentProblem`, a load-time `check` that touches nothing, and two `create` methods (one with an `EffectContext` for a recorded run). `GenericKind` (tools module) is the base class; `EffectTool` wraps an effectful tool and journals `effect_pending`, `effect_done` and `effect_failed`, which gives exactly-once behaviour and simulation. `Tool.execute` returns a **`String`**: a tool result is text.

**The tools module** has about 4,400 lines in 38 files and depends only on the core and Jackson (the mail library is optional). Kinds: `file` (read, list, write; `mode`, `allow` globs, `overwrite`, `max_bytes`), `http`, `webhook`, `shell`, `sql`, `email`. `ShellKind` refuses interpreters (`sh`, `bash`, `node`, `perl`, `env` and similar): allowing one allows anything. That is why a development workflow cannot run `npm` today. `NetPolicy` refuses loopback, private and metadata addresses, with one exception: when the declared base host is itself `localhost` or a loopback address, loopback to it is allowed.

**Loom registers kinds by hand** (`ToolFactory`, one line per kind, through `GenericKindAdapter`). Knowledge of kind **names** is kept in about eight further places: `security/Capabilities` (a `switch`; an unknown kind is "not one the audit knows", assumed to reach everything), `ToolFacts`, `WhatIf`, `ScriptedTools` (the `weave test` tool scripting), `SafeDefaults`, `PlanChecker`, `SecurityAudit` and `RewindChecks` (`EFFECT_KINDS`).

**Audit model.** A tool is three booleans (reads untrusted content, reaches private data, sends or changes something outward) plus an effect level (`TaskEffect`: none, reads, changes, from the core) and where the effect came from (declared or assumed). There is no isolation field.

**Rewind.** `RewindStmt.Effects` is `ASK_FIRST`, `KEEP`, `REPEAT`. It is implemented purely through journal identity (`Generations.identity`): `keep` finds the effect already done, `repeat` runs it again. **Nothing is undone.**

**eval4j** is a library of data and criteria (about 17,000 lines); it does not run the evaluation. `Criterion` is a small SPI (a deterministic check or a judged metric returning score, pass or fail and reasons). `EvalScenario` is flat: one input and its expectations. The runner is Loom's `weave eval` (`eval/` package): Wilson 95% intervals on pass rates, `--repetitions`, a cost tail (median, p95, runaway rate), judge validation and position-bias checks. `EvalRun`, `RunWriter` and `Pricing` write a run in an export format.

**Not read:** how a stored eval run is re-scored; whether `weave eval` runs cases in parallel; the `eval4j-report` and addons modules; whether the multimodal input of phase 31 could carry a tool-produced image back to a model (a tool result is a `String`, so today a screenshot must be a file the script passes on).

## 2. The framework parts

**F1. Self-describing kinds (35a).** A default method on `ToolKind`, `profile(options)`, returns a small core record: what the kind reaches (the three booleans), its effect level, the isolation level it applies, and a one-line note. The eight Loom sites ask the kind and their name lists are deleted. A kind that does not override it answers "unknown", which is today's behaviour. No behaviour changes: a characterization test pins the audit's output for every `.loom` file in the repository (`RepositoryScriptsTest` already walks them) before and after.

**F2. Registration (35a).** `ToolFactory` takes a registry of kinds. The tools module's kinds are registered as now; an optional module's kinds are found with `ServiceLoader` when the jar is on the classpath, so adding the browser jar adds the `browser` kind and nothing else changes.

**F3. Restore is a workspace operation, not a framework feature (35c).** An earlier draft added a `Checkpointable` capability to the core and `side effects: restore` to Loom. The owner dropped it (D9): what restoring means differs for every tool, not every backend can offer it, and a tool author should not carry it. `workspace` offers `snapshot`, `restore` and `diff` as ordinary operations; a script calls them with `use` and a rewind with `side effects: repeat` runs the attempt again. The existing effect protocol covers replay and a crash mid-restore (the operations are idempotent and journaled).

**F4. Isolation as an audited fact (35d).** The level a tool reports appears in `weave audit` and in the review facts, with rules LA31 to LA33 (D19) for a tool that runs a program or serves a process at a level below the one the script declared it needs. A new rule must be listed in `docs/security/weave-audit.md` and mapped in `owasp-llm-top-10.md`.

**F5. Owned resources (35d).** A small registry in the run folder records what a tool started (a process id, a port, a deadline). On cancel and on end the tool stops it; on a resumed run, the registry is read first and anything left from a crashed one is stopped before work continues.

## 3. The tools

Each row says what it is for **besides** a coding agent (principle 30).

| Kind | Where (D4) | What it does | Other workflows that need it |
|---|---|---|---|
| `sandbox` | tools | Runs a program from an allow-list inside a declared isolation (`process`, `container`, or a consumer's backend) with declared limits (`run`); the workspace is the only mount; network `none`, a host allow-list (`network: allow` with `hosts:`) or `open`; optionally keeps a process running for a while (`serve`, `stop`, `status`) (D5 to D8) | A data analyst running a script on a file; a converter; a grader running a submission; anything an agent computes by code |
| `workspace` | tools | A directory the agent reads and writes: `read`, `list`, `exists`, `write`, `append`, `patch` (exact-once edits), `find`, `grep`, `diff`, `snapshot`, `restore`; `allow` required, protected paths, a built-in deny list, symlinks never followed (D11) | A report or document writer revising drafts; a config editor; any "edit, check, try again" loop |
| `compute` | tools | Pure computation: `calc` (exact decimals), `date` (an injected clock), `json` (path), `csv` (count, sum, mean, min, max, group by). No reach, no effect, deterministic (D13) | Anything that today asks a model to do arithmetic or reshape data; finance and reporting steps |
| `verify` | tools | Turns a verifier's result into one typed record: JUnit XML and JSON Schema validation (TAP and SARIF later); counts typed, names and messages `unchecked` (D12) | Test results; schema validation of a model's structured output; a result an evaluation scores |
| `scan` | tools | Finds secrets and personal data in text or a file and reports a rule and a place, never the match; a gate, with no `redact` (D14) | A guard before anything is sent or published; a pre-commit check; a document review |
| `browser` | extras | Opens a page, reads its text, takes a screenshot to a file, clicks and fills, waits for a condition, reports the console and failed requests; a JDK-only CDP backend plus the contract and a fake; every request goes through `NetPolicy` (D16) | Research on pages that need scripts; checking an app the sandbox serves; filling a form in an approval step |
| `search` | extras | Web search through a provider the user chooses and keys (a `SecretRef`); titles, URLs and snippets as `unchecked` (D18) | Any research workflow |
| `documents` | extras | Extracts text and tables from PDF (Apache PDFBox), DOCX and HTML into text; a size and page limit (D18) | Intake of contracts, invoices, papers, tickets |
| `git` | extras | `status`, `diff`, `log`, `branch`, `commit`; `push` is an effect that needs approval; runs the `git` program through the sandbox contract (D18) | Docs-as-code; a change-review workflow; release notes |

Considered and left for later (see D18): object storage, a message queue, a chat kind (the `webhook` kind already posts), a calendar, speech, image generation (the `http` kind reaches these providers).

## 4. Staging

| Sub-phase | What | Gate |
|---|---|---|
| 35a | First, the move to `src/tools/` in a commit of its own (D4); then F1, F2: self-describing kinds and the registry. No new tool and no behaviour change | The owner reviews the branch before 35b |
| 35b | `compute`, `verify`, `scan`: pure and cheap, no runtime change. `compute` first, to prove the profile (D13) | |
| 35c | The `workspace` kind: read, write, patch, find, grep, diff, snapshot, restore; and a spike with a real workflow of the `use` plus `side effects: repeat` pattern (D9) | |
| 35d | F4, F5 and `sandbox`: the proxy spike first (D6), then `run` with the process, container and fake backends, then `serve` last (D7); audit rules LA31 to LA33 and `docs/security/sandbox.md` (D19) | |
| 35e | The extras modules: `search`, `documents`, `git`, then `browser` last (D16, D18) | |
| 35f | eval4j (D17) and the whole ecosystem pass, nothing deferred (D20): docs, two starters, CTK scenarios, skills, VS Code hover text and snippets, the tutorial chapter | |

## 5. Risks (for the owner to weigh against the decisions)

1. **The refactor in 35a touches the audit,** the most security-critical code in Loom. The characterization test is the control; without it the refactor must not start.
2. **The sandbox is a promise people will rely on.** A weak backend that is described as strong is worse than none. Principle 26 and D5 exist for this.
3. **Restore can be wrong in a quiet way:** a snapshot that misses a file, or a restore that races a running process. The snapshot is hashed and verified on restore, and a restore with a served process running is refused. The `use` plus `side effects: repeat` pattern rests on how `Generations.identity` treats `repeat` (not read): a spike in 35c proves it on a real workflow before it is documented.
4. **A served port and `NetPolicy`.** The policy refuses loopback by design. A browser or `http` tool reaching a served app needs a narrow exception tied to that one handle, not a switch that opens loopback for everyone (D7).
5. **A tool result is text.** A screenshot cannot come back as an image to a model today; the browser writes a file and the script decides what to do with it (D16).
6. **The egress proxy (D6).** A host allow-list for a container needs a proxy the container can reach and nothing else. Whether that works the same on Docker for Linux, Docker Desktop and rootless Podman is not known; it is a spike at the start of 35d, and a platform that fails it refuses `network: allow` rather than weakening it.
7. **Scope.** Nine kinds and two runtime changes in one phase is large. The gates and the order are meant to let the owner stop after any sub-phase and still have something whole.
