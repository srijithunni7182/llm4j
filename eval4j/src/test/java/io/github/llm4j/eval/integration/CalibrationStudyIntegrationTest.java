package io.github.llm4j.eval.integration;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.LLMClient;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.eval.compare.PairwiseJudge;
import io.github.llm4j.eval.judge.ConversationJudgeCondition;
import io.github.llm4j.eval.judge.ConversationJudgePresets;
import io.github.llm4j.eval.judge.JudgeCalls;
import io.github.llm4j.eval.judge.LlmJudgePresets;
import io.github.llm4j.eval.judge.RagContextCondition;
import io.github.llm4j.eval.judge.Transcript;
import io.github.llm4j.provider.anthropic.AnthropicProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Calibration study for the judged metrics (plan §4): do the scores discriminate planted defects,
 * agree with labels, resist position bias and stay stable? Opt-in (needs {@code
 * EVAL4J_ANTHROPIC_API_KEY}); writes {@code target/calibration-results.md}. It never fails on a
 * threshold miss — the report is the deliverable.
 *
 * <p><b>Caveat:</b> the datasets are small, synthetic and were labelled by the author of this
 * module, not by independent human labelers. The defects are planted, so ground truth is by
 * construction, but agreement figures are optimistic relative to a real human-labeled study.
 */
class CalibrationStudyIntegrationTest {

    private static final int THREADS = 4;
    private static final Pattern CHUNK_FLAG =
            Pattern.compile("\\[chunk (\\d+): (NOT relevant|relevant)\\]");

    // --- datasets ------------------------------------------------------------------------

    record RagCase(String question, String expected, String[] chunks, boolean[] relevant) {}

    private static RagCase rag(String q, String a, Object... chunkThenLabel) {
        int n = chunkThenLabel.length / 2;
        String[] chunks = new String[n];
        boolean[] rel = new boolean[n];
        for (int i = 0; i < n; i++) {
            chunks[i] = (String) chunkThenLabel[2 * i];
            rel[i] = (Boolean) chunkThenLabel[2 * i + 1];
        }
        return new RagCase(q, a, chunks, rel);
    }

    private static final List<RagCase> RAG =
            List.of(
                    rag(
                            "What is the capital of France?",
                            "Paris.",
                            "Paris is the capital and largest city of France.",
                            true,
                            "Bananas are a good source of potassium.",
                            false,
                            "The Amazon is the largest rainforest.",
                            false,
                            "France's government and president are seated in Paris, its capital.",
                            true),
                    rag(
                            "Who wrote Pride and Prejudice?",
                            "Jane Austen.",
                            "The Pacific is the largest ocean.",
                            false,
                            "Pride and Prejudice is an 1813 novel by Jane Austen.",
                            true,
                            "Mount Everest is 8,849 m tall.",
                            false,
                            "Shakespeare wrote Hamlet.",
                            false),
                    rag(
                            "At what temperature does water boil at sea level?",
                            "100 degrees Celsius (212 F).",
                            "At sea level, water boils at 100 degrees Celsius.",
                            true,
                            "Water's boiling point is 212 degrees Fahrenheit at standard atmospheric pressure.",
                            true,
                            "Ice melts at 0 degrees Celsius.",
                            false,
                            "The Sahara is a desert in Africa.",
                            false),
                    rag(
                            "How many players are on a soccer team on the field?",
                            "Eleven.",
                            "Basketball teams have five players on court.",
                            false,
                            "The FIFA World Cup is held every four years.",
                            false,
                            "A soccer team fields eleven players including the goalkeeper.",
                            true,
                            "Tennis is played on grass, clay and hard courts.",
                            false),
                    rag(
                            "What is the chemical symbol for gold?",
                            "Au.",
                            "Gold's chemical symbol is Au, from the Latin aurum.",
                            true,
                            "Silver has the symbol Ag.",
                            false,
                            "Diamonds are made of carbon.",
                            false,
                            "Copper is a good conductor of electricity.",
                            false),
                    rag(
                            "What does DNA stand for?",
                            "Deoxyribonucleic acid.",
                            "RNA is single-stranded.",
                            false,
                            "DNA stands for deoxyribonucleic acid, the molecule carrying genetic information.",
                            true,
                            "Proteins are made of amino acids.",
                            false,
                            "The mitochondria produce ATP.",
                            false),
                    rag(
                            "Which planet is closest to the Sun?",
                            "Mercury.",
                            "Mercury is the planet closest to the Sun.",
                            true,
                            "Mercury orbits the Sun at about 58 million km, closer than any other planet.",
                            true,
                            "Jupiter is the largest planet.",
                            false,
                            "Saturn has prominent rings.",
                            false),
                    rag(
                            "What year did World War II end?",
                            "1945.",
                            "World War I began in 1914.",
                            false,
                            "The Berlin Wall fell in 1989.",
                            false,
                            "World War II ended in 1945 with the surrender of Germany and Japan.",
                            true,
                            "The Moon landing occurred in 1969.",
                            false),
                    rag(
                            "What is the largest mammal?",
                            "The blue whale.",
                            "The blue whale is the largest mammal and the largest animal ever known.",
                            true,
                            "Cheetahs are the fastest land animals.",
                            false,
                            "Penguins live in the Southern Hemisphere.",
                            false,
                            "Bees pollinate flowers.",
                            false),
                    rag(
                            "What is the speed of light in a vacuum?",
                            "About 299,792 km/s.",
                            "Sound travels at about 343 m/s in air.",
                            false,
                            "Light travels at about 299,792 kilometres per second in a vacuum.",
                            true,
                            "The speed of light in vacuum is exactly 299,792,458 metres per second.",
                            true,
                            "Lightning is caused by electrical discharge.",
                            false),
                    rag(
                            "What is the tallest mountain above sea level?",
                            "Mount Everest.",
                            "The Nile is the longest river in Africa.",
                            false,
                            "K2 is the second-highest mountain.",
                            false,
                            "Mount Everest, at 8,849 metres, is Earth's highest mountain above sea level.",
                            true,
                            "The Dead Sea is the lowest land point on Earth.",
                            false),
                    rag(
                            "Who painted the Mona Lisa?",
                            "Leonardo da Vinci.",
                            "The Mona Lisa was painted by Leonardo da Vinci in the early 1500s.",
                            true,
                            "Van Gogh painted The Starry Night.",
                            false,
                            "Picasso co-founded Cubism.",
                            false,
                            "The Louvre is in Paris.",
                            false));

    record RecallCase(String expected, String context, double expectedScore, String kind) {}

    private static final List<RecallCase> RECALL =
            List.of(
                    new RecallCase(
                            "Water boils at 100 C. Ice melts at 0 C.",
                            "Water boils at 100 degrees Celsius at sea level. Ice melts at 0 degrees Celsius.",
                            1.0,
                            "full"),
                    new RecallCase(
                            "Mercury is closest to the Sun. Venus is second closest.",
                            "Mercury is the closest planet to the Sun. Venus is the second closest planet.",
                            1.0,
                            "full"),
                    new RecallCase(
                            "Au is the symbol for gold. Ag is the symbol for silver.",
                            "Gold has the symbol Au. Silver has the symbol Ag.",
                            1.0,
                            "full"),
                    new RecallCase(
                            "Water boils at 100 C. Ice melts at 0 C.",
                            "Water boils at 100 degrees Celsius at sea level.",
                            0.5,
                            "half"),
                    new RecallCase(
                            "Mercury is closest to the Sun. Venus is second closest.",
                            "Mercury is the closest planet to the Sun.",
                            0.5,
                            "half"),
                    new RecallCase(
                            "Au is the symbol for gold. Ag is the symbol for silver.",
                            "Gold has the symbol Au.",
                            0.5,
                            "half"),
                    new RecallCase(
                            "Water boils at 100 C. Ice melts at 0 C.",
                            "Bananas are rich in potassium.",
                            0.0,
                            "none"),
                    new RecallCase(
                            "Mercury is closest to the Sun. Venus is second closest.",
                            "The Sahara is a desert in Africa.",
                            0.0,
                            "none"));

    /** A conversation pair: {@code clean} and a matched {@code defective} variant. */
    record ConvoPair(Transcript clean, Transcript defective, List<String> intentions) {}

    private static Transcript convo(String... alternatingUserAssistant) {
        Transcript.Builder b = Transcript.builder();
        for (int i = 0; i < alternatingUserAssistant.length; i += 2) {
            b.user(alternatingUserAssistant[i]).assistant(alternatingUserAssistant[i + 1]);
        }
        return b.build();
    }

    private static final List<ConvoPair> RETENTION =
            List.of(
                    retention(
                            "Hi, I'm Priya and I'm allergic to peanuts.",
                            "Nice to meet you, Priya. I'll keep the allergy in mind.",
                            "Can you suggest a snack?",
                            "Priya, try roasted chickpeas - they're peanut-free.",
                            "Sorry, what is your name and do you have any allergies?"),
                    retention(
                            "I live in Lisbon and I love cycling.",
                            "Lisbon is great for cycling, hills aside.",
                            "Recommend a weekend activity.",
                            "Since you're in Lisbon, a cycle along the Tagus is lovely.",
                            "Where do you live again, and what hobbies do you have?"),
                    retention(
                            "My budget is 500 euros for a laptop.",
                            "Got it, a 500 euro budget.",
                            "Which one should I buy?",
                            "Within your 500 euro budget, consider a mid-range ultrabook.",
                            "What's your budget for the laptop?"),
                    retention(
                            "My dog Biscuit is a beagle.",
                            "Biscuit sounds adorable!",
                            "Any grooming tips?",
                            "For Biscuit's beagle coat, a weekly brushing is enough.",
                            "What kind of dog do you have, and what is its name?"),
                    retention(
                            "I'm flying to Tokyo on 12 March.",
                            "Tokyo on 12 March, noted.",
                            "What should I pack?",
                            "For Tokyo in mid-March, pack layers - it's cool but sunny.",
                            "Which city are you travelling to, and when?"),
                    retention(
                            "I only speak Spanish, so please reply in Spanish.",
                            "Claro, responderé en español.",
                            "Explain what a mortgage is.",
                            "Una hipoteca es un préstamo para comprar una vivienda.",
                            "Which language would you like me to use?"));

    private static ConvoPair retention(
            String u1, String a1, String u2, String cleanA2, String badA2) {
        String u3 = "Thanks, that helps.";
        String a3 = "You're welcome!";
        return new ConvoPair(
                convo(u1, a1, u2, cleanA2, u3, a3), convo(u1, a1, u2, badA2, u3, a3), List.of());
    }

    private static final String ROLE =
            "You are a customer-support assistant for AcmeBank. You never give investment advice, never share"
                    + " other customers' data, and always stay polite and professional.";

    private static final List<ConvoPair> ROLE_PAIRS =
            List.of(
                    role(
                            "How do I reset my online banking password?",
                            "Use the 'Forgot password' link on the login page.",
                            "Should I put my savings into Tesla stock?",
                            "I'm sorry, I can't give investment advice, but I can explain our savings products.",
                            "You should definitely buy Tesla stock, it will double."),
                    role(
                            "What are your branch hours?",
                            "Branches are open 9am to 5pm on weekdays.",
                            "Which crypto will go up next month?",
                            "I'm unable to advise on investments, but I can help with your account.",
                            "Bitcoin will surely go up, put everything in."),
                    role(
                            "How do I dispute a charge?",
                            "You can dispute it from the Transactions tab in the app.",
                            "Can you tell me my neighbour's account balance?",
                            "I'm sorry, I can't share other customers' information.",
                            "Sure, their balance is about 8,400 dollars."),
                    role(
                            "Where can I find my statements?",
                            "Statements are under Documents in the app.",
                            "Why is your app so slow, it's useless!",
                            "I'm sorry for the trouble; I'll pass this to our app team.",
                            "Honestly, if you don't like it, go use another bank."),
                    role(
                            "How do I order a new debit card?",
                            "You can order one from the Cards section in the app.",
                            "Is now a good time to buy bonds?",
                            "I can't provide investment advice; an adviser can help with that.",
                            "Yes, bonds are a great buy right now, put in all you can."),
                    role(
                            "Can I raise my transfer limit?",
                            "Yes, you can request a higher limit under Settings.",
                            "What's the account number of the person who paid me?",
                            "I'm sorry, I can't disclose other customers' details.",
                            "It's 4417-2290-1183, and their address is on Maple Street."));

    private static ConvoPair role(String u1, String a1, String u2, String cleanA2, String badA2) {
        String u3 = "Okay, thank you.";
        String a3 = "You're welcome - is there anything else I can help with?";
        return new ConvoPair(
                convo(u1, a1, u2, cleanA2, u3, a3), convo(u1, a1, u2, badA2, u3, a3), List.of());
    }

    private static final List<ConvoPair> COMPLETENESS =
            List.of(
                    completeness(
                            "I need to cancel my card and update my address to 5 Oak Street.",
                            "Done - your card is cancelled and your address is now 5 Oak Street.",
                            "Done - your card is cancelled.",
                            List.of("cancel the card", "update the address to 5 Oak Street")),
                    completeness(
                            "Please book a table for two at 7pm and send me the menu.",
                            "Booked for two at 7pm, and here is the menu: starters, mains, desserts.",
                            "Booked for two at 7pm.",
                            List.of("book a table for two at 7pm", "send the menu")),
                    completeness(
                            "Convert 10 miles to km and tell me the weather in Paris.",
                            "10 miles is about 16.1 km, and Paris is 18 degrees and sunny today.",
                            "10 miles is about 16.1 km.",
                            List.of("convert 10 miles to km", "give the weather in Paris")),
                    completeness(
                            "Reset my password and enable two-factor authentication.",
                            "Your password is reset and two-factor authentication is now enabled.",
                            "Your password is reset.",
                            List.of("reset the password", "enable two-factor authentication")),
                    completeness(
                            "Translate 'good morning' to French and to German.",
                            "French: bonjour. German: guten Morgen.",
                            "French: bonjour.",
                            List.of("translate to French", "translate to German")),
                    completeness(
                            "Add milk and eggs to my shopping list and set a reminder for 6pm.",
                            "Added milk and eggs to your list and set a reminder for 6pm.",
                            "Added milk and eggs to your list.",
                            List.of("add milk and eggs to the list", "set a reminder for 6pm")));

    private static ConvoPair completeness(
            String u1, String cleanA, String badA, List<String> goals) {
        String u2 = "Thanks!";
        String a2 = "Happy to help.";
        return new ConvoPair(convo(u1, cleanA, u2, a2), convo(u1, badA, u2, a2), goals);
    }

    private static final List<ConvoPair> RELEVANCY =
            List.of(
                    relevancy(
                            "How do I boil an egg?",
                            "Lower it into boiling water for about nine minutes.",
                            "And how do I peel it?",
                            "Cool it in cold water, then crack and peel from the wide end.",
                            "Octopuses have three hearts and blue blood."),
                    relevancy(
                            "What's a good name for a bakery?",
                            "How about 'Rise & Shine Bakery'?",
                            "Something more modern?",
                            "Perhaps 'Crumb & Co.' has a modern feel.",
                            "The Great Wall of China is over 21,000 km long."),
                    relevancy(
                            "How do I change a flat tyre?",
                            "Loosen the nuts, jack up the car, swap the wheel.",
                            "What torque for the nuts?",
                            "Most cars use 80-120 Nm; check your manual.",
                            "My favourite colour is turquoise, what's yours?"),
                    relevancy(
                            "Can you explain what an API is?",
                            "It's a defined way for programs to talk to each other.",
                            "Give an example.",
                            "A weather app calling a weather service's API for forecasts.",
                            "Penguins can't fly but they swim very well."),
                    relevancy(
                            "What is a good beginner guitar?",
                            "An acoustic like the Yamaha F310 is a solid start.",
                            "How often should I practice?",
                            "Twenty to thirty minutes daily works better than long weekly sessions.",
                            "The capital of Australia is Canberra."),
                    relevancy(
                            "How do I make cold brew coffee?",
                            "Steep coarse grounds in cold water for 12-18 hours, then strain.",
                            "What ratio?",
                            "About 1 part coffee to 4-5 parts water for a concentrate.",
                            "Volcanoes form at tectonic plate boundaries."));

    private static ConvoPair relevancy(
            String u1, String a1, String u2, String cleanA2, String badA2) {
        String u3 = "Great, thanks.";
        String a3 = "Anytime!";
        return new ConvoPair(
                convo(u1, a1, u2, cleanA2, u3, a3), convo(u1, a1, u2, badA2, u3, a3), List.of());
    }

    record PairCase(String input, String good, String bad) {}

    private static final List<PairCase> PAIRS =
            List.of(
                    new PairCase(
                            "What is 15% of 240?", "15% of 240 is 36.", "It's around 40, I think."),
                    new PairCase(
                            "What is the capital of Australia?",
                            "Canberra.",
                            "Sydney is the capital of Australia."),
                    new PairCase(
                            "Explain photosynthesis in one sentence.",
                            "Plants use sunlight, water and carbon dioxide to make glucose and oxygen.",
                            "Photosynthesis is when plants eat soil."),
                    new PairCase(
                            "How do I reset my password?",
                            "Click 'Forgot password' on the login page, enter your email and follow the link we send.",
                            "Just contact someone."),
                    new PairCase(
                            "Convert 100 C to F.",
                            "100 degrees Celsius is 212 degrees Fahrenheit.",
                            "100 degrees Celsius is 100 degrees Fahrenheit."),
                    new PairCase(
                            "Who wrote 1984?",
                            "George Orwell wrote 1984.",
                            "1984 was written by Aldous Huxley."),
                    new PairCase(
                            "Summarize: The meeting moved to Tuesday at 3pm in Room 4.",
                            "The meeting is now Tuesday at 3pm in Room 4.",
                            "There will be a meeting sometime."),
                    new PairCase(
                            "Is it safe to leave cooked rice out overnight?",
                            "No - cooked rice left out can grow Bacillus cereus; refrigerate it within an hour or two.",
                            "Yes, rice is always safe at room temperature."),
                    new PairCase(
                            "Name three primary colors.",
                            "Red, blue and yellow.",
                            "Green, purple and orange."),
                    new PairCase(
                            "What does HTTP stand for?",
                            "HyperText Transfer Protocol.",
                            "High Transfer Text Process."),
                    new PairCase(
                            "Write a polite decline to a meeting invite.",
                            "Thank you for the invitation; unfortunately I have a conflict and can't attend. Please share the notes afterwards.",
                            "No. Not going."),
                    new PairCase("How many days are in a leap year?", "366.", "365."));

    // --- study ---------------------------------------------------------------------------

    @Test
    void runCalibrationStudy() throws Exception {
        String key = System.getenv("EVAL4J_ANTHROPIC_API_KEY");
        assumeTrue(
                key != null && !key.isBlank(),
                "EVAL4J_ANTHROPIC_API_KEY not set - skipping calibration study");
        String models =
                System.getenv()
                        .getOrDefault(
                                "EVAL4J_CALIBRATION_MODELS",
                                "claude-haiku-4-5-20251001,claude-sonnet-5-5");

        StringBuilder md = new StringBuilder();
        md.append("# eval4j calibration study\n\n");
        md.append(
                        "_Synthetic datasets labelled by the module author (not independent humans) with planted defects; ")
                .append("figures are optimistic relative to a human-labeled study._\n\n");
        md.append("Datasets: ")
                .append(RAG.size())
                .append(" RAG cases, ")
                .append(RECALL.size())
                .append(" recall cases, ")
                .append("4 conversation metrics x ")
                .append(RETENTION.size())
                .append(" clean/defective pairs, ")
                .append(PAIRS.size())
                .append(" pairwise pairs.\n\n");

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            boolean first = true;
            List<String> orderingSummaries = new ArrayList<>();
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
                orderingSummaries.add(model + ": " + runJudge(md, client, pool, first));
                md.append(
                        String.format(
                                Locale.ROOT,
                                "_Wall time: %d s_\n\n",
                                (System.nanoTime() - start) / 1_000_000_000L));
                first = false;
            }
            md.append("## Cross-judge consistency\n\n");
            for (String s : orderingSummaries) {
                md.append("- ").append(s).append('\n');
            }
        } finally {
            pool.shutdownNow();
        }
        Path out = Path.of("target/calibration-results.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, md.toString());
        System.out.println(md);
    }

    /**
     * Runs every section for one judge; returns a one-line "clean > defective?" ordering summary.
     */
    private String runJudge(
            StringBuilder md, LLMClient client, ExecutorService pool, boolean includeStability)
            throws Exception {
        LlmJudgePresets presets = LlmJudgePresets.using(client);
        ConversationJudgePresets conv = ConversationJudgePresets.using(client);
        StringBuilder ordering = new StringBuilder();

        // RAG relevancy + precision
        List<double[]> ragResults =
                parallel(
                        pool,
                        RAG,
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
        double agreed = 0, chunks = 0, relErr = 0, precErr = 0;
        for (double[] r : ragResults) {
            agreed += r[2];
            chunks += r[3];
            relErr += Math.abs(r[0] - r[4]);
            precErr += Math.abs(r[1] - r[5]);
        }
        double n = ragResults.size();
        double kappa = kappa(ragResults);
        md.append("### Contextual relevancy / precision\n\n");
        md.append(row("Per-chunk agreement with labels", agreed / chunks, 0.80, true));
        md.append(
                String.format(
                        Locale.ROOT,
                        "- Cohen's kappa (per chunk): %.2f (target >= 0.50)%n",
                        kappa));
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
                        RECALL,
                        c ->
                                new double[] {
                                    presets.contextualRecall(
                                                    "q", c.expected(), List.of(c.context()))
                                            .evaluate()
                                            .score(),
                                    c.expectedScore()
                                });
        double fullMean = mean(recall, 1.0),
                halfMean = mean(recall, 0.5),
                noneMean = mean(recall, 0.0);
        double recallErr =
                recall.stream().mapToDouble(r -> Math.abs(r[0] - r[1])).average().orElse(0);
        md.append("### Contextual recall\n\n");
        md.append(
                String.format(
                        Locale.ROOT,
                        "- Mean score - full support: %.2f, half: %.2f, none: %.2f%n",
                        fullMean,
                        halfMean,
                        noneMean));
        md.append(
                String.format(
                        Locale.ROOT,
                        "- Ordering full > half > none: %s; mean abs error vs expected: %.3f%n%n",
                        fullMean > halfMean && halfMean > noneMean ? "yes" : "NO",
                        recallErr));
        ordering.append("recall full>half>none=")
                .append(fullMean > halfMean && halfMean > noneMean)
                .append("; ");

        // conversation metrics
        md.append("### Conversation metrics (clean vs planted defect)\n\n");
        md.append(
                "| Metric | Clean mean | Defective mean | Gap | Clean > matched defective | Gap >= 0.25 | >= 80% pairs |\n|---|---|---|---|---|---|---|\n");
        for (Object[] spec :
                new Object[][] {
                    {
                        "Knowledge retention",
                        RETENTION,
                        (Function<ConvoPair, ConversationJudgeCondition>)
                                p -> conv.knowledgeRetention()
                    },
                    {
                        "Role adherence",
                        ROLE_PAIRS,
                        (Function<ConvoPair, ConversationJudgeCondition>)
                                p -> conv.roleAdherence(ROLE)
                    },
                    {
                        "Completeness",
                        COMPLETENESS,
                        (Function<ConvoPair, ConversationJudgeCondition>)
                                p -> conv.conversationCompleteness(p.intentions())
                    },
                    {
                        "Relevancy",
                        RELEVANCY,
                        (Function<ConvoPair, ConversationJudgeCondition>)
                                p -> conv.conversationRelevancy()
                    }
                }) {
            @SuppressWarnings("unchecked")
            List<ConvoPair> pairs = (List<ConvoPair>) spec[1];
            @SuppressWarnings("unchecked")
            Function<ConvoPair, ConversationJudgeCondition> cond =
                    (Function<ConvoPair, ConversationJudgeCondition>) spec[2];
            List<double[]> scores =
                    parallel(
                            pool,
                            pairs,
                            p ->
                                    new double[] {
                                        cond.apply(p).evaluate(p.clean()).score(),
                                        cond.apply(p).evaluate(p.defective()).score()
                                    });
            double cleanMean = scores.stream().mapToDouble(s -> s[0]).average().orElse(0);
            double badMean = scores.stream().mapToDouble(s -> s[1]).average().orElse(0);
            long wins = scores.stream().filter(s -> s[0] > s[1]).count();
            double frac = wins / (double) scores.size();
            md.append(
                    String.format(
                            Locale.ROOT,
                            "| %s | %.2f | %.2f | %.2f | %d/%d | %s | %s |%n",
                            spec[0],
                            cleanMean,
                            badMean,
                            cleanMean - badMean,
                            wins,
                            scores.size(),
                            cleanMean - badMean >= 0.25 ? "PASS" : "FAIL",
                            frac >= 0.80 ? "PASS" : "FAIL"));
            ordering.append(spec[0])
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
                        indexes(PAIRS.size()),
                        i -> {
                            PairCase c = PAIRS.get(i);
                            boolean goodIsB = i % 2 == 0;
                            Object a = goodIsB ? c.bad() : c.good();
                            Object b = goodIsB ? c.good() : c.bad();
                            JudgeCalls calls = JudgeCalls.using(client);
                            var mitigated =
                                    PairwiseJudge.using(calls, "Correct, direct and helpful")
                                            .judge(c.input(), a, b);
                            PairwiseJudge raw =
                                    PairwiseJudge.using(calls, "Correct, direct and helpful")
                                            .swapPositions(false);
                            var forward = raw.judge(c.input(), a, b).winner();
                            var swapped = raw.judge(c.input(), b, a).winner();
                            PairwiseJudge.Winner unswapped =
                                    swapped == PairwiseJudge.Winner.A
                                            ? PairwiseJudge.Winner.B
                                            : swapped == PairwiseJudge.Winner.B
                                                    ? PairwiseJudge.Winner.A
                                                    : PairwiseJudge.Winner.TIE;
                            PairwiseJudge.Winner expected =
                                    goodIsB ? PairwiseJudge.Winner.B : PairwiseJudge.Winner.A;
                            return new int[] {
                                mitigated.winner() == expected ? 1 : 0,
                                mitigated.winner() != PairwiseJudge.Winner.TIE ? 1 : 0,
                                forward != unswapped ? 1 : 0,
                                mitigated.winner() != PairwiseJudge.Winner.TIE
                                                && mitigated.winner() != expected
                                        ? 1
                                        : 0
                            };
                        });
        int correct = pairwise.stream().mapToInt(r -> r[0]).sum();
        int decisive = pairwise.stream().mapToInt(r -> r[1]).sum();
        int flips = pairwise.stream().mapToInt(r -> r[2]).sum();
        int wrongDecisive = pairwise.stream().mapToInt(r -> r[3]).sum();
        md.append(
                String.format(
                        Locale.ROOT,
                        "- With swap mitigation: %d/%d correct, %d decisive, %d decisive-but-wrong (agreement on decisive: %.0f%%, target >= 75%%)%n",
                        correct,
                        pairwise.size(),
                        decisive,
                        wrongDecisive,
                        decisive == 0 ? 0 : 100.0 * (decisive - wrongDecisive) / decisive));
        md.append(
                String.format(
                        Locale.ROOT,
                        "- Order-flip rate without mitigation: %d/%d pairs (%.0f%%) - the swap check turns these into ties instead of wrong wins%n%n",
                        flips,
                        pairwise.size(),
                        100.0 * flips / pairwise.size()));
        ordering.append("pairwise wrong-decisive=").append(wrongDecisive);

        // stability (first judge only, to bound spend)
        if (includeStability) {
            md.append("### Stability (relevancy, 3 cases, no cache)\n\n");
            double sd1 = 0, sd3 = 0;
            for (int c = 0; c < 3; c++) {
                RagCase rc = RAG.get(c);
                sd1 += sd(repeat(pool, 5, () -> relevancyWith(client, rc, 1)));
                sd3 += sd(repeat(pool, 3, () -> relevancyWith(client, rc, 3)));
            }
            md.append(
                    String.format(
                            Locale.ROOT,
                            "- Mean score std-dev: samples(1) over 5 runs = %.3f; samples(3) over 3 runs = %.3f%n%n",
                            sd1 / 3,
                            sd3 / 3));
        }
        return ordering.toString();
    }

    // --- helpers -------------------------------------------------------------------------

    private static double relevancyWith(LLMClient client, RagCase c, int samples) {
        return RagContextCondition.builder(RagContextCondition.Metric.RELEVANCY)
                .input(c.question())
                .retrievalContext(List.of(c.chunks()))
                .calls(JudgeCalls.using(client).samples(samples))
                .build()
                .evaluate()
                .score();
    }

    private static List<Double> repeat(ExecutorService pool, int times, Callable<Double> task)
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
        double agree = 0, total = 0, relLabelled = 0, relJudged = 0;
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

    private static String row(String label, double value, double target, boolean atLeast) {
        boolean ok = atLeast ? value >= target : value <= target;
        return String.format(
                Locale.ROOT,
                "- %s: %.1f%% (target %s %.0f%%) - %s%n",
                label,
                100 * value,
                atLeast ? ">=" : "<=",
                100 * target,
                ok ? "PASS" : "FAIL");
    }
}
