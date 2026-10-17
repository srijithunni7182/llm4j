package io.github.llm4j.spring;

import io.github.llm4j.loom.execution.HarnessExecutor;

/**
 * Declare a bean of this type to configure every executor {@link LoomRuntime} creates: a {@code HumanInterface}, a run journal,
 * an audit logger, a secret store. It runs before {@code initialize()}.
 */
@FunctionalInterface
public interface LoomExecutorCustomizer {
    void customize(HarnessExecutor executor);
}
