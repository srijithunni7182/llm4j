package io.github.llm4j.eval.export;

import io.github.llm4j.eval.optimize.OptimizationResult;
import io.github.llm4j.eval.optimize.Round;
import io.github.llm4j.eval.optimize.RoundAction;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Writes a prompt-optimizer run into the bundle's {@code optimizations.jsonl}. Never throws. */
public final class OptimizationExporter {

    private OptimizationExporter() {}

    public static void export(OptimizationResult r) {
        try {
            EvalRun run = EvalRun.get();
            if (!run.isExporting()) {
                return;
            }
            run.recordOptimization(toLine(r));
        } catch (RuntimeException e) {
            System.err.println("eval4j: could not export the optimizer run: " + e);
        }
    }

    static Map<String, Object> toLine(OptimizationResult r) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put(
                "id",
                "opt-"
                        + Hashes.hex16(
                                r.seed().id()
                                        + "\u0000"
                                        + r.best().id()
                                        + "\u0000"
                                        + r.trace().size()));
        String promptId =
                r.seed().parameters().isEmpty()
                        ? "prompt"
                        : r.seed().parameters().keySet().iterator().next();
        o.put("promptId", promptId);
        o.put("fromVersion", r.seed().id());
        o.put("toVersion", r.best().id());
        o.put("stopReason", String.valueOf(r.stopReason()));
        o.put("generalized", r.generalized());
        List<Map<String, Object>> rounds = new ArrayList<>();
        double best = r.seedScores().validationMean();
        rounds.add(round(0, "BASELINE", null, best, null, null));
        for (Round round : r.trace()) {
            boolean accepted = round.action() == RoundAction.ACCEPTED;
            Double candidate =
                    round.validationMean() != null
                            ? round.validationMean()
                            : round.childBatchMean();
            if (accepted && round.validationMean() != null) {
                best = Math.max(best, round.validationMean());
            }
            rounds.add(
                    round(
                            round.index() + 1,
                            accepted ? "ACCEPTED" : "REJECTED",
                            candidate,
                            best,
                            null,
                            round.action().name()));
        }
        o.put("rounds", rounds);
        Map<String, Object> budget = new LinkedHashMap<>();
        budget.put("calls", r.cost().trackedLlmCalls() + r.cost().rewriterCalls());
        o.put("budget", budget);
        if (r.bestScores().testMean() != null) {
            Map<String, Object> overfit = new LinkedHashMap<>();
            overfit.put("trainScore", r.bestScores().validationMean());
            overfit.put("validationScore", r.bestScores().testMean());
            overfit.put("gap", r.bestScores().validationMean() - r.bestScores().testMean());
            o.put("overfit", overfit);
        }
        List<Map<String, Object>> diff = new ArrayList<>();
        for (String line : r.toPatch().unifiedDiff().split("\n")) {
            if (line.startsWith("---")
                    || line.startsWith("+++")
                    || line.startsWith("@@")
                    || line.isEmpty()) {
                continue;
            }
            String op = line.startsWith("+") ? "ADD" : line.startsWith("-") ? "DEL" : "KEEP";
            String text = line.length() > 1 ? line.substring(1) : "";
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("op", op);
            d.put("text", text.length() > 2000 ? text.substring(0, 2000) : text);
            diff.add(d);
            if (diff.size() >= 2000) {
                break;
            }
        }
        o.put("diff", diff);
        return o;
    }

    private static Map<String, Object> round(
            int index, String action, Double candidate, double best, Integer calls, String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("index", index);
        m.put("action", action);
        if (candidate != null) {
            m.put("candidateScore", Math.max(0, Math.min(1, candidate)));
        }
        m.put("bestScore", Math.max(0, Math.min(1, best)));
        if (note != null) {
            m.put("note", note);
        }
        return m;
    }
}
