package io.github.llm4j.eval.assertions;

import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.eval.export.EvalChecks;
import io.github.llm4j.eval.export.MetricRef;
import io.github.llm4j.eval.judge.Transcript;
import java.util.List;
import org.assertj.core.api.AbstractObjectAssert;
import org.assertj.core.api.ObjectAssert;

/**
 * AssertJ custom assertion for a multi-turn conversation — a sequence of {@link AgentResult}s from
 * repeated calls to the same agent. Obtain one via {@link ConversationAssertions#assertThat(List)}.
 */
public class ConversationAssert
        extends AbstractObjectAssert<ConversationAssert, List<AgentResult>> {

    private static final MetricRef M_HASTURNCOUNT =
            MetricRef.assertion(
                    "turn-count", "Turn count", "conversations", "multi", "conversation");
    private static final MetricRef M_ALLCOMPLETEDSUCCESSFULLY =
            MetricRef.assertion(
                    "turns-completed",
                    "All turns completed",
                    "conversations",
                    "multi",
                    "reliability");

    public ConversationAssert(List<AgentResult> actual) {
        super(actual, ConversationAssert.class);
    }

    public ConversationAssert hasTurnCount(int expected) {
        EvalChecks.check(
                M_HASTURNCOUNT,
                () -> {
                    isNotNull();
                    if (actual.size() != expected) {
                        failWithMessage(
                                "Expected conversation to have <%s> turns but had <%s>",
                                expected, actual.size());
                    }
                });
        return this;
    }

    public ConversationAssert allCompletedSuccessfully() {
        EvalChecks.check(
                M_ALLCOMPLETEDSUCCESSFULLY,
                () -> {
                    isNotNull();
                    for (int i = 0; i < actual.size(); i++) {
                        if (!actual.get(i).isCompleted()) {
                            failWithMessage(
                                    "Expected turn <%s> to complete successfully but it did not",
                                    i);
                        }
                    }
                });
        return this;
    }

    /**
     * Pairs the given user inputs with these results into a {@link Transcript} so
     * conversation-level judge conditions apply: {@code
     * assertThat(results).conversation(inputs).is(conv.roleAdherence(...))}.
     */
    public ObjectAssert<Transcript> conversation(List<String> userInputs) {
        isNotNull();
        return new ObjectAssert<>(Transcript.fromResults(userInputs, actual));
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
