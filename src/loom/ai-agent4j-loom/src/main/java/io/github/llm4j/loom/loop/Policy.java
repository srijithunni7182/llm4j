package io.github.llm4j.loom.loop;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * What a team allows to be captured, from {@code capture.properties} beside the script (or at the
 * repository root): {@code workflows} (only these), {@code deny-fields} (never capture these input
 * names), {@code max-age} (no run older than this, like {@code 30d}).
 */
public record Policy(Set<String> workflows, Set<String> denyFields, Duration maxAge) {

    public static final String FILE = "capture.properties";

    public static Policy none() {
        return new Policy(Set.of(), Set.of(), null);
    }

    public static Policy load(Path script) throws IOException {
        Path beside = script.toAbsolutePath().normalize().getParent().resolve(FILE);
        Path file = Files.isRegularFile(beside) ? beside : Consent.root(script).resolve(FILE);
        if (!Files.isRegularFile(file)) return none();
        Properties p = new Properties();
        try (var in = Files.newBufferedReader(file)) {
            p.load(in);
        }
        return new Policy(
                list(p.getProperty("workflows")),
                list(p.getProperty("deny-fields")),
                age(p.getProperty("max-age")));
    }

    /** Why this run may not be captured, one line each; empty when it may. */
    public List<String> violations(RunRecord run, Instant now) {
        List<String> out = new ArrayList<>();
        if (!workflows.isEmpty() && !workflows.contains(run.workflow()))
            out.add(
                    "capture.properties allows only the workflows "
                            + workflows
                            + ", and this run is of "
                            + run.workflow());
        if (maxAge != null && Duration.between(run.modified(), now).compareTo(maxAge) > 0)
            out.add(
                    "capture.properties allows runs up to "
                            + maxAge.toDays()
                            + " days old (max-age), and this one is older");
        return out;
    }

    private static Set<String> list(String s) {
        if (s == null || s.isBlank()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).forEach(out::add);
        return out;
    }

    private static Duration age(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim().toLowerCase();
        long n = Long.parseLong(t.substring(0, t.length() - 1));
        return switch (t.charAt(t.length() - 1)) {
            case 'd' -> Duration.ofDays(n);
            case 'h' -> Duration.ofHours(n);
            case 'w' -> Duration.ofDays(7 * n);
            default -> throw new IllegalArgumentException(
                    "max-age: write a number and d, h or w, like 30d");
        };
    }
}
