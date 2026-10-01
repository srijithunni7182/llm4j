package io.github.llm4j.eval.integration;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.LLMClient;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.eval.criteria.Criteria;
import io.github.llm4j.eval.criteria.Criterion;
import io.github.llm4j.eval.dataset.EvalScenario;
import io.github.llm4j.eval.judge.LlmJudgePresets;
import io.github.llm4j.eval.optimize.Candidate;
import io.github.llm4j.eval.optimize.LlmCallCounter;
import io.github.llm4j.eval.optimize.OptimizationResult;
import io.github.llm4j.eval.optimize.OptimizerBudget;
import io.github.llm4j.eval.optimize.PromptConstraints;
import io.github.llm4j.eval.optimize.PromptOptimizer;
import io.github.llm4j.eval.optimize.Split;
import io.github.llm4j.eval.optimize.SystemUnderTest;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.provider.anthropic.AnthropicProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Efficacy study for the prompt optimizer (verification plan §4): does optimizing against a judge
 * really improve a prompt, and is the gain confirmed by measures the optimizer never saw?
 *
 * <p>Task: routing support tickets into five categories under a labelling policy the seed prompt
 * does not state (refund requests are billing even when about shipping; feature requests and praise
 * are "other"; carrier problems are shipping). Ground truth is an exact-match label, computed
 * independently of any judge.
 *
 * <p>Roles: the system model and the optimization judge J1 (Haiku) run the loop; the rewriter is a
 * different model (Sonnet); the final check J2 is a third model (Opus) that never took part. The
 * report compares seed and best on the sealed test split by J1, by J2 and by ground truth, and adds
 * two controls: a no-optimization re-score (noise) and an unguided random-edit rewriter.
 *
 * <p>Opt-in: needs {@code EVAL4J_ANTHROPIC_API_KEY}; writes {@code target/optimizer-efficacy.md}.
 * Never fails on a threshold miss — the report is the deliverable. Datasets are synthetic and
 * author-made.
 */
class PromptOptimizerEfficacyIntegrationTest {

    private static final List<String> LABELS =
            List.of("billing", "technical", "account", "shipping", "other");

    private static final String SEED_PROMPT =
            "You route customer support tickets. Categories: billing, technical, account, shipping,"
                    + " other. Reply with exactly one category word and nothing else.";

    /** A deliberately weak seed: it never names the categories, so the loop has real work to do. */
    private static final String WEAK_SEED_PROMPT =
            "You route customer support tickets to the right team. Reply with one word.";

    private static final String[][] TICKETS = {
        {"billing", "I was charged twice for my last order."},
        {"billing", "Please refund my purchase, the item arrived broken."},
        {"billing", "My invoice shows the wrong amount."},
        {"billing", "Can I get a refund for the shipping fee? The parcel was late."},
        {"billing", "Why did my card get charged after I cancelled?"},
        {"billing", "I need a receipt for my tax records."},
        {"technical", "The app crashes when I open the settings page."},
        {"technical", "Error 500 appears when I upload a photo."},
        {"technical", "The website is very slow and times out."},
        {"technical", "Notifications don't work on my Android phone."},
        {"technical", "The export button does nothing when I click it."},
        {"technical", "Video playback stutters and freezes."},
        {"account", "I can't log in, it says my password is wrong."},
        {"account", "How do I change the email address on my profile?"},
        {"account", "Please delete my account and all my data."},
        {"account", "I never received the verification code."},
        {"account", "My account was locked after several attempts."},
        {"account", "Can I merge two accounts I created by mistake?"},
        {"shipping", "Where is my package? It has been ten days."},
        {"shipping", "The tracking number doesn't show any updates."},
        {"shipping", "My order was delivered to the wrong address."},
        {"shipping", "Can I change the delivery date?"},
        {"shipping", "The courier left the parcel outside and it got stolen."},
        {"shipping", "Do you deliver to Norway?"},
        {"other", "Just wanted to say your team is fantastic, thank you!"},
        {"other", "Do you have any job openings?"},
        {"other", "I'd like to suggest a dark mode for the app."},
        {"other", "Are you partnering with any universities?"},
        {"other", "What are your opening hours at the office?"},
        {"other", "How can I write a review of the product?"},
    };

    private static final String[] WRAPS = {"%s", "Hi, %s Thanks."};

    private static final String[] GENERIC_ADVICE = {
        "Think carefully before answering.",
        "Consider the customer's tone.",
        "Be concise and precise.",
        "Double-check your answer.",
        "Read the whole ticket first.",
        "Prefer the most specific category.",
    };

    static List<EvalScenario> scenarios() {
        List<EvalScenario> out = new ArrayList<>();
        int i = 0;
        for (String[] ticket : TICKETS) {
            for (int w = 0; w < WRAPS.length; w++) {
                out.add(
                        new EvalScenario(
                                ticket[0] + "-" + (++i),
                                String.format(WRAPS[w], ticket[1]),
                                null,
                                ticket[0],
                                null,
                                null,
                                null));
            }
        }
        return out;
    }

    /**
     * Splits by <em>base ticket</em>: both wordings of a ticket always land in the same split, so
     * the rewriter can never see a near-duplicate of a test ticket. Ratios 50/30/20 of the base
     * tickets.
     */
    static io.github.llm4j.eval.optimize.DataSplit leakFreeSplit(
            List<EvalScenario> all, long seed) {
        int baseCount = all.size() / WRAPS.length;
        List<Integer> bases = new ArrayList<>();
        for (int b = 0; b < baseCount; b++) {
            bases.add(b);
        }
        java.util.Collections.shuffle(bases, new Random(seed));
        int train = (int) Math.round(baseCount * 0.5);
        int validation = (int) Math.round(baseCount * 0.3);
        List<EvalScenario> trainSet = new ArrayList<>();
        List<EvalScenario> validationSet = new ArrayList<>();
        List<EvalScenario> testSet = new ArrayList<>();
        for (int i = 0; i < bases.size(); i++) {
            List<EvalScenario> target =
                    i < train ? trainSet : i < train + validation ? validationSet : testSet;
            for (int w = 0; w < WRAPS.length; w++) {
                target.add(all.get(bases.get(i) * WRAPS.length + w));
            }
        }
        return new io.github.llm4j.eval.optimize.DataSplit(trainSet, validationSet, testSet);
    }

    private static LLMClient client(String key, String model) {
        return new DefaultLLMClient(
                new AnthropicProvider(LLMConfig.builder().apiKey(key).defaultModel(model).build()));
    }

    /** The routed label, or {@code null} if the reply is not exactly one known label. */
    static String label(Object output) {
        String text =
                String.valueOf(output).trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        return LABELS.contains(text) ? text : null;
    }

    private static SystemUnderTest system(LLMClient model) {
        return (candidate, scenario) ->
                model.chat(
                                LLMRequest.builder()
                                        .addSystemMessage(candidate.get("system-prompt"))
                                        .addUserMessage("Ticket: " + scenario.input())
                                        .temperature(0.0)
                                        .build())
                        .getContent();
    }

    /**
     * An unguided rewriter: appends a random generic sentence to the current text, ignoring
     * failures.
     */
    private static LLMClient randomEditRewriter(Random random) {
        return new io.github.llm4j.eval.support.StubJudge(
                request -> {
                    String user = io.github.llm4j.eval.support.StubJudge.userMessage(request);
                    String marker = "<<<BEGIN CURRENT TEXT>>>\n";
                    int start = user.indexOf(marker) + marker.length();
                    String current =
                            user.substring(start, user.indexOf("\n<<<END CURRENT TEXT>>>", start));
                    String edited =
                            current + " " + GENERIC_ADVICE[random.nextInt(GENERIC_ADVICE.length)];
                    return "```json\n{\"new_text\": \""
                            + edited.replace("\\", "\\\\")
                                    .replace("\"", "\\\"")
                                    .replace("\n", "\\n")
                            + "\"}\n```";
                });
    }

    private static double groundTruth(
            SystemUnderTest system, Candidate candidate, List<EvalScenario> scenarios) {
        long right =
                scenarios.stream()
                        .filter(s -> s.expectedOutput().equals(label(system.run(candidate, s))))
                        .count();
        return right / (double) scenarios.size();
    }

    private static double judged(
            SystemUnderTest system,
            Candidate candidate,
            List<EvalScenario> scenarios,
            LLMClient judge) {
        LlmJudgePresets presets = LlmJudgePresets.using(judge);
        return scenarios.stream()
                .mapToDouble(
                        s ->
                                presets.correctness(s.expectedOutput())
                                        .evaluate(system.run(candidate, s).toString())
                                        .score())
                .average()
                .orElse(0);
    }

    @Test
    void runEfficacyStudy() throws Exception {
        String key = System.getenv("EVAL4J_ANTHROPIC_API_KEY");
        assumeTrue(key != null && !key.isBlank(), "EVAL4J_ANTHROPIC_API_KEY not set");
        boolean weak =
                "weak"
                        .equalsIgnoreCase(
                                System.getenv().getOrDefault("EVAL4J_OPTIMIZER_SEED", "strong"));
        String seedPrompt = weak ? WEAK_SEED_PROMPT : SEED_PROMPT;
        String outFile =
                System.getenv()
                        .getOrDefault(
                                "EVAL4J_EFFICACY_OUT",
                                weak
                                        ? "target/optimizer-efficacy-weak.md"
                                        : "target/optimizer-efficacy.md");
        int seeds = Integer.parseInt(System.getenv().getOrDefault("EVAL4J_OPTIMIZER_SEEDS", "3"));
        int maxRollouts =
                Integer.parseInt(System.getenv().getOrDefault("EVAL4J_OPTIMIZER_ROLLOUTS", "500"));

        LLMClient systemModel =
                client(
                        key,
                        System.getenv()
                                .getOrDefault("EVAL4J_SYSTEM_MODEL", "claude-haiku-4-5-20251001"));
        LLMClient j1 =
                client(
                        key,
                        System.getenv()
                                .getOrDefault("EVAL4J_J1_MODEL", "claude-haiku-4-5-20251001"));
        LLMClient rewriterModel =
                client(
                        key,
                        System.getenv().getOrDefault("EVAL4J_REWRITER_MODEL", "claude-sonnet-5-5"));
        LLMClient j2 =
                client(key, System.getenv().getOrDefault("EVAL4J_J2_MODEL", "claude-opus-5-5"));

        SystemUnderTest system = system(systemModel);
        List<EvalScenario> all = scenarios();
        StringBuilder md = new StringBuilder("# Prompt optimizer efficacy study\n\n");
        md.append(
                "_Synthetic ticket-routing task, author-made; one task and few seeds, so treat as indicative._\n\n");
        md.append("Scenarios: ")
                .append(all.size())
                .append(
                        " (split 50/30/20 by base ticket per seed, so both wordings of a ticket stay together). Budget: ")
                .append(maxRollouts)
                .append(" rollouts. Seed prompt: `")
                .append(seedPrompt)
                .append("`\n\n");
        md.append(
                "| run | seed GT | best GT | seed J1 | best J1 | seed J2 | best J2 | generalized | stop | rounds | rollouts | LLM calls |\n|---|---|---|---|---|---|---|---|---|---|---|---|\n");

        for (int s = 1; s <= seeds; s++) {
            LlmCallCounter j1Counter = LlmCallCounter.wrap(j1);
            LlmCallCounter systemCounter = LlmCallCounter.wrap(systemModel);
            LlmJudgePresets presets = LlmJudgePresets.using(j1Counter);
            Criterion correctness =
                    Criteria.perScenario(
                            "correctness",
                            sc -> Criteria.judged(presets.correctness(sc.expectedOutput())));
            Criterion singleLabel =
                    Criteria.guardrail(
                            "single-label",
                            out -> {
                                if (label(out) == null) {
                                    throw new AssertionError(
                                            "the reply must be exactly one category word, got: "
                                                    + out);
                                }
                            });
            OptimizationResult result =
                    PromptOptimizer.builder()
                            .seed(Candidate.of("system-prompt", seedPrompt))
                            .system(system(systemCounter))
                            .criteria(List.of(correctness, singleLabel))
                            .scenarios(all)
                            .split(
                                    Split.explicit(
                                            leakFreeSplit(all, s).train(),
                                            leakFreeSplit(all, s).validation(),
                                            leakFreeSplit(all, s).test()))
                            .rewriter(rewriterModel)
                            .judge(j1)
                            .parameterDescription(
                                    "system-prompt",
                                    "System prompt of a model that routes customer support tickets to exactly one of five categories")
                            .constraints(PromptConstraints.builder().maxChars(2500).build())
                            .budget(OptimizerBudget.builder().maxRollouts(maxRollouts).build())
                            .targetValidationMean(0.95)
                            .parallelism(4)
                            .trackCalls(j1Counter, systemCounter)
                            .randomSeed(s)
                            .acknowledgeSideEffects()
                            .build()
                            .run();

            var split = leakFreeSplit(all, s);
            Candidate seed = result.seed();
            Candidate best = result.best();
            md.append(
                    String.format(
                            Locale.ROOT,
                            "| seed %d | %.2f | %.2f | %.2f | %.2f | %.2f | %.2f | %s | %s | %d | %d | %d |%n",
                            s,
                            groundTruth(system, seed, split.test()),
                            groundTruth(system, best, split.test()),
                            result.seedScores().testMean(),
                            result.bestScores().testMean(),
                            judged(system, seed, split.test(), j2),
                            judged(system, best, split.test(), j2),
                            result.generalized(),
                            result.stopReason(),
                            result.trace().size(),
                            result.cost().rollouts(),
                            result.cost().trackedLlmCalls() + result.cost().rewriterCalls()));
            md.append("\n<details><summary>seed ")
                    .append(s)
                    .append(" best prompt and verdict</summary>\n\n```\n")
                    .append(best.get("system-prompt"))
                    .append("\n```\n\n")
                    .append(String.join("; ", result.verdict().reasons()))
                    .append("\n\n</details>\n\n");
            result.writeReport(Path.of("target/optimizer-efficacy-report-seed" + s));
        }

        // control 1: noise only. Re-score the untouched seed twice on the same test split.
        var control = leakFreeSplit(all, 1);
        Candidate seed = Candidate.of("system-prompt", seedPrompt);
        md.append("## Control: no optimization (noise)\n\n");
        md.append(
                String.format(
                        Locale.ROOT,
                        "Seed ground-truth accuracy on test, two independent runs: %.3f and %.3f.%n%n",
                        groundTruth(system, seed, control.test()),
                        groundTruth(system, seed, control.test())));

        // control 2: unguided random edits
        Random random = new Random(11);
        LLMClient randomRewriter = randomEditRewriter(random);
        md.append("## Control: random edits\n\n");
        OptimizationResult randomResult =
                PromptOptimizer.builder()
                        .seed(seed)
                        .system(system(systemModel))
                        .criteria(
                                List.of(
                                        Criteria.perScenario(
                                                "correctness",
                                                sc ->
                                                        Criteria.judged(
                                                                LlmJudgePresets.using(j1)
                                                                        .correctness(
                                                                                sc
                                                                                        .expectedOutput())))))
                        .scenarios(all)
                        .split(
                                Split.explicit(
                                        control.train(), control.validation(), control.test()))
                        .rewriter(randomRewriter)
                        .budget(OptimizerBudget.builder().maxRollouts(maxRollouts).build())
                        .targetValidationMean(0.95)
                        .parallelism(4)
                        .acknowledgeSideEffects()
                        .build()
                        .run();
        md.append(
                String.format(
                        Locale.ROOT,
                        "Random-edit rewriter: seed GT %.2f -> best GT %.2f, generalized=%s, stop=%s.%n%n",
                        groundTruth(system, seed, control.test()),
                        groundTruth(system, randomResult.best(), control.test()),
                        randomResult.generalized(),
                        randomResult.stopReason()));

        Path out = Path.of(outFile);
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, md.toString());
        System.out.println(md);
    }
}
