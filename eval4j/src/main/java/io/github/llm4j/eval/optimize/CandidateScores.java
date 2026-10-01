package io.github.llm4j.eval.optimize;

import io.github.llm4j.eval.criteria.Scorecard;
import java.util.List;

/**
 * A candidate's scorecards on the validation and (sealed) test splits. For the best candidate the
 * validation scores are the <em>confirmation</em> run, not the selection-time scores. {@code
 * testMean} is null when there is no test split.
 */
public record CandidateScores(
        double validationMean, List<Scorecard> validation, Double testMean, List<Scorecard> test) {

    public CandidateScores {
        validation = List.copyOf(validation);
        test = List.copyOf(test);
    }
}
