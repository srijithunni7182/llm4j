# Completion Verification Plan

> **Layout update.** The tools were later moved out of Loom into the `ai-agent4j-tools` library (package
> `io.github.llm4j.tools`; see design.md §9). Where this document says `io.github.llm4j.loom.tools.generic` or
> `loom/ai-agent4j-loom/.../generic`, read the tools module for the tools, guards and their tests, and Loom for the
> executor, parser, CLI and guide tests.

[`verification.md`](verification.md) lists the checks the work must pass, and
[`test-strategy.md`](test-strategy.md) says how they are built. This plan says **how we prove the work is
finished**: a fixed sequence of gates, each with a command, the evidence it leaves, and a pass rule. It is
run at the end by someone who didn't write the code, or, failing that, by the author with a clean
checkout and every step done literally.

The point is to rule out the usual ways "done" is wrong:

| How "done" goes wrong | Gate that catches it |
|---|---|
| A requirement has no test | G2 (every check ID has a test; every criterion has a check) |
| A test passes but checks nothing | G3 (sabotage run: break a rule, see a failure) |
| Tests were skipped, disabled or quietly narrowed | G2, G8 (no `@Disabled`; skipped count is explained) |
| It works in the author's tree but not on a clean one | G1 (clean checkout, twice) |
| Old behaviour moved | G4 (existing suites, unchanged) |
| The unit tests pass but a real script doesn't work | G5 (real runs of the sample and each tool) |
| The packaged app differs from the tested classes | G6 (the `weave` JAR, not `target/classes`) |
| The docs describe something else | G7 (documented examples and commands executed) |
| It is safe in tests and unsafe in the open | G9 (hostile suite, secrets sweep, manual security review) |

## Conventions

- **Working directory:** the repository root. The module is `loom/ai-agent4j-loom`; commands use
  `-pl loom/ai-agent4j-loom -am` so ai-agent4j is built first.
- **Evidence** goes in `.kiro/specs/loom-generic-tools/evidence/`, one file per gate, named as below, and
  is committed. A gate with no evidence file has not passed.
- **A gate fails** if any listed condition fails. There is no partial pass: fix, then re-run that gate *and
  every later gate*.
- **Record** the commit SHA at the top of each evidence file. Evidence for a different SHA than the final
  one is void.

## Check IDs in the tests

So that G2 can be mechanical, every test that implements a check from `verification.md` carries its ID:

```java
@Tag("V4.1") @DisplayName("V4.1 slack: body, content type, result text")
@Test void slackBody() { … }
```

A test covering several checks carries several tags. Fuzz, hostile and concurrency tests use their IDs
(`F1`, `H5`, `C3`). Live checks use `L1`–`L4` plus `@Tag("live")`.

## The verification script

`scripts/verify-generic-tools.sh` (written as task 15 and itself reviewed) runs the mechanical gates and
writes evidence. It has no logic that can't be read in one sitting. It **only reports**; it fixes nothing.

```bash
scripts/verify-generic-tools.sh            # G1, G2, G4, G6, G8 and the reports G3/G7/G9 need
scripts/verify-generic-tools.sh --gate G2  # one gate
```

The gates below say which parts are scripted and which are done by hand.

---

## G0 Preconditions

| | |
|---|---|
| Do | Confirm the work is on one branch, the tree is clean (`git status` shows nothing), and `tasks.md` has every box ticked except the optional live task. Record the SHA. |
| Evidence | `evidence/G0-preconditions.txt`: SHA, `git status --short` (empty), the unticked boxes (only task 14). |
| Pass | Clean tree. No unticked box other than task 14. |

## G1 Clean build and full tests, twice

| | |
|---|---|
| Do | In a **fresh clone** at the SHA, with an empty local Maven repository for the project's own artifacts: |
| | `git clone <repo> verify && cd verify && git checkout <SHA>` |
| | `mvn -B -pl loom/ai-agent4j-loom -am clean install` (full build with tests and the JaCoCo check) |
| | Run it a second time, then once more with `-Dsurefire.runOrder=random -Dloom.fuzz.iterations=20000`. |
| Evidence | `evidence/G1-build.txt`: the three command lines, the Maven summaries, and the surefire totals (tests, failures, errors, skipped) for each run. |
| Pass | All three runs succeed. Failures and errors are 0. The two plain runs have the **same** test count. Skipped tests are listed and every one is one of: a `@Tag("live")` test, an OS-specific test skipped on the other OS, or a symlink test on a platform that can't make symlinks. Total added test time is under 60 s (strategy §2). |

## G2 Traceability: nothing unchecked, nothing unimplemented

Scripted. Three comparisons, each a plain set difference whose output is saved.

| Comparison | Pass rule |
|---|---|
| **Checks → tests.** Every ID in a table row of `verification.md` (V1.1 … V13, F1–F5, H1–H6, C1–C5; L1–L4 excluded) against the `@Tag` values in the surefire XML reports. | Every ID has at least one **executed, passing** test. IDs with only skipped tests fail. |
| **Tests → checks.** Every `@Tag` of that form in the test sources against `verification.md`. | No tag names an ID that doesn't exist (catches typos that hide a gap). |
| **Criteria → checks.** Every numbered acceptance criterion in `requirements.md` against the matrix in `test-strategy.md` §9. | Every criterion appears in at least one matrix row. |
| **Disabled tests.** `grep -rn "@Disabled" loom/ai-agent4j-loom/src/test/…/generic`. | None. |
| **Assertions exist.** The script lists any test method in `generic/` whose body has no `assertThat`, `assert*`, `verify` or `assertThrows`. | The list is empty, or each entry is justified in the evidence file. |

Evidence: `evidence/G2-traceability.txt` with the three diffs (each empty) and the justification list.

## G3 The tests can fail (sabotage)

A passing suite proves little if the tests can't fail. For each guard, break one rule on a **scratch
branch**, run only the affected tests, and confirm at least one fails. Then discard the branch.

| # | Sabotage | Expected failing checks |
|---|---|---|
| S1 | `NetPolicy`: stop unmapping IPv4-mapped IPv6 addresses | V3.2 |
| S2 | `NetPolicy`: check the address but connect with a second lookup (bypass the checking `Dns`) | V3.3 |
| S3 | `HttpKind`: accept `//host` in `path` | V6.2, H2, F2 |
| S4 | `Redactor`: skip the Base64 form | V1.5, F4 |
| S5 | Any kind: put the URL (or password) in an error message | V1.5, H1 |
| S6 | `EffectTool`: don't write `pending` before acting | V2.3, V2.4, V4.7 |
| S7 | `EffectTool`: return the recorded result for `effect_failed` | V2.7 |
| S8 | `FileKind`: allow names starting with `.` | V7.3, H4, F2 |
| S9 | `SafePaths` use in `file`: skip the symlink check | V7.3 |
| S10 | `ShellKind`: run through `sh -c` | V8.1, H5 |
| S11 | `ShellKind`: don't clear the environment | V8.5 |
| S12 | `ShellKind`: skip the interpreter deny-list | V8.3 |
| S13 | Approval rule for `shell`: remove it | V8.10 |
| S14 | `SqlGuard`: drop `INTO` from the keywords | V9.3, F1, H6 |
| S15 | `SqlKind`: don't call `setReadOnly(true)` | V9.4 |
| S16 | `SqlKind`: build the query by string concatenation of params | V9.1 |
| S17 | `EmailKind`: allow CR/LF in the subject | V5.3, F3, H3 |
| S18 | `EmailKind`: skip the `allow_to` check | V5.2, H3 |
| S19 | `EmailKind`: enable `sendpartial` | V5.11 |
| S20 | `max_per_run`: count only `done` records | V5.4, V5.12, C3 |
| S21 | `Limits.readCapped`: read the whole body | V3.7, V8.8 |

Evidence: `evidence/G3-sabotage.md`, one line per row: the sabotage (a diff hunk or commit message on the
scratch branch), the tests that failed, and "restored". **Pass:** every row fails at least one test, and
the failing tests include the ones listed (extra failures are fine; none of the listed failing means the
check is too weak and must be strengthened). The scratch branch is deleted and the final SHA's tree is
unchanged (`git diff <SHA>` empty).

## G4 Nothing that existed has moved

| | |
|---|---|
| Do | Run the **whole** Loom suite and the ai-agent4j suite from the G1 checkout, and compare with the baseline: the same suites at the commit before this work started (`ab316b4`'s parent, the merged `nifty-lovelace` head `71f67cb`). |
| | `mvn -B -pl ai-agent4j,loom/ai-agent4j-loom test` at both commits. |
| Evidence | `evidence/G4-regression.txt`: per-class test counts at the baseline and now. |
| Pass | Every test that existed at the baseline exists now and passes (no test removed, renamed away or relaxed: `git diff 71f67cb -- '*Test.java'` shows only additions in pre-existing test files, or each modification is explained). The `ApprovalGate.key` values are unchanged (V2.11). |

## G5 Real runs, outside the test harness

Mechanical tests use fakes. This gate runs the real `weave` on real local services, once, by hand, and
records the transcript. All of it runs on the verification machine with no cloud accounts.

| # | Run | Pass rule |
|---|---|---|
| R1 | **The sample.** `samples/digest/run.sh` with a real model key if available (else the scripted model switch the sample documents). | Produces the report file and state file, leaves an `.eml` in the outbox, and a second run reads the first run's state. |
| R2 | **Crash and resume.** Run the sample with `--journal runs/d1`, kill the process (`kill -9`) after the report is written and before the notification, then run it again with the same journal. | The webhook stand-in (a `python3 -m http.server`-style listener or `nc -l` capturing requests) received the notification **once**. |
| R3 | **Each tool by hand.** One short script per tool in `evidence/scripts/`, using a local stand-in: a local HTTP listener (`webhook`, `http`), a local SMTP sink (`python3 -m aiosmtpd`, or the outbox), a temp directory (`file`), `echo`/`df` (`shell`), a H2 or PostgreSQL database (`sql`). | Each does what it says; each refuses one hostile input chosen by the verifier (not taken from the test list) with `Error:` and no effect. |
| R4 | **Scheduling.** `weave schedule sync digest.loom --store /tmp/t` then `weave triggers install /tmp/t` (dry), then `--apply` on a throwaway user, then `weave triggers list`, then `weave triggers uninstall --apply`. | Output matches the guide; nothing remains installed afterwards. |
| R5 | **Cost and quota.** Run the sample with `budget { tokens: 1 }`. | The run refuses the model call before sending anything, and the tools are not called. |

Evidence: `evidence/G5-runs.md`: each command, its (secret-free) output, and a one-line verdict. **Pass:**
all five verdicts are "as expected", and the verifier's own hostile inputs in R3 were refused.

## G6 The packaged app

The tests run against classes. Users run the shaded JAR.

| | |
|---|---|
| Do | `mvn -B -pl loom/ai-agent4j-loom -am package`, then run R1, R3's `email` and `sql` scripts, and a `webhook` call with **only** the packaged JAR on the classpath (`java -jar …` or `weave package … --fat`), from an empty directory. |
| | List the JAR: `unzip -l …jar | grep -E 'angus|jakarta.mail|postgresql'`. |
| | Start a script that uses none of the new tools and confirm (class-loading probe, `-verbose:class | grep -c jakarta/mail`) no mail class is loaded. |
| Evidence | `evidence/G6-package.txt`. |
| Pass | The scripts run from the JAR. Angus Mail and the PostgreSQL driver are in it. No mail class is loaded when `email` isn't used. A script whose JDBC driver is missing (e.g. an `jdbc:oracle:` URL) fails at load with the "no JDBC driver" message. |

## G7 Documentation and tooling match reality

| | |
|---|---|
| Do | (a) Run the guide-examples test (V10.1): it reads the "Generic Tools" section of `LOOM_GUIDE.md`, extracts every fenced `loom` block and validates each one, so the examples can't drift from the guide. |
| | (b) Execute the documented commands (V10.7). |
| | (c) In VS Code (or by a scripted LSP request): hover `use: webhook` and complete options in a `tool` block. |
| | (d) Read the new guide section start to finish as a user: build the digest from it alone. |
| | (e) Grep the READMEs, `LOOM_PROMPT.md` and the gap analysis for the six kinds. |
| Evidence | `evidence/G7-docs.md`: the block-compare output, command outputs, an LSP transcript or screenshot, and the notes from (d). |
| Pass | No guide block is missing from the examples file; the commands behave as documented; completion and hover work; (d) succeeded without reading source; all six kinds are named in each document. Anything in the guide that the code doesn't do (or the reverse) is a defect, fixed before sign-off. |

## G8 Quality of the evidence itself

| | |
|---|---|
| Do | Count tests per layer and per tool from the surefire reports. Review the skipped list. Review the coverage reports. |
| Evidence | `evidence/G8-coverage.txt` with the table below filled in. |
| Pass | Every row meets its target. |

| Measure | Target |
|---|---|
| Lines, `tools.generic` (JaCoCo) | ≥ 80% |
| Branches, each of `NetPolicy`, `SqlGuard`, `PathMatcher`, `Redactor`, `EffectTool`, the path/`permit` code, the address validator | ≥ 90% |
| Hostile attacks recorded | every row of strategy §6 |
| Tests per tool (L1 and L2) | each of the six has at least one test at each of L0, L1 and L2 |
| Skipped tests without an allowed reason | 0 |

## G9 Safety review

| | |
|---|---|
| Do | (a) Run the hostile-model suite (V12) and read its attack list: add any attack that comes to mind that isn't in it, and run it. |
| | (b) **Secrets sweep.** Run R1–R3 with a recognisable fake secret in every secret option (`SECRETSECRET1234`). Then `grep -r SECRETSECRET1234` over: the run journal directory, the audit log, the trace output (`--trace=json`), the outbox, the report files, the JVM's stdout/stderr, and the surefire reports. |
| | (c) Run `/security-review` over the diff from the baseline SHA. |
| | (d) Read the six kinds' `check` and `create` code once with this question: "what does a hostile model do with each argument?". |
| Evidence | `evidence/G9-safety.md`: the extra attacks and results, the sweep output (empty), the security-review findings and what was done about each, notes from (d). |
| Pass | No hostile attack has an effect. The sweep finds nothing. Every finding from (c) is fixed or has a written reason in the evidence. |

## G10 Live checks (optional)

Run L1–L4 from `verification.md` if accounts are available. **Pass:** each passes, or the evidence file
states plainly "not run: <reason>" and the guide's `teams` note says the shape is unverified. This gate
doesn't block sign-off, but the absence is a recorded known gap.

---

## Sign-off

Completed by the verifier after the last gate, in `evidence/SIGN-OFF.md`:

| Gate | Evidence | SHA | Result | Date |
|---|---|---|---|---|
| G0 Preconditions | G0-preconditions.txt | | | |
| G1 Clean build, twice | G1-build.txt | | | |
| G2 Traceability | G2-traceability.txt | | | |
| G3 Sabotage | G3-sabotage.md | | | |
| G4 Regression | G4-regression.txt | | | |
| G5 Real runs | G5-runs.md | | | |
| G6 Packaged app | G6-package.txt | | | |
| G7 Docs and tooling | G7-docs.md | | | |
| G8 Coverage and evidence | G8-coverage.txt | | | |
| G9 Safety review | G9-safety.md | | | |
| G10 Live (optional) | | | | |

**Per-tool completion.** A tool is complete when all of the following hold, and each row is ticked
separately so a partial delivery is visible:

| | `webhook` | `email` | `http` | `file` | `shell` | `sql` |
|---|---|---|---|---|---|---|
| Its V-group passes (V4 / V5 / V6 / V7 / V8 / V9) | | | | | | |
| Its hostile attacks (H1 / H3 / H2 / H4 / H5 / H6) | | | | | | |
| Its sabotage rows fail as expected (S-rows) | | | | | | |
| Real run by hand (R3) | | | | | | |
| Runs from the packaged JAR (G6) | | | | | | |
| Documented, with an example that parses (G7) | | | | | | |
| Secrets sweep clean (G9b) | | | | | | |
| Effect-journal replay verified (V2) *(not for `sql`)* | | | | | | n/a |

**The work is complete when** G0–G9 pass at one SHA, the sign-off table is filled in, every per-tool row
is ticked for the tools being delivered, and `verification.md`'s Results section points at the evidence
folder. If only some tools ship, the sign-off says which; the others stay unticked and their tasks stay
open. The shared foundations (V1–V3, G1–G4, G9) must pass in either case.

## Re-verification

Any later change under `tools/generic`, `EffectTool`, `ToolFactory`, the parser's tool-block code, or
`ApprovalGate` re-runs G1, G2, G4 and the affected rows of G3 and G9 before it is merged. A change to the
guide or the sample re-runs G7. The verification script is wired into the Jenkins "Unit Tests & Coverage"
stage for G1, G2 and G8, so those three never go stale.
