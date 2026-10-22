package io.github.llm4j.loom.review;

import io.github.llm4j.loom.review.adapt.AuditCatalog;
import io.github.llm4j.loom.review.adapt.AuditSource;
import io.github.llm4j.loom.review.adapt.ValidityCatalog;
import io.github.llm4j.loom.review.adapt.ValiditySource;
import io.github.llm4j.loom.review.engine.FindingSource;
import io.github.llm4j.loom.review.engine.ReviewEngine;
import io.github.llm4j.loom.review.engine.RuleCatalog;
import io.github.llm4j.loom.review.pack.DataRuleSource;
import io.github.llm4j.loom.review.pack.Pack;
import io.github.llm4j.loom.review.pack.PackCatalog;
import io.github.llm4j.loom.review.pack.PackLoader;
import java.util.List;

/**
 * Assembles the review that {@code weave review} runs: which sources of findings and which rules
 * are known.
 */
public final class Reviews {

    private Reviews() {}

    /** Where the core rule pack is stored inside the jar. */
    public static final String CORE_PACK = "review-rules/core";

    private static volatile Pack core;

    /** The rule pack that ships with weave, loaded once and checked as it loads. */
    public static Pack corePack() {
        Pack p = core;
        if (p == null) {
            p = PackLoader.classpath(Reviews.class.getClassLoader(), CORE_PACK);
            core = p;
        }
        return p;
    }

    /** The sources every review uses: the security audit, the validator, and the core rule pack. */
    public static List<FindingSource> defaultSources() {
        return List.of(
                new AuditSource(), new ValiditySource(), new DataRuleSource(List.of(corePack())));
    }

    /** An engine over the default sources. */
    public static ReviewEngine engine() {
        return new ReviewEngine(defaultSources());
    }

    /** What is known about every default rule. */
    public static RuleCatalog catalog() {
        return new RuleCatalog()
                .addAll(AuditCatalog.rules())
                .addAll(ValidityCatalog.rules())
                .addAll(PackCatalog.rules(corePack()));
    }
}
