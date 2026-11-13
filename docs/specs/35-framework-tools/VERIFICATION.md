# Phase 35 verification plan: self-describing tools and a wider tool set

Status: **final for phase 35; nothing is built.** Section 0 lists what was read and what is still to read; section 1 lists what must be proved. Companions: [DESIGN.md](DESIGN.md), [TEST_STRATEGY.md](TEST_STRATEGY.md), [DECISIONS.md](DECISIONS.md), [phase 34 VERIFICATION](../34-supervision-signals/VERIFICATION.md).
**Evidence levels:** E0 read in the source or documents; E1 a unit test; E2 an engine test with scripted models on the controlled scheduler; E3 a kill-and-resume or concurrency test; E4 run by hand on a real provider or process (the owner's gate). **Integration** means an `@Tag("integration")` test that runs only where a container runtime or a browser exists. No live key is needed; `make test-live` is never run.

## 0. Survey

### Read (E0), with where

- **`ToolKind`** (`src/ai-agent4j/.../agent/tool/ToolKind.java`): `name`, `required`, `optional`, `prefixes`, `secrets`, `agentProblem`, `check`, two `create` methods. `profile` is a new default method here (D2).
- **The tools module** (`src/ai-agent4j-tools/.../tools/`, 38 files, about 4,400 lines, depending on the core and Jackson, the mail library optional): `GenericKind`, `EffectTool`, `FileKind` and `FileTool` (actions `read`, `list`, `exists`, `write`, `append`; `mode`; `allow` defaulting to `*.md, *.txt, *.json, *.jsonl, *.csv, *.log`; `overwrite`; `max_bytes`; a `PathGuard` with the paths Loom reserves; a lock per file; an effect policy with `on_unknown`), `ShellKind` (refuses interpreters), `WebhookKind`, `NetPolicy` (refuses loopback, private and metadata addresses; allows loopback only when the declared base host is itself loopback).
- **Loom's registration** (`.../loom/tools/ToolFactory.java`): a `Map<String, ToolKind>`; every built-in a script can name is a `ToolKind` (the tools module's six through `GenericKindAdapter`; `OpenApiKind`, `GraphKind`; the anonymous ones built by `simple(...)`, among them `duckduckgo`, `serpapi`, `google_search`, `calculator`, `datetime`, `current_time`, `class` and `skill_registry`; `LanguageTools`).
- **The places that name kinds in Loom's Java:** `security/Capabilities.of` (a `switch`), `review/fact/ToolFacts` (`JOURNALED`), `whatif/WhatIf` (a set), `generate/PlanChecker`, `execution/RewindChecks` (`EFFECT_KINDS`, plus its own `file` and `http` mode parsing, a second opinion beside `Capabilities`), and, by the survey, `ScriptedTools`, `SafeDefaults` and `SecurityAudit`.
- **A ninth copy, in TypeScript:** the extension's language server (`src/loom/vscode-loom/src/lsp/server.ts`) has hand-written hover text that lists the kinds in `use:` and the kinds each option applies to. The grammar does not list kinds. `snippets/loom.code-snippets` and `CHANGELOG.md` exist.
- **Rewind** (`execution/Rewinder.java`, `ast/RewindStmt.java`, `ast/CheckpointStmt.java`): a checkpoint snapshots variables, their origins and the agents' memory mark in memory and writes a `#checkpoint` journal entry once; a rewind restores variables and memory, decides through `effectsIn` and the `side effects:` policy (`ask first`, `keep`, `repeat`), and records a new generation. `RewindChecks` rejects `repeat` over a changing task that is not idempotent, and over a tool its agent does not approve. The guide's wording is `side effects: ask first / keep / repeat`.
- **`use Tool(args) -> var`** (`LOOM_GUIDE.md`, "Calling a tool yourself"): runs a declared tool with named arguments; "a resumed run does not call it twice"; `retry` on a changing tool that is not marked safe to repeat is refused.
- **`ProjectClasses` and `--classes`** (`.../loom/init/ProjectClasses.java`, `cli/ClassesOptions.java`): folders or jars become the context class loader; a jar brings no transitive dependencies.
- **The build:** the root `pom.xml` lists every module, Loom's directly (`src/loom` has no `pom.xml`); about 26 files mention `src/ai-agent4j-tools` (the `verify-*.sh` scripts, docs, the root pom).
- **The security documents:** `docs/security/weave-audit.md` ends at LA30.
- **eval4j and `weave eval`** (read in the survey before this spec): `Criterion` (a deterministic or judged check returning score, verdict and reasons), a flat `EvalScenario`, `EvalRun` and `RunWriter` and `Pricing`, and, in Loom's `eval/` package, Wilson intervals, the cost tail and `--repetitions`.
- **A tool result is a `String`** (`Tool.execute` returns `String`).

### To read before the work that depends on it (each ends in a short note under this section)

| # | What | Why | When |
|---|---|---|---|
| R1 | How `Generations.identity` treats `repeat`: whether identical effects run again in a new attempt | D9 rests on it; the pattern test (G60) is its proof | 35c, before the spike |
| R2 | Whether a tool's option can name another tool (`workspace: Data`) | D6's composition; if not, the fallback is a repeated root plus a load check | 35a, read; used in 35d |
| R3 | `ShellKind`'s `allow` matching syntax | `sandbox` reuses it where it fits (D6) | 35d |
| R4 | The release configuration with two new published parent poms, and the reactor order | D4 | 35a |
| R5 | How `use` journals a non-effect call against an effect call, and how `isEffect` per action drives it | `snapshot` and `restore` must be journaled effects (D11) | 35c |
| R6 | How each of the eight Java sites uses its list: a question the profile answers, or whether a kind exists (the registry's) | D2 | 35a, first |
| R7 | How the audit treats the plain `Tool` classes in the core that have no kind (`DelegateTaskTool`, `ScheduledActionTool`, `MemoryManagementTool`) | D3 note | 35a |
| R8 | `EffectPolicy` (`idempotent`, `on_unknown`) for `snapshot` and `restore` | D9: a crash mid-restore is retried | 35c |
| R9 | How a stored eval run is re-scored, whether `weave eval` runs cases in parallel, the `eval4j-report` module and the export schema | D17 | 35f, first |
| R10 | The `PIIDetector` API | `scan` reuses it (D14) | 35b |
| R11 | The egress proxy's reachability from a network with no outside route, on Docker for Linux, Docker Desktop and rootless Podman. **Docker for Linux was probed ad hoc (above) and works; the integration test must still be written, and the other two are not probed** | D6 spike | 35d, first |
| R12 | Where a load-time probe fits in `weave check`, and whether a skip flag (`--no-probe`) is wanted for CI | D5 | 35d |
| R13 | Every list of kinds in the documentation, the skills and the starters (a grep for `use: webhook`, `webhook | email`), and the exact hover and snippet entries | D20 | 35f |
| R14 | Whether phase 31's multimodal return path could carry an image from a tool | informational; D16 keeps a screenshot a file | 35e |
| R15 | The CDP calls for request interception on the browser versions the module will name | D16 | 35e |

### Notes on the reads (written by the build)

- **R2 (35a).** A bare word is a valid option value (`LoomParser.optionValue`), so `workspace: Data` parses, as the text `Data`. A kind's `check` and `create` see only their own options and the base directory, never another declaration, so resolving `Data` to a `workspace` tool's root and protected paths must be done by Loom's `ToolFactory` before the kind is asked (or D6's fallback, a repeated root plus a load check). Settled in 35d; it does not block 35a.
- **R4 (35a).** The release (`-Prelease deploy`) bundles every module of the reactor, so a parent pom is published by being in it; `pom` packaging inherits name, licence, SCM and developers from `llm4j-parent`, and the sources and javadoc steps skip it. `mvn -Prelease -Dgpg.skip=true -DskipTests install -pl src/tools,src/tools/extras,src/tools/ai-agent4j-tools` built both parents and the light module's three jars. Reactor order: the root lists `src/tools` where it listed the light module, and `src/tools` lists the light module before `extras`; Loom depends on the light module's artifact only. `docs/PUBLISHING.md` now counts fourteen artifacts. `make release-check` (the whole reactor) was not run.
- **R6 (35a, first).** The eight sites do not all ask the same question:

  | Site | What its list means | A question the profile answers? |
  |---|---|---|
  | `security/Capabilities.of` and `ofName` (`SEARCH`, `HARMLESS`) | What a kind reaches, from its options | **Yes**: this is the profile |
  | `generate/PlanChecker.KINDS` | Whether a kind exists | No: the registry's (`ToolFactory.kinds()`), identical list today |
  | `review/fact/ToolFacts.JOURNALED`, `whatif/WhatIf.EFFECT_KINDS`, `wtest/ScriptedTools.EFFECT_KINDS` | The same six names (`webhook`, `email`, `http`, `file`, `shell`, `sql`): the kinds whose calls go through the effect journal (all six tools extend `GenericTool implements Effectful`). It includes `sql` and a read-only `file`, so it is **not** the effect level | Not with D2's fields; see the contradiction below |
  | `execution/RewindChecks` (`READ_ONLY_KINDS`, `EFFECT_KINDS`, its own `file` and `http` parsing) | Whether a tool can have changed something before a rewind; any kind it does not list is "unknown" and counted as risky. It ignores `effect:` and `reach:` | Yes, but asking it **changes `weave check`** (below) |
  | `security/SecurityAudit` (`shell` with `unattended` or `allow_interpreters`, `file` with `overwrite`, `http` writing without `allow_paths`), `generate/SafeDefaults` and `PlanChecker` (`shell` needs a reason) | Rules about one kind's own options, not lists of kinds | Not a reach question; can stay as rules about that kind |
- **R7 (35a).** `MemoryManagementTool` is attached by Loom only for an agent with `memory { facts }`, and the audit treats it through the agent's memory setting (`privateData` "memory facts", the rule on memory fed by untrusted content), not as a kind. `DelegateTaskTool` and `ScheduledActionTool` are not reachable from a script. None of the three needs a profile.

### Probes done while writing the spec (E0; ad hoc, not tests)

Run in the cloud session on 2026-11-13 (Ubuntu 24.04, root, kernel 6.18, cgroup v1). They show what the implementation session can rely on; they do not replace the integration tests.
- **A container runtime is already installed:** `docker` 29.9.2 with `dockerd`, `containerd` and `runc` are in the image. The daemon is **not** running at session start; `nohup dockerd > log 2>&1 &` (as root) started it within seconds and `docker info` answered (overlayfs, runc).
- **Images:** Docker Hub is reachable. A first `docker pull alpine:3.20` returned HTTP 429 (rate limit); the image then came down with `docker run`. Tests should use one small image, pull it once and retry on a 429.
- **No network:** `docker run --network none` ran a command.
- **No route out:** a container on `docker network create --internal` had only the bridge's own subnet in its routing table and could not reach an outside host.
- **The proxy's premise (R11, Docker for Linux):** a listener bound on the host to the internal network's gateway address (here `172.18.0.1`) **was reachable from a container on that network**, while the container had no route to anything else. So an egress proxy bound to the gateway can be the container's only way out. Docker Desktop and rootless Podman are **not** probed; Podman is not in the image's package lists.

### Where the code contradicts a decision or the design (filled in by the build)

If a read shows that a decision cannot be built as written, the session stops at that point, writes the finding here with the evidence, and asks the owner; it does not deviate silently.

**C1 (R6, 35a): D2 says the eight sites ask the kind and their lists are deleted; DESIGN F1 says no behaviour changes. For four sites both cannot hold.** **Answered by the owner (2026-11-13):** (1) option (a), a `journaled` field in the profile; (2) option (a), `RewindChecks` asks the audit and the new `weave check` output is accepted; (3) the kind-specific option rules stay as they are.
1. **`ToolFacts`, `WhatIf`, `ScriptedTools`.** Their shared list means "journaled", which D2's profile (three reach booleans, effect level, isolation, note) cannot express. Deriving it from the effect level changes behaviour: `weave test` would answer "ok" to an unscripted call of a search tool where today the case fails, `weave whatif` would list search and `openapi` tools as effects, and the review's `journaled` fact would change for every non-generic kind. Options: (a) a `journaled` field in the profile, true for the six generic kinds, behaviour identical; (b) derive it from the effect level and accept the changes; (c) keep the three lists for now.
2. **`RewindChecks`.** Its list disagrees with the audit. With the audit's answer (`Capabilities.resolve`: the profile and any `effect:` or `reach:`), a tool the audit says changes nothing stops being counted as risky. In `every-kind.loom` (the characterization fixture) the unstated-rewind warning today names `Translate`, `Translit`, `Detect`, `Speak`, `Transcribe`, `translate`, `speak`, `GraphRead` (`read_only: true`), `Skills`, `MineNone`, `MineReads`, `MineFetch`, `Declared` (`effect: reads`) and `Quiet` (`effect: none`); after the change it would not, and `side effects: repeat` would stop requiring their approval. Options: (a) ask the audit and record the new `weave check` output as accepted; (b) keep its list.
3. The kind-specific option rules in `SecurityAudit`, `SafeDefaults` and `PlanChecker` are not lists of kinds; the build proposes to leave them as rules about that kind.

## 1. Acceptance: what must be true when the phase is built

Every row is `not built`.

| # | The claim | How | Level |
|---|---|---|---|
| A1 | The tools module is at `src/tools/` with its two parents; **the move is a pure rename** in its own commit; every script, Makefile target and document path is updated; the artifact coordinates are unchanged | CI on the commit; a grep for the old path | E1 |
| A2 | **Every built-in kind answers `profile`, and the audit and review output for every repository script is identical** to the recorded output (G36); a kind that does not answer is assumed to reach everything (G37) | the characterization test | E1 |
| A3 | Registration follows D3: a name is registered once (G38), a failed or absent kind names its module (G39), `weave check` lists the kinds and their source, and `ServiceLoader` uses the `--classes` loader | one test each | E1, E2 |
| A4 | The weight rule holds (G40): the light module's dependencies are pinned and each extras module has an allow-list; every module and both parents have a README | the dependency tests; `docs-check` | E1 |
| A5 | **`compute`** meets D13 (G62) and is built first | its tests | E1 |
| A6 | **`verify`** meets D12 (G61) | its tests and a canary | E1 |
| A7 | **`scan`** meets D14 and never echoes a match (G63) | its tests, with a canary in every output channel | E1 |
| A8 | **`workspace`** meets D11: `allow` required, no symlink followed, the deny list, exact patches, exact and idempotent restore, journaled snapshots, refused during a serve (G54 to G59) | its tests, and a kill-and-resume | E1, E3 |
| A9 | **The D9 pattern works** (G60): a rewind with `repeat` after a restore re-runs identical writes; R1's note records what `Generations.identity` does | the pattern test | E2 |
| A10 | **`sandbox` `run`** meets D5, D6 and D8 (G41 to G47, G49 to G51) on the fake and `process` backends | its tests | E1, E2, E3 |
| A11 | **`serve`** meets D7 (G52, G53): a lease, a one-port grant, cleanup of leftovers | its tests with the fake backend | E2, E3 |
| A12 | **The container backend and the proxy** meet D6 and D8 on Docker for Linux (G42, G48), and the platforms that pass R11 are listed in the docs; a platform that fails refuses `network: allow` | integration tests | integration, then **E4** |
| A13 | **LA31 to LA33** fire as D19 says (G66), are listed in `docs/security/weave-audit.md` and mapped in `owasp-llm-top-10.md`, and `docs/security/sandbox.md` exists | `SecurityDocTest` and one test each | E1 |
| A14 | **The extras modules** `search`, `documents`, `git` and `browser` meet D16 and D18, each with a fake, a contract test and a README; the browser checks every request (G65) | their tests; the real browser and the real `git` are integration | E1, E2, integration |
| A15 | **eval4j and `weave eval`** meet D17 (G67): steps, evidence, cost per pass, `schemaVersion`, the old constructor, every existing eval unchanged; `docs/API_COMPATIBILITY.md` is updated | their tests | E1 |
| A16 | **The provenance oracle passes** with the new canaries (G64) | `make test-oracle` | E3 |
| A17 | **The ecosystem pass is complete** (D20, G68): references, wiki, READMEs, `llms.txt`, the extension's hover text, snippets and CHANGELOG, two starters (`weave check` clean, `weave test` cases, `StartersTest`), CTK scenarios, the three skills, the tutorial chapter and its generated page, release notes | `make regen` then `make docs-check` clean | E1, E2 |
| A18 | **The gate is kept:** the session stopped after 35a, reported what it built and found, and waited for the owner before 35b | the report | n/a |
| A19 | **Both starters run end to end** with a real key and, for the second, a container runtime | the owner, by hand | **E4** |

## 1b. What the build reached (kept current; written at the end of each sub-phase)

Nothing is built.

## 2. How the report is written

At the end of each sub-phase the report states, for each row, the level reached and the evidence (the test names), **what was not run** (`make test-full`, the other `slow` tests, every integration test that needed a runtime the session lacked, anything at E4), the risks of DESIGN §5 that turned out to be real, each R-item read and what it found, and any decision that was changed and why. A row is never marked reached without the test that proves it. **Docker is in the cloud image but its daemon must be started (see the probes above); if a session cannot start it, the container backend and the proxy are reported as not verified, not as passing.**
