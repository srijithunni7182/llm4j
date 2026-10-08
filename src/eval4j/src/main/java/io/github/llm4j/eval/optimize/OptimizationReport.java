package io.github.llm4j.eval.optimize;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.llm4j.eval.criteria.Scorecard;
import io.github.llm4j.eval.report.AtomicFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Writes the machine-readable trace and a human-readable Markdown report for a finished run. */
final class OptimizationReport {

    static final String TRACE_FILE = "optimizer-trace.json";
    static final String REPORT_FILE = "optimizer-report.md";

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private OptimizationReport() {}

    static void write(OptimizationResult result, Path directory) {
        try {
            AtomicFiles.write(
                    directory.resolve(TRACE_FILE), MAPPER.writeValueAsBytes(trace(result)));
            AtomicFiles.write(
                    directory.resolve(REPORT_FILE),
                    markdown(result).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(
                    "could not write the optimizer report to " + directory, e);
        }
    }

    static Map<String, Object> trace(OptimizationResult r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stopReason", r.stopReason());
        out.put("generalized", r.generalized());
        out.put("verdictReasons", r.verdict().reasons());
        out.put("seed", CandidateSnapshot.of(r.seed()));
        out.put("best", CandidateSnapshot.of(r.best()));
        out.put("bestSelectionValidationMean", r.bestSelectionValidationMean());
        out.put("seedScores", r.seedScores());
        out.put("bestScores", r.bestScores());
        out.put("seedVsBest", r.seedVsBest());
        out.put("cost", r.cost());
        out.put("warnings", r.warnings());
        out.put("rounds", r.trace());
        return out;
    }

    static String markdown(OptimizationResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Optimizer report\n\n");
        sb.append("- **Stop reason:** ").append(r.stopReason()).append('\n');
        sb.append("- **Generalized:** ").append(r.generalized() ? "yes" : "**NO**").append('\n');
        for (String reason : r.verdict().reasons()) {
            sb.append("  - ").append(reason).append('\n');
        }
        sb.append('\n');
        sb.append("## Scores\n\n| | validation | test |\n|---|---|---|\n");
        sb.append(scoreRow("Seed", r.seedScores()));
        sb.append(scoreRow("Best (confirmed)", r.bestScores()));
        sb.append(
                String.format(
                        Locale.ROOT,
                        "%nSelection-time validation mean of the best candidate: %.3f%n%n",
                        r.bestSelectionValidationMean()));
        if (r.seedVsBest() != null) {
            Comparison c = r.seedVsBest();
            sb.append(
                    String.format(
                            Locale.ROOT,
                            "Test-split head to head: best wins %d, seed wins %d, ties %d",
                            c.bestWins(),
                            c.seedWins(),
                            c.ties()));
            if (c.interval() != null) {
                sb.append(
                        String.format(
                                Locale.ROOT,
                                " (95%% interval on best's share of decisive scenarios: %.2f-%.2f)",
                                c.interval().low(),
                                c.interval().high()));
            }
            sb.append("\n\n");
        }
        Cost cost = r.cost();
        sb.append(
                String.format(
                        Locale.ROOT,
                        "## Cost\n\nRollouts: %d · rewriter calls: %d · tracked LLM calls: %d · elapsed: %.1f s%n%n",
                        cost.rollouts(),
                        cost.rewriterCalls(),
                        cost.trackedLlmCalls(),
                        cost.elapsedMillis() / 1000.0));
        sb.append("## Change\n\n");
        String diff = r.toPatch().unifiedDiff();
        sb.append(
                diff.isEmpty()
                        ? "_No change: the seed remained the best candidate._\n"
                        : "```diff\n" + diff + "```\n");
        sb.append("\n## Rounds\n\n");
        sb.append(
                "| # | action | parent | param | parent batch | child batch | validation | frontier | note |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|\n");
        for (Round round : r.trace()) {
            sb.append(
                    String.format(
                            Locale.ROOT,
                            "| %d | %s | %s | %s | %.2f | %s | %s | %d | %s |%n",
                            round.index(),
                            round.action(),
                            cell(round.parentId()),
                            cell(round.parameter()),
                            round.parentBatchMean(),
                            round.childBatchMean() == null
                                    ? "-"
                                    : String.format(Locale.ROOT, "%.2f", round.childBatchMean()),
                            round.validationMean() == null
                                    ? "-"
                                    : String.format(Locale.ROOT, "%.2f", round.validationMean()),
                            round.frontierSize(),
                            cell(round.note())));
        }
        if (!r.warnings().isEmpty()) {
            sb.append("\n## Warnings\n\n");
            r.warnings().forEach(w -> sb.append("- ").append(cell(w)).append('\n'));
        }
        return sb.toString();
    }

    private static String scoreRow(String label, CandidateScores scores) {
        return String.format(
                Locale.ROOT,
                "| %s | %.3f | %s |%n",
                label,
                scores.validationMean(),
                scores.testMean() == null
                        ? "n/a"
                        : String.format(Locale.ROOT, "%.3f", scores.testMean()));
    }

    /** Keeps free text from breaking Markdown table rows. */
    private static String cell(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }

    static double mean(List<Scorecard> cards) {
        return cards.stream().mapToDouble(Scorecard::score).average().orElse(0.0);
    }
}
