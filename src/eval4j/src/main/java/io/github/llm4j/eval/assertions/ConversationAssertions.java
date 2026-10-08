package io.github.llm4j.eval.assertions;

import io.github.llm4j.agent.AgentResult;
import java.util.List;

/** Entry point for eval4j's fluent assertions on a multi-turn conversation. */
public final class ConversationAssertions {

    private ConversationAssertions() {}

    public static ConversationAssert assertThat(List<AgentResult> turns) {
        return new ConversationAssert(turns);
    }
}
