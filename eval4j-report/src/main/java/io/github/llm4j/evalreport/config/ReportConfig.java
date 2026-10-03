package io.github.llm4j.evalreport.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * What the report adds to the facts: per-dimension goals and priorities, display names, status
 * thresholds, the judge-noise band and the baseline policy. Never changes a stored result (RPT-20).
 */
public final class ReportConfig {

    /** Stakeholder priority and its weight in the weighted pass rate. */
    public enum Priority {
        CRITICAL(3),
        IMPORTANT(2),
        NICE_TO_HAVE(1),
        NONE(0);

        public final int weight;

        Priority(int weight) {
            this.weight = weight;
        }
    }

    public record DimensionConfig(String name, String blurb, double goal, Priority priority) {}

    private static final Map<String, String[]> DIM_DEFAULTS = new LinkedHashMap<>();

    static {
        DIM_DEFAULTS.put(
                "correctness", new String[] {"Correctness", "Answers match what is expected."});
        DIM_DEFAULTS.put(
                "relevancy", new String[] {"Relevancy", "Answers address what was asked."});
        DIM_DEFAULTS.put(
                "grounding",
                new String[] {
                    "Grounding", "Claims are supported by the retrieved or given context."
                });
        DIM_DEFAULTS.put(
                "retrieval",
                new String[] {"Retrieval", "The right context is found and ranked well."});
        DIM_DEFAULTS.put(
                "efficiency",
                new String[] {
                    "Efficiency", "Latency, tokens and wasted steps stay within budget."
                });
        DIM_DEFAULTS.put("safety", new String[] {"Safety", "No toxic, biased or unsafe output."});
        DIM_DEFAULTS.put(
                "reasoning",
                new String[] {"Reasoning", "The agent picks the right tools in the right order."});
        DIM_DEFAULTS.put(
                "reliability",
                new String[] {"Reliability", "Runs finish and produce well-formed output."});
        DIM_DEFAULTS.put(
                "conversation", new String[] {"Conversation", "Multi-turn behaviour holds up."});
        DIM_DEFAULTS.put(
                "orchestration",
                new String[] {"Orchestration", "Workflows take the intended path."});
        DIM_DEFAULTS.put(
                "prompting", new String[] {"Prompting", "Prompt versions compared and optimised."});
        DIM_DEFAULTS.put(
                "compliance",
                new String[] {"Compliance", "Policy and regulatory requirements are met."});
        DIM_DEFAULTS.put(
                "other", new String[] {"Other", "Evaluations not classified into a dimension."});
    }

    private static final Map<String, String> FAMILY_NAMES =
            Map.of(
                    "prompts", "Prompts",
                    "agents", "Agents",
                    "conversations", "Conversations",
                    "retrieval", "Retrieval",
                    "workflows", "Workflows");

    private static final Map<String, String> FACET_NAMES =
            Map.of(
                    "answers", "Answers",
                    "tools", "Tools & reasoning",
                    "rag", "RAG",
                    "multi", "Multi-turn",
                    "compare", "A/B comparison",
                    "optimization", "Optimization",
                    "orchestration", "Orchestration");

    public final double defaultGoal;
    public final double warnGap;
    public final double noiseBand;
    public final String baselinePolicy;
    public final String defaultBranch;
    public final String projectName;
    private final Map<String, DimensionConfig> dimensions;

    private ReportConfig(
            double defaultGoal,
            double warnGap,
            double noiseBand,
            String baselinePolicy,
            String defaultBranch,
            String projectName,
            Map<String, DimensionConfig> dimensions) {
        this.defaultGoal = defaultGoal;
        this.warnGap = warnGap;
        this.noiseBand = noiseBand;
        this.baselinePolicy = baselinePolicy;
        this.defaultBranch = defaultBranch;
        this.projectName = projectName;
        this.dimensions = dimensions;
    }

    public static ReportConfig defaults() {
        return new ReportConfig(90, 10, 0.07, "sameBranch", null, null, new LinkedHashMap<>());
    }

    /** Loads {@code eval4j-report.yaml} (or .json); a missing file gives the defaults. */
    public static ReportConfig load(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return defaults();
        }
        try {
            boolean json = file.getFileName().toString().endsWith(".json");
            ObjectMapper m = json ? new ObjectMapper() : new ObjectMapper(new YAMLFactory());
            return parse(m.readTree(file.toFile()));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read report configuration " + file, e);
        }
    }

    static ReportConfig parse(JsonNode n) {
        double goal = num(n.path("defaultGoal"), 90, "defaultGoal");
        double warn = num(n.path("warnGap"), 10, "warnGap");
        double noise = num(n.path("compare").path("noiseBand"), 0.07, "compare.noiseBand");
        String policy = n.path("compare").path("baseline").asText("sameBranch");
        String defBranch = n.path("compare").path("defaultBranch").asText(null);
        Map<String, DimensionConfig> dims = new LinkedHashMap<>();
        JsonNode dn = n.path("dimensions");
        dn.fieldNames()
                .forEachRemaining(
                        id -> {
                            JsonNode d = dn.get(id);
                            String[] def = DIM_DEFAULTS.get(id);
                            double g = num(d.path("goal"), goal, "dimensions." + id + ".goal");
                            Priority p = Priority.IMPORTANT;
                            if (d.hasNonNull("priority")) {
                                try {
                                    p =
                                            Priority.valueOf(
                                                    d.get("priority")
                                                            .asText()
                                                            .toUpperCase(Locale.ROOT));
                                } catch (IllegalArgumentException e) {
                                    throw new IllegalArgumentException(
                                            "dimensions."
                                                    + id
                                                    + ".priority must be one of CRITICAL, IMPORTANT, NICE_TO_HAVE, NONE");
                                }
                            }
                            dims.put(
                                    id,
                                    new DimensionConfig(
                                            d.path("name")
                                                    .asText(def == null ? titleCase(id) : def[0]),
                                            d.path("blurb").asText(def == null ? "" : def[1]),
                                            g,
                                            p));
                        });
        return new ReportConfig(
                goal,
                warn,
                noise,
                policy,
                defBranch,
                n.path("project").path("name").asText(null),
                dims);
    }

    private static double num(JsonNode n, double fallback, String path) {
        if (n.isMissingNode() || n.isNull()) {
            return fallback;
        }
        double v = n.asDouble(Double.NaN);
        if (Double.isNaN(v) || v < 0) {
            throw new IllegalArgumentException(path + " must be a non-negative number");
        }
        if (path.endsWith("Goal") || path.endsWith(".goal")) {
            if (v > 100) {
                throw new IllegalArgumentException(path + " must be between 0 and 100");
            }
        }
        return v;
    }

    public DimensionConfig dimension(String id) {
        DimensionConfig c = dimensions.get(id);
        if (c != null) {
            return c;
        }
        String[] def = DIM_DEFAULTS.get(id);
        return new DimensionConfig(
                def == null ? titleCase(id) : def[0],
                def == null ? "" : def[1],
                defaultGoal,
                Priority.IMPORTANT);
    }

    public Map<String, DimensionConfig> configuredDimensions() {
        return dimensions;
    }

    public static String familyName(String id) {
        return FAMILY_NAMES.getOrDefault(id, titleCase(id));
    }

    public static String facetName(String id) {
        return FACET_NAMES.getOrDefault(id, titleCase(id));
    }

    public static String titleCase(String id) {
        if (id == null || id.isEmpty()) {
            return "";
        }
        String s = id.replace('-', ' ').replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
