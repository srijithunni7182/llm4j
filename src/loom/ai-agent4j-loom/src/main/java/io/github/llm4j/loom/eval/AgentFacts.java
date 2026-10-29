package io.github.llm4j.loom.eval;

import java.math.BigDecimal;

/**
 * What one agent did in a scenario's run: how often it was asked, what it used and what went wrong
 * on the way. These are facts from the trace, not judgments.
 *
 * @param agent the agent's name
 * @param calls successful model calls
 * @param promptTokens tokens sent
 * @param completionTokens tokens written
 * @param cost what it cost when prices are known, else null
 * @param cutAnswers answers a provider cut short at the output limit
 * @param fallbacks times a step of this agent took its {@code on_failure} fallback
 */
public record AgentFacts(
        String agent,
        long calls,
        long promptTokens,
        long completionTokens,
        BigDecimal cost,
        long cutAnswers,
        long fallbacks) {

    public long tokens() {
        return promptTokens + completionTokens;
    }
}
