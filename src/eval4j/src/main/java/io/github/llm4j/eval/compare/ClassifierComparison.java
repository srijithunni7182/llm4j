package io.github.llm4j.eval.compare;

import io.github.llm4j.classify.Answer;
import io.github.llm4j.classify.ChoiceAnswer;
import io.github.llm4j.classify.Classifier;
import io.github.llm4j.classify.ClassifyRequest;
import io.github.llm4j.classify.ClassifyResult;
import io.github.llm4j.classify.ClassifyUsage;
import io.github.llm4j.classify.Question;
import io.github.llm4j.classify.YesNoAnswer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Compares {@link Classifier}s on labelled cases, so a team can decide whether a backend (a chat
 * model, Jev) is good enough for <em>its</em> criteria: the same state and the same question go to
 * every contender, and the human label is the ground truth. For each contender the report gives
 * accuracy, the invalid-reply rate, calibration (the expected calibration error, the Brier score
 * and the reliability curve), the accuracy you keep if you act only on the most confident answers,
 * latency, tokens and the cost per 1,000 decisions; and between contenders the agreement and, with
 * {@link Builder#repeats(int)}, how often an answer flips. It is the study of {@code
 * docs/specs/07-classifier/STUDY_REPORT.md} as a reusable class.
 *
 * <p>Confidence is read the same way for every contender: for a yes/no question the distance of the
 * probability from a coin toss ({@code max(p, 1-p)}); for a choice the probability of the chosen
 * label when the backend gave probabilities, else its confidence. A call that fails or returns an
 * invalid reply counts as wrong and has no confidence. Accuracy, calibration and agreement are
 * computed on the first repeat; latency, tokens and cost on all of them.
 */
public final class ClassifierComparison {

    /** The name of the one question every case asks. */
    public static final String QUESTION = "criterion";

    /**
     * One labelled case.
     *
     * @param id a name for the case, shown with misses
     * @param category what kind of case it is ({@code "typo"}, {@code "injection"}); accuracy is
     *     broken down by it
     * @param state the named state fields
     * @param question the question, named {@value ClassifierComparison#QUESTION}; yes/no or choice
     * @param label the human label: {@code "yes"} or {@code "no"}, or one of the choice's labels
     */
    public record Case(
            String id,
            String category,
            Map<String, String> state,
            Question question,
            String label) {

        public Case {
            if (!(question instanceof Question.YesNo || question instanceof Question.Choice)) {
                throw new IllegalArgumentException(
                        "a comparison case is a yes/no or a choice question");
            }
            if (question instanceof Question.YesNo
                    ? !(label.equals("yes") || label.equals("no"))
                    : !((Question.Choice) question).labels().contains(label)) {
                throw new IllegalArgumentException(
                        "label '" + label + "' is not an answer to the question of case " + id);
            }
            state = Map.copyOf(state);
        }

        /** A yes/no case whose state is one text. */
        public static Case yesNo(
                String id, String category, String text, String instructions, boolean yes) {
            return new Case(
                    id,
                    category,
                    Map.of(ClassifyRequest.TEXT, text),
                    new Question.YesNo(QUESTION, instructions),
                    yes ? "yes" : "no");
        }

        /** A choice case whose state is one text. */
        public static Case choice(
                String id,
                String category,
                String text,
                String instructions,
                String label,
                String... options) {
            return new Case(
                    id,
                    category,
                    Map.of(ClassifyRequest.TEXT, text),
                    Question.Choice.of(QUESTION, instructions, options),
                    label);
        }
    }

    /** One contender's numbers. Calibration fields are empty when it gave no confidence. */
    public record Row(
            String contender,
            int n,
            double accuracy,
            int invalid,
            OptionalDouble ece,
            OptionalDouble brier,
            OptionalDouble accuracyAt80,
            OptionalDouble accuracyAt60,
            long p50Millis,
            long p95Millis,
            long inputTokens,
            long outputTokens,
            OptionalDouble costPer1000,
            boolean calibrated) {}

    /**
     * One bin of a reliability curve: the answers whose confidence is in [low, high), how often
     * they were right, and their mean confidence.
     */
    public record Bin(double low, double high, int n, double accuracy, double meanConfidence) {}

    /** A case a contender got wrong. */
    public record Miss(
            String contender,
            String caseId,
            String category,
            String expected,
            String got,
            OptionalDouble confidence) {}

    /** What {@link #run()} found. */
    public record Report(
            List<Row> rows,
            Map<String, Map<String, Double>> accuracyByCategory,
            Map<String, Double> agreement,
            Map<String, Double> flipRate,
            Map<String, List<Bin>> curves,
            List<Miss> misses) {

        public String toMarkdown() {
            StringBuilder md =
                    new StringBuilder(
                            "| contender | n | accuracy | invalid | ECE | Brier | acc@80% | acc@60% | p50 ms | p95 ms | in tok | out tok | $/1,000 | calibrated |\n");
            md.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
            for (Row r : rows) {
                md.append(
                        String.format(
                                Locale.ROOT,
                                "| %s | %d | %.3f | %d | %s | %s | %s | %s | %d | %d | %d | %d | %s | %s |%n",
                                r.contender(),
                                r.n(),
                                r.accuracy(),
                                r.invalid(),
                                f(r.ece()),
                                f(r.brier()),
                                f(r.accuracyAt80()),
                                f(r.accuracyAt60()),
                                r.p50Millis(),
                                r.p95Millis(),
                                r.inputTokens(),
                                r.outputTokens(),
                                r.costPer1000().isPresent()
                                        ? String.format(
                                                Locale.ROOT, "%.4f", r.costPer1000().getAsDouble())
                                        : "n/a",
                                r.calibrated() ? "yes" : "no"));
            }
            Map<String, Double> any =
                    accuracyByCategory.values().stream().findFirst().orElse(Map.of());
            if (!any.isEmpty()) {
                md.append("\nAccuracy by category:\n\n| category | ")
                        .append(String.join(" | ", accuracyByCategory.keySet()))
                        .append(" |\n|---|")
                        .append("---|".repeat(accuracyByCategory.size()))
                        .append('\n');
                for (String cat : any.keySet()) {
                    md.append("| ").append(cat);
                    for (Map<String, Double> byCat : accuracyByCategory.values())
                        md.append(String.format(Locale.ROOT, " | %.2f", byCat.get(cat)));
                    md.append(" |\n");
                }
            }
            if (!agreement.isEmpty()) {
                md.append("\nAgreement (same prediction):\n\n");
                agreement.forEach(
                        (k, v) -> md.append(String.format(Locale.ROOT, "- %s: %.2f%n", k, v)));
            }
            if (!flipRate.isEmpty()) {
                md.append("\nShare of cases whose answer changed between repeats:\n\n");
                flipRate.forEach(
                        (k, v) -> md.append(String.format(Locale.ROOT, "- %s: %.2f%n", k, v)));
            }
            if (!misses.isEmpty()) {
                md.append("\nMisses:\n\n");
                misses.stream()
                        .limit(30)
                        .forEach(
                                m ->
                                        md.append(
                                                String.format(
                                                        Locale.ROOT,
                                                        "- %s [%s] %s: expected %s, got %s%n",
                                                        m.contender(),
                                                        m.category(),
                                                        m.caseId(),
                                                        m.expected(),
                                                        m.got())));
            }
            return md.toString();
        }

        private static String f(OptionalDouble v) {
            return v.isPresent() ? String.format(Locale.ROOT, "%.3f", v.getAsDouble()) : "n/a";
        }
    }

    private record Outcome(
            boolean valid,
            String pred,
            OptionalDouble conf,
            boolean correct,
            long ms,
            int in,
            int out,
            boolean calibrated) {}

    private final List<Case> cases;
    private final Map<String, Classifier> contenders;
    private final Map<String, double[]> prices;
    private final int repeats;
    private final int parallelism;
    private final int bins;

    private ClassifierComparison(Builder b) {
        this.cases = List.copyOf(b.cases);
        this.contenders = new LinkedHashMap<>(b.contenders);
        this.prices = Map.copyOf(b.prices);
        this.repeats = b.repeats;
        this.parallelism = b.parallelism;
        this.bins = b.bins;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Runs every contender on every case (and repeat) and builds the report. */
    public Report run() {
        Map<String, Outcome[][]> outcomes = new LinkedHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(parallelism);
        try {
            for (Map.Entry<String, Classifier> c : contenders.entrySet()) {
                List<Future<Outcome>> futures = new ArrayList<>();
                for (Case cs : cases)
                    for (int rep = 0; rep < repeats; rep++)
                        futures.add(pool.submit(() -> ask(c.getValue(), cs)));
                Outcome[][] grid = new Outcome[cases.size()][repeats];
                int i = 0;
                for (Future<Outcome> f : futures) {
                    grid[i / repeats][i % repeats] = await(f);
                    i++;
                }
                outcomes.put(c.getKey(), grid);
            }
        } finally {
            pool.shutdownNow();
        }
        return report(outcomes);
    }

    private static Outcome await(Future<Outcome> f) {
        try {
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    private static Outcome ask(Classifier classifier, Case c) {
        long start = System.nanoTime();
        try {
            ClassifyResult result =
                    classifier.classify(
                            ClassifyRequest.builder()
                                    .state(c.state())
                                    .question(c.question())
                                    .build());
            long wall = (System.nanoTime() - start) / 1_000_000;
            ClassifyUsage u = result.usage();
            Answer a = result.answer(QUESTION);
            String pred;
            OptionalDouble conf;
            if (a instanceof YesNoAnswer y) {
                pred = y.yes() ? "yes" : "no";
                conf = OptionalDouble.of(y.certainty());
            } else {
                ChoiceAnswer ch = (ChoiceAnswer) a;
                pred = ch.label();
                Double p = ch.probabilities().get(pred);
                conf = p != null ? OptionalDouble.of(p) : ch.confidence();
            }
            return new Outcome(
                    true,
                    pred,
                    conf,
                    pred.equals(c.label()),
                    u.latencyMillis() > 0 ? u.latencyMillis() : wall,
                    u.inputTokens(),
                    u.outputTokens(),
                    a.calibrated());
        } catch (RuntimeException e) {
            return new Outcome(
                    false,
                    null,
                    OptionalDouble.empty(),
                    false,
                    (System.nanoTime() - start) / 1_000_000,
                    0,
                    0,
                    false);
        }
    }

    // ---- the maths

    private Report report(Map<String, Outcome[][]> outcomes) {
        List<Row> rows = new ArrayList<>();
        Map<String, Map<String, Double>> byCategory = new LinkedHashMap<>();
        Map<String, List<Bin>> curves = new LinkedHashMap<>();
        Map<String, Double> flips = new LinkedHashMap<>();
        List<Miss> misses = new ArrayList<>();
        for (Map.Entry<String, Outcome[][]> e : outcomes.entrySet()) {
            String name = e.getKey();
            Outcome[][] grid = e.getValue();
            List<Outcome> first = new ArrayList<>();
            List<Outcome> all = new ArrayList<>();
            for (Outcome[] perCase : grid) {
                first.add(perCase[0]);
                all.addAll(List.of(perCase));
            }
            List<Outcome> withConf = first.stream().filter(o -> o.conf().isPresent()).toList();
            List<Long> ms = all.stream().map(Outcome::ms).sorted().toList();
            long in = all.stream().mapToLong(Outcome::in).sum();
            long out = all.stream().mapToLong(Outcome::out).sum();
            double[] price = prices.get(name);
            OptionalDouble cost =
                    price == null || all.isEmpty()
                            ? OptionalDouble.empty()
                            : OptionalDouble.of(
                                    (in * price[0] + out * price[1]) / 1e6 / all.size() * 1000);
            rows.add(
                    new Row(
                            name,
                            first.size(),
                            mean(first.stream().map(o -> o.correct() ? 1.0 : 0.0).toList())
                                    .orElse(0),
                            (int) first.stream().filter(o -> !o.valid()).count(),
                            ece(withConf),
                            brier(withConf),
                            selective(withConf, 0.8),
                            selective(withConf, 0.6),
                            percentile(ms, 0.5),
                            percentile(ms, 0.95),
                            in,
                            out,
                            cost,
                            first.stream().anyMatch(Outcome::calibrated)));
            curves.put(name, curveBins(withConf));
            Map<String, Double> cat = new LinkedHashMap<>();
            Map<String, List<Double>> grouped = new LinkedHashMap<>();
            for (int i = 0; i < cases.size(); i++)
                grouped.computeIfAbsent(cases.get(i).category(), k -> new ArrayList<>())
                        .add(first.get(i).correct() ? 1.0 : 0.0);
            grouped.forEach((k, v) -> cat.put(k, mean(v).orElse(0)));
            byCategory.put(name, cat);
            if (repeats > 1) {
                long flipped =
                        java.util.Arrays.stream(grid)
                                .filter(
                                        pc ->
                                                java.util.Arrays.stream(pc)
                                                                .map(Outcome::pred)
                                                                .distinct()
                                                                .count()
                                                        > 1)
                                .count();
                flips.put(name, flipped / (double) grid.length);
            }
            for (int i = 0; i < cases.size(); i++) {
                Outcome o = first.get(i);
                if (!o.correct())
                    misses.add(
                            new Miss(
                                    name,
                                    cases.get(i).id(),
                                    cases.get(i).category(),
                                    cases.get(i).label(),
                                    o.valid() ? o.pred() : "(invalid)",
                                    o.conf()));
            }
        }
        Map<String, Double> agreement = new LinkedHashMap<>();
        List<String> names = new ArrayList<>(outcomes.keySet());
        for (int a = 0; a < names.size(); a++) {
            for (int b = a + 1; b < names.size(); b++) {
                int same = 0;
                for (int i = 0; i < cases.size(); i++) {
                    String pa = outcomes.get(names.get(a))[i][0].pred();
                    String pb = outcomes.get(names.get(b))[i][0].pred();
                    if (pa != null && pa.equals(pb)) same++;
                }
                agreement.put(names.get(a) + " vs " + names.get(b), same / (double) cases.size());
            }
        }
        return new Report(rows, byCategory, agreement, flips, curves, misses);
    }

    private static OptionalDouble mean(List<Double> v) {
        return v.isEmpty()
                ? OptionalDouble.empty()
                : OptionalDouble.of(
                        v.stream().mapToDouble(Double::doubleValue).average().orElse(0));
    }

    private OptionalDouble ece(List<Outcome> rows) {
        if (rows.isEmpty()) return OptionalDouble.empty();
        double e = 0;
        for (Bin b : curveBins(rows))
            e += b.n() / (double) rows.size() * Math.abs(b.accuracy() - b.meanConfidence());
        return OptionalDouble.of(e);
    }

    private static OptionalDouble brier(List<Outcome> rows) {
        return mean(
                rows.stream()
                        .map(o -> Math.pow(o.conf().getAsDouble() - (o.correct() ? 1 : 0), 2))
                        .toList());
    }

    /** Accuracy over the {@code coverage} share of answers with the highest confidence. */
    private static OptionalDouble selective(List<Outcome> rows, double coverage) {
        if (rows.isEmpty()) return OptionalDouble.empty();
        List<Outcome> sorted =
                rows.stream()
                        .sorted(Comparator.comparingDouble((Outcome o) -> -o.conf().getAsDouble()))
                        .toList();
        int k = Math.max(1, (int) (sorted.size() * coverage));
        return mean(sorted.subList(0, k).stream().map(o -> o.correct() ? 1.0 : 0.0).toList());
    }

    private List<Bin> curveBins(List<Outcome> rows) {
        List<Bin> out = new ArrayList<>();
        for (int b = 0; b < bins; b++) {
            double lo = b / (double) bins;
            double hi = (b + 1) / (double) bins;
            int last = b;
            List<Outcome> in =
                    rows.stream()
                            .filter(
                                    o -> {
                                        double c = o.conf().getAsDouble();
                                        return (c >= lo && c < hi)
                                                || (last == bins - 1 && c == 1.0);
                                    })
                            .toList();
            if (!in.isEmpty()) {
                out.add(
                        new Bin(
                                lo,
                                hi,
                                in.size(),
                                mean(in.stream().map(o -> o.correct() ? 1.0 : 0.0).toList())
                                        .orElse(0),
                                in.stream()
                                        .mapToDouble(o -> o.conf().getAsDouble())
                                        .average()
                                        .orElse(0)));
            }
        }
        return out;
    }

    private static long percentile(List<Long> sorted, double q) {
        return sorted.isEmpty()
                ? 0
                : sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(q * sorted.size()) - 1));
    }

    /** Configures a {@link ClassifierComparison}. */
    public static final class Builder {

        private final List<Case> cases = new ArrayList<>();
        private final Map<String, Classifier> contenders = new LinkedHashMap<>();
        private final Map<String, double[]> prices = new LinkedHashMap<>();
        private int repeats = 1;
        private int parallelism = 4;
        private int bins = 10;

        private Builder() {}

        public Builder cases(List<Case> cases) {
            this.cases.addAll(cases);
            return this;
        }

        /**
         * Adds a contender; the name labels its row. Contenders are compared in the order added.
         */
        public Builder contender(String name, Classifier classifier) {
            if (contenders.putIfAbsent(
                            name,
                            java.util.Objects.requireNonNull(
                                    classifier, "classifier cannot be null"))
                    != null) {
                throw new IllegalArgumentException("contender '" + name + "' was added twice");
            }
            return this;
        }

        /**
         * What a contender costs, in dollars per million input and output tokens, for the cost
         * column. Without it the column reads n/a.
         */
        public Builder price(String contender, double inputPerMillion, double outputPerMillion) {
            prices.put(contender, new double[] {inputPerMillion, outputPerMillion});
            return this;
        }

        /** Asks each case this many times, to measure how often the answer flips. Default 1. */
        public Builder repeats(int repeats) {
            if (repeats < 1)
                throw new IllegalArgumentException("repeats must be at least 1, got " + repeats);
            this.repeats = repeats;
            return this;
        }

        /**
         * Calls in flight at once. Default 4; use 1 for a scripted backend whose answers depend on
         * call order.
         */
        public Builder parallelism(int parallelism) {
            if (parallelism < 1)
                throw new IllegalArgumentException(
                        "parallelism must be at least 1, got " + parallelism);
            this.parallelism = parallelism;
            return this;
        }

        public ClassifierComparison build() {
            if (contenders.isEmpty()) throw new IllegalStateException("add at least one contender");
            if (cases.isEmpty()) throw new IllegalStateException("add at least one case");
            return new ClassifierComparison(this);
        }
    }
}
