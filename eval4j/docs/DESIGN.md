# Design philosophy

Why eval4j is shaped the way it is, and where the design and verification notes live.

[← eval4j README](../README.md) · [All docs](README.md)

---

## Why this isn't a Python port

`eval4j` is not `deepeval` translated line-for-line into Java. The Python shape —
`EvalCase(input=..., actual_output=..., ...)` plus `assert_test(test_case, [metric1, metric2])` —
doesn't map cleanly onto how Java developers already test things, so it wasn't kept:

| | `deepeval` (Python) | `eval4j` (Java) |
|---|---|---|
| Structural checks | `EvalCase` object + `Metric` classes | AssertJ custom assertion methods on `AgentResult`/`LLMResponse` directly |
| LLM-as-judge | `GEval`/preset `Metric` subclasses, passed as a list to `assert_test` | A real `org.assertj.core.api.Condition`, passed to the `.is(...)` every AssertJ user already knows |
| Composing several checks | A Python list: `assert_test(case, [metric1, metric2, metric3])` | Fluent chaining (`.is(a).is(b).is(c)`), or AssertJ's own `allOf`/`SoftAssertions` |
| Data-driven suites | A `Dataset`/`Synthesizer` abstraction and a separate `deepeval test run` CLI | Plain JUnit 5 `@ParameterizedTest` + `@MethodSource`, run by the same `mvn test` you already use; the optional `DatasetSynthesizer` just emits plain YAML |
| Golden datasets | JSON/CSV loaded into `EvalCase`s | YAML loaded into a plain data record, still fed through `@MethodSource` — no config-driven runner |
| IDE experience | Dict-shaped kwargs, duck-typed metrics | Typed builders, autocomplete, compile-time checking |

The result is that reading an eval4j test doesn't require learning eval4j's own execution model
first — it requires knowing AssertJ and JUnit 5, which if you're already testing Java, you do.

See also: [Why AI Agent4J?](../../ai-agent4j/wiki/WHY_AI_AGENT4J.md) for the same philosophy applied
to the core library, and [WHY_EVAL4J.md](../../ai-agent4j/wiki/WHY_EVAL4J.md) for a longer treatment
of why agentic Java apps specifically need evals.

## Design & verification documents

| Document | What it is |
|---|---|
| [SPEC](../SPEC-deepeval-parity.md) | The specification for RAG judging, reporting/baselines, conversation metrics, synthesis and comparison, with implementation notes |
| [TEST-STRATEGY](../TEST-STRATEGY-deepeval-parity.md) | How the automated tests are structured |
| [VERIFICATION-PLAN](../VERIFICATION-PLAN-deepeval-parity.md) | How behavior is verified end to end, including the calibration study |
| [VERIFICATION-RESULTS](../VERIFICATION-RESULTS.md) | What was actually run, the results, and the honest gaps |

## Roadmap

Not built yet:
- **Logprob-weighted G-Eval scoring** — needs logprobs on `LLMRequest`/`LLMResponse` in `ai-agent4j` first; the rubric + self-consistency approach covers most of the benefit.
- **Evaluating whole [Loom](../../loom/) workflows** (a `WorkflowResultAssert`).
- **Safety metrics** — PII leakage and red-teaming (bias/toxicity presets exist).
- **Multimodal evaluation** — image/audio outputs.
- **Judge-cost tracking** in reports (needs token usage on `LLMResponse`).
