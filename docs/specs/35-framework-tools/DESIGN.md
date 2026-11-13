# Phase 35: design

Status: **draft.** Facts marked (read) come from the merged source on `main` after PR 119. Anything marked (not read) must be read before the decision that depends on it is taken. Principles: [PRINCIPLES.md](PRINCIPLES.md). Choices: [DECISIONS.md](DECISIONS.md).

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

**F4. Isolation as an audited fact (35d).** The level a tool reports appears in `weave audit` and in the review facts, with a rule (LA31) for a tool that runs a program or serves a process at a level below the one the script declared it needs. A new rule must be listed in `docs/security/weave-audit.md` and mapped in `owasp-llm-top-10.md`.

**F5. Owned resources (35d).** A small registry in the run folder records what a tool started (a process id, a port, a deadline). On cancel and on end the tool stops it; on a resumed run, the registry is read first and anything left from a crashed one is stopped before work continues.

## 3. The tools

Each row says what it is for **besides** a coding agent (principle 30).

| Kind | Where | What it does | Other workflows that need it |
|---|---|---|---|
| `sandbox` | tools | Runs a program from an allow-list inside a declared isolation with declared limits (`run`); optionally keeps one running for a while (`serve`, `stop`) | A data analyst running a script on a file; a converter (a document to PDF); a grader running a submission; anything an agent computes by code |
| `workspace` | tools | A directory the agent reads and writes, with a patch operation guarded by a precondition hash, find and grep, protected paths, a diff, and snapshot and restore | A report or document writer revising drafts; a config editor; any "edit, check, try again" loop |
| `compute` | tools | Pure computation: exact arithmetic, date arithmetic with an injected clock, JSON path and transform, CSV statistics, regex test with a limit. No reach, no effect, deterministic | Anything that today asks a model to do arithmetic or reshape data; finance and reporting steps |
| `check` | tools | Turns a verifier's output into one typed record: JUnit XML, TAP, SARIF, an exit code, a JSON Schema validation | Test results; a linter; schema validation of a model's structured output; a compliance scan result |
| `scan` | tools | Finds secrets and personal data in text or files and reports a name and a place, never the match | A guard before anything is sent or published; a pre-commit check; a document review |
| `browser` | optional module | Opens a page, reads its text, takes a screenshot to a file, clicks and fills, waits for a condition, reports the console and failed requests. Every request goes through `NetPolicy` | Research on pages that need scripts; checking a deployed page; form-filling an approval workflow; a visual check |
| `search` | optional module | Web search through a provider the user chooses and keys (a `SecretRef`); returns titles, URLs and snippets as `unchecked` | Any research workflow |
| `documents` | optional module | Extracts text and tables from PDF, DOCX and HTML into text; a size and page limit | Intake of contracts, invoices, papers, tickets |
| `git` | optional module | `status`, `diff`, `log`, `branch`, `commit`; `push` is an effect that needs approval; runs the `git` program through the sandbox contract | Docs-as-code; a change-review workflow; release notes |

Considered and left for later (see D18): object storage, a message queue, a chat kind (the `webhook` kind already posts), a calendar, speech, image generation (the `http` kind reaches these providers).

## 4. Staging

| Sub-phase | What | Gate |
|---|---|---|
| 35a | First, the move to `src/tools/` in a commit of its own (D4); then F1, F2: self-describing kinds and the registry. No new tool and no behaviour change | The owner reviews the branch before 35b |
| 35b | `compute`, `check`, `scan` and the `file` additions the decisions pick. Pure and cheap: no runtime change | |
| 35c | The `workspace` kind: read, write, patch, find, grep, diff, snapshot, restore; and a spike with a real workflow of the `use` plus `side effects: repeat` pattern (D9) | |
| 35d | F4, F5 and `sandbox` (process and container backends, the fake, `serve` if D7 says so) | |
| 35e | The optional modules the decisions pick: `browser`, `search`, `documents`, `git` | |
| 35f | eval4j (D17) and the ecosystem: docs, starters, CTK scenarios, skills, VS Code, the tutorial | |

## 5. Risks (for the owner to weigh against the decisions)

1. **The refactor in 35a touches the audit,** the most security-critical code in Loom. The characterization test is the control; without it the refactor must not start.
2. **The sandbox is a promise people will rely on.** A weak backend that is described as strong is worse than none. Principle 26 and D5 exist for this.
3. **Restore can be wrong in a quiet way:** a snapshot that misses a file, or a restore that races a running process. The snapshot is hashed and verified on restore, and a restore with a served process running is refused. The `use` plus `side effects: repeat` pattern rests on how `Generations.identity` treats `repeat` (not read): a spike in 35c proves it on a real workflow before it is documented.
4. **A served port and `NetPolicy`.** The policy refuses loopback by design. A browser or `http` tool reaching a served app needs a narrow exception tied to that one handle, not a switch that opens loopback for everyone (D7).
5. **A tool result is text.** A screenshot cannot come back as an image to a model today; the browser writes a file and the script decides what to do with it (D16).
6. **The egress proxy (D6).** A host allow-list for a container needs a proxy the container can reach and nothing else. Whether that works the same on Docker for Linux, Docker Desktop and rootless Podman is not known; it is a spike at the start of 35d, and a platform that fails it refuses `allow:` rather than weakening it.
7. **Scope.** Nine kinds and two runtime changes in one phase is large. The gates and the order are meant to let the owner stop after any sub-phase and still have something whole.
