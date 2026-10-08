package io.github.llm4j.eval.optimize;

import io.github.llm4j.eval.dataset.EvalScenario;
import java.util.List;

/**
 * The three disjoint scenario sets used by the optimizer: {@code train} feeds the rewriter, {@code
 * validation} selects among candidates, and {@code test} is sealed until the end.
 */
public record DataSplit(
        List<EvalScenario> train, List<EvalScenario> validation, List<EvalScenario> test) {

    public DataSplit {
        train = List.copyOf(train);
        validation = List.copyOf(validation);
        test = List.copyOf(test);
    }
}
