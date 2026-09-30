package io.github.llm4j.eval.optimize;

import java.util.List;

/**
 * One row of the optimization trace.
 *
 * @param childId the proposed candidate's id, or null if none was created
 * @param childBatchMean null when the child was never evaluated
 * @param validationMean null unless the child was scored on validation
 * @param onFrontier whether an accepted child ended up on the Pareto frontier
 * @param note human-readable detail (rejection reason, error message)
 */
public record Round(
        int index,
        String parentId,
        String parameter,
        RoundAction action,
        String childId,
        List<String> batch,
        double parentBatchMean,
        Double childBatchMean,
        Double validationMean,
        boolean onFrontier,
        int poolSize,
        int frontierSize,
        String note) {

    public Round {
        batch = List.copyOf(batch);
    }
}
