# Phase 11 verification plan: workflow tests

Status: **not started**. Run top to bottom at the end of sub-phase 11d; the relevant rows after each sub-phase. Results in §7.

## 1. Acceptance criteria

| # | Criterion | Evidence |
|---|---|---|
| A1 | I can test a workflow's branching, failure handling and human steps with no model, no keys and no network | `TestCommandTest`, `ScriptedModelsTest` |
| A2 | I can assert the route taken, the calls made, the effects that would have happened and what a person was asked | `AssertionsTest`, `EffectsRecorderTest`, `HumansTest` |
| A3 | I can make a step fail on a schedule and see `retry`, `on_failure`, `rewind` and rate-limit handling run, without waiting | `FailureInjectionTest`, `VirtualClockTest` |
| A4 | A test cannot pass by accident: an unscripted model or tool call fails the case and names it | `StrictModeTest` |
| A5 | A failed assertion tells me expected, actual, case and step, with a route diff | `FailureMessageGoldenTest`, `RouteDiffTest` |
| A6 | I can see which branches no case reaches and get a skeleton for a case that would | `CoverageTest` |
| A7 | CI runs it: JUnit XML, exit codes, `--min-coverage`, deterministic order and seeds | `JunitReportTest`, `TestCommandTest` |
| A8 | A Java team gets the same thing in JUnit, with the same results as the YAML | `LoomTestParityTest` |
| A9 | Cases are fast: median under 20 ms, 100 cases in under 5 s | `TestSpeedTest` |
| A10 | `weave run`, `weave eval` and every existing script behave exactly as before | `RunUnchangedTest`, `EvalMockUnchangedTest` |
| A11 | Every shipped example has a passing suite | `ExamplesSuiteTest` |
| A12 | Documented, with the init templates generating a passing `tests/` | `InitTemplateTestsPassTest`, docs review |

## 2. Traceability: requirement → tests

| Req | Tests | Status |
|---|---|---|
| R1 | `ParallelIsolationTest`, `ShuffleSeedTest` | ⬜ 11a/11c |
| R2 | `TestFileValidationTest` | ⬜ 11a |
| R3 | `DefaultsMergeTest` | ⬜ 11a |
| R4 | `StepsListTest` | ⬜ 11a |
| R5 | `TypedAnswerTest` | ⬜ 11a |
| R6 | `StrictModeTest` | ⬜ 11a |
| R7 | `UsageDeterminismTest`, `BudgetExhaustionCaseTest` | ⬜ 11a/11b |
| R8 | `ModelsUsedTest` | ⬜ 11a |
| R9 | `StreamScriptTest` | ⬜ 11d |
| R10 | `ScriptedTasksTest` | ⬜ 11b |
| R11 | `ScriptedToolsTest`, `EffectsRecorderTest` | ⬜ 11b |
| R12 | `ApproveFlowTest` | ⬜ 11b |
| R13 | `HumansTest`, `ResumeCaseTest` | ⬜ 11b |
| R14 | `VirtualClockTest`, `BackoffVirtualTest` | ⬜ 11b |
| R15 | `FailureInjectionTest`, `RetryExhaustedTest` | ⬜ 11b |
| R16 | `OnFailureTakesOverTest`, `RewindCaseTest` | ⬜ 11b |
| R17 | `SteerStopTest` | ⬜ 11d |
| R18 | `FailureMessageGoldenTest`, `RouteDiffTest` | ⬜ 11a |
| R19 | `RouteLabelsTest`, `RenameBreaksCheckTest` | ⬜ 11a |
| R20 | `CallsZeroTest` | ⬜ 11a |
| R21 | `SmokeCaseTest` | ⬜ 11a |
| R22 | `MultiFailureReportTest` | ⬜ 11a |
| R23 | `LongTextReportTest` | ⬜ 11a |
| R24 | `SecretMaskingTest` | ⬜ 11a |
| R25 | `TagSelectionTest` | ⬜ 11c |
| R26 | `TestCommandTest` (exit codes) | ⬜ 11a |
| R27 | `CheckFirstTest`, `MissingSecretNotAnErrorTest` | ⬜ 11a |
| R28 | `SummaryLineGoldenTest` | ⬜ 11a |
| R29 | `TestSpeedTest` | ⬜ 11c |
| R30 | `JunitReportTest`, `JsonReportTest` | ⬜ 11c |
| R31 | `WatchTest` | ⬜ 11c |
| R32 | `CoverageTest`, `CoverageIdsMatchGraphTest` | ⬜ 11c |
| R33 | `CoverageSkeletonTest` | ⬜ 11c |
| R34 | `LoomTestParityTest` | ⬜ 11d |
| R35 | `LoomTestAssertionsTest` | ⬜ 11d |
| R36 | `LoomTestFactoryTest` | ⬜ 11d |
| R37 | build check (pom diff) | ⬜ 11a |
| R38 | `RunUnchangedTest`, `EvalMockUnchangedTest` | ⬜ 11a/11b |
| R39 | `RecorderFromTraceTest` | ⬜ 11a |
| R40 | out of scope (recorded) | n/a |

## 3. Quality gates

- All existing tests pass unmodified.
- Line coverage ≥ 90 % in `wtest/`; mutation score ≥ 75 % on the assertion engine and matchers (PIT).
- `scripts/verify-wtest.sh` (new): runs every example suite, checks node coverage floor, JUnit validity.
- No new runtime dependency unless approved in 11a (R37).
- The `weave test` JSON report schema is checked in.

## 4. Manual checks

1. Write, by hand and without reading the spec, three cases for `ticket-triage` from `weave test --steps` output. Time it; note every confusion.
2. Break the script (rename an agent); the test file's failure points at the new name.
3. Run a suite in the editor terminal and in CI (JUnit shows in the CI UI).
4. Show a failing route diff to someone unfamiliar and ask them what went wrong.

## 5. Documentation owed

Wiki "Testing a workflow"; LOOM_GUIDE section "weave test"; the workflow skill's testing stage (before `weave eval`) and chapter 8 of the guide; `weave init` templates; `llms.txt`; Java `LoomTestKit` page and javadoc; README mention (once, at the end of the program).

## 6. Exit checklist

- [ ] Every row in §2 ✅ and every A-criterion has evidence
- [ ] Examples' suites pass and are in the build
- [ ] Docs in §5 updated; roadmap status updated
- [ ] DESIGN §12 questions answered and recorded

## 7. Results

*(per sub-phase: suite timings, coverage per example, mutation score)*
