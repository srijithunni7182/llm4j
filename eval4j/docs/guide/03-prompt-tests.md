# 3. Test the prompts

**Goal:** each prompt is shown to enforce the rule it exists for, and any rewrite is compared with the current version before it ships.

## Why

A prompt is code that nobody type-checks. A one-word edit can quietly stop the agent searching or start it inventing. A
prompt test fails the build when that happens, and gives an A/B result when you propose a change.

## How

1. **State the rule** each prompt must enforce, as rubric lines in the golden dataset (for example: "the prompt must make the
   agent search and call out a fabricated term in the first sentence").
2. **Run the prompt on a case** and judge the output against the rubric. One judge call per case; the verdict counts toward every
   dimension the case lists:

```java
Object out = agent.run(task);                              // or a plain model call for prompts without tools
assertThat(out).is(
        llmJudged("Prompt rule")
                .criteria(String.join("\n", rubricLines))
                .scenario(scenario)                        // input, context, dimensions
                .judge(judgeClient).cache(judgeCache).threshold(0.7)
                .build());
```

3. **Assert the tools too** for prompts that need them: `usesTool("WebSearch")`. A prompt that stops the agent searching is a regression a text judge may miss.
4. **A/B a candidate.** Put the rewrite in a second registry file and compare both orders (so position bias cancels):

```java
PromptComparison.Result result = PromptComparison.using(judgeClient)
        .criteria("Which output follows the prompt's intent better: verifies fabricated terms, specific, concise?")
        .variantA("current",   s -> run(s, currentRegistry))
        .variantB("candidate", s -> run(s, candidateRegistry))
        .scenarios(targets).swapPositions(true)
        .cache(judgeCache).judgeIdentifier("judge-main")
        .run();
PromptComparisonAssertions.assertThat(result).hasNoErrors().doesNotRegress(0.05);
```

## Practical notes

- **Different judge from the agent**, ideally stronger. Hexamind judges Gemini agents with Claude.
- **Cache judge calls** (`FileSystemJudgeCache`) so a re-run only pays for changed cases.
- **Apply the prompt's instructions to the case's input** when the prompt has `{{placeholders}}` the app fills at run time; do not drag the whole
  application (vector stores, embeddings) into a prompt test.
- Compare **both orders**. A judge tends to favour whichever answer it sees first.

## Gate

Every prompt meets its rule on its cases, and any candidate does not regress (`doesNotRegress`). Fix failing prompts by hand first; use
the [optimizer](04-prompt-optimization.md) only if the failures are many or subtle.
