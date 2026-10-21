package io.github.llm4j.classify;

/**
 * What a classify call cost. Numbers and names only: never the state or an answer.
 *
 * @param inputTokens tokens sent, as the backend reported them (0 when it did not)
 * @param outputTokens tokens received
 * @param latencyMillis wall time of the call, retries included
 * @param backend which kind of backend answered ({@code "llm"}, {@code "jev"}, …)
 * @param model the model that answered, or {@code ""} when the backend did not say
 */
public record ClassifyUsage(
        int inputTokens, int outputTokens, long latencyMillis, String backend, String model) {

    public ClassifyUsage {
        if (inputTokens < 0 || outputTokens < 0 || latencyMillis < 0)
            throw new IllegalArgumentException("usage numbers cannot be negative");
        backend = backend == null ? "" : backend;
        model = model == null ? "" : model;
    }

    /** The total of two calls to the same backend (the samples of one classification). */
    public ClassifyUsage plus(ClassifyUsage other) {
        return new ClassifyUsage(
                inputTokens + other.inputTokens,
                outputTokens + other.outputTokens,
                latencyMillis + other.latencyMillis,
                backend,
                model.isEmpty() ? other.model : model);
    }
}
