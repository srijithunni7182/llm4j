# Phase 18 verification plan: install, identity and environment

Status: **not started** (specs in draft). Run §0 first, then top to bottom at the end of each sub-phase (proposed: **18a** identity and snapshot policy, R1; **18b** doctor and add-on checks, R2, R6; **18c** skill install and tools, R3, R4, R5; **18d** models, prices and secrets, R7, R8). Results in §7.
Findings: F10 to F19 of the [register](../FIELD_FINDINGS.md). Strategy: [TEST_STRATEGY.md](TEST_STRATEGY.md).

## 0. Reproduce first

| # | Finding | Procedure | If the finding is real, you see | Result |
|---|---|---|---|---|
| P1 | F10, F12 | Build `main` into a jar; `weave --version`. Compare with the released 6.0.0 jar from Maven Central (`ai-agent4j-loom-6.0.0-cli.jar`). | the same output line from two different jars | ⬜ |
| P2 | F10 | Put the released 6.0.0 jar in a temporary `~/.m2` layout and a development jar elsewhere; run `weave doctor`. | no mention of the disagreement | ⬜ |
| P3 | F11 | Run `weave observe` with an inspect add-on built from a different commit than the core on the class path. | a `NoClassDefFoundError` or similar, not a sentence | ⬜ |
| P4 | F13 | In an empty folder, `weave guide --install-skill --for claude`; then run the deployer skill's `fabric-up.sh`. | the script dies on a missing jar; the install said nothing about it | ⬜ |
| P5 | F14 | In a plain shell with no `weave` on `PATH`, follow the skill's text as a coding agent would. | no route to a working `weave` except building the extension's jar by hand | ⬜ |
| P6 | F16 | Plant a class file containing `Unresolved compilation problem` in a module's `target/classes`; run `mvn package` (no `clean`) and unzip the jar. | the marker class is in the jar | ⬜ |
| P7 | F17 | **Live, free:** a "list models" call with the owner's key for the model the templates default to. | the default model is not in the list for this key | ⬜ |
| P8 | F18 | Run a script using a Gemini model with `--prices` omitted; read the cost line and the window the budget used. | no window or price; the cost line gives no source for any price | ⬜ |
| P9 | F19 | A project whose `.env.example` holds a fake key `test-key-000`; run `weave check`, `weave doctor`, `weave init` in another folder. | no warning; `init` prints a `.env` step first | ⬜ |
| P10 | F15 | Open a folder with a `.loom` file in the extension (a fresh VS Code profile). | no offer to set up the coding agent | ⬜ |

## 1. Acceptance criteria

| # | Criterion | Evidence |
|---|---|---|
| A1 | `weave --version` and every jar's manifest say release or development, the commit and the build time; a bug report is enough to find the commit | `VersionLineTest`, `EveryModuleJarCarriesBuildIdentityTest`, P1 |
| A2 | Between releases `main` carries a snapshot version; the JBang catalogue is tied to the last released version and a test fails if they differ | `ReleasedVersionSourceOfTruthTest`, the release procedure rehearsed once (§4 M1) |
| A3 | `weave doctor` names two jars that share a version and differ in build, with the one-line fix, and says nothing when all agree | `DoctorJarsTest`, `DoctorJarsNoNetworkTest`, P2 |
| A4 | An add-on that does not match its core refuses with one sentence | `InspectRefusesMismatchedCoreTest`, `FabricRefusesMismatchedCoreTest`, P3 |
| A5 | The skill install is honest about what is missing; the lookup order is one and the same in shell and PowerShell | `InstallSkillSummaryTest`, `ToolLookupOrderTest`, P4 |
| A6 | `weave tools fetch` downloads only what is named, after asking, with a checked checksum, only for a release build, only under `~/.llm4j/tools` | `ToolsFetchTest`, `ToolsFetchNeverExecutesTest` |
| A7 | A coding agent in a plain terminal reaches a working `weave` or is told the one install line | `SkillLauncherTest`, `DoctorExplainsPlainTerminalTest`, P5 |
| A8 | The editor offers the coding-agent set-up once per workspace and writes nothing without a choice | `agentSetupNotification.test.ts`, P10, M3 |
| A9 | A jar containing half-compiled classes cannot be released or bundled | `ReleaseCheckRejectsEclipseStubsTest`, `verifyVsixRejectsStubsTest`, P6 |
| A10 | Every model a template names is in a dated catalogue with window, output limit and price; the age is shown; a key that cannot use a model is told so before a run | `CatalogueTest`, `InitPicksDefaultPerKeyTest`, `CheckOnlineTest`, `PricesUpdateTest`, `CostLineSaysWhereThePriceCameFromTest`, P7, P8 |
| A11 | A secret in a file meant to be committed is noticed without ever being shown; `init` shows the secret store first and generates an empty example | `EnvExampleSecretWarningTest`, `NoSecretValueInOutputTest`, `InitPrintsSecretStoreFirstTest`, `GeneratedEnvExampleIsEmptyTest`, P9 |
| A12 | Existing projects, scripts and the `bin/` route keep working; every change is in the release notes and `API_COMPATIBILITY.md` | existing tests unmodified; the table in [DESIGN §4](DESIGN.md) |
| A13 | Each guard is noticed when removed | PIT score not lower; by-hand removals named in §7 |

## 2. Traceability: requirement → tests

| Req | Tests | Status |
|---|---|---|
| R1.1, R1.2 | `VersionLineTest`, `EveryModuleJarCarriesBuildIdentityTest` | ⬜ |
| R1.3 | `ReleasedVersionSourceOfTruthTest`, `DocsNameTheReleasedVersionTest` | ⬜ |
| R1.4 | issue-template docs test | ⬜ |
| R2.1, R2.3 | `DoctorJarsTest`, `DoctorJarsNoNetworkTest` | ⬜ |
| R2.2 | `InspectRefusesMismatchedCoreTest`, `FabricRefusesMismatchedCoreTest` | ⬜ |
| R3.1, R3.2 | `ToolLookupOrderTest` (sh, ps1), `InstallSkillSummaryTest` | ⬜ |
| R3.3 | `ToolsFetchTest`, `ToolsFetchNeverExecutesTest` | ⬜ |
| R3.4 | skill docs tests | ⬜ |
| R4.1 to R4.3 | `SkillLauncherTest`, `DoctorExplainsPlainTerminalTest`, docs tests | ⬜ |
| R5.1, R5.2 | `agentSetupNotification.test.ts`, existing `welcomeView.test.ts` unchanged | ⬜ |
| R6.1 to R6.3 | `ReleaseCheckRejectsEclipseStubsTest`, `verifyVsixRejectsStubsTest`, `doctor.sh` test, docs test | ⬜ |
| R7.1, R7.2 | `CatalogueTest`, `InitPicksDefaultPerKeyTest` | ⬜ |
| R7.3 | `CheckOnlineTest` (fixture) and the live check (tagged) | ⬜ |
| R7.4, R7.5 | `PricesUpdateTest`, `CostLineSaysWhereThePriceCameFromTest` | ⬜ |
| R8.1 | `EnvExampleSecretWarningTest`, `NoSecretValueInOutputTest` | ⬜ |
| R8.2, R8.3 | `InitPrintsSecretStoreFirstTest`, `GeneratedEnvExampleIsEmptyTest`, `ImportEnvOffersToBlankExampleTest` | ⬜ |

## 3. Quality gates

- [ ] `mvn verify` green on every module touched (including `loom-inspect`, `loom-inspect-core`, `loom-fabric`, `ai-agent4j`, `ai-agent4j-loom`), coverage gates held, none lowered.
- [ ] PIT `mutation` profile: score not lower; R2.1, R3.1, R3.3 and R8.1 included.
- [ ] `make format-check` and `make release-check` green; the release check now includes R6.1.
- [ ] `npm test` unit subset of the extension green (R5.1).
- [ ] The skills' scripts pass `shellcheck` (POSIX) and PSScriptAnalyzer in CI, or the repository records why not.
- [ ] No new dependency, or its justification is in the pull request (`mvn dependency:tree`, licence, CVEs).
- [ ] `SecurityDocTest` green; `secure-by-construction.md` lists R3.3 and R8.1.

## 4. Manual checks

| # | Check | Pass |
|---|---|---|
| M1 | **Rehearse the release procedure with snapshots** on a throwaway branch: release version, tag locally, next snapshot, catalogue check; write the steps into `PUBLISHING.md` as run | ⬜ |
| M2 | On a clean machine with only Java: `jbang app install weave@…`, `weave doctor`, `weave guide --install-skill`, `weave tools fetch fabric` (a release version is needed, so after the next release), run the deployer's `fabric-up` | ⬜ |
| M3 | A fresh VS Code profile and a folder with a `.loom` file: the notification appears once, *Set up* shows the file list before writing, *Don't ask again* sticks | ⬜ |
| M4 | The owner's machine, where the two 6.0.0 jars exist: `weave doctor` finds them and the fix works | ⬜ |
| M5 | Live: `weave check --online` with the owner's Gemini key reports the unavailable default model and the alternatives | ⬜ |
| M6 | Read every new message aloud as a person: each says what is wrong and what to do, in one sentence | ⬜ |

## 5. Documentation owed

`PUBLISHING.md`, `CONTRIBUTING.md`, `install/README.md`, `docs/GETTING_STARTED.md`, `docs/API_COMPATIBILITY.md`, `docs/security/secure-by-construction.md`, generated command pages, the deployer and workflow skills, extension README and CHANGELOG, the issue template, `llms.txt`, release notes. Stack-level pages once at the end of the program.

## 6. Exit checklist

- [ ] §0 recorded with dates and build ids; findings that did not reproduce reported
- [ ] Every row in §2 ✅ or partial with a reason; every A-criterion has evidence
- [ ] M1 to M6 done and recorded in §7
- [ ] DESIGN §6 questions answered; decisions D5 and D6 recorded in the program page
- [ ] Docs in §5 updated; roadmap status updated

## 7. Results

### 18a and 18b-part (R1, R6), 2026-10-29

**Reproduced first:** P1 and F12: before the change `weave --version` printed `weave 6.1.0 (built <time>)` with no commit or kind, and `main` carried the released number, so a jar built from the repository was indistinguishable by number from the release. P6: `scripts/doctor.sh` on a planted `Unresolved compilation problem` class found nothing before; after, it names the file. (P2, P3, P4, P5, P7 to P10 are for 18b to 18d.)

Built: `BuildIdentity` (core) and the manifest attributes on every module jar (`git-commit-id-maven-plugin`, a build plugin only, no runtime dependency); `weave --version` shows kind, commit, dirty flag and time; main is `6.2.0-SNAPSHOT` with `llm4j.released` (6.1.0) as the one source of truth for the JBang catalogue, `weave init`'s generated test project and the deploy default; `scripts/release-version.sh` (rehearsed in a scratch clone: `release 6.2.0` leaves no snapshot, `next 6.2.1-SNAPSHOT` keeps `llm4j.released`, a `-SNAPSHOT` release is refused); `scripts/CheckJars.java` in `make release-check`, the CI release job and `verify-vsix.sh`; `doctor.sh` stub warning; CONTRIBUTING and PUBLISHING; issue template.
Tests first: `BuildIdentityTest` (6), `VersionLineTest` (3), `ReleasedVersionSourceOfTruthTest` (4), `ReleaseCheckRejectsEclipseStubsTest` (3, tagged fragile: starts a JVM), `IssueTemplateAsksForTheBuildTest`.
Guards removed by hand once: the stub marker in `CheckJars` (the stub test fails), `llm4j.released` changed to a different number (`ReleasedVersionSourceOfTruthTest` fails).
Verified: `mvn -B verify` on the whole reactor, all 13 modules BUILD SUCCESS (the Loom module alone 6 min 47 s); `make format-check` exit 0; `java scripts/CheckJars.java src` on the freshly built jars passes **after a correction**: the first version of the script also scanned the third-party copies in `target/lib` and failed on them, and leftover `6.1.0` jars from before the identity change failed it too. It now checks only the jars a build puts directly in `target/`, and `make release-check` now runs `clean verify` so a stale jar cannot be mistaken for a release jar. My first commit message for this sub-phase said the jar check passed; it had not been run on the real output yet.
**Not run:** the fragile test inside the default build (it runs with `-Pextended`; I ran it by hand and it passes), PIT, the `-Pextended` build, `make release-check` in full (its `CheckJars` step was run separately), the VS Code extension build.
Changed existing tests: `LauncherTest` (the version line format, and the catalogue test now asks for "a published version"; the exact tie moved to `ReleasedVersionSourceOfTruthTest`).
### 18b (R2), 2026-10-29

Built: `Doctor.jars` and `JarDiscovery` (the running jar, `~/.m2` copies, the editor extension's bundled jar, `WEAVE_JAR`; local files only; a jar it cannot read is skipped; silent when all agree), wired into `weave doctor`; `CoreMatch` in `loom-inspect-core` (JDK-only manifest comparison, so it still works against a core older than any class it could ask for), called by `InspectObserveProvider.open` and `FabricCLI.main`.
Tests first: `DoctorJarsTest` (5), `CoreMatchTest` (6). Guard removed by hand once: forcing `same = true` in `CoreMatch` fails 2 of its tests.
Verified: `mvn verify` on `ai-agent4j-loom` (3,544 tests, 0 failures, 3 skipped), `loom-inspect-core` (61), `loom-fabric` (215), `loom-inspect` (357), all BUILD SUCCESS, gates passed.
**Not reproduced end to end (P2, P3):** I did not put a real older jar and a development jar side by side or run `weave observe` against a deliberately mismatched add-on; the checks are proven on fake jars and manifests. The fabric check compares Fabric with the inspect core it bundles, not with a separately installed Loom core.

Note for the owner: every file name that carried `6.1.0` for a build output now carries `6.2.0-SNAPSHOT` (samples' `run.sh` were changed to find the jar by pattern). Nothing was tagged, published or pushed to a tag.


