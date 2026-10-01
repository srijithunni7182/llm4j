# G3: sabotage runs

Each row breaks one rule in the real source, runs the tests that should notice, and restores the source.
**DETECTED** means every expected check failed.

| # | Sabotage | Expected to fail | Result | Failing checks | Failing tests |
|---|---|---|---|---|---|
| S1 | NetPolicy: stop unwrapping IPv4-mapped IPv6 addresses | V3.2 | **DETECTED** | failing checks ['V3.2'] | NetPolicyTest.anIpv4MappedIpv6AnswerIsJudgedAsTheIpv4AddressItWraps |
| S2 | HttpSupport: connect through a second, unchecked DNS lookup | V3.3 | **DETECTED** | failing checks ['V3.3'] | HttpSupportTest.theConnectionUsesTheCheckedAddressWithoutASecondLookup |
| S3 | RequestPath: accept // in a path | V6.2, H2 | **DETECTED** | failing checks ['F2', 'H2', 'V6.2'] | HostileModelSuiteTest.httpAttacks, HttpToolTest.generatedPathsAreOnlyEverAcceptedWhenTheyStayBelowTheBase, HttpToolTest.hostileOrMalformedPathsAreRefusedAndNothingIsSent |
| S4 | Redactor: skip the Base64 forms | V1.5, F4 | **DETECTED** | failing checks ['F4', 'V1.5'] | RedactorTest.generatedSecretsNeverSurviveInAnyForm, RedactorTest.removesTheSecretInEveryFormItCanAppearIn |
| S5 | A tool puts its URL in an error and its output isn't scrubbed | V1.5 | **DETECTED** | failing checks ['V1.5'] | WebhookToolTest.theUrlNeverComesBackEvenWhenTheServerEchoesIt |
| S6 | EffectTool: don't write 'pending' before acting | V2.3, V2.4 | **DETECTED** | failing checks ['V2.1', 'V2.10', 'V2.2', 'V2.3', 'V2.4', 'V5.12'] | EffectToolTest.anAttemptWhoseOutcomeIsUnknownUsesUpTheAllowanceBecauseItMayHaveBeenSent, EffectToolTest.anOutcomeTheToolReportsAsUnknownStaysPending, EffectToolTest.anUnknownOutcomeIsNotRepeatedByDefault, EffectToolTest.onUnknownRetryRepeatsIt, EffectToolTest.writesPendingBeforeActingAndDoneAfter, EffectsEndToEndTest.aCrashAtAnyJournalWriteSendsTheMessageExactlyOnceAcrossTheResume |
| S7 | EffectTool: treat a failed record as done | V2.7 | **DETECTED** | failing checks ['V2.7'] | EffectToolTest.aFailedCallIsNotReportedAsDoneAndTheRetryRunsAgain |
| S8 | PathGuard: allow hidden files | V7.3, H4 | **DETECTED** | failing checks ['F2', 'H4', 'V7.3'] | FileToolTest.escapesHiddenFilesAndDisallowedNamesAreRefused, FileToolTest.generatedPathsNeverReachOutsideTheRootOrHiddenFiles, HostileModelSuiteTest.fileAttacks |
| S9 | SafePaths: skip the symlink check | V7.3 | **DETECTED** | failing checks ['H4', 'V7.3'] | FileToolTest.aSymlinkLeadingOutIsRefused, SafePathsTest.symbolicLinksLeadingOutAreRefused |
| S10 | ShellTool: run through sh -c | V8.1, H5 | **DETECTED** | failing checks ['H5', 'V8.1', 'V8.5', 'V8.7'] | ShellToolTest.aProgramThatRunsTooLongIsStoppedAndSoAreItsChildren, ShellToolTest.argumentsAreNeverInterpretedByAShell, ShellToolTest.theChildSeesOnlyAMinimalEnvironment |
| S11 | ShellTool: don't clear the environment | V8.5 | **DETECTED** | failing checks ['V8.5'] | ShellToolTest.theChildSeesOnlyAMinimalEnvironment |
| S12 | ShellKind: skip the interpreter deny-list | V8.3 | **DETECTED** | failing checks ['V8.3'] | ShellToolTest.interpretersAndWrappersAreLoadErrorsUnlessAllowed |
| S13 | ShellKind: drop the approve-or-unattended rule | V8.10 | **DETECTED** | failing checks ['V8.10'] | ShellToolTest.anAgentMustApproveAShellToolOrTheToolMustSayItIsUnattended |
| S14 | SqlGuard: stop forbidding INTO | V9.3, F1, H6 | **DETECTED** | failing checks ['F1', 'H6', 'V9.3'] | SqlGuardTest.aRefusalSaysWhichRule, SqlGuardTest.generatedStatementsAreClassifiedExactlyAsTheirConstructionSays, SqlGuardTest.statementsThatCouldChangeThingsOrAreNotOneSelectAreRefused |
| S15 | SqlTool: don't open the connection read-only | V9.4 | **DETECTED** | failing checks ['V9.4'] | SqlToolTest.everyCallOpensAReadOnlyConnectionAndClosesIt |
| S16 | SqlTool: put parameter values into the statement text | V9.1 | **DETECTED** | failing checks ['H6', 'V9.1'] | SqlToolTest.aSelectWithBoundParametersReturnsATable, SqlToolTest.jsonAndCsvFormats, SqlToolTest.valuesReachTheDatabaseOnlyAsBoundParameters |
| S17 | EmailTool: allow line breaks in the subject | V5.3, F3 | **DETECTED** | failing checks ['F3', 'H3', 'V5.3'] | EmailFuzzTest.generatedSubjectsNamesAndAddressesNeverAddHeadersOrRecipients, EmailToolTest.injectionOversizeAndTooManyRecipientsAreRefusedWithNothingSent |
| S18 | EmailTool: skip the allow_to check | V5.2 | **DETECTED** | failing checks ['H3', 'V5.2'] | EmailToolTest.allowToLetsTheAgentChooseOnlyWithinThePatterns |
| S19 | SmtpSender: deliver to the valid recipients when one is refused | V5.11 | **DETECTED** | failing checks ['V5.11'] | EmailToolTest.everyStageOfAFailedSendEndsAsFailedWithARedactedReason |
| S20 | EffectTool: count only finished calls against max_per_run | V5.12 | **DETECTED** | failing checks ['C3', 'V5.12'] | EffectToolTest.anAttemptWhoseOutcomeIsUnknownUsesUpTheAllowanceBecauseItMayHaveBeenSent, EmailToolTest.fiftyThreadsAgainstALimitOfTwentySendExactlyTwenty |
| S22 | HttpSupport: send credential headers on a redirect to another origin | V3.5, H2 | **DETECTED** | failing checks ['H2', 'V3.5'] | HttpSupportTest.credentialsAreNotSentToAnotherOriginOnAFollowedRedirect |
| S23 | PathGuard: allow control and line-break characters in a path | V7.3, H4 | **DETECTED** | failing checks ['F2', 'H3', 'H4', 'V5.5', 'V7.3'] | EmailToolTest.anAttachmentWhoseNameCouldInjectAHeaderIsRefused, FileToolTest.aNameWithAControlOrLineBreakCharacterIsRefusedEverywhere, FileToolTest.generatedPathsNeverReachOutsideTheRootOrHiddenFiles |
| S21 | Limits: read the whole body | V3.7 | **DETECTED** | failing checks ['V3.7'] | LimitsTest.readCappedDoesNotReadTheWholeStream, LimitsTest.readCappedReadsOnlyWhatItMayAndKnowsThereWasMore |
