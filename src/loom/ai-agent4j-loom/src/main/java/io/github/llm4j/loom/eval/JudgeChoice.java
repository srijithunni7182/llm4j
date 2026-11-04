package io.github.llm4j.loom.eval;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.function.Predicate;

/**
 * Who judges the plain-sentence checks (docs/specs/28-evaluation-first, R12). A model must not grade
 * its own work by default, so the order is: {@code --judge}; {@code judge:} in {@code dataset.yaml};
 * {@code judge} in the project's {@code eval.properties}; otherwise the cheapest model the project
 * can reach that is not the model being judged, preferring another provider. {@code --judge same}
 * judges a model with itself on purpose. When only one model can be reached it judges itself, with a
 * caution, and the run never refuses for want of a second key.
 *
 * @param model the judge
 * @param source why it was chosen, in words
 * @param selfJudging whether the judge is the model being judged
 * @param caution what to be careful of, or null
 */
public record JudgeChoice(String model, String source, boolean selfJudging, String caution) {

    /** The word for judging a model with itself on purpose. */
    public static final String SAME = "same";

    private static final String SELF_CAUTION =
            "self-judging: scores may be biased toward the judged model's own style";

    /**
     * Chooses the judge for answers written by {@code judged}.
     *
     * @param flag the {@code --judge} value, or null
     * @param dataset the {@code judge:} of dataset.yaml, or null
     * @param project the project setting, or null
     * @param judged the model whose answers are judged, or null
     * @param candidates the models the project names, in the order it names them
     * @param reachable whether a model's key is available
     */
    public static JudgeChoice choose(
            String flag,
            String dataset,
            String project,
            String judged,
            List<String> candidates,
            Predicate<String> reachable) {
        if (SAME.equals(flag) && judged != null)
            return new JudgeChoice(judged, "--judge same, on purpose", true, SELF_CAUTION);
        if (flag != null && !SAME.equals(flag)) return explicit(flag, "--judge", judged);
        if (dataset != null) return explicit(dataset, "judge: in dataset.yaml", judged);
        if (project != null) return explicit(project, "judge in eval.properties", judged);

        List<String> others = new ArrayList<>();
        for (String m : candidates)
            if (m != null && !m.equals(judged) && reachable.test(m) && !others.contains(m))
                others.add(m);
        if (others.isEmpty()) {
            String self = judged != null ? judged : candidates.stream().filter(reachable).findFirst().orElse(null);
            return new JudgeChoice(
                    self,
                    "the only model this project can reach",
                    true,
                    "this project can reach only one model, so it judges itself: " + SELF_CAUTION);
        }
        List<String> otherProvider = others.stream().filter(m -> judged == null || !provider(m).equals(provider(judged))).toList();
        List<String> pool = otherProvider.isEmpty() ? others : otherProvider;
        String cheapest = pool.stream().min(Comparator.comparingInt(JudgeChoice::cost)).orElseThrow();
        return new JudgeChoice(
                cheapest,
                "the cheapest other model this project can reach" + (otherProvider.isEmpty() ? "" : ", from another provider"),
                false,
                null);
    }

    private static JudgeChoice explicit(String model, String source, String judged) {
        boolean self = model.equals(judged);
        return new JudgeChoice(model, source, self, self ? SELF_CAUTION : null);
    }

    /** One line for the report and the confirmation: the judge, who it judges, and why. */
    public String describe() {
        return model + " (" + source + ")";
    }

    /** The describe line with the model being judged, for "Read this first". */
    public String describe(String judged) {
        return model + " judging " + (judged == null ? "the agents" : judged) + " (" + source + ")";
    }

    // ── settings ─────────────────────────────────────────────────────────────────────────────

    /** The {@code judge:} line of {@code dataset.yaml}, or null. */
    public static String datasetJudge(Path goldenDir) {
        try {
            Path file = goldenDir.resolve("dataset.yaml");
            if (!Files.isRegularFile(file)) return null;
            for (String line : Files.readString(file).split("\\R")) {
                String l = line.strip();
                if (l.startsWith("judge:")) {
                    String v = l.substring("judge:".length()).strip().replaceAll("^[\"']|[\"']$", "");
                    return v.isEmpty() ? null : v;
                }
            }
        } catch (IOException e) {
            return null; // no setting: the default order goes on
        }
        return null;
    }

    /** The {@code judge} of the project's {@code eval.properties}, or null. */
    public static String projectJudge(Path projectRoot) {
        try {
            Path file = projectRoot.resolve("eval.properties");
            if (!Files.isRegularFile(file)) return null;
            Properties p = new Properties();
            p.load(new StringReader(Files.readString(file)));
            String v = p.getProperty("judge");
            return v == null || v.isBlank() ? null : v.strip();
        } catch (IOException e) {
            return null;
        }
    }

    // ── ranking ──────────────────────────────────────────────────────────────────────────────

    /** The provider a model name belongs to: the part before a slash, else its leading letters. */
    static String provider(String model) {
        String m = model.toLowerCase(Locale.ROOT);
        int slash = m.indexOf('/');
        if (slash > 0) return m.substring(0, slash);
        int end = 0;
        while (end < m.length() && Character.isLetter(m.charAt(end))) end++;
        return m.substring(0, end);
    }

    /**
     * A rough price rank from the model's name (lower is cheaper), used only when nothing better is
     * known. It is shown with the choice, and any setting above overrides it.
     */
    static int cost(String model) {
        // whole words only: "gemini" is not "mini"
        List<String> words = List.of(model.toLowerCase(Locale.ROOT).split("[^a-z]+"));
        if (words.contains("nano") || words.contains("lite")) return 0;
        if (words.contains("flash") || words.contains("haiku") || words.contains("mini") || words.contains("small")) return 1;
        if (words.contains("opus") || words.contains("pro") || words.contains("sol")) return 3;
        return 2;
    }
}
