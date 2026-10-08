# Why eval4j?

**[eval4j](../../eval4j/)** exists because testing an LLM-powered agent is a different problem
than testing ordinary code, and the tools most Java projects already reach for — `assertEquals`,
mocked HTTP responses — don't cover it. If you've tried to unit-test a `ReActAgent` and found
yourself either asserting on brittle exact strings or giving up on testing the LLM-facing parts
entirely, this is for you.

## 🎯 The Problem: Non-Determinism Defeats `assertEquals`

A traditional unit test works because the function under test is deterministic: same input, same
output, forever. An LLM call breaks that assumption. A prompt tweak, a model version bump, or even
just re-running the same test can change the exact wording of an answer while it stays completely
correct — and can just as easily keep the wording identical while the *content* quietly becomes
wrong. Neither direction is something `assertEquals("42", result.getFinalAnswer())` can tell you
about.

Two different kinds of regressions slip through unnoticed without a dedicated eval layer:

1. **Trajectory regressions** — the agent starts calling the wrong tool, calling tools in the wrong
   order, or skipping tool use it actually needed. This *is* deterministic and testable, but
   ordinary tests rarely check it because `AgentResult.getSteps()` isn't the first thing anyone
   thinks to assert on.
2. **Content regressions** — the final answer becomes subtly wrong, irrelevant, or hallucinated,
   in ways only another model (or a human) could reliably judge.

`eval4j` builds one fluent assertion surface that answers both: real assertions for the first kind,
LLM-as-judge conditions for the second.

## 🧩 The Design Choice: Extend AssertJ and JUnit 5, Don't Replace Them

The Python ecosystem's answer to this problem — `deepeval` — is excellent, but it comes from
`pytest`'s world: `EvalCase` objects, `Metric` classes, and `assert_test(case, [metrics])`, run
through a separate `deepeval test run` command. Porting that shape into Java line-for-line would
mean every eval4j user has to learn eval4j's execution model *before* they can read a single test.

`eval4j` instead treats **AssertJ's `Condition<T>`** and **JUnit 5's `@ParameterizedTest`** as the
right abstractions already — an LLM-as-judge check just is a `Condition`, and a golden dataset just
is a data source for a parameterized test. Nothing new to learn beyond what any Java developer
already knows from testing anything else. This is the same "zero magic" philosophy laid out in
[Why AI Agent4J?](WHY_AI_AGENT4J.md) applied to the testing layer.

## 🆚 Comparison with `deepeval`

| | `deepeval` | `eval4j` |
|---|---|---|
| **API shape** | `EvalCase` + `Metric` list passed to `assert_test` | AssertJ custom assertions + `Condition<T>`, chained fluently |
| **Composing checks** | A Python list of metrics | `.is(a).is(b).is(c)`, or AssertJ's own `allOf`/`SoftAssertions` |
| **Data-driven suites** | `Dataset`/`Synthesizer`, run via a separate CLI | `@ParameterizedTest` + `@MethodSource`, run by the same `mvn test` |
| **IDE support** | Dict-shaped kwargs, duck-typed metrics | Typed builders, full autocomplete, compile-time checking |
| **Test runner** | `deepeval test run` (its own CLI) | Your existing Surefire/Failsafe setup — no new tooling |
| **Dependency weight** | A Python environment plus the `deepeval` package | One more Maven module alongside `ai-agent4j` |
| **Golden datasets** | JSON authored for `EvalCase` | Plain YAML loaded into a data record, still fed through ordinary JUnit — no config-driven runner |
| **Judge-score calibration** | G-Eval with token-logprob-weighted scoring | Reasoning-first, labeled 1-5 rubric rating (normalized to 0-1), plus optional self-consistency sampling — most of the calibration benefit without needing logprobs support in every provider |
| **Judge-call caching** | Built-in result caching | Pluggable `JudgeCache` (in-memory or file-system, opt-in), keyed by the exact content of the call |

## 🧠 What's Actually New Here (Not Just a Renamed Wrapper)

Two things eval4j does that a naive AssertJ setup wouldn't give you by default:

1. **`LlmJudgeCondition`** is a real `org.assertj.core.api.Condition`, not a lookalike — so it
   plugs into `.is(...)`, `.has(...)`, and every AssertJ combinator for free, and it works
   identically whether you're asserting on an `AgentResult`, a raw `LLMResponse`, or a plain
   `String`.
2. **`taskCompletion(input)`** judges the agent's *entire run*, tool use included, not just
   whether the final sentence reads well — a distinction that matters specifically for agents,
   not for plain single-shot LLM calls.

For the full API and worked examples, see [src/eval4j/README.md](../../eval4j/README.md).
