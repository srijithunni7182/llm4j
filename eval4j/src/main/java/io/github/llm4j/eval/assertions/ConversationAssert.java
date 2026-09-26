package io.github.llm4j.eval.assertions;

import io.github.llm4j.agent.AgentResult;
import java.util.List;
import org.assertj.core.api.AbstractObjectAssert;

/**
 * AssertJ custom assertion for a multi-turn conversation — a sequence of {@link AgentResult}s from
 * repeated calls to the same agent. Obtain one via {@link
 * ConversationAssertions#assertThat(List)}.
 */
public class ConversationAssert extends AbstractObjectAssert<ConversationAssert, List<AgentResult>> {

    public ConversationAssert(List<AgentResult> actual) {
        super(actual, ConversationAssert.class);
    }

    public ConversationAssert hasTurnCount(int expected) {
        isNotNull();
        if (actual.size() != expected) {
            failWithMessage(
                    "Expected conversation to have <%s> turns but had <%s>", expected, actual.size());
        }
        return this;
    }

    public ConversationAssert allCompletedSuccessfully() {
        isNotNull();
        for (int i = 0; i < actual.size(); i++) {
            if (!actual.get(i).isCompleted()) {
                failWithMessage("Expected turn <%s> to complete successfully but it did not", i);
            }
        }
        return this;
    }

    /** Returns an {@link AgentResultAssert} for the turn at the given zero-based index. */
    public AgentResultAssert turn(int index) {
        isNotNull();
        if (index < 0 || index >= actual.size()) {
            failWithMessage(
                    "Expected conversation to have a turn at index <%s> but it only had <%s> turns",
                    index, actual.size());
        }
        return new AgentResultAssert(actual.get(index));
    }
}
