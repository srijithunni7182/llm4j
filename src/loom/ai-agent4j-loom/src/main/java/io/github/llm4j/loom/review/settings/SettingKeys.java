package io.github.llm4j.loom.review.settings;

import io.github.llm4j.loom.autonomy.expr.Suggest;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The keys {@code review.properties} accepts. A misspelt key is an error that names the nearest
 * valid one, so a typo never silently turns a setting off.
 */
final class SettingKeys {

    private SettingKeys() {}

    /** Keys that stand alone. */
    static final List<String> LITERALS =
            List.of(
                    "fail-on",
                    "lenses",
                    "heuristics",
                    "packs",
                    "notice",
                    "notice.timeout",
                    "journal");

    /** Families whose second part is free: {@code assume.foreach.items}, {@code data.regions}, … */
    static final List<String> FAMILIES =
            List.of("assume.", "fix.", "data.", "blast.", "workflow.", "cost.", "oversight.");

    /** A rule id, optionally followed by one of the per-rule suffixes. */
    private static final Pattern RULE =
            Pattern.compile("([A-Z]{1,6}-?\\d{2,4})(\\.(severity|suppress|accept))?");

    /** Null when the key is acceptable, else the message to show. */
    static String problem(String key) {
        if (LITERALS.contains(key)) return null;
        for (String f : FAMILIES) if (key.startsWith(f) && key.length() > f.length()) return null;
        if (RULE.matcher(key).matches()) return null;
        String hint = Suggest.hint(key, LITERALS);
        return "unknown setting '" + key + "'" + hint;
    }
}
