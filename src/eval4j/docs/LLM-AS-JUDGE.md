# LLM-as-judge

Grade what can't be string-matched — correctness, relevancy, groundedness, task completion — with a judge model, rubric scoring, sampling and caching.

[← eval4j README](../README.md) · [All docs](README.md)

---

## LLM-as-judge — `LlmJudgeCondition` / `LlmJudgePresets`

**Use a different (ideally stronger) model as the judge than the one being tested.** Grading an
agent's output with the same model/client that produced it means the model is partly grading its
own work — fine for a quick smoke test (the integration test in this repo does exactly that,
deliberately, to keep the example to one API key), but for real suites, wire a separate `LLMClient`
in as the judge.

`LlmJudgeCondition` is a real `org.assertj.core.api.Condition`, built with:

```java
llmJudged("Conciseness")
    .criteria("The answer is three sentences or fewer and directly answers the question")
    .judge(judgeClient)     // any LLMClient — Gemini, Claude, Sarvam, Ollama, a routing client, a test double
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


### Judge a whole scenario in one line, and fill the report's dimensions

`scenario(...)` binds a golden case (input, expected output, retrieved context and dimensions) to the check, and
`dimensions(...)` says which quality dimensions the verdict counts toward. The judge is called **once**; the verdict is
recorded under each dimension, so the dashboard's dimension rings fill from your dataset:

```java
assertThat(result).is(
    llmJudged("Rubric adherence")
        .criteria(String.join("\n", rubricLines))
        .scenario(scenario)            // input, expected output, retrieval context, dimensions
        .judge(judge).cache(cache).threshold(0.7)
        .build());
```

`OutputExtractor.extract(actual)` returns the text a judge grades from an `AgentResult`, `LLMResponse` or `String`.

## Judge with a classifier

> **Status:** works with any chat model through `Classifier.using(chatClient)`. A Jev-backed classifier plugs in the same way once the Jev provider ships
> (same release; see [the classification page](../../ai-agent4j/wiki/Classification.md)). Pairwise, RAG-chunk and conversation judging still use `.judge(LLMClient)`;
> the presets are unchanged.

A judgement is one question with a fixed set of answers, so it can be asked as a **typed question** instead of "write a rating as JSON and I will parse it". With
`.classifier(...)` the judge sends the output (and the input, expected output, context and trajectory you give it, as delimited data) to a `Classifier`, and the score
is the probability of **yes** to "does the output satisfy this criterion?". There is nothing to parse, so there is nothing to fail parsing; `threshold`, `samples`,
`cache`, telemetry and `dimensions` work as before.

```java
Classifier judge = Classifier.using(cheapChatClient);   // any chat model; a Jev-backed classifier plugs in the same way

assertThat(result).is(llmJudged("Correctness")
        .criteria("The answer states the refund window as 30 days")
        .classifier(judge)                              // instead of .judge(chatClient)
        .threshold(0.7)
        .build());
```

Two things differ from a chat judge, on purpose:

- **No invented reason.** The verdict's reason is empty unless the backend gave one. A failure then reads `llm-judged "Correctness" (score=0.20, threshold=0.70)`, with
  the score and the threshold, and nothing made up. Ask for a reason when you want one (it costs output tokens).
- **A graded criterion is a five-point scale.** `graded(true)` asks "how well" on a scale from *fails the criterion* to *fully satisfies*, and the score is the position
  on it, as the rubric presets give.

```java
Classifier judge = LlmClassifier.builder(cheapChatClient).withRationale(true).build();   // a reason costs output tokens

var verdict = llmJudged("Correctness")
        .criteria("The answer states the refund window and when it starts")
        .classifier(judge)
        .graded(true)                                    // a five-point scale instead of yes/no; the score is the position on it
        .build()
        .evaluate("Refunds are accepted within 30 days.");
// verdict.score() is 0.75 here; verdict.reason() is "" unless the backend gave a rationale
```

Set either `.judge(...)` or `.classifier(...)`, not both. `dimensions(...)` still files the one verdict under every dimension you name. The cache never mixes a
classifier's verdicts with a chat judge's, nor a graded verdict with a yes/no one.

### Compare classifiers on your own cases

Whether a cheaper or faster backend is good enough is a question about **your** criteria, so measure it. `ClassifierComparison` sends the same labelled cases to every
contender and reports, for each: accuracy against your labels, invalid replies, calibration (expected calibration error, Brier score, the reliability curve), the accuracy
you keep if you act only on the most confident answers, latency, tokens and cost per 1,000 decisions; and between them the agreement and how often an answer flips.

```java
List<Case> cases = List.of(
        Case.yesNo("c1", "clear", "Refunds are accepted within 30 days.", "Does the answer state a 30 day refund window?", true),
        Case.yesNo("c2", "wrong number", "Refunds are accepted within 14 days.", "Does the answer state a 30 day refund window?", false));

ClassifierComparison.Report report = ClassifierComparison.builder()
        .cases(cases)
        .contender("cheap", Classifier.using(cheapChatClient))
        .contender("strong", Classifier.using(strongChatClient))
        .price("cheap", 0.10, 0.50)                      // dollars per million input and output tokens; optional
        .repeats(3)                                      // ask each case three times to see how often the answer flips
        .build()
        .run();

System.out.println(report.toMarkdown());                 // accuracy, calibration, latency, cost, agreement
```

The labels are the ground truth, never another model. Use a few dozen cases you have read yourself, including the awkward ones (negation, typos, the
wrong number, an injection attempt). The run that produced the figures in [the Phase 7 study](../../../docs/specs/07-classifier/STUDY_REPORT.md) is this, in Python.
Treat a difference of one or two cases as noise.

## Choosing a judge model

A small calibration study (author-labelled synthetic data, Claude Haiku 4.5 vs Sonnet 5.5 — see
[VERIFICATION-RESULTS.md](../VERIFICATION-RESULTS.md)) found both judges close on RAG relevancy/precision,
the conversation metrics and clear-cut pairwise comparisons. A smaller judge was more lenient on
partial-support recall, more prone to preferring one of two equivalent answers, and flipped its
verdict on order alone more often (which `PromptComparison`'s position swap turns into ties). Rule of
thumb: a small model is fine for exploratory runs; use a stronger judge for recall and for anything that
gates CI. Treat these findings as indicative, not proven — the data is small and not independently
labelled.
