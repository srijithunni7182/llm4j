package io.github.llm4j.loom.review.settings;

import io.github.llm4j.loom.review.model.Lens;
import io.github.llm4j.loom.review.model.Severity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What a project says about its review: which lenses run, which rules are off or louder or quieter,
 * whether heuristic rules run, the severity that fails a build, and the declared facts and
 * assumptions rules read ({@code setting.*}). Read from {@code review.properties}; immutable.
 */
public final class ReviewSettings {

    /** The file name looked for beside a script. */
    public static final String FILE_NAME = "review.properties";

    private final Optional<Severity> failOn;
    private final Set<Lens> lenses;
    private final boolean heuristics;
    private final Map<String, RuleSetting> rules;
    private final Map<String, String> values;
    private final List<Suppression> suppressions;
    private final java.time.LocalDate today;
    private final Set<String> baseline;

    private ReviewSettings(
            Optional<Severity> failOn,
            Set<Lens> lenses,
            boolean heuristics,
            Map<String, RuleSetting> rules,
            Map<String, String> values,
            List<Suppression> suppressions,
            java.time.LocalDate today,
            Set<String> baseline) {
        this.failOn = failOn;
        this.lenses = Collections.unmodifiableSet(EnumSet.copyOf(lenses));
        this.heuristics = heuristics;
        this.rules = Collections.unmodifiableMap(new LinkedHashMap<>(rules));
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        this.suppressions = List.copyOf(suppressions);
        this.today = today;
        this.baseline = Set.copyOf(baseline);
    }

    /** Nothing declared: default lenses, advisory (never fails), heuristics off. */
    public static ReviewSettings defaults() {
        return new ReviewSettings(
                Optional.empty(),
                LensSelection.defaults(),
                false,
                Map.of(),
                Map.of(),
                List.of(),
                null,
                Set.of());
    }

    /** Reads the file beside {@code script} if there is one, else the defaults. */
    public static ReviewSettings besides(Path script) {
        Path dir = script.toAbsolutePath().getParent();
        Path file = dir == null ? Path.of(FILE_NAME) : dir.resolve(FILE_NAME);
        return Files.isRegularFile(file) ? load(file) : defaults();
    }

    /** Reads a settings file. @throws InvalidSettingsException listing every problem found */
    public static ReviewSettings load(Path file) {
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException e) {
            throw new InvalidSettingsException(
                    List.of(file + " could not be read: " + e.getMessage()));
        }
        return parse(file.toString(), text);
    }

    /** Parses settings text; {@code name} is only used in messages. */
    public static ReviewSettings parse(String name, String text) {
        PropertiesFile f = PropertiesFile.parse(name, text);
        List<String> problems = new ArrayList<>(f.errors());
        Optional<Severity> failOn = Optional.empty();
        Set<Lens> lenses = LensSelection.defaults();
        boolean heuristics = false;
        Map<String, RuleSetting> rules = new LinkedHashMap<>();
        List<Suppression> suppressions = new ArrayList<>();
        for (PropertiesFile.Entry e : f.entries()) {
            String where = name + ":" + e.line() + ": ";
            String bad = SettingKeys.problem(e.key());
            if (bad != null) {
                problems.add(where + bad);
                continue;
            }
            try {
                switch (e.key()) {
                    case "fail-on" -> failOn = failOn(e.value());
                    case "lenses" -> lenses = LensSelection.resolve(e.value(), lenses);
                    case "heuristics" -> heuristics = onOff(e.value());
                    default -> {
                        if (isRuleKey(e.key())) {
                            applyRule(rules, e.key(), e.value());
                            if (e.key().endsWith(".suppress") || e.key().endsWith(".accept"))
                                suppressions.add(
                                        Suppression.parse(
                                                e.key().substring(e.key().indexOf('.') + 1),
                                                e.key().substring(0, e.key().indexOf('.')),
                                                e.value(),
                                                e.line()));
                        }
                    }
                }
            } catch (IllegalArgumentException ex) {
                problems.add(where + ex.getMessage());
            }
        }
        if (!problems.isEmpty()) throw new InvalidSettingsException(problems);
        return new ReviewSettings(
                failOn, lenses, heuristics, rules, f.values(), suppressions, null, Set.of());
    }

    private static boolean isRuleKey(String key) {
        if (SettingKeys.LITERALS.contains(key)) return false;
        for (String fam : SettingKeys.FAMILIES) if (key.startsWith(fam)) return false;
        return true;
    }

    private static void applyRule(Map<String, RuleSetting> rules, String key, String value) {
        int dot = key.indexOf('.');
        String id = dot < 0 ? key : key.substring(0, dot);
        RuleSetting current = rules.getOrDefault(id, RuleSetting.NONE);
        if (dot < 0) {
            rules.put(id, current.withEnabled(onOff(value)));
        } else if (key.substring(dot + 1).equals("severity")) {
            Severity s =
                    Severity.parse(value)
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "severity takes high, medium, low or info, not '"
                                                            + value
                                                            + "'"));
            rules.put(id, current.withSeverity(s));
        } // suppress entries are read separately (see suppressions()); accept comes with 9e
    }

    private static boolean onOff(String v) {
        return switch (v.toLowerCase(java.util.Locale.ROOT)) {
            case "on", "true", "yes" -> true;
            case "off", "false", "no" -> false;
            default -> throw new IllegalArgumentException("expected on or off, not '" + v + "'");
        };
    }

    private static Optional<Severity> failOn(String v) {
        if (v.equalsIgnoreCase("none")) return Optional.empty();
        return Optional.of(
                Severity.parse(v)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "fail-on takes high, medium, low, info or none, not '"
                                                        + v
                                                        + "'")));
    }

    // ---- reading

    /** The severity at which a review exits 1, or empty when review is advisory. */
    public Optional<Severity> failOn() {
        return failOn;
    }

    /** The lenses that run. */
    public Set<Lens> lenses() {
        return lenses;
    }

    public boolean lensOn(Lens lens) {
        return lenses.contains(lens);
    }

    /** True when heuristic rules run without being named. */
    public boolean heuristics() {
        return heuristics;
    }

    /** What the project says about {@code ruleId}. */
    public RuleSetting rule(String ruleId) {
        return rules.getOrDefault(ruleId, RuleSetting.NONE);
    }

    /** Values a project need not declare: the text fixes insert, which a project may override. */
    private static final Map<String, String> DEFAULTS =
            Map.of(
                    "fix.retry", "retry 2 backoff 2s timeout 60s",
                    "fix.loop.max", "max 5",
                    "data.prompt.max-tokens", "2000",
                    "data.loop.incident", "20");

    /**
     * A value such as {@code data.regions}: what the project declared, else the built-in default
     * for the few settings that have one, else empty.
     */
    public Optional<String> value(String key) {
        String declared = values.get(key);
        return Optional.ofNullable(declared != null ? declared : DEFAULTS.get(key));
    }

    /** The project's suppressions, in file order. */
    public List<Suppression> suppressions() {
        return suppressions;
    }

    /** Every key and value the project declared, in file order (not the defaults). */
    public Map<String, String> values() {
        return values;
    }

    // ---- derived copies (command-line options)

    public ReviewSettings withFailOn(Optional<Severity> level) {
        return new ReviewSettings(
                level, lenses, heuristics, rules, values, suppressions, today, baseline);
    }

    /** Applies a {@code --lens} spec. @throws IllegalArgumentException when it is not valid */
    public ReviewSettings withLenses(String spec) {
        return new ReviewSettings(
                failOn,
                LensSelection.resolve(spec, lenses),
                heuristics,
                rules,
                values,
                suppressions,
                today,
                baseline);
    }

    /**
     * A copy with one more value, such as {@code assume.loop.iterations}, over what the file said.
     */
    public ReviewSettings withValue(String key, String value) {
        Map<String, String> v = new LinkedHashMap<>(values);
        v.put(key, value);
        return new ReviewSettings(
                failOn, lenses, heuristics, rules, v, suppressions, today, baseline);
    }

    /** A copy that knows today's date, so that an accept with an {@code until} date can expire. */
    public ReviewSettings withToday(java.time.LocalDate day) {
        return new ReviewSettings(
                failOn, lenses, heuristics, rules, values, suppressions, day, baseline);
    }

    /** A copy that treats the findings with these fingerprints as baselined. */
    public ReviewSettings withBaseline(Set<String> fingerprints) {
        return new ReviewSettings(
                failOn, lenses, heuristics, rules, values, suppressions, today, fingerprints);
    }

    /** Today's date, or null when the caller did not give one (no entry then expires). */
    public java.time.LocalDate today() {
        return today;
    }

    /** The fingerprints recorded in the baseline. */
    public Set<String> baseline() {
        return baseline;
    }

    public ReviewSettings withHeuristics(boolean on) {
        return new ReviewSettings(failOn, lenses, on, rules, values, suppressions, today, baseline);
    }
}
