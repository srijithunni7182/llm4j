# H4: sabotage runs

Each row breaks one safeguard in the real source, runs the ladder tests, and restores the source.
**DETECTED** means at least one test failed; a safeguard whose removal fails nothing is untested.

| # | Sabotage | Result | Detail | Failing tests |
|---|---|---|---|---|
| S21 | Re-judge (and demote) a standing the original engine earned, when a decision moves to the ladder engine | **DETECTED** | 2 test(s) failed | LadderGuideTest.everyLoomBlockInTheSectionLoadsWithItsImportsAndPassesTheChecks, TrustMigrationTest.aLedgerTheOriginalEngineWroteIsAdoptedAtEveryLevelItReachedAndNothingIsDemotedByTheChange |
| S22 | Hide the cases a ladder wrote from the replay feature | **DETECTED** | 14 test(s) failed | DecideResilienceTest.aCaseThatIsRewoundAndDecidedAgainCountsOnceAndThePersonIsNotAskedTwice, DecideResilienceTest.aRunThatCrashesAtEveryJournalWriteAndIsResumedEndsWithExactlyOneCaseOneProposalAndOneVerdict, DecideResilienceTest.aVerdictInTheJournalIsAppendedToALedgerThatWasDownWhenItWasGiven, DecideSafetyTest.aProposalThatClaimsToBeApprovedPromotesItselfOrPlantsRecordsChangesNothing, DecideSafetyTest.whenTheAgentChangesStartOverReturnsToTheStartLevelWithNoEvidence (+9 more) |
| S23 | Let a case change rung after the agent has replied (a freeze arriving mid-case) | **DETECTED** | 2 test(s) failed | DecideSafetyTest.aFreezeThatArrivesWhileACaseIsBeingProposedDoesNotChangeThatCase, LadderGuideTest.everyLoomBlockInTheSectionLoadsWithItsImportsAndPassesTheChecks |
| S24 | When two ways down hold, take the first instead of the lowest | **DETECTED** | 2 test(s) failed | LadderGuideTest.everyLoomBlockInTheSectionLoadsWithItsImportsAndPassesTheChecks, LadderSlipsAndSampleTest.whenBothHoldTheLowestRungWins |
| S25 | Write the trust block without its days requirement | **DETECTED** | 4 test(s) failed | LadderGuideTest.everyLoomBlockInTheSectionLoadsWithItsImportsAndPassesTheChecks, TrustAsLadderEquivalenceTest.theLadderMovesWhereTheTrustBlockMovesOverRandomHistories, TrustDesugarTest.eachStepUpIsTheRuleItWasWrittenAs, TrustDesugarTest.everyPartOfARuleIsNamedInTheWordsOfTheTrustBlock |
| S26 | Count every case, not only blind ones, as evidence for a trust block | **DETECTED** | 2 test(s) failed | LadderGuideTest.everyLoomBlockInTheSectionLoadsWithItsImportsAndPassesTheChecks, TrustAsLadderEquivalenceTest.theLadderMovesWhereTheTrustBlockMovesOverRandomHistories |
