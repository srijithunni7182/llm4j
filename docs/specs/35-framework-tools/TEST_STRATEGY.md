# Phase 35 test strategy: self-describing tools and a wider tool set

Status: **final for phase 35, ready to build.** Companion to [DESIGN.md](DESIGN.md); the decisions behind it are D15 and parts of D2 to D14, D16, D17 and D19 in [DECISIONS.md](DECISIONS.md), all taken. Nothing is built, so every test below is to be written, and **the guard tests are written before the code they guard** (red, then green).
It continues [phase 34's strategy](../34-supervision-signals/TEST_STRATEGY.md): the same rules (hermetic, behaviour not structure, a guard is tested by breaking it, the tiers stay honest) and the guard numbering carries on from G36.

## 1. What phase 35 adds to the test tools

- **A fake per kind that cannot be hermetic by itself.** `sandbox` (a `SandboxBackend` that never runs a program), `browser` (a scripted page and console), `search` and `documents` (scripted answers), `git` (scripted output). Where the real tool is hermetic, the test uses it directly: `workspace` on a temporary directory, `compute`, `verify` and `scan` on text.
- **`weave test` learns the new kinds through their profiles and fakes (D2),** not through a list of names: `ScriptedTools` asks the kind.
- **An injected `Clock`** for `compute`'s `date` action, so every date test is exact.
- **A real `process` backend that runs the JDK's own `java`** (from `java.home`) with a tiny class as its program, so the backend contract is tested with no external program in the default build.
- **A scripted DevTools endpoint** for the browser's contract tests: OkHttp `MockWebServer` with a WebSocket upgrade plays scripted protocol messages, including a page that asks for a disallowed host.
- **A fake resolver** for the egress proxy's address checks (the `NetPolicy.Resolver` seam), so "a name on the list resolves to a private address" is a unit test.
- **A characterization harness for the audit** that records the output of `weave audit` and `weave review` for every `.loom` file in the repository (`RepositoryScriptsTest` already walks them), and one for `weave eval` on every existing eval.
- **A kill-point harness** (the existing one) extended to the new journaled writes: a `sandbox` `run`, a `workspace` `snapshot` and `restore`, a `serve` lease.

## 2. Layers

| Layer | What it proves | Where |
|---|---|---|
| **L1 unit** | Pure logic: the `compute` expression grammar, the JUnit and JSON Schema parsers, the `scan` rules, the patch applier, the glob and path matching, the isolation and limit validators | each module's tests |
| **L2 contract** | One set of cases per kind, run against the fake and against the real hermetic implementation; `@Tag("integration")` against a real runtime | tools and extras modules |
| **L3 Loom** | The audit output (characterization), the `weave check` kind list and its messages, `weave test` scripting of the new kinds, LA31 to LA33 | Loom module |
| **L4 pattern** | The D9 pattern with `workspace` and the fake sandbox: a rewind with `side effects: repeat` restores the files and runs the identical writes again | Loom module |
| **L5 integration** | The container backend, the egress proxy on each platform, a real browser | `@Tag("integration")`, only where a runtime exists |
| **L6 oracle** | No model-written value is trusted after passing through a `sandbox` result, a `verify` record, a `scan` finding or a page | `make test-oracle` |

## 3. The guards and how each is broken on purpose

| # | Guard | The test | Break it by |
|---|---|---|---|
| G36 | **The refactor changes no audit output** (D2) | Record `weave audit` and `weave review` for every repository script before the change; after it the output is identical. Any difference is reported to the owner, not resolved | Changing one built-in kind's profile (for example `file` read-only reports a write) |
| G37 | **A kind that does not answer `profile` is assumed to reach everything** (D2) | A test kind with no `profile` is audited: untrusted, private, outward, effect | Making the default "reaches nothing" |
| G38 | **A kind name is registered once** (D3 rule 2) | A second kind named `file` is a load error naming both sources | Letting a later registration replace an earlier one |
| G39 | **A kind that fails to load names its module; an absent module is named** (D3 rules 1 and 4) | A broken service entry, and a script using `browser` without the jar, give messages that say what to add | Swallowing the load failure |
| G40 | **The light module's dependencies do not grow** (D4 rule 2) | A test reads the resolved dependency list of `ai-agent4j-tools`; each extras module has its own allow-list test | Adding a dependency |
| G41 | **A `sandbox` has no default isolation** (D5 rule 1) | A tool without `isolation:` is a load error | Defaulting to `process` |
| G42 | **A request for `container` never falls back to a weaker level** (D5 rule 2) | With the runtime absent (a fake probe), the tool fails with the message that names what to install | Falling back to `process` |
| G43 | **The reported level is what is shown, and a mismatch stops the run** (D5 rule 4) | A backend that reports a lower level than asked stops the run; the audit shows the reported one | Showing the requested level |
| G44 | **Only an allow-listed program runs, with an argument list, in the workspace** (D6) | A program not on the list, a string with spaces taken as a command line, and a `cwd` that escapes the workspace are each refused | Passing the line to a shell, or skipping the `cwd` check |
| G45 | **The environment is empty unless a `SecretRef` is named** (D6, D19) | No variable reaches the program by default; a named secret is resolved per call and appears nowhere in the result, journal or trace | Inheriting the host environment |
| G46 | **The workspace is the only mount, and protected paths are read-only in it** (D6, D11) | The mount list from the container backend's command line (a unit test on the argument builder); a write to a protected path is refused in the tool | Adding a second mount, or mounting protected paths read-write |
| G47 | **A backend that cannot enforce `network: allow` refuses it** (D6, D8) | The `process` backend with `network: allow` is a load error | Treating `allow` as `open` |
| G48 | **The proxy refuses loopback, private and metadata addresses even for a listed name, and connects to the address it resolved** (D6) | A fake resolver returns a private address for a listed name: refused; a second resolution returning a different address does not change the target | Skipping the address check, or resolving twice |
| G49 | **Time limits are relaxed but bounded, and an agent can only lower them** (D6) | The default is the constant; a script above the ceiling is refused; an agent asking for more gets the script's value; `memory`, `pids` or `disk` with `process` is a load error | Removing the ceiling, or letting the agent raise the limit |
| G50 | **Output is cut keeping the start and the end, and says so** (D6) | A long output returns both ends, `truncated: true` and the cut size | Keeping only the start |
| G51 | **A `run` is journaled and not repeated on resume** (D6) | Kill before and after the journal write of a `run`; resume; the program ran once | Writing the journal entry after returning |
| G52 | **Leftovers from a crashed run are removed** (D7, D8) | A registry file naming a container and a port; a resumed run removes them before work continues (the fake backend records the stop) | Skipping the registry read on resume |
| G53 | **A served process is reachable only on its own port, only while its handle lives** (D7) | The grant is exactly `127.0.0.1:<port>`; after `stop` it is gone; a second port is refused; the live-handle and deadline limits fail loudly | A general loopback allowance |
| G54 | **`workspace` needs `allow`, keeps to its root and never follows a symlink** (D11 rules 1 and 2) | No `allow` is a load error; a symlink to a file outside the root is refused by `read`, `write`, `find`, `grep`, `snapshot` and `restore` | Following links |
| G55 | **The built-in deny list cannot be overridden** (D11 rule 3) | A write under `.git/**` and a read of `.env`, `x.pem`, `x.key` and `id_rsa` are refused even when `allow` matches them | Letting `allow` win |
| G56 | **A patch applies exactly or not at all** (D11) | An `old` found twice or not at all, overlapping edits, a protected file and a wrong `version` each fail with a message and leave the file unchanged | Applying edits one after another, or partly |
| G57 | **Restore is exact, idempotent and bounded** (D11 rules 1, 2 and 5) | Files since created are deleted; ignored and protected paths are untouched; restoring twice gives the same state; a workspace over the cap makes no partial snapshot; a changed stored file fails the restore and names it | Leaving new files, or taking a partial snapshot |
| G58 | **`snapshot` and `restore` are journaled and not redone on resume** (D11 rule 4) | Kill and resume: the recorded snapshot id is reused, not a snapshot of later state | Not declaring them effects |
| G59 | **A restore is refused while a process is served** (D7, D11) | A live handle makes `restore` fail naming the handle | Ignoring the lease |
| G60 | **The D9 pattern works: a rewind with `repeat` after a restore runs the identical writes again** (D9) | With `workspace` and the fake sandbox: attempt one writes and fails a check, the rewind restores, attempt two's identical write lands | Using `keep`, or skipping the restore: the file is missing and the test fails |
| G61 | **A parse failure is a failed check, an empty report is `total: 0`, and names and messages stay `unchecked`** (D12) | Malformed XML, an empty report, and a canary in a test name that must carry the untrusted mark | Counting a failed parse as a pass |
| G62 | **`compute` is exact, bounded and deterministic** (D13) | `0.1 + 0.2` is `0.3`; division by zero, over-long, over-deep and over-size inputs are refused with a reason; `date` with a fixed clock | Using binary floats, or removing a bound |
| G63 | **`scan` never echoes a match** (D14) | A canary secret in the input appears in no result, journal entry, trace, log line or exception message | Including the match in a message |
| G64 | **No model-written value becomes trusted through a new tool** (D15) | The provenance oracle gets canaries through a `sandbox` result, a `verify` record, a `scan` finding and a browser page | Marking a parsed text field as trusted |
| G65 | **The browser checks every request, and stops with the run** (D16) | A page that loads a disallowed host and a loopback address: both blocked and listed in `failed_requests`; downloads and dialogs refused and recorded; the browser stops on end, cancel and crash recovery | Checking only the first URL |
| G66 | **LA31, LA32 and LA33 fire at the right severity and not otherwise** (D19) | One test each, including a no-fire case; the rules are listed in `weave-audit.md` and mapped (`SecurityDocTest`) | Dropping a rule, or a wrong severity |
| G67 | **eval4j stays compatible and the new figures are right** (D17) | The old `EvalScenario` constructor works; every existing eval's output is unchanged; steps run in order; cost per pass has its interval; the export carries `schemaVersion` | Removing the old constructor, or changing an existing figure |
| G68 | **The documentation is correct** (D20) | Every README sample is tested (`*DocsTest`); starters pass `StartersTest` and `RepositoryScriptsTest`; a CTK scenario per kind; the skills' shared references stay identical; `make docs-check` is clean | A stale sample, table row or generated file |

## 4. Mutation and coverage

- **A new profile, `mutation-tools`,** covers the deciding classes of the tools module: the path guard and the workspace jail, the patch applier, snapshot and restore, the `scan` rules, the `verify` parsers, the `compute` parser, and the sandbox's request validator and isolation check. **85% or more;** surviving mutants are killed with better assertions, never excluded.
- **The Loom `mutation` profile gains the profile-driven part of `Capabilities`;** its score cannot drop.
- New classes meet the per-class JaCoCo gates of the module they land in; the extras modules get gates equal to the tools module's. No limit is lowered and no exclusion added.

## 5. The tiers and the loop

As phases 33 and 34: `-Dtest=` while working; `make test-core` after each sub-phase (and `make docs-check` after docs, a command, a starter or a review rule); `make test-fast` on every module touched after the phase (core, tools, the extras, eval4j, Loom; the extension's own `npm` tests where it changed); `make format-check`; `make test-oracle` once at the end; never `make test-full` or `make test-live`. The `integration` tests run only where a runtime exists, and the report says which ran. The move to `src/tools/` updates every script and Makefile path in its own commit, and CI is its test.

## 6. What is not tested here

A container's kernel boundary; the proxy on a platform that was not verified (the docs list the verified ones, D8); a real model's judgement; a real browser version other than the ones named in its README; Windows; the time to hash a very large tree beyond the cap; and the by-hand run of the two starters with a real key (E4, the owner's).
