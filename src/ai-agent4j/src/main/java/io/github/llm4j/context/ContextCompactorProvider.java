package io.github.llm4j.context;

import java.util.Map;

/**
 * Makes a strategy findable by name. List an implementation in
 * {@code META-INF/services/io.github.llm4j.context.ContextCompactorProvider} (or provide it from a module) and
 * {@link ContextCompactors#byName} will find it, so a configuration file can say {@code compaction: my_strategy}.
 */
public interface ContextCompactorProvider {

    /** The strategy's name: lower case, words separated by underscores. */
    String name();

    /**
     * Builds the strategy.
     *
     * @param options settings as text, from the configuration; reject ones you do not know with an {@link IllegalArgumentException}
     * @param context anything else the strategy needs, such as a model client
     */
    ContextCompactor create(Map<String, String> options, ProviderContext context);
}
