package io.github.llm4j.loom.review.settings;

import io.github.llm4j.loom.review.model.Severity;
import java.util.Optional;

/**
 * What a project says about one rule: turned off, turned on (for an off-by-default rule), or
 * another severity.
 */
public record RuleSetting(Boolean enabled, Severity severity) {

    /** A rule that is not mentioned. */
    public static final RuleSetting NONE = new RuleSetting(null, null);

    public Optional<Severity> severityOverride() {
        return Optional.ofNullable(severity);
    }

    RuleSetting withEnabled(boolean on) {
        return new RuleSetting(on, severity);
    }

    RuleSetting withSeverity(Severity s) {
        return new RuleSetting(enabled, s);
    }
}
