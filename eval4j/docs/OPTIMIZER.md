# Prompt optimizer

Autonomously improves the text of an AI system — first, an agent's prompt — until eval4j's own metrics
say it is good enough, then hands you a **reviewable patch**. It is modelled on
[GEPA](optimizer/DESIGN.md#2-background-what-gepa-does): rewrite based on *why* the prompt failed, keep
a diverse pool of candidates, and verify on data the loop never saw.

[← eval4j README](../README.md) · [All docs](README.md)

> [!WARNING]
> This is the one part of eval4j that **changes things**. It runs your system hundreds of times and
> calls an LLM to rewrite text. Use test doubles for tools that write, send or charge (the builder makes
> you acknowledge this), and read the diff before applying it.

---

## The loop in one picture

```
seed prompt ──▶ pick a promising candidate ──▶ run it on a few TRAIN scenarios
     ▲                                                   │
     │                                     scores + judge reasons + assertion messages
     │                                                   ▼
keep it only if it beats its parent           an LLM rewrites the prompt to fix those failures
on the batch, then scores well on VALIDATION ◀──────────┘
     │
     └── when the target is hit or the budget is spent:
         re-check the winner, then verify seed vs winner on a sealed TEST split
```

Three splits keep it honest: **train** feeds the rewriter (it never sees anything else), **validation**
chooses between candidates, and **test** is sealed until the end. The result's `generalized()` says
whether the improvement survived all of that.

## Usage

```java
PromptOptimizer optimizer = PromptOptimizer.builder()
    .seed(Candidate.of("system-prompt", currentPrompt))
    .system((candidate, scenario) ->                       // build your agent from the candidate text
        buildAgent(candidate.get("system-prompt")).run(scenario.input()))
    .criteria(List.of(
        Criteria.judged(presets.correctness("...")),       // any eval4j condition, as data
        Criteria.guardrail("no-pii", noPii)))              // must hold, or the scenario scores 0
    .scenarios(EvalScenarios.fromYamlResource("scenarios.yaml"))
    .split(Split.ratios(0.5, 0.3, 0.2).seed(42))           // train / validation / test
    .rewriter(rewriterClient)                              // a different model from the judge
    .constraints(PromptConstraints.builder().maxChars(4000).mustContain("{{input}}").build())
    .budget(OptimizerBudget.builder().maxRollouts(600).maxRounds(30).build())
    .targetValidationMean(0.9)
    .checkpointDir(Path.of("target/optimizer"))            // resume after a crash
    .acknowledgeSideEffects()                              // required
    .build();

PromptOptimizer.Estimate estimate = optimizer.estimate();  // what the budget allows, before spending
OptimizationResult result = optimizer.run();

result.writeReport(Path.of("target/optimizer"));           // trace JSON + Markdown report
if (result.generalized()) {
    result.toPatch().applyTo(promptsDir);                  // your decision; the optimizer never writes your files
}
```

This example is compiled and run by
[`OptimizerDocsExampleTest`](../src/test/java/io/github/llm4j/eval/optimize/OptimizerDocsExampleTest.java).

## Ideas worth knowing

| Concept | What it does |
|---|---|
| **Criteria** | `Criteria.judged(...)`, `assertion(...)`, `guardrail(...)`, `weighted(...)`, `perScenario(...)` turn eval4j conditions into data (score, pass/fail, reasons) instead of thrown assertions. Useful on their own. |
| **Guardrails** | Deterministic checks that must pass. A violation forces the scenario score to 0, so a candidate can't buy a high judge score by breaking a rule. |
| **Constraints** | Length limits and required/forbidden text, checked *before* any rollout is spent on a candidate. |
| **Budget** | Hard caps on rollouts, rounds, LLM calls and time. Rollout and time caps are never exceeded; part of the rollout budget is reserved for the final confirmation and test runs. |
| **Confirmation** | The winner is re-scored on validation with fresh outputs, so a lucky score during the search doesn't survive. |
| **Verdict** | `generalized` is true only if the test split improved by a real margin, validation and test agree (within a tolerance that grows on small splits, where a few scenarios are mostly noise), no guardrail fails, the seed doesn't beat the winner on more test scenarios, and — if the run stopped because validation hit the target — the sealed test agrees. `verdict().reasons()` says why not. |
| **Resume** | With `checkpointDir`, state is saved atomically after every round; run again to resume. A checkpoint from a different configuration is refused, not ignored. |
| **Redactor** | `redactor(...)` scrubs outputs and feedback before they reach the rewriter, traces and checkpoints. |
| **Cost** | `estimate()` gives upper bounds before you run; `LlmCallCounter` wraps a client so judge and agent calls count toward `maxLlmCalls` and the reported cost. |

## Honest limits

- **It optimizes what your criteria check, nothing else.** In our grounded-QA run, two different judges
  scored the starting prompt 1.00 while a hard format rule (answer with a bare phrase or the literal
  token `NOT_IN_CONTEXT`) was failing. Put hard rules in a deterministic `guardrail` or `assertion`, and
  reserve rubric judges for qualities that need judgement. Two judges agreeing is not proof when both
  read the same under-specified rubric.
- **Evidence so far is small:** three synthetic tasks, 2-3 seeds each, on one provider
  ([results](optimizer/VERIFICATION-RESULTS.md)). Treat the optimizer as experimental: use it to
  *propose* prompt changes, and review the diff.
- Optimizing against an LLM judge can raise the judge's score without improving real quality. The
  splits, guardrails, constraints and confirmation reduce this but cannot eliminate it. **Read the diff.**
- Small datasets give noisy selection; the minimum split size (5) is a floor, not a recommendation.
- It optimizes text only: no few-shot selection, no structural changes to the agent.
- Cost scales with `rollouts × (agent + judge calls)`; run `estimate()` first.

Design, tests and how it is verified: [SPEC](optimizer/SPEC.md) · [DESIGN](optimizer/DESIGN.md) ·
[TEST-STRATEGY](optimizer/TEST-STRATEGY.md) · [VERIFICATION-PLAN](optimizer/VERIFICATION-PLAN.md) ·
[VERIFICATION-RESULTS](optimizer/VERIFICATION-RESULTS.md).
