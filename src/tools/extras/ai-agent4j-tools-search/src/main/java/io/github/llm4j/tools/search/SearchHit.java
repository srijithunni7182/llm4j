package io.github.llm4j.tools.search;

import java.util.Objects;

/**
 * One search result: a page's title, its address and the provider's snippet of it. All three are
 * written by whoever published the page, so a workflow treats them as untrusted text.
 */
public record SearchHit(String title, String url, String snippet) {

    /** Checks that nothing is missing; an absent snippet is empty. */
    public SearchHit {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(url, "url");
        snippet = snippet == null ? "" : snippet;
    }
}
