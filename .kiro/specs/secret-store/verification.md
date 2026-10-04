# Secret store: verification

Companion to [design.md](design.md) and [test-strategy.md](test-strategy.md). Status in section 2 is **computed from the surefire reports of the final run**: *Verified* means every named test exists and passed. Nothing is marked by hand.

## 1. Gates

| Gate | Check | Command (repo root) | Result |
|---|---|---|---|
| G1 | Specs written and consistent | design, test strategy and this plan | every requirement ID has a row in section 2 |
| G2 | Compiles, whole reactor (examples included) | `mvn -q -o -DskipTests -Djacoco.skip=true compile` | exit 0 |
| G3 | New core tests | `mvn -pl ai-agent4j test -Dtest='io.github.llm4j.secret.*Test'` | 103 tests pass (names, stores and refs, master key, encrypted file, provider, tools and config, repository scan) |
| G4 | New Loom tests | `mvn -pl loom/ai-agent4j-loom test -Dtest='SecretsLoomTest,SecretCommandsTest,SecretDocsTest'` | 20 tests pass |
| G5 | Full regression, each touched module | `mvn -o -pl <module> verify` | ai-agent4j: 783 tests (2 skipped, as before). ai-agent4j-addons: 17. loom/ai-agent4j-loom: 819 (1 skipped, as before), including the jacoco rules. eval4j: 466. eval4j-report: 46. 0 failures, 0 errors |
| G6 | CTK | `cd loom/ctk && mvn -o test` | 30 tests pass |
| G7 | No keys needed | all of the above run with no provider key in the environment | same results |
| G8 | Repository hygiene | `RepositoryHasNoSecretsTest`; scan for `AIza`, `sk-ant-`, `AKIA` shapes | 0 matches |
| G9 | Negative controls | section 3 | each of the 5 controls made its named tests fail |

## 2. Requirement to test matrix

| ID | Requirement | Tests (all must pass) | Status |
|---|---|---|---|
| STO-01 | name rule | `SecretNamesAndMetadataTest#nameLengthLimit`<br>`StoresAndRefTest#inMemoryRejectsBadNamesAndEmptyValues`<br>`EncryptedFileSecretStoreTest#badNamesAndEmptyValuesAreRefused` | Verified |
| STO-02 | host patterns: exact, `*.suffix`, case-insensitive, never the apex or look-alikes | `SecretNamesAndMetadataTest#unrestrictedAllowsEverything`<br>`SecretNamesAndMetadataTest#exactHostsAreCaseInsensitive`<br>`SecretNamesAndMetadataTest#wildcardMatchesSubdomainsNotTheApexOrLookalikes`<br>`SecretNamesAndMetadataTest#restrictedSecretNeverAllowsAMissingHost`<br>`SecretNamesAndMetadataTest#hostsMayCarryAPortOrBracketsWhenAsked`<br>`SecretNamesAndMetadataTest#hostOfUrls`<br>`SecretNamesAndMetadataTest#metadataHoldsNoValueAndIsImmutable` | Verified |
| STO-03 | stores: in-memory, env (read-only), chained; host binding by `resolveFor` | `StoresAndRefTest#inMemoryRoundTrip`<br>`StoresAndRefTest#inMemoryCopiesTheCallersArrayAndWipesOnRemoveAndClose`<br>`StoresAndRefTest#hostBindingIsEnforcedByResolveFor`<br>`StoresAndRefTest#envStoreIsReadOnlyAndNamesAreVariables`<br>`StoresAndRefTest#chainTakesTheFirstStoreThatHasTheName` | Verified |
| REF-01 | `SecretRef` resolves on the fly; never prints or serializes a value; equality | `StoresAndRefTest#refResolvesOnTheFly`<br>`StoresAndRefTest#refNeverPrintsTheValueAndIsNotSerializable`<br>`StoresAndRefTest#refEqualityAndLiterals`<br>`StoresAndRefTest#refResolveForEnforcesTheStoresBinding` | Verified |
| REF-02 | providers fetch the key for each request (rotation needs no rebuild) | `ProviderSecretTest#theKeyIsFetchedForEachRequestSoRotationNeedsNoRebuild`<br>`SecretsLoomTest#loom_provider_fetchesTheKeyForEachRequest`<br>`ToolsAndConfigSecretTest#serpApiFetchesItsKeyPerSearchAndScrubsErrors`<br>`ToolsAndConfigSecretTest#openApiToolFetchesQueryAndHeaderCredentialsPerRequest`<br>`ToolsAndConfigSecretTest#restSkillRegistryFetchesItsKeyPerRequestAndScrubsErrors` | Verified |
| CRY-01 | master key from passphrase, key file, consumer-named variable, supplier; wiped; never printed | `MasterKeyTest#*`<br>`EncryptedFileSecretStoreTest#passphraseVariantsAllOpenTheSameStore` | Verified |
| CRY-02 | format: round trip, awkward values, no plaintext or names in the file, 600 000 iterations default, floor enforced | `EncryptedFileSecretStoreTest#roundTripSurvivesReopening`<br>`EncryptedFileSecretStoreTest#awkwardValuesRoundTrip`<br>`EncryptedFileSecretStoreTest#theFileHoldsNoPlaintextAndNoSecretNames`<br>`EncryptedFileSecretStoreTest#defaultIterationsAreSixHundredThousand`<br>`EncryptedFileSecretStoreTest#iterationFloorIsEnforced`<br>`EncryptedFileSecretStoreTest#unsupportedVersionsAlgorithmsAndLimitsAreRefused` | Verified |
| CRY-03 | fresh nonce for every write; header authenticated | `EncryptedFileSecretStoreTest#everyWriteUsesAFreshNonce`<br>`EncryptedFileSecretStoreTest#theSameContentEncryptsDifferentlyEachTime`<br>`EncryptedFileSecretStoreTest#everyAuthenticatedHeaderFieldIsBoundToTheCiphertext` | Verified |
| CRY-04 | atomic write; failure leaves the file intact; no temp file left | `EncryptedFileSecretStoreTest#aFailedWriteLeavesTheFileIntactAndNoTemporaryFile`<br>`EncryptedFileSecretStoreTest#manyThreadsReadAndWriteWithoutLosingAnything` | Verified |
| CRY-05 | change detection, reload, external rotation, rekey, close wipes | `EncryptedFileSecretStoreTest#anotherWritersChangeIsDetectedNotOverwritten`<br>`EncryptedFileSecretStoreTest#anExternallyRotatedSecretIsPickedUpWithoutARestart`<br>`EncryptedFileSecretStoreTest#aFileRekeyedElsewhereIsFollowedWhenTheKeyFileMatches`<br>`EncryptedFileSecretStoreTest#rekeyingReplacesTheKeyAndTheSalt`<br>`EncryptedFileSecretStoreTest#removeAndOverwrite`<br>`EncryptedFileSecretStoreTest#closeWipesAndLaterUseFails`<br>`EncryptedFileSecretStoreTest#createRefusesAnExistingFileAndOpenRefusesAMissingOne`<br>`EncryptedFileSecretStoreTest#openOrCreateDoesWhicheverApplies` | Verified |
| CRY-06 | consumer owns path and ACL: nothing required by default; opt-in refusal; the directory is not created | `EncryptedFileSecretStoreTest#thereIsNoPermissionRequirementByDefault`<br>`EncryptedFileSecretStoreTest#requirePrivateFileRefusesAFileOthersCanRead`<br>`EncryptedFileSecretStoreTest#theLibraryCreatesTheFileOwnerOnlyAndKeepsWhatTheConsumerSets`<br>`EncryptedFileSecretStoreTest#theLibraryDoesNotCreateTheConsumersDirectory` | Verified |
| CRY-07 | tamper matrix: wrong key, truncation, bit flips, not a store | `EncryptedFileSecretStoreTest#aWrongMasterKeyIsRefusedWithoutSayingWhy`<br>`EncryptedFileSecretStoreTest#notAStoreAtAll`<br>`EncryptedFileSecretStoreTest#truncationIsDetected`<br>`EncryptedFileSecretStoreTest#ciphertextAndTagFlipsAreDetected`<br>`EncryptedFileSecretStoreTest#anyBitFlipInTheFileEitherFailsOrChangesNothing` | Verified |
| INT-01 | providers: key in the credential header, never the URL; `String` overload unchanged | `ProviderSecretTest#theKeyNeverTravelsInTheUrl`<br>`ProviderSecretTest#theStringOverloadBehavesAsBefore`<br>`ToolsAndConfigSecretTest#stringConstructorsOfTheSearchToolsStillWork`<br>`ToolsAndConfigSecretTest#configWithoutAKeyOrWithABlankOne` | Verified |
| INT-02 | host binding refused before anything is sent; missing secret is an authentication error | `ProviderSecretTest#aSecretBoundToOtherHostsIsRefusedBeforeAnythingIsSent`<br>`ProviderSecretTest#aSecretBoundToTheRealHostIsAccepted`<br>`ProviderSecretTest#aMissingSecretIsAnAuthenticationErrorThatNamesTheSecret`<br>`ToolsAndConfigSecretTest#requireApiKeyChecksTheHostOfTheBaseUrl`<br>`ToolsAndConfigSecretTest#serpApiRefusesAHostTheSecretIsNotBoundTo`<br>`ToolsAndConfigSecretTest#searchToolsReportAMissingSecretWithoutSendingAnything`<br>`ToolsAndConfigSecretTest#openApiToolRefusesAHostTheSecretIsNotBoundTo`<br>`ToolsAndConfigSecretTest#webSearchBindsTheKeyToGoogleApisAndScrubsErrors` | Verified |
| INT-03 | embedding provider, `LLMConfig`, semantic memory accept references | `ProviderSecretTest#theEmbeddingProviderNoLongerPutsTheKeyInAnErrorMessageOrTheUrl`<br>`ToolsAndConfigSecretTest#configNeverHoldsOrPrintsTheKey`<br>`ToolsAndConfigSecretTest#configEqualityNeverComparesTheValueOfAStoredSecret`<br>`ToolsAndConfigSecretTest#semanticMemoryConfigAcceptsReferences` | Verified |
| INT-04 | existing provider and embedding tests still pass against the new contract | `SarvamChatProviderTest#*`<br>`SarvamTextProviderTest#*`<br>`SarvamAudioProviderTest#*`<br>`SarvamTextToSpeechProviderTest#*`<br>`GeminiEmbeddingProviderTest#*` | Verified |
| LOOM-01 | `secret.NAME` parses; bad names rejected | `SecretsLoomTest#loom_parse_secretReferenceIsAnOptionValue` | Verified |
| LOOM-02 | missing secret or no store is a load error that says how to fix it | `SecretsLoomTest#loom_provider_missingSecretIsALoadError`<br>`SecretsLoomTest#loom_provider_withoutAStoreSaysHowToGiveOne`<br>`SecretsLoomTest#loom_tool_secretOptionIsResolvedFromTheStore` | Verified |
| LOOM-03 | declared provider: key per request; https only (or localhost); host binding at load | `SecretsLoomTest#loom_provider_fetchesTheKeyForEachRequest`<br>`SecretsLoomTest#loom_provider_keyBoundToAnotherHostIsRefusedBeforeAnyRequest`<br>`SecretsLoomTest#loom_provider_keyOverPlainHttpToARemoteHostIsRefused` | Verified |
| LOOM-04 | built-in models look the store up first, then the environment | `SecretsLoomTest#loom_builtinModels_lookStoreFirstThenEnvironment`<br>`ProvidersTest#anthropicNeedsItsKeyAndItsNameIsReserved` | Verified |
| LOOM-05 | generic tool options resolved from the store; existing messages updated | `SecretsLoomTest#loom_tool_secretOptionIsResolvedFromTheStore`<br>`ToolsTest#v4_2_aLiteralSecretIsRejected`<br>`ProvidersTest#v3_3_providerDeclarationsAreChecked`<br>`KnowledgeTest#v5_11_badSourcesAndEmbeddings` | Verified |
| LOOM-06 | audit LA15 | `SecretsLoomTest#loom_audit_flagsACredentialSentToACustomAddress` | Verified |
| CLI-01 | `weave secrets` create, set, list, remove: values never printed or stored in the clear | `SecretCommandsTest#cli_lifecycle_neverShowsOrStoresValueInTheClear`<br>`SecretCommandsTest#cli_createRefusesDifferingEntriesAndExistingFile` | Verified |
| CLI-02 | value from stdin; key from a consumer-named variable | `SecretCommandsTest#cli_setReadsStdinAndKeyFromNamedVariable` | Verified |
| CLI-03 | import-env and rekey | `SecretCommandsTest#cli_importEnvCopiesWithoutEchoAndReportsMissing`<br>`SecretCommandsTest#cli_rekeyChangesTheMasterKey` | Verified |
| CLI-04 | `--secrets` opens the store for check/run; wrong key and missing file are clean errors; nothing persisted in the run spec | `SecretCommandsTest#cli_secretsOption_opensTheStoreForRunAndCheck`<br>`SecretCommandsTest#cli_missingFileOptionAndWrongKeyAreCleanErrors`<br>`SecretCommandsTest#cli_secretsOptionNeedsAFileAndNothingIsPersistedInTheRunSpec` | Verified |
| CLI-05 | `secrets` is a registered command and listed in `llms.txt` | `LlmsTxtTest#*` | Verified |
| SEC-01 | no secret value in `toString`, messages, logs, errors the server echoes | `StoresAndRefTest#refNeverPrintsTheValueAndIsNotSerializable`<br>`EncryptedFileSecretStoreTest#toStringsAndMessagesNeverContainTheValueOrThePassphrase`<br>`ProviderSecretTest#aProviderThatEchoesTheKeyInAnErrorDoesNotLeakIt`<br>`ToolsAndConfigSecretTest#openApiToolDoesNotReturnOrLogACredentialTheServerEchoes`<br>`SecretCommandsTest#cli_lifecycle_neverShowsOrStoresValueInTheClear` | Verified |
| SEC-02 | a secret is sent only to hosts it allows; a refusal sends nothing | `ProviderSecretTest#aSecretBoundToOtherHostsIsRefusedBeforeAnythingIsSent`<br>`SecretsLoomTest#loom_provider_keyBoundToAnotherHostIsRefusedBeforeAnyRequest` | Verified |
| SEC-03 | tampering, truncation, wrong key and a lowered iteration count are detected | `EncryptedFileSecretStoreTest#everyAuthenticatedHeaderFieldIsBoundToTheCiphertext`<br>`EncryptedFileSecretStoreTest#anyBitFlipInTheFileEitherFailsOrChangesNothing`<br>`EncryptedFileSecretStoreTest#truncationIsDetected`<br>`EncryptedFileSecretStoreTest#aWrongMasterKeyIsRefusedWithoutSayingWhy` | Verified |
| SEC-04 | nothing chosen for the consumer: no default path or key source | `EncryptedFileSecretStoreTest#theLibraryDoesNotCreateTheConsumersDirectory`<br>`MasterKeyTest#fromEnvUsesTheVariableTheConsumerNames`<br>`SecretDocsTest#docs_stateWhoOwnsThePathAndTheKey` | Verified |
| SEC-05 | the CLI never takes a secret value as an argument | `SecretCommandsTest#cli_lifecycle_neverShowsOrStoresValueInTheClear`<br>`SecretCommandsTest#cli_setReadsStdinAndKeyFromNamedVariable` | Verified |
| SEC-06 | providers hold no copy of the key beyond the request | `ProviderSecretTest#theKeyIsFetchedForEachRequestSoRotationNeedsNoRebuild`<br>`ToolsAndConfigSecretTest#configNeverHoldsOrPrintsTheKey` | Verified |
| HARD-01 | no credential-shaped string is committed to the repository | `RepositoryHasNoSecretsTest#*` | Verified |
| DOC-01 | documented Loom examples parse; links resolve; required statements present | `SecretDocsTest#*` | Verified |

## 3. Negative controls

Each guarantee was broken on purpose; the named tests failed, then the change was reverted and the tests passed again.

| # | Break | Failing tests |
|---|---|---|
| 1 | `Credentials.scrub` returns its input unchanged (no scrubbing of echoed keys) | `ProviderSecretTest#aProviderThatEchoesTheKeyInAnErrorDoesNotLeakIt` |
| 2 | `SecretMetadata.allows` always true (host binding off) | `ProviderSecretTest#aSecretBoundToOtherHostsIsRefusedBeforeAnythingIsSent`, `StoresAndRefTest#hostBindingIsEnforcedByResolveFor`, `StoresAndRefTest#chainTakesTheFirstStoreThatHasTheName` |
| 3 | `SecretRef.resolve` caches the first value (no per-request fetch) | `StoresAndRefTest#refResolvesOnTheFly` |
| 4 | A realistic Google-key-shaped string planted in the tree | `RepositoryHasNoSecretsTest#noFileInTheRepositoryContainsACredentialShapedString` (a first attempt with a sequential string was correctly ignored as a placeholder) |
| 5 | `weave secrets set` echoes the stored value | `SecretCommandsTest#cli_lifecycle_neverShowsOrStoresValueInTheClear`, `cli_setReadsStdinAndKeyFromNamedVariable`, `cli_secretsOption_opensTheStoreForRunAndCheck` |

## 4. Iteration log

1. First Loom run after the `OptionValue` refactor: 4 failures, all old assertions on messages that were intentionally reworded to mention the secret store (provider api_key, unset variable, Gemini embedding key, SerpApi literal). Assertions updated.
2. First core run: 19 errors in existing tests that mocked `LLMConfig.getApiKey()` only (Sarvam x4, Gemini embedding). Stubs extended to the new `hasApiKey`/`requireApiKey`/`getApiKeyRef`. One new test expected literal refs to be unequal; the implementation compares literals in constant time, so the test was corrected.
3. `SecretsLoomTest` rotation test first ran two workflows on one executor and the second made no request; rewritten to rotate the secret inside the mock server while serving the first request of one workflow.
4. `SecretDocsTest` first paired code fences wrongly (a ```java block shifted the pairing); fixed to parse fences with their language.
5. CLI test first used a stub model factory, so "key only in the store" could not fail; it now uses the real default factory.
6. Control 4 first used a sequential string and passed (placeholder heuristics); redone with a realistic string.

## 5. Not covered, stated plainly

- Protecting the store file's path with an ACL, and the secrecy of the master key's source, are the consumer's responsibility by design.
- Windows file ACLs are not inspected (POSIX permissions only). Concurrent writers are detected, not coordinated.
- Tool options (e.g. a SerpApi key declared in Loom) are resolved when the tool is created, and `PineconeVectorStore` resolves once; documented in the wiki page.
- Keys that were committed earlier remain in git history and must be rotated at the provider.
