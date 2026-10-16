# Verification

| # | Criterion | Requirement | Test |
|---|---|---|---|
| V1 | JSON in chatter, in an unclosed fence, with braces inside strings is found | R1.1 | `jsonInsideChatterOrWithoutAClosingFenceIsStillFound` |
| V2 | A cut-off answer is recognised as unfinished | R1.2 | `anAnswerCutOffMidwayIsRecognisedAsUnfinished` |
| V3 | Missing field, wrong enum word, wrong nested type are named by path | R2.1, R2.2 | `aMissingFieldAWrongEnumWordAndAWrongTypeAreNamedByPath` |
| V4 | Extra fields ignored; a number may stand for text | R2.2 | `extraFieldsAreIgnoredAndNumbersMayStandForText` |
| V5 | YAML first, JSON second: the second is used, the model was told why, one `schema_violation` traced | R3.1, R3.2 | `yamlIsRejectedThenAskedAgainWithTheReasonAndTheSecondAnswerIsUsed` |
| V6 | Retries exhausted: the step fails, `on_failure` runs, the variable is unset | R3.3 | `whenTheCorrectionDoesNotHelpTheStepFailsAndItsHandlerRuns` |
| V7 | A step's own `expecting` is enforced | R4 | `aStepsOwnExpectingSchemaIsEnforcedToo` |
| V8 | An agent without a schema still returns whatever it said | R4 | `anAgentWithNoSchemaStillGivesBackWhateverItSaid` |
| V9 | Decision proposals still work (lenient) | R5.1 | `ReplayToolsTest`, `LadderBoundRunTest` |

## Result

Built 2026-10-16. The whole Loom suite was run before and after: the only failures the change caused were the six decision-proposal tests (fixed by R5.1) and three repository-walking tests that tripped over a folder named `.loom` and over imported fragment files (fixed in the tests).
