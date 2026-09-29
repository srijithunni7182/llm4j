# Fluent assertions & pass rates

Deterministic, free checks on agent runs (tools, order, iterations, tokens, JSON shape), plus pass-rate aggregation for noisy suites.

[← eval4j README](../README.md) · [All docs](README.md)

---

## Fluent assertions — `AgentAssertions` / `LlmResponseAssertions`

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


## Pass-rate aggregation — `PassRate`

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
