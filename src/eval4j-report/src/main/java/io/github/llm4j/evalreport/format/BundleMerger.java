package io.github.llm4j.evalreport.format;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.llm4j.evalreport.format.model.Ev;
import io.github.llm4j.evalreport.format.model.RunMeta;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges the bundles of one build (same {@code groupId}: several Maven modules or JVMs) into a
 * single run. The merged run takes the group id as its run id, the earliest start and latest end,
 * and the worst status. A key present in several bundles keeps the line from the later bundle and
 * is reported. (Spec 01 §7.)
 */
public final class BundleMerger {

    private BundleMerger() {}

    public static RunBundle merge(String groupId, List<RunBundle> parts) {
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("nothing to merge for group " + groupId);
        }
        List<RunBundle> ordered = new ArrayList<>(parts);
        ordered.sort(
                (a, b) ->
                        String.valueOf(a.run().startedAt())
                                .compareTo(String.valueOf(b.run().startedAt())));
        List<String> warnings = new ArrayList<>();
        Map<String, Ev> evals = new LinkedHashMap<>();
        for (RunBundle b : ordered) {
            warnings.addAll(b.warnings());
            for (Ev e : b.evaluations()) {
                if (e.key() == null) {
                    continue;
                }
                Ev prev = evals.put(e.key(), e);
                if (prev != null && prev.counted() && e.counted()) {
                    warnings.add(
                            "key "
                                    + e.key()
                                    + " appears in two bundles of group "
                                    + groupId
                                    + "; the later one was used");
                }
            }
        }
        ObjectNode run = RunBundleReader.MAPPER.createObjectNode();
        run.put("schemaVersion", 1);
        run.put("format", "eval4j-run");
        run.put("runId", groupId);
        run.put("groupId", groupId);
        run.put("status", worst(ordered));
        run.put("startedAt", ordered.get(0).run().startedAt());
        String end = null;
        for (RunBundle b : ordered) {
            String e = b.run().endedAt();
            if (e != null && (end == null || e.compareTo(end) > 0)) {
                end = e;
            }
        }
        if (end != null) {
            run.put("endedAt", end);
        }
        RunMeta first = ordered.get(0).run();
        ObjectNode project = run.putObject("project");
        if (first.project() != null) {
            project.put("name", first.project());
        }
        run.set("source", first.source());
        run.set("profile", first.profile());
        ObjectNode env = run.putObject("env");
        if (first.env().isObject()) {
            env.setAll((ObjectNode) first.env().deepCopy());
        }
        for (String kind : List.of("agents", "judges", "datasets")) {
            env.set(kind, unionById(ordered, kind));
        }
        ArrayNode metrics = run.putArray("metrics");
        Map<String, JsonNode> seen = new LinkedHashMap<>();
        for (RunBundle b : ordered) {
            for (var m : b.run().metrics()) {
                seen.putIfAbsent(m.id(), RunBundleReader.MAPPER.valueToTree(m));
            }
        }
        seen.values().forEach(metrics::add);
        run.set("summary", first.summary());
        RunMeta meta = RunBundleReader.meta(run);
        List<io.github.llm4j.evalreport.format.model.ScenarioDef> scenarios = new ArrayList<>();
        List<io.github.llm4j.evalreport.format.model.TestOutcome> tests = new ArrayList<>();
        List<JsonNode> traces = new ArrayList<>();
        List<JsonNode> optimizations = new ArrayList<>();
        Map<String, Boolean> scenarioIds = new LinkedHashMap<>();
        for (RunBundle b : ordered) {
            for (var s : b.scenarios()) {
                if (scenarioIds.putIfAbsent(
                                String.valueOf(s.caseId() != null ? s.caseId() : s.id()), true)
                        == null) {
                    scenarios.add(s);
                }
            }
            tests.addAll(b.tests());
            traces.addAll(b.traces());
            optimizations.addAll(b.optimizations());
        }
        return new RunBundle(
                meta,
                new ArrayList<>(evals.values()),
                scenarios,
                tests,
                traces,
                optimizations,
                warnings);
    }

    private static String worst(List<RunBundle> parts) {
        String[] rank = {"FAILED", "RUNNING", "PARTIAL", "COMPLETE"};
        for (String s : rank) {
            for (RunBundle b : parts) {
                if (s.equals(b.run().status())) {
                    return s;
                }
            }
        }
        return "COMPLETE";
    }

    private static ArrayNode unionById(List<RunBundle> parts, String kind) {
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        for (RunBundle b : parts) {
            for (JsonNode n : b.run().env().path(kind)) {
                String id = n.path("id").asText();
                JsonNode prev = byId.get(id);
                if (prev == null) {
                    byId.put(id, n);
                } else if (kind.equals("judges")) {
                    // judge call statistics add up across JVMs
                    ObjectNode merged = ((ObjectNode) prev).deepCopy();
                    JsonNode a = prev.path("stats");
                    JsonNode c = n.path("stats");
                    if (c.isObject()) {
                        ObjectNode st = merged.putObject("stats");
                        for (String f :
                                List.of(
                                        "calls",
                                        "cacheHits",
                                        "tokensIn",
                                        "tokensOut",
                                        "failures")) {
                            st.put(f, a.path(f).asLong() + c.path(f).asLong());
                        }
                        if (c.has("latencyMeanMs")) {
                            st.set("latencyMeanMs", c.get("latencyMeanMs"));
                        }
                    }
                    byId.put(id, merged);
                }
            }
        }
        ArrayNode out = RunBundleReader.MAPPER.createArrayNode();
        byId.values().forEach(out::add);
        return out;
    }
}
