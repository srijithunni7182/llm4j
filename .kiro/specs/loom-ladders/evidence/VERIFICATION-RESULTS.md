# Verification results

Each check of [verification.md](../verification.md), what was done, and what it showed. **Pass** means an automated test or a measured run did it;
**Partly** says what is missing; **Needs people** is a check that asks someone other than the author; **Not done** is not done. The mapping of this plan
to the spec (completion item 4) has **not** been done and should be done by someone other than the author.

Tests named here are in `src/loom/ai-agent4j-loom/src/test/java/io/github/llm4j/loom/`. "Reference ladders" are the three `.loom` files in
`src/test/resources/ladders/`; "novel" are the two in `ladders/novel/`.

## Summary

| | |
|---|---|
| Checks | 76 in the plan |
| Pass | 70 of 76 (92%); 1 partly; 5 need people; 0 not done or a finding. The most that can be done without people is 70 of 76. |
| Whole module test suite | `mvn verify`: 1,594 tests, 0 failures, 1 skipped (as before), coverage rules pass; the default build also excludes the `fragile` group, whose decision tests (`ChannelRunTest`) were run explicitly |
| Ladder tests | about 330, all passing |
| Safeguard removal (H4) | **26 of 26** caught ([H4-sabotage.md](H4-sabotage.md), [H4-sabotage-partial.md](H4-sabotage-partial.md)); S15 needed a new test; S21-S26 cover the trust work |
| Mutation testing (PIT) on the language and engine | **90%** of 1,785 mutants killed, test strength 92%, 53 with no covering test (first run: 79%, 176) |
| Line / branch coverage | expression language 99.0% / 92.5%; ladder package 97.6% / 88.3%; CLI, parser, executor wiring 94.2% / 73.1% |
| Editor extension tests | 174 pass, 4 fail; the 4 fail identically on the commit before this work (logo script, renderer copy: environment) |

## A. Can a publisher express a real policy without waiting for us?

| # | Result | Evidence |
|---|---|---|
| A1 | Pass | `ReferenceLaddersTest.theRefundAgentEarnsActsSlipsAndStartsOverWhenItChanges` |
| A2 | Pass | `ReferenceLaddersTest.theScriptAuthorIsJudgedByEditsAndByWhatCiLaterSays` |
| A3 | Pass | `ReferenceLaddersTest.theBlogPublisherNeedsCarefulReviewsAndTrafficBeforeItPublishesAlone`, `aRubberStampingReviewer…`, `anEditorsPolicyOnTheBaseline…` |
| A4 | **Partly / needs people** | Two further agents written (`ladders/novel/reply_drafter.loom`, `calendar_slot.loom`) and a 40-category sorter, all run by `NovelAgentsTest` with no new Java. The author wrote them, so the check as stated (someone else tries) is open. |
| A5 | Pass | `LadderVerificationTest.v_A5_theSameLadderUsedByTwoDecisions…` |
| A6 | Pass | Five ladders, one engine; `grep` finds no class named for any of them. |
| A7 | Pass | Reference ladders define their own measures with no import; `StandardLibraryTest` |
| A8 | Pass | `LadderRunTest` scripts and `NovelAgentsTest` sorter define every measure themselves |
| A9 | Pass | `ReferenceLaddersTest.tighteningAReferenceLadder…` (a one-phrase edit) |

## B. Is it readable and checkable as code?

| # | Result | Evidence |
|---|---|---|
| B1 | **Needs people** | Not done. The ladders and `weave ladder describe` output are ready to give to three readers. |
| B2 | Pass | `TypicalMistakesTest`: 20 mistakes, each caught at load with the line and the nearest name. It found a real gap (untyped `remember:` values); fixed. |
| B3 | Partly | `LadderDescriberTest`, `ExprDescriberTest`, `PresentationTest`: the description is generated from the parsed ladder. That a person reads it correctly is the open part of B1. |
| B4 | Pass | `LadderCommandsTest.statusSaysWhereEachScopeStands…`, `testTriesTheLadderOnARecordedHistory…`: each condition shown with its value and what is needed. |
| B5 | Pass | Highlighting: `grammar.test` (a small tokenizer over the real grammar: the ladder words, the three modes after `mode:`, durations, percentages, decimals). Completion: `ladderCompletion.test` (13: ladders after `ladder:`, rungs after `ceiling:` and `slip to`, modes, measures including `dangerous` and the imported standard ones, case fields, collection methods, parts of a ratio, the words of a rung). Underline: `check.integration.test` with the real `weave.jar`: a misspelt measure, an unknown ceiling rung and an unknown agent ladder are errors on the right line, with the guess. Not tried in a running VS Code window; the extension's tests use its fake host. |
| B6 | Pass | `LadderGuideTest`: the guide's examples load with their imports and pass the checks; its commands and options exist. |

## C. Is the record trustworthy?

| # | Result | Evidence |
|---|---|---|
| C1 | Pass | `LadderVerificationTest.v_C1_everyCaseInTheLedgerMatchesWhatTheAgentAndThePersonDid` (60 cases, choices); `LadderRunTest` (artifacts: accept, edit, reject) |
| C2 | Pass | `LadderEngineTest.aLateOutcomeCanCauseASlip…`, `ReferenceLaddersTest` (CI, traffic, take-down); an outcome that has not arrived is unknown, not false (`StandardLibraryTest.reversedIsTrueOnlyFor…`) |
| C3 | Pass | `LadderRunTest.theSameRunTwiceLeavesOneCase…`; `LadderStoresTest` (restart, racing writers); `LadderVerificationTest.v_D10…` |
| C4 | Pass | `LadderStoresTest` on memory, files and a database |
| C5 | Pass | `PreviousReleaseStoreTest`: two real stores written by the release before this work (ledger, level store, audit, run journals): every case reads, every level, epoch and identity is where it was left, the first evaluation moves no one, and nothing is lost. `TrustMigrationTest` adds a forced level and an edited trust block. |
| C6 | Pass | `LadderVerificationTest.v_C6_personalDataIsMaskedBeforeAnythingIsWritten` |
| C7 | Pass | `LadderVerificationTest.v_C7_retentionRemovesTheTextAndKeepsTheCounts` (found that retention did not remove artifacts; fixed) |
| C8 | Pass | `SealTest` (14) and `TamperedStoreTest` (2): a changed record, a record added at the end, one slipped in among the previous release\'s, one removed, a swapped seal, a different key are each found, on memory, file and database ledgers and with 8 concurrent writers; a decision with a record that does not verify asks a person for every case and `status`/`history`/`verify` say so; `verify --accept --reason` lets an operator trust the record as it stands. Limits are stated in the guide: not protection against someone who can read both the key and the ledger, and the last records can be lost. |

## D. Does it fail safe?

| # | Result | Evidence |
|---|---|---|
| D1 | Pass | `LadderEngineTest.aNewScopeStartsOnTheFirstRung…` |
| D2 | Pass | `ExprPropertyTest.allUnknownValuesNeverMakeARateThresholdTrue`, `noDataNeverMakesAClimbTrue` |
| D3 | Pass | `WilsonTest.twentyOutOfTwentyIsNotOneHundredPercentEvidence`; sabotage S11 |
| D4 | Pass | `LadderDecider` fails closed on a ledger that cannot be read (`LadderRunTest.aLedgerThatCannotBeReadNeverMeansTheAgentActsAlone`); S16 |
| D5 | Pass | `LadderVerificationTest.v_D5_…`, `LadderRunTest.anArtifactAgentThatReturnsNothingUsable…`; S17 |
| D6 | Pass | `LadderBoundRunTest`: a decider that stays silent leaves the run paused with nothing decided, one case and one model call across resumes; the answer arriving after a resume decides the case once; at the top rung an escalated case is never acted on while the person is away. |
| D7 | Pass | `LadderEngineTest.anEarnedClimbIsProposedOnceAndWaitsForTheNamedPerson` |
| D8 | Pass | `LadderEngineTest.theCeilingStopsAClimbThatIsEarned`; `LadderCommandsTest` shows "the ceiling is …" |
| D9 | Pass | `LadderAuditTest` |
| D10 | Pass | `LadderVerificationTest.v_D10…` (ledger down: the run goes on, and catches up on resume) |

## E. Does the agent have to earn it, honestly?

| # | Result | Evidence |
|---|---|---|
| E1 | Pass | `LadderVerificationTest.v_E1_…` (nothing in the questions, trace or audit before the answer; released after); S2 |
| E2 | Pass | `LadderAuditTest.aClimbThatCountsEveryAnsweredCaseWarns…` |
| E3 | Pass | `ReferenceLaddersTest.aRubberStampingReviewer…` |
| E4 | Pass | `LadderCommandsTest.anAgentThatAlwaysEscalatesShowsLowCoverage…` |
| E5 | Pass | `LadderVerificationTest.v_E5_…` (forged ledger lines in the agent's reasoning on a file ledger) |
| E6 | Pass | `LadderPolicyTest.samplingIsDeterministic…`, `LadderPolicyBoundaryTest` (1%, 100%, 0%); S9 |
| E7 | Pass | `LadderRunTest.scopesEarnSeparatelyAndALimitSendsACase…` |

## F. Does change reset what it should?

| # | Result | Evidence |
|---|---|---|
| F1 | Pass | `LadderRunTest.aChangedAgentStartsOver…`; S5 |
| F2 | Pass | The identity hashes the prompt, persona, tools, schema and guard (earlier work, `AgentIdentityTest`); `LadderRunTest` changes the prompt |
| F3 | Pass | `LadderRunTest.aProviderThatSilentlyServesAnotherModel…`; S6 (found and fixed: the first reply now records the model) |
| F4 | Pass | `LadderVerificationTest.v_F4_…` (a comment, a layout change and a ladder edit are not a new agent) |
| F5 | Pass | `LadderChecksTest.changingTheAgentIsStated…` |
| F6 | Pass | `LadderChecksTest` (refused for a reachable autonomous rung) |
| F7 | Pass | `ReferenceLaddersTest.tighteningAReferenceLadder…`, `LadderEngineBoundaryTest`; S12 |
| F8 | Pass | same tests (looser: nothing promoted) |
| F9 | Pass | `LadderPolicyTest.theRulesInForce…` (a measure edit changes the fingerprint) |
| F10 | Pass | `LadderEngineTest.aLoweredCeilingDemotesWithThatReason` |
| F11 | Pass | `LadderBoundRunTest.aLadderEditedWhileACaseWaits…`: the case waiting for a person finishes blind under the rules it began under; the next case is asked under the edited ladder. |
| F12 | Pass | `LadderEngineTest` (idempotent re-check) |
| F13 | Pass | `LadderEpochReplayTest`, `LadderTestOnPastCasesCliTest` (a changed prompt in a real `weave run`), `ReplayDurabilityTest` (trust): the new agent starts on the rung the replay of the old agent's cases supports (judged by the ladder's own conditions), at most what the old one had; the report names the rung and why; nothing is written to the ledger beyond the new epoch, nobody is asked. |

## G. Does the old way still work?

| # | Result | Evidence |
|---|---|---|
| G1 | **Needs people** | The whole suite passes (`mvn verify`: 1,594 tests), but twelve files that exist on `main` were changed; [G1-altered-tests.md](G1-altered-tests.md) lists each with the exact lines and the reason. The plan asks a person to approve those reasons. A mistake earlier in this work, now corrected: `LadderRunTest` (the earned-autonomy R3 tests) was overwritten and the results said "unchanged"; it is restored from `main`, ported only where it bypassed the product, and my tests are in `LadderBoundRunTest`. |
| G2 | Pass | `PreviousReleaseLevelHistoryTest`: seven decision scripts (the repository's `scripts/g5/refund.loom` and the guide's trust example among them), 320 cases each, run by the release before this work and by this one: every case ran at the same level and proposed the same, every change of level and every promotion proposal is the same. Found and fixed on the way: the level words of an audited case, which cases are audited, the order of audit and limit. |
| G3 | Pass | Every `.loom` in the repository still loads (the existing repository-wide checks run in the suite); the new words are contextual (`LadderScriptTest`). |
| G4 | Pass | `PreviousReleaseStoreTest`: status, history, outcome, promote, demote, freeze, unfreeze, approve, reject and replay run on a copy of a store the previous release wrote, with the previous release's arguments: the same exit code and the same output, line for line (JSON: every key it printed is there with the same value). Found and fixed: the outcome message, approve's answer for a moot proposal, status for a trust decision (now printed by the original code), the model note in `last change`. |
| G5 | Pass | `LadderVerificationTest.v_G5_…` (a `trust` decision and a ladder decision in one script) |
| G6 | Pass | `LadderChecksTest.aDecisionHasItsRulesInOnePlace` |

## H. Is it safe to give to a publisher?

| # | Result | Evidence |
|---|---|---|
| H1 | Pass | `LadderVerificationTest.v_H1_…`: 19 attempts to read, call, loop or change something, each stopped at load |
| H2 | Pass | `LadderScaleTest` (see J1) |
| H3 | Pass | `LadderAuditTest` |
| H4 | Pass | 20 of 20 ([H4-sabotage.md](H4-sabotage.md)) |
| H5 | Pass | `LadderVerificationTest.v_H5_…` (a 5 MB artifact on a file ledger); `DistanceTest` (two large unlike documents are unknown, not a hang) |
| H6 | Pass | `LadderEngineBoundaryTest`, `LadderCommandsTest`; S3 |

## I. Can an operator run it?

| # | Result | Evidence |
|---|---|---|
| I1 | **Needs people** | The commands answer every question listed (`LadderCommandsTest`); a person who did not write the ladder has not tried. |
| I2 | Pass | `LadderCommandsTest` (reason and name recorded; audit file) |
| I3 | Pass | `ChannelArtifactDecisionTest` (artifact decisions from a phone: the blind rung asks for the person's own work and nothing sent shows the draft or its reasoning; the confirm rung shows the proposal and takes accept, reject or a corrected text) and `ChannelRunTest` (tagged `fragile`, so the default build skips it; run explicitly, 8 of 8). |
| I4 | Pass | `LadderCommandsTest.testTriesTheLadderOnARecordedHistoryAndWritesNothing` |
| I5 | Pass | C5; the earlier engine's files are read as they are |

## J. Performance and size

| # | Result | Evidence |
|---|---|---|
| J1 | Pass | `LadderScaleTest`: 100,000 cases in 50 scopes, one case's ladder step about 180 ms against a bound of 1 s |
| J2 | Pass | `ArtifactReferenceTest`: 100 KB, 1 MB and 5 MB artifacts as proposals and edits are kept once, apart, under the hash of their content (the records of the case stay under 2 KB); read back whole; edit size computed in about a second at 5 MB; retention removes the text from that one place and the measure becomes unknown; also on the file ledger. |
| J3 | Pass | `LadderStoresTest`, `LadderEngineTest.twoWritersNever…` (6 and 8 racing writers on memory, files and a database) |

## K. Documentation and examples

| # | Result | Evidence |
|---|---|---|
| K1 | **Needs people** | Not done. |
| K2 | Pass | `LadderGuideTest` |
| K3 | Pass | The guide's "What it does not do" paragraph; `LadderGuideTest` |

## Completion criteria

1. Every check passes or is deferred with a reason: **no**. Open: A4 and B1 and I1 and K1 (need people), C8, D6, F11, F13, I3 and J2 (see above).
2. A4 and B1 performed by someone other than the author: **no**.
3. H4 shows no unprotected protection: **yes**, 20 of 20.
4. The independent mapping of the plan to the spec: **no**.
5. Existing suite, repository script check and documentation snippets pass in a clean checkout: yes: `mvn verify`, 1,489 tests.
6. The performance bounds are met and recorded: **yes** (J1).

**The work is not done by this plan's own definition.** The remaining items need people, or are gaps listed in [IMPLEMENTATION-NOTES.md](IMPLEMENTATION-NOTES.md).
