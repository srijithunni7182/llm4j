package io.github.llm4j.loom.review;

import io.github.llm4j.loom.review.adapt.AuditCatalog;
import io.github.llm4j.loom.review.adapt.AuditSource;
import io.github.llm4j.loom.review.adapt.ValidityCatalog;
import io.github.llm4j.loom.review.adapt.ValiditySource;
import io.github.llm4j.loom.review.cost.CostEstimator;
import io.github.llm4j.loom.review.cost.CostRules;
import io.github.llm4j.loom.review.engine.FindingSource;
import io.github.llm4j.loom.review.engine.Overlap;
import io.github.llm4j.loom.review.engine.ReviewEngine;
import io.github.llm4j.loom.review.engine.ReviewRule;
import io.github.llm4j.loom.review.engine.RuleCatalog;
import io.github.llm4j.loom.review.pack.DataRuleSource;
import io.github.llm4j.loom.review.pack.Pack;
import io.github.llm4j.loom.review.pack.PackCatalog;
import io.github.llm4j.loom.review.pack.PackLoader;
import io.github.llm4j.loom.review.pack.PackSet;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import java.nio.file.Path;
import java.util.ArrayList;
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
        return sources(PackSet.coreOnly(corePack()));
    }

    /** The audit, the validator and the rules of the given packs. */
    public static List<FindingSource> sources(PackSet packs) {
        return List.of(new AuditSource(), new ValiditySource(), new DataRuleSource(packs.packs()));
    }

    /**
     * The packs a script's project has pinned (core alone when it pinned none).
     *
     * @throws io.github.llm4j.loom.review.pack.PackException listing every problem
     */
    public static PackSet packs(ReviewSettings settings, Path base) {
        return PackSet.resolve(settings.value("packs"), base, corePack());
    }

    /** An engine over the audit, the validator and the given packs. */
    public static ReviewEngine engine(PackSet packs) {
        return engine(packs, List.of());
    }

    /** As {@link #engine(PackSet)}, with Java rules the user brought in a jar. */
    public static ReviewEngine engine(PackSet packs, List<ReviewRule> javaRules) {
        return engine(packs, javaRules, null);
    }

    /** As above, with an estimator so that the report says what the workflows cost. */
    public static ReviewEngine engine(
            PackSet packs, List<ReviewRule> javaRules, CostEstimator estimator) {
        List<FindingSource> all = new ArrayList<>(sources(packs));
        if (estimator != null) all.add(new CostRules(estimator));
        all.addAll(javaRules);
        return new ReviewEngine(all, OVERLAPS, estimator);
    }

    /**
     * Rules that describe one cause from two angles; the first of each pair absorbs the second. A
     * rule joins this list when it is added beside a security audit rule that says the same thing.
     */
    static final List<Overlap> OVERLAPS = List.of();

    /** An engine over the default sources. */
    public static ReviewEngine engine() {
        return new ReviewEngine(defaultSources());
    }

    /** What is known about every default rule. */
    public static RuleCatalog catalog() {
        return catalog(PackSet.coreOnly(corePack()));
    }

    /** What is known about the audit and validator rules and every rule of the given packs. */
    public static RuleCatalog catalog(PackSet packs) {
        return catalog(packs, List.of());
    }

    /** As {@link #catalog(PackSet)}, with the rules of Java rule jars. */
    public static RuleCatalog catalog(PackSet packs, List<ReviewRule> javaRules) {
        RuleCatalog c =
                new RuleCatalog()
                        .addAll(AuditCatalog.rules())
                        .addAll(ValidityCatalog.rules())
                        .addAll(CostRules.catalog());
        for (Pack p : packs.packs()) c.addAll(PackCatalog.rules(p));
        for (ReviewRule r : javaRules) c.addAll(r.rules());
        return c;
    }
}
