package io.github.llm4j.loom.review;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.graph.GraphService;
import io.github.llm4j.loom.review.engine.FindingSource;
import io.github.llm4j.loom.review.engine.ReviewInput;
import io.github.llm4j.loom.review.model.Finding;
import io.github.llm4j.loom.review.model.Note;
import io.github.llm4j.loom.review.pack.DataRuleSource;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Phase 25, R1: LP10 fires on an agent that reasons and must answer in a large schema, and on
 * nothing else. The table crosses every reason to reason with every size of schema.
 */
class Lp10Test {

    @TempDir Path dir;

    static final String SHORT = "You analyse the request.";
    static final String LONG = "Weigh the request carefully against every rule we have. ".repeat(60);

    static final String FLAT2 = "{ a: string, b: number }";
    static final String FLAT6 = "{ a: string, b: string, c: string, d: string, e: string, f: string }";
    static final String NESTED = "{ a: string, b: { c: string, d: number } }";
    static final String LIST_OF_OBJECTS = "{ a: string, items: list<{ name: string, n: number }> }";
    static final String BARE_LIST = "{ a: string, items: list }";

    /** One agent definition per reason to reason (the short prompt is the control: it does not). */
    static String agent(String reason, String schema) {
        String tools = "tool Files { use: file  root: \"data\"  mode: read }\n";
        return switch (reason) {
            case "tools" -> tools + "agent A { model: \"m\" system: \"" + SHORT + "\" tools: [Files] output_schema: " + schema + " }\n";
            case "grounding" -> "agent A { model: \"m\" system: \"" + SHORT + "\" grounding: web output_schema: " + schema + " }\n";
            case "long prompt" -> "agent A { model: \"m\" system: \"" + LONG + "\" output_schema: " + schema + " }\n";
            default -> "agent A { model: \"m\" system: \"" + SHORT + "\" output_schema: " + schema + " }\n";
        };
    }

    static final String CALL = "workflow Main(q) { delegate \"Look at {q}\" to A -> r\n note \"{r.a}\" }\n";

    List<Finding> review(String source) throws Exception {
        return review(source, ReviewSettings.defaults());
    }

    List<Finding> review(String source, ReviewSettings settings) throws Exception {
        Path file = dir.resolve("s" + System.nanoTime() + ".loom");
        Files.writeString(file, source);
        ReviewInput input =
                new ReviewInput(
                        file.getFileName().toString(),
                        dir,
                        new LoomLoader().load(file.toString()),
                        Map.of(),
                        List.of(),
                        new GraphService().graph(file));
        List<Finding> out = new ArrayList<>();
        new DataRuleSource(List.of(Reviews.corePack()))
                .collect(
                        input,
                        settings,
                        new FindingSource.Sink() {
                            @Override
                            public void finding(Finding f) {
                                out.add(f);
                            }

                            @Override
                            public void note(Note n) {}
                        });
        return out;
    }

    static Stream<Arguments> table() {
        List<Arguments> rows = new ArrayList<>();
        for (String reason : List.of("tools", "grounding", "long prompt", "none")) {
            rows.add(Arguments.of(reason, "flat 2", FLAT2, false));
            rows.add(Arguments.of(reason, "flat 6", FLAT6, !reason.equals("none")));
            rows.add(Arguments.of(reason, "nested", NESTED, !reason.equals("none")));
            rows.add(Arguments.of(reason, "list of objects", LIST_OF_OBJECTS, !reason.equals("none")));
        }
        rows.add(Arguments.of("tools", "bare list", BARE_LIST, false));
        return rows.stream();
    }

    @ParameterizedTest(name = "{0} + {1} schema -> fires {3}")
    @MethodSource("table")
    void firesExactlyWhenTheAgentReasonsAndTheSchemaIsLarge(
            String reason, String size, String schema, boolean fires) throws Exception {
        List<Finding> found = review(agent(reason, schema) + CALL);

        assertThat(found.stream().anyMatch(f -> f.rule().equals("LP10")))
                .as(reason + " + " + size)
                .isEqualTo(fires);
    }

    @Test
    void theMessageNamesTheReasonAndTheSchema() throws Exception {
        Finding f =
                review(agent("tools", LIST_OF_OBJECTS) + CALL).stream()
                        .filter(x -> x.rule().equals("LP10"))
                        .findFirst()
                        .orElseThrow();

        assertThat(f.title()).contains("Agent A").contains("has tools").contains("a list of objects");
        assertThat(f.severity().word()).isEqualTo("medium");
        assertThat(f.fixHint()).contains("extractor");
    }

    @Test
    void theSettingsMoveTheThresholds() throws Exception {
        String flat6WithTools = agent("tools", FLAT6) + CALL;
        ReviewSettings stricter = ReviewSettings.defaults().withValue("prompts.large-schema-fields", "8");
        ReviewSettings looser = ReviewSettings.defaults().withValue("prompts.large-schema-fields", "1");

        assertThat(review(flat6WithTools, stricter)).noneMatch(f -> f.rule().equals("LP10"));
        assertThat(review(agent("tools", FLAT2) + CALL, looser)).anyMatch(f -> f.rule().equals("LP10"));

        ReviewSettings shortPrompts = ReviewSettings.defaults().withValue("prompts.short-prompt-tokens", "5");
        assertThat(review(agent("none", NESTED) + CALL, shortPrompts)).anyMatch(f -> f.rule().equals("LP10"));
    }

    @Test
    void theExpectingOfAnyDelegateCountsAsTheSchema() throws Exception {
        String source =
                "tool Files { use: file  root: \"data\"  mode: read }\n"
                        + "agent A { model: \"m\" system: \"" + SHORT + "\" tools: [Files] }\n"
                        + "workflow Main(q) {\n"
                        + "  delegate \"one {q}\" to A -> r expecting { v: string }\n"
                        + "  delegate \"two {q}\" to A -> s expecting " + NESTED + "\n"
                        + "  note \"{r.v} {s.a}\"\n}\n";

        assertThat(review(source)).anyMatch(f -> f.rule().equals("LP10"));
    }

    @Test
    void anAgentThatWorksOverRoundsReasonsEvenWithAShortPromptAndNoTools() throws Exception {
        String source =
                "agent A { model: \"m\" system: \"" + SHORT + "\" output_schema: " + NESTED + " }\n"
                        + "workflow Main(q) {\n"
                        + "  delegate \"start {q}\" to A -> r\n"
                        + "  loop until (r.a == \"done\") max 4 {\n"
                        + "    delegate \"improve {r.a}\" to A -> r\n"
                        + "  }\n}\n";

        Finding f = review(source).stream().filter(x -> x.rule().equals("LP10")).findFirst().orElseThrow();
        assertThat(f.title()).contains("works over rounds");
    }

    @Test
    void aLoopThatDoesNotFeedTheAnswerBackIsNotRounds() throws Exception {
        String source =
                "agent A { model: \"m\" system: \"" + SHORT + "\" output_schema: " + NESTED + " }\n"
                        + "workflow Main(q) {\n"
                        + "  loop until (r.a == \"done\") max 4 {\n"
                        + "    delegate \"look at {q}\" to A -> r\n"
                        + "  }\n}\n";

        assertThat(review(source)).noneMatch(f -> f.rule().equals("LP10"));
    }

    @Test
    void anAgentWhosePromptSizeIsUnknownIsNotJudgedOnSize() throws Exception {
        String source =
                "agent A { model: \"m\" prompt: \"missing-file\" output_schema: " + NESTED + " }\n" + CALL;

        assertThat(review(source)).noneMatch(f -> f.rule().equals("LP10"));
    }
}
