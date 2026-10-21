package io.github.llm4j.eval.judge;

import io.github.llm4j.classify.Answer;
import io.github.llm4j.classify.Classifier;
import io.github.llm4j.classify.ClassifyRequest;
import io.github.llm4j.classify.ClassifyResult;
import io.github.llm4j.classify.Scale;
import io.github.llm4j.classify.ScoreAnswer;
import io.github.llm4j.classify.YesNoAnswer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Asks a {@link Classifier} the question a judge answers: does the output satisfy the criterion? A
 * single criterion is one yes/no question, whose probability of yes is the score; a graded
 * criterion is one question on a five-point scale, whose position is the score. Everything the
 * judge would be shown (input, expected output, context, retrieved context, trajectory, the output)
 * goes in as named state fields, which the classifier delimits as untrusted data. The verdict has
 * the classifier's rationale as its reason when it gave one and an empty reason otherwise.
 */
final class ClassifierJudge {

    /** The name of the one question asked. */
    static final String QUESTION = "criterion";

    /**
     * The levels of a graded criterion, lowest first (the same five levels as the judge rubric).
     */
    static final Scale GRADES =
            Scale.of(
                    "fails the criterion",
                    "mostly fails",
                    "partially satisfies",
                    "mostly satisfies",
                    "fully satisfies");

    private ClassifierJudge() {}

    /** The result of one classification and what it cost, so the caller can record telemetry. */
    record Judged(JudgeVerdict verdict, ClassifyResult result) {}

    static Judged judge(
            Classifier classifier,
            boolean graded,
            String name,
            String criteria,
            String input,
            String expectedOutput,
            List<String> context,
            List<String> retrievalContext,
            String actualOutput,
            String trajectory) {
        Map<String, String> state = new LinkedHashMap<>();
        if (input != null && !input.isBlank()) state.put("input", input);
        if (expectedOutput != null && !expectedOutput.isBlank())
            state.put("expected_output", expectedOutput);
        if (context != null && !context.isEmpty())
            state.put("context", "- " + String.join("\n- ", context));
        if (retrievalContext != null && !retrievalContext.isEmpty())
            state.put("retrieved_context", "- " + String.join("\n- ", retrievalContext));
        if (trajectory != null && !trajectory.isBlank()) state.put("trajectory", trajectory);
        state.put("actual_output", actualOutput == null ? "" : actualOutput);

        String criterion = name + ": " + criteria;
        ClassifyRequest.Builder request = ClassifyRequest.builder().state(state);
        if (graded) {
            request.score(
                    QUESTION,
                    "How well does the actual_output satisfy this criterion? " + criterion,
                    GRADES);
        } else {
            request.yesNo(QUESTION, "Does the actual_output satisfy this criterion? " + criterion);
        }
        ClassifyResult result = classifier.classify(request.build());
        Answer answer = result.answer(QUESTION);
        double score =
                answer instanceof ScoreAnswer s
                        ? s.position()
                        : ((YesNoAnswer) answer).probability();
        return new Judged(new JudgeVerdict(score, answer.rationale().orElse("")), result);
    }
}
