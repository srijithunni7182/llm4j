# Phase 11 test strategy: workflow tests

Status: **draft for review**. Requirements `R1`…`R40` are in [DESIGN.md](DESIGN.md); the traceability matrix is in [VERIFICATION.md](VERIFICATION.md).

## 1. Principles

- **Tests first** for each sub-phase (11a to 11d), failing for the right reason.
- **A test framework must be tested harder than the code it tests.** The commonest failure of a test tool is a test that passes when it should fail. Every assertion kind has a *negative* test: a case built to fail, asserting the reported message.
- **Deterministic and offline.** No model, no network, no real time. Virtual clock everywhere.
- **Dogfooding.** Every script in `src/examples/*` and `samples/` gets a `tests/` folder of real cases written with this tool; those suites are part of the build and are the corpus the DX program reuses (phases 12, 14, 16).
- **Old behaviour is pinned.** `weave run` and `weave eval --mock` outputs on the existing scripts are compared before and after the new seams are added (R38).
- **Hostile text is data.** Agent replies, prompts and test names containing markup, control characters, very long lines and RTL text must not break any report format (JUnit XML escaping, JSON, TAP).

## 2. Levels

| Level | What | Examples | Requirements |
|---|---|---|---|
| **File model** | Parse, defaults merge, every validation error (R2) with its nearest-name hint; duplicate names; unknown step labels; classifier answers outside the enum; `--steps` equals the graph's labels and ids | `TestFileParseTest`, `TestFileValidationTest`, `DefaultsMergeTest`, `StepsListTest` | R1 to R4 |
| **Scripted models** | Each form (text, sequence, when, typed, error, stream), strict failures with the full message, lenient fallback and its marker, token usage determinism, model names recorded, typed answers validated | `ScriptedModelsTest`, `StrictModeTest`, `TypedAnswerTest`, `UsageDeterminismTest` | R5 to R9 |
| **Tools, tasks, effects** | Real vs replaced tasks, tools never real, effects recorded with digests, `approve:` consumes a human answer | `ScriptedTasksTest`, `ScriptedToolsTest`, `EffectsRecorderTest`, `ApproveFlowTest` | R10 to R12 |
| **Humans** | Order, suspension, resume continues from the journal, no effect repeated after resume | `HumansTest`, `ResumeCaseTest` | R13 |
| **Time** | `sleep` advances virtual time, backoff is virtual, schedules fire in virtual time, no real wait (a test fails if wall time exceeds a bound) | `VirtualClockTest`, `BackoffVirtualTest` | R14 |
| **Failures** | Each category drives `retry`, `timeout`, `on_failure`, `rate_limits`, `rewind`; attempts counted; handler `ran` asserted; `final` states | `FailureInjectionTest`, `RetryExhaustedTest`, `OnFailureTakesOverTest`, `RewindCaseTest` | R15, R16 |
| **Assertions** | Every key positive and negative; every matcher; route subsequence vs exact; the diff message; multi-failure reporting; truncation; secret masking | `AssertionsTest`, `MatchersTest`, `RouteDiffTest`, `FailureMessageGoldenTest`, `SecretMaskingTest` | R18 to R25 |
| **CLI** | Exit codes, selection flags, `--check`, `--show-case`, text summary, JUnit/JSON/TAP validity, `--fail-fast`, check failure of the script | `TestCommandTest`, `JunitReportTest`, `JsonReportTest` | R26 to R30 |
| **Isolation / parallel** | Cases do not share state under `--parallel` and `--shuffle`; same results as serial; seed reproducibility | `ParallelIsolationTest`, `ShuffleSeedTest` | R1, R29 |
| **Coverage** | Node and branch coverage on scripts with every statement kind; ids equal graph ids; uncovered list and skeleton text | `CoverageTest`, `CoverageIdsMatchGraphTest` | R32, R33 |
| **Watch** | Change to script/prompt/test reruns only dependents (fake file events) | `WatchTest` | R31 |
| **Java twin** | The same cases through YAML and Java give identical results; `@TestFactory` loading | `LoomTestParityTest`, `LoomTestFactoryTest` | R34 to R37 |
| **Seams** | `weave run` and `weave eval --mock` unchanged after seams are added; no test mode in the interpreter | `RunUnchangedTest`, `EvalMockUnchangedTest` | R38, R39 |
| **Speed** | Median case under 20 ms; 100-case suite under 5 s (tagged performance test) | `TestSpeedTest` | R29 |
| **Dogfood** | The examples' own suites pass; 90 % node coverage on the examples it can reach, tracked | `ExamplesSuiteTest` | goals |
| **Mutation** | Mutating the assertion engine (flip a comparison, drop a check) is caught by the tests | PIT on `wtest/` | quality gate |

## 3. Fixtures

- `src/test/resources/wtest/scripts/` small scripts, one per behaviour (routing, retry, rewind, human, task, steer).
- `src/test/resources/wtest/cases/` the YAML for each, with `expected-failures/` containing deliberately failing cases and their golden messages.
- The existing examples with new `tests/` folders.

## 4. Fault injection

A scripted reply of the wrong type; a `when` list with no `otherwise`; a case that scripts more failures than `retry` allows; a case that leaves a human question unanswered; a script that changes between `--check` and run; a regex pathological pattern in `matches`; two cases writing the same report path; a test file that includes a symlink loop.

## 5. What is deliberately not tested

- The quality of any model answer (that is `weave eval`).
- Real provider behaviour, rate-limit headers or SDK quirks.
- The wording of every possible failure message beyond the golden set; the *structure* (expected, actual, case, step) is tested.
