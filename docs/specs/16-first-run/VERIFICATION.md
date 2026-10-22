# Phase 16 verification plan: the first run

Status: **not started**. Run top to bottom at the end of sub-phase 16e; the relevant rows after each sub-phase. Results in §7.

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
| R1 | `TryRunsRealInterpreterTest`, `ScenariosAreValidProjectsTest` | ⬜ 16a |
| R2 | `NarratorTest`, `NarratorMatchesEventsTest` | ⬜ 16a |
| R3 | `TrySummaryTest`, `NextCommandsExistTest` | ⬜ 16a |
| R4 | `ScenarioSuitesPassTest`, `TryKeepProjectPassesTest` | ⬜ 16a |
| R5 | `TryNoNetworkTest` | ⬜ 16a |
| R6 | `TryNoResidueTest` | ⬜ 16a |
| R7 | `TryStatesScriptedModelsTest` | ⬜ 16a |
| R8 | `TryInterruptTest`, `TryClosedPipeTest`, `NarratorPlainOutputTest` | ⬜ 16a |
| R9 | `TrySpeedTest` | ⬜ 16a |
| R10 | `TryLiveCapTest`, `TryLiveMissingKeyTest` | ⬜ 16e |
| R11 | `TryLiveOnlyNetworkPathTest` | ⬜ 16e |
| R12 | `DoctorChecksTest`, `DoctorVerdictTest`, `DoctorJsonSchemaTest` | ⬜ 16b |
| R13 | `DoctorNoSecretsTest` | ⬜ 16b |
| R14 | `DoctorNetworkOptInTest`, `DoctorTlsConnectOnlyTest`, `DoctorProxyHintsTest` | ⬜ 16b |
| R15 | `DoctorFixAsksFirstTest`, `DoctorFixScopeTest` | ⬜ 16b |
| R16 | `DoctorMessageRuleTest`, `TryMessageRuleTest` | ⬜ 16b |
| R17 | `ContributorDoctorUnchangedTest` | ⬜ 16b |
| R18 | `JbangCatalogTest` | ⬜ 16c |
| R19 | `LauncherTest`, `ChecksumTest` | ⬜ 16c |
| R20 | `VersionCoherenceTest`, `extensionVersionWarn.test.ts` | ⬜ 16c |
| R21 | `ShellcheckTest`, `LauncherWindowsTest` | ⬜ 16c |
| R22 | docs checklist (follow-ups recorded) | ⬜ 16c |
| R23 | `InitInteractiveTest`, `InitNonInteractiveUnchangedTest` | ⬜ 16d |
| R24 | `InitNoKeyGuidanceTest` | ⬜ 16d |
| R25 | `TemplatesPassGatesTest` | ⬜ 16d |
| R26 | `NextOrderTest` | ⬜ 16d |
| R27 | `NextAgreesWithHintsTest` | ⬜ 16d |
| R28 | `welcomeView.test.ts` | ⬜ 16e |
| R29 | manual §4 (measured), CPU budget in `NewcomerJourneyTest` | ⬜ 16e |
| R30 | `NewcomerJourneyTest` | ⬜ 16e |
| R31 | `GettingStartedDocsTest` | ⬜ 16e |
| R32 | `NoProviderClassLoadedTest` | ⬜ 16a |
| R33 | `ScenarioIsDataTest` | ⬜ 16a |

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
