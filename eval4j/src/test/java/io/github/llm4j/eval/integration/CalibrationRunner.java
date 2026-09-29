package io.github.llm4j.eval.integration;

import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.LLMClient;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.eval.compare.PairwiseJudge;
import io.github.llm4j.eval.judge.ConversationJudgeCondition;
import io.github.llm4j.eval.judge.ConversationJudgePresets;
import io.github.llm4j.eval.judge.JudgeCalls;
import io.github.llm4j.eval.judge.LlmJudgePresets;
import io.github.llm4j.eval.judge.Transcript;
import io.github.llm4j.provider.anthropic.AnthropicProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs a labelled calibration {@link Dataset} against one or more live judges and writes a Markdown
 * report (plan §4). Shared by the round-1 and round-2 calibration studies.
 */
final class CalibrationRunner {

    private static final int THREADS = 4;
    private static final Pattern CHUNK_FLAG =
            Pattern.compile("\\[chunk (\\d+): (NOT relevant|relevant)\\]");

    record RagCase(String question, String expected, String[] chunks, boolean[] relevant) {}

    record RecallCase(String expected, String context, double expectedScore, String kind) {}

    /** A conversation pair: {@code clean} and a matched {@code defective} variant. */
    record ConvoPair(Transcript clean, Transcript defective, List<String> intentions, String role) {
        ConvoPair(Transcript clean, Transcript defective, List<String> intentions) {
            this(clean, defective, intentions, null);
        }
    }

    /** {@code tie}: the two answers are equivalent and the expected verdict is TIE. */
    record PairCase(String input, String good, String bad, boolean tie) {
        PairCase(String input, String good, String bad) {
            this(input, good, bad, false);
        }
    }

    record Dataset(
            String name,
            List<RagCase> rag,
            List<RecallCase> recall,
            List<ConvoPair> retention,
            List<ConvoPair> role,
            List<ConvoPair> completeness,
            List<ConvoPair> relevancy,
            List<PairCase> pairs) {}

    private CalibrationRunner() {}

    static RagCase rag(String q, String a, Object... chunkThenLabel) {
        int n = chunkThenLabel.length / 2;
        String[] chunks = new String[n];
        boolean[] rel = new boolean[n];
        for (int i = 0; i < n; i++) {
            chunks[i] = (String) chunkThenLabel[2 * i];
            rel[i] = (Boolean) chunkThenLabel[2 * i + 1];
        }
        return new RagCase(q, a, chunks, rel);
    }

    static Transcript convo(String... alternatingUserAssistant) {
        Transcript.Builder b = Transcript.builder();
        for (int i = 0; i < alternatingUserAssistant.length; i += 2) {
            b.user(alternatingUserAssistant[i]).assistant(alternatingUserAssistant[i + 1]);
        }
        return b.build();
    }

    /**
     * Runs the dataset on every judge in {@code EVAL4J_CALIBRATION_MODELS} and writes the report.
     */
    static String execute(Dataset d, String title, String outFile, boolean stability)
            throws Exception {
        String key = System.getenv("EVAL4J_ANTHROPIC_API_KEY");
        String models =
                System.getenv()
                        .getOrDefault(
                                "EVAL4J_CALIBRATION_MODELS",
                                "claude-haiku-4-5-20251001,claude-sonnet-5-5");
        StringBuilder md = new StringBuilder();
        md.append("# ").append(title).append("\n\n");
        md.append(
                        "_Synthetic datasets labelled by the module author (not independent humans) with ")
                .append(
                        "planted defects; figures are optimistic relative to a human-labeled study._\n\n");
        md.append("Dataset `")
                .append(d.name())
                .append("`: ")
                .append(d.rag().size())
                .append(" RAG cases, ")
                .append(d.recall().size())
                .append(" recall cases, conversation pairs (retention ")
                .append(d.retention().size())
                .append(", role ")
                .append(d.role().size())
                .append(", completeness ")
                .append(d.completeness().size())
                .append(", relevancy ")
                .append(d.relevancy().size())
                .append("), ")
                .append(d.pairs().size())
                .append(" pairwise pairs.\n\n");

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            boolean first = true;
            List<String> summaries = new ArrayList<>();
            for (String model : models.split(",")) {
                model = model.trim();
                LLMClient client =
                        new DefaultLLMClient(
                                new AnthropicProvider(
                                        LLMConfig.builder()
                                                .apiKey(key)
                                                .defaultModel(model)
                                                .build()));
                md.append("## Judge: `").append(model).append("`\n\n");
                long start = System.nanoTime();
                summaries.add(model + ": " + runJudge(md, client, pool, d, stability && first));
                md.append(
                        String.format(
                                Locale.ROOT,
                                "_Wall time: %d s_\n\n",
                                (System.nanoTime() - start) / 1_000_000_000L));
                first = false;
            }
            md.append("## Cross-judge consistency\n\n");
            for (String s : summaries) {
                md.append("- ").append(s).append('\n');
            }
        } finally {
            pool.shutdownNow();
        }
        Path out = Path.of(outFile);
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, md.toString());
        System.out.println(md);
        return md.toString();
    }

    private static String runJudge(
            StringBuilder md, LLMClient client, ExecutorService pool, Dataset d, boolean stability)
            throws Exception {
        LlmJudgePresets presets = LlmJudgePresets.using(client);
        ConversationJudgePresets conv = ConversationJudgePresets.using(client);
        StringBuilder ordering = new StringBuilder();

        // RAG relevancy + precision
        List<double[]> ragResults =
                parallel(
                        pool,
                        d.rag(),
                        c -> {
                            var rel =
                                    presets.contextualRelevancy(c.question(), List.of(c.chunks()))
                                            .evaluate();
                            var prec =
                                    presets.contextualPrecision(
                                                    c.question(), c.expected(), List.of(c.chunks()))
                                            .evaluate();
                            int agree = 0;
                            int judgedRelevant = 0;
                            Matcher m = CHUNK_FLAG.matcher(rel.reason());
                            while (m.find()) {
                                boolean judged = m.group(2).equals("relevant");
                                judgedRelevant += judged ? 1 : 0;
                                if (judged == c.relevant()[Integer.parseInt(m.group(1))]) {
                                    agree++;
                                }
                            }
                            return new double[] {
                                rel.score(),
                                prec.score(),
                                agree,
                                c.chunks().length,
                                labelRelevancy(c.relevant()),
                                labelPrecision(c.relevant()),
                                judgedRelevant
                            };
                        });
        double agreed = 0;
        double chunks = 0;
        double relErr = 0;
        double precErr = 0;
        for (double[] r : ragResults) {
            agreed += r[2];
            chunks += r[3];
            relErr += Math.abs(r[0] - r[4]);
            precErr += Math.abs(r[1] - r[5]);
        }
        double n = ragResults.size();
        md.append("### Contextual relevancy / precision\n\n");
        md.append(row("Per-chunk agreement with labels", agreed / chunks, 0.80));
        md.append(
                String.format(
                        Locale.ROOT,
                        "- Cohen's kappa (per chunk): %.2f (target >= 0.50)%n",
                        kappa(ragResults)));
        md.append(
                String.format(
                        Locale.ROOT,
                        "- Mean abs error, relevancy: %.3f; precision: %.3f%n%n",
                        relErr / n,
                        precErr / n));

        // recall
        List<double[]> recall =
                parallel(
                        pool,
                        d.recall(),
                        c ->
                                new double[] {
                                    presets.contextualRecall(
                                                    "q", c.expected(), List.of(c.context()))
                                            .evaluate()
                                            .score(),
                                    c.expectedScore()
                                });
        double recallErr =
                recall.stream().mapToDouble(r -> Math.abs(r[0] - r[1])).average().orElse(0);
        md.append("### Contextual recall\n\n");
        md.append("| Expected score | Cases | Mean judged score |\n|---|---|---|\n");
        boolean monotone = true;
        double previous = Double.NEGATIVE_INFINITY;
        for (double expected :
                recall.stream().mapToDouble(r -> r[1]).distinct().sorted().toArray()) {
            double mean = mean(recall, expected);
            long count = recall.stream().filter(r -> r[1] == expected).count();
            md.append(String.format(Locale.ROOT, "| %.2f | %d | %.2f |%n", expected, count, mean));
            monotone &= mean >= previous;
            previous = mean;
        }
        md.append(
                String.format(
                        Locale.ROOT,
                        "%n- Mean scores non-decreasing with expected support: %s; mean abs error vs"
                                + " expected: %.3f%n%n",
                        monotone ? "yes" : "NO",
                        recallErr));
        ordering.append("recall monotone=").append(monotone).append("; ");

        // conversation metrics
        md.append("### Conversation metrics (clean vs planted defect)\n\n");
        md.append(
                "| Metric | Clean mean | Defective mean | Gap | Clean > matched defective | Gap >= 0.25"
                        + " | >= 80% pairs |\n|---|---|---|---|---|---|---|\n");
        record Spec(
                String name,
                List<ConvoPair> pairs,
                Function<ConvoPair, ConversationJudgeCondition> cond) {}
        List<Spec> specs =
                List.of(
                        new Spec(
                                "Knowledge retention",
                                d.retention(),
                                p -> conv.knowledgeRetention()),
                        new Spec("Role adherence", d.role(), p -> conv.roleAdherence(p.role())),
                        new Spec(
                                "Completeness",
                                d.completeness(),
                                p -> conv.conversationCompleteness(p.intentions())),
                        new Spec("Relevancy", d.relevancy(), p -> conv.conversationRelevancy()));
        for (Spec spec : specs) {
            List<double[]> scores =
                    parallel(
                            pool,
                            spec.pairs(),
                            p ->
                                    new double[] {
                                        spec.cond().apply(p).evaluate(p.clean()).score(),
                                        spec.cond().apply(p).evaluate(p.defective()).score()
                                    });
            double cleanMean = scores.stream().mapToDouble(s -> s[0]).average().orElse(0);
            double badMean = scores.stream().mapToDouble(s -> s[1]).average().orElse(0);
            long wins = scores.stream().filter(s -> s[0] > s[1]).count();
            double frac = wins / (double) scores.size();
            md.append(
                    String.format(
                            Locale.ROOT,
                            "| %s | %.2f | %.2f | %.2f | %d/%d | %s | %s |%n",
                            spec.name(),
                            cleanMean,
                            badMean,
                            cleanMean - badMean,
                            wins,
                            scores.size(),
                            cleanMean - badMean >= 0.25 ? "PASS" : "FAIL",
                            frac >= 0.80 ? "PASS" : "FAIL"));
            ordering.append(spec.name())
                    .append(" clean>defect=")
                    .append(cleanMean > badMean)
                    .append("; ");
        }
        md.append('\n');

        // pairwise
        md.append("### Pairwise comparison\n\n");
        List<int[]> pairwise =
                parallel(
                        pool,
                        indexes(d.pairs().size()),
                        i -> {
                            PairCase c = d.pairs().get(i);
                            boolean goodIsB = i % 2 == 0;
                            Object a = goodIsB ? c.bad() : c.good();
                            Object b = goodIsB ? c.good() : c.bad();
                            JudgeCalls calls = JudgeCalls.using(client);
                            String criteria = "Correct, direct and helpful";
                            var mitigated =
                                    PairwiseJudge.using(calls, criteria).judge(c.input(), a, b);
                            PairwiseJudge raw =
                                    PairwiseJudge.using(calls, criteria).swapPositions(false);
                            var forward = raw.judge(c.input(), a, b).winner();
                            var swapped = raw.judge(c.input(), b, a).winner();
                            PairwiseJudge.Winner unswapped =
                                    swapped == PairwiseJudge.Winner.A
                                            ? PairwiseJudge.Winner.B
                                            : swapped == PairwiseJudge.Winner.B
                                                    ? PairwiseJudge.Winner.A
                                                    : PairwiseJudge.Winner.TIE;
                            PairwiseJudge.Winner expected =
                                    c.tie()
                                            ? PairwiseJudge.Winner.TIE
                                            : goodIsB
                                                    ? PairwiseJudge.Winner.B
                                                    : PairwiseJudge.Winner.A;
                            boolean decisive = mitigated.winner() != PairwiseJudge.Winner.TIE;
                            return new int[] {
                                c.tie() ? 1 : 0, // 0: is an equal-pair
                                mitigated.winner() == expected ? 1 : 0, // 1: matches expected
                                decisive ? 1 : 0, // 2: decisive
                                decisive && mitigated.winner() != expected
                                        ? 1
                                        : 0, // 3: wrong decisive
                                forward != unswapped ? 1 : 0 // 4: raw order flip
                            };
                        });
        int subtleTotal = 0;
        int subtleCorrect = 0;
        int subtleDecisive = 0;
        int subtleWrongDecisive = 0;
        int equalTotal = 0;
        int equalTies = 0;
        int flips = 0;
        for (int[] r : pairwise) {
            flips += r[4];
            if (r[0] == 1) {
                equalTotal++;
                equalTies += r[1];
            } else {
                subtleTotal++;
                subtleCorrect += r[1];
                subtleDecisive += r[2];
                subtleWrongDecisive += r[3];
            }
        }
        md.append(
                String.format(
                        Locale.ROOT,
                        "- Pairs with a better answer (%d): %d correct, %d decisive, %d decisive-but-wrong"
                                + " (agreement on decisive: %.0f%%, target >= 75%%)%n",
                        subtleTotal,
                        subtleCorrect,
                        subtleDecisive,
                        subtleWrongDecisive,
                        subtleDecisive == 0
                                ? 0
                                : 100.0 * (subtleDecisive - subtleWrongDecisive) / subtleDecisive));
        if (equalTotal > 0) {
            md.append(
                    String.format(
                            Locale.ROOT,
                            "- Equivalent-answer pairs (%d): %d judged TIE (a decisive win between equivalent"
                                    + " answers is a false preference)%n",
                            equalTotal,
                            equalTies));
        }
        md.append(
                String.format(
                        Locale.ROOT,
                        "- Order-flip rate without mitigation: %d/%d pairs (%.0f%%)%n%n",
                        flips,
                        pairwise.size(),
                        100.0 * flips / pairwise.size()));
        ordering.append("pairwise wrong-decisive=").append(subtleWrongDecisive);

        if (stability) {
            md.append("### Stability (relevancy, first 3 cases, no cache)\n\n");
            double sd1 = 0;
            double sd3 = 0;
            for (int c = 0; c < Math.min(3, d.rag().size()); c++) {
                RagCase rc = d.rag().get(c);
                sd1 += sd(repeat(pool, 5, () -> relevancyWith(client, rc, 1)));
                sd3 += sd(repeat(pool, 3, () -> relevancyWith(client, rc, 3)));
            }
            md.append(
                    String.format(
                            Locale.ROOT,
                            "- Mean score std-dev: samples(1) over 5 runs = %.3f; samples(3) over 3 runs ="
                                    + " %.3f%n%n",
                            sd1 / 3,
                            sd3 / 3));
        }
        return ordering.toString();
    }

    // --- helpers ---

    private static double relevancyWith(LLMClient client, RagCase c, int samples) {
        return io.github.llm4j.eval.judge.RagContextCondition.builder(
                        io.github.llm4j.eval.judge.RagContextCondition.Metric.RELEVANCY)
                .input(c.question())
                .retrievalContext(List.of(c.chunks()))
                .calls(JudgeCalls.using(client).samples(samples))
                .build()
                .evaluate()
                .score();
    }

    private static List<Double> repeat(
            ExecutorService pool, int times, java.util.concurrent.Callable<Double> task)
            throws Exception {
        List<Future<Double>> futures = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            futures.add(pool.submit(task));
        }
        List<Double> out = new ArrayList<>();
        for (Future<Double> f : futures) {
            out.add(f.get());
        }
        return out;
    }

    private static double sd(List<Double> xs) {
        double mean = xs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return Math.sqrt(xs.stream().mapToDouble(x -> (x - mean) * (x - mean)).sum() / xs.size());
    }

    private static <T, R> List<R> parallel(ExecutorService pool, List<T> items, Function<T, R> fn)
            throws Exception {
        List<Future<R>> futures = new ArrayList<>();
        for (T item : items) {
            futures.add(pool.submit(() -> fn.apply(item)));
        }
        List<R> out = new ArrayList<>();
        for (Future<R> f : futures) {
            out.add(f.get());
        }
        return out;
    }

    private static List<Integer> indexes(int n) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(i);
        }
        return out;
    }

    private static double mean(List<double[]> rows, double expected) {
        return rows.stream()
                .filter(r -> r[1] == expected)
                .mapToDouble(r -> r[0])
                .average()
                .orElse(Double.NaN);
    }

    private static double labelRelevancy(boolean[] relevant) {
        int count = 0;
        for (boolean r : relevant) {
            count += r ? 1 : 0;
        }
        return count / (double) relevant.length;
    }

    private static double labelPrecision(boolean[] relevant) {
        int total = 0;
        for (boolean r : relevant) {
            total += r ? 1 : 0;
        }
        if (total == 0) {
            return 0;
        }
        double sum = 0;
        int seen = 0;
        for (int k = 1; k <= relevant.length; k++) {
            if (relevant[k - 1]) {
                seen++;
                sum += seen / (double) k;
            }
        }
        return sum / total;
    }

    /** Cohen's kappa over all chunk judgments (judge vs label). */
    private static double kappa(List<double[]> rows) {
        double agree = 0;
        double total = 0;
        double relLabelled = 0;
        double relJudged = 0;
        for (double[] r : rows) {
            agree += r[2];
            total += r[3];
            relLabelled += r[4] * r[3];
            relJudged += r[6];
        }
        double po = agree / total;
        double pl = relLabelled / total;
        double pj = relJudged / total;
        double pe = pl * pj + (1 - pl) * (1 - pj);
        return pe >= 1 ? 1 : (po - pe) / (1 - pe);
    }

    private static String row(String label, double value, double target) {
        return String.format(
                Locale.ROOT,
                "- %s: %.1f%% (target >= %.0f%%) - %s%n",
                label,
                100 * value,
                100 * target,
                value >= target ? "PASS" : "FAIL");
    }
}
