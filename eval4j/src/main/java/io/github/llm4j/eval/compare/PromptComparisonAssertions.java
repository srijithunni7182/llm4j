package io.github.llm4j.eval.compare;

/** Entry point: {@code PromptComparisonAssertions.assertThat(result).doesNotRegress(0.05)}. */
public final class PromptComparisonAssertions {

    private PromptComparisonAssertions() {}

    public static PromptComparisonAssert assertThat(PromptComparison.Result result) {
        return new PromptComparisonAssert(result);
    }
}
