package io.github.llm4j.eval.integration;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.LLMClient;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.eval.criteria.Criteria;
import io.github.llm4j.eval.criteria.Criterion;
import io.github.llm4j.eval.dataset.EvalScenario;
import io.github.llm4j.eval.judge.JudgeVerdict;
import io.github.llm4j.eval.judge.LlmJudgeCondition;
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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Second efficacy study (verification plan §4, "more than one task"): three tasks with different
 * shapes, each starting from a deliberately weak prompt that does not state a hidden policy.
 *
 * <ul>
 *   <li><b>qa</b> - grounded question answering: answer with the shortest phrase, or {@code
 *       NOT_IN_CONTEXT} when the passage lacks the fact. Optimized against an LLM judge (J1).
 *   <li><b>extract</b> - field extraction with normalization rules (ISO date, integer cents, vendor
 *       without legal suffix). Optimized against a deterministic field-level scorer: no judge.
 *   <li><b>reply</b> - customer replies under a policy (apologize, promise no money, offer
 *       escalation, short). Optimized against a rubric judge (J1) with no ground-truth label.
 * </ul>
 *
 * Every task also reports a deterministic ground-truth measure computed independently of any judge,
 * and (for judged tasks) a score from a stronger judge (J2) that never took part in the loop.
 * Datasets are generated, synthetic and author-made. Opt-in via {@code EVAL4J_ANTHROPIC_API_KEY}.
 * Never fails on a threshold miss; the report is the deliverable.
 */
class PromptOptimizerMultiTaskIntegrationTest {

    // --- task model --------------------------------------------------------------------------

    private interface Task {
        String name();

        String seedPrompt();

        String description();

        List<EvalScenario> scenarios();

        /** Criteria the optimizer sees, built on the optimization judge. */
        List<Criterion> criteria(LLMClient j1);

        /** Independent, deterministic 0..1 measure of one output. */
        double groundTruth(EvalScenario scenario, String output);

        /** Score from a different, stronger judge; NaN when the task has no judge. */
        double strongJudge(EvalScenario scenario, String output, LLMClient j2);
    }

    // --- qa ----------------------------------------------------------------------------------

    private static final class QaTask implements Task {
        private static final String NONE = "NOT_IN_CONTEXT";
        private static final String[][] ENTITIES = {
            {"Zorvex", "1987", "Lisbon", "240", "irrigation pumps"},
            {"Kelmar", "1994", "Tallinn", "75", "ferry software"},
            {"Ostrava Labs", "2003", "Brno", "410", "water sensors"},
            {"Nimbus Foods", "1979", "Cork", "1,200", "frozen pastry"},
            {"Pelagic Works", "2011", "Bergen", "58", "tide turbines"},
            {"Harrow & Finch", "1968", "Leeds", "330", "rail signalling"},
            {"Quillon", "2015", "Porto", "36", "ebook readers"},
            {"Altamira Tech", "1999", "Seville", "520", "solar inverters"},
            {"Brightwell", "1985", "Cardiff", "190", "ceramic tiles"},
            {"Dovetail Studio", "2008", "Gdansk", "44", "furniture joints"},
            {"Ferrous Group", "1952", "Essen", "2,300", "steel beams"},
            {"Lumen Ridge", "2019", "Tromso", "27", "aurora cameras"},
            {"Marrow Bio", "2006", "Lyon", "145", "enzyme kits"},
            {"Northgate", "1991", "Aarhus", "88", "wind blades"},
            {"Olive Branch Co", "1974", "Patras", "610", "olive oil"},
            {"Pinewood Games", "2013", "Turku", "62", "board games"},
            {"Rhodium Labs", "2001", "Basel", "275", "catalyst sensors"},
            {"Saltmarsh", "1989", "Hull", "134", "fish packaging"},
            {"Tessellate", "2017", "Gent", "51", "floor robots"},
            {"Umber & Sons", "1948", "Graz", "97", "wooden toys"},
        };
        private static final String[] FACT_TEMPLATES = {
            "was founded in %s", "is based in %s", "employs %s people", "makes %s"
        };
        private static final String[] QUESTIONS = {
            "In what year was %s founded?",
            "In which city is %s based?",
            "How many people does %s employ?",
            "What does %s make?"
        };

        @Override
        public String name() {
            return "qa";
        }

        @Override
        public String seedPrompt() {
            return "Answer the question using the passage.";
        }

        @Override
        public String description() {
            return "System prompt of a model that answers a question about a short passage";
        }

        @Override
        public List<EvalScenario> scenarios() {
            Random random = new Random(5);
            List<EvalScenario> out = new ArrayList<>();
            for (String[] entity : ENTITIES) {
                int missing = random.nextInt(4);
                int asked = (missing + 1 + random.nextInt(3)) % 4; // a fact the passage includes
                StringBuilder passage = new StringBuilder(entity[0]).append(' ');
                boolean first = true;
                for (int fact = 0; fact < 4; fact++) {
                    if (fact == missing) {
                        continue;
                    }
                    passage.append(first ? "" : ", and it ")
                            .append(String.format(FACT_TEMPLATES[fact], entity[fact + 1]));
                    first = false;
                }
                passage.append('.');
                out.add(scenario(entity[0], passage, asked, entity[asked + 1]));
                out.add(scenario(entity[0], passage, missing, NONE));
            }
            return out;
        }

        private static EvalScenario scenario(
                String entity, CharSequence passage, int fact, String expected) {
            return new EvalScenario(
                    (expected.equals(NONE) ? "absent-" : "present-") + entity + "-" + fact,
                    "Passage: " + passage + "\nQuestion: " + String.format(QUESTIONS[fact], entity),
                    null,
                    expected,
                    null,
                    null,
                    null);
        }

        @Override
        public List<Criterion> criteria(LLMClient j1) {
            return List.of(
                    Criteria.perScenario(
                            "correctness",
                            sc ->
                                    Criteria.judged(
                                            LlmJudgePresets.using(j1)
                                                    .correctness(sc.expectedOutput()))),
                    Criteria.guardrail(
                            "short-answer",
                            out -> {
                                if (words(out) > 8) {
                                    throw new AssertionError(
                                            "answer must be a short phrase (at most 8 words), got "
                                                    + words(out));
                                }
                            }));
        }

        @Override
        public double groundTruth(EvalScenario scenario, String output) {
            String expected = scenario.expectedOutput();
            String normalized = normalize(output);
            if (expected.equals(NONE)) {
                return normalized.equals(normalize(NONE)) ? 1 : 0;
            }
            return normalized.contains(normalize(expected)) && words(output) <= 6 ? 1 : 0;
        }

        @Override
        public double strongJudge(EvalScenario scenario, String output, LLMClient j2) {
            return LlmJudgePresets.using(j2)
                    .correctness(scenario.expectedOutput())
                    .evaluate(output)
                    .score();
        }
    }

    // --- extract -----------------------------------------------------------------------------

    private static final class ExtractTask implements Task {
        private static final String[][] VENDORS = {
            {"Acme Tools Inc.", "Acme Tools"},
            {"Blue Harbor LLC", "Blue Harbor"},
            {"Nordwind GmbH", "Nordwind"},
            {"Kappa Ltd", "Kappa"},
            {"Silverline Inc.", "Silverline"},
            {"Marlow & Page LLC", "Marlow & Page"},
            {"Tundra Systems GmbH", "Tundra Systems"},
            {"Orchid Foods Ltd", "Orchid Foods"},
        };
        private static final String[] MONTHS = {
            "January",
            "February",
            "March",
            "April",
            "May",
            "June",
            "July",
            "August",
            "September",
            "October",
            "November",
            "December"
        };

        @Override
        public String name() {
            return "extract";
        }

        @Override
        public String seedPrompt() {
            return "Extract the vendor, date and total from the text as JSON.";
        }

        @Override
        public String description() {
            return "System prompt of a model that extracts vendor, date and total from invoice text as JSON";
        }

        @Override
        public List<EvalScenario> scenarios() {
            Random random = new Random(9);
            List<EvalScenario> out = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                String[] vendor = VENDORS[random.nextInt(VENDORS.length)];
                int year = 2022 + random.nextInt(3);
                int month = random.nextInt(12);
                int day = 1 + random.nextInt(28);
                long cents = 500 + random.nextInt(250_000);
                String date =
                        switch (i % 4) {
                            case 0 -> MONTHS[month] + " " + day + ", " + year;
                            case 1 -> day + " " + MONTHS[month] + " " + year;
                            case 2 -> MONTHS[month].substring(0, 3) + " " + day + " " + year;
                            default -> String.format(
                                    Locale.ROOT, "%d-%02d-%02d", year, month + 1, day);
                        };
                String amount =
                        switch (i % 3) {
                            case 0 -> String.format(
                                    Locale.ROOT, "$%,d.%02d", cents / 100, cents % 100);
                            case 1 -> String.format(
                                    Locale.ROOT, "EUR %d.%02d", cents / 100, cents % 100);
                            default -> String.format(
                                    Locale.ROOT,
                                    "%s EUR",
                                    String.format(
                                            Locale.GERMANY, "%,d,%02d", cents / 100, cents % 100));
                        };
                String text =
                        switch (i % 2) {
                            case 0 -> "Invoice from "
                                    + vendor[0]
                                    + " dated "
                                    + date
                                    + ". Total due: "
                                    + amount;
                            default -> "Dear customer, please find the bill issued by "
                                    + vendor[0]
                                    + " on "
                                    + date
                                    + ". Amount payable: "
                                    + amount
                                    + ". Thank you.";
                        };
                String expected =
                        vendor[1]
                                + "|"
                                + String.format(Locale.ROOT, "%d-%02d-%02d", year, month + 1, day)
                                + "|"
                                + cents;
                out.add(new EvalScenario("doc-" + i, text, null, expected, null, null, null));
            }
            return out;
        }

        @Override
        public List<Criterion> criteria(LLMClient j1) {
            return List.of(
                    Criteria.perScenario(
                            "fields",
                            sc ->
                                    Criteria.judged(
                                            "fields",
                                            output -> score(sc, String.valueOf(output)),
                                            0.99)));
        }

        private static JudgeVerdict score(EvalScenario scenario, String output) {
            String[] expected = scenario.expectedOutput().split("\\|");
            String[] keys = {"vendor", "date", "total_cents"};
            int right = 0;
            List<String> wrong = new ArrayList<>();
            for (int k = 0; k < keys.length; k++) {
                String got = field(output, keys[k]);
                if (expected[k].equals(got)) {
                    right++;
                } else {
                    wrong.add(keys[k] + ": expected " + expected[k] + " but got " + got);
                }
            }
            return new JudgeVerdict(
                    right / 3.0, wrong.isEmpty() ? "all fields correct" : String.join("; ", wrong));
        }

        private static String field(String output, String key) {
            Matcher m =
                    Pattern.compile("\"" + key + "\"\\s*:\\s*(?:\"([^\"]*)\"|(-?\\d+(?:\\.\\d+)?))")
                            .matcher(output);
            if (!m.find()) {
                return null;
            }
            return m.group(1) != null ? m.group(1) : m.group(2);
        }

        @Override
        public double groundTruth(EvalScenario scenario, String output) {
            return score(scenario, output).score() == 1.0 ? 1 : 0; // all three fields exact
        }

        @Override
        public double strongJudge(EvalScenario scenario, String output, LLMClient j2) {
            return Double.NaN;
        }
    }

    // --- reply -------------------------------------------------------------------------------

    private static final class ReplyTask implements Task {
        private static final String POLICY =
                "The reply apologizes sincerely, does NOT promise or hint at a refund, credit or"
                        + " compensation, offers to escalate the issue to a human agent, and is no"
                        + " longer than 80 words.";
        private static final String[] ISSUES = {
            "my parcel arrived two weeks late",
            "the app deleted my saved projects",
            "I was billed twice this month",
            "support never answered my last three emails",
            "the product stopped working after one day",
            "my subscription renewed after I cancelled it",
        };
        private static final String[] TONES = {
            "I'm really upset:",
            "Hello, quick question:",
            "This is unacceptable!",
            "Not sure who to ask, but",
            "I want my money back because",
            "Please help,",
            "Just letting you know that",
        };

        @Override
        public String name() {
            return "reply";
        }

        @Override
        public String seedPrompt() {
            return "You are a customer support agent. Reply to the customer's message.";
        }

        @Override
        public String description() {
            return "System prompt of a customer-support model that replies to complaints";
        }

        @Override
        public List<EvalScenario> scenarios() {
            Random random = new Random(3);
            List<EvalScenario> out = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                String issue = ISSUES[i % ISSUES.length];
                String tone = TONES[random.nextInt(TONES.length)];
                out.add(
                        new EvalScenario(
                                "complaint-" + i,
                                tone + " " + issue + ".",
                                null,
                                null,
                                null,
                                null,
                                null));
            }
            return out;
        }

        @Override
        public List<Criterion> criteria(LLMClient j1) {
            return List.of(
                    Criteria.judged(
                            LlmJudgeCondition.llmJudged("reply-policy")
                                    .criteria(POLICY)
                                    .judge(j1)
                                    .threshold(0.8)
                                    .build()),
                    Criteria.guardrail(
                            "not-an-essay",
                            out -> {
                                if (words(out) > 120) {
                                    throw new AssertionError(
                                            "reply must stay short, got " + words(out) + " words");
                                }
                            }));
        }

        @Override
        public double groundTruth(EvalScenario scenario, String output) {
            String text = output.toLowerCase(Locale.ROOT);
            boolean apologizes = text.contains("sorry") || text.contains("apolog");
            boolean escalates = text.contains("escalat");
            boolean noMoney =
                    !Pattern.compile("refund|compensat|credit|reimburs").matcher(text).find();
            return apologizes && escalates && noMoney && words(output) <= 80 ? 1 : 0;
        }

        @Override
        public double strongJudge(EvalScenario scenario, String output, LLMClient j2) {
            return LlmJudgeCondition.llmJudged("reply-policy")
                    .criteria(POLICY)
                    .judge(j2)
                    .threshold(0.8)
                    .build()
                    .evaluate(output)
                    .score();
        }
    }

    // --- helpers -----------------------------------------------------------------------------

    private static int words(Object output) {
        String text = String.valueOf(output).trim();
        return text.isEmpty() ? 0 : text.split("\\s+").length;
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
    }

    private static LLMClient client(String key, String model) {
        return new DefaultLLMClient(
                new AnthropicProvider(LLMConfig.builder().apiKey(key).defaultModel(model).build()));
    }

    private static SystemUnderTest system(LLMClient model) {
        return (candidate, scenario) ->
                model.chat(
                                LLMRequest.builder()
                                        .addSystemMessage(candidate.get("system-prompt"))
                                        .addUserMessage(scenario.input())
                                        .temperature(0.0)
                                        .build())
                        .getContent();
    }

    private static double mean(
            Task task, SystemUnderTest system, Candidate candidate, List<EvalScenario> scenarios) {
        return scenarios.stream()
                .mapToDouble(s -> task.groundTruth(s, String.valueOf(system.run(candidate, s))))
                .average()
                .orElse(0);
    }

    private static double strong(
            Task task,
            SystemUnderTest system,
            Candidate candidate,
            List<EvalScenario> scenarios,
            LLMClient j2) {
        return scenarios.stream()
                .mapToDouble(s -> task.strongJudge(s, String.valueOf(system.run(candidate, s)), j2))
                .average()
                .orElse(Double.NaN);
    }

    // --- the study ---------------------------------------------------------------------------

    @Test
    void runMultiTaskStudy() throws Exception {
        String key = System.getenv("EVAL4J_ANTHROPIC_API_KEY");
        assumeTrue(key != null && !key.isBlank(), "EVAL4J_ANTHROPIC_API_KEY not set");
        var env = System.getenv();
        List<String> wanted =
                Arrays.asList(
                        env.getOrDefault("EVAL4J_STUDY_TASKS", "extract,qa,reply").split(","));
        int seeds = Integer.parseInt(env.getOrDefault("EVAL4J_OPTIMIZER_SEEDS", "3"));
        int maxRollouts = Integer.parseInt(env.getOrDefault("EVAL4J_OPTIMIZER_ROLLOUTS", "400"));
        long maxCalls = Long.parseLong(env.getOrDefault("EVAL4J_STUDY_MAX_LLM_CALLS", "3000"));
        Path out = Path.of(env.getOrDefault("EVAL4J_STUDY_OUT", "target/optimizer-multitask.md"));

        LLMClient systemModel =
                client(key, env.getOrDefault("EVAL4J_SYSTEM_MODEL", "claude-haiku-4-5-20251001"));
        LLMClient j1 =
                client(key, env.getOrDefault("EVAL4J_J1_MODEL", "claude-haiku-4-5-20251001"));
        LLMClient rewriter =
                client(key, env.getOrDefault("EVAL4J_REWRITER_MODEL", "claude-sonnet-5-5"));
        LLMClient j2 = client(key, env.getOrDefault("EVAL4J_J2_MODEL", "claude-opus-5-5"));
        SystemUnderTest system = system(systemModel);

        StringBuilder md = new StringBuilder("# Prompt optimizer - multi-task efficacy study\n\n");
        md.append(
                "_Three synthetic, author-made tasks; each starts from a weak seed prompt that hides"
                        + " a policy. GT = deterministic ground truth computed outside any judge;"
                        + " J1 = optimization judge's test mean; J2 = stronger independent judge"
                        + " (judged tasks only). Budget per run: "
                        + maxRollouts
                        + " rollouts, "
                        + maxCalls
                        + " LLM calls._\n\n");

        for (Task task : List.of(new ExtractTask(), new QaTask(), new ReplyTask())) {
            if (!wanted.contains(task.name())) {
                continue;
            }
            List<EvalScenario> all = task.scenarios();
            md.append("## ")
                    .append(task.name())
                    .append("\n\nSeed prompt: `")
                    .append(task.seedPrompt())
                    .append("`; ")
                    .append(all.size())
                    .append(" scenarios.\n\n");
            md.append(
                    "| seed | GT seed | GT best | J1 seed | J1 best | J2 seed | J2 best | generalized | stop | rounds | rollouts | LLM calls |\n"
                            + "|---|---|---|---|---|---|---|---|---|---|---|---|\n");
            for (int s = 1; s <= seeds; s++) {
                LlmCallCounter j1Counter = LlmCallCounter.wrap(j1);
                LlmCallCounter systemCounter = LlmCallCounter.wrap(systemModel);
                var split = Split.ratios(0.5, 0.3, 0.2).seed(s);
                OptimizationResult result =
                        PromptOptimizer.builder()
                                .seed(Candidate.of("system-prompt", task.seedPrompt()))
                                .system(system(systemCounter))
                                .criteria(task.criteria(j1Counter))
                                .scenarios(all)
                                .split(split)
                                .rewriter(rewriter)
                                .judge(j1)
                                .parameterDescription("system-prompt", task.description())
                                .constraints(PromptConstraints.builder().maxChars(2500).build())
                                .budget(
                                        OptimizerBudget.builder()
                                                .maxRollouts(maxRollouts)
                                                .maxLlmCalls(maxCalls)
                                                .build())
                                .targetValidationMean(0.95)
                                .parallelism(4)
                                .trackCalls(j1Counter, systemCounter)
                                .randomSeed(s)
                                .acknowledgeSideEffects()
                                .build()
                                .run();
                List<EvalScenario> test = split.apply(all).test();
                Candidate seedCandidate = result.seed();
                Candidate best = result.best();
                md.append(
                        String.format(
                                Locale.ROOT,
                                "| %d | %.2f | %.2f | %.2f | %.2f | %s | %s | %s | %s | %d | %d | %d |%n",
                                s,
                                mean(task, system, seedCandidate, test),
                                mean(task, system, best, test),
                                result.seedScores().testMean(),
                                result.bestScores().testMean(),
                                fmt(strong(task, system, seedCandidate, test, j2)),
                                fmt(strong(task, system, best, test, j2)),
                                result.generalized(),
                                result.stopReason(),
                                result.trace().size(),
                                result.cost().rollouts(),
                                result.cost().trackedLlmCalls() + result.cost().rewriterCalls()));
                md.append("\n<details><summary>")
                        .append(task.name())
                        .append(" seed ")
                        .append(s)
                        .append(" best prompt and verdict</summary>\n\n```\n")
                        .append(best.get("system-prompt"))
                        .append("\n```\n\n")
                        .append(String.join("; ", result.verdict().reasons()))
                        .append("\n\n</details>\n\n");
                Files.createDirectories(out.toAbsolutePath().getParent());
                Files.writeString(out, md.toString()); // keep partial results if a later run fails
            }
        }
        System.out.println(md);
    }

    private static String fmt(double value) {
        return Double.isNaN(value) ? "n/a" : String.format(Locale.ROOT, "%.2f", value);
    }
}
