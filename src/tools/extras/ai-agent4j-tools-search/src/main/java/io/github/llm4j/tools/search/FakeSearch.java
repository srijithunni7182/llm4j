package io.github.llm4j.tools.search;

import io.github.llm4j.tools.HttpSupport;
import java.util.ArrayList;
import java.util.List;

/**
 * A provider that calls nothing and answers every query with the same made-up pages
 * ({@code provider: fake} where a test lists it in its {@code META-INF/services}). For a workflow's
 * tests and for this module's: it is not listed in the module's own services file, so a script
 * cannot pick it by accident in production.
 */
public final class FakeSearch implements SearchProvider {

    @Override
    public String name() {
        return "fake";
    }

    @Override
    public String host() {
        return "search.invalid";
    }

    @Override
    public boolean needsKey() {
        return false;
    }

    @Override
    public List<SearchHit> search(String query, int max, String apiKey, HttpSupport http) {
        List<SearchHit> hits = new ArrayList<>();
        for (int i = 1; i <= Math.min(max, 3); i++)
            hits.add(
                    new SearchHit(
                            "Result " + i + " for " + query,
                            "https://example.com/" + i,
                            "A page about " + query + "."));
        return hits;
    }
}
