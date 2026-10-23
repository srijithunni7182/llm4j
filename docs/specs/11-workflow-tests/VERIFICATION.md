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
| R1 | `RunSelectionTest` (cases side by side give the results and steps a serial run gives; a failure in one does not touch another; a seed repeats an order) | ✅ 11c |
| R2 | `TestFileCheckTest` (unknown agents, classifier answers outside the labels, unknown questions, unknown steps and calls, inputs against the parameters, duplicate names, bad keys, bad `final`, bad matchers; each with file, case and nearest name) | ✅ 11a |
| R3 | `TestFileCheckTest.defaultsMergeIntoEveryCase…`, `TestCommandTest.showCasePrintsTheMergedCase` | ✅ 11a |
| R4 | `TestFileCheckTest.stepsAreListed…`, `TestCommandTest.stepsListsWhatATestCanName…` | ✅ 11a |
| R5 | `WorkflowCasesTest.theResultIsASubsetUnlessExact` (typed answers); scripted typed answers are not yet checked against the agent's `output_schema` when the file is checked | ◐ 11a |
| R6 | `WorkflowCasesTest` (an unscripted agent, an unscripted classifier, a list that runs out, a `when` with no match; `repeat: last`; `--lenient` and its note) | ✅ 11a |
| R7 | `ScriptedWorldTest.tokenUsageIsDeterministic…` (usage from the text length, a case-level `usage:`, `expect.tokens`); budget exhaustion cases are not written yet | ◐ 11b |
| R8 | `WorkflowCasesTest.everyAssertionKindFailsWhenItDoesNotHold` (`models_used`) | ✅ 11a |
| R9 | `StreamScriptTest` | ⬜ 11d |
| R10 | `ScriptedWorldTest.aTaskIsReplacedByItsScriptedResultAndCounted`: a `run` task answers from `tasks:` and is counted, and an unscripted one fails the case; real tasks (`--classes`) are not wired into `weave test` yet | ◐ 11b |
| R11 | `ScriptedWorldTest` (tools are never real; an acting tool's call is recorded as an effect with tool, kind, target and a digest; `effects: none \| {count} \| [list]`; an unscripted non-acting tool fails the case) | ✅ 11b |
| R12 | An approval is a question put to a person: it takes the next `humans:` answer and is recorded in `asked` with kind `approval`; a test of an `approve:` agent is still to be written | ◐ 11b |
| R13 | `ScriptedWorldTest` (humans in order, every question recorded, an unanswered question suspends the run, `resume:` continues from the journal) | ✅ 11b |
| R14 | `ScriptedWorldTest` (backoff of 30 s and 60 s took no real time, `sleep 3h` too; `elapsed_virtual`), `BackoffSleeperTest` (the one seam added: retry backoff goes through the executor's sleeper) | ✅ 11b |
| R15 | `ScriptedWorldTest` (`times`, `on_calls`, `always`, categories; a rate limit nothing says to wait for fails the step at once, as the runtime does; `rate_limits` pausing is not covered yet) | ◐ 11b |
| R16 | `ScriptedWorldTest` (`attempts`, `ran: [ on_failure of … ]`, `final: failed \| completed \| suspended`); `on_exhausted` and `rewind` cases come with 11c | ◐ 11b |
| R17 | `SteerStopTest` | ⬜ 11d |
| R18 | `RouteDiffTest`, `MatchersTest`, `TestCommandTest.aFailingCase…` (golden output): expected, actual, case and first divergence | ✅ 11a |
| R19 | `TestFileCheckTest.aRenamedAgentBreaksTheFileCheck…`; the route is labelled as `weave graph` labels agents and steps | ✅ 11a |
| R20 | `TestFileCheckTest.callsMayNameZeroButOnlyThingsThatExist`, `WorkflowCasesTest` | ✅ 11a |
| R21 | `WorkflowCasesTest.aSmokeCaseAssertsOnlyThatTheRunCompleted` | ✅ 11a |
| R22 | `WorkflowCasesTest.allFailuresOfACaseAreReported…` | ✅ 11a |
| R23 | `MatchersTest.longTextIsShownCut…` (length and hash); the JSON report with the full text comes with 11c | ◐ 11a |
| R24 | Under test every `env.NAME` reads a stand-in (`TestRunner.standIn`), so no secret reaches a report; masking of values a case itself prints is not needed yet | ◐ 11a |
| R25 | `RunSelectionTest.tagsSelectAndExcludeCases`, `TestCommandTest.tagsSelectCasesFromTheCommandLine` | ✅ 11c |
| R26 | `TestCommandTest` (0 all passed, 1 a case failed, 2 could not run) | ✅ 11a |
| R27 | `TestFileCheckTest.aScriptThatDoesNotLoad…`, `WorkflowCasesTest.aMissingSecretIsNotAnError…`, `TestCommandTest.aScriptThatDoesNotCheck…` | ✅ 11a |
| R28 | `TestCommandTest`, `ReportsTest.theTextReportHasOneLinePerCase…` (a line per case, failures expanded, `N passed, M failed in T · nodes covered x/y (k uncovered: see --coverage)`) | ✅ 11c |
| R29 | `TestSpeedTest`: 100 cases in 0.3 s serially (median case 2 ms), 0.17 s on 4 workers | ✅ 11c |
| R30 | `ReportsTest` (JUnit XML parsed by a real XML parser with hostile names and messages, JSON with a version, TAP), `TestCommandTest` (`--format`) | ✅ 11c |
| R31 | `WatchTest` (what is watched; a new, gone or changed file; only a changed test file reruns its cases), `TestCommandTest.watchRuns…`; the loop polls every 500 ms | ✅ 11c |
| R32 | `CoverageTest` (ids are the graph's: `StepResolver` maps every kind of block; nodes and blocks covered; cases add up). Loop exits by condition and by maximum, and a `human_prompt` answer path, are not counted separately | ◐ 11c |
| R33 | `CoverageTest.theHintTellsWhatACaseWouldNeedToDo`, `TestCommandTest.coveragePrints…`: a hint per uncovered node, never an assertion | ✅ 11c |
| R34 | `LoomTestParityTest` | ⬜ 11d |
| R35 | `LoomTestAssertionsTest` | ⬜ 11d |
| R36 | `LoomTestFactoryTest` | ⬜ 11d |
| R37 | The pom is unchanged: YAML is read with the Jackson YAML module the build already has | ✅ 11a |
| R38 | One seam was added, with a default that behaves as before: `DelegateRunner.Host.pause` (retry backoff) now goes through the executor's sleeper, whose default really sleeps; `BackoffSleeperTest` pins both; all existing suites pass unmodified | ✅ 11a, 11b |
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

### 11b (2026-10-23)

- Built: failure injection (`fail:` with `times`, `on_calls`, `always` and the categories rate_limit, timeout, server_error, invalid_output, tool_error, refused, quota) for agents, classifiers, tools and tasks; a virtual clock (the executor's clock and sleeper, so backoff, `sleep` and rate-limit waits take no real time; `clock: { start }`, `expect.elapsed_virtual`); scripted tools (never real; an acting tool, i.e. webhook, email, http, file, shell or sql, records the effect it would have had; model scripts can call a tool with `{ call: Tool, with: {…} }`) and scripted tasks; scripted people (`humans:` in order, an unanswered question suspends the run, `resume:` continues it from the journal); assertions `attempts`, `ran`, `asked`, `effects`, `elapsed_virtual`, `tokens`, and `usage:` to set a call's token counts.
- One seam in the interpreter: the retry backoff slept with `Thread.sleep`, so a test could not make it virtual. It now goes through the executor's `sleeper` (`Host.pause`), whose default is the real sleep.
- A finding about the runtime: a rate limit that nothing says to wait for (no `rate_limits` block) fails the step at once and is not retried, whatever `retry` says. A case now documents it.
- A tool declared in the script is replaced, for the case, by the scripted one (the declaration is removed from the in-memory copy; the file is untouched).
- Not yet: real tasks through `--classes`; `rate_limits` pausing and `rewind` cases; budget exhaustion; a test of an `approve:` agent.
- Tests: `ScriptedWorldTest` (17), `BackoffSleeperTest` (2), three more in `TestFileCheckTest`.

### 11c (2026-10-23)

- Built: coverage by the graph's own ids (a `StepResolver` turns a run's step id, such as `Main/s1/b0/r1.0`, into the node id `n6`; one hook was added to the executor, `setStepListener`, which is told each statement as it starts and adds nothing to the trace or the journal), `--coverage` with a hint for each uncovered node, `--min-coverage`, the `branches` assertion (which arm of an alt ran, and that the other did not), `--tag` and `--exclude-tag`, `--shuffle` with a printed `--seed`, `--parallel` (default the number of processors; `--fail-fast` runs in order), `--format json|junit|tap`, `--watch`.
- Speed: 100 cases in 0.3 s serially (median case 2 ms), 0.17 s on 4 workers.
- Not built: coverage of loop exits and of `human_prompt` answer paths as separate branches; a stable JSON Schema file for the report (the version field is there, the schema is documented in 11d); `--watch` uses polling, not file-system events.
- Tests: `CoverageTest` (8), `ReportsTest` (5), `RunSelectionTest` (5), `WatchTest` (3), `TestSpeedTest` (1), eight more in `TestCommandTest`.
