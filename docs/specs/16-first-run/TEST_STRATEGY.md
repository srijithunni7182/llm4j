# Phase 16 test strategy: the first run

Status: **draft for review**. Requirements `R1`…`R33` are in [DESIGN.md](DESIGN.md); the traceability matrix is in [VERIFICATION.md](VERIFICATION.md).

## 1. Principles

- **The newcomer is the test.** The most important test in this phase is a scripted newcomer who has Java and nothing else: it runs the documented path from a clean directory, with no keys, no network, a read-only `HOME`, and fails if any step fails, prints no next step, takes too many commands, or exceeds the time budget.
- **The docs are executable.** The "Getting started" page's commands are extracted and run by that test (the repo's existing `ContextDocsExamplesTest` approach), so the first thing a user reads cannot drift.
- **Demos are real.** `try` runs the real interpreter with the phase 11 collaborators; the tests assert that, and that every scenario's suite passes, so the tour cannot lie about what Loom does.
- **Offline by construction.** No test in this phase touches the network except the explicit, local-loopback tests for `doctor --network` (against a local TLS test server) and `try --live` (a fake model server).
- **Secrets never appear.** `doctor` and `try` outputs are grepped for every seeded secret, prefix and length marker.
- **Messages are specified.** Failure texts are tested by rule (one sentence + one action) and by golden file for the common ones.
- **Old behaviour is pinned.** `weave init` (non-interactive), `weave next`, `weave check`, templates and `scripts/doctor.sh` behave as before except where this phase extends them; golden outputs for non-interactive modes are unchanged.

## 2. Levels

| Level | What | Examples | Requirements |
|---|---|---|---|
| **Scenarios** | Each scenario is a valid project; its suite passes under `weave test`; resources load from the jar; `--list` text; `--keep` writes an editable project that checks, reviews (no HIGH) and tests green | `ScenarioSuitesPassTest`, `ScenariosAreValidProjectsTest`, `TryKeepProjectPassesTest` | R1, R4 |
| **Real interpreter** | `try` goes through `HarnessExecutor` with scripted collaborators (a spy proves the interpreter ran every statement); no provider class is loaded without `--live` | `TryRunsRealInterpreterTest`, `NoProviderClassLoadedTest` | R1, R32 |
| **Narrator** | One sentence per statement kind from the decision events; accurate against the run (condition and values shown equal the event); no sentence without a matching event; width/NO_COLOR/pipe behaviours | `NarratorTest`, `NarratorMatchesEventsTest`, `NarratorPlainOutputTest` | R2, R8, R33 |
| **Summary** | Findings card, "this demo cost $0", graph (ASCII) present, three scenario-specific next commands exist and run | `TrySummaryTest`, `NextCommandsExistTest` | R3 |
| **Guarantees** | No network (empty network namespace), no env reads beyond display, no files unless `--keep`, temp removed on interrupt, clean `Ctrl+C`, closed pipe | `TryNoNetworkTest`, `TryNoResidueTest`, `TryInterruptTest`, `TryClosedPipeTest` | R5 to R8 |
| **Speed** | Default scenario under 5 s from process start (tagged performance test); run under 2 s | `TrySpeedTest` | R9 |
| **Live** | Cap default; estimate and confirmation; missing key → doctor guidance; the only path touching network | `TryLiveCapTest`, `TryLiveMissingKeyTest` | R10, R11 |
| **Doctor checks** | Every check ok/warn/fail/skipped with fixtures (old Java via fake, mismatched jars, missing keys, unwritable store, stale prices, absent script); verdict line; exit codes; JSON schema | `DoctorChecksTest`, `DoctorVerdictTest`, `DoctorJsonSchemaTest` | R12 |
| **Doctor secrets** | Seeded env secrets never printed in any form (value, prefix, length) in any format | `DoctorNoSecretsTest` | R13 |
| **Doctor network** | Off by default; `--network` prints hosts first; TLS connect to a local test server; no request body; proxy hints | `DoctorNetworkOptInTest`, `DoctorTlsConnectOnlyTest`, `DoctorProxyHintsTest` | R14 |
| **Doctor fix** | Asks first; only safe local changes; never touches profiles or installs | `DoctorFixAsksFirstTest`, `DoctorFixScopeTest` | R15 |
| **Message rule** | Every failure message is one sentence plus one action | `DoctorMessageRuleTest`, `TryMessageRuleTest` | R16 |
| **Install** | Launcher passes arguments and `JAVA_OPTS`, finds Java, refuses old Java with the clear message, exec's; shellcheck; Windows `.cmd` (on Windows hosts); JBang catalog is valid JSON and references real coordinates; checksum verified | `LauncherTest`, `ShellcheckTest`, `LauncherWindowsTest`, `JbangCatalogTest`, `ChecksumTest` | R18, R19, R21 |
| **Version coherence** | `--version` prints CLI, jar and protocol versions; extension warns on major mismatch (fake) | `VersionCoherenceTest`, `extensionVersionWarn.test.ts` | R20 |
| **Init** | Interactive flow with scripted stdin; non-terminal behaviour unchanged (golden); no key → option 1 explains; templates pass check/review/test | `InitInteractiveTest`, `InitNonInteractiveUnchangedTest`, `TemplatesPassGatesTest` | R23 to R25 |
| **Next / hints** | Ordering with phases 9 to 14 stages; one primary command; every command's last-line hint equals `weave next`'s | `NextOrderTest`, `NextAgreesWithHintsTest` | R26, R27 |
| **Editor welcome** | Manifest `viewsWelcome` content, command ids exist, buttons run the intended commands (fake) | `welcomeView.test.ts` | R28 |
| **Newcomer journey** | The documented path end to end, ≤ 6 commands, exit codes, CPU budget, hints present, docs-extracted commands executed | `NewcomerJourneyTest`, `GettingStartedDocsTest` | R29 to R31 |
| **Architecture** | Narrator/scenarios data-driven (adding a scenario folder needs no code change: a test adds a temporary scenario) | `ScenarioIsDataTest` | R33 |

## 3. Fixtures

- `src/main/resources/try/<scenario>/` project + `demo.test.yaml` (these are production resources, tested as fixtures).
- `src/test/resources/doctor/` fake environments: old Java report, mismatched jar versions, unwritable store, stale prices, secrets set.
- A local TLS test server (self-signed, trusted in-test) for `doctor --network`; a fake model HTTP server for `try --live`.
- A sandbox runner for the newcomer test: empty network namespace where the platform supports it (Linux CI), read-only `HOME`, a clean working directory.

## 4. Fault injection

`JAVA_HOME` pointing at nothing; two Java versions on PATH; terminal width 20 and 400; output piped to `head -1`; `NO_COLOR` set; a non-UTF-8 locale; the temp directory unwritable; `--keep` into an existing non-empty directory; a scenario resource missing from the jar (clear internal error, not a stack trace); a store lock left by a dead process; a proxy variable with credentials in it (never printed); the model catalog absent.

## 5. What is deliberately not tested in the build

- The JBang download path on a real network (manual, measured in VERIFICATION §7).
- Third-party package-manager installation.
- Terminal emulators' rendering beyond plain text and the colour/no-colour switch.
- Subjective tone of narration beyond the rules in DESIGN §8.
