package io.github.llm4j.loom.execution;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code pii:} setting of an internal node, applied with the masker an agent's {@code guard}
 * uses: {@code mask} replaces personal data before the text leaves, {@code block} refuses text that
 * carries any, {@code warn} sends it and records the finding. Findings name types and counts, never
 * values.
 */
final class PiiGuard {

    private static final io.github.llm4j.privacy.PIIDetector DETECTOR =
            new io.github.llm4j.privacy.RegexPIIDetector();

    private PiiGuard() {}

    /**
     * @param kind the audit key for the node's name: {@code classifier} or {@code extractor}
     * @param inTrace what the trace line says the text was, such as {@code "the state of Intent"}
     * @param inFailure what a blocked step names, such as {@code "the state for Intent"}
     * @param destination what the text is kept from, such as {@code "the backend"}
     */
    static String apply(
            HarnessExecutor executor,
            String kind,
            String name,
            String mode,
            String inTrace,
            String inFailure,
            String destination,
            String text) {
        if (mode == null) return text;
        Map<io.github.llm4j.privacy.PIIType, Integer> found =
                new java.util.EnumMap<>(io.github.llm4j.privacy.PIIType.class);
        for (io.github.llm4j.privacy.PIIEntity e : DETECTOR.detect(text).getEntities()) {
            if (io.github.llm4j.privacy.MaskingLLMClient.PERSONAL.contains(e.getType()))
                found.merge(e.getType(), 1, Integer::sum);
        }
        if (found.isEmpty()) return text;
        String types =
                String.join(
                        ", ",
                        new java.util.TreeSet<>(found.keySet().stream().map(Enum::name).toList()));
        String event =
                switch (mode) {
                    case "mask" -> "pii_masked";
                    case "block" -> "pii_blocked";
                    default -> "pii_detected";
                };
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(kind, name);
        data.put("step", executor.currentStep());
        data.put(
                "types",
                new java.util.TreeMap<>(
                                found.entrySet().stream()
                                        .collect(
                                                java.util.stream.Collectors.toMap(
                                                        e -> e.getKey().name(),
                                                        Map.Entry::getValue)))
                        .toString());
        data.put("event", event);
        executor.audit(event, data);
        executor.trace(
                TraceEvent.GUARD,
                null,
                event.replace('_', ' ') + " in " + inTrace + ": " + data.get("types"),
                data);
        if (mode.equals("block")) {
            throw new StepFailure(
                    "guard: "
                            + inFailure
                            + " contains personal data ("
                            + types
                            + "); pii: block keeps it from "
                            + destination,
                    null);
        }
        return mode.equals("mask")
                ? io.github.llm4j.privacy.MaskingLLMClient.mask(
                        text, DETECTOR, io.github.llm4j.privacy.MaskingLLMClient.PERSONAL, null)
                : text;
    }
}
