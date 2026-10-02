# G3: sabotage runs

Each row breaks one rule in the real source, runs the channel tests, and restores the source.
**DETECTED** means every expected check failed.

| # | Sabotage | Expected to fail | Result | Failing checks | Failing tests |
|---|---|---|---|---|---|
| M1 | Ignore the allowlist (anyone may answer) | V3.3, V5.1 | **WEAK** | failing checks ['V3.3']; expected ['V5.1'] did not fail | TelegramTest.aReplyFromAChatOrAUserThatMayNotAnswerIsIgnoredNotAnsweredAndCounted |
| M2 | Accept an answer twice | V2.3 | **DETECTED** | failing checks ['V1.8', 'V2.2', 'V2.3', 'V2.6', 'V3.6', 'V4.5', 'V4.6', 'V5.1'] | ChannelRunTest.questionsAndAnswerWorkFromATerminalWithNoChannelConfigured, ChannelSafetyTest.onlyAnAllowedSenderForAnOpenQuestionEverAnswers, QuestionFlowTest.aSecondAnswerIsRefusedAndNamesWhoWasFirst, QuestionFlowTest.anExpiredQuestionIsClosedTheRunIsToldToCarryOnAndTheStepGetsAnEmptyAnswer, QuestionFlowTest.anUnknownExpiredOrAnsweredCodeChangesNothingAndSaysWhy, QuestionFlowTest.everyCrashPointLeavesOneAnswerAndAtMostADuplicateMessageWithTheSameCode (+1 more) |
| M3 | Match a coded reply to the wrong question | V3.4 | **SURVIVED** | no test failed |  |
| M4 | Drop the code requirement for approvals | V5.3 | **DETECTED** | failing checks ['V5.3'] | ChannelSafetyTest.anApprovalNeedsTheCodeInTheReplyWhateverElseIsTrue |
| M5 | Show the proposal in a watch question | V5.4 | **DETECTED** | failing checks ['V5.4'] | ChannelRunTest.aWatchQuestionItsReminderAndItsConfirmationNeverShowTheProposal |
| M6 | Send the question again on every resume | V1.4 | **SURVIVED** | no test failed |  |
| M7 | Advance the offset before the replies are recorded | V3.6 | **DETECTED** | failing checks ['V3.6'] | TelegramTest.aRestartNeitherAppliesOldRepliesAgainNorMissesNewOnes |
| M8 | Let the token through in an error | V3.1 | **DETECTED** | failing checks ['V3.1', 'V5.6'] | ChannelSafetyTest.theBotTokenIsNowhereInTheStoreTheAuditLogTheLogsOrTheErrors, TelegramTest.aFailureThatEchoesTheAddressNeverCarriesTheToken |
| M9 | Treat an empty allowlist as everyone | V5.7 | **DETECTED** | failing checks ['V5.7'] | ChannelSafetyTest.aChannelThatCannotBeUsedFailsAtStartAndNamesWhatIsMissingNeverAcceptingEveryone |
