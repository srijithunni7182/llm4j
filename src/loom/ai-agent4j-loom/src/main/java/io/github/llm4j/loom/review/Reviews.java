package io.github.llm4j.loom.review;

import io.github.llm4j.loom.review.adapt.AuditCatalog;
import io.github.llm4j.loom.review.adapt.AuditSource;
import io.github.llm4j.loom.review.adapt.ValidityCatalog;
import io.github.llm4j.loom.review.adapt.ValiditySource;
import io.github.llm4j.loom.review.engine.FindingSource;
import io.github.llm4j.loom.review.engine.ReviewEngine;
import io.github.llm4j.loom.review.engine.RuleCatalog;
import java.util.List;

/**
 * Assembles the review that {@code weave review} runs: which sources of findings and which rules
 * are known.
 */
public final class Reviews {

    private Reviews() {}

    /** The sources every review uses. */
    public static List<FindingSource> defaultSources() {
        return List.of(new AuditSource(), new ValiditySource());
    }

    /** An engine over the default sources. */
    public static ReviewEngine engine() {
        return new ReviewEngine(defaultSources());
    }

    /** What is known about every default rule. */
    public static RuleCatalog catalog() {
        return new RuleCatalog().addAll(AuditCatalog.rules()).addAll(ValidityCatalog.rules());
    }
}
