# Comparing prompts and models

A/B test two prompts, agents or models over a dataset with a position-bias-safe judge.

[← eval4j README](../README.md) · [All docs](README.md)

---

## Comparing two prompts

```java
PromptComparison.Result result = PromptComparison.using(judgeClient)
    .criteria("More helpful, accurate and concise for a customer-support answer")
    .variantA("current", s -> agentA.run(s.input()))
    .variantB("candidate", s -> agentB.run(s.input()))
    .scenarios(EvalScenarios.fromYamlResource("scenarios.yaml"))
    .run();

PromptComparisonAssertions.assertThat(result).doesNotRegress(0.05).hasNoErrors();
```

Each pair is judged in both orders and only counts as a win if both agree (otherwise a flagged tie),
which cancels position bias. Per-scenario scores feed the same reports and baselines
(`Pairwise: current vs candidate`).
