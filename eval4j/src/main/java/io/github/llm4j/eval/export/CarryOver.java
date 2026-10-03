package io.github.llm4j.eval.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Carries the latest known verdicts of earlier runs into this one for cases it did not evaluate, so
 * a cheap run still shows a complete picture. Carried lines are marked {@code source: CARRIED} with
 * the run and time that produced them; the report always labels them and counts them separately
 * from fresh evidence.
 */
final class CarryOver {

    private CarryOver() {}

    /**
     * @param root the export root
     * @param thisRun this run's id (never carried from)
     * @param caseIds the cases this run is about (declared scenarios and cases it evaluated)
     * @param seenEvaluated keys this run has a counted result for
     * @return {carried, passed, failed}
     */
    private static boolean inScope(
            JsonNode n, String caseId, Set<String> caseIds, Set<String> suites) {
        if (caseId != null && caseIds.contains(caseId)) {
            return true;
        }
        String testId = n.path("testId").asText(null);
        if (testId != null) {
            int hash = testId.indexOf('#');
            return hash > 0 && suites.contains(testId.substring(0, hash));
        }
        return false;
    }

    static int[] apply(
            Path root,
            String thisRun,
            String branch,
            Set<String> caseIds,
            Set<String> suites,
            Set<String> seenEvaluated,
            RunWriter writer,
            int firstSeq) {
        Path runs = root.resolve("runs");
        if (!Files.isDirectory(runs)) {
            return new int[3];
        }
        Path latest = null;
        String latestStart = "";
        try (Stream<Path> s = Files.list(runs)) {
            for (Path dir : (Iterable<Path>) s::iterator) {
                if (dir.getFileName().toString().equals(thisRun)
                        || !Files.isRegularFile(dir.resolve("run.json"))) {
                    continue;
                }
                JsonNode run = RunWriter.MAPPER.readTree(dir.resolve("run.json").toFile());
                String status = run.path("status").asText();
                if (!"COMPLETE".equals(status) && !"PARTIAL".equals(status)) {
                    continue;
                }
                String runBranch = run.path("source").path("branch").asText(null);
                if (branch != null && runBranch != null && !branch.equals(runBranch)) {
                    continue;
                }
                String start = run.path("startedAt").asText("");
                if (start.compareTo(latestStart) > 0) {
                    latestStart = start;
                    latest = dir;
                }
            }
        } catch (IOException e) {
            return new int[3];
        }
        if (latest == null || !Files.isRegularFile(latest.resolve("evaluations.jsonl"))) {
            return new int[3];
        }
        int carried = 0;
        int passedCount = 0;
        int failedCount = 0;
        int seq = firstSeq;
        Set<String> done = new HashSet<>(seenEvaluated);
        String previousRun = latest.getFileName().toString();
        try {
            List<String> lines =
                    Files.readAllLines(latest.resolve("evaluations.jsonl"), StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode n;
                try {
                    n = RunWriter.MAPPER.readTree(line);
                } catch (IOException e) {
                    continue;
                }
                String key = n.path("key").asText(null);
                String caseId = n.path("caseId").asText(null);
                if (key == null
                        || done.contains(key)
                        || !"EVALUATED".equals(n.path("status").asText())
                        || !inScope(n, caseId, caseIds, suites)) {
                    continue;
                }
                ObjectNode c = ((ObjectNode) n).deepCopy();
                c.put("seq", seq++);
                c.put("source", "CARRIED");
                String origin = n.path("evaluatedInRun").asText(previousRun);
                c.put("evaluatedInRun", origin);
                if (!c.hasNonNull("evaluatedAt") && n.hasNonNull("timestamp")) {
                    c.put("evaluatedAt", n.get("timestamp").asText());
                }
                c.remove("calls");
                c.remove("tokensIn");
                c.remove("tokensOut");
                c.remove("costUsd");
                writer.append("evaluations.jsonl", c);
                done.add(key);
                carried++;
                if (n.path("passed").asBoolean()) {
                    passedCount++;
                } else {
                    failedCount++;
                }
            }
        } catch (IOException e) {
            return new int[] {carried, passedCount, failedCount};
        }
        return new int[] {carried, passedCount, failedCount};
    }
}
