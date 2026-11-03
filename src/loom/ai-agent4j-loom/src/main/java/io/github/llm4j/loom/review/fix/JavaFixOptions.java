package io.github.llm4j.loom.review.fix;

import java.util.List;
import java.util.Map;

/** The Java-coded fix options, by the rule they belong to. */
public final class JavaFixOptions {

    private static final Map<String, List<JavaFixOption>> BY_RULE =
            Map.of("LP09", List.of(new ToExtractorFix()), "LP10", List.of(new SplitFix()));

    private JavaFixOptions() {}

    /** The options for findings of {@code ruleId}; empty for most rules. */
    public static List<JavaFixOption> forRule(String ruleId) {
        return BY_RULE.getOrDefault(ruleId, List.of());
    }
}
