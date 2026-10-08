package io.github.llm4j.eval.optimize;

/**
 * Thrown by {@code PromptOptimizer.Builder#build()} (and split validation) when the configuration
 * cannot produce a trustworthy run. The message states what is wrong and how to fix it.
 */
public class OptimizerConfigurationException extends RuntimeException {

    public OptimizerConfigurationException(String message) {
        super(message);
    }

    public OptimizerConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
