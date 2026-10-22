# Phase 11 design: workflow tests (`weave test`)

Status: **draft for review**. Layer: Loom (`ai-agent4j-loom`: new package `wtest`, CLI command `weave test`; a Java `LoomTestKit` in the same module). Companion documents:
[TEST_STRATEGY.md](TEST_STRATEGY.md), [VERIFICATION.md](VERIFICATION.md), [program overview](../LOOM_DX.md). Builds on `HarnessExecutor`, the `LLMClientFactory`, `ToolRegistry`, `TaskRegistry` and `HumanInterface`
seams that `weave eval` and the `MockModels` already use, the run journal, and the graph (`GraphBuilder`) for step and branch identity.

## 1. Problem

A Loom script is two things at once: **logic** (which agent runs, which branch is taken, what happens when a step fails, when a person is asked) and **model behaviour** (what an agent answers).
Today a developer can test the second with `weave eval` (golden dataset, optionally real models) and run the first end to end with `--mock`, where every model answers `[mock answer]` or the first enum value.
There is no way to say *"when the classifier says `order_status`, the reply agent is called exactly once and the result is routed to `support`"*, nor *"when the reply agent fails twice with a rate limit, the third attempt succeeds"*,
nor *"when the classifier is unsure, a person is asked and no email is sent"*. The logic of a workflow is exactly what Loom made symbolic, deterministic and checkable, yet the only way to test it is with a model in the loop.

The consequences are the ones Loom exists to remove: branching and failure-handling bugs are found in production, prompt changes cannot be separated from logic changes, and CI cannot run a workflow test for free in milliseconds.

## 2. Goals and non-goals

Goals:

- **`weave test`** runs test cases against a script with **scripted models, tools, tasks and humans**: no model, no network, no keys, no spend, deterministic, in milliseconds per case.
- **Assert on behaviour, not just output**: the result, the route (which steps ran, which branch each `alt` took), how many times each agent/tool/task was called, which effects happened, cost against a cap, what was asked of a person.
- **Inject failures** on a schedule (rate limit on call 1 and 2, a timeout, a malformed answer, a tool error) to test `retry`, `timeout`, `on_failure`, `rewind` and `on_stopped` without waiting.
- **Coverage of the graph**: which nodes and branches no case reached, so an untested `else` is visible.
- **A Java twin** (`LoomTestKit`) with the same semantics for teams that write their tests in JUnit, including the Spring starter.
- **A file format** (`*.test.yaml`) that is simple enough to be written by hand, by the generator ([phase 12](../12-generate/DESIGN.md)) and from a captured production run ([phase 14](../14-production-loop/DESIGN.md)).

Non-goals:

- Not an evaluation of answer *quality*: that stays `weave eval` (golden data, judges, real models). The two are complementary and share fixtures where useful (§11).
- Not a property-based fuzzer for prompts.
- Not a mock of the *provider protocol*: the seam is `LLMClient`, not HTTP.
- Not a change to how `weave run` executes: tests run the same `HarnessExecutor` with substituted collaborators; the interpreter has no test mode.

## 3. The test file

Beside the script (`tests/*.test.yaml` by default, found by `weave test main.loom`; `--tests <dir|file>` overrides):

```yaml
# tests/triage.test.yaml
script: ../triage.loom           # relative to this file; default: the script given on the command line
workflow: Main                   # default: the only workflow, else required

defaults:                        # merged into every case
  models:
    Replier: "Your parcel shipped yesterday."

cases:
  - name: order-status questions get an automatic reply
    input: { message: "where is my parcel?" }
    models:
      Intent: { kind: order_status, urgent: "no", tone: calm, confidence: 0.95 }   # classifier answer, typed
    expect:
      result: { team: support, route: reply }
      calls: { Replier: 1 }
      route: [ intentclassify Intent, delegate Replier, return ]                  # ordered steps that ran (subsequence)
      never: [ human_prompt ]

  - name: an unsure classifier asks a person and sends nothing
    input: { message: "hmm" }
    models:
      Intent: { kind: unsure }
    humans: [ "billing" ]                                    # answers, in order, to human_prompt
    expect:
      result: { team: billing, route: person }
      asked: [ { prompt: contains "classifier was not sure" } ]
      calls: { Replier: 0 }

  - name: the classifier being down falls back to a person
    input: { message: "x" }
    fail:
      Intent: { times: 3, category: rate_limit }              # attempts 1..3 fail; retry 2 means 3 attempts, so on_failure runs
    humans: [ "support" ]
    expect:
      result: { route: person }
      attempts: { Intent: 3 }
      ran: [ on_failure of intentclassify Intent ]

  - name: a reply that fails twice then succeeds
    input: { message: "where is my order" }
    models: { Intent: { kind: order_status, urgent: "no", tone: calm } }
    fail:
      Replier: { times: 2, category: timeout }
    expect:
      calls: { Replier: 3 }
      result: { route: reply }
```

### 3.1 Test file requirements

- **R1** Cases are independent: each starts from a fresh executor, journal and clock. Order does not matter; `--shuffle` runs them in random order to prove it.
- **R2** A test file is validated before anything runs (`weave test --check`): unknown agents/tools/tasks/steps named in `models`, `fail`, `route`, `calls`; a `result` field the workflow cannot return; a classifier answer outside the declared enum; duplicate case names. Errors name the file, case and nearest valid name.
- **R3** `defaults` merge into each case: maps merge by key (case wins), lists are replaced. The merged case is printable (`weave test --show-case NAME`).
- **R4** Names in a case use the script's own names (`Replier`, `Intent`) and graph step labels (`delegate Replier`, `intentclassify Intent`); `weave test --steps main.loom` lists every addressable step label and node id, so authors never guess.

## 4. Scripted collaborators

### 4.1 Models (R5 to R9)

`models:` maps an **agent or classifier name** to what it answers. Forms:

| Form | Meaning |
|---|---|
| `Replier: "text"` | Every call answers this text |
| `Replier: [ "first", "second", "third" ]` | Answers in order; a call beyond the list is an error (strict, R6) unless `repeat: last` is set on the case |
| `Replier: { when: [ { contains: "refund", reply: "..." }, { matches: "^order \\d+", reply: "..." }, { otherwise: "..." } ] }` | Chooses by the task text the agent received (after interpolation and prompt assembly) |
| `Intent: { kind: order_status, urgent: "no", tone: calm, confidence: 0.95 }` | A classifier answer, typed against the classifier's questions; `confidence` is used by the `confidence at least ...` rule |
| `Auditor: { status: SECURE, issues: [] }` | An agent with `output_schema`: the mapping is the typed answer; the harness serialises it as the model would |
| `Writer: { error: { category: rate_limit } }` | Fails as that category (see §4.5) |

- **R5** Agents with an `output_schema` or `expecting` get **typed** answers that are validated against the schema when the file is checked; a case that scripts an invalid answer on purpose says so (`invalid: true`) to test the harness's coercion and repair path.
- **R6** **Strict by default.** A model call that no script entry answers fails the case with the agent name, the call number and the task text, so a test cannot pass by silently getting a default reply. `--lenient` (or `strict: false` on a case) falls back to `MockModels`' neutral reply and reports the case as *lenient-passed*.
- **R7** Token usage of a scripted call is deterministic: from the text length by the runtime's estimator unless the case gives `usage: { in: 120, out: 40 }`, so cost and budget assertions are meaningful and `budget` exhaustion can be tested.
- **R8** The model name the script asks for is recorded; a case may assert `models_used: { Replier: "claude-haiku-5-5" }`, so a refactor that swaps a model is visible. No provider code is loaded.
- **R9** Streaming and `steerable` agents are scripted by chunks: `Replier: { stream: ["Hel", "lo"] }`, with `steer:` events (§4.6).

### 4.2 Tasks and tools (R10 to R12)

- **R10** `run` tasks are **real by default** (they are deterministic Java and the point of `run` is that they are trustworthy); a case may replace one: `tasks: { RefundPolicy: { verdict: allow } }` or `{ error: ... }`. Replaced tasks are recorded as calls.
- **R11** Tools are **never real** in `weave test`: every tool is replaced by a scripted result (`tools: { WebSearch: [ { returns: "..." } ] }`), or by `returns: ok` if the case does not care. A call to a tool with no scripted result fails strictly (R6). The six generic effectful kinds (`webhook`, `email`, `http`, `file`, `shell`, `sql`) record their would-be effect (target, payload digest) in the case's **effects list** instead of performing it, so `expect.effects` can assert "exactly one email to the billing address, none to the customer".
- **R12** `approve:` is honoured as in a real run: a tool call that needs a person's yes produces an `asked` entry and consumes the next `humans:` answer (`yes`/`no`).

### 4.3 Humans (R13)

- **R13** `humans: [ ... ]` answers `human_prompt`, `approve` and `decide` questions in order. An unanswered question ends the case as **suspended** (not failed) when `expect.suspended: true` is set, so the pause-and-resume behaviour of a workflow is testable: `resume: [ "yes" ]` continues the same case from its journal.

### 4.4 Time (R14)

- **R14** The case runs on a **virtual clock**. `sleep 5m` advances it instantly; schedules fire in virtual time; `clock: { start: "2026-10-01T07:00:00Z" }` fixes "now". A case can assert `elapsed_virtual`. No test waits on real time.

### 4.5 Failures (R15, R16)

- **R15** `fail:` injects failures per target (agent, classifier, tool, task): `{ times: N }` (the first N attempts), `{ on_calls: [2, 5] }`, `{ always: true }`, with `category` from the runtime's real set (`rate_limit`, `timeout`, `server_error`, `invalid_output`, `tool_error`, `refused`, `quota`) so `retry`, backoff (virtual), `timeout`, `on_failure`, `rate_limits` pausing and rewind-on-late-failure paths run for real.
- **R16** `expect.attempts`, `expect.ran` (`on_failure of ...`, `on_exhausted of ...`) and `expect.final: failed | completed | suspended | stopped` assert which handler took over and how the run ended.

### 4.6 Steering and stopping (R17)

- **R17** `steer:` events (`after_call: { Researcher: 2 }`, text) and `stop:` (`at: step`, mode) test `steerable` agents and `on_stopped`. Out of the first delivery (11d); specified here so the format leaves room.

## 5. Assertions (R18 to R25)

| Key | Asserts | Notes |
|---|---|---|
| `result` | The workflow's returned value, field by field | Subset match by default; `exact: true` for equality; values may use matchers (below) |
| `final` | How the run ended | default `completed` |
| `route` | Steps that ran, as an **ordered subsequence** by default (`route_exact: true` for the full list) | Labels from `--steps`; loops list each iteration |
| `branches` | `alt` choices: `{ n7: then }` or by label | The same ids as the graph and `weave review` |
| `calls` / `attempts` | Counts per agent, classifier, tool, task, including 0 | A listed name with no entry is an error if it does not exist |
| `never` | Steps or targets that must not appear | e.g. `never: [ tool email ]` |
| `asked` | Questions put to a person, in order, with matchers | prompt text, kind (`human_prompt`, `approve`, `decide`) |
| `effects` | What effectful tools would have done (target, kind, digest, count) | R11 |
| `models_used` | Model names requested per agent | R8 |
| `tokens` / `cost` | `{ max: 5000 }`, `{ max: 0.01 }` using the price table | R7; cost needs a price file or a case-level `prices:` |
| `context` | Variables at the end: `{ intent.kind: order_status }` | After the run, read-only |
| `journal` | Entries by kind and step, replay-safety (no effect twice after resume) | for resume cases |
| `trace` | A raw trace event matches | escape hatch; discouraged |

**Matchers** (usable wherever a value is expected): literal; `any`; `contains: "x"`; `matches: "regex"`; `one_of: [..]`; `gt/lt/between`; `type: number`; `absent`; `not: matcher`. Regexes are run with a step limit to avoid pathological patterns.

- **R18** A failed assertion reports **expected, actual, the case, the step** and, for `route`, a compact diff of the two routes with the first divergence marked. The failure message never requires opening the YAML to understand.
- **R19** `route` is computed from the executor's step events and addressed by the graph's labels and ids; a rename of an agent changes the label and the test file fails `--check` with the new nearest name (R2), not silently.
- **R20** `calls: { X: 0 }` is allowed and meaningful; naming an agent that does not exist is a check error.
- **R21** `expect` may be omitted for a smoke case; the case then asserts only `final: completed` and no strict-mode failure.
- **R22** Multiple failures in one case are all reported, not only the first.
- **R23** `result` values that are long text are shown truncated with their length and a hash; full text is in the JSON report.
- **R24** Matchers on text never log secrets: values from `env.` are masked in every report.
- **R25** A case may declare `tags: [ smoke, failure ]`; `--tag` and `--exclude-tag` select.

## 6. The command

```
weave test [script.loom] [options]
  --tests <file|dir>          test files (default: tests/ beside the script, *.test.yaml)
  --case <name|glob>          run matching cases
  --tag <t> / --exclude-tag <t>
  --check                     validate the test files only; run nothing
  --steps                     list addressable step labels and node ids, then exit
  --show-case <name>          print the merged case
  --coverage                  print uncovered nodes and branches
  --min-coverage <pct>        exit 1 below this node coverage (default: none)
  --lenient                   allow neutral default replies (reported)
  --shuffle [--seed N]        random order, reproducible
  --watch                     rerun on save (script, prompts, tests)
  --format text|json|junit|tap     (default: text; junit for CI)
  --fail-fast
  --parallel N                cases are independent; default: CPU count
  --prices <file> / --loot <file> / --no-env     as elsewhere
```

- **R26** Exit codes: `0` all passed; `1` any failed; `2` could not run (bad file, script does not check). A script that fails `weave check` fails every case with the check error rather than running (R27).
- **R27** `weave test` runs the full load-time check first. Secrets that the script requires (`env.X`) are **not needed**: a missing secret is not an error under test (models and tools are scripted), but a script that fails parsing or validation is.
- **R28** Output (text) is one line per case with a mark, time and name, failures expanded, and a summary: `12 passed, 1 failed, 0 skipped in 0.4 s · nodes covered 18/21 (3 uncovered: see --coverage)`.
- **R29** Speed: a case with no real task or file I/O completes in under 20 ms median on CI hardware (target); a suite of 100 cases in under 5 s including JVM start.
- **R30** `--format junit` produces JUnit XML (one testcase per case, failure body = the R18 message) so any CI shows results natively; `--format json` is versioned and documented.
- **R31** `--watch` re-runs only cases whose script, imports, prompts or test file changed; it is the primary loop for editing a prompt-free workflow.

## 7. Coverage (R32, R33)

- **R32** Node coverage = nodes of the script's graph executed by at least one case / all nodes; branch coverage = each `alt` arm, `loop` exit (by condition and by `max`), `on_failure`/`on_exhausted`/`on_stopped` handler and `human_prompt` answer path. Computed from the same node ids as `weave graph`, so coverage can later be painted on the graph (phase 10 overlay).
- **R33** `--coverage` prints uncovered items with their line and a suggested case skeleton (`# to reach this: Intent answers kind: complaint`); it never invents a passing assertion.

## 8. The Java twin: `LoomTestKit` (R34 to R37)

For JUnit users (and the Spring starter), the same engine, fluent:

```java
LoomTest t = LoomTest.of(Path.of("src/main/resources/triage/main.loom"))
        .classifier("Intent", Map.of("kind", "order_status", "urgent", "no", "tone", "calm", "confidence", 0.95))
        .model("Replier", "Your parcel shipped yesterday.")
        .failing("Replier", Failure.times(2, Category.TIMEOUT));

LoomRun run = t.run("Main", Map.of("message", "where is my parcel?"));

assertThat(run).completed()
        .returned("team", "support")
        .called("Replier", 3)
        .tookBranch("alt@line37", Branch.THEN)
        .neverSent("email")
        .costBelow("0.01");
```

- **R34** `LoomTest` uses the same scripted collaborators, strictness, virtual clock and matchers as `weave test` (one implementation; the CLI is a thin loader of YAML into `LoomTest` calls). A parity test runs the same case through both and compares results.
- **R35** Assertions are AssertJ-style (`assertThat(run)…`) in the same package family as the existing `eval4j` assertions so a team using eval4j finds it familiar; failure messages are the R18 messages.
- **R36** `LoomTest` can load a `*.test.yaml` and run it (`LoomTest.cases(path)` returns a JUnit 5 `@TestFactory` stream), so YAML and Java cases coexist in one suite.
- **R37** No dependency is added to `ai-agent4j-loom`'s runtime scope; YAML parsing uses a library already in the build (to be confirmed in 11a; otherwise a small parser limited to the format's subset).

## 9. Architecture

```
wtest/
  TestFile, TestCase, Expect                 the model (records), parsed from YAML, validated against the graph (R2)
  ScriptedModels  (implements LLMClientFactory)   answers by rule; typed answers; failures by category; usage; strictness
  ScriptedTools, ScriptedTasks                  ToolRegistry/TaskRegistry decorators; effects recorder
  ScriptedHumans (implements HumanInterface)    ordered answers; suspend on none
  VirtualClock                                  Clock + the executor's sleep/backoff hooks
  Recorder                                      collects step events, branches, calls, attempts, asked, effects, usage
  Matchers, Assertions, RouteDiff
  Coverage                                      node/branch coverage over GraphResult
  TestRunner                                    runs cases in parallel with isolated executors
  reports: Text, Json, Junit, Tap
cli/TestCommand
LoomTest, LoomRun (Java twin)
```

- **R38** Collaborators plug in through the existing seams (`LLMClientFactory`, `ToolRegistry`, `TaskRegistry`, `HumanInterface`, a clock). If a seam is missing (virtual time for `sleep` and backoff; an effects recorder for the generic tools), 11a adds it **without changing default behaviour**; a test pins that `weave run` is byte-for-byte unchanged.
- **R39** The `Recorder` reads the existing trace events (`--trace`'s source) and the journal; it does not need the interpreter to know a test is running.

## 10. Relationship to `weave eval` and `--mock`

| | `weave test` (this phase) | `weave eval` | `weave run --mock` |
|---|---|---|---|
| Question | Does the logic do the right thing for a given model answer? | Do the real agents answer well? | Is it wired up? |
| Models | scripted | real (or mock for wiring) | neutral mock |
| Keys / spend | none | yes, capped | none |
| Speed | milliseconds | seconds to minutes | seconds |
| Deterministic | yes | no | yes |
| Where it runs | every save, every commit | before release, on prompt changes | first run |

- **R40** `weave eval` fixtures can name a `test` case as their scripted preamble in a later phase (out of scope here); for now the two share only the graph ids.

## 11. Sub-phases

| | Delivers | Tests |
|---|---|---|
| 11a | Test file model + validation (`--check`, `--steps`), `ScriptedModels` (text, sequence, when, typed, errors), strictness, `Recorder`, assertions `result/final/route/calls/never`, text report, exit codes | `TestFileValidationTest`, `ScriptedModelsTest`, `StrictModeTest`, `AssertionsTest`, `RouteDiffTest`, `TestCommandTest` |
| 11b | Failure injection, virtual clock, tasks/tools/effects, humans, `asked`/`effects`/`attempts`/`ran`, suspended/resume | `FailureInjectionTest`, `VirtualClockTest`, `EffectsRecorderTest`, `HumansTest`, `ResumeCaseTest` |
| 11c | Coverage, `--watch`, `--parallel`, `--shuffle`, JUnit/JSON/TAP, tags | `CoverageTest`, `JunitReportTest`, `ParallelIsolationTest`, `WatchTest` |
| 11d | Java twin `LoomTestKit`, parity test, steering/stop, docs, `weave init` templates gain `tests/` | `LoomTestParityTest`, `SteerStopTest`, `InitTemplateTestsPassTest` |

## 12. Decisions to confirm (open questions)

1. **File format.** YAML (readable, matches the repo's golden dataset files). Alternative: a Loom-flavoured syntax in the script folder (`test "name" { ... }`). Recommendation: YAML now; a language-level `test` block can come later without breaking the files.
2. **Real tasks by default** (R10). A task that performs I/O would then run in tests. Recommendation: tasks run real, but a task declared with `reach: writes|sends` is replaced by a recorded effect unless the case says `real: true`. Confirm.
3. **Strict by default** (R6). It makes first cases a little more work and makes every passing test mean something. Confirm.
4. **Name.** `weave test` vs `weave check --cases`. Recommendation: `weave test`.
5. **Where tests live in `weave init` projects.** `src/test/resources/loom-tests/` for the Maven layout and `tests/` for `--flat`. Confirm.
