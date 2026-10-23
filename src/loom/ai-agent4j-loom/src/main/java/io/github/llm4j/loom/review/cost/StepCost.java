package io.github.llm4j.loom.review.cost;

/**
 * What one model step spends each time it runs, and how many times it can run in the workflow.
 *
 * @param workflow the workflow it is in
 * @param line its line in the script
 * @param label what it is, such as {@code delegate Replier}
 * @param model the model it calls
 * @param each one typical run of the step
 * @param eachWorst one run of the step at its longest (an agent with tools at its most rounds)
 * @param retries how many retries the step declares
 * @param typicalRuns how many times it typically runs (loops, for each, retries are 1 typically)
 * @param worstRuns how many times it can run at most, or {@code -1} when that has no bound
 */
public record StepCost(
        String workflow,
        int line,
        String label,
        String model,
        Usage each,
        Usage eachWorst,
        int retries,
        long typicalRuns,
        long worstRuns) {

    public Usage typical() {
        return each.times(typicalRuns);
    }

    /** The worst case, or {@code null} when the step can run without bound. */
    public Usage worst() {
        return worstRuns < 0 ? null : eachWorst.times(worstRuns);
    }
}
