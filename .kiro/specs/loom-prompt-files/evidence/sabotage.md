# Prompt files: sabotage run

Commit 0d508a6. Each row breaks one rule in the real sources, runs the checks that guard it, and records whether the named test failed. The sources are restored after every row.

| ID | Requirement | What was broken | Test that must fail | Result |
|---|---|---|---|---|
| P1 | R6.1 | Follow a link that leaves the prompt folder | aLinkThatLeavesTheFolderIsRefused | caught |
| P2 | R1.5 | Read a prompt file of any size | aFileOverTheLimitIsRefusedByName | caught |
| P3 | R1.4 | Take 'latest' as the last version in sort order, not the highest number | theLatestVersionIsTheHighestNumberNotTheLastAlphabetically | caught |
| P4 | R4.1 | Leave the prompt text out of an agent's identity | editingThePromptFileMakesADifferentAgent, pinningAnotherVersionIsADifferentAgentAndPinningTheLatestIsNot | caught |
| P5 | R2.5 | Ignore the command-line pin | r2_5_aCommandLinePinBeatsTheScriptsVersion, pinningAnotherVersionChangesTheResearchersPromptAndNothingElse | caught |
| P6 | R2.1 / R6.1 | Accept any text as a prompt reference | r2_1_aMalformedReferenceIsAParseErrorThatShowsTheForm | caught |
| P7 | R3.1 / R3.3 | Run a script whose prompt file is missing instead of failing the load | r3_1_aMissingPromptNamesTheNearestIdsAndWhereToPutTheFile, checkSaysWhatIsMissingAndWhereToPutIt | caught |
| P8 | R2.4 | Forget the folder a script names with prompts: | anImportedFileCannotChooseTheFolder | caught |
| P9 | R6.3 | Put the prompt text in the audit's hash column instead of a hash of it | auditListsWhichPromptEachAgentRunsWithAHashAndNeverTheText | caught |
| P10 | R7.1 | Let the panel open any file once an agent has a prompt file | the panel may open the prompt file of an agent, and no other file in the same folder | caught |
| P11 | R7.2 | Create a prompt file over one that is already there | a prompt that already has a file is not offered, and a lone missing one is created without asking | caught |

11 of 11 caught.
