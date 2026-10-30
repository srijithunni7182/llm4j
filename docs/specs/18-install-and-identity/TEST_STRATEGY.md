# Phase 18 test strategy: install, identity and environment

How each requirement of [DESIGN.md](DESIGN.md) is tested, under the rules of [TESTING_STRATEGY.md](../../TESTING_STRATEGY.md): hermetic by default; anything that needs the network or a key is tagged and opt-in.

## 1. The principle: build the mess, then look at it

These findings are about **combinations**: two jars with one name, a skill folder missing its jars, a project with a secret in the wrong file. Unit tests of one class do not meet them. The main device of this phase is a **fixture builder** that
creates the messy situation in a temporary folder, and runs the real command against it.

## 2. Test levels

| Level | What it covers |
|---|---|
| **Unit** | Version and manifest parsing (R1.2), the identity comparison (R2.1), the lookup order (R3.1), the secret-name rule (R8.1), the catalogue reader and its age check (R7.1), the model chooser (R7.2) |
| **Command** | `weave --version`, `doctor`, `check`, `init`, `guide --install-skill`, `tools fetch`, `secrets import-env` through `WeaveCLI.execute(...)` with an injected environment (`Env`), file system (a temporary folder) and HTTP client |
| **Script** | The skills' POSIX launcher and `lib.sh`, run by `ProcessBuilder` against a temporary skill folder with fake `weave`, Fabric and inspect jars (small stub jars built in the test); the PowerShell versions are linted and run on the Windows CI leg only (`@EnabledOnOs`) |
| **Build** | The release check's marker scan runs on a jar the test builds with a planted stub class |
| **Extension** | The notification of R5.1 with the VS Code API faked (`node --test`, the existing pattern) |
| **Live** (opt-in) | `check --online` against one real provider (a free `list models` call), `prices update` against the real repository; both tagged `live` |

## 3. Fixtures

- **`JarFixture`**: writes a small jar with a chosen `Implementation-Version`, `Build-Commit`, `Build-Time` and `Build-Kind` and, where needed, a marker class. Used for R1, R2 and R6.
- **`SkillFolderFixture`**: a skill folder with `scripts/` copied from the repository and `bin/` empty, partly filled or full, plus a `PATH` and `HOME` the test controls (so the extension folder, `~/.m2` and `~/.vscode` are all inside the temporary directory).
- **`ProviderModelsFixture`**: a `MockWebServer` that answers a provider's "list models" call (and a 404 for a model), for R7.3.
- **`CentralFixture`**: a `MockWebServer` standing in for Maven Central, serving a jar and its checksum (and a wrong checksum), for R3.3.
- **`ProjectFixture`**: a project folder with a `.env.example` holding a **fake** key (`test-key-000`, per the repository rule: obviously fake values only), for R8.

## 4. Per requirement

| Requirement | Tests (written first) |
|---|---|
| **R1.1, R1.2** | `VersionLineTest` (release and development, dirty tree, the protocol suffix kept); `EveryModuleJarCarriesBuildIdentityTest` (builds the release check's jars and reads each manifest) |
| **R1.3** | `ReleasedVersionSourceOfTruthTest`: the catalogue must equal the released-version property; fails when they differ; passes with a `-SNAPSHOT` pom; `DocsNameTheReleasedVersionTest` (existing docs tests, adapted) |
| **R1.4** | the issue template contains the request (a docs test) |
| **R2.1** | `DoctorJarsTest`: no finding with one jar; one finding for two jars of one version and different commits; the sentence names both and the fix; no finding for two builds of different versions; the extension folder found under a controlled `HOME` |
| **R2.2** | `InspectRefusesMismatchedCoreTest`, `FabricRefusesMismatchedCoreTest`: a core jar with a different commit on the class path produces the one-sentence refusal and no `NoClassDefFoundError`; guard test: removing the check makes them fail |
| **R2.3** | `DoctorJarsNoNetworkTest` (no socket is opened; the existing `NoNetwork` pattern) |
| **R3.1** | `ToolLookupOrderTest` (shell and PowerShell): each rung of the order wins over the next; a missing everything gives the named message |
| **R3.2** | `InstallSkillSummaryTest`: the table says missing where the fixture lacks a jar, and never "ready" when a script cannot run |
| **R3.3** | `ToolsFetchTest`: asks before downloading; a wrong checksum is refused and nothing is left behind; a development build is refused; the path and variable are printed; nothing is written outside `~/.llm4j/tools`; `ToolsFetchNeverExecutesTest` |
| **R4.1** | `SkillLauncherTest`: finds each rung; prints the install line when none; does not recurse into itself |
| **R4.2, R4.3** | `DoctorExplainsPlainTerminalTest`; docs tests for the README lines |
| **R5.1** | `agentSetupNotification.test.ts`: shown once per workspace, remembered, *Don't ask again* global, no file written without a choice |
| **R6.1, R6.3** | `ReleaseCheckRejectsEclipseStubsTest` (a jar with the marker fails and names the class), `verifyVsixRejectsStubsTest` |
| **R6.2** | `doctor.sh` test with a planted marker; docs test for `CONTRIBUTING.md` |
| **R7.1, R7.2** | `CatalogueTest` (every model a template or example names is in the catalogue with window, output limit and price; the date is present; age warning at 90 days with an injected clock), `InitPicksDefaultPerKeyTest` |
| **R7.3** | `CheckOnlineTest`: a model the fixture says is unavailable is reported with alternatives; no key in any output; offline `check` unchanged (no socket) |
| **R7.4** | `PricesUpdateTest`: checksum verified, newer preferred, offline falls back to shipped |
| **R7.5** | `CostLineSaysWhereThePriceCameFromTest` |
| **R8.1** | `EnvExampleSecretWarningTest`: warns for a non-empty secret-like value, not for an empty one, not for a non-secret name; `NoSecretValueInOutputTest` (the value, its first characters and its length are absent from stdout, stderr, logs and exception messages; table-driven over the commands) |
| **R8.2, R8.3** | `InitPrintsSecretStoreFirstTest`, `GeneratedEnvExampleIsEmptyTest`, `ImportEnvOffersToBlankExampleTest` |

## 5. Fault injection

A jar with no manifest; a manifest with a missing `Build-Commit`; a download cut off half way; a checksum file that is not a checksum; a "list models" answer that is not JSON; a catalogue with a bad date; a `HOME` that cannot be read. Each ends in a message that names the file or host, never in a stack trace and never in a silent pass.

## 6. Mutation testing

The identity comparison (R2.1), the lookup order (R3.1), the checksum check (R3.3) and the secret-name rule (R8.1) join the Loom `mutation` profile. Surviving mutants are killed with assertions, not excluded. Each guard is removed once by hand (R2.2, R3.3's checksum, R6.1's scan, R8.1's silence about the value) and the failing test is named in `VERIFICATION.md` §7.

## 7. Deliberately not tested

- That Maven Central is up, or that its checksums are correct (the fixture stands in; the live test is opt-in).
- That a particular editor's language server leaves stubs (R6 tests the build's refusal of the marker, not the editor).
- Operating systems the CI does not run (macOS shell differences are covered by running the POSIX script test on the macOS leg if the repository adds one; until then the script is kept to POSIX `sh` features and linted).
