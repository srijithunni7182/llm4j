package io.github.llm4j.evalreport.format;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.llm4j.evalreport.format.model.Ev;
import io.github.llm4j.evalreport.format.model.MetricDef;
import io.github.llm4j.evalreport.format.model.RunMeta;
import io.github.llm4j.evalreport.format.model.TestOutcome;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a bundle from the v1 {@code eval4j-report.json} (spec 01 §10) so older runs can be opened,
 * compared and trended. Everything v1 recorded is kept; what v1 never knew (kind, family,
 * dimension) takes the documented defaults.
 */
public final class LegacyV1Importer {

    private LegacyV1Importer() {}

    private static Double dbl(JsonNode n, String f) {
        return n.hasNonNull(f) ? Double.valueOf(n.get(f).asDouble()) : null;
    }

    private static Long lng(JsonNode n, String f) {
        return n.hasNonNull(f) ? Long.valueOf(n.get(f).asLong()) : null;
    }

    /**
     * @return the directory of the new bundle under {@code root}
     */
    public static Path importFile(Path v1Json, Path root) throws IOException {
        JsonNode in = RunBundleReader.MAPPER.readTree(v1Json.toFile());
        String runId =
                in.path("runId").asText("legacy-" + Ids.caseId(v1Json.toString()).substring(2, 10));
        runId = runId.replaceAll("[^A-Za-z0-9._-]", "-");
        if (runId.length() < 6) {
            runId = "legacy-" + runId;
        }
        String started = in.path("startedAt").asText("1970-01-01T00:00:00Z");
        List<Ev> evals = new ArrayList<>();
        Map<String, MetricDef> metrics = new LinkedHashMap<>();
        Map<String, Integer> occurrences = new HashMap<>();
        List<String> judges = new ArrayList<>();
        int seq = 0;
        for (JsonNode r : in.path("records")) {
            String metricName = r.path("metric").asText("metric");
            String metricId = Ids.slug(metricName);
            boolean judged = r.hasNonNull("threshold") && r.path("threshold").asDouble() > 0;
            metrics.putIfAbsent(
                    metricId,
                    new MetricDef(
                            metricId,
                            metricName,
                            judged ? "JUDGE" : "ASSERTION",
                            "agents",
                            "answers",
                            null,
                            dbl(r, "threshold"),
                            null,
                            null,
                            r.hasNonNull("judgeIdentifier")
                                    ? Ids.slug(r.get("judgeIdentifier").asText())
                                    : null));
            String testId = r.path("suite").asText("") + "#" + r.path("testName").asText("");
            int occ = occurrences.merge(testId + "\u0000" + metricId, 1, Integer::sum) - 1;
            String judge =
                    r.hasNonNull("judgeIdentifier")
                            ? Ids.slug(r.get("judgeIdentifier").asText())
                            : null;
            if (judge != null && !judges.contains(judge)) {
                judges.add(judge);
            }
            List<String> ctx = null;
            if (r.path("retrievalContext").isArray()) {
                ctx = new ArrayList<>();
                for (JsonNode c : r.get("retrievalContext")) {
                    ctx.add(c.asText());
                }
            }
            evals.add(
                    new Ev(
                            seq++,
                            Ids.key(testId, metricId, occ),
                            Ids.caseId(testId),
                            null,
                            testId,
                            metricId,
                            judged ? "JUDGE" : "ASSERTION",
                            "EVALUATED",
                            dbl(r, "score"),
                            dbl(r, "threshold"),
                            r.path("passed").asBoolean(),
                            r.path("reason").asText(null),
                            null,
                            null,
                            null,
                            null,
                            null,
                            "FRESH",
                            null,
                            null,
                            judge,
                            null,
                            lng(r, "durationMs"),
                            null,
                            null,
                            null,
                            null,
                            r.path("input").asText(null),
                            r.path("actualOutput").asText(null),
                            r.path("expectedOutput").asText(null),
                            ctx,
                            null,
                            r.path("timestamp").asText(started)));
        }
        List<TestOutcome> tests = new ArrayList<>();
        for (JsonNode t : in.path("tests")) {
            String id = t.path("suite").asText("") + "#" + t.path("testName").asText("");
            tests.add(
                    new TestOutcome(
                            id,
                            t.path("suite").asText(null),
                            t.path("testName").asText(null),
                            Ids.caseId(id),
                            null,
                            t.path("status").asText("PASSED"),
                            lng(t, "durationMs"),
                            t.path("message").asText(null)));
        }
        ObjectNode run = RunBundleReader.MAPPER.createObjectNode();
        run.put("schemaVersion", 1);
        run.put("format", "eval4j-run");
        run.put("runId", runId);
        run.put("status", "COMPLETE");
        run.put("startedAt", started);
        if (in.hasNonNull("endedAt")) {
            run.put("endedAt", in.get("endedAt").asText());
        }
        run.putObject("project");
        ObjectNode src = run.putObject("source");
        String sha = in.path("gitSha").asText("");
        if (sha.matches("[0-9a-fA-F]{7,64}")) {
            src.put("commit", sha.length() > 12 ? sha.substring(0, 12) : sha);
        }
        run.putObject("profile").put("name", "FULL");
        ObjectNode env = run.putObject("env");
        ArrayNode jn = env.putArray("judges");
        judges.forEach(j -> jn.addObject().put("id", j));
        ArrayNode mn = run.putArray("metrics");
        metrics.values().forEach(m -> mn.add(RunBundleReader.MAPPER.valueToTree(m)));
        run.putObject("extensions").put("importedFrom", "eval4j-report.json (v1)");
        RunMeta meta = RunBundleReader.meta(run);
        return BundleWriter.write(
                root,
                new RunBundle(meta, evals, List.of(), tests, List.of(), List.of(), List.of()),
                run);
    }
}
