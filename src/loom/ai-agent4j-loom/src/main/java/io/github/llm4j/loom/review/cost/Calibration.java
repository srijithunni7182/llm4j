package io.github.llm4j.loom.review.cost;

import io.github.llm4j.loom.runtime.FileRunJournal;
import io.github.llm4j.loom.runtime.RunJournal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Compares what the estimate said with what a real, journaled run spent, so the defaults can be
 * corrected from evidence ("this loop turns 2.1 times, not 3"). It reads only the usage records a
 * run writes to its journal; it runs nothing and calls no model.
 */
public final class Calibration {

    /** Estimated typical against actual, for a workflow or a model. */
    public record Row(
            String name,
            long estimatedTokens,
            long actualTokens,
            long actualCalls,
            long estimatedCalls) {
        /** Actual over estimated, or 0 when nothing was estimated. */
        public double ratio() {
            return estimatedTokens == 0 ? 0 : (double) actualTokens / estimatedTokens;
        }
    }

    /**
     * The comparison.
     *
     * @param workflows one row per workflow that spent anything in the run
     * @param models one row per model
     * @param notes things worth saying about the result
     */
    public record Result(List<Row> workflows, List<Row> models, List<String> notes) {
        public Result {
            workflows = List.copyOf(workflows);
            models = List.copyOf(models);
            notes = List.copyOf(notes);
        }

        public Row total() {
            long est = 0, act = 0, calls = 0, estCalls = 0;
            for (Row r : workflows) {
                est += r.estimatedTokens();
                act += r.actualTokens();
                calls += r.actualCalls();
                estCalls += r.estimatedCalls();
            }
            return new Row("total", est, act, calls, estCalls);
        }
    }

    private Calibration() {}

    /** What the journal of {@code runDir} says each workflow and model spent. */
    public static Result compare(Path runDir, CostEstimate estimate) {
        Path file = runDir.resolve("journal.json");
        if (!Files.isRegularFile(file))
            throw new IllegalArgumentException(
                    runDir
                            + " has no journal.json: give the folder of a run made with a run store");
        RunJournal journal = new FileRunJournal(file);
        Map<String, long[]> byWorkflow = new TreeMap<>(); // tokens, calls
        Map<String, long[]> byModel = new TreeMap<>();
        for (Map.Entry<String, RunJournal.Entry> e : journal.all().entrySet()) {
            if (!e.getKey().contains("#usage:") || !(e.getValue().value() instanceof Map<?, ?> m))
                continue;
            long tokens = number(m.get("prompt")) + number(m.get("completion"));
            long calls = number(m.get("calls"));
            String key = e.getKey();
            int slash = key.indexOf('/');
            String workflow =
                    slash < 0 ? key.substring(0, key.indexOf('#')) : key.substring(0, slash);
            add(byWorkflow, workflow, tokens, calls);
            add(byModel, String.valueOf(m.get("model")), tokens, calls);
        }
        List<Row> workflows = new ArrayList<>();
        for (var e : byWorkflow.entrySet()) {
            WorkflowCost w =
                    estimate.workflows().stream()
                            .filter(x -> x.name().equals(e.getKey()))
                            .findFirst()
                            .orElse(null);
            workflows.add(row(e.getKey(), w == null ? null : w.typical(), e.getValue()));
        }
        Usage all = Usage.NONE;
        for (Row r : workflows)
            for (WorkflowCost w : estimate.workflows())
                if (w.name().equals(r.name())) all = all.plus(w.typical());
        List<Row> models = new ArrayList<>();
        for (var e : byModel.entrySet()) {
            Usage.Spend s = all.byModel().get(e.getKey());
            models.add(
                    new Row(
                            e.getKey(),
                            s == null ? 0 : s.tokens(),
                            e.getValue()[0],
                            e.getValue()[1],
                            s == null ? 0 : s.calls()));
        }
        List<String> notes = new ArrayList<>();
        if (workflows.isEmpty())
            notes.add("the run recorded no model usage, so there is nothing to compare");
        for (Row r : workflows) {
            if (r.estimatedTokens() == 0)
                notes.add(
                        r.name()
                                + " spent tokens but the script has no workflow of that name to estimate");
            else if (r.ratio() > 2 || r.ratio() < 0.5)
                notes.add(
                        String.format(
                                Locale.ROOT,
                                "%s used %.1f times the typical estimate (%d calls against %d estimated): set the assumptions that decide it, such as assume.loop.iterations or assume.foreach.items, from this evidence",
                                r.name(),
                                r.ratio(),
                                r.actualCalls(),
                                r.estimatedCalls()));
        }
        notes.add("One run is one sample: calibrate from several before changing a default.");
        return new Result(workflows, models, notes);
    }

    private static Row row(String name, Usage typical, long[] actual) {
        return new Row(
                name,
                typical == null ? 0 : typical.tokens(),
                actual[0],
                actual[1],
                typical == null ? 0 : typical.calls());
    }

    private static void add(Map<String, long[]> into, String key, long tokens, long calls) {
        long[] v = into.computeIfAbsent(key, k -> new long[2]);
        v[0] += tokens;
        v[1] += calls;
    }

    private static long number(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }

    /** The comparison as a table. */
    public static String text(Result r) {
        StringBuilder b = new StringBuilder();
        b.append(
                String.format(
                        Locale.ROOT,
                        "%-22s %12s %12s %8s%n",
                        "workflow",
                        "estimated",
                        "actual",
                        "ratio"));
        for (Row w : r.workflows()) b.append(line(w));
        if (r.workflows().size() > 1) b.append(line(r.total()));
        b.append('\n')
                .append(
                        String.format(
                                Locale.ROOT,
                                "%-22s %12s %12s %8s%n",
                                "model",
                                "estimated",
                                "actual",
                                "ratio"));
        for (Row m : r.models()) b.append(line(m));
        b.append('\n');
        for (String n : r.notes()) b.append(n).append('\n');
        return b.toString();
    }

    private static String line(Row r) {
        return String.format(
                Locale.ROOT,
                "%-22s %,12d %,12d %8s%n",
                r.name(),
                r.estimatedTokens(),
                r.actualTokens(),
                r.estimatedTokens() == 0 ? "-" : String.format(Locale.ROOT, "%.2f", r.ratio()));
    }
}
