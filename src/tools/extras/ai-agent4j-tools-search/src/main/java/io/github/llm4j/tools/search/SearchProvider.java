package io.github.llm4j.tools.search;

import io.github.llm4j.tools.HttpSupport;
import java.io.IOException;
import java.util.List;

/**
 * A web search service the {@code search} tool asks (phase 35, D18). None is built in: a script
 * names one with {@code provider:}, because a provider means a key, terms of use and a cost that
 * are the user's to choose. A provider is found with {@link java.util.ServiceLoader} when its jar is
 * on the class path, listed in {@code META-INF/services/io.github.llm4j.tools.search.SearchProvider}.
 *
 * <p>A provider calls one host. The tool lets it reach that host and nothing else, over HTTPS, and
 * sends every request through {@link HttpSupport}, so the SSRF and size guards hold for every
 * provider.
 */
public interface SearchProvider {

    /** The name a script gives in {@code provider:}, e.g. {@code brave}. */
    String name();

    /** The one host this provider calls, e.g. {@code api.search.brave.com}. */
    String host();

    /** Whether this provider needs {@code api_key:}. */
    default boolean needsKey() {
        return true;
    }

    /**
     * Runs one query.
     *
     * @param query what the agent asked, already bounded in length
     * @param max the most results wanted; a provider may return fewer
     * @param apiKey the key, or null when {@link #needsKey()} is false
     * @param http the only way to reach the network, limited to {@link #host()}
     */
    List<SearchHit> search(String query, int max, String apiKey, HttpSupport http)
            throws IOException;
}
