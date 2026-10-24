package io.github.llm4j.loom.review.cost;

/**
 * What one model step spends in a workflow, summed over every time it runs, typically and at most.
 *
 * @param workflow the workflow it is in
 * @param line its line in the script
 * @param label what it is, such as {@code delegate Replier}
 * @param model the model it calls
 * @param typical what it spends over the runs of a typical execution
 * @param worstUsage what it spends over the runs of the longest allowed execution; only the part
 *     that has a bound when {@code worstRuns} is {@code -1}
 * @param retries how many retries the step declares
 * @param typicalRuns how many times it typically runs
 * @param worstRuns how many times it can run at most, or {@code -1} when that has no bound
 */
public record StepCost(
        String workflow,
        int line,
        String label,
        String model,
        Usage typical,
        Usage worstUsage,
        int retries,
        long typicalRuns,
        long worstRuns) {

    /** The worst case, or {@code null} when the step can run without bound. */
    public Usage worst() {
        return worstRuns < 0 ? null : worstUsage;
    }
}
