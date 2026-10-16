# G5: sabotage runs

Each row breaks one rule in the real source, runs the starter and host tests, and restores the source.
**DETECTED** means every expected check failed.

| # | Sabotage | Expected to fail | Result | Failing checks | Failing tests |
|---|---|---|---|---|---|
| S1 | drop @ConditionalOnMissingBean from the primary LLMClient | V0.3 | **DETECTED** | failing checks ['V0.3'] | ZeroMagicTest.aBeanOfYourOwnAlwaysTakesThePlace |
| S2 | accept a client with no provider instead of failing at start-up | V3.1 | **DETECTED** | failing checks ['V3.1'] | SettingsTest.aClientNeedsItsProviderAndAnyOptionalSettingIsRead |
| S3 | let the literal api-key win over api-key-secret | V2.8 | **DETECTED** | failing checks ['V2.8', 'V7.3'] | RagTest.passwordsAndKeysCanBeSecretsNotLiterals, SecretsTest.aLiteralAndASecretTogetherAreRefused |
| S4 | resolve api-key-secret once at start-up | V2.6 | **DETECTED** | failing checks ['V2.6', 'V2.7'] | SecretsTest.aKeyBoundToOtherHostsIsNeverSentToThisOne, SecretsTest.aKeyIsFetchedForEachRequest |
| S5 | never mask personal data | V4.7 | **DETECTED** | failing checks ['V4.7', 'V4.8', 'V4.9'] | DecoratorsTest.maskingIsOutsideTheBudgetSoTheEstimatorSeesMaskedText, DecoratorsTest.onlyTheNamedTypesAreMasked, DecoratorsTest.personalDataNeverReachesTheProvider |
| S6 | ignore the per-call cap | V4.2 | **DETECTED** | failing checks ['V4.2', 'V4.4', 'V4.5'] | DecoratorsTest.aWindowedBudgetRefillsWhenTheWindowEnds, DecoratorsTest.budgetListenersHearTheWarningOnce, DecoratorsTest.theBudgetRefusesACallBeforeItReachesTheProvider |
| S7 | skip kind.check before create | V6.2 | **DETECTED** | failing checks ['V6.2'] | ToolsTest.aToolWhoseOptionsAreWrongFailsStartUpWithTheLibrarysReason |
| S8 | let a later tool overwrite an earlier one of the same name | V6.6 | **DETECTED** | failing checks ['V6.6'] | ToolsTest.twoToolsWithOneNameAreAnErrorThatNamesBoth |
| S9 | remove the durability guard | V8.28, V9.8 | **WEAK** | failing checks ['V8.28']; expected ['V9.8'] did not fail | LoomHostRunTest.withoutAStoreAScriptThatWaitsIsRefusedOrFailsItsRun |
| S10 | ignore fail-on | V9.5 | **DETECTED** | failing checks ['V9.5', 'V9.6'] | LoomRuntimeTest.aScriptWithTheLethalTrifectaDoesNotStart, LoomRuntimeTest.clearingTheRiskOrLoweringTheGateKnowinglyLetsItStart |
| S12 | accept an answer for a step that is not waiting | V8.12 | **DETECTED** | failing checks ['V8.12'] | LoomHostCoverageTest.anEmptyOrRepeatedOrUnaskedAnswerIsRefusedPlainly, LoomHostRunTest.anAnswerThatDoesNotFitIsRefusedWithAReason |
| S13 | never shut an executor down after a run ends | V8.5 | **DETECTED** | failing checks ['V8.5'] | LoomHostRunTest.theExecutorIsShutDownWhateverTheOutcome |
| S14 | attach observe after initialize() | V12.4 | **DETECTED** | failing checks ['V12.3', 'V12.4', 'V12.6'] | LoomDurableTest.theRunPageSessionIsOpenedOnceWithTheSettingsAndEveryExecutorIsAttachedFirst |
| S15 | give run executors no trigger store | V8.14, V8.19 | **WEAK** | failing checks ['V8.14']; expected ['V8.19'] did not fail | LoomHostRunTest.aSleepingRunWakesWhenTheTriggerIsDue |
| S16 | use the system clock instead of the injected one in executors | V8.14, V8.19 | **WEAK** | failing checks ['V8.14']; expected ['V8.19'] did not fail | LoomHostRunTest.aSleepingRunWakesWhenTheTriggerIsDue |
| S19 | make the agent builder a singleton | V5.6, V5.7 | **DETECTED** | failing checks ['V5.1', 'V5.6', 'V5.7'] | AgentsTest.everyBuilderAndEveryAgentIsNewAndThereIsNoSharedAgent, SettingsTest.agentMistakesAreRefused |
| S20 | print the literal api-key while creating a client | V2.10 | **DETECTED** | failing checks ['V2.10'] | SecretsTest.aLiteralKeyIsInNoLogMessageOrPrintout |
