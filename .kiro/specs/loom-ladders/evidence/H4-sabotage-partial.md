# H4: sabotage runs

Each row breaks one safeguard in the real source, runs the ladder tests, and restores the source.
**DETECTED** means at least one test failed; a safeguard whose removal fails nothing is untested.

| # | Sabotage | Result | Detail | Failing tests |
|---|---|---|---|---|
| S27 | Keep a large artifact inside the record of the case instead of apart | **DETECTED** | 3 test(s) failed | ArtifactReferenceTest.aLargeArtifactSurvivesTheFileLedgerWholeAndRetentionRemovesItFromTheFile, ArtifactReferenceTest.aLargeProposalAndItsEditAreKeptByReferenceAndMeasuredWithinABoundAndRetentionRemovesThem, ArtifactReferenceTest.textAtOrUnderTheLimitStaysInTheRecord |
| S28 | Retention leaves the text of a large artifact in place | **DETECTED** | 2 test(s) failed | ArtifactReferenceTest.aLargeArtifactSurvivesTheFileLedgerWholeAndRetentionRemovesItFromTheFile, ArtifactReferenceTest.aLargeProposalAndItsEditAreKeptByReferenceAndMeasuredWithinABoundAndRetentionRemovesThem |
| S29 | Accept a record after the anchor that has no valid seal | **DETECTED** | 6 test(s) failed | SealTest.aForgedPromotionIsFoundWhicheverWayItIsDone, SealTest.aMemoryLedgerIsSealedToo, SealTest.aRecordAddedByHandAtTheEndIsFound, SealTest.aRecordOfOneScopeGivenTheSealOfAnotherIsFoundBecauseTheSealCoversWhatItSays, SealTest.anOperatorWhoHasLookedCanAcceptTheRecordAndItIsSealedFromThere (+1 more) |
| S30 | Go on using a record of levels that does not verify | **DETECTED** | 1 test(s) failed | TamperedStoreTest.anAgentCannotBeGivenALevelByEditingTheLedgerAndTheOperatorCanLookAndAccept |
| S31 | Do not compare the anchor's hash with the records before it | **DETECTED** | 1 test(s) failed | TamperedStoreTest.anAgentCannotBeGivenALevelByEditingTheLedgerAndTheOperatorCanLookAndAccept |
| S32 | Ignore a gap in the versions of a scope (a removed record) | **DETECTED** | 1 test(s) failed | SealTest.aRecordRemovedIsFound |
