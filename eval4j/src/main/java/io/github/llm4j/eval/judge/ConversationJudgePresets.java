package io.github.llm4j.eval.judge;

import io.github.llm4j.LLMClient;
import java.util.List;
import java.util.Objects;

/**
 * Conversation-level judge conditions, applied to a {@link Transcript}:
 *
 * <pre>{@code
 * ConversationJudgePresets conv = ConversationJudgePresets.using(judgeClient);
 * assertThat(transcript)
 *     .is(conv.knowledgeRetention())
 *     .is(conv.roleAdherence("You are a polite banking assistant who never gives investment advice."))
 *     .is(conv.conversationCompleteness(List.of("cancel the card", "confirm the address")))
 *     .is(conv.conversationRelevancy());
 * }</pre>
 */
public final class ConversationJudgePresets {

    private static final double DEFAULT_THRESHOLD = 0.5;

    private final LLMClient judge;

    private ConversationJudgePresets(LLMClient judge) {
        this.judge = judge;
    }

    public static ConversationJudgePresets using(LLMClient judge) {
        return new ConversationJudgePresets(Objects.requireNonNull(judge, "judge cannot be null"));
    }

    /** The assistant keeps and correctly uses facts the user stated earlier. */
    public ConversationJudgeCondition knowledgeRetention() {
        return knowledgeRetention(DEFAULT_THRESHOLD);
    }

    public ConversationJudgeCondition knowledgeRetention(double threshold) {
        return base(ConversationJudgeCondition.Metric.KNOWLEDGE_RETENTION, threshold).build();
    }

    /** Every assistant turn stays within the given role, persona and constraints. */
    public ConversationJudgeCondition roleAdherence(String role) {
        return roleAdherence(role, DEFAULT_THRESHOLD);
    }

    public ConversationJudgeCondition roleAdherence(String role, double threshold) {
        return base(ConversationJudgeCondition.Metric.ROLE_ADHERENCE, threshold).role(role).build();
    }

    /** The user's goals — extracted by the judge — were satisfied by the end. */
    public ConversationJudgeCondition conversationCompleteness() {
        return base(ConversationJudgeCondition.Metric.COMPLETENESS, DEFAULT_THRESHOLD).build();
    }

    /** The given user goals were satisfied by the end (no extraction call is made). */
    public ConversationJudgeCondition conversationCompleteness(List<String> intentions) {
        return conversationCompleteness(intentions, DEFAULT_THRESHOLD);
    }

    public ConversationJudgeCondition conversationCompleteness(
            List<String> intentions, double threshold) {
        return base(ConversationJudgeCondition.Metric.COMPLETENESS, threshold)
                .intentions(intentions)
                .build();
    }

    /** Assistant turns are relevant to the recent dialogue. */
    public ConversationJudgeCondition conversationRelevancy() {
        return conversationRelevancy(DEFAULT_THRESHOLD);
    }

    public ConversationJudgeCondition conversationRelevancy(double threshold) {
        return base(ConversationJudgeCondition.Metric.RELEVANCY, threshold).build();
    }

    private ConversationJudgeCondition.Builder base(
            ConversationJudgeCondition.Metric metric, double threshold) {
        return ConversationJudgeCondition.builder(metric)
                .calls(JudgeCalls.using(judge))
                .threshold(threshold);
    }
}
