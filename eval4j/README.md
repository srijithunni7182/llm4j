<img src="eval4j_logo.svg" align="right" width="200" alt="eval4j Logo">

# eval4j

**A ground-up evaluation framework for [ai-agent4j](../ai-agent4j/) — built on AssertJ and JUnit 5, not ported from Python.**

`eval4j` lets you write tests that check whether your agents, tools, and LLM calls actually behave
correctly, not just whether your Java compiles. It fills the gap the Python ecosystem covers with
tools like `deepeval` — but does it with primitives Java developers already use every day.

---

## Why evaluate your agents?

An LLM call is not deterministic. A prompt tweak, a model upgrade, or an unrelated refactor can
silently change what your agent answers, which tools it picks, or whether it hallucinates — and a
normal `assertEquals` can't catch any of that, because there's no single "correct" string to
compare against. `eval4j` gives you two matching tools for that problem:

- **Fluent assertions** for the parts of an agent run that *are* deterministic and worth
  regression-testing precisely — which tools were called, in what order, whether the run
  completed, how confident the agent was.
- **LLM-as-judge conditions** for the parts that aren't — is this answer actually correct, is it
  relevant, is it grounded in the retrieved context — by asking another LLM call to grade it
  against a stated criterion and a threshold, the same way a human reviewer would.

Both live inside your normal `mvn test` run. There's no separate eval CLI, no YAML-driven test
runner replacing your test method, and no new mental model beyond "write a JUnit test."

---

## Install

Add the module to your project (matching `ai-agent4j`'s version):

```xml
<dependency>
    <groupId>io.github.srijithunni7182</groupId>
    <artifactId>eval4j</artifactId>
    <version>5.0</version>
</dependency>
```

If you're working inside this monorepo, `eval4j` is already wired into the root aggregator
[pom.xml](../pom.xml) alongside `ai-agent4j` and `ai-agent4j-addons`.

---

## Quick example

```java
import static io.github.llm4j.eval.assertions.AgentAssertions.assertThat;
import static io.github.llm4j.eval.judge.LlmJudgeCondition.llmJudged;

AgentResult result = agent.run("What is 15% of 240?");

assertThat(result)
    .completedSuccessfully()
    .usesToolsExactly("calculator")
    .hasFinalAnswerContaining("36")
    .is(llmJudged("Correctness")
            .criteria("The answer correctly computes 15% of 240 and states it plainly")
            .judge(judgeClient)
            .threshold(0.7)
            .build());
```

Everything on the left of `.is(...)` is a deterministic, free assertion backed directly by the
real fields on [`AgentResult`](../ai-agent4j/src/main/java/io/github/llm4j/agent/AgentResult.java)
— see [ReAct-Agent-Guide.md](../ai-agent4j/wiki/ReAct-Agent-Guide.md) for what `AgentResult` and
its `AgentStep`s actually represent, and
[Creating-Custom-Tools.md](../ai-agent4j/wiki/Creating-Custom-Tools.md) for how tool names get
chosen. `.is(...)` is the one call that costs an LLM request, because "correctness" genuinely needs
a judge.

---

## API walkthrough

### 1. Fluent assertions — `AgentAssertions` / `LlmResponseAssertions`

`AgentResultAssert` (via `AgentAssertions.assertThat(AgentResult)`) is a standard AssertJ custom
assertion — it extends `AbstractObjectAssert`, so it chains with every AssertJ method you already
know (`.isNotNull()`, `.satisfies(...)`, `.is(Condition)`), plus these eval4j-specific methods:

| Method | Checks |
|---|---|
| `hasFinalAnswerContaining(String)` | Final answer contains a substring (case-insensitive) |
| `hasFinalAnswerMatching(Pattern)` | Final answer matches a regex |
| `usesTool(String)` | The tool was **attempted** at some point (case-insensitive) — see the warning below |
| `usesToolSuccessfully(String)` | The tool actually **executed** (`StepOutcome.EXECUTED`), not just attempted |
| `hadActionRejected(String)` | A human reviewer rejected an attempted call via Human-in-the-Loop approval |
| `hasStepOutcome(String, AgentResult.StepOutcome)` | Some call to the tool has exactly the given outcome |
| `usesToolsExactly(String...)` | Exactly this sequence of tools, in this order, no more |
| `usesToolsInOrder(String...)` | These tools were called in this relative order — other tool calls may happen in between (an ordered-subsequence check, useful for trajectory testing when extra steps are allowed) |
| `usesToolWithArgument(String, String, Object)` | Some call to the tool had the given argument key/value |
| `extractingToolArgument(String, String)` | AssertJ `ListAssert` of that argument's value across every call to the tool |
| `usesNoTools()` | No tools were called |
| `isConfidentAbove(double)` | `AgentResult.getConfidence()` exceeds a threshold |
| `completedSuccessfully()` | The agent produced a final answer without hitting max iterations |
| `followedProtocol()` | The model's raw output parsed as the expected format on every iteration — stricter than `completedSuccessfully()`, which still passes when the model ignored the protocol and its whole raw output got treated as the final answer |
| `completesWithinIterations(int)` | Iteration budget (an "efficiency" check) |
| `usesFewerTokensThan(int)` | `AgentResult.getUsage().getTotalTokens()` is under a budget |
| `hasRedundantActionCountAtMost(int)` | Looping/dithering budget — repeated identical action+input pairs |
| `hasValidJson(Class<?>)` | Final answer parses as the given JSON shape |

> **`usesTool` only means the model *requested* that action — not that it ran.** `ReActAgent`
> records a step whenever the model asks for an action, including one that named an unknown tool,
> was blocked as a repeated/looping call, or — notably for a Human-in-the-Loop workflow — one a
> human reviewer **rejected**. A rejected `send_email` call still satisfies `usesTool("send_email")`.
> If success (or rejection specifically) is what you're checking, use `usesToolSuccessfully`/
> `hadActionRejected`/`hasStepOutcome` instead.

`LlmResponseAssertions.assertThat(LLMResponse)` mirrors this for a single raw LLM call (not behind
a `ReActAgent`) — `hasContentContaining`, `hasFinishReason`, `usesFewerTokensThan` (reads
`LLMResponse.TokenUsage`, the natural place for a token-budget check since a `ReActAgent` run can
span several underlying LLM calls with no single aggregate total), and `hasValidJson`.

`ConversationAssertions.assertThat(List<AgentResult>)` gives you `hasTurnCount`,
`allCompletedSuccessfully`, and `.turn(index)` (returns an `AgentResultAssert` for that turn) for
multi-turn conversations.

### 2. LLM-as-judge — `LlmJudgeCondition` / `LlmJudgePresets`

**Use a different (ideally stronger) model as the judge than the one being tested.** Grading an
agent's output with the same model/client that produced it means the model is partly grading its
own work — fine for a quick smoke test (the integration test in this repo does exactly that,
deliberately, to keep the example to one API key), but for real suites, wire a separate `LLMClient`
in as the judge.

`LlmJudgeCondition` is a real `org.assertj.core.api.Condition`, built with:

```java
llmJudged("Conciseness")
    .criteria("The answer is three sentences or fewer and directly answers the question")
    .judge(judgeClient)     // any LLMClient — Gemini, Sarvam, Ollama, a routing client, a test double
    .threshold(0.7)         // default 0.5 if omitted
    .input(originalQuestion)          // optional
    .expectedOutput(knownGoodAnswer)  // optional
    .context(List.of("fact one"))             // optional
    .retrievalContext(List.of("chunk one"))   // optional
    .build();
```

Because it's a plain `Condition`, it works with `.is(...)`/`.has(...)` against an `AgentResult`, an
`LLMResponse`, or even a bare `String` — and it composes with AssertJ's own `Condition` combinators
(`Assertions.not(...)`, `allOf(...)`, `anyOf(...)`) and with `SoftAssertions`, for free.

Internally it prompts the judge with the same fenced ` ```json ` convention `ReActAgent` itself
already uses to parse tool calls, and wraps judge-call failures (network errors, unparseable
responses) in a `JudgeEvaluationException`, distinct from a normal failed assertion.

**The output being graded is untrusted.** It's the agent's own (possibly wrong, possibly
adversarial) output, so the judge prompt wraps it — and context/retrieved-context/trajectory — in
explicit `<<<BEGIN ...>>>`/`<<<END ...>>>` delimiters and instructs the judge to treat everything
inside as data, never as instructions, even if it contains text that looks like grading
instructions.

**`taskCompletion(input)` includes the full step trajectory**, not just the final answer — set
`.includeTrajectory(true)` on any custom `llmJudged(...)` condition if a criterion needs the same.
Other presets stay final-answer-only by default so the judge's focus isn't diluted with irrelevant
tool-call noise.

**Scoring is rubric-based, not a raw float.** LLMs are well documented to be poorly calibrated when
asked to directly output a continuous "probability" — they cluster around round numbers like 0.7
or 0.8 regardless of how well the criterion is actually met. So instead of `{"score": 0.83}`, the
judge is asked to reason step by step and then pick a labeled 1-5 rating:

```
1 = Completely fails the criterion      4 = Mostly satisfies, with only minor gaps
2 = Mostly fails, minor alignment only  5 = Fully and clearly satisfies the criterion
3 = Partially satisfies the criterion
```

That rating is normalized to a 0.0-1.0 score (`(rating - 1) / 4.0`) for the threshold comparison,
so nothing about the public API changes — `.threshold(0.7)` still means the same thing. This is the
same insight behind the original G-Eval paper's methodology, short of needing token
log-probabilities from the provider (which would require adding logprobs support to
`LLMRequest`/`LLMResponse` in `ai-agent4j` itself, and isn't available consistently across
Gemini/Sarvam/Ollama — tracked as a possible future upgrade, not built).

**Self-consistency sampling** reduces single-call noise further: set `.samples(n)` to average `n`
independent judge calls instead of trusting one. A single sample runs at temperature 0
(deterministic); `samples > 1` automatically switches to a higher temperature so the samples can
actually disagree with each other — averaging identical temperature-0 responses wouldn't reduce
anything.

```java
llmJudged("Correctness")
    .criteria("...")
    .judge(judgeClient)
    .samples(3)   // 3 independent judge calls, averaged — costs 3x the judge calls
    .build();
```

Off by default (`samples(1)`) so existing tests and cost profiles don't change unless you opt in.

**Caching** avoids paying for the same judge call twice. `LlmJudgeCondition` keys the cache by the
*exact content* of the call — the judge rubric text itself, criterion, inputs, trajectory, the
output being judged, and temperature — so a hit only happens when the same call would be made
again; there's no separate cache invalidation to manage, editing the rubric or the criteria
automatically invalidates old entries, and a `samples=1` draw can never collide with a `samples=3`
draw (they run at different temperatures, which is now part of the key). `LLMClient` doesn't expose
which model/provider it wraps, so if you cache across a judge-model change, also set
`.judgeIdentifier("gemini-2.5-pro")` (or whatever you're using) — otherwise switching judge models
while reusing the same cache would silently replay verdicts graded by the old one.

```java
llmJudged("Correctness")
    .criteria("...")
    .judge(judgeClient)
    .cache(InMemoryJudgeCache.create())                       // per-JVM/test-run only, or:
    .cache(FileSystemJudgeCache.at(Path.of("target/eval4j-cache")))  // persists across mvn runs
    .build();
```

`FileSystemJudgeCache` is the one that actually saves money in CI: point it at a directory your CI
caches between runs, and a scenario whose criterion/inputs/output haven't changed since the last
run replays the cached verdict instead of re-spending a judge call. With `.samples(n)`, each of the
`n` draws is cached under its own key — a rerun with unchanged inputs replays the same `n` draws
(reproducible) rather than drawing fresh, differently-priced randomness every time; a genuinely new
input still gets `n` fresh ones. Not applied unless you pass a `cache(...)`.

**Presets** (`LlmJudgePresets.using(judgeClient)`, binding the judge once):

| Preset | Checks |
|---|---|
| `correctness(expectedOutput)` | Semantic match against a known-good answer (unlike exact-string `hasFinalAnswerContaining`) |
| `answerRelevancy(input)` | The answer actually addresses what was asked |
| `faithfulness(retrievalContext)` / `groundedness(retrievalContext)` | No claim in the answer goes beyond what the retrieved context supports (same check, two names — RAGAS/Azure AI call it "groundedness") |
| `hallucinationFree(context)` | No fabricated facts/entities relative to the given context |
| `taskCompletion(input)` | Agent-specific: did the *whole run* — tool use included — satisfy the original request, not just the final sentence |
| `bias()` / `toxicity()` | Judge-graded bias/toxicity checks |
| `LlmJudgePresets.bias(BiasMonitor)` (static) | Delegates to an existing `io.github.llm4j.fairness.BiasMonitor` instead of a fresh judge prompt, so detection logic isn't duplicated |

Anything not in this table is a one-liner via `llmJudged(...)` directly — presets exist for
discoverability of the standard set, not as an exhaustive enum.

**Asserting several checks together** — `AbstractAssert.is(Condition)` already returns `this`, so
they just chain, each with its own specific failure message:

```java
LlmJudgePresets presets = LlmJudgePresets.using(judgeClient);

assertThat(result)
    .is(presets.correctness("36"))
    .is(presets.answerRelevancy(question))
    .is(presets.hallucinationFree(context));
```

### 3. Pass-rate aggregation — `PassRate`

Judge-based metrics are noisy enough that requiring *every* case in a 50-row dataset to pass is
usually the wrong bar. `PassRate` turns thrown `AssertionError`s into counted outcomes instead of
propagating them, then asserts on the aggregate:

```java
PassRate passRate = new PassRate();
for (Scenario scenario : scenarios) {
    AgentResult result = agent.run(scenario.input());
    passRate.record(() -> assertThat(result).hasFinalAnswerContaining(scenario.expected()));
}
passRate.requireAtLeast(0.9);
```

It works with any assertion that throws a plain `AssertionError` — eval4j's, AssertJ's, or vanilla
JUnit's — so it has no dependency on the rest of eval4j.

### 4. Golden datasets — `EvalScenario` / `EvalScenarios`

Hardcoding every case in Java doesn't scale, and it locks non-engineers out of authoring or
reviewing eval scenarios. `EvalScenario` is a plain data record (`name`, `input`,
`expectedOutputContains`, `expectedOutput`, `expectedTools`, `context`, `retrievalContext`) loaded
from YAML — but loading data is *all* eval4j does here; you still write the actual assertion chain
by hand in a normal `@ParameterizedTest`:

```yaml
# src/test/resources/scenarios.yaml
- name: percentage-calculation
  input: "What is 15% of 240?"
  expectedOutputContains: "36"
  expectedTools: [calculator]
- name: capital-lookup
  input: "What's the capital of France?"
  expectedOutputContains: "Paris"
```

```java
@ParameterizedTest(name = "{0}")
@MethodSource("scenarios")
void agentHandlesGoldenScenarios(EvalScenario scenario) {
    AgentResult result = agent.run(scenario.input());
    assertThat(result).hasFinalAnswerContaining(scenario.expectedOutputContains());
}

static Stream<EvalScenario> scenarios() {
    return EvalScenarios.fromYamlResource("scenarios.yaml").stream();
}
```

`EvalScenarios` also has `fromYaml(Path)` and `fromYaml(InputStream)` overloads. This is
deliberately *not* a config-driven test runner — the YAML supplies data, JUnit still owns the test.

### 5. Reporting — `EvalReportExtension`

```java
@ExtendWith(EvalReportExtension.class)
class MyAgentEvalTest {
    // ...
}
```

Prints a pass/fail summary (with judge reasons for failures) in `afterAll`, observing whatever
`@Test`/`@ParameterizedTest` methods in the class throw — eval4j's assertions or plain
JUnit/AssertJ ones. It's a standard JUnit 5 `TestWatcher`/`AfterAllCallback` extension, not a
bespoke "runner" you call explicitly.

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
| Data-driven suites | A `Dataset`/`Synthesizer` abstraction and a separate `deepeval test run` CLI | Plain JUnit 5 `@ParameterizedTest` + `@MethodSource`, run by the same `mvn test` you already use |
| Golden datasets | JSON/CSV loaded into `EvalCase`s | YAML loaded into a plain data record, still fed through `@MethodSource` — no config-driven runner |
| IDE experience | Dict-shaped kwargs, duck-typed metrics | Typed builders, autocomplete, compile-time checking |

The result is that reading an eval4j test doesn't require learning eval4j's own execution model
first — it requires knowing AssertJ and JUnit 5, which if you're already testing Java, you do.

See also: [Why AI Agent4J?](../ai-agent4j/wiki/WHY_AI_AGENT4J.md) for the same philosophy applied
to the core library, and [WHY_EVAL4J.md](../ai-agent4j/wiki/WHY_EVAL4J.md) for a longer treatment
of why agentic Java apps specifically need evals.

---

## Roadmap

Built so far (this module): fluent assertions, LLM-as-judge conditions + the standard preset set,
rubric-based (not raw-float) scoring, optional self-consistency sampling, pluggable judge-call
caching (in-memory and file-system), pass-rate aggregation, YAML golden datasets, and JUnit-native
reporting.

Tracked, not yet built:
- **Contextual RAG judging** (`contextualPrecision`/`contextualRecall`/`contextualRelevancy`) —
  needs per-chunk relevance judgments, either judge-LLM-only or embedding-based (via
  `ai-agent4j-addons`'s local ONNX/DJL embeddings).
- **True logprob-weighted G-Eval scoring** — the original paper's exact methodology computes a
  probability-weighted score from the judge model's own token log-probabilities, which is more
  rigorous than this module's rubric-based rating but needs logprobs support added to
  `LLMRequest`/`LLMResponse` in `ai-agent4j` itself first, and isn't available consistently across
  Gemini/Sarvam/Ollama. The rubric + self-consistency approach here gets most of the calibration
  benefit without that cross-module dependency.
- **Dataset synthesis, JSON/HTML reporting, CI/regression-baseline tracking**, including
  persisting historical scores from `FileSystemJudgeCache`-style CI caching into an actual trend
  view (right now caching saves judge-call cost, but nothing tracks score trends over time).
- **Evaluating whole [Loom](../loom/) multi-agent workflows**, not just single `ReActAgent` runs —
  needs its own `WorkflowResultAssert` once Loom's execution/result model is scoped out.
- **Comparative prompt testing** (pairwise "which prompt variant wins" judging).
- **Conversational semantic metrics** (knowledge retention, role adherence across a whole
  conversation) — `ConversationAssert` currently only checks turn count/completion structurally,
  not conversation-level judge-based criteria.
- **Multimodal evaluation** — judging image/audio outputs, relevant given this ecosystem's own
  voice apps (e.g. Kingini's STT/TTS), not built or scoped yet.

---

## Testing eval4j itself

```bash
cd eval4j
mvn test                              # unit tests — no API key needed
mvn -P integration-tests verify       # + a real Gemini-backed ReActAgent and judge round-trip
                                       # requires GEMINI_API_KEY (or GOOGLE_API_KEY) in the environment
```
