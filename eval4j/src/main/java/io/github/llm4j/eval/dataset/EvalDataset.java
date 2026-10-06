package io.github.llm4j.eval.dataset;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A folder of golden dataset files: one YAML list of {@link EvalScenario} per target ({@code researcher.yaml}, {@code workflow.yaml}), plus an
 * optional {@code dataset.yaml} that names the quality dimensions the dataset uses (so a report shows each of them even when nothing evaluated it).
 *
 * <p>Loading never stops at the first thing wrong: {@link #problems()} lists a file that cannot be read, a scenario with no input, a repeated id,
 * and a dimension that {@code dataset.yaml} does not declare.
 */
public record EvalDataset(Path dir, Map<String, List<EvalScenario>> files, Map<String, String> dimensions, List<Problem> problems) {

    /** The file that names the dimensions. */
    public static final String DIMENSIONS_FILE = "dataset.yaml";

    /** The file of recorded tool answers (read by {@code weave eval}); not a scenario list. */
    public static final String FIXTURES_FILE = "fixtures.yaml";

    /** Something wrong with the dataset; {@code fatal} when a file could not be read at all. */
    public record Problem(String file, String scenario, String message, boolean fatal) {
        @Override
        public String toString() {
            return file + (scenario == null ? "" : " [" + scenario + "]") + ": " + message;
        }
    }

    public EvalDataset {
        files = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(files));
        dimensions = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(dimensions));
        problems = List.copyOf(problems);
    }

    /** Reads the folder. A missing folder is one fatal problem and nothing else. */
    public static EvalDataset load(Path dir) {
        List<Problem> problems = new ArrayList<>();
        Map<String, List<EvalScenario>> files = new TreeMap<>();
        Map<String, String> dimensions = new LinkedHashMap<>();
        if (!Files.isDirectory(dir)) {
            problems.add(new Problem(dir.toString(), null, "the dataset folder does not exist", true));
            return new EvalDataset(dir, files, dimensions, problems);
        }
        List<Path> yaml = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.{yaml,yml}")) {
            stream.forEach(yaml::add);
        } catch (IOException e) {
            problems.add(new Problem(dir.toString(), null, "cannot list the folder: " + e.getMessage(), true));
        }
        yaml.sort(java.util.Comparator.comparing(Path::toString));
        for (Path file : yaml) {
            String name = file.getFileName().toString();
            String stem = name.substring(0, name.lastIndexOf('.'));
            if (name.equals(DIMENSIONS_FILE)) {
                readDimensions(file, dimensions, problems);
            } else if (name.equals(FIXTURES_FILE)) {
                continue;
            } else {
                try (InputStream in = Files.newInputStream(file)) {
                    files.put(stem, EvalScenarios.fromYaml(in).stream().map(EvalScenarios::normalized).toList());
                } catch (IOException | EvalDatasetException e) {
                    problems.add(new Problem(name, null, "cannot be read: " + rootMessage(e), true));
                }
            }
        }
        check(files, dimensions, problems);
        return new EvalDataset(dir, files, dimensions, problems);
    }

    /** All scenarios of all files, in file order. */
    public List<EvalScenario> all() {
        List<EvalScenario> out = new ArrayList<>();
        files.values().forEach(out::addAll);
        return out;
    }

    private static void check(Map<String, List<EvalScenario>> files, Map<String, String> dimensions, List<Problem> problems) {
        Map<String, String> seen = new HashMap<>();
        for (var entry : files.entrySet()) {
            String file = entry.getKey() + ".yaml";
            for (EvalScenario s : entry.getValue()) {
                String label = s.id() != null ? s.id() : s.name();
                if (s.input() == null || s.input().isBlank()) {
                    problems.add(new Problem(file, label, "has no input", false));
                }
                if (s.id() != null) {
                    String first = seen.putIfAbsent(s.id(), file);
                    if (first != null) problems.add(new Problem(file, label, "repeats the id " + s.id() + " (first in " + first + ")", false));
                }
                if (!dimensions.isEmpty() && s.dimensions() != null) {
                    for (String d : s.dimensions()) {
                        if (!dimensions.containsKey(d)) {
                            problems.add(new Problem(file, label, "uses the dimension " + d + ", which " + DIMENSIONS_FILE + " does not declare (it has " + String.join(", ", dimensions.keySet()) + ")", false));
                        }
                    }
                }
            }
        }
    }

    private static void readDimensions(Path file, Map<String, String> into, List<Problem> problems) {
        try (InputStream in = Files.newInputStream(file)) {
            Object root = new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory()).readValue(in, Object.class);
            Object dims = root instanceof Map<?, ?> m ? m.get("dimensions") : null;
            if (dims instanceof Map<?, ?> named) {
                named.forEach((k, v) -> into.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
            } else if (dims instanceof List<?> list) {
                list.forEach(k -> into.put(String.valueOf(k), ""));
            } else if (root != null) {
                problems.add(new Problem(file.getFileName().toString(), null, "needs a dimensions: mapping (name: what it means) or list", true));
            }
        } catch (IOException e) {
            problems.add(new Problem(file.getFileName().toString(), null, "cannot be read: " + rootMessage(e), true));
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m.lines().findFirst().orElse(m);
    }
}
