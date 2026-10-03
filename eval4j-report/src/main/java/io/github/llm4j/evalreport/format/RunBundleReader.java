package io.github.llm4j.evalreport.format;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.evalreport.format.model.Ev;
import io.github.llm4j.evalreport.format.model.MetricDef;
import io.github.llm4j.evalreport.format.model.RunMeta;
import io.github.llm4j.evalreport.format.model.ScenarioDef;
import io.github.llm4j.evalreport.format.model.TestOutcome;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Reads a run bundle directory. Lenient by default: a malformed line is skipped with a warning and
 * a truncated last line (a crashed run) is tolerated. {@code strict} turns any problem into a
 * {@link BundleFormatException} naming the file and line.
 */
public final class RunBundleReader {

    public static final ObjectMapper MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private RunBundleReader() {}

    public static RunBundle read(Path dir, boolean strict) {
        List<String> warnings = new ArrayList<>();
        JsonNode runNode = readJson(dir.resolve("run.json"));
        if (runNode == null) {
            throw new BundleFormatException("not a run bundle (no run.json): " + dir);
        }
        int version = runNode.path("schemaVersion").asInt(1);
        if (version > 1) {
            throw new BundleFormatException(
                    "run bundle "
                            + dir
                            + " has schemaVersion "
                            + version
                            + "; this report reads version 1");
        }
        RunMeta meta = meta(runNode);
        List<Ev> evals = lines(dir, "evaluations", Ev.class, strict, warnings);
        // a key appears once per run: when it repeats (a result carried over a "not evaluated"
        // line, or a writer bug) the later line wins; a clash of two real results is reported
        java.util.Map<String, Ev> byKey = new java.util.LinkedHashMap<>();
        List<Ev> clean = new ArrayList<>(evals.size());
        for (Ev raw : evals) {
            Ev e = sanitize(raw, warnings);
            if (e.key() == null) {
                clean.add(e);
                continue;
            }
            Ev prev = byKey.put(e.key(), e);
            if (prev != null && prev.counted() && e.counted()) {
                warnings.add(
                        "key " + e.key() + " appears twice with results; the later line was used");
            }
        }
        clean.addAll(byKey.values());
        return new RunBundle(
                meta,
                clean,
                lines(dir, "scenarios", ScenarioDef.class, strict, warnings),
                lines(dir, "tests", TestOutcome.class, strict, warnings),
                lines(dir, "traces", JsonNode.class, strict, warnings),
                lines(dir, "optimizations", JsonNode.class, strict, warnings),
                warnings);
    }

    public static RunMeta meta(JsonNode n) {
        List<MetricDef> metrics = new ArrayList<>();
        for (JsonNode m : n.path("metrics")) {
            try {
                metrics.add(MAPPER.treeToValue(m, MetricDef.class));
            } catch (IOException e) {
                // skip an unreadable metric definition; evaluations still resolve by id
            }
        }
        JsonNode src = n.path("source");
        return new RunMeta(
                n.path("runId").asText(null),
                n.path("groupId").asText(null),
                n.hasNonNull("runNumber") ? n.get("runNumber").asInt() : null,
                n.path("status").asText("COMPLETE"),
                n.path("startedAt").asText(null),
                n.path("endedAt").asText(null),
                n.path("project").path("name").asText(null),
                src.path("branch").asText(null),
                src.path("commit").asText(null),
                src,
                n.path("profile"),
                n.path("env"),
                metrics,
                n.path("summary"));
    }

    /** RPT-15: clamp out-of-range scores; an EVALUATED line without a verdict becomes an ERROR. */
    private static Ev sanitize(Ev e, List<String> warnings) {
        Double score = e.score();
        if (score != null && (score < 0 || score > 1)) {
            warnings.add("score " + score + " out of range for " + e.key() + "; clamped");
            score = Math.max(0, Math.min(1, score));
        }
        String status = e.status() == null ? "EVALUATED" : e.status();
        if ("EVALUATED".equals(status) && e.passed() == null) {
            warnings.add("evaluation " + e.key() + " has no 'passed'; treated as ERROR");
            status = "ERROR";
        }
        return new Ev(
                e.seq(),
                e.key(),
                e.caseId(),
                e.scenarioId(),
                e.testId(),
                e.metric(),
                e.kind(),
                status,
                score,
                e.threshold(),
                e.passed(),
                e.reason(),
                e.display(),
                e.measured(),
                e.family(),
                e.facet(),
                e.dimension(),
                e.source(),
                e.evaluatedInRun(),
                e.evaluatedAt(),
                e.judgeId(),
                e.samples(),
                e.durationMs(),
                e.calls(),
                e.tokensIn(),
                e.tokensOut(),
                e.costUsd(),
                e.input(),
                e.actualOutput(),
                e.expectedOutput(),
                e.retrievalContext(),
                e.traceId(),
                e.timestamp());
    }

    private static JsonNode readJson(Path file) {
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return MAPPER.readTree(file.toFile());
        } catch (IOException e) {
            throw new BundleFormatException("cannot read " + file + ": " + e.getMessage());
        }
    }

    private static <T> List<T> lines(
            Path dir, String base, Class<T> type, boolean strict, List<String> warnings) {
        Path plain = dir.resolve(base + ".jsonl");
        Path gz = dir.resolve(base + ".jsonl.gz");
        Path file = Files.exists(plain) ? plain : Files.exists(gz) ? gz : null;
        List<T> out = new ArrayList<>();
        if (file == null) {
            return out;
        }
        try (InputStream raw = Files.newInputStream(file);
                InputStream in = file.equals(gz) ? new GZIPInputStream(raw) : raw;
                BufferedReader r =
                        new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            int n = 0;
            while ((line = r.readLine()) != null) {
                n++;
                if (line.isBlank()) {
                    continue;
                }
                try {
                    out.add(MAPPER.readValue(line, type));
                } catch (IOException e) {
                    String msg = file.getFileName() + ":" + n + ": " + e.getMessage();
                    if (strict) {
                        throw new BundleFormatException(msg);
                    }
                    warnings.add("skipped " + msg);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /** A bundle is unreadable. */
    public static final class BundleFormatException extends RuntimeException {
        public BundleFormatException(String message) {
            super(message);
        }
    }
}
