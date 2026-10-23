package io.github.llm4j.loom.review;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.LLMClient;
import io.github.llm4j.budget.CharsPerTokenEstimator;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.review.cost.Calibration;
import io.github.llm4j.loom.review.cost.CostEstimate;
import io.github.llm4j.loom.review.cost.CostEstimator;
import io.github.llm4j.loom.review.cost.WorkflowCost;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import io.github.llm4j.loom.runtime.FileRunJournal;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A4, R17 of spec 09: the estimate is measured against runs, not promised. Each case runs a
 * workflow for real through the executor, with a provider that answers with text of a stated length
 * and reports the usage the run-time guard's own estimator gives for what it was really sent and
 * what it answered. The typical estimate must be within 2x of what the run used for at least 80 %
 * of the cases, and the worst-case estimate must never be below it.
 *
 * <p>These are mock-provider runs: they test how the estimator models prompts, loops, fan-out and
 * branches, not how long real models talk. The assumed answer length (600 tokens) is checked
 * against real providers by the live check in VERIFICATION.md, which is still owed.
 */
class CorpusCostAccuracyTest {

    @TempDir Path dir;

    /** A case: a script, the arguments, and how long each agent's n-th answer is in characters. */
    record Case(
            String name,
            String script,
            Map<String, Object> args,
            BiFunction<String, Integer, String> answer) {}

    static String text(int chars, String ending) {
        String base = "word ".repeat(Math.max(0, chars / 5));
        return base + ending;
    }

    /** A budget makes the run journal the usage it is measured by. */
    static final String BUDGET = "budget { tokens: 100000000 }\n";

    static final String A =
            BUDGET
                    + "agent A { model: \"m\"  system: \"You are A. Answer the request in a few paragraphs.\" }\n";
    static final String B =
            "agent B { model: \"n\"  system: \"You are B. Answer the request in a few paragraphs.\" }\n";

    static List<Case> cases() {
        return List.of(
                new Case(
                        "single",
                        A
                                + "workflow Main(q) { delegate \"Question: {q}\" to A -> r  note \"{r}\" }",
                        Map.of("q", "why"),
                        (a, n) -> text(2200, "")),
                new Case(
                        "chain",
                        A
                                + B
                                + "workflow Main(q) { delegate \"Question: {q}\" to A -> r  delegate \"Improve: {r}\" to B -> s  note \"{s}\" }",
                        Map.of("q", "why"),
                        (a, n) -> text(2000, "")),
                new Case(
                        "loop-stops-early",
                        A
                                + "workflow Main(q) { loop until (r == \"done\") max 5 { delegate \"Try: {q}\" to A -> r } }",
                        Map.of("q", "why"),
                        (a, n) -> n >= 2 ? "done" : text(1800, "")),
                new Case(
                        "loop-hits-max",
                        A
                                + "workflow Main(q) { loop until (r == \"done\") max 3 { delegate \"Try: {q}\" to A -> r } on_exhausted { note \"gave up\" } }",
                        Map.of("q", "why"),
                        (a, n) -> text(1800, "")),
                new Case(
                        "for-each",
                        A
                                + "workflow Main(docs) { for each d in docs { delegate \"Summarise: {d}\" to A -> r } }",
                        Map.of("docs", List.of("one", "two", "three", "four")),
                        (a, n) -> text(1800, "")),
                new Case(
                        "alt-taken",
                        A
                                + B
                                + "workflow Main(q) { delegate \"Question: {q}\" to A -> r  alt (q == \"why\") { delegate \"Expand: {r}\" to B -> s } else { note \"short\" } }",
                        Map.of("q", "why"),
                        (a, n) -> text(2000, "")),
                new Case(
                        "broadcast",
                        A
                                + B
                                + "workflow Main(q) { broadcast \"Review: {q}\" to [A, B] -> rs  note \"done\" }",
                        Map.of("q", "why"),
                        (a, n) -> text(2400, "")),
                new Case(
                        "call-twice",
                        A
                                + "workflow Helper(q) { delegate \"Help with {q}\" to A -> h }\nworkflow Main(q) { call Helper(q = q) -> x  call Helper(q = q) -> y  note \"done\" }",
                        Map.of("q", "why"),
                        (a, n) -> text(1800, "")),
                new Case(
                        "parallel-three",
                        A
                                + "workflow Main(q) { parallel { delegate \"One {q}\" to A -> a  delegate \"Two {q}\" to A -> b  delegate \"Three {q}\" to A -> c } note \"{a}{b}{c}\" }",
                        Map.of("q", "why"),
                        (a, n) -> text(1800, "")),
                new Case(
                        "long-prompt",
                        BUDGET
                                + "agent A { model: \"m\"  system: \"You are A. "
                                + "Follow the house style. ".repeat(40)
                                + "\" }\nworkflow Main(q) { delegate \"Question: {q}\" to A -> r  note \"{r}\" }",
                        Map.of("q", "why"),
                        (a, n) -> text(2800, "")));
    }

    @Test
    void typicalIsWithinTwoTimesForMostCasesAndWorstIsNeverBelowActual() throws Exception {
        StringBuilder table =
                new StringBuilder(
                        "| Case | Estimated typical | Actual | Ratio | Estimated worst | Worst >= actual |\n|---|---|---|---|---|---|\n");
        int within = 0;
        List<String> worstBelow = new ArrayList<>();
        List<Case> all = cases();
        for (Case c : all) {
            Path run = Files.createDirectories(dir.resolve(c.name()));
            long actual = record(c, run);
            CostEstimate e =
                    new CostEstimator()
                            .estimate(
                                    ReviewFixtures.input(run, c.script()),
                                    ReviewSettings.defaults());
            WorkflowCost main =
                    e.workflows().stream()
                            .filter(w -> w.name().equals("Main"))
                            .findFirst()
                            .orElseThrow();
            long typical = main.typical().tokens();
            double ratio = (double) typical / actual;
            boolean ok = ratio >= 0.5 && ratio <= 2.0;
            if (ok) within++;
            boolean worstOk = main.unbounded() || main.worst().tokens() >= actual;
            if (!worstOk) worstBelow.add(c.name());
            table.append(
                    String.format(
                            "| %s | %d | %d | %.2f | %s | %s |%n",
                            c.name(),
                            typical,
                            actual,
                            ratio,
                            main.unbounded() ? "unbounded" : String.valueOf(main.worst().tokens()),
                            worstOk));
        }
        table.append(String.format("%nWithin 2x: %d of %d.%n", within, all.size()));
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/review-cost-accuracy.md"), table.toString());
        assertThat(all.size()).isGreaterThanOrEqualTo(8);
        assertThat(worstBelow).as("worst case below what a run used").isEmpty();
        assertThat(within)
                .as(table.toString())
                .isGreaterThanOrEqualTo((int) Math.ceil(all.size() * 0.8));
    }

    /** Runs the case for real and returns the tokens the journal says it used. */
    private long record(Case c, Path run) throws IOException {
        LoomScript script = new LoomParser(new Lexer(c.script()).tokenize()).parseScript();
        Map<String, AtomicInteger> calls = new java.util.concurrent.ConcurrentHashMap<>();
        LLMClientFactory factory =
                model ->
                        new LLMClient() {
                            @Override
                            public LLMResponse chat(LLMRequest request) {
                                String system = request.getMessages().get(0).getContent();
                                String agent =
                                        system.replaceAll("(?s).*?You are (\\w+)\\..*", "$1");
                                int n =
                                        calls.computeIfAbsent(agent, k -> new AtomicInteger())
                                                .incrementAndGet();
                                String content = c.answer().apply(agent, n);
                                String reply =
                                        "```json\n{\"thought\": \"t\", \"final_answer\": \""
                                                + content
                                                + "\"}\n```";
                                long in = CharsPerTokenEstimator.INSTANCE.prompt(request);
                                long out = CharsPerTokenEstimator.INSTANCE.completion(reply);
                                return LLMResponse.builder()
                                        .content(reply)
                                        .model(model)
                                        .tokenUsage((int) in, (int) out, (int) (in + out))
                                        .build();
                            }

                            @Override
                            public Stream<LLMResponse> chatStream(LLMRequest request) {
                                return Stream.of(chat(request));
                            }
                        };
        HarnessExecutor executor = new HarnessExecutor(script, new ToolRegistry(), factory);
        executor.setJournal(new FileRunJournal(run.resolve("journal.json")));
        executor.initialize();
        Map<String, String> strings = new java.util.HashMap<>();
        c.args()
                .forEach(
                        (k, v) -> {
                            if (v instanceof String str) strings.put(k, str);
                            else executor.getContext().setVariable(k, v);
                        });
        executor.executeWorkflow("Main", strings);
        executor.shutdown();
        Files.writeString(run.resolve("main.loom"), c.script());
        CostEstimate e =
                new CostEstimator()
                        .estimate(ReviewFixtures.input(run, c.script()), ReviewSettings.defaults());
        Calibration.Result r = Calibration.compare(run, e);
        return r.total().actualTokens();
    }
}
