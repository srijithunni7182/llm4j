package io.github.llm4j.loom.loop;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;

/**
 * What a team has chosen to check in CI, from {@code gate.properties} beside the script. A missing
 * file means the defaults: the checks that cost nothing and need no data.
 */
public record GateConfig(
        boolean check,
        String reviewFailOn,
        boolean test,
        Integer minCoverage,
        boolean requireApproved,
        boolean regress,
        String store,
        String since,
        int limit,
        String regressFailOn,
        boolean eval) {

    public static final String FILE = "gate.properties";
    private static final Set<String> LEVELS = Set.of("high", "medium", "low", "off");

    public static GateConfig defaults() {
        return new GateConfig(
                true, "high", true, null, false, true, null, null, 200, "flip-unsafe", false);
    }

    /**
     * Reads the file; every key is checked, and an unknown one is an error (a typo must not go
     * unnoticed).
     */
    public static GateConfig load(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) return defaults();
        Properties p = new Properties();
        try (var in = Files.newBufferedReader(file)) {
            p.load(in);
        }
        Set<String> known =
                Set.of(
                        "check",
                        "review",
                        "test",
                        "test.min-coverage",
                        "test.require-approved",
                        "regress",
                        "regress.store",
                        "regress.since",
                        "regress.limit",
                        "regress.fail-on",
                        "regress.max-cost",
                        "eval");
        for (String k : p.stringPropertyNames())
            if (!known.contains(k))
                throw new IllegalArgumentException(
                        file.getFileName()
                                + ": unknown key \""
                                + k
                                + "\"; the keys are "
                                + new java.util.TreeSet<>(known)
                                + ".");
        String review = value(p, "review", "fail-on:high");
        String level =
                review.equals("false") || review.equals("off")
                        ? "off"
                        : review.replaceFirst("^fail-on:", "");
        if (!LEVELS.contains(level))
            throw new IllegalArgumentException(
                    file.getFileName()
                            + ": review = "
                            + review
                            + " is not fail-on:high, fail-on:medium, fail-on:low or off.");
        return new GateConfig(
                bool(p, "check", true),
                level,
                bool(p, "test", true),
                p.containsKey("test.min-coverage")
                        ? Integer.valueOf(p.getProperty("test.min-coverage").trim())
                        : null,
                bool(p, "test.require-approved", false),
                bool(p, "regress", true),
                p.getProperty("regress.store") == null
                        ? null
                        : p.getProperty("regress.store").trim(),
                p.getProperty("regress.since") == null
                        ? null
                        : p.getProperty("regress.since").trim(),
                Integer.parseInt(value(p, "regress.limit", "200")),
                value(p, "regress.fail-on", "flip-unsafe"),
                bool(p, "eval", false));
    }

    private static String value(Properties p, String key, String dflt) {
        String v = p.getProperty(key);
        return v == null || v.isBlank() ? dflt : v.trim();
    }

    private static boolean bool(Properties p, String key, boolean dflt) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) return dflt;
        String t = v.trim().toLowerCase();
        if (t.equals("true")) return true;
        if (t.equals("false")) return false;
        throw new IllegalArgumentException(key + " = " + v + " is not true or false.");
    }
}
