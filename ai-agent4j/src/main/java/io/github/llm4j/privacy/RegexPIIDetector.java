package io.github.llm4j.privacy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Regex-based PII detector using pattern matching. Fast and lightweight implementation suitable for
 * most use cases.
 */
public class RegexPIIDetector implements PIIDetector {

    private static final Map<PIIType, Pattern> PATTERNS = new HashMap<>();

    static {
        // Email pattern
        PATTERNS.put(
                PIIType.EMAIL,
                Pattern.compile(
                        "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}",
                        Pattern.CASE_INSENSITIVE));

        // Phone patterns (US and international)
        PATTERNS.put(
                PIIType.PHONE,
                Pattern.compile(
                        "(?:\\+?1[-.]?)?\\(?([0-9]{3})\\)?[-.]?([0-9]{3})[-.]?([0-9]{4})|"
                                + // US format
                                "\\+?[0-9]{1,4}[-\\s](?:[0-9]{1,4}[-\\s]?){1,4}" // International
                        // (require at
                        // least one
                        // separator)
                        ));

        // SSN pattern (XXX-XX-XXXX)
        PATTERNS.put(PIIType.SSN, Pattern.compile("\\b[0-9]{3}-[0-9]{2}-[0-9]{4}\\b"));

        // Credit card pattern (basic Luhn algorithm not included for simplicity)
        PATTERNS.put(
                PIIType.CREDIT_CARD,
                Pattern.compile(
                        "\\b(?:4[0-9]{12}(?:[0-9]{3})?|"
                                + // Visa
                                "5[1-5][0-9]{14}|"
                                + // Mastercard
                                "3[47][0-9]{13}|"
                                + // Amex
                                "6(?:011|5[0-9]{2})[0-9]{12})\\b" // Discover
                        ));

        // IP Address pattern (IPv4)
        PATTERNS.put(PIIType.IP_ADDRESS, Pattern.compile("\\b(?:[0-9]{1,3}\\.){3}[0-9]{1,3}\\b"));

        // URL pattern
        PATTERNS.put(
                PIIType.URL,
                Pattern.compile(
                        "https?://[a-zA-Z0-9\\-._~:/?#\\[\\]@!$&'()*+,;=%]+",
                        Pattern.CASE_INSENSITIVE));
    }

    private static final List<PIIType> PRIORITY =
            List.of(
                    PIIType.CREDIT_CARD,
                    PIIType.SSN,
                    PIIType.EMAIL,
                    PIIType.URL,
                    PIIType.IP_ADDRESS,
                    PIIType.PHONE);

    @Override
    public PIIDetectionResult detect(String text) {
        if (text == null || text.isEmpty()) {
            return PIIDetectionResult.empty();
        }

        List<PIIEntity> allEntities = new ArrayList<>();

        for (Map.Entry<PIIType, Pattern> entry : PATTERNS.entrySet()) {
            PIIType type = entry.getKey();
            Pattern pattern = entry.getValue();
            Matcher matcher = pattern.matcher(text);

            while (matcher.find()) {
                allEntities.add(
                        PIIEntity.builder()
                                .type(type)
                                .value(matcher.group())
                                .startIndex(matcher.start())
                                .endIndex(matcher.end())
                                .build());
            }
        }

        // Filter overlaps
        List<PIIEntity> filteredEntities = new ArrayList<>();
        // Sort by priority then start index
        allEntities.sort(
                (e1, e2) -> {
                    int p1 = PRIORITY.indexOf(e1.getType());
                    int p2 = PRIORITY.indexOf(e2.getType());
                    if (p1 != p2) return Integer.compare(p1, p2);
                    return Integer.compare(e1.getStartIndex(), e2.getStartIndex());
                });

        for (PIIEntity entity : allEntities) {
            boolean overlaps = false;
            for (PIIEntity kept : filteredEntities) {
                if (isOverlapping(entity, kept)) {
                    overlaps = true;
                    break;
                }
            }
            if (!overlaps) {
                filteredEntities.add(entity);
            }
        }

        // Restore order by index for strict output consistency if needed,
        // but result usually doesn't guarantee order.
        // Let's sort by start index for the builder.
        filteredEntities.sort((e1, e2) -> Integer.compare(e1.getStartIndex(), e2.getStartIndex()));

        PIIDetectionResult.Builder resultBuilder = PIIDetectionResult.builder();
        filteredEntities.forEach(resultBuilder::addEntity);
        return resultBuilder.build();
    }

    private boolean isOverlapping(PIIEntity e1, PIIEntity e2) {
        return Math.max(e1.getStartIndex(), e2.getStartIndex())
                < Math.min(e1.getEndIndex(), e2.getEndIndex());
    }

    @Override
    public String mask(String text, MaskingStrategy strategy) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        PIIDetectionResult result = detect(text);
        if (!result.containsPII()) {
            return text;
        }

        // Sort entities by start index in reverse order to maintain indices during
        // replacement
        List<PIIEntity> sortedEntities = new ArrayList<>(result.getEntities());
        sortedEntities.sort((e1, e2) -> Integer.compare(e2.getStartIndex(), e1.getStartIndex()));

        StringBuilder masked = new StringBuilder(text);

        for (PIIEntity entity : sortedEntities) {
            String replacement = getMaskedValue(entity, strategy);
            masked.replace(entity.getStartIndex(), entity.getEndIndex(), replacement);
        }

        return masked.toString();
    }

    private String getMaskedValue(PIIEntity entity, MaskingStrategy strategy) {
        String value = entity.getValue();

        switch (strategy) {
            case FULL:
                return "*".repeat(value.length());

            case PARTIAL:
                if (value.length() <= 2) {
                    return "*".repeat(value.length());
                }
                return value.charAt(0)
                        + "*".repeat(value.length() - 2)
                        + value.charAt(value.length() - 1);

            case PLACEHOLDER:
                return "[" + entity.getType().name() + "]";

            default:
                return "[" + entity.getType().name() + "]";
        }
    }
}
