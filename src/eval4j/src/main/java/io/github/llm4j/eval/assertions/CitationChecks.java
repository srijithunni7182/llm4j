package io.github.llm4j.eval.assertions;

import io.github.llm4j.grounding.Citation;
import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * What the citation assertions share: which site a citation points at, and the message when a set
 * of citations does not fit a rule. A citation matches a domain when its host is that domain or a
 * subdomain of it ({@code www.who.int} matches {@code who.int}); a URL that is not an address
 * matches nothing.
 */
final class CitationChecks {

    private CitationChecks() {}

    /** The host of {@code url}, lower-cased, or null when it has none. */
    static String hostOf(String url) {
        try {
            String host = URI.create(url.strip()).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The domain as written in an assertion: no scheme, no path, lower-case. */
    static String domain(String written) {
        String d = written.strip().toLowerCase(Locale.ROOT).replaceFirst("^[a-z]+://", "");
        int slash = d.indexOf('/');
        return slash < 0 ? d : d.substring(0, slash);
    }

    static boolean matches(Citation citation, String domain) {
        String host = hostOf(citation.url());
        String wanted = domain(domain);
        return host != null && (host.equals(wanted) || host.endsWith("." + wanted));
    }

    static boolean anyMatches(Citation citation, List<String> domains) {
        return domains.stream().anyMatch(d -> matches(citation, d));
    }

    static String urls(List<Citation> citations) {
        return citations.stream().map(Citation::url).toList().toString();
    }
}
