package io.github.llm4j.loom.review.fact;

import io.github.llm4j.loom.autonomy.expr.Suggest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The models the library names, from the short list of checked models it ships (the same list as
 * the context windows). It is a catalog of what is known, not of what exists: a model missing from
 * it is not wrong, only unfamiliar, and the review says so only when the name is a near miss of a
 * known one (a likely typo).
 */
final class ModelCatalog {

    private static final Set<String> KNOWN = load();

    private ModelCatalog() {}

    private static Set<String> load() {
        Set<String> ids = new LinkedHashSet<>();
        try (InputStream in =
                io.github.llm4j.context.ContextWindows.class.getResourceAsStream(
                        "windows.properties")) {
            if (in != null)
                new String(in.readAllBytes(), StandardCharsets.UTF_8)
                        .lines()
                        .map(String::strip)
                        .filter(l -> !l.isEmpty() && !l.startsWith("#") && l.contains("="))
                        .map(l -> l.substring(0, l.indexOf('=')).strip().toLowerCase(Locale.ROOT))
                        .forEach(ids::add);
        } catch (IOException e) {
            // no catalog: every model is unfamiliar and nothing is flagged
        }
        return ids;
    }

    /** True when the catalog lists the model (with or without its provider prefix). */
    static boolean knows(String id) {
        String key = bare(id);
        return KNOWN.stream().anyMatch(k -> bare(k).equals(key));
    }

    /** The known model the name is probably a misspelling of, or empty when there is none. */
    static String nearMiss(String id) {
        if (KNOWN.isEmpty() || knows(id)) return "";
        String near = Suggest.nearest(bare(id), KNOWN.stream().map(ModelCatalog::bare).toList());
        return near == null ? "" : near;
    }

    private static String bare(String id) {
        String t = id.toLowerCase(Locale.ROOT);
        int slash = t.indexOf('/');
        return slash >= 0 ? t.substring(slash + 1) : t;
    }
}
