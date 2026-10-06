# weave eval: sabotage run

Commit c1e41ef. Each row breaks one rule in the real sources, runs the checks that guard it, and records whether the named test failed. The sources are restored after every row.

| ID | Requirement | What was broken | Test that must fail | Result |
|---|---|---|---|---|
| E1 | R3.4 / R6.3 | Count a line nothing judged as met | withNoJudgeARubricLineIsUnjudgedNeverAPass | caught |
| E2 | R6.3 | Let unjudged outrank a failure when a scenario's checks are combined | aFailureBeatsUnjudgedWhichBeatsAPass | caught |
| E3 | R3.5 | Ignore the limit: neither give each scenario what is left nor stop when it is reached | r3_5_aLimitThatIsReachedStopsTheRunAndCountsWhatWasNotRun | caught |
| E4 | R4.1 | Make weave check need a dataset | r4_1_checkAndRunNeverNeedADatasetOrAnEvalFolder | caught |
| E5 | R6.1 | Start a real run without asking | r6_1_aRealRunSaysWhatItWillDoAndAsksFirst_andNoMeansNothingRuns | caught |
| E6 | R3.5 | Let a mock run use the real models | r3_5_aMockRunNeverTouchesTheRealModelsAndJudgesNothing, theShippedNewsletterExampleRunsInAMockRun | caught |
| E7 | R4.5 | Overwrite a dataset file that is already there | r4_5_initCreatesAStarterDatasetAndNeverOverwritesAFile | caught |
| E8 | R6.2 | Put dataset text into the report page unescaped | theHtmlEscapesEverythingFromTheDatasetAndRunsNothing | caught |
| E9 | R2.2 | Stop reading the older RUBRIC:/EXPECT: lines in context | r2_2_theOlderConventionInContextIsReadAsTheFields | caught |
| E10 | R3.9 | Keep the script's own declaration of a tool that has fixtures | aFixtureToolReplacesTheScriptsDeclarationOfThatTool, inAMockRunEveryToolTheAgentsUseIsAnsweredWithoutTheOutsideWorld | caught |
| E11 | R3.2 | Check the content of a mock answer | inAMockRunContentChecksAreUnjudgedBecauseAMockSaysNothingAboutContent | caught |
| E12 | R1.1 | Match dataset files to agents by exact case only | anAgentFileMatchesWhateverTheCaseAndAWorkflowFileMatchesItsName | caught |

12 of 12 caught.
