# Phase 16 verification plan: the first run

Status: **16a to 16c done** (`weave try`; `weave doctor`; launchers, JBang); 16d, 16e not started. Run top to bottom at the end of sub-phase 16e; the relevant rows after each sub-phase. Results in §7.

## 1. Acceptance criteria

| # | Criterion | Evidence |
|---|---|---|
| A1 | With only Java installed, one command shows a real workflow run, narrated, with no key, no account and no network | `TryRunsRealInterpreterTest`, `TryNoNetworkTest` |
| A2 | The demo is honest: it runs the real interpreter, says the models are scripted, and its narration matches what the interpreter did | `NarratorMatchesEventsTest`, `NoProviderClassLoadedTest`, `TrySummaryTest` |
| A3 | `try` leaves nothing behind unless asked, exits cleanly on interrupt or closed pipe, and finishes the default tour in under 5 s (CPU) | `TryNoResidueTest`, `TryInterruptTest`, `TrySpeedTest` |
| A4 | `try --keep` leaves a project that checks, reviews without HIGH findings, and passes its own tests | `TryKeepProjectPassesTest` |
| A5 | `weave doctor` tells me what is wrong with my setup, in one sentence with one fix each, without revealing any secret | `DoctorChecksTest`, `DoctorMessageRuleTest`, `DoctorNoSecretsTest` |
| A6 | `doctor` is local unless I ask for `--network`, and then only connects, with the hosts listed first | `DoctorNetworkOptInTest`, `DoctorTlsConnectOnlyTest` |
| A7 | I can install with one line on any machine with Java (JBang) or three lines (direct), and the launcher gives a clear message for old or missing Java | `LauncherTest`, `JbangCatalogTest` |
| A8 | CLI, jar and extension versions are coherent and mismatches are reported | `VersionCoherenceTest`, `extensionVersionWarn.test.ts` |
| A9 | `weave init` with no arguments guides me (generate, template or demo) and does not change scripted behaviour | `InitInteractiveTest`, `InitNonInteractiveUnchangedTest` |
| A10 | Every template passes check, review and its own tests | `TemplatesPassGatesTest` |
| A11 | `weave next` and each command's closing hint agree, and always give one primary next step | `NextOrderTest`, `NextAgreesWithHintsTest` |
| A12 | The documented newcomer path works verbatim in at most six commands within the time budget, and the docs are executed by the test | `NewcomerJourneyTest`, `GettingStartedDocsTest` |
| A13 | The editor offers the same path from a welcome view | `welcomeView.test.ts` |
| A14 | No telemetry, no account, no network by default | `TryNoNetworkTest`, `DoctorNetworkOptInTest`, `noNetwork.test.ts` |
| A15 | Time to first success measured on a clean machine: under 2 minutes wall time via JBang (network-bound) | manual §4, recorded in §7 |
| A16 | Existing init, next, check, templates and the contributor `doctor.sh` are unchanged | existing tests, unmodified |

## 2. Traceability: requirement → tests

| Req | Tests | Status |
|---|---|---|
| R1 | `TryCommandTest.theDefaultScenarioIsNarratedFromTheRealInterpreter`, `...keepLeavesAProjectWhoseTestsPass...` | ✅ 16a |
| R2 | `TryCommandTest` (each sentence carries values from the interpreter's own decision events and journal) | ✅ 16a |
| R3 | `TryCommandTest.theDefaultScenario...` (path, result, cost $0, review line, three next commands) | ✅ 16a |
| R4 | `TryCommandTest.keepLeavesAProjectWhoseTestsPassAndRefusesToOverwrite` (both scenarios' suites pass) | ✅ 16a |
| R5 | `TryCommandTest` (the env refuses to build any model client; `try` is entirely scripted) | ✅ 16a |
| R6 | `TryCommandTest.nothingIsWrittenUnlessKeepIsGiven...` | ✅ 16a |
| R7 | `TryCommandTest.theDefaultScenario...` (the line opens and closes the run) | ✅ 16a |
| R8 | no colour is ever written, and `--step`/pacing only on a terminal; a Ctrl+C message and closed-pipe handling are **not** built (the temp folder is removed in a `finally`) | 🟦 16a partial |
| R9 | `TryCommandTest` (whole class, 7 runs: 1.5 s) | ✅ 16a |
| R10 | `TryLiveCapTest`, `TryLiveMissingKeyTest` | ⬜ 16e |
| R11 | `TryLiveOnlyNetworkPathTest` | ⬜ 16e |
| R12 | `DoctorCommandTest` (the verdict line; exit 0 ready, 1 warnings only, 2 not ready; `--format json` version 1) | ✅ 16b |
| R13 | `DoctorCommandTest.aMissingKeyIsNamedWithTheOneActionAndNeverItsValue` (a seeded secret never appears: not the value, a prefix or its length) | ✅ 16b |
| R14 | `DoctorCommandTest.nothingConnectsUnlessAskedAndThenOnlyToTheScriptsHosts` (hosts printed first; a proxy URL with a password is never printed) | ✅ 16b |
| R15 | `DoctorCommandTest.fixAsksFirstAndOnlyDoesTheSafeLocalThings` | ✅ 16b |
| R16 | `DoctorCommandTest.everyProblemIsOneSentenceAndOneAction` (doctor); try's own errors name the choices | ✅ 16b |
| R17 | `scripts/doctor.sh` is untouched (git shows no change); the CONTRIBUTING link is written with the end-of-program docs | ✅ 16b (docs owed) |
| R18 | `LauncherTest.theJbangAliasPointsAtTheCliJarOfThisVersion` (alias `weave` -> the `cli` jar of the pom's version) | ✅ 16c |
| R19 | `LauncherTest` (arguments untouched, `JAVA_OPTS`, `JAVA_HOME` first, old/missing Java and missing jar each say what to do), `...theChecksumFileVerifiesTheJar` | ✅ 16c |
| R20 | `LauncherTest.theVersionLineCarriesTheProtocolOfServe` (`weave --version` prints the version and the serve protocol); the extension's warning is 16e | 🟦 16c/16e |
| R21 | `LauncherTest` (execution with a fake java; shellcheck when installed, skipped here; the Windows launcher is checked as text, not run) | ✅ 16c |
| R22 | `install/README.md` names the follow-ups (Homebrew, SDKMAN, Scoop) | ✅ 16c |
| R23 | `InitInteractiveTest`, `InitNonInteractiveUnchangedTest` | ⬜ 16d |
| R24 | `InitNoKeyGuidanceTest` | ⬜ 16d |
| R25 | `TemplatesPassGatesTest` | ⬜ 16d |
| R26 | `NextOrderTest` | ⬜ 16d |
| R27 | `NextAgreesWithHintsTest` | ⬜ 16d |
| R28 | `welcomeView.test.ts` | ⬜ 16e |
| R29 | manual §4 (measured), CPU budget in `NewcomerJourneyTest` | ⬜ 16e |
| R30 | `NewcomerJourneyTest` | ⬜ 16e |
| R31 | `GettingStartedDocsTest` | ⬜ 16e |
| R32 | `TryCommandTest` (the env refuses model clients); no provider is constructed | ✅ 16a |
| R33 | `TryCommandTest.listNamesEveryScenario...` (scenarios are `try/index.txt` and a folder) | ✅ 16a |

## 3. Quality gates

- All existing tests pass unmodified.
- Line coverage ≥ 90 % in `try/` and `doctor/`; mutation score ≥ 70 % on message formatting and the secret-safety paths.
- The newcomer journey runs in CI on Linux with an empty network namespace and a read-only `HOME`.
- `shellcheck` clean launcher; `scripts/verify-first-run.sh` (new) runs the journey and the launchers.
- Jar size increase recorded and within the budget agreed (DESIGN §11.4).

## 4. Manual checks

1. On a clean VM with only a JDK: install via JBang and run `try`; time it; note every friction point.
2. Same on Windows and macOS (launcher and colours).
3. Hand `weave try` to someone who has never seen Loom; do not explain; ask what they think Loom is afterwards and what they would do next. Record verbatim.
4. Run `doctor` in six broken environments (old Java, no key, bad proxy, read-only store, stale prices, mismatched jars) and judge whether each message alone would fix the problem.
5. Run `weave init` in an empty directory, choose each option once.
6. `weave try --live` with a real key and the default cap; confirm the cap and estimate.

## 5. Documentation owed

"Getting started" page rewritten around `try` (checked by `GettingStartedDocsTest`); install page (JBang and direct); troubleshooting page generated from `doctor`'s messages; README top section (once, at the end of the program); extension README and walkthrough; follow-up checklist for package managers; `llms.txt`.

## 6. Exit checklist

- [ ] Every row in §2 ✅ and every A-criterion has evidence
- [ ] Newcomer journey green in CI; manual newcomer feedback recorded in §7
- [ ] Docs in §5 updated; roadmap status updated
- [ ] DESIGN §11 questions answered and recorded

## 7. Results

*(per sub-phase: time-to-first-success measurements, doctor message review, jar size, newcomer feedback)*

### 16a (2026-10-24)

- Built: `weave try [scenario] [--list] [--step] [--fast] [--keep DIR] [--no-color]`, `demo/` (`Scenarios` from `try/index.txt` and a folder per scenario, `Narrator`), two scenarios (`triage`, `flaky`) shipped in the jar as real projects whose `tests/main.test.yaml` is the demo script *and* the test suite. The run is the real interpreter on scripted collaborators (`CaseProbe`), replayed (`ObserveReplay`) for the decision events; each step gets one sentence made from the statement kind, the decision it took and the journal entry. Ends with the path, the result, "this demo cost $0", the review line and three next commands; the scripted-models line opens and closes it.
- Tests: `TryCommandTest` 7.
- Deviations: the plan's `try/` package is `demo/` (`try` is a Java keyword); the graph is a one-line path of the steps taken, not a drawing of the whole graph; the summary's cost line says $0 for the demo and does not yet print an estimate for a real run; `--fabric` and `--live` come in 16e; the third and fourth scenarios (`newsletter`, `refund`) in 16d.

### 16b (2026-10-24)

- Built (`doctor/`, `weave doctor [script] [--network] [--fix] [--yes] [--format text|json]`): checks for Java (version, `JAVA_HOME` against the Java running), CLI version, the project (`weave check` result), keys (names of the variables the script needs from its models and tools; set or not, never a value), models (a name no known provider serves, with the nearest known name), network (opt-in; a TLS connect to each provider host the script uses, no request, no key; the hosts are printed first; a proxy is mentioned, never shown), the run store (writable, 50 MB free), prices (older than 90 days is a warning), Fabric (`LOOM_FABRIC_URL`: plain http to another machine is a warning), and `.env.example` holding what looks like a real value. `--fix` lists the safe local fixes and asks (`--yes` agrees): create the store folder, add `.env` to `.gitignore`, copy `.env.example` to `.env`.
- `DeployPlan` gained `unknownModels` (used by doctor), found because `weave check --no-env` does not flag an unknown model name: a typo only shows up when the script runs.
- Tests: `DoctorCommandTest` 11.
- Deviations: the extension check is not built (it needs the editor: 16e); the CLI check reports the version only (the bundled-jar comparison needs the extension); the CPU/exit codes are 0, 1, 2 for ready, ready with warnings, not ready.

### 16c (2026-10-24)

- Built: `install/weave` and `install/weave.cmd` (find Java via `JAVA_HOME` then `PATH`, refuse Java older than 17 with a way out, pass every argument through untouched, honour `JAVA_OPTS` and `WEAVE_JAR`), `install/README.md` (the three-line install), `jbang-catalog.json` (alias `weave` for `jbang weave@srijithunni7182/llm4j try`), `scripts/make-release-launchers.sh` (the jar, the launchers and `weave.jar.sha256`), and `weave --version` now also prints the serve protocol.
- Tests: `LauncherTest` 9 (1 skipped: shellcheck is not installed here).
- Not verified: the JBang alias against a real published artifact (nothing is published from here), and the Windows launcher by running it.
