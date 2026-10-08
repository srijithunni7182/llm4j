# Tasks: verification plan

Companion to [design.md](design.md) and [test-strategy.md](test-strategy.md). A requirement is **Verified** only when its named test exists, passes, and fails when the behaviour is removed (see section 3).

## 1. Gates

| Gate | Check | Command (repo root) | Result |
|---|---|---|---|
| G1 | Specs written and consistent | review of design, test strategy and this plan | every requirement ID has a row in section 2 |
| G2 | Compiles, whole reactor | `mvn -q -DskipTests -Djacoco.skip=true compile` (all modules, examples included) | exit 0, no errors |
| G3 | New API tests | `mvn -pl ai-agent4j test -Dtest='io.github.llm4j.agent.task.*Test'` | 23 tests pass (TaskTest 5, TaskContextTest 6, TaskResultTest 7, TaskRegistryTest 5) |
| G4 | New Loom tests | `mvn -pl src/loom/ai-agent4j-loom test -Dtest='io.github.llm4j.loom.task.*Test'` | all pass (section 6) |
| G5 | Full regression, each touched module | `mvn -pl <module> verify` | ai-agent4j: 680 tests (2 skipped, as before). src/loom/ai-agent4j-loom: 799 (1 skipped, as before), including the jacoco coverage rules. eval4j: 466. eval4j-report: 46. 0 failures, 0 errors, nothing newly skipped |
| G6 | CTK | `cd src/loom/ctk && mvn test`, plus `TaskCtkConformanceTest` | CTK: 30 tests pass (24 existing, 6 new). The Java runtime's trace equals both canonical task traces |
| G7 | No keys needed | G5 and G6 run with `GOOGLE_API_KEY`, `SERPAPI_KEY`, `GEMINI_API_KEY`, `ANTHROPIC_API_KEY` and `SARVAM_API_KEY` unset (none is set in this environment) | same results |
| G8 | Docs | `TaskDocsTest`, `SecurityDocTest`, link checks, read-through | green; every document in section 4 updated |
| G9 | Negative control | section 3 | each of the 5 controls made its named test fail |

## 2. Requirement to test matrix

Status is computed from the surefire reports of the final run: **Verified** means every named test exists and passed.

| ID | Requirement | Tests (all must pass) | Status |
|---|---|---|---|
| API-01 | `Task` name rule, `getName`, defaults | `TaskTest#nameRule`<br>`TaskTest#defaults` | Verified |
| API-02 | default effect `CHANGES` | `TaskTest#defaultEffectIsChanges` | Verified |
| API-03 | `pure`/`reads`/`changes`/`of` factories | `TaskTest#factories`<br>`TaskTest#aTaskMayThrowChecked` | Verified |
| API-04 | `TaskContext` args, variables, typed accessors, dotted paths | `TaskContextTest#argsVariablesAndIdentity`<br>`TaskContextTest#variablePaths`<br>`TaskContextTest#typedAccessors`<br>`TaskContextTest#requireArg` | Verified |
| API-05 | inputs are immutable deep copies; cycles rejected | `TaskContextTest#immutable`<br>`TaskContextTest#cyclesAreRejectedNotLooped` | Verified |
| API-06 | `TaskResult` shape, reserved keys, JSON-safe check, naming | `TaskResultTest#*` | Verified |
| API-07 | `TaskRegistry` register, duplicate, get, `discovered()` | `TaskRegistryTest#*` | Verified |
| LANG-01 | `run` grammar, options, `on_failure` | `TaskParseTest#grammar`<br>`TaskParseTest#helpfulErrors`<br>`TaskParseTest#nestsAnywhereAStatementDoes` | Verified |
| LANG-02 | argument value forms and typing | `TaskParseTest#argumentForms`<br>`TaskParseTest#keywordsAreValidArgumentAndTaskNames`<br>`TaskRunTest#typedArguments` | Verified |
| LANG-03 | dynamic result variable; static task name | `TaskParseTest#dynamicResult`<br>`TaskParseTest#dynamicTaskNameRejected`<br>`TaskRunTest#dynamicResultVariableInForEach` | Verified |
| LANG-04 | `budget` on `run` rejected; `run` still an identifier; `schedule { run: }` unchanged | `TaskParseTest#budgetRejected`<br>`TaskParseTest#runStillAnIdentifier` | Verified |
| RUN-01 | zero model use | `TaskRunTest#noModelCalls`<br>`TaskRunTest#mixedWorkflowCallsTheModelOnlyForAgentSteps` | Verified |
| RUN-02 | result map bound; usable in `alt` and placeholders | `TaskRunTest#resultMapBound`<br>`TaskRunTest#altOnOutcome`<br>`TaskRunTest#workflowInputsAreVisibleAsVariables`<br>`TaskRunTest#taskReturningNullFailsTheStep` | Verified |
| RUN-03 | unresolved argument fails closed (including a plain unset name), never retried | `TaskRunTest#missingArgumentFailsClosed` | Verified |
| RUN-04 | inputs immutable; result copied | `TaskRunTest#immutableInputs`<br>`TaskRunTest#resultIsCopiedSoLaterMutationDoesNotReachTheWorkflow` | Verified |
| RUN-05 | journal and replay; failed entry; operator retry; file journal round trip | `TaskJournalTest#replay`<br>`TaskJournalTest#entriesHaveKindTask`<br>`TaskJournalTest#replayAcrossAFileJournalGivesTheSameVariable`<br>`TaskJournalTest#failedRecorded`<br>`TaskJournalTest#failureWithoutHandlerIsNotRecordedSoAResumeTriesAgain`<br>`TaskJournalTest#operatorRetry` | Verified |
| RUN-06 | effect protocol, crash window, idempotency key | `TaskJournalTest#crashWindowDefaultPolicyDoesNotRepeat`<br>`TaskJournalTest#crashWindowIdempotentTaskRunsAgainWithTheSameKey`<br>`TaskJournalTest#crashWindowOnUnknownRetryRunsAgain`<br>`TaskJournalTest#crashWindowOperatorRetryOverrides`<br>`TaskJournalTest#crashBetweenEffectDoneAndStepRecordReusesTheResult`<br>`TaskJournalTest#idempotencyKeyRules`<br>`TaskJournalTest#stepIdIsGiven`<br>`TaskJournalTest#eachRoundOfALoopIsItsOwnStepWithItsOwnKey`<br>`TaskRewindTest#keepDoesNotRepeatAnEffectAlreadyDone`<br>`TaskRewindTest#repeatRunsAnIdempotentTaskAgainWithAFreshKey` | Verified |
| RUN-07 | retry classification, backoff, timeout | `TaskRetryApprovalTest#retryMatrix`<br>`TaskRetryApprovalTest#backoffDoublesAndIsSkippedWhenNoRetryHappens`<br>`TaskRetryApprovalTest#aRetrySucceedsAndBindsTheResult`<br>`TaskRetryApprovalTest#timeoutOfAPureTaskIsRetried`<br>`TaskRetryApprovalTest#aTimedOutTaskIsInterruptedNotLeftRunning`<br>`TaskRetryApprovalTest#timeoutOfANonIdempotentChangeIsAnUnknownOutcomeAndNotRetried`<br>`TaskRetryApprovalTest#aFastTaskUnderATimeoutJustRuns`<br>`TaskJournalTest#notPerformedIsSafeToRepeatEvenForANonIdempotentChange`<br>`TaskJournalTest#anUnknownFailureOfANonIdempotentChangeIsNeverRetriedEvenIfAskedTo` | Verified |
| RUN-08 | approval gate | `TaskRetryApprovalTest#approvalYesRunsTheTask`<br>`TaskRetryApprovalTest#noApprovalNeededBelowTheThreshold`<br>`TaskRetryApprovalTest#approvalNoNeverRunsTheTaskAndIsNeverRetried`<br>`TaskRetryApprovalTest#approvalIsAskedOncePerDistinctArgumentsAndNotAgainOnResume`<br>`TaskRetryApprovalTest#approvalCanSuspendTheRunAndAnAnswerResumesIt` | Verified |
| RUN-09 | simulation | `TaskSimulateTest#aSimulatedRunOnlyRunsTasksThatChangeNothing`<br>`TaskSimulateTest#aSimulationDoesNotPoisonTheRealRun`<br>`TaskSimulateTest#aSimulationNeverAsksAPersonAboutATaskItWillNotRun` | Verified |
| RUN-10 | `maxPerRun` | `TaskSimulateTest#maxPerRun`<br>`TaskSimulateTest#maxPerRunDoesNotCountPureTasks`<br>`TaskConcurrencyTest#theCapHoldsAcrossParallelBranches` | Verified |
| RUN-11 | trace and audit; PII masked | `TaskRunTest#traceEvents`<br>`TaskRunTest#auditMasksPii` | Verified |
| RUN-12 | concurrency | `TaskConcurrencyTest#parallelBlockOfTasks`<br>`TaskConcurrencyTest#parallelForEach`<br>`TaskConcurrencyTest#aFailureHandlerDoesNotHoldUpOtherBranches` | Verified |
| VAL-01 | unknown task, wherever a statement can be | `TaskValidationTest#unknownTask`<br>`TaskValidationTest#unknownTaskIsFoundWhereverAStatementCanBe`<br>`TaskValidationTest#weaveCheckFindsTasksOnTheClassPathAndNamesTheUnknownOnes` | Verified |
| VAL-02 | `retry` on a non-idempotent change | `TaskValidationTest#retryUnsafe` | Verified |
| VAL-03 | duplicate argument names | `TaskParseTest#duplicateArgument` | Verified |
| VAL-04 | rewind over a `CHANGES` task | `TaskValidationTest#rewindOverChangesTask`<br>`TaskRewindTest#repeatOverANonIdempotentTaskIsRefusedByTheCheck`<br>`TaskRewindTest#aRewindWithNoStatedPolicyOverAPaymentWarnsAndStillKeepsByDefaultInTheRuntime` | Verified |
| VAL-05 | result variable known to decision checks | `TaskValidationTest#resultVariableKnown` | Verified |
| INT-01 | trace event names and data | `TaskRunTest#traceEvents`<br>`TaskValidationTest#weaveRunExecutesAClassPathTaskWithNoModel` | Verified |
| INT-02 | walker, drift, budgets know `RunStmt` | `TaskValidationTest#walkerSeesOnFailure`<br>`TaskIntegrationTest#scriptDriftSeesAChangedTaskCallBeforeTheForkPoint` | Verified |
| INT-03 | `RunTravel` timeline shows task rows | `TaskIntegrationTest#travelTimelineShowsTaskSteps`<br>`TaskIntegrationTest#travelTimelineShowsAFailedTaskStep` | Verified |
| INT-04 | eval4j trace, assertions and report graph | `WorkflowTaskAssertTest#*`<br>`TaskTraceExportTest#tasksAreNonAgentNodesInTheGraph`<br>`TaskTraceExportTest#aRefusedRefundTakesTheElsePathAndNeverRunsThePayment`<br>`TaskTraceExportTest#anApprovedRefundTakesThePaymentPath` | Verified |
| INT-05 | CTK scenarios and comparator | `TaskCtkConformanceTest#*`<br>`TaskConformanceTest#*` | Verified |
| INT-06 | VS Code grammar has `run` | `TaskDocsTest#docsMentionTasks` | Verified |
| INT-07 | docs updated | `TaskDocsTest#docsMentionTasks`<br>`TaskDocsTest#everyLocalLinkInTheNewAndChangedDocsResolves`<br>`TaskDocsTest#theGuideAnchorsTheDocsLinkToExist` | Verified |
| SEC-01 | tasks unreachable from models | `RefundWorkflowTest#agentCannotCallTask`<br>`RefundWorkflowTest#anAgentCannotListATaskAsATool` | Verified |
| SEC-02 | injected text inert | `RefundWorkflowTest#injectionIsInert`<br>`RefundWorkflowTest#injectedTextInAVariableDoesNotSelectCode` | Verified |
| SEC-03 | immutability and JSON-safe results | `TaskContextTest#immutable`<br>`TaskResultTest#jsonSafe`<br>`TaskRunTest#immutableInputs` | Verified |
| SEC-04 | PII masked in audit and trace | `TaskRunTest#auditMasksPii` | Verified |
| SEC-05 | a script cannot load classes | `TaskParseTest#noClassLoadingSyntax` | Verified |
| DOC-01 | documented examples parse and pass the checks | `TaskDocsTest#documentedExamplesParse`<br>`TaskDocsTest#documentedExamplesPassTheChecksWithTheTasksTheyName`<br>`TaskDocsTest#theRefundFixtureAndTheCtkScriptsParseAndPass`<br>`TaskDocsTest#theGuidesRefundExampleIsTheTestedOne`<br>`SecurityDocTest#everyLoomExampleInTheSecurityGuideLoadsAndPassesTheChecks` | Verified |
| DOC-02 | docs say what the design says | `TaskDocsTest#docsMentionTasks` | Verified |
| SCN-01 | refund bot end to end, including crash and resume | `RefundWorkflowTest#endToEndApproved`<br>`RefundWorkflowTest#endToEndRefusedGoesToAPerson`<br>`RefundWorkflowTest#anOrderIsNotRefundedTwice`<br>`RefundWorkflowTest#crashAfterPaymentDoesNotPayTwice_nonIdempotentTask`<br>`RefundWorkflowTest#crashAfterPaymentDoesNotPayTwice_idempotentTask`<br>`RefundWorkflowTest#theWholeRunReplaysWithNoNewWork` | Verified |
| REG-01 | existing repository-wide script check still passes with `run` scripts present | `RepositoryScriptsTest#n2_everyScriptInTheRepositoryPassesTheChecks` | Verified |

## 3. Negative controls (G9)

Each was applied temporarily to the implementation (or, for 5, to the test), the named test was run and had to fail, then the change was reverted.

| # | Change | Test that must fail | Observed |
|---|---|---|---|
| 1 | skip the journal lookup in `TaskRunner.run` | `TaskJournalTest#replay` | failed: `nothing ran again ==> expected: <1> but was: <2>` |
| 2 | treat an unknown outcome of a non-idempotent change as retryable | `TaskRetryApprovalTest#retryMatrix` | failed: `CHANGES/other` made 3 attempts instead of 1 |
| 3 | pass the live variable map instead of an immutable copy (`TaskValues.freeze` returns its input) | `TaskContextTest#immutable` (API) | failed: `Expected UnsupportedOperationException to be thrown, but nothing was thrown` |
| 4 | resolve a missing argument to an empty string | `TaskRunTest#missingArgumentFailsClosed` | failed: `Expected RuntimeException to be thrown, but nothing was thrown` |
| 5 | register the payment task as an agent tool (and list it in the agent's `tools:`) | `RefundWorkflowTest#agentCannotCallTask` | failed: the payment ran twice (`expected: <1> but was: <2>`) |

## 4. Documentation checklist (INT-07)

- [x] `src/loom/ai-agent4j-loom/LOOM_GUIDE.md`: "Tasks: deterministic steps (`run`)" section (agent-or-task table, writing, registering, syntax, failure and crash behaviour, checks, testing), primitive list, Java bridge
- [x] `src/loom/ai-agent4j-loom/README.md`: `run` in the primitive table
- [x] `src/loom/ai-agent4j-loom/WHY_LOOM.md`: "Code where it must be code"
- [x] `src/loom/ai-agent4j-loom/LOOM_PROMPT.md`: "Deterministic Task (no model)" entry for language models
- [x] `llms.txt` and `src/ai-agent4j/llms.txt`: `Task`, `run`, link to the new wiki page
- [x] `src/ai-agent4j/wiki/Creating-Tasks.md` (new), `Home.md`, `Creating-Custom-Tools.md`: the Java API
- [x] `docs/guide/01-decide-your-agents.md`: "Agent or task?"
- [x] `docs/guide/06-build-the-workflow.md`: "Tasks: the steps with no model"
- [x] `docs/guide/07-validate-and-audit.md`: `weave check` rules for tasks, audit note
- [x] `docs/guide/08-trajectory-tests.md`: asserting task steps
- [x] `docs/guide/10-best-practices.md`: do, don't, checklist
- [x] `docs/guide/README.md`, `.claude/skills/llm4j-workflow-guide/SKILL.md`: stage table
- [x] `docs/security/securing-workflows.md`: "Or take the model out of it: tasks", patterns to avoid
- [x] `docs/articles/LOOM_ARTICLE.md`: primitive table and count
- [x] `README.md` (root): Loom feature list
- [x] `src/loom/ctk/README.md`: task steps in the CTK
- [x] `src/loom/vscode-loom` grammar: nothing to change; `run` is already a keyword in the grammar (checked by `TaskDocsTest#docsMentionTasks`)

## 5. Iteration log

Each row is one run of a gate and what it found. Failures were fixed in the implementation unless the row says the test was wrong.

| # | Gate | Result | What was found and changed |
|---|---|---|---|
| 1 | G1 | specs drafted | the first draft of the matrix and checklist had been pre-marked Verified/ticked: reset to Pending/unticked; statuses are now computed from reports (section 2). The design's example used `alt x == "y"`; Loom requires `alt (x == "y")`: design corrected |
| 2 | G3 | 23/23 pass | none |
| 3 | G4 parser | 9 of 11 pass | two tests used wrong `alt`/`loop` syntax (**test** bug): fixed |
| 4 | G4 run | 12 of 13 | one script used `list`, a keyword (**test** bug): renamed |
| 5 | G4 journal | 12 of 14 | (a) **implementation vs design**: a `TaskNotPerformed` from a non-idempotent change was not retried although RUN-07 says it should be: the loop now retries it and stops only on an unknown outcome; (b) a test picked the wrong journal key and expected a handler step to run twice, although the handler's own step is journaled and replayed (**test** bugs): fixed |
| 6 | G4 run | `missingArgumentFailsClosed` extended | **defect found while reviewing**: an unset variable reads as `""` in Loom (`DefaultVariableContext.getVariable`), so `a = never_set` would have handed the task an empty string (fail open). The executor now checks the variable exists and the path resolves; tests cover `never_set`, `never_set.field` and `x.y.z` |
| 7 | G4 others | mostly first time | validation 8/8 (also through the `weave check` / `weave run` CLI path), retry and approval 11/11, concurrency 3/3, refund scenario 10/10, all first time; simulate 3 of 4: the failure was a wrong test assumption (an unset variable reads as empty text, not null) (**test** bug). Because passing first time proves little, the five negative controls in section 3 were run |
| 8 | G5 eval4j, report | pass | `eval4j-report` needed the changed `eval4j` and loom jars installed first (`-Djacoco.skip=true`, because the loom module's jacoco `check` fails when tests are skipped) |
| 9 | G6 CTK | baseline broken before this change | `src/loom/ctk/pom.xml` pointed at `../ai-agent4j/target/...`, a path that stopped existing when the repository moved into `src/loom/`; changed to `../../ai-agent4j/target/...` so the kit's own tests (24) run again. The kit's runner is a stub (`executeScript` returns an empty trace), so the Java runtime is held to the canonical task traces by `TaskCtkConformanceTest` |
| 10 | G5 regression 1 | loom 790 of 791 | `RepositoryScriptsTest` (every `.loom` file in the repository must pass `weave check`) rejected the new CTK scripts: `Escalate` is not a registered task. It already stands in for host-supplied tools; it now stands in for host-supplied tasks the same way (**test** updated) |
| 11 | G5 verify + G2 | loom 791 pass, jacoco rules pass, reactor compiles | none |
| 12 | review | 3 defects found by reading the executor | (a) the effect claim's lock was held while an `on_failure` block ran, so one branch's handler could stall other parallel branches; (b) a `timeout` did not interrupt the task (`CompletableFuture.cancel` does not interrupt), so a payment could complete after its step had failed; (c) approval was requested for a task a simulation would not run. Each got a **failing test first** (all three confirmed failing), then the fix: claim under the lock and act outside it; `ExecutorService.submit` + `Future.cancel(true)`; simulate check before approval |
| 13 | review | tests added for loops and rewinds | each loop round is its own step with its own idempotency key; `side effects: keep` does not repeat a done effect; `side effects: repeat` repeats an idempotent task with a fresh key; the check refuses `repeat` over a non-idempotent task. All passed as designed |
| 14 | G2 to G9, final | all green | section 1 |

## 6. Evidence: tests per class (final run)

New and extended test classes, with the number of test methods and how many passed in the final `mvn verify` of each module:

```
ConcurrencyTest                      2 tests,   2 passed
RefundWorkflowTest                  10 tests,  10 passed
RepositoryScriptsTest                1 tests,   1 passed
RewindTest                           8 tests,   8 passed
SecurityDocTest                      3 tests,   3 passed
TaskConcurrencyTest                  4 tests,   4 passed
TaskConformanceTest                  6 tests,   6 passed
TaskContextTest                      6 tests,   6 passed
TaskCtkConformanceTest               2 tests,   2 passed
TaskDocsTest                         7 tests,   7 passed
TaskIntegrationTest                  3 tests,   3 passed
TaskJournalTest                     16 tests,  16 passed
TaskParseTest                       11 tests,  11 passed
TaskRegistryTest                     5 tests,   5 passed
TaskResultTest                       7 tests,   7 passed
TaskRetryApprovalTest               12 tests,  12 passed
TaskRewindTest                       4 tests,   4 passed
TaskRunTest                         13 tests,  13 passed
TaskSimulateTest                     5 tests,   5 passed
TaskTest                             5 tests,   5 passed
TaskTraceExportTest                  3 tests,   3 passed
TaskValidationTest                   8 tests,   8 passed
WorkflowTaskAssertTest               5 tests,   5 passed
```

`ConcurrencyTest` and `SecurityDocTest`, `RepositoryScriptsTest` are existing classes that this feature touches or relies on. Totals per module are in section 1.

## 7. Known limits (also in the design, section 11)

- Java cannot force a thread to stop: a timed-out task is interrupted, and a task that ignores interruption keeps running. A timeout on a non-idempotent task that changes things leaves its outcome unknown, and the step is not retried.
- The Loom Conformance Test Kit's own runner is a stub; conformance for the Java runtime is checked by `TaskCtkConformanceTest`. A Python runtime would need its own task registry and must reproduce the canonical-JSON `payload`.
- `weave audit` cannot see what a task's Java does; it only counts task steps.
- The documentation was checked by tests (examples parse and pass `weave check`, links resolve, required phrases are present) and written to match the behaviour verified here; it has not had an independent editorial review.
