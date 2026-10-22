package io.github.llm4j.spring;

import io.github.llm4j.agent.ReActAgent;

/**
 * Declare a bean of this type to change every {@code ReActAgent.Builder} the starter makes, after
 * its own setup, in {@code @Order}.
 */
@FunctionalInterface
public interface ReActAgentBuilderCustomizer {
    void customize(ReActAgent.Builder builder);
}
