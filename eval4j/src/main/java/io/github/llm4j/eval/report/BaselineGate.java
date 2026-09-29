package io.github.llm4j.eval.report;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Baseline storage and comparison behind {@link EvalBaseline}. A baseline file is a JSON object
 * mapping {@code suite#test#metric} to that metric's score (the average when a test recorded the
 * metric several times).
 */
public final class BaselineGate {

    /** Floating-point slack so a drop of exactly {@code maxRegression} passes. */
    private static final double EPSILON = 1e-9;

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private BaselineGate() {}

    /** Result of comparing a run with a baseline. */
    public record GateResult(List<String> regressions, List<String> noBaseline) {
        public boolean passed() {
            return regressions.isEmpty();
        }
    }

    static String key(String suite, String test, String metric) {
        return suite + "#" + test + "#" + metric;
    }

    /** Averages records into {@code suite#test#metric → score}. */
    public static Map<String, Double> aggregate(List<EvalRecord> records) {
        Map<String, double[]> sums = new TreeMap<>();
        for (EvalRecord r : records) {
            double[] s = sums.computeIfAbsent(key(r.suite(), r.testName(), r.metric()), k -> new double[2]);
            s[0] += r.score();
            s[1]++;
        }
        Map<String, Double> out = new TreeMap<>();
        sums.forEach((k, s) -> out.put(k, s[0] / s[1]));
        return out;
    }

    public static Map<String, Double> load(Path file) {
        try {
            return MAPPER.readValue(
                    Files.readAllBytes(file), new TypeReference<TreeMap<String, Double>>() {});
        } catch (IOException e) {
            throw new IllegalStateException("Could not read baseline file " + file, e);
        }
    }

    /** Writes {@code current} entries for {@code suite}, keeping other suites' entries as they were. */
    public static void update(Path file, String suite, Map<String, Double> current) {
        Map<String, Double> merged = new TreeMap<>();
        if (Files.exists(file)) {
            merged.putAll(load(file));
        }
        merged.keySet().removeIf(k -> k.startsWith(suite + "#"));
        current.forEach(
                (k, v) -> {
                    if (k.startsWith(suite + "#")) {
                        merged.put(k, v);
                    }
                });
        try {
            AtomicFiles.write(file, MAPPER.writeValueAsBytes(merged));
        } catch (IOException e) {
            throw new IllegalStateException("Could not write baseline file " + file, e);
        }
    }

    /** Compares the current run's records for {@code suite} with {@code baseline}. */
    public static GateResult compare(
            Map<String, Double> baseline,
            Map<String, Double> current,
            String suite,
            EvalBaseline.Granularity granularity,
            double maxRegression) {
        Map<String, Double> base = restrict(baseline, suite, granularity);
        Map<String, Double> now = restrict(current, suite, granularity);
        List<String> regressions = new ArrayList<>();
        List<String> noBaseline = new ArrayList<>();
        now.forEach(
                (k, score) -> {
                    Double old = base.get(k);
                    if (old == null) {
                        noBaseline.add(display(k, granularity));
                        return;
                    }
                    double delta = score - old;
                    if (-delta > maxRegression + EPSILON) {
                        regressions.add(
                                String.format(
                                        java.util.Locale.ROOT,
                                        "%s: baseline %.3f -> current %.3f (delta %+.3f, allowed"
                                                + " drop %.3f)",
                                        display(k, granularity), old, score, delta, maxRegression));
                    }
                });
        return new GateResult(regressions, noBaseline);
    }

    /** Suite granularity collapses keys to {@code metric} averages; case keeps {@code test#metric}. */
    private static Map<String, Double> restrict(
            Map<String, Double> all, String suite, EvalBaseline.Granularity granularity) {
        Map<String, double[]> acc = new LinkedHashMap<>();
        all.forEach(
                (k, v) -> {
                    if (!k.startsWith(suite + "#")) {
                        return;
                    }
                    String rest = k.substring(suite.length() + 1); // test#metric
                    String outKey =
                            granularity == EvalBaseline.Granularity.CASE
                                    ? rest
                                    : rest.substring(rest.lastIndexOf('#') + 1);
                    double[] a = acc.computeIfAbsent(outKey, x -> new double[2]);
                    a[0] += v;
                    a[1]++;
                });
        Map<String, Double> out = new TreeMap<>();
        acc.forEach((k, a) -> out.put(k, a[0] / a[1]));
        return out;
    }

    private static String display(String key, EvalBaseline.Granularity granularity) {
        return granularity == EvalBaseline.Granularity.CASE
                ? key.replace("#", " / ")
                : key + " (suite average)";
    }

    static String readString(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
