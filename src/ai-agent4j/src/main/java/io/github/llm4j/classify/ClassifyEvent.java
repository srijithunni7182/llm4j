package io.github.llm4j.classify;

import java.util.Map;

/**
 * One classify call, as a listener sees it. Numbers and names only: never the state, an answer or a
 * rationale.
 *
 * @param usage tokens, latency, backend and model (zeros when the call failed before a reply)
 * @param questions how many questions were asked
 * @param outcome whether the call produced a result
 * @param errorType the exception's simple class name when it failed, else null
 * @param labels the chosen label of every choice question; empty when the call failed. Labels are
 *     the developer's own words, not the state
 */
public record ClassifyEvent(
        ClassifyUsage usage,
        int questions,
        Outcome outcome,
        String errorType,
        Map<String, String> labels) {

    public enum Outcome {
        OK,
        FAILED
    }

    public ClassifyEvent {
        labels = labels == null ? Map.of() : Map.copyOf(labels);
    }
}
