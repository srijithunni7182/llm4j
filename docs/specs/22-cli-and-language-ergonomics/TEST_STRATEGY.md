# Phase 22 test strategy: CLI and language ergonomics

How each requirement of [DESIGN.md](DESIGN.md) is tested, under the rules of [TESTING_STRATEGY.md](../../TESTING_STRATEGY.md): hermetic, deterministic, no model. The requirements are many and small, so the strategy is a few reusable devices rather than a bespoke approach per item.

## 1. Principle: pin the message, not just the behaviour

Most of these findings are about **what the tool says**. A test that only asserts "it fails" would pass on the old, unhelpful message. So tests assert the **hint text** (golden files for error messages), the **options a command accepts** (generated from the command's own help), and the **lines a run prints** (and does not print). Golden files are reviewed like code; changing one is a visible, deliberate act.

## 2. Reusable devices

| Device | Used for |
|---|---|
| **Known-mistakes registry test** (R3): a table of (script snippet, expected message) pairs, one row per registry entry, run through the real parser and validator | R3, and every future "it said X but meant Y" |
| **Option-parity test** (R1): reads `weave test`, `run` and `eval` option definitions and asserts that the shared options (`--classes`, `--loot`, `--env-file`) exist on all three with identical descriptions | R1 |
| **Output contract test** (R2): runs a command with a captured stdout, stderr and logger and asserts what appears at each verbosity | R2 |
| **Repository-scripts corpus** (R4, R5): every `.loom` file in the repository (the existing `RepositoryScriptsTest` corpus) is lexed, parsed and checked before and after a change; the **set of token streams** must be identical except where a change is meant | R4, R5 |
| **Runtime-vs-lint coverage test** (R5.1): finds every call site where the runtime substitutes text and asserts the lint scans that statement kind, so a new position cannot be forgotten | R5.1 |
| **Fake Fabric** (R10): the `fabric-sim` example server from Phase 16, or a stub jar that prints and binds loopback | R10 |
| **Process stub jars** (R8, R10): tiny jars standing in for the inspect add-on and Fabric, built in the test | R8, R10 |

## 3. Fixtures

- **`MavenProjectFixture`**: a temporary project with a `pom.xml`, `target/classes` holding a compiled task class from a stub source, and a `.loom` script that calls it (R1).
- **`StringsFixture`**: scripts with each string shape in R4 (embedded `\"`, a trailing backslash, `\\`, `\n`, a quote in a prompt file placeholder).
- **`SubstitutionFixture`**: the field case (`out` inside a message) and one per substituted position (R5.1).
- **`TaskEffectsFixture`**: tasks declared with effect policies `none`, `read`, `write`, `send` and one undeclared (R7).
- **`ReviewEnvironmentFixture`**: a script that is clean but whose provider key is unset; one with a real script defect (R9).
- **`CostFixture`**: agents with and without known prices, with measured usage in a `preflight.json` (R11).

## 4. Per requirement

| Requirement | Tests (written first) |
|---|---|
| **R1** | `OptionParityTest`; `TestUsesTargetClassesInAMavenProjectTest` (one announcing line; `--no-auto-classes` disables); `TestWithExplicitClassesTest`; the three commands' help share one generated text |
| **R2** | `DefaultLogLevelIsWarningTest`, `VerboseRestoresInfoTest`, `ScriptedCaseHeaderOnlyOnFailureTest`, `LoaderLineIsFineTest`, `LibraryLoggingUnaffectedTest` (a library user's logger configuration is not overridden), `UserLoggingConfigWinsTest` |
| **R3** | `KnownMistakesRegistryTest` (table), `ExpectingBeforeRetryHintTest`, `ReservedValueHintTest`, `HintsNeverEchoPromptTextTest` |
| **R4** | `StringEscapesTest` (`\"`, `\\`, `\n`, `\t`, unknown escape kept, trailing backslash error with hint), `StringEscapeCorpusUnchangedTest` (the repository corpus lexes to the same strings except where a script used `\"`; any exception is listed in the test), `PromptFilePlaceholdersUnaffectedTest`, a CTK scenario |
| **R5** | `LintCoversEverySubstitutedPositionTest` (the coverage test), `OutInsideAMessageIsReportedTest` (the field case), `SubstitutionBracesSettingTest` (only `{name}` and `{a.b}` replaced), `SubstitutionDefaultIsLegacyTest`, `CheckSubstitutionPreviewTest`, `FixSubstitutionShowsADiffAndNeedsAYesTest`, a CTK scenario |
| **R6** | `ParallelFallbackPatternDocsTest` (the documented pattern parses and runs in a scripted test); no construct is tested because none is built here |
| **R7** | `RealTaskRunsTheActualClassTest`, `RealTaskWithEffectsIsRefusedTest` (guard test), `UndeclaredTaskIsTreatedAsHavingEffectsTest`, `OtherTasksStayScriptedTest` |
| **R8** | `ObserveRulesWithoutAddOnSaysHowTest`, `ObserveRulesEmptyFileMeansDefaultsTest`, `ObserveRulesInitWritesStarterTest` |
| **R9** | `MissingKeyIsASetupFindingTest`, `SetupFindingsAreNotCountedByFailOnTest`, `IncludeSetupRestoresCountingTest`, `ReviewPanelOpenCountExcludesSetupTest` (extension), `AFindingMustBeClearableByEditingTheScriptTest` (a rule-level invariant over every review rule's declared kind) |
| **R10** | `FabricUpFindsTheJarByLookupOrderTest`, `FabricUpGeneratesOwnerOnlyPasswordFileTest` (mode 0600; the password is in no output, log or argument), `FabricUpRefusesUnsafeBindTest`, `FabricStatusShowsLastReportPerClientTest`, `FabricDownTest`, `RunObservedSecretsKeyFileTest`, `DeployerScriptsDelegateToWeaveFabricTest` |
| **R11** | `CostBlockStatesAssumptionsTest`, `CostBlockPrintsDollarsWhenPriceKnownTest`, `CostBlockNamesMissingPriceTest`, `CapBitesBeforeWorstCaseSentenceTest`, `EstimateUsesMeasuredUsageTest` |

## 5. Fault injection

A `target/classes` that is empty; a script whose task class is missing (R1); a registry entry whose snippet no longer fails (the test fails, so a stale entry cannot stay); a string with a million characters (R4: no quadratic scan); a task class that throws on load (R7); a Fabric jar that exits at once; a password file that cannot be created (R10: a clear message, no partial file); a `preflight.json` with a bad number (R11: ignored with a warning).

## 6. Mutation testing

R3's registry matching, R4's escape handling, R5's position coverage and the braces mode, R7's effect check and R9's "script finding" invariant join the Loom `mutation` profile. Each guard is removed once by hand (R7's effect refusal, R9's classification, R10's file mode) and the failing test named in `VERIFICATION.md` §7.

## 7. Deliberately not tested

- The wording quality of a hint beyond the golden text (a person reviews each new one).
- Fabric's own behaviour (its tests are its own); only the launching and the front door.
- Third-party loggers' formats.
