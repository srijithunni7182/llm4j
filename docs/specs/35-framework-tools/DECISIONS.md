# Phase 35: decisions

Status: **draft; D1 to D9 are taken, D10 is folded into D11, D11 to D13 are taken, D14 to D18 are taken, D23 is deferred, four decisions are open (D19 to D22).** Each gives the question, why it matters, the options and a recommendation. The recommendation is a proposal: the owner decides one at a time and the answer is written under **Decision**. [PRINCIPLES.md](PRINCIPLES.md) holds the principles each option is checked against; [DESIGN.md](DESIGN.md) holds the facts.

## Decision log

| | Decision | State |
|---|---|---|
| D1 | The scope and staging of this phase | **taken** (one phase, six sub-phases, one review gate, after 35a; the gate after 35c was dropped with D9) |
| D2 | How a tool kind describes itself | **taken** (a `profile` method on `ToolKind`; built-ins move their profiles into the kinds; a characterization test first) |
| D3 | Registering and discovering kinds | **taken** (explicit for the built-ins, `ServiceLoader` for optional modules, four rules) |
| D4 | The weight rule and the module layout | **taken** (a tools parent, the light module inside it, an `extras` aggregator for the heavy ones; weight tests; a README each) |
| D5 | Isolation as an honest, audited fact | **taken** (option A with six rules; one sub-question on the load-time probe still open) |
| D6 | The sandbox contract | **taken** (two kinds; one workspace mount; network none, a host allow-list or open; relaxed time limits; no changed-files list) |
| D7 | Long-running processes (`serve`) and reaching them | **taken** (`serve`, `stop`, `status` with a lease and a one-port grant; built last in 35d) |
| D8 | Sandbox backends and the default | **taken** (`process`, `container` and `fake`; an interface for `vm` and remote; six rules) |
| D9 | Restore on rewind: the mechanism | **taken** (no framework support: restore is a `workspace` operation a script calls; a spike in 35c) |
| D10 | Snapshot storage and limits | **folded into D11** (storage is an implementation detail; the visible rules moved) |
| D11 | The workspace kind, patching and protected paths | **taken** (a new kind next to `file`; `allow` required; optional `version` on patch; protected paths; a built-in deny list; no `delete` or `move`) |
| D12 | Evidence: the `check` kind and provenance | **taken** (a minimal `check`: JUnit XML and JSON Schema; the typed-counts and untrusted-text rules) |
| D13 | Pure computation: `compute` | **taken** (one `compute` kind: `calc`, `date`, `json`, `csv`; built first) |
| D14 | Finding secrets and personal data: `scan` | **taken** (a pure `scan` kind that reports a rule and a place, never the match; no `redact` action) |
| D15 | Testing: fakes, contract tests, guards, the oracle | **taken** (all six parts, with two additions: a hermetic test of the D9 pattern, and the proxy spike in the integration tier) |
| D16 | The browser | **taken** (an optional extras module: a JDK-only CDP backend, plus the contract and a fake; kept in this phase) |
| D17 | eval4j enhancements | **taken** (option B: steps, evidence, cost per pass, a versioned export; no oracle module yet) |
| D18 | Other tools: now, later, never | **taken** (the table as proposed; `search`, `documents` and `git` now, as extras modules) |
| D19 | Security rules and documents | open |
| D20 | Visibility: the ecosystem | open |
| D21 | Names | open |
| D22 | What stays out of this phase | open |
| D23 | Predictions from a pre-trained model: a `predict` tool | **deferred** (left out of this phase by the owner; to be taken up later) |

## D1. The scope and staging of this phase

**Why.** Two runtime changes (self-describing kinds, restore) and nine tools are one large phase. The riskiest parts are the audit refactor (35a) and the rewind change (35c).

- **A. One phase, six sub-phases** (35a to 35f, [DESIGN §4](DESIGN.md)), with a review gate after 35a and after 35c.
- **B. Two phases:** the framework (F1 to F5, with `compute`, `workspace` and `sandbox` as the tools that prove it), then the optional modules and eval4j.
- **C. Framework only;** tools later, one at a time.

**Recommendation: A.** The order is built so the owner can stop after any sub-phase with something whole, and the two gates fall where the runtime changes. B is the fallback if the owner finds the phase too large to review.

**Decision (owner): option A, with the gates.** One phase with six sub-phases (35a to 35f, [DESIGN §4](DESIGN.md)).
**The gates:** the implementation session **stops after 35a** (self-describing kinds, the audit refactor) **and after 35c** (restore on rewind), reports what it built and found, and waits for the owner, who then tells it to continue or to change something. Nothing is merged in between. If the review load proves too large, the split into two phases (option B) stays available at the 35d/35e boundary.
**Amended (owner, with D9):** the gate after 35c is **dropped**: 35c no longer changes the rewind runtime, so it is only the `workspace` tool. The one gate is after 35a. The rest of D1 stands.

## D2. How a tool kind describes itself

**Why.** Eight places in Loom hold a list of kind names. Every new kind would need all eight changed, and a missed one fails quietly (the audit treats the kind as unknown, which is safe but noisy, or `weave test` cannot script it).

- **A. A default method on `ToolKind`,** `profile(options)`, returning a core record: the three reach booleans, the effect level, restorable, the isolation level, a note. The eight sites ask the kind; their lists are deleted. A kind that does not answer is "unknown", as today.
- **B. A separate registry in Loom** that kinds register metadata into: no core change, but the kind and its facts live in different places and can disagree.
- **C. Keep the lists** and add the new kinds to each.

**Recommendation: A.** It is the smallest core change (one default method and one record), keeps a kind's facts next to its code (principle 24), and old kinds keep working. Condition: a **characterization test** pins the audit output for every repository script before the refactor and after; the refactor does not start without it.

**Decision (owner): option A, with the characterization test.** `ToolKind` gains a default `profile(options)` returning a core `ToolProfile`; the built-in kinds move their profiles into themselves, so Loom keeps no list of kind names for what a kind reaches. A kind that does not answer is "unknown" and is assumed to reach everything, as today.
**Precondition:** before the refactor, a test records the audit output for every `.loom` file in the repository; after it, the output must be identical. Any difference is reported to the owner, not resolved silently, because it is either a profile bug or a place where the old lists disagreed.
**Settled in 35a, not here:** how the kinds Loom registers itself (`duckduckgo`, `calculator`, `openapi`, `knowledge_graph`, `skill_registry`, `class`) answer the same question (the same method on Loom's registration interface, or a small remaining switch), and the mapping from today's boolean `effect` to `TaskEffect`.
**Amended (with D9):** the profile has no `restorable` field.

## D3. Registering and discovering kinds

**Why.** `ToolFactory` registers kinds by hand. Optional modules (browser, documents) cannot be registered by the tools module, which must not depend on them.

- **A. Explicit only:** the host lists every kind it wants (the weave CLI would list the optional ones, so it would have to depend on them).
- **B. Explicit for the built-ins, `ServiceLoader` for optional modules** found only when their jar is on the classpath.
- **C. `ServiceLoader` for everything.**

**Recommendation: B.** `ServiceLoader` is a JDK feature with no scanning and no reflection beyond instantiating a listed class, so it fits principle 8 of the repository. The built-ins stay explicit so the core set is visible in one place. An optional kind that fails to load is a load error naming the module, never a silent absence.

**Decision (owner): option B, with four rules.** The built-ins stay in the explicit list in `ToolFactory`; kinds from optional modules are found with `ServiceLoader` when their jar is on the classpath.
1. A kind that fails to load is an error naming the module and the reason, never a silent absence.
2. A name already registered is an error, not a quiet override: a jar cannot replace `file` or `shell` by carrying the same name.
3. `weave check` lists the kinds it found and which jar supplied each, so the audit report says where a kind came from.
4. The weave CLI and the Maven plugin load kinds the same way. A script that uses a kind whose jar is absent fails at load with a message naming the module to add.
**Read while taking this decision:** every built-in kind a script can name is already a `ToolKind` (the tools module's six, Loom's `openapi` and `knowledge_graph`, the anonymous ones built by `simple(...)`, and `LanguageTools`), so `profile` (D2) goes on all of them and no separate registration interface is needed. Plain `Tool` classes in the core without a kind (`DelegateTaskTool`, `ScheduledActionTool`, `MemoryManagementTool`) were not examined; whether the audit treats them separately is a 35a question.

## D4. The weight rule and the module layout

**Why.** Principle 25: the tools module must stay light. Several of these tools tempt a dependency (a PDF library, a browser driver, a container client).

- **A. In `ai-agent4j-tools` (JDK and Jackson only):** `sandbox`, `workspace`, `compute`, `check`, `scan`. **Separate optional modules:** `ai-agent4j-tools-browser`, `-documents`, `-search`, `-git`.
- **B. Everything in the tools module,** with the heavy parts as `<optional>` dependencies.
- **C. One new module for everything new.**

**Recommendation: A**, with a **pinned test** that fails if `ai-agent4j-tools` gains a dependency (it reads the resolved dependency list), and a size note in the release notes. `search` and `git` are light and could live in the tools module; they are separate only so a user who wants neither pays nothing, and the owner may prefer to fold them in.

**Decision (owner): option A, in the owner's layout.** Light tools keep going into `ai-agent4j-tools`; a tool that needs a dependency is its own module.
```
src/tools/pom.xml                       parent (new, published): the weight rule and shared build settings
src/tools/ai-agent4j-tools/             the light tools, moved from src/ai-agent4j-tools; the artifact id is unchanged
src/tools/extras/pom.xml                aggregator (new, published): versions of heavy dependencies in one place, shared build and test settings
src/tools/extras/ai-agent4j-tools-browser/
                 ai-agent4j-tools-documents/
                 ai-agent4j-tools-search/
                 ai-agent4j-tools-git/
```
The aggregator is called **`extras`** (not `addon-tools`, which would be confused with the existing `ai-agent4j-addons`); the artifact ids stay `ai-agent4j-tools-<name>`. Whether `search` and `git` stay separate or fold into the light module is left as the owner stated it: separate, as optional capabilities, and open to being folded in during 35e if they prove too small to justify a module.
**Rules**
1. **The move comes first, in a commit of its own** with nothing else in it, so CI proves a pure rename. About 26 files mention the old path (the `verify-*.sh` scripts, the root `pom.xml`, docs); the artifact coordinates do not change for users.
2. **Weight tests:** the light module has a pinned test that fails if its resolved dependencies grow beyond the core, Jackson and the optional mail library; each extras module has a small allow-list test of its own dependencies, and its licence must be compatible with Apache 2.0. The release notes state the light module's jar size before and after.
3. **A README for each module, and for the two parents.** Each module's README says what it is for and who needs it, gives the options table and a working `tool` example, says what the audit sees (the profile note) and what the tool reaches and how to run it safely, and says how to add it to `weave` and to a Maven project. The samples are checked by a `*DocsTest` like the other documentation. Each is linked from the root README, the module table in `CLAUDE.md` and `llms.txt`. The parent READMEs explain the layout, the weight rule and how to add a module.
4. **Adding an extras module to `weave`** (a gap in D3, closed here): the `ServiceLoader` lookup uses the class loader that `--classes` sets (`ProjectClasses.loader()`), not the default one; and an extras module that has dependencies also publishes a self-contained jar for `--classes`, since that option brings no transitive dependencies.
**To check in 35a:** the release configuration with two new published parent poms (not read), and the reactor order (the extras modules depend on the light module and the core; Loom depends on neither the extras nor their parent).

## D5. Isolation as an honest, audited fact

**Why.** The audit cannot tell a program run on the host from one run in a container. A reader of `weave audit` should see which it is, and a script that needs more than the backend gives should not run.

- **A. Declared, reported and enforced:** the script declares the **minimum** isolation it needs (`isolation: container`); the backend reports the level it applied; a request the backend cannot meet fails closed at load (when the runtime can be probed) or at first use; the audit shows the reported level and a rule (LA31) flags a program run at `none` or `process`.
- **B. Advisory:** a field in the audit, no enforcement.
- **C. None:** the sandbox is as isolated as its backend and the docs say so.

**Recommendation: A.** Levels: `none`, `process`, `container`, `vm`. There is **no default**: a `sandbox` tool without an `isolation` line is a load error, so nobody gets a weak level by omission. The fallback rule (never to a weaker level) is a test, checked by removing the container runtime.

**Decision (owner): option A.** Levels `none`, `process`, `container`, `vm`. Rules:
1. **No default:** a `sandbox` tool without an `isolation` line is a load error.
2. **Fail closed, never weaker:** a request for `container` with no runtime fails; it never falls back to `process`. Tested by removing the runtime.
3. **Probe at load:** a missing container runtime is a load-time error, not a surprise mid-run (a backend that cannot be probed at load, such as a remote one, fails at first use). **Sub-question still open:** whether `weave check` itself runs `docker version` or `podman version` (the first time `check` would run a program), or only the run does. Recommendation: run it in `check` and say so.
4. **The reported level is what the audit shows,** not the requested one; if they differ, the run stops.
5. **Honest wording:** the audit note and the journal say "isolation: process", never "sandboxed".
6. **LA31** flags a program run at `none` or `process` (medium when the workspace also holds secrets or the network is `open`, high when both); taken in D19.
The docs say plainly that a container is not a boundary against a kernel exploit.

## D6. The sandbox contract

**Why.** This is the part llm4j owns (principle 23). It must be small, deny-by-default and testable.

Proposed shape, for the owner to change:
- **Operation `run`:** a program name from an allow-list and an argument list (never a string through a shell), a working directory inside the workspace, an environment allow-list (no secrets unless a `SecretRef` is named, D19), optional stdin, a time limit.
- **Declared limits:** wall time, output bytes, processes, memory, disk written, files. A limit that is hit ends the run and is reported.
- **Mounts:** the workspace read-write; everything else read-only or absent; a list of read-only paths (the protected paths of D11 are mounted read-only).
- **Network:** `none` (the default), or an allow-list of hosts where the backend can enforce it (a container with a proxy), or `open` (flagged by the audit). A backend that cannot enforce an allow-list refuses it rather than treat it as `open`.
- **Result (typed, text):** `exit`, `timedOut`, `truncated`, `isolation` (as applied), `durationMs`, `stdout`, `stderr` (both `unchecked`, redacted through `Redactor`), and a list of files the run created or changed.

Options for the program allow-list: **A.** named programs only (`npm`, `node`, `python3`, `javac`); **B.** a profile per language (a named set of programs and their limits); **C.** any program.

**Recommendation: the contract above; A for the first version** (B can sit on top of it later), and C is refused. The sandbox is the *only* place an interpreter is allowed: `ShellKind`'s deny-list stays as it is.

**Composition (owner, taken): two kinds.** `shell` stays the host tool: small, allow-listed, interpreters refused, unchanged. `sandbox` is the contained one, the only place an interpreter may run. A `sandbox` and a `workspace` share a directory by **naming the workspace tool** (`workspace: Data`), so there is one root, one set of protected paths and one snapshot for `restore` to cover what a program wrote. A shell inside a sandbox is a `sandbox` whose `allow` names `sh`, a visible and deliberate choice inside a container. The docs say plainly: `shell` for the few host commands you list, `sandbox` for anything that runs code. `file` has no isolation level: it runs in the JVM and its containment is the path jail, which `workspace` keeps. A new top-level `sandbox` declaration was refused as too heavy for Loom; a `shell` with an `isolation:` option was refused because one name would then cover two different risks.
**To read in 35a before building:** whether a tool's option can name another tool. If not, the fallback is to repeat the path in both tools and add a load check that two tools naming one workspace name the same root.
**Contract (owner, taken).** One `run` operation (`serve` is D7).
- **A call:** `program`, `args` (a list, never a string through a shell), an optional `cwd` inside the workspace, optional `stdin`. The program must be in the tool's `allow`, in the same syntax as `shell`'s where it fits (to read in 35d); any program is refused. A `cwd` that escapes the workspace is an error.
- **Environment:** a list of variable names, empty by default; a secret gets in only by naming a `SecretRef` (D19).
- **Mounts:** the workspace only, read-write except protected paths, which are read-only. No other mount in the first version; reference data goes into the workspace and is protected.
- **Network:** three values. `none` (the default); `allow: "host, host:port"`, a **host allow-list** (the owner holds that a whitelist is a must); `open`, flagged by the audit. The allow-list is enforced by a small **egress proxy** the container backend runs, written on the JDK: the container sits on a network with no other route, so a program that ignores the proxy settings simply cannot connect (it fails closed). The proxy resolves each name once and connects to that address, and refuses loopback, private and metadata addresses even when the name is on the list (the rules of `NetPolicy`). A backend that cannot enforce a list (`process`, or a platform where the proxy was not proven) **refuses `allow:` at load**; it is never treated as `open`. **Granularity is the host (and port), not the path:** for HTTPS a path rule would need the proxy to intercept TLS, which is refused. **Confirmed by the owner:** host-level is what the whitelist means here (the `http` tool, which makes its own requests, keeps its path rules).
- **To prove first in 35d (a spike):** the proxy's reachability from a network with no outside route on Docker for Linux, Docker Desktop and rootless Podman. If a platform cannot be made reliable, `allow:` is refused there with a message that says so; it is never weakened.
- **Limits are relaxed but bounded.** `timeout` defaults to **10 minutes** (a named constant), because a build by a coding agent will not finish in 60 seconds; there is a hard ceiling (one hour, a named constant) a script cannot exceed, and an agent may ask for less than the script's timeout, never more. `max_output` defaults to 64 KiB and the result keeps **the start and the end** of the output (a build's errors are at the end) and says what was cut. `memory`, `pids` and `disk` are enforced only by the container backend; declaring one with `isolation: process` is a load error, never silently ignored.
- **Result:** `exit`, `timedOut`, `truncated`, `isolation` (as applied), `durationMs`, `stdout`, `stderr` (both `unchecked`, redacted through `Redactor`). There is **no changed-files list**: the script uses `workspace diff` (D11: a diff against the last snapshot), so the sandbox does no scan after a run.
- **Effects:** a `run` is an effect, journaled, never repeated on resume or replay, simulated in `weave test`; the agent needs `approve:` as with `shell`, which the earned-autonomy ladder can relax.
- **Image:** named in the script, never pulled implicitly (a pull is a network effect, so it is an explicit option).

**Decision (owner):** taken as above; host-level matching confirmed.

## D7. Long-running processes (`serve`) and reaching them

**Why.** Anything that checks a running thing (a web app, a server, a notebook) needs a process that outlives one call. It also needs a way for the `http` or `browser` tool to reach it, and `NetPolicy` refuses loopback on purpose.

- **A. `serve`, `stop` and `status` operations,** with a **lease** (D5's resource owner, principle 31): a handle, a loopback port chosen by the backend, a deadline, stopped on run end, cancel and crash recovery. A tool that wants to reach it is granted **that one port, for the life of the handle**, through a narrow `NetPolicy` grant, not a loopback switch.
- **B. `serve`, and a generic `allow_loopback: true` option** on `http` and `browser`.
- **C. No `serve` in this phase;** `run` only.

**Recommendation: A**, in 35d if the owner is willing, else C and a later phase. B is refused: it turns the SSRF guard off for every address on the machine. The grant must be journaled (the audit shows "this workflow reaches a process it started").

**Decision (owner): option A.** `serve`, `stop` and `status` operations on the `sandbox` kind.
- **Where it runs:** inside the same container as `run`, with the same isolation, mounts and network rule; the backend publishes its port to the host's loopback only.
- **The lease:** a handle, a port the backend chooses, a deadline and an owner (the run). The deadline follows the same relaxed-but-bounded rule as `run` (a default, and a hard ceiling no script can exceed). A readiness check lets a script wait until the port answers.
- **The grant:** a tool that must reach the process (`http`, `browser`) is given a `NetPolicy` entry for exactly `127.0.0.1:<that port>`, valid until the handle ends, journaled, and shown by the audit as "this workflow reaches a process it started". A generic `allow_loopback` option was **refused**: it would turn the SSRF guard off for every address on the machine.
- **Ownership and cleanup:** a registry file in the run folder records process id, container name, port and deadline. A process is stopped on `stop`, on cancel and on run end; a resumed run reads the registry first and stops anything a crashed run left. A limit on live handles per run, with a loud error when exceeded.
- **With `workspace restore` (D10):** a restore is refused while a served process holds the workspace.
- **Order:** `serve` is built last in 35d, after `run` is solid; it is the hardest part of the sandbox (orphans after a crash, port clashes, a process that ignores a stop signal).

## D8. Sandbox backends and the default

**Why.** llm4j cannot ship a microVM and should not pretend a plain process is a sandbox.

- **A. Three:** `process` (honest level `process`: a child process with a scrubbed environment, a working-directory jail and the limits the JDK can enforce, which are wall time and output; it cannot enforce memory or network), `container` (the `docker` or `podman` CLI through `ProcessBuilder`, no client library), and a `fake` for tests. `vm` and remote services are an SPI the consumer implements.
- **B. Container only,** with a fake. No `process` backend: a script cannot run anywhere without a container runtime.
- **C. SPI only:** the consumer brings every backend.

**Recommendation: A.** `process` is there so a developer can try a workflow on a laptop, and it is flagged by LA31 (D5) so it is never mistaken for isolation; the container backend is the one to recommend. The runtime is probed once at load; the container image is named in the script and never pulled implicitly (a pull is a network effect, so it is an explicit option).

**Decision (owner): option A, with six rules.** Backends: `process` (reports `process`; refuses `memory`, `pids`, `disk` and `network: allow` at load), `container` (the `docker` or `podman` command line through `ProcessBuilder`, no client library; enforces every limit, the one mount and the three network values), `fake` (scripted answers and recorded calls; never runs a program), and a small `SandboxBackend` interface a consumer implements for `vm` or a remote service (llm4j ships none).
1. **Selection** is from the script's `isolation` line; there is no global default and no auto-detection, so a missing runtime is D5's error and never a quiet switch.
2. **Container runtime:** `docker` or `podman`, from `PATH` or named in a `runtime:` option; with neither named, `docker` then `podman`, and the audit shows the one found.
3. **Image:** named in the script, never pulled implicitly; a missing image is an error that says the pull command, or an explicit `pull: true` that the audit shows (a pull is a network effect).
4. **Cleanup:** each container is named from the run id and step id, runs with `--rm`, and is stopped and removed on timeout, cancel and run end; the D7 registry lets a resumed run remove leftovers.
5. **The interface stays small:** `SandboxBackend` has `run`, and `serve`, `stop` and `status` for backends that can serve; it reports its isolation level and which limits it enforces; a backend that cannot serve makes `serve` a load error, not a failure at first use.
6. **Testing:** the D15 contract test runs against `fake`, always against `process` (it needs only the JDK), and against `container` only where a runtime exists (`@Tag("integration")`); `weave test` uses `fake`.
**First supported runtime: Docker for Linux.** The docs say which others were verified (after the D6 spike) and do not promise all three.

## D9. Restore on rewind: the mechanism

**Why.** Rewind today re-runs or skips effects; it cannot give a directory back. Retrying a build or a document edit from a clean state needs that.

- **A. A `Checkpointable` capability in the core;** at a `checkpoint`, Loom snapshots each checkpointable tool in scope and records the id in the journal; `side effects: restore` restores them before the attempt is replayed; anything else in the discarded attempt still needs `ask first`, `keep` or `repeat`.
- **B. Compensation per effect** (a saga: each effect names its undo). Powerful, but it invites claiming that a send can be undone.
- **C. Nothing in the framework:** the script calls `workspace snapshot` and `workspace restore` itself with `use`, and the rewind says `side effects: repeat`.

**Recommendation (first draft): A,** after reading how checkpoints and rewinds execute. A checkpoint already snapshots variables and agent memory in memory and a rewind restores them, so tool state would go in the same two places; three hazards shaped the design (a checkpoint runs again on replay, `begin` runs again on resume, a crash can fall between a restore and the recording of the new attempt).

**Decision (owner): none in the framework; option C.** The owner's reasons: what "restore" means is subjective and differs for every tool; it could become a bottleneck for anyone building a tool; and not every backend (a `vm`, a remote service) can or wants to support it. The framework therefore gains **no** `Checkpointable` capability, **no** `restorable` field in the profile, **no** `side effects: restore` word, no change to `Rewinder` and no new `RewindChecks` rule.
**How a script does it** (a sketch, not a script that parses today):
```loom
use Data(action = "snapshot", name = "clean")          // once, before the checkpoint
checkpoint Clean  starting with feedback = "none"
use Data(action = "restore", name = "clean")           // a no-op the first time; after a rewind it undoes the failed attempt
...
rewind to Clean when (...) at most 2 times  carrying feedback = "..."  side effects: repeat
```
**Why the existing machinery is enough:** `use Tool(...)` is journaled and not run twice on resume, so a replay reuses the recorded snapshot id instead of taking a new one; the restore is declared idempotent (`EffectPolicy`), so an unknown outcome after a crash is retried; and `repeat` is already allowed when the tools involved are approved.
**What it costs:** the audit sees effects, not "this workflow can restore"; the author writes the three lines; and `repeat` re-runs the attempt's effects, so it fits an attempt that touches only the workspace and the sandbox (an attempt that also sends an email keeps `ask first`).
**To prove in 35c (a spike):** how `Generations.identity` treats `repeat` (not read), that identical effects really run again in the new attempt, with a real workflow. If the pattern is awkward, a later phase may add language support, informed by real scripts; that is recorded in D22.
`workspace` keeps `snapshot`, `restore` and `diff` as its own operations (D10, D11); a `vm` or remote backend whose files are not on the host implements them itself or does not offer them, and nothing in the sandbox contract (D6) requires them.

## D10. Snapshot storage and limits

**Folded into D11 (owner).** Where a snapshot is stored is an implementation detail behind three operations (`snapshot`, `restore`, `diff`); no script and no other tool depends on it, and it can change without breaking anyone. Deciding it before the 35c spike would repeat what D9 removed. The behaviour a script can see moved to D11. **Guidance for the implementer, not a decision:** a content-addressed copy store on the JDK (SHA-256 per file stored once, a manifest, a hash checked on restore) under the run folder, or under a `store:` directory outside a managed run, outside the workspace root so the agent's own tools cannot reach it; git through the command line is a possible second backend; filesystem features are not portable. Revisit in the 35c spike. A snapshot is a convenience for trying again, not a security boundary.

## D11. The workspace kind, patching and protected paths

**Why.** The `file` kind (read, list, write) is a stable public surface. An editing agent needs more, and needs some files out of its reach.

- **A. A new `workspace` kind** (a superset of `file`), operations: `read`, `list`, `write`, `patch`, `find`, `grep`, `diff`, `snapshot`, `restore`; `file` is unchanged.
- **B. Add the operations to `file`.** One kind, but it widens a published surface and mixes a simple tool with a stateful one.

**Patch format:** a model is more reliable with search-and-replace blocks than with unified diffs, so `patch` takes **a list of (old text, new text) edits for one file plus the hash of the file it was written against**; it fails, changing nothing, when the hash differs, an old text is not found exactly once, or the file is protected. A unified diff may be accepted later.
**Protected paths:** `protect: ["tests/**", "oracle/**"]` makes those paths read-only to the agent (in the tool and in the sandbox mount). This is what keeps evidence honest (principle 28): a model cannot edit the test that judges it.

**Snapshot, restore and diff** (from the folded D10), as operations a script calls with `use` (D9). Built **last in 35c**, after `read`, `write`, `patch`, `find` and `grep`; if the spike shows they are not missed, they come out. The behaviour a script depends on:
1. **Restore is exact:** the files of the snapshot are put back and files created since are deleted, inside the workspace root only (the path jail of every operation); ignored and protected paths are left alone.
2. **Bounded and loud:** caps on files, bytes and the number of snapshots per workspace (default eight; the same name replaces the older one). A workspace over a cap fails with "the workspace is larger than the snapshot cap; ignore a path or raise the cap" and never takes a partial snapshot. A default ignore list (`node_modules`, `target`, `.git`, build output) the script can change.
3. **Refused while a process is served** (D7), naming the handle.
4. **`diff` compares the workspace to a named snapshot** (the latest if none is named) and lists added, changed and deleted paths, bounded in count: this is how a script learns what a `sandbox` run did (D6).
5. **Idempotent:** restoring the same snapshot twice leaves the same state, which is what lets a crash mid-restore be retried (D9).

**Decision (owner): option A, with the rules below.** A new `workspace` kind next to `file`; `file` is unchanged and its guards (`PathGuard`, the reserved paths, the per-file locks, the effect policy) are reused. Calls use `action`, `path` and `content`, as `file` does (the `op` in the D9 sketch becomes `action`).

| Action | What it does |
|---|---|
| `read` | A file, optionally by line range; returns a short `version` tag, a hash of the whole file |
| `list`, `exists` | As in `file`; `list` is bounded and skips ignored paths |
| `write`, `append` | Create or extend a file, creating directories; `overwrite` as in `file` |
| `patch` | A list of (old text, new text) edits for one file; all apply or none |
| `find` | Paths matching a glob, bounded |
| `grep` | A literal search by default, or a regex with a bounded line length and match count; returns path, line and text, with optional context lines |
| `diff` | The workspace against a named snapshot (the latest if none is named): added, changed and deleted paths, bounded |
| `snapshot`, `restore` | The five rules above; built last in 35c |

**Patch.** Each `old` must appear exactly once in the original file; edits are matched against the original, not one after another; overlapping edits are refused; a failure names the edit and why (not found, or found N times) and changes nothing; a protected path is refused. The file's `version` is **optional**: if given it must match. (The first draft required it; exact-once matching already stops a stale patch landing on the wrong text, and a required tag makes a model fail on a mistyped one.)
**Protected paths.** `protect: "tests/**, oracle/**"` makes those paths read-only through every call of this tool and read-only in the sandbox mount that names it (D6): one declaration for both, which keeps evidence honest (principle 28). A script's own `use` calls go through the same tool, so protected files are put in place before the run or by a second `workspace` tool declared without `protect`.
**Rules**
1. **`allow` is required, with no default** (a missing setting is a load error, as `isolation` is in D5); it matches relative path globs.
2. **Symlinks are never followed** by reads, writes, `find`, `grep`, snapshot or restore: a program in the sandbox can create a link inside the workspace, and a host-side tool that followed it would read the host.
3. **A built-in deny list that `allow` cannot override:** writes under `.git/**` are refused (a hook file there would run on the host the next time `git` does); reads of `.env*`, `*.pem`, `*.key` and `id_*` are refused.
4. **`snapshot` and `restore` are journaled effects**, so a resumed run reuses the recorded result instead of taking a snapshot of later state; both are idempotent (D9).
5. **Text only:** a binary file is listed and counted, `read` refuses it with its size; snapshots and `diff` handle it by hash.
6. **`ignore` affects only listing, `find`, `grep`, `snapshot` and `diff`;** reading and writing follow `allow`.
7. **Modes** `read`, `write`, `readwrite` as in `file`; `restore` needs write.
8. **Left out of the first version:** `delete` and `move` (a `sandbox` can do both); they can be added on demand.
**What the audit sees (D2):** reaches private data (the workspace), effect `changes` unless `mode: read`, no network; the note names the root and the protected paths.


## D12. Evidence: the `check` kind and provenance

**Why.** Many workflows decide on the result of a verifier. Today that is a string the model reads.

- **A. A `check` kind** that parses JUnit XML, TAP, SARIF, an exit code or a JSON Schema validation into one record: `passed`, `failed`, `skipped`, `total`, a list of items (name, status, message), the source. Counts and statuses are typed and trusted as *parsed by the host*; **names and messages stay `unchecked`**. The record never says "requirement met".
- **B. Leave parsing to the script** (a `shell` or `sandbox` call and text matching).

**Recommendation: A.** The provenance oracle gets a canary that travels through a verifier's output, to prove no model-written value becomes trusted by passing through it. A parse failure is a failed check, never a pass.

**Decision (owner): option A, minimal.** A `check` kind (its name may become `verify`, D21) that turns a verifier's result into one typed record. **First version: JUnit XML and JSON Schema validation.** An exit code is already in the `sandbox` result (D6) and needs no tool. TAP and SARIF are added when a real workflow needs them (not in this phase).
**Where it applies:** in a script, after a `sandbox` run has written a report into the workspace, `use Verify(source = "out/report.xml") -> result`, then `rewind ... when (result.failed > 0)` or `alt`; also to validate a model's structured output against a schema, and, in D17, to give eval4j a typed result to score instead of text.
**Rules**
1. **Counts and statuses are typed data parsed by the host;** the names and messages of items stay `unchecked`, since a model may have written them. The provenance oracle gets a canary that travels through a verifier's output.
2. **A parse failure is a failed check, never a pass;** an empty report is reported as `total: 0` and the script decides.
3. **The record never says "requirement met":** it says the artifact passed a check, and the check is only as good as who wrote it (hence `protect:` in D11).
4. **It reads a file in the workspace or takes the text; it never runs a program.** It is pure: no reach, no effect, deterministic.
5. **Bounded:** a cap on report size and on the number of items kept; truncation is said (principle 32).
6. **No new core types:** the record lives in the tools module; eval4j's evidence type is D17.
7. **No compare operation** (for example "did the failure count go down"): a script compares two numbers itself.

## D13. Pure computation: `compute`

**Why.** Models are poor at arithmetic and reshaping data, and a pure tool has no reach and nothing to audit. It is also the cheapest tool to add.

- **A. One `compute` kind with operations:** exact arithmetic (`BigDecimal`, a bounded expression grammar, no scripting engine); date arithmetic with an injected `Clock`; JSON path and a small transform; CSV statistics (count, sum, mean, percentile, group by); a regex test with a step limit (against ReDoS).
- **B. Several small kinds.**
- **C. Leave it to the model or the sandbox.**

**Recommendation: A.** Pure, deterministic, replays exactly, `reach: none`. Bounds: expression length and depth, result size, a time limit. It is the first tool to build, to prove the self-describing profile (D2) on a kind with nothing to hide.

**Decision (owner): option A, with `csv` in.** One `compute` kind with four actions:
| Action | What it does |
|---|---|
| `calc` | Exact decimal arithmetic (`BigDecimal`) from a bounded expression grammar: numbers, `+ - * / %`, parentheses, and a few functions (`round`, `min`, `max`, `abs`); no scripting engine |
| `date` | Add or subtract days, months or years; the difference between two dates; the weekday; uses an injected `Clock` so tests are exact |
| `json` | Pick a value by path (`a.b[0].c`) or list keys; no transform language |
| `csv` | Count, sum, mean, min, max and group-by over a named column, with a row cap |
**Rules**
1. **Pure and deterministic:** it reaches nothing, changes nothing and replays exactly; its profile (D2) is `ToolProfile.pure(...)`.
2. **Bounded:** limits on expression length and nesting, input size, rows and result size; division by zero, malformed input and over-limit input are errors that say why.
3. **No regex action in the first version:** it would need a real defence against catastrophic patterns and nothing here needs it yet.
4. **Exact decimals:** the result of `calc` is a decimal string, never a binary float (`0.1 + 0.2` is `0.3`).
5. **Built first,** to prove the `profile` method (D2) on the simplest kind.
Left out on purpose: a general transform language, a statistics library, a spreadsheet formula engine; a `sandbox` can run a real script for anything larger.

## D14. Finding secrets and personal data: `scan`

**Why.** A guard before anything is sent or published, and a check an agent can run on its own work.

- **A. A `scan` kind** over text or a file: secrets (known token shapes, high-entropy strings) and personal data (reusing the core `PIIDetector`), reporting **a rule name and a location, never the match**. JDK only; the rules are versioned so a finding can be reproduced.
- **B. Only a Loom `guard`,** no tool.
- **C. An optional module** wrapping a scanner program through the sandbox.

**Recommendation: A**, and say plainly in the docs that it is a net, not a proof: false negatives exist. Package and licence policy checks are **not** here: they belong to the vertical (D22).

**Decision (owner): option A, with the rules below and no `redact` action.** A `scan` kind over text or a workspace file that finds secrets (known token shapes and high-entropy strings) and personal data (through the core `PIIDetector`).
**Where it applies:** before a `webhook` or `email` step, a script scans the text it is about to send (`use Scan(text = draft) -> found`, then branches on the count); after a `sandbox` run it scans a file or directory the agent produced; it catches an agent that copied a key from a file into a prompt.
**Rules**
1. **Never echo the match:** a finding is a rule name, a file and line, and a character range, and nothing of the matched text, so a found secret does not reach a model's context, the journal or a trace (the principle of `SecretRef.toString()`).
2. **A net, not a proof:** false negatives exist; a clean result means "nothing the rules know", and the docs and the audit note say so.
3. **Versioned rules:** the result carries the rule-set version, so a finding can be reproduced and a change in rules is visible.
4. **Bounded:** caps on input size, number of files and number of findings, with truncation said (principle 32); entropy detection has a minimum length so it does not flag every hash.
5. **Pure:** it reads text or a workspace file and reaches nothing; its profile (D2) is pure, or reads for a file.
6. **Reuses what exists:** personal data goes through the core `PIIDetector`; the tool adds the secret patterns and the reporting.
7. **Package and licence policy checks are not here** (the vertical, D22).
**Left out on purpose:** a `redact` action that returns the text with matches masked. It would make the tool the producer of the text that goes out, which raises the bar for correctness; `scan` is a gate only for now.

## D15. Testing: fakes, contract tests, guards, the oracle

**Why.** Principles 22 and 29.

Proposed:
1. Every kind ships a **fake backend** (scripted answers, recorded calls) and a `weave test` vocabulary for it, so a workflow using it is tested with no container, browser or network.
2. A **contract test** per kind runs the same cases against the fake and, tagged `@Tag("integration")`, against the real backend (a container runtime or a browser is not in the default build).
3. **Guards are tested by breaking them once:** the program allow-list, the path jail, the protected paths, the patch precondition, the snapshot hash check, the isolation fail-closed rule.
4. **Mutation profiles** for the new decision-making classes (the isolation check, the patch applier, the snapshot and restore, the `check` parsers).
5. The **audit characterization test** of D2.
6. The **provenance oracle** gets canaries through a sandbox result, a `check` record and a browser page.

**Recommendation: all six.** The two real backends are exercised in CI only where a runtime exists, and the report says which ran.

**Decision (owner): all six parts, with two additions.**
1. **Every kind ships a fake and a `weave test` vocabulary** (scripted answers, recorded calls); the fake plugs in through the kind, which D2 makes possible (`ScriptedTools` no longer keeps its own list of kinds).
2. **A contract test per kind** runs the same cases against the fake and, tagged `@Tag("integration")`, against the real backend (a container runtime, a browser); the report says which ran.
3. **Guards are tested by breaking them once:** the sandbox's program allow-list, the workspace path jail and the no-symlink rule, protected paths and the built-in deny list, the patch's exact-once rule, the isolation fail-closed rule (remove the runtime), the proxy's refusal of private and metadata addresses, and `scan` never echoing a match.
4. **Mutation profiles** for the decision-making classes (the isolation check, the patch applier, the path guard, the `check` parsers, the `scan` rules); a score cannot drop.
5. **The audit characterization test** of D2.
6. **The provenance oracle gets new canaries** through a `sandbox` result, a `check` record and a `scan` finding; the browser adds a page.
**Additions.** (a) **The D9 pattern has a hermetic test:** with the `workspace` tool and the fake sandbox, a rewind restores the files and the identical writes run again; it is how 35c finds out whether `Generations.identity` treats `repeat` as D9 assumes. (b) **The proxy spike of D6 is an `integration`-tier check,** run only where Docker exists; its result is written into the docs' list of verified platforms.
CLAUDE.md's rules are unchanged: new code meets the coverage bar of the module it lands in, nothing is `@Disabled` to get green, and the tiers (`make test-core`, `make test-fast`, `make test-oracle` at the end) are as they are. The real container backend and a real browser are not in the default build, so CI covers them only where a runtime is installed.

## D16. The browser

**Why.** Reading pages that need scripts, checking a running page and filling a form are things an agent cannot do with `http`. It is also the heaviest tool and the one that reads the most untrusted content.

- **A. An optional module with a reference backend over the Chrome DevTools Protocol,** written on the JDK (`java.net.http.WebSocket`) against a browser the user has installed (found on `PATH` or named in an option): no new dependency. Operations: `open`, `text`, `screenshot` (to a workspace file), `click`, `fill`, `wait_for`, `console`, `failed_requests`. Every network request is checked against `NetPolicy` and the script's allow-list (the protocol's request-interception call, so a page cannot reach what the script did not allow).
- **B. An optional module over Playwright for Java:** less code, a large dependency with its own driver.
- **C. Contract and fake only;** the consumer brings the backend.

**Recommendation: A**, plus the contract and fake of C. A tool result is a `String` (DESIGN §1, not read: phase 31 return path), so a screenshot is **a file the script passes on**, not an image returned to a model. Page text is `unchecked` and the audit marks the kind as reading untrusted content. If the owner prefers less code to write, B is acceptable as an optional module.

**Decision (owner): options A and C together; kept in this phase.** An optional extras module, `ai-agent4j-tools-browser`, with the **contract and a fake** (C) and a **reference backend on the JDK** (A): the Chrome DevTools Protocol over `java.net.http.WebSocket` to a browser the user already has installed (found on `PATH` or named in an option), with no new dependency. Option B (Playwright for Java) was not taken.
**Where it applies:** research on pages that need scripts; checking an app the sandbox serves (D7); filling a form in an approval step; a screenshot as evidence.
**Actions (first version):** `open` (load a URL, wait, return title and status), `text` (visible text, bounded), `screenshot` (a PNG to a workspace file, returning its path), `click` and `fill` (by CSS selector, or by visible text or label), `wait_for` (a selector or text, with a timeout), `console` (bounded), `failed_requests` (bounded).
**Rules**
1. **Every request is checked, not just the first URL:** the protocol's request interception applies `NetPolicy` and the script's allow-list to everything a page loads; loopback and metadata addresses are refused, with the one exception of the one-port grant for a process the run started (D7).
2. **Page content is untrusted:** the audit marks the kind as reading untrusted content, and `text` and `console` are `unchecked`.
3. **A tool result is text:** a screenshot is a file the script passes on, not an image returned to a model (a change to the tool result type is out of this phase, D22).
4. **Isolation:** a fresh profile with no saved cookies or logins; the browser is stopped when the run ends, is cancelled or is recovered after a crash (principle 31); where a container is available it can run in the sandbox.
5. **No downloads, no file pickers and no permission prompts;** dialogs are dismissed and recorded.
6. **Bounded:** time, page text, number of requests and screenshot size, with truncation said.
7. **Fake and contract tests (D15):** the fake plays scripted pages; the real backend is `integration`-tagged and needs an installed browser. The module's README says which browser versions were verified.
**What it costs, accepted:** a small CDP client (about a thousand lines) to write and to keep current as the protocol changes between browser versions, and the largest module among the extras. It is built last in 35e; if it proves too large, it can move out to its own phase (the owner may revisit).

## D17. eval4j enhancements

**Why.** These tools make multi-step and executable evaluations possible; eval4j was built for single-input, single-answer scenarios.

- **A. Four small additions:** (1) **steps** in a scenario (an ordered list, each with its own input and expectations, so "create, then modify" is one scenario) with the old constructor kept; (2) an **`Evidence`** record (the typed result of D12) a `Criterion` can read; (3) an **oracle** that runs a check against an artifact (an SPI in eval4j; implementations in a small new module that depends on both eval4j and the tools module, since neither knows the other); (4) a **cost-per-pass** figure with a confidence interval next to the pass rate, in `weave eval` and the export, and a **versioned export schema**.
- **B. Only (1) and (2);** oracles stay in the vertical.
- **C. Nothing in eval4j;** the vertical brings its own.

**Recommendation: A.** (1) and (2) are needed by anything that evaluates a workflow with steps; (4) mostly exists (Wilson intervals, the cost tail) and needs only the combined figure and a version field. (3) is the one with a cost: a new module. The owner may take B now and (3) with the first user.

**Decision (owner): option B, with five rules.** Four small additions to eval4j and `weave eval`:
1. **Steps in a scenario:** an ordered list of steps, each with its own input and expectations, so "create a page, then add a form to it" is one scenario; the old constructor stays.
2. **Evidence:** a typed record a `Criterion` can read (the `check` result of D12: counts and statuses), in eval4j and with no dependency on the tools module.
3. **Cost per pass with a confidence interval,** next to the pass rate, in `weave eval` and the export; the pieces exist (Wilson intervals, the cost tail), so this adds the combined figure.
4. **A versioned export:** a `schemaVersion` field in the run export, so a reader can tell a newer file.
**Left out for now:** the executable oracle (an interface to run a check against an artifact, with implementations in a new module that depends on both eval4j and the tools module). It comes back with the first real user; until then an evaluator writes a `Criterion` that calls a `sandbox` itself, which `Criterion` already allows.
**Rules**
1. **Compatibility:** steps are an addition to `EvalScenario` and the old constructor is kept; `docs/API_COMPATIBILITY.md` is updated in the same change.
2. **Evidence stays typed and free text stays `unchecked`,** as in D12.
3. **No new dependencies:** eval4j stays as it is.
4. **`weave eval` changes only to print the new figure and read steps;** nothing else in the runner moves.
5. **Tests:** the output of `weave eval` for every existing eval in the repository is pinned before the change.
**Not yet read, to read at the start of 35f:** how a stored run is re-scored, whether `weave eval` runs cases in parallel, and the report module; any of it may change the plan and the owner is told.

## D18. Other tools: now, later, never

**Why.** The phase should not build tools nobody asked for, and should not forget ones many workflows need.

| Tool | Proposal |
|---|---|
| `search` (web search through a keyed provider) | **Now,** optional module: every research workflow needs it |
| `documents` (PDF, DOCX, HTML to text) | **Now,** optional module: intake is the commonest first step |
| `git` (status, diff, log, branch, commit; `push` approved) | **Now,** optional module, through the sandbox contract |
| Object storage (S3, GCS) | **Later:** needs request signing; the `http` kind can reach it meanwhile |
| Message queue (publish, receive) | **Later:** wants idempotency keys and a consumer model; its own decision |
| Chat | **Never as a kind:** the `webhook` kind posts; a bot with replies is a deployer's concern |
| Calendar, speech, image generation | **Later or never:** reachable with `http` and a key; no shared contract to add |
| Package and licence policy | **Vertical,** not the framework |
| Code navigation (symbols, references) | **Vertical,** `find` and `grep` in `workspace` are the general part |

**Recommendation: the table.** The owner can move a row.

**Decision (owner): the table as it stands.** `search`, `documents` and `git` are built now, each as a member of the extras aggregator (D4), in 35e with the browser (D16); the rows marked later, never and the vertical stay as written. Notes carried into the build:
- **`search`:** a provider interface with one reference backend; the key is a `SecretRef` and results are `unchecked`. The provider is named in the module's README only and none is baked in (provider choice involves a key, terms of use and cost, so it is the consumer's).
- **`documents`:** the module depends on **Apache PDFBox** for PDF (the one extras module with a real dependency, which is what the extras structure is for); its dependency tree and licence are checked when it is built (D4 rule 2), and the module publishes the self-contained jar for `weave` that D4 rule 4 requires.
- **`git`:** a thin wrapper over the `git` program run through the sandbox contract, so it inherits isolation and limits; `push` is an effect that needs approval; it must never let `.git/hooks` run on the host, which the workspace deny list (D11) already guards.
- **Weight of 35e:** four members (browser, search, documents, git), each with a README, a fake, a contract test and a profile.

## D19. Security rules and documents

**Why.** A tool that runs model-written code is the largest new attack surface in the project.

Proposed:
1. **LA31:** a program is run, or a process served, at an isolation level below `container` (a finding naming the tool, the level and what can follow), medium when the workspace also holds secrets or the network is `open`, high when both. A `weave audit` rule is listed in `docs/security/weave-audit.md` and mapped to OWASP LLM06 and LLM05.
2. **LA32:** a workflow reaches a process it started (the D7 grant), an info finding.
3. A **threat model** page, `docs/security/sandbox.md`: what the sandbox does and does not stop (a container is not a boundary against a kernel exploit), who owns the image, what a mount exposes.
4. **Secrets into a sandbox are denied by default.** A run gets an environment variable only by naming a `SecretRef`, resolved per call, never cached, never logged, and the audit lists it.
5. `weave review` rules for the same facts (the review rule pages are generated, `make regen`).

**Recommendation: all five.**

**Decision (owner):** open.

## D20. Visibility: the ecosystem

**Why.** The phases so far each moved the whole ecosystem at the end.

Proposed: the tool-kind table in `LOOM_GUIDE.md` and `LOOM_PROMPT.md`; the wiki pages; `llms.txt`; the VS Code grammar and snippets; **two starters** that need no coding background (a report writer that edits a document, checks it and, when the check fails, restores the workspace and tries again; a data analyst that runs a script in the sandbox and checks the result); CTK scenarios; the `llm4j-workflow-guide`, `-harness-design` and `-patterns` skills (tools, isolation, the restore pattern); a tutorial chapter; the release notes; `docs/API_COMPATIBILITY.md` for the new core types.

**Recommendation: all of it,** in 35f, with `make docs-check` as the check.

**Decision (owner):** open.

## D21. Names

**Why.** Words in a Loom script and in a tool's options are public from the day they ship.

Proposed: kinds `sandbox`, `workspace`, `compute`, `check`, `scan`, `browser`, `search`, `documents`, `git`; options `isolation`, `network`, `protect`, `ignore`, `limits`. `check` is already a word for `weave check`; it appears only as a `use:` value, so it does not collide, but the owner may prefer `verify`.

**Recommendation: these, with `check` replaced by `verify` if the owner agrees** (it avoids two meanings in the docs).

**Decision (owner):** open.

## D22. What stays out of this phase

Proposed: any vertical's own tools (package policy, code navigation, a design brief); a `vm` or remote sandbox backend (an SPI, not an implementation); sibling or queue messaging; compensation of external effects (principle 27); framework support for restoring tools on rewind (a `Checkpointable` capability, `side effects: restore`), deferred until real workflows show the D9 pattern is awkward; a GUI or desktop-control tool; image returned to a model from a tool (a change to the tool result type, its own phase); changes to `weave eval` beyond D17; and anything in the VS Code extension beyond grammar and snippets.

**Recommendation: confirm the list.**

**Decision (owner):** open.

## D23. Predictions from a pre-trained model: a `predict` tool

**Deferred (owner): left out of this phase; to be taken up later.** Raised by the owner: an agent is poor at statistical prediction on tabular data, and the traditional answer is to train and tune a model per dataset, while pre-trained tabular models (TabPFN, TabICL) predict in one forward pass; time-series forecasters (Chronos-2, TimesFM) are a related family. What was found when it was raised, so it need not be researched again:
- **Licences are the main trap:** TabPFN's code is Apache 2.0 but its 2.5 and later weights are non-commercial, with outputs limited to non-commercial evaluation and research unless a commercial licence or the paid API is used (the sources disagree on details; read the licence text). TimesFM 3.0 weights are non-commercial while 2.5 is Apache 2.0; Chronos-2 is reported as Apache 2.0 (blog sources only). TabICL has public weights; its licence was not verified.
- **No Java path:** nothing was found on ONNX export or a Java runtime for these models; in practice they run in Python.
- **First reaction (not a proposal):** llm4j owns a `predict` contract in an optional extras module and never ships or defaults to a model; backends behind an interface (the user's own image through `sandbox` with `network: none`, a hosted API whose data leaving the machine the audit shows, and a trivial baseline); results carry cross-validated metrics, the baseline's score, the training-row count and the model's id and version; guards for too-small training sets and a target also in the features; a pinned seed; a journaled effect. Tabular classification and regression first, forecasting a second action later. Tests can cover only the contract against a fake and the baseline; a real model is an `integration` test.
Nothing in this phase depends on it.

