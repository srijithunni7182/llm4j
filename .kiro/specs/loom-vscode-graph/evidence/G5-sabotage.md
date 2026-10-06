# G5 sabotage run


> The script that produced this table was removed once the evidence was recorded, because it matched exact source text and would need upkeep with every change. It is in git history at commit f2730e8 (scripts/sabotage_*.py).
Commit f2730e8. Each row breaks one rule in the real sources, runs the checks that guard it, and records whether the named test failed. The sources are restored after every row.

| ID | Requirement | What was broken | Test that must fail | Result |
|---|---|---|---|---|
| S1 | V1.2a | Drop the then/else labels on alt branches | anAltLabelsItsTwoBranchesAndBothJoinTheNextStep, theContentFactoryWorkflowHasEightNodesAndEightEdges | caught |
| S2 | V1.4 | Draw no handler blocks | handlersHangOffTheirOwnerOnLabelledEdgesAndRejoinTheMainPath | caught |
| S3 | V2.5 | Skip import cycle detection | anImportCycleEndsWithOneWarningNamingTheFilesAndBothGraphsStillLoad | caught |
| S4 | V2.9 | Let the last definition win when two files define the same workflow | whenTwoImportsDefineTheSameNameTheFirstInRunOrderWinsAndTheOtherIsReported | caught |
| S5 | VS.4 | Log a secret: stop masking key-shaped text | keyShapedTextIsMasked, aKeyPastedIntoAPromptDoesNotAppearInTheJsonOrTheMermaid | caught |
| S6 | V3.4 | Make weave graph reach for the environment | itNeverReachesForAModelAPersonASecretTheEnvironmentOrACommand | caught |
| S7 | V4.2 | Report maps every node kind to the generic statement kind | everyWorkflowInTheRepositoryKeepsItsOldNodesAndEdges, theGraphUsesTheNewKindsAndCarriesSettingsAndTheTraceValidates | caught |
| S8 | V6.5 / VS.1 | Stop escaping < in text drawn into the graph | text from a script is escaped everywhere it is drawn | caught |
| S9 | V10.3b | Ignore the expected path when drawing a run | overlay: nodes on both paths are ok, only expected is missed, only actual is unexpected, neither is none | caught |
| S10 | V10.4 | Drop the visit count | overlay counts visits, so a loop run three times shows ×3 and a node run once shows none | caught |
| S11 | V10.14 | Let the report's copy of the renderer drift from the canonical file | check-graph-render-sync | caught |
| S12 | V8.2 | Remove the debounce: every save runs weave graph at once | ten saves within the debounce make one run | caught |
| S13 | VS.2 | Let the panel open any file it is asked to | opening a source file is allowed for the entry and its imports only | caught |
| S14 | V6.5 | Let the panel page run inline styles | the policy allows nothing but the extension's own files and nonce-marked scripts | caught |

14 of 14 caught.
