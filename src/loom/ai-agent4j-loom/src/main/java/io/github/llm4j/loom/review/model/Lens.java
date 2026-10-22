package io.github.llm4j.loom.review.model;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * The aspect of a workflow a finding is about. Nine lenses are on by default and four are optional
 * (off until asked). The declaration order is the ranking order of lenses: when two findings are
 * equally severe, the one in the earlier lens is listed first.
 */
public enum Lens {
    SECURITY("security", "Security", false),
    PRIVACY("privacy", "Privacy and compliance", false),
    DURABILITY("durability", "Durability", false),
    RELIABILITY("reliability", "Reliability", false),
    COST("cost", "Cost and performance", false),
    VALIDITY("validity", "Validity", false),
    PROMPTS("prompts", "Prompts", false),
    OBSERVABILITY("observability", "Observability", false),
    TESTABILITY("testability", "Testability", false),
    GOVERNANCE("governance", "Model governance", true),
    MAINTAINABILITY("maintainability", "Maintainability", true),
    OVERSIGHT("oversight", "Human oversight", true),
    BLAST_RADIUS("blast-radius", "Blast radius", true);

    private final String id;
    private final String title;
    private final boolean optional;

    Lens(String id, String title, boolean optional) {
        this.id = id;
        this.title = title;
        this.optional = optional;
    }

    /** The id used in settings, rule files and JSON: {@code security}, {@code blast-radius}, … */
    public String id() {
        return id;
    }

    /** The name shown to people. */
    public String title() {
        return title;
    }

    /** True for a lens that is off unless the project or the command line turns it on. */
    public boolean optional() {
        return optional;
    }

    /** Reads a lens id (also accepting underscores and any case); empty when it is not one. */
    public static Optional<Lens> parse(String text) {
        if (text == null) return Optional.empty();
        String t = text.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return Arrays.stream(values()).filter(l -> l.id.equals(t)).findFirst();
    }
}
