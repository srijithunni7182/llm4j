package io.github.llm4j.loom.review.engine;

import java.util.List;

/**
 * A rule written in Java, for what a data rule cannot say: a loop, arithmetic across several
 * things, text analysis beyond a bounded pattern, or a new kind of analysis. It is a {@link
 * FindingSource} that also says which rules it provides, so they can be listed and explained. It is
 * found with {@link java.util.ServiceLoader} in a jar the user names with {@code --rules-jar}; a
 * jar beside the script is never loaded.
 *
 * <p>Like every source it must be a pure function of its input: no clock, no environment, no
 * network, no model and no tool.
 */
public interface ReviewRule extends FindingSource {

    /** What this class reports, one entry per rule id. */
    List<RuleInfo> rules();
}
