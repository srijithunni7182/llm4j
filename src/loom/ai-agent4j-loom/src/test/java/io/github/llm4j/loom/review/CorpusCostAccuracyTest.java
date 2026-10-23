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

    static final String TOPIC = "how a transistor works";
    static final String EXPLAIN = "Explain {q} in three short paragraphs.";

    static List<Case> cases() {
        Map<String, Object> q = Map.of("q", TOPIC);
        return List.of(
                new Case(
                        "single",
                        A + wf("delegate \"" + EXPLAIN + "\" to A -> reply  note \"{reply}\""),
                        q,
                        (a, n) -> text(1200, "")),
                new Case(
                        "chain",
                        A
                                + B
                                + wf(
                                        "delegate \""
                                                + EXPLAIN
                                                + "\" to A -> first  delegate \"Rewrite this more clearly, keeping three paragraphs: {first}\" to B -> second  note \"{second}\""),
                        q,
                        (a, n) -> text(1000, "")),
                new Case(
                        "loop-stops-at-typical",
                        A
                                + wf(
                                        "loop until (result == \"done\") max 5 { delegate \""
                                                + EXPLAIN
                                                + "\" to A -> result }"),
                        q,
                        (a, n) -> n >= 3 ? "done" : text(900, "")),
                new Case(
                        "loop-hits-max",
                        A
                                + wf(
                                        "loop until (result == \"done\") max 3 { delegate \""
                                                + EXPLAIN
                                                + "\" to A -> result } on_exhausted { note \"gave up\" }"),
                        q,
                        (a, n) -> text(900, "")),
                new Case(
                        "for-each",
                        A
                                + "workflow Main(docs) { for each doc in docs { delegate \"Summarise {doc} in one paragraph.\" to A -> summary } }",
                        Map.of("docs", List.of("one", "two", "three", "four", "five")),
                        (a, n) -> text(900, "")),
                new Case(
                        "alt-taken",
                        A
                                + B
                                + wf(
                                        "delegate \""
                                                + EXPLAIN
                                                + "\" to A -> draft  alt (q == \""
                                                + TOPIC
                                                + "\") { delegate \"Expand this with an example: {draft}\" to B -> expanded } else { note \"short\" }"),
                        q,
                        (a, n) -> text(1000, "")),
                new Case(
                        "broadcast",
                        A
                                + B
                                + wf(
                                        "broadcast \""
                                                + EXPLAIN
                                                + "\" to [A, B] -> reviews  note \"done\""),
                        q,
                        (a, n) -> text(1200, "")),
                new Case(
                        "call-twice",
                        A
                                + "workflow Helper(q) { delegate \""
                                + EXPLAIN
                                + "\" to A -> help }\nworkflow Main(q) { call Helper(q = q) -> outone  call Helper(q = q) -> outtwo  note \"done\" }",
                        q,
                        (a, n) -> text(900, "")),
                new Case(
                        "parallel-three",
                        A
                                + wf(
                                        "parallel { delegate \"Explain the first idea behind {q} in one paragraph.\" to A -> intro  delegate \"Explain the second idea behind {q} in one paragraph.\" to A -> body  delegate \"Explain the third idea behind {q} in one paragraph.\" to A -> outro } note \"{intro}{body}{outro}\""),
                        q,
                        (a, n) -> text(900, "")),
                new Case(
                        "long-prompt",
                        BUDGET
                                + "agent A { model: \"m\"  system: \"You are A. "
                                + "Follow the house style. ".repeat(40)
                                + "\" }\n"
                                + wf(
                                        "delegate \""
                                                + EXPLAIN
                                                + "\" to A -> reply  note \"{reply}\""),
                        q,
                        (a, n) -> text(1200, "")));
    }

    static String wf(String body) {
        return "workflow Main(q) { " + body + " }";
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
        return record(c, run, mock(c));
    }

    /** A provider that answers with text of the length the case says and reports usage honestly. */
    static LLMClientFactory mock(Case c) {
        Map<String, AtomicInteger> calls = new java.util.concurrent.ConcurrentHashMap<>();
        return model ->
                new LLMClient() {
                    @Override
                    public LLMResponse chat(LLMRequest request) {
                        String system = request.getMessages().get(0).getContent();
                        String agent = system.replaceAll("(?s).*?You are (\\w+)\\..*", "$1");
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
    }

    /** Runs the case through the executor with this provider and returns the journaled tokens. */
    static long record(Case c, Path run, LLMClientFactory factory) throws IOException {
        LoomScript script = new LoomParser(new Lexer(c.script()).tokenize()).parseScript();
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
        if (System.getProperty("dumpJournal") != null)
            System.out.println(
                    "JOURNAL "
                            + c.name()
                            + " "
                            + new FileRunJournal(run.resolve("journal.json"))
                                    .all().entrySet().stream()
                                            .filter(x -> x.getKey().contains("usage"))
                                            .toList());
        return r.total().actualTokens();
    }
}
