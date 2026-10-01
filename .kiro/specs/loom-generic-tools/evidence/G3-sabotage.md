# G3: sabotage runs

Each row breaks one rule in the real source, runs the tests that should notice, and restores the source
(`scripts/sabotage_generic_tools.py`). **DETECTED** means every expected check failed.

**Result: 21 of 21 detected.**

History (the first run found three problems with the *checks*, not the code):

- **S5** was reported WEAK because I had expected the hostile-model check H1 to fail too. It can't: none of H1's
  attacks reach the server-error path where the sabotage matters. The check that does (V1.5) failed; the expectation was corrected.
- **S9** didn't compile (removing the symlink comparison left an `IOException` that nothing throws). The sabotage was rewritten
  to keep the call and change the comparison; it is detected by V7.3 and H4.
- **S21** (read the whole body) made the test JVM run out of memory, because the test fed the reader an endless stream. That is a
  detection, but a crash can't be mapped to a check, so the test now feeds a finite 5 MB stream and fails cleanly (V3.7).

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
| S21 | Limits: read the whole body | V3.7 | **DETECTED** | failing checks ['V3.7'] | LimitsTest.readCappedDoesNotReadTheWholeStream, LimitsTest.readCappedReadsOnlyWhatItMayAndKnowsThereWasMore |
