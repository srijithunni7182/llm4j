package io.github.llm4j.eval.optimize;

import io.github.llm4j.eval.criteria.Criteria;
import io.github.llm4j.eval.criteria.Criterion;
import io.github.llm4j.eval.dataset.EvalScenario;
import io.github.llm4j.eval.support.StubJudge;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A deterministic world for testing the optimizer without an LLM. A "prompt" is a bag of lesson
 * tokens such as {@code [L:units]}; each scenario belongs to a category and scores well only if the
 * prompt contains that category's lesson. The scripted rewriter reads the failure feedback and adds
 * the missing lessons, so the known optimum (all lessons present) is reachable and testable.
 */
final class SimulationSupport {

    static final List<String> CATEGORIES = List.of("units", "dates", "currency", "names");
    static final String PARAM = "system-prompt";

    private static final Pattern LESSON = Pattern.compile("\\[L:(\\w+)]");
    private static final Pattern MISSING = Pattern.compile("Missing lesson for category (\\w+)");

    private SimulationSupport() {}

    static List<EvalScenario> scenarios(int perCategory) {
        List<EvalScenario> out = new ArrayList<>();
        for (String category : CATEGORIES) {
            for (int i = 1; i <= perCategory; i++) {
                out.add(
                        new EvalScenario(
                                category + "-" + i,
                                category + " question " + i,
                                null,
                                null,
                                null,
                                null,
                                null));
            }
        }
        return out;
    }

    /** Output encodes which lessons the prompt contained, and the scenario's category. */
    static SystemUnderTest system() {
        return (candidate, scenario) -> {
            StringBuilder lessons = new StringBuilder();
            Matcher m = LESSON.matcher(candidate.get(PARAM));
            while (m.find()) {
                lessons.append(m.group(1)).append(',');
            }
            return "cat="
                    + scenario.name().substring(0, scenario.name().indexOf('-'))
                    + ";lessons="
                    + lessons;
        };
    }

    /** Wraps a system, recording every (candidate id, scenario) it is asked to run. */
    static final class CountingSystem implements SystemUnderTest {
        private final SystemUnderTest delegate;
        final java.util.List<String> calls =
                java.util.Collections.synchronizedList(new ArrayList<>());

        CountingSystem(SystemUnderTest delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object run(Candidate candidate, EvalScenario scenario) {
            calls.add(candidate.id() + ":" + scenario.name());
            return delegate.run(candidate, scenario);
        }

        long count() {
            return calls.size();
        }

        long countFor(java.util.Collection<EvalScenario> scenarios) {
            java.util.Set<String> names = new java.util.HashSet<>();
            scenarios.forEach(sc -> names.add(sc.name()));
            synchronized (calls) {
                return calls.stream()
                        .filter(c -> names.contains(c.substring(c.indexOf(':') + 1)))
                        .count();
            }
        }
    }

    /** 1.0 if the prompt had the category's lesson, else 0.2 with actionable feedback. */
    static Criterion lessonCriterion() {
        return Criteria.scenarioAssertion(
                "has-lesson",
                (scenario, output) -> {
                    String out = String.valueOf(output);
                    String category = out.substring(4, out.indexOf(';'));
                    if (!out.contains("lessons=")
                            || !out.substring(out.indexOf("lessons=")).contains(category + ",")) {
                        throw new AssertionError(
                                "Missing lesson for category "
                                        + category
                                        + ": the prompt does not say how to handle "
                                        + category
                                        + " questions");
                    }
                });
    }

    /**
     * Same scoring as {@link #lessonCriterion()} but 0.2 instead of 0 when the lesson is missing.
     */
    static Criterion partialCreditCriterion() {
        return Criteria.judged(
                "lesson-score",
                output -> {
                    String out = String.valueOf(output);
                    String category = out.substring(4, out.indexOf(';'));
                    boolean has = out.substring(out.indexOf("lessons=")).contains(category + ",");
                    return new io.github.llm4j.eval.judge.JudgeVerdict(
                            has ? 1.0 : 0.2,
                            has
                                    ? "ok"
                                    : "Missing lesson for category "
                                            + category
                                            + ": the prompt does not cover it");
                },
                0.99);
    }

    /** Extracts one delimited section body from a rewriter user message. */
    static String section(String message, String label) {
        String begin = "<<<BEGIN " + label + ">>>\n";
        int start = message.indexOf(begin);
        if (start < 0) {
            return "";
        }
        start += begin.length();
        return message.substring(start, message.indexOf("\n<<<END " + label + ">>>", start));
    }

    static String jsonReply(String text) {
        return "```json\n{\"new_text\": " + quote(text) + "}\n```";
    }

    static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    /** A rewriter that adds the lesson for every category named in the failure feedback. */
    static StubJudge lessonRewriter() {
        return rewriter(
                (current, failures) -> {
                    StringBuilder text = new StringBuilder(current);
                    Matcher m = MISSING.matcher(failures);
                    while (m.find()) {
                        String token = "[L:" + m.group(1) + "]";
                        if (text.indexOf(token) < 0) {
                            text.append(' ').append(token);
                        }
                    }
                    return text.toString();
                });
    }

    /**
     * Like {@link #lessonRewriter()} but adds only the first missing lesson per call (slow
     * learner).
     */
    static StubJudge slowRewriter() {
        return rewriter(
                (current, failures) -> {
                    Matcher m = MISSING.matcher(failures);
                    while (m.find()) {
                        String token = "[L:" + m.group(1) + "]";
                        if (!current.contains(token)) {
                            return current + " " + token;
                        }
                    }
                    return current + " (no change needed)";
                });
    }

    /** A rewriter whose proposal is any function of (current text, failures text). */
    static StubJudge rewriter(BiFunction<String, String, String> policy) {
        return new StubJudge(
                request -> {
                    String user = StubJudge.userMessage(request);
                    return jsonReply(
                            policy.apply(section(user, "CURRENT TEXT"), section(user, "FAILURES")));
                });
    }

    static PromptOptimizer.Builder optimizer(StubJudge rewriter) {
        return PromptOptimizer.builder()
                .seed(Candidate.of(PARAM, "You are a helpful assistant."))
                .system(system())
                .criteria(List.of(partialCreditCriterion()))
                .scenarios(scenarios(15))
                .split(Split.ratios(0.5, 0.3, 0.2).seed(1))
                .rewriter(rewriter)
                .budget(OptimizerBudget.builder().maxRollouts(3000).maxRounds(60).build())
                .targetValidationMean(0.95)
                .randomSeed(7)
                .acknowledgeSideEffects();
    }
}
