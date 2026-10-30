# Phase 22 verification plan: CLI and language ergonomics

Status: **not started** (specs in draft). Run §0 first, then top to bottom at the end of each sub-phase (proposed: **22a** options, output and messages, R1 to R3; **22b** strings and substitution, R4, R5 (after decision D3); **22c** the harness and the review, R7, R9, R11; **22d** observe and Fabric, R8, R10; **R6** has no build here). Results in §7.
Findings: F43 to F54 of the [register](../FIELD_FINDINGS.md). Strategy: [TEST_STRATEGY.md](TEST_STRATEGY.md).

## 0. Reproduce first

| # | Finding | Procedure | If the finding is real, you see | Result |
|---|---|---|---|---|
| P1 | F43 | `weave test --help` and `weave run --help`; run `weave test` on a project whose tasks are in `target/classes`. | no `--classes` on `test`; the tasks are not found | ⬜ |
| P2 | F46 | `weave run` and `weave test --coverage` on any sample. | several `INFO: Loading Loom script` lines and a log header per case | ⬜ (code read 2026-10-29: `LoomLoader` logs at INFO) |
| P3 | F47 | A `delegate … retry 1 expecting {…}` script; `weave check`. | a parse error that does not name the order | ⬜ |
| P4 | F48 | A scripted test case whose task result map has the key `value`. | a reserved-word error with no alternative | ⬜ |
| P5 | F49 | A script with `"say \"hi\""`; `weave check`. | the string ends at the escaped quote and the rest is a parse error | ⬜ (code read 2026-10-29: `Lexer.string()` has no escape handling) |
| P6 | F50 | A script with a variable `out` and the string `"length-out-of-range(...)"` in a `note`, a `halt` and a `set`; `weave check` and `weave run --mock`. | the lint is silent for one or more positions and the path is printed into the message | ⬜ |
| P7 | F52 | A scripted workflow test of a script that calls a real task; try to run the task's real code. | no way to | ⬜ |
| P8 | F44 | `weave observe rules` without the add-on; then with an empty rules file. | a failure for both | ⬜ |
| P9 | F45 | `weave review` on a clean script with no key set. | five HIGH findings for the key | ⬜ |
| P10 | F53 | Follow the deployer skill from `fabric-up` to `run-observed` with a master key kept elsewhere; run `fabric-status`. | `<store>.key` required; no last-report time | ⬜ |
| P11 | F54 | `weave review` on the `article-flow` script with no price table and a 150,000-token cap. | typical and worst-case tokens, no dollars, nothing about the cap biting first | ⬜ |
| P12 | R7's premise | List the effect policy declared by each built-in and example task. | undeclared tasks exist (they would be refused) | ⬜ |

## 1. Acceptance criteria

| # | Criterion | Evidence |
|---|---|---|
| A1 | `weave test`, `run` and `eval` share the same class and loot options, from one definition; a Maven project's classes are found and the line says so | `OptionParityTest`, `TestUsesTargetClassesInAMavenProjectTest`, P1 |
| A2 | A command prints the answer, not the log; `-v` restores the log | `DefaultLogLevelIsWarningTest`, `VerboseRestoresInfoTest`, `ScriptedCaseHeaderOnlyOnFailureTest`, P2 |
| A3 | Known mistakes are answered with their fix, from a registry with golden tests | `KnownMistakesRegistryTest`, `ExpectingBeforeRetryHintTest`, `ReservedValueHintTest`, P3, P4 |
| A4 | A string can hold `\"` and `\\`; every script in the repository means what it meant | `StringEscapesTest`, `StringEscapeCorpusUnchangedTest`, P5 |
| A5 | The lint scans every position the runtime substitutes; `substitution: braces` replaces only `{name}`; a preview and a rewrite exist; the default is unchanged until the next major version | `LintCoversEverySubstitutedPositionTest`, `OutInsideAMessageIsReportedTest`, `SubstitutionBracesSettingTest`, `SubstitutionDefaultIsLegacyTest`, `CheckSubstitutionPreviewTest`, `FixSubstitutionShowsADiffAndNeedsAYesTest`, P6 |
| A6 | The current pattern for parallel fallbacks is documented and tested; no construct is added | `ParallelFallbackPatternDocsTest` |
| A7 | A workflow test can run an effect-free task's real code and refuses any task with effects (and any undeclared one) | `RealTaskRunsTheActualClassTest`, `RealTaskWithEffectsIsRefusedTest`, `UndeclaredTaskIsTreatedAsHavingEffectsTest`, P7, P12 |
| A8 | `weave observe rules` tells how to get the add-on, treats an empty file as no rules, and can write a starter | `ObserveRulesWithoutAddOnSaysHowTest`, `ObserveRulesEmptyFileMeansDefaultsTest`, `ObserveRulesInitWritesStarterTest`, P8 |
| A9 | A missing key is a setup finding, not a HIGH script finding, and does not count toward `--fail-on` or the panel's open count | `MissingKeyIsASetupFindingTest`, `SetupFindingsAreNotCountedByFailOnTest`, `ReviewPanelOpenCountExcludesSetupTest`, `AFindingMustBeClearableByEditingTheScriptTest`, P9 |
| A10 | `weave fabric up/down/status` exist, the password is never shown, the bind is loopback, `status` shows the last report per client, and the deployer skill delegates | `FabricUpGeneratesOwnerOnlyPasswordFileTest`, `FabricUpRefusesUnsafeBindTest`, `FabricStatusShowsLastReportPerClientTest`, `RunObservedSecretsKeyFileTest`, `DeployerScriptsDelegateToWeaveFabricTest`, P10 |
| A11 | The cost block states its assumptions, prints dollars when it can, says when the cap bites first, and uses measured usage when present | `CostBlockStatesAssumptionsTest`, `CostBlockPrintsDollarsWhenPriceKnownTest`, `CapBitesBeforeWorstCaseSentenceTest`, `EstimateUsesMeasuredUsageTest`, P11 |
| A12 | Existing scripts and options keep working; every change is in the release notes and `API_COMPATIBILITY.md` | existing tests unmodified; the table in [DESIGN §4](DESIGN.md) |
| A13 | Each guard is noticed when removed | PIT score not lower; by-hand removals named in §7 |

## 2. Traceability: requirement → tests

| Req | Tests | Status |
|---|---|---|
| R1 | `OptionParityTest`, `TestUsesTargetClassesInAMavenProjectTest`, `TestWithExplicitClassesTest` | ⬜ |
| R2 | `DefaultLogLevelIsWarningTest`, `VerboseRestoresInfoTest`, `ScriptedCaseHeaderOnlyOnFailureTest`, `LoaderLineIsFineTest`, `LibraryLoggingUnaffectedTest`, `UserLoggingConfigWinsTest` | ⬜ |
| R3 | `KnownMistakesRegistryTest`, `ExpectingBeforeRetryHintTest`, `ReservedValueHintTest`, `HintsNeverEchoPromptTextTest` | ⬜ |
| R4 | `StringEscapesTest`, `StringEscapeCorpusUnchangedTest`, `PromptFilePlaceholdersUnaffectedTest`, a CTK scenario | ⬜ |
| R5 | the A5 tests, a CTK scenario | ⬜ |
| R6 | `ParallelFallbackPatternDocsTest` | ⬜ |
| R7 | the A7 tests, `OtherTasksStayScriptedTest` | ⬜ |
| R8 | the A8 tests | ⬜ |
| R9 | the A9 tests, `IncludeSetupRestoresCountingTest` | ⬜ |
| R10 | the A10 tests, `FabricUpFindsTheJarByLookupOrderTest`, `FabricDownTest` | ⬜ |
| R11 | the A11 tests, `CostBlockNamesMissingPriceTest` | ⬜ |

## 3. Quality gates

- [ ] `mvn verify` green on `ai-agent4j-loom` and any module touched, coverage gates held, none lowered.
- [ ] PIT: the Loom `mutation` profile with R3, R4, R5, R7 and R9 added; score not lower.
- [ ] `RepositoryScriptsTest` green; the corpus lexes to identical strings except the listed ones; the CTK has scenarios for R4 and R5.
- [ ] For each Loom syntax or setting change (R4, R5): `LOOM_GUIDE.md` (its `loom` blocks are parse-tested), `LOOM_PROMPT.md`, `docs/guide/06-build-the-workflow.md`, the `llm4j-workflow-guide` skill, the VS Code grammar and snippets updated, and a CTK scenario added ([CLAUDE.md "things that change together"](../../../CLAUDE.md)).
- [ ] `make format-check`, `make release-check` green.
- [ ] `SecurityDocTest` green; `secure-by-construction.md` mentions R7's effect rule and R10's password file.
- [ ] No new dependency.

## 4. Manual checks

| # | Check | Pass |
|---|---|---|
| M1 | Rebuild the owner's `article-flow` with only the fixed tools: `weave test` runs with no `java -cp`; the output is readable; the strings that needed workarounds are written naturally | ⬜ |
| M2 | Read each registry message aloud: it names the fix | ⬜ |
| M3 | Run `weave check --substitution braces` on the owner's project and read what it would change; decide the date for the default flip (D3) | ⬜ |
| M4 | `weave fabric up` from a clean machine with a fetched jar; open the page; `status` shows when the owner's process last reported | ⬜ |
| M5 | `weave review` on a project with no key set: the panel's count and the exit code are as the owner would expect | ⬜ |

## 5. Documentation owed

Generated command pages; `LOOM_GUIDE.md`; `LOOM_PROMPT.md`; `docs/guide/06-build-the-workflow.md` and the others named in §3; the workflow and deployer skills; the VS Code grammar and snippets; `CONTRIBUTING.md` (the registry); `docs/API_COMPATIBILITY.md`; `docs/security/secure-by-construction.md`; release notes; `llms.txt`. Stack-level pages once at the end of the program.

## 6. Exit checklist

- [ ] §0 recorded with dates and build ids; findings that did not reproduce reported
- [ ] Every row in §2 ✅ or partial with a reason; every A-criterion has evidence
- [ ] M1 to M5 done and recorded in §7
- [ ] DESIGN §6 questions answered; decision D3 recorded in the program page
- [ ] Docs in §5 updated; roadmap status updated

## 7. Results

*(empty until 22a)*

## 7. Results

### 22a: R1 to R4 (done)

- **Reproduced first (P1, P3, P5, P2, P4):** `weave test` had no `--classes`, `--loot` or `--env-file` (P1); `expecting` after `retry` was a parse error with no hint (P3); `"say \"hi\""` failed with "Unexpected character '\'" (P5). **P2 did not reproduce as written:** `weave test` and the other non-progress commands were already quiet (`executeQuietly`, `WEAVE_LOG`); the real gaps were no `-v` flag and the progress commands (`run`, `resume`, `tick`, `daemon`) still logging INFO. **P4's cause was not the language:** `value` is reserved by `TaskResult.ok(map)`, so it surfaced as a Java message in a scripted case, not as a parse error; the hint is added there.
- **R4:** the lexer reads `\"` and `\\`, keeps every other backslash, and says what to write for a string ending in one; the editor's parser matches. `StringEscapesTest` (7, with the corpus of every `.loom` file: only the new CTK script contains `\"`), `parseDocument.test.ts` (3), CTK scenario `string_escapes` with `StringEscapesCtkTest`.
- **R3:** `KnownMistakes` registry (two rows) with `KnownMistakesTest` (7) and a scripted-case test in `ScriptedWorldTest`; documented in `CONTRIBUTING.md`.
- **R1:** `weave test` has `--classes`, `--no-auto-classes`, `--loot`, `--env-file`, `--no-env-file`; the first, third and fourth are mixins shared with `run` and `eval`. `OptionParityTest` (5). **`--loot` is accepted and checked to exist but otherwise ignored by `test`**, which scripts every tool.
- **R2:** global `-v` / `-vv` (an inherited option on the root command), default WARNING for every command including `run`; `VerboseFlagTest` (4). The old `eval --verbose` is now the global flag. **Behaviour change:** `weave run` no longer prints INFO progress lines unless `-v` is given.
- Not done in 22a: the "scripted case header only on failure" part of R2 (the case lines of `weave test` are its answer and were left as they are); `--classes` help text is shared by mixin, `--loot`'s is not (it differs per command); the `weave test --classes` path with real task classes was not exercised end to end, only the option and the note.

### 22b: R8, R9, R11 (done; R11's measured usage is not)

- **Reproduced (P8, P9, P11):** `weave observe rules` failed for both a missing and an empty file; `weave review` of a clean script with no key gave three HIGH `LV02` findings (the standalone jar at `HEAD`); the cost line gave tokens and the missing price but no assumptions and nothing about the cap.
- **R9:** `Setup.is` (an `LV02` finding whose message is about the environment) moves such findings into `ReviewReport.setup()`: a "Setup" section in the text, `setup` and `summary.setup` in the JSON, outside the counts, `--fail-on` and the editor's open count; `--include-setup` merges them back. A missing prompt file stays a finding. Tests: `SetupFindingsTest` (6), `ReviewSetupCommandTest` (2, skipped on a machine that has `GEMINI_API_KEY`, named in the skip), an editor test that a `setup` list is never counted. **The command-level test found a real bug:** the `--min-severity` filter rebuilt the report and dropped the new list.
- **R8:** a missing or comment-only rules file means the defaults apply (exit 0); a file with rules and no add-on says `weave tools fetch inspect` or `--classes`; `--init` writes a commented starter and never overwrites. `ObserveRulesTest` (5). `ObserveCliTest` was changed on purpose (it pinned the old wording).
- **R11:** per-step "Assumed per step" lines (runs and tokens a run, typical and at most), the sentence "the cap, N tokens, will stop the run before the worst case, M tokens: expect cut results", and `tokenCap` / `capStopsBeforeWorstCase` in the JSON. `CostBlockSaysWhatItAssumedTest` (7). Dollars when every model is priced and the unpriced model named were already there and are pinned by tests. **Not done: using measured usage from `preflight.json` or a recorded run.** The preflight does not record per-agent usage, so there is nothing to feed it yet.
- Verification: Loom module 3,764 tests, 0 failed after the one wording test was fixed and re-run on its own (the full module was not re-run), 2 skipped (same two); extension 761 passed, 0 skipped.

### 22c: R5, R6, R7 (done)

- **R5:** `SubstitutedText` lists the fifteen statement positions the runtime substitutes; `SubstitutedPositionsTest` compares that list with the runtime's own `resolve`/`resolvePayload` calls, so a new position cannot be forgotten. The lint now scans all of them (it scanned four). `substitution: braces|legacy` is a contextual top-level setting (default `legacy`; `LoomLoader` copies it). `weave check --substitution braces` previews, `--fix-substitution` shows a diff and writes only after a yes (`--yes`). Tests: `SubstitutedPositionsTest` (6), `SubstitutionSettingTest` (6), `SubstitutionMigrationTest` (6), `CheckSubstitutionCommandTest` (5), `SubstitutionCtkTest` (CTK `substitution_braces.loom`).
- **Found by the wider lint:** the `approval` starter said "read from the email" while `email` is a variable, so the word would have been replaced by the whole email. Reworded. A text that is exactly a variable's name (`call AskPerson(reply_text = reply_text)`) is a reference, not an ordinary word, and is not warned about in legacy mode; **under `braces` such an argument must be written `{reply_text}`** (the preview lists it and `--fix-substitution` rewrites it). A braces-mode warning for an unrewritten reference is not built.
- **R7:** `real: [Task]` per case (`RealTasks`, `TestChecker`, `CaseRunner`). Real tasks come from `TaskRegistry.discovered()`; a task not on the class path already stops the script at load with the `--classes` advice, so no separate message exists. Only `TaskEffect.NONE`; the default (`CHANGES`) counts as having effects. `RealTasksTest` (7).
- **R6:** documented only (`LOOM_GUIDE.md`, "Parallel branches that fall back"); `ParallelFallbackPatternDocsTest` runs the documented script in a scripted case.
- **Guards removed once by hand:** R7's effect refusal (`RealTasksTest.aTaskThatChangesThingsCannotBeRealAndTheMessageSaysWhy` went red) and R5's braces switch in `PayloadText` (`SubstitutionSettingTest.braceModeReplacesOnlyTheBracedName` went red); both restored.
- **Verification:** Loom module `mvn verify`: 3,796 tests, 0 failed, 2 skipped (`ImportClosureLoaderTest` unicode path: this machine's file-system encoding; `LiveCostAccuracyTest`: needs `LLM4J_LIVE`, runs in the final `make test-live`). Mutation profile for the Loom module not re-run in this step.
- **Housekeeping:** an accidental `spotless:apply` on the Loom module (outside Spotless's scope) reformatted ~100 files; all were reverted.

### 22d: R10 (done; R8 was done in 22b)

- **R10.1:** `weave fabric up | down | status` (`FabricCommand`, `FabricLauncher`, `FabricJar`, `SystemFabricHost`). Jar lookup in the scripts' order; the password file is created already owner-only (mode 600) and the secret is in no output, argument or log; unsafe bind refused with the scripts' message (exit 3); a second `up` changes nothing; a server that does not answer is stopped and its log shown (exit 5); `down` signals only a process whose command line is Fabric's; `status` reads each client's last report from `<data>/history/<client>/*.jsonl`, so `weave` has no class dependency on Fabric. Tests: `FabricLauncherTest` (20), `FabricJarTest` (6).
- **R10.2:** `run-observed` takes `--secrets-key-file` / `LOOM_SECRETS_KEY_FILE` (`-SecretsKeyFile`). `weave run` already had the option. `DeployerScriptsTest.aMasterKeyKeptElsewhereNeedsNoFileNamedAfterTheStore`.
- **R10.3:** the three Fabric scripts call `weave fabric` when it exists and fall back to their own logic; `DeployerScriptsDelegateTest` (4, a stub `weave`), and `DeployerScriptsTest` (7, real jars, delegation active) pass with **0 skipped**.
- **A behaviour change the old test pinned:** `fabric-up` without a password used to refuse (exit 2); it now generates one. `DeployerScriptsTest` was updated deliberately and now asserts the generated file's mode and that the password is not printed.
- **Found while verifying:** the inspect/fabric jars in `target/` were built from an older commit than the weave jar, so the real-process test failed on the identity check until they were rebuilt with `clean package`. Not a code defect; a stale build.
- **Guards removed once by hand:** the file mode (`rw-------` to `rw-r--r--`: `upWritesAnOwnerOnlyPasswordFile…` red) and the unsafe-bind refusal (`upRefusesABindBeyondThisComputer…` red); both restored.
- **Manual check against the real server:** `up` (password file mode 600, not in the log), second `up` ("Nothing changed"), `status`, an unsafe bind (exit 3), `down`, `status` again.
- **Not done:** the PowerShell scripts were edited in step with the bash ones but **not run** (no `pwsh` here). Fabric under a service manager is not part of this command (local use only, as proposed).
- **A defect of an earlier step, found by the package check (`scripts/verify-skill-package.sh`, which had not been run on this branch):** `weave eval` crashed after its report in the standalone jar (`NoClassDefFoundError: org/opentest4j/TestAbortedException`, from the dashboard bundle of Phase 17/19 work, `b7028482`): the unit tests had JUnit on their class path, the shaded jar has none. Fixed in eval4j (`StopTest`), `RunsWithoutJUnitTest` (3; red with the direct `throw` put back). The check then also showed that a script run from the package must give `weave fabric` the jar it found in the skill's own `bin/`; `lib.sh`/`lib.ps1` now export `FABRIC_JAR` first. All three package checks pass (workflow guide, deployer with a real Fabric, harness design).
