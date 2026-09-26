package io.github.llm4j.eval.judge;

import java.util.List;

/**
 * Builds the system/user prompt sent to a judge {@link io.github.llm4j.LLMClient}. Asks for
 * step-by-step reasoning followed by a discrete 1-5 rubric rating, rather than a raw continuous
 * score: LLMs are well documented to be poorly calibrated when asked to output a probability-like
 * float directly, but noticeably more reliable when grading against a labeled ordinal scale after
 * reasoning about it first (the same insight behind the G-Eval methodology, short of needing
 * token log-probabilities from the provider).
 */
final class JudgePrompt {

    static final String SYSTEM_PROMPT =
            """
            You are an impartial evaluator grading an AI system's output against a specific
            criterion.

            First, reason step by step about how well the actual output satisfies the criterion.
            Then assign a rating from 1 to 5 using this rubric:
              1 = Completely fails the criterion
              2 = Mostly fails, with only minor alignment
              3 = Partially satisfies the criterion
              4 = Mostly satisfies, with only minor gaps
              5 = Fully and clearly satisfies the criterion

            Respond with ONLY a single JSON object inside a ```json code block, in exactly this
            shape, and no other text:

            ```json
            {
              "reasoning": "step-by-step reasoning about how well the criterion is met",
              "rating": 4
            }
            ```
            """;

    private JudgePrompt() {}

    static String buildUserMessage(
            String criterionName,
            String criteria,
            String input,
            String expectedOutput,
            List<String> context,
            List<String> retrievalContext,
            String actualOutput) {
        StringBuilder sb = new StringBuilder();
        sb.append("Criterion: ").append(criterionName).append('\n');
        sb.append("What to check: ").append(criteria).append("\n\n");

        if (input != null && !input.isBlank()) {
            sb.append("Input:\n").append(input).append("\n\n");
        }
        if (context != null && !context.isEmpty()) {
            sb.append("Context:\n");
            context.forEach(c -> sb.append("- ").append(c).append('\n'));
            sb.append('\n');
        }
        if (retrievalContext != null && !retrievalContext.isEmpty()) {
            sb.append("Retrieved Context:\n");
            retrievalContext.forEach(c -> sb.append("- ").append(c).append('\n'));
            sb.append('\n');
        }
        if (expectedOutput != null && !expectedOutput.isBlank()) {
            sb.append("Expected Output:\n").append(expectedOutput).append("\n\n");
        }
        sb.append("Actual Output:\n").append(actualOutput);
        return sb.toString();
    }
}
