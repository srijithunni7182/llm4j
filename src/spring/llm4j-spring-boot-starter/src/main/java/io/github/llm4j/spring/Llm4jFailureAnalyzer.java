package io.github.llm4j.spring;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Prints an {@link Llm4jConfigurationException} as Spring Boot's Description / Action, without the
 * stack trace.
 */
class Llm4jFailureAnalyzer extends AbstractFailureAnalyzer<Llm4jConfigurationException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, Llm4jConfigurationException cause) {
        return new FailureAnalysis(cause.getMessage(), cause.action(), cause);
    }
}
