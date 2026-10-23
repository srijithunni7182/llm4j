# Phase 11 verification plan: workflow tests

Status: **11a done** (file model, check, scripted models and classifiers, assertions, `weave test`). Run top to bottom at the end of sub-phase 11d; the relevant rows after each sub-phase. Results in §7.

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
| R1 | `WorkflowCasesTest.casesShareNothing` (each case has its own executor); `ParallelIsolationTest`, `ShuffleSeedTest` | ◐ 11a serial; 11c parallel |
| R2 | `TestFileCheckTest` (unknown agents, classifier answers outside the labels, unknown questions, unknown steps and calls, inputs against the parameters, duplicate names, bad keys, bad `final`, bad matchers; each with file, case and nearest name) | ✅ 11a |
| R3 | `TestFileCheckTest.defaultsMergeIntoEveryCase…`, `TestCommandTest.showCasePrintsTheMergedCase` | ✅ 11a |
| R4 | `TestFileCheckTest.stepsAreListed…`, `TestCommandTest.stepsListsWhatATestCanName…` | ✅ 11a |
| R5 | `WorkflowCasesTest.theResultIsASubsetUnlessExact` (typed answers); scripted typed answers are not yet checked against the agent's `output_schema` when the file is checked | ◐ 11a |
| R6 | `WorkflowCasesTest` (an unscripted agent, an unscripted classifier, a list that runs out, a `when` with no match; `repeat: last`; `--lenient` and its note) | ✅ 11a |
| R7 | Token usage of a scripted call is deterministic from the text length (`CharsPerTokenEstimator`); a case-level `usage:` and budget exhaustion cases wait for 11b | ◐ 11a |
| R8 | `WorkflowCasesTest.everyAssertionKindFailsWhenItDoesNotHold` (`models_used`) | ✅ 11a |
| R9 | `StreamScriptTest` | ⬜ 11d |
| R10 | `ScriptedTasksTest` | ⬜ 11b |
| R11 | `ScriptedToolsTest`, `EffectsRecorderTest` | ⬜ 11b |
| R12 | `ApproveFlowTest` | ⬜ 11b |
| R13 | `HumansTest`, `ResumeCaseTest` | ⬜ 11b |
| R14 | `VirtualClockTest`, `BackoffVirtualTest` | ⬜ 11b |
| R15 | `FailureInjectionTest`, `RetryExhaustedTest` | ⬜ 11b |
| R16 | `OnFailureTakesOverTest`, `RewindCaseTest` | ⬜ 11b |
| R17 | `SteerStopTest` | ⬜ 11d |
| R18 | `RouteDiffTest`, `MatchersTest`, `TestCommandTest.aFailingCase…` (golden output): expected, actual, case and first divergence | ✅ 11a |
| R19 | `TestFileCheckTest.aRenamedAgentBreaksTheFileCheck…`; the route is labelled as `weave graph` labels agents and steps | ✅ 11a |
| R20 | `TestFileCheckTest.callsMayNameZeroButOnlyThingsThatExist`, `WorkflowCasesTest` | ✅ 11a |
| R21 | `WorkflowCasesTest.aSmokeCaseAssertsOnlyThatTheRunCompleted` | ✅ 11a |
| R22 | `WorkflowCasesTest.allFailuresOfACaseAreReported…` | ✅ 11a |
| R23 | `MatchersTest.longTextIsShownCut…` (length and hash); the JSON report with the full text comes with 11c | ◐ 11a |
| R24 | Under test every `env.NAME` reads a stand-in (`TestRunner.standIn`), so no secret reaches a report; masking of values a case itself prints is not needed yet | ◐ 11a |
| R25 | `TagSelectionTest` | ⬜ 11c |
| R26 | `TestCommandTest` (0 all passed, 1 a case failed, 2 could not run) | ✅ 11a |
| R27 | `TestFileCheckTest.aScriptThatDoesNotLoad…`, `WorkflowCasesTest.aMissingSecretIsNotAnError…`, `TestCommandTest.aScriptThatDoesNotCheck…` | ✅ 11a |
| R28 | `TestCommandTest` (one line per case, failures expanded, summary line); the coverage clause waits for 11c | ◐ 11a |
| R29 | `TestSpeedTest` | ⬜ 11c |
| R30 | `JunitReportTest`, `JsonReportTest` | ⬜ 11c |
| R31 | `WatchTest` | ⬜ 11c |
| R32 | `CoverageTest`, `CoverageIdsMatchGraphTest` | ⬜ 11c |
| R33 | `CoverageSkeletonTest` | ⬜ 11c |
| R34 | `LoomTestParityTest` | ⬜ 11d |
| R35 | `LoomTestAssertionsTest` | ⬜ 11d |
| R36 | `LoomTestFactoryTest` | ⬜ 11d |
| R37 | The pom is unchanged: YAML is read with the Jackson YAML module the build already has | ✅ 11a |
| R38 | No seam was added: the executor already takes a client factory, classifier factory, human interface, clock and sleeper; all existing suites pass unmodified | ✅ 11a (virtual clock in 11b) |
| R39 | `Recorder` reads the trace events; the interpreter does not know a test is running | ✅ 11a |
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

### 11a (2026-10-23)

- Built: `weave test` with `--tests`, `--case`, `--check`, `--steps`, `--show-case`, `--lenient`, `--fail-fast`; the test file model with defaults merged into each case; a checker that validates the files against the script (R2); scripted models for agents (text, list, `when`, typed map) and scripted classifiers (a label per question, `confidence`, `unsure`); strictness (an unscripted call fails the case and names the agent, the call and the task); a recorder over the trace events; assertions `result` (subset, or `exact`), `final`, `route` (ordered subsequence, or `route_exact`), `never`, `calls`, `context`, `models_used`; matchers (`any`, `absent`, `contains`, `matches` under a step limit, `one_of`, `gt`, `gte`, `lt`, `lte`, `between`, `type`, `not`); a route diff.
- How a case runs: the script is loaded afresh, every agent and classifier is given a model name of its own (`scripted:<Name>`) so the scripted factory knows which one is calling, and the real executor runs the workflow; nothing in the interpreter knows it is a test.
- Cases run in a few milliseconds each (the 20 ms median target is measured with the speed test in 11c).
- Decisions taken from DESIGN §12: YAML (1), strict by default (3), the name `weave test` (4). Decision 2 (tasks) and 5 (where `weave init` puts tests) are for 11b and 11d.
- Not yet: failure injection, virtual time, tasks, tools and effects, humans (11b); coverage, watch, parallel, shuffle, JUnit/JSON/TAP, tags (11c); the Java twin, steering and stopping, streams, init templates, the examples' own suites (11d). A `human_prompt` in a script currently fails the case ("a person was asked and the case has no answer").
- Tests: `MatchersTest`, `RouteDiffTest`, `TestFileCheckTest`, `WorkflowCasesTest`, `TestCommandTest` (about 60 tests).
