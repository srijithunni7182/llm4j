# 4. Optimize the prompts

**Goal:** improve prompts that still fail after chapter 3, with proof that the improvement is real and not overfitting.

## When (and when not)

Optimize **after** a failing prompt test, never before: the optimizer needs scenarios and a judge to optimize *against*, and it
spends money. If one prompt fails one case, fix it by hand. Use the optimizer when several cases fail, or the failure is subtle.

## How

`PromptOptimizer` rewrites a prompt from the reasons it failed, keeps a pool of candidates, and verifies the winner on scenarios it
never trained on. It never writes your files: it hands back a patch. With prompt files, the patch is the text of a new version (`vN+1.md`) that you add beside the old one and compare with `--prompt`.

```java
PromptOptimizer optimizer = PromptOptimizer.builder()
        .seed(Candidate.of("system-prompt", currentPrompt))
        .system((candidate, scenario) -> buildAgent(candidate.get("system-prompt")).run(scenario.input()))
        .criteria(List.of(Criteria.judged(presets.correctness("...")), Criteria.guardrail("no-pii", noPii)))
        .scenarios(EvalScenarios.fromYamlResource("scenarios.yaml"))
        .split(Split.ratios(0.5, 0.3, 0.2).seed(42))      // train / validation / sealed test
        .rewriter(rewriterClient)                          // a different model from the judge
        .constraints(PromptConstraints.builder().maxChars(4000).mustContain("{{input}}").build())
        .budget(OptimizerBudget.builder().maxRollouts(600).maxRounds(30).build())
        .targetValidationMean(0.9)
        .checkpointDir(Path.of("target/optimizer"))
        .acknowledgeSideEffects()                          // required: use test doubles for tools with side effects
        .build();

PromptOptimizer.Estimate estimate = optimizer.estimate();   // look at this BEFORE run()
OptimizationResult result = optimizer.run();
result.writeReport(Path.of("target/optimizer"));
if (result.generalized()) { result.toPatch().applyTo(promptsDir); }
```

## Keep it safe and cheap

- **Read `estimate()` first**, and set `OptimizerBudget` (`maxRollouts`, `maxRounds`, `maxLlmCalls`, time). A `SpendGuard`
  around the clients is a second net ([chapter 5](05-test-agents-with-caps.md)).
- **Guardrail criteria** (`Criteria.guardrail`) force a score of zero on a violation, so the optimizer cannot win by weakening a safety rule.
- **Side effects:** the optimizer replays your agent many times. Give it test doubles for tools that send, write or pay.
- **Checkpoint** (`checkpointDir`) so a stopped run resumes instead of restarting.
- It optimizes **prompt text**. It does not optimize a Loom workflow's structure.

## Gate

`result.generalized()` is true: the sealed test split improved by a real margin, validation and test agree, no guardrail failed and the seed
does not beat the winner on more test cases. If false, `result.verdict().reasons()` says why; do not apply the patch. Then re-run chapter 3's
tests on the patched prompts. Details: [optimizer guide](https://github.com/srijithunni7182/llm4j/blob/main/eval4j/docs/OPTIMIZER.md).
