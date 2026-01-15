package io.github.llm4j.fairness;

import java.util.List;

/**
 * Interface for monitoring and detecting bias in AI agent outputs.
 * Implementations can use various techniques including pattern matching,
 * ML models, or external bias detection services.
 * 
 * This interface provides hooks for xAI compliance without prescribing
 * a specific bias detection methodology.
 */
public interface BiasMonitor {

    /**
     * Analyzes text for potential bias.
     *
     * @param text    the text to analyze
     * @param context optional context about where the text came from
     * @return list of detected bias events (empty if no bias detected)
     */
    List<BiasEvent> detectBias(String text, BiasContext context);

    /**
     * Convenience method to detect bias without context.
     *
     * @param text the text to analyze
     * @return list of detected bias events
     */
    default List<BiasEvent> detectBias(String text) {
        return detectBias(text, BiasContext.empty());
    }

    /**
     * Checks if the detected bias should trigger an alert/intervention.
     *
     * @param events list of bias events
     * @return true if intervention is recommended
     */
    default boolean shouldIntervene(List<BiasEvent> events) {
        return events.stream()
                .anyMatch(event -> event.getSeverity() == BiasSeverity.HIGH ||
                        event.getSeverity() == BiasSeverity.CRITICAL);
    }
}
