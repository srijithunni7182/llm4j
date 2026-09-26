package io.github.llm4j.eval.assertions;

import io.github.llm4j.agent.AgentResult;

/** Entry point for eval4j's fluent assertions on {@link AgentResult}. */
public final class AgentAssertions {

    private AgentAssertions() {}

    public static AgentResultAssert assertThat(AgentResult actual) {
        return new AgentResultAssert(actual);
    }
}
