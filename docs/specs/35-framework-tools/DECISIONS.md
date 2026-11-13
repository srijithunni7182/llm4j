# Phase 35: decisions

Status: **draft; all twenty-two decisions are open.** Each gives the question, why it matters, the options and a recommendation. The recommendation is a proposal: the owner decides one at a time and the answer is written under **Decision**. [PRINCIPLES.md](PRINCIPLES.md) holds the principles each option is checked against; [DESIGN.md](DESIGN.md) holds the facts.

## Decision log

| | Decision | State |
|---|---|---|
| D1 | The scope and staging of this phase | open |
| D2 | How a tool kind describes itself | open |
| D3 | Registering and discovering kinds | open |
| D4 | The weight rule and the module layout | open |
| D5 | Isolation as an honest, audited fact | open |
| D6 | The sandbox contract | open |
| D7 | Long-running processes (`serve`) and reaching them | open |
| D8 | Sandbox backends and the default | open |
| D9 | Restore on rewind: the mechanism | open |
| D10 | Snapshot storage and limits | open |
| D11 | The workspace kind, patching and protected paths | open |
| D12 | Evidence: the `check` kind and provenance | open |
| D13 | Pure computation: `compute` | open |
| D14 | Finding secrets and personal data: `scan` | open |
| D15 | Testing: fakes, contract tests, guards, the oracle | open |
| D16 | The browser | open |
| D17 | eval4j enhancements | open |
| D18 | Other tools: now, later, never | open |
| D19 | Security rules and documents | open |
| D20 | Visibility: the ecosystem | open |
| D21 | Names | open |
| D22 | What stays out of this phase | open |

## D1. The scope and staging of this phase

**Why.** Two runtime changes (self-describing kinds, restore) and nine tools are one large phase. The riskiest parts are the audit refactor (35a) and the rewind change (35c).

- **A. One phase, six sub-phases** (35a to 35f, [DESIGN §4](DESIGN.md)), with a review gate after 35a and after 35c.
- **B. Two phases:** the framework (F1 to F5, with `compute`, `workspace` and `sandbox` as the tools that prove it), then the optional modules and eval4j.
- **C. Framework only;** tools later, one at a time.

**Recommendation: A.** The order is built so the owner can stop after any sub-phase with something whole, and the two gates fall where the runtime changes. B is the fallback if the owner finds the phase too large to review.

**Decision (owner):** open.

## D2. How a tool kind describes itself

**Why.** Eight places in Loom hold a list of kind names. Every new kind would need all eight changed, and a missed one fails quietly (the audit treats the kind as unknown, which is safe but noisy, or `weave test` cannot script it).

- **A. A default method on `ToolKind`,** `profile(options)`, returning a core record: the three reach booleans, the effect level, restorable, the isolation level, a note. The eight sites ask the kind; their lists are deleted. A kind that does not answer is "unknown", as today.
- **B. A separate registry in Loom** that kinds register metadata into: no core change, but the kind and its facts live in different places and can disagree.
- **C. Keep the lists** and add the new kinds to each.

**Recommendation: A.** It is the smallest core change (one default method and one record), keeps a kind's facts next to its code (principle 24), and old kinds keep working. Condition: a **characterization test** pins the audit output for every repository script before the refactor and after; the refactor does not start without it.

**Decision (owner):** open.

## D3. Registering and discovering kinds

**Why.** `ToolFactory` registers kinds by hand. Optional modules (browser, documents) cannot be registered by the tools module, which must not depend on them.

- **A. Explicit only:** the host lists every kind it wants (the weave CLI would list the optional ones, so it would have to depend on them).
- **B. Explicit for the built-ins, `ServiceLoader` for optional modules** found only when their jar is on the classpath.
- **C. `ServiceLoader` for everything.**

**Recommendation: B.** `ServiceLoader` is a JDK feature with no scanning and no reflection beyond instantiating a listed class, so it fits principle 8 of the repository. The built-ins stay explicit so the core set is visible in one place. An optional kind that fails to load is a load error naming the module, never a silent absence.

**Decision (owner):** open.

## D4. The weight rule and the module layout

**Why.** Principle 25: the tools module must stay light. Several of these tools tempt a dependency (a PDF library, a browser driver, a container client).

- **A. In `ai-agent4j-tools` (JDK and Jackson only):** `sandbox`, `workspace`, `compute`, `check`, `scan`. **Separate optional modules:** `ai-agent4j-tools-browser`, `-documents`, `-search`, `-git`.
- **B. Everything in the tools module,** with the heavy parts as `<optional>` dependencies.
- **C. One new module for everything new.**

**Recommendation: A**, with a **pinned test** that fails if `ai-agent4j-tools` gains a dependency (it reads the resolved dependency list), and a size note in the release notes. `search` and `git` are light and could live in the tools module; they are separate only so a user who wants neither pays nothing, and the owner may prefer to fold them in.

**Decision (owner):** open.

## D5. Isolation as an honest, audited fact

**Why.** The audit cannot tell a program run on the host from one run in a container. A reader of `weave audit` should see which it is, and a script that needs more than the backend gives should not run.

- **A. Declared, reported and enforced:** the script declares the **minimum** isolation it needs (`isolation: container`); the backend reports the level it applied; a request the backend cannot meet fails closed at load (when the runtime can be probed) or at first use; the audit shows the reported level and a rule (LA31) flags a program run at `none` or `process`.
- **B. Advisory:** a field in the audit, no enforcement.
- **C. None:** the sandbox is as isolated as its backend and the docs say so.

**Recommendation: A.** Levels: `none`, `process`, `container`, `vm`. There is **no default**: a `sandbox` tool without an `isolation` line is a load error, so nobody gets a weak level by omission. The fallback rule (never to a weaker level) is a test, checked by removing the container runtime.

**Decision (owner):** open.

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

**Decision (owner):** open.

## D7. Long-running processes (`serve`) and reaching them

**Why.** Anything that checks a running thing (a web app, a server, a notebook) needs a process that outlives one call. It also needs a way for the `http` or `browser` tool to reach it, and `NetPolicy` refuses loopback on purpose.

- **A. `serve`, `stop` and `status` operations,** with a **lease** (D5's resource owner, principle 31): a handle, a loopback port chosen by the backend, a deadline, stopped on run end, cancel and crash recovery. A tool that wants to reach it is granted **that one port, for the life of the handle**, through a narrow `NetPolicy` grant, not a loopback switch.
- **B. `serve`, and a generic `allow_loopback: true` option** on `http` and `browser`.
- **C. No `serve` in this phase;** `run` only.

**Recommendation: A**, in 35d if the owner is willing, else C and a later phase. B is refused: it turns the SSRF guard off for every address on the machine. The grant must be journaled (the audit shows "this workflow reaches a process it started").

**Decision (owner):** open.

## D8. Sandbox backends and the default

**Why.** llm4j cannot ship a microVM and should not pretend a plain process is a sandbox.

- **A. Three:** `process` (honest level `process`: a child process with a scrubbed environment, a working-directory jail and the limits the JDK can enforce, which are wall time and output; it cannot enforce memory or network), `container` (the `docker` or `podman` CLI through `ProcessBuilder`, no client library), and a `fake` for tests. `vm` and remote services are an SPI the consumer implements.
- **B. Container only,** with a fake. No `process` backend: a script cannot run anywhere without a container runtime.
- **C. SPI only:** the consumer brings every backend.

**Recommendation: A.** `process` is there so a developer can try a workflow on a laptop, and it is flagged by LA31 (D5) so it is never mistaken for isolation; the container backend is the one to recommend. The runtime is probed once at load; the container image is named in the script and never pulled implicitly (a pull is a network effect, so it is an explicit option).

**Decision (owner):** open.

## D9. Restore on rewind: the mechanism

**Why.** Rewind today re-runs or skips effects; it cannot give a directory back. Retrying a build or a document edit from a clean state needs that.

- **A. A `Checkpointable` capability in the core;** at a `checkpoint`, Loom snapshots each checkpointable tool in scope and records the id in the journal; `rewind ... effects: restore` restores them before the attempt is replayed; anything else in the discarded attempt still needs `ask first`, `keep` or `repeat`.
- **B. Compensation per effect** (a saga: each effect names its undo). Powerful, but it invites claiming that a send can be undone.
- **C. Nothing in Loom:** the script calls `workspace restore` itself after a rewind.

**Recommendation: A.** It is the one change to the rewind runtime, and it is narrow: restore touches only tools that declare `restorable`. B is refused (principle 27). C works today and is the fallback, but it loses the guarantee that the restore happens before the replay and is journaled. `RewindChecks` gains a rule: `effects: restore` on a script with no restorable tool in scope is a load error.

**Decision (owner):** open.

## D10. Snapshot storage and limits

**Why.** A snapshot of a tree with `node_modules` is gigabytes. The mechanism must be correct before it is clever.

- **A. A content-addressed copy store** under the run folder (JDK only: SHA-256 per file, a manifest, files stored once), with `ignore` globs, a cap on files and bytes, and a hash verified on restore.
- **B. Git through the CLI** (needs `git`; handles large trees by deltas).
- **C. Filesystem features** (reflinks, overlay, ZFS): fast, not portable.

**Recommendation: A**, with a default ignore list (`node_modules`, `target`, `.git`, build outputs) the script can change, a cap that fails loudly ("the workspace is larger than the snapshot cap; ignore a path or raise the cap"), and the manifest hash in the journal. B can be a second backend later. A restore is **refused** while a served process holds the workspace (D7).

**Decision (owner):** open.

## D11. The workspace kind, patching and protected paths

**Why.** The `file` kind (read, list, write) is a stable public surface. An editing agent needs more, and needs some files out of its reach.

- **A. A new `workspace` kind** (a superset of `file`, restorable), operations: `read`, `list`, `write`, `patch`, `find`, `grep`, `diff`, `snapshot`, `restore`; `file` is unchanged.
- **B. Add the operations to `file`.** One kind, but it widens a published surface and mixes a simple tool with a stateful one.

**Patch format:** a model is more reliable with search-and-replace blocks than with unified diffs, so `patch` takes **a list of (old text, new text) edits for one file plus the hash of the file it was written against**; it fails, changing nothing, when the hash differs, an old text is not found exactly once, or the file is protected. A unified diff may be accepted later.
**Protected paths:** `protect: ["tests/**", "oracle/**"]` makes those paths read-only to the agent (in the tool and in the sandbox mount). This is what keeps evidence honest (principle 28): a model cannot edit the test that judges it.

**Recommendation: A**, with the patch and the protected paths as above.

**Decision (owner):** open.

## D12. Evidence: the `check` kind and provenance

**Why.** Many workflows decide on the result of a verifier. Today that is a string the model reads.

- **A. A `check` kind** that parses JUnit XML, TAP, SARIF, an exit code or a JSON Schema validation into one record: `passed`, `failed`, `skipped`, `total`, a list of items (name, status, message), the source. Counts and statuses are typed and trusted as *parsed by the host*; **names and messages stay `unchecked`**. The record never says "requirement met".
- **B. Leave parsing to the script** (a `shell` or `sandbox` call and text matching).

**Recommendation: A.** The provenance oracle gets a canary that travels through a verifier's output, to prove no model-written value becomes trusted by passing through it. A parse failure is a failed check, never a pass.

**Decision (owner):** open.

## D13. Pure computation: `compute`

**Why.** Models are poor at arithmetic and reshaping data, and a pure tool has no reach and nothing to audit. It is also the cheapest tool to add.

- **A. One `compute` kind with operations:** exact arithmetic (`BigDecimal`, a bounded expression grammar, no scripting engine); date arithmetic with an injected `Clock`; JSON path and a small transform; CSV statistics (count, sum, mean, percentile, group by); a regex test with a step limit (against ReDoS).
- **B. Several small kinds.**
- **C. Leave it to the model or the sandbox.**

**Recommendation: A.** Pure, deterministic, replays exactly, `reach: none`. Bounds: expression length and depth, result size, a time limit. It is the first tool to build, to prove the self-describing profile (D2) on a kind with nothing to hide.

**Decision (owner):** open.

## D14. Finding secrets and personal data: `scan`

**Why.** A guard before anything is sent or published, and a check an agent can run on its own work.

- **A. A `scan` kind** over text or a file: secrets (known token shapes, high-entropy strings) and personal data (reusing the core `PIIDetector`), reporting **a rule name and a location, never the match**. JDK only; the rules are versioned so a finding can be reproduced.
- **B. Only a Loom `guard`,** no tool.
- **C. An optional module** wrapping a scanner program through the sandbox.

**Recommendation: A**, and say plainly in the docs that it is a net, not a proof: false negatives exist. Package and licence policy checks are **not** here: they belong to the vertical (D22).

**Decision (owner):** open.

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

**Decision (owner):** open.

## D16. The browser

**Why.** Reading pages that need scripts, checking a running page and filling a form are things an agent cannot do with `http`. It is also the heaviest tool and the one that reads the most untrusted content.

- **A. An optional module with a reference backend over the Chrome DevTools Protocol,** written on the JDK (`java.net.http.WebSocket`) against a browser the user has installed (found on `PATH` or named in an option): no new dependency. Operations: `open`, `text`, `screenshot` (to a workspace file), `click`, `fill`, `wait_for`, `console`, `failed_requests`. Every network request is checked against `NetPolicy` and the script's allow-list (the protocol's request-interception call, so a page cannot reach what the script did not allow).
- **B. An optional module over Playwright for Java:** less code, a large dependency with its own driver.
- **C. Contract and fake only;** the consumer brings the backend.

**Recommendation: A**, plus the contract and fake of C. A tool result is a `String` (DESIGN §1, not read: phase 31 return path), so a screenshot is **a file the script passes on**, not an image returned to a model. Page text is `unchecked` and the audit marks the kind as reading untrusted content. If the owner prefers less code to write, B is acceptable as an optional module.

**Decision (owner):** open.

## D17. eval4j enhancements

**Why.** These tools make multi-step and executable evaluations possible; eval4j was built for single-input, single-answer scenarios.

- **A. Four small additions:** (1) **steps** in a scenario (an ordered list, each with its own input and expectations, so "create, then modify" is one scenario) with the old constructor kept; (2) an **`Evidence`** record (the typed result of D12) a `Criterion` can read; (3) an **oracle** that runs a check against an artifact (an SPI in eval4j; implementations in a small new module that depends on both eval4j and the tools module, since neither knows the other); (4) a **cost-per-pass** figure with a confidence interval next to the pass rate, in `weave eval` and the export, and a **versioned export schema**.
- **B. Only (1) and (2);** oracles stay in the vertical.
- **C. Nothing in eval4j;** the vertical brings its own.

**Recommendation: A.** (1) and (2) are needed by anything that evaluates a workflow with steps; (4) mostly exists (Wilson intervals, the cost tail) and needs only the combined figure and a version field. (3) is the one with a cost: a new module. The owner may take B now and (3) with the first user.

**Decision (owner):** open.

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

**Decision (owner):** open.

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

Proposed: the tool-kind table in `LOOM_GUIDE.md` and `LOOM_PROMPT.md`; the wiki pages; `llms.txt`; the VS Code grammar and snippets; **two starters** that need no coding background (a report writer that edits a document, checks it and rewinds with `restore`; a data analyst that runs a script in the sandbox and checks the result); CTK scenarios; the `llm4j-workflow-guide`, `-harness-design` and `-patterns` skills (tools, isolation, restore); a tutorial chapter; the release notes; `docs/API_COMPATIBILITY.md` for the new core types.

**Recommendation: all of it,** in 35f, with `make docs-check` as the check.

**Decision (owner):** open.

## D21. Names

**Why.** Words in a Loom script and in a tool's options are public from the day they ship.

Proposed: kinds `sandbox`, `workspace`, `compute`, `check`, `scan`, `browser`, `search`, `documents`, `git`; the rewind word `restore` (`effects: restore`, in the same family as `ask first`, `keep`, `repeat`); options `isolation`, `network`, `protect`, `ignore`, `limits`. `check` is already a word for `weave check`; it appears only as a `use:` value, so it does not collide, but the owner may prefer `verify`. The parser must read `restore` as a keyword only in the position after `effects:`.

**Recommendation: these, with `check` replaced by `verify` if the owner agrees** (it avoids two meanings in the docs).

**Decision (owner):** open.

## D22. What stays out of this phase

Proposed: any vertical's own tools (package policy, code navigation, a design brief); a `vm` or remote sandbox backend (an SPI, not an implementation); sibling or queue messaging; compensation of external effects (principle 27); a GUI or desktop-control tool; image returned to a model from a tool (a change to the tool result type, its own phase); changes to `weave eval` beyond D17; and anything in the VS Code extension beyond grammar and snippets.

**Recommendation: confirm the list.**

**Decision (owner):** open.
