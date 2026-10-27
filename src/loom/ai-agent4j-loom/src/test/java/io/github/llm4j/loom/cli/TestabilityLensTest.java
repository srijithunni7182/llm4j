package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** R33, R34 of spec 09 (§6.8): is the workflow testable, and is it tested? */
class TestabilityLensTest {

    @TempDir Path dir;

    ByteArrayOutputStream out = new ByteArrayOutputStream();

    static final String SCRIPT =
            """
            tool Mail { use: webhook  url: env.HOOK }
            agent Intent { model: "m"  system: "s" }
            agent Replier { model: "m"  system: "s"  tools: [Mail] }
            workflow Main(q) {
              delegate "{q}" to Intent -> i
              human_prompt "ok?" -> ok
              delegate "tell {q}" to Replier -> r retry 2
              note "{r}"
            }
            """;

    JsonNode review(String... extra) throws Exception {
        return reviewOf(SCRIPT, extra);
    }

    JsonNode reviewOf(String script, String... extra) throws Exception {
        Files.writeString(dir.resolve("main.loom"), script);
        ReviewCommand c = new ReviewCommand();
        List<String> all =
                new ArrayList<>(
                        List.of(
                                dir.resolve("main.loom").toString(),
                                "--format",
                                "json",
                                "--min-severity",
                                "info"));
        all.addAll(List.of(extra));
        new CommandLine(c).parseArgs(all.toArray(new String[0]));
        out.reset();
        WeaveEnv env =
                new WeaveEnv(
                        m -> null,
                        q -> "",
                        new PrintStream(out, true),
                        new PrintStream(new ByteArrayOutputStream(), true),
                        Clock.systemUTC(),
                        d -> {},
                        cmd ->
                                new io.github.llm4j.loom.trigger.system.CommandRunner.Result(
                                        0, "", ""),
                        List.of("weave"),
                        k -> k.equals("HOOK") ? "https://example.com/hook" : null);
        assertThat(ReviewCommand.review(c, env)).isZero();
        return new ObjectMapper().readTree(out.toString());
    }

    List<String> rules(JsonNode r) {
        List<String> ids = new ArrayList<>();
        r.get("findings").forEach(f -> ids.add(f.get("rule").asText()));
        return ids;
    }

    JsonNode one(JsonNode r, String rule) {
        for (JsonNode f : r.get("findings")) if (f.get("rule").asText().equals(rule)) return f;
        throw new AssertionError(rule + " not found in " + rules(r));
    }

    void tests(String name, String yaml) throws Exception {
        Files.createDirectories(dir.resolve("tests"));
        Files.writeString(dir.resolve("tests").resolve(name), yaml);
    }

    @Test
    void theLensIsOptionalUntilTheWorkflowTestPhaseExists() throws Exception {
        assertThat(rules(review())).noneMatch(r -> r.startsWith("LT"));
        assertThat(rules(review("--lens", "+testability"))).contains("LT01");
    }

    @Test
    void lt01IsMediumWhenTheScriptCanSendOrAskAndNamesTheFileToWrite() throws Exception {
        JsonNode f = one(review("--lens", "+testability"), "LT01");
        assertThat(f.get("severity").asText()).isEqualTo("medium");
        assertThat(f.get("fixHint").asText()).contains("tests/main.test.yaml");
    }

    @Test
    void lt01IsLowForAScriptThatOnlyAnswers() throws Exception {
        JsonNode r =
                reviewOf(
                        "agent A { model: \"m\"  system: \"s\" }\nworkflow Main(q) { delegate \"{q}\" to A -> r  note \"{r}\" }\n",
                        "--lens",
                        "+testability");
        assertThat(one(r, "LT01").get("severity").asText()).isEqualTo("low");
    }

    @Test
    void withTestsPresentLt01GoesAndTheOtherRulesLookAtWhatTheyScript() throws Exception {
        tests(
                "main.test.yaml",
                """
                cases:
                  - name: a
                    models: { Intent: "x" }
                """);
        JsonNode r = review("--lens", "+testability");
        List<String> ids = rules(r);
        assertThat(ids).doesNotContain("LT01");
        assertThat(one(r, "LT02").get("title").asText()).contains("Replier");
        assertThat(ids)
                .contains("LT04", "LT05"); // retry 2 and a human_prompt, no fail:, no humans:
        assertThat(ids.stream().filter(x -> x.equals("LT02")).count()).isEqualTo(1);
    }

    @Test
    void failuresAndHumansInTheTestsSatisfyLt04AndLt05() throws Exception {
        tests(
                "main.test.yaml",
                """
                cases:
                  - name: a
                    models: { Intent: "x", Replier: "y" }
                    fail: { Replier: { times: 1, category: timeout } }
                    humans: [ "yes" ]
                """);
        assertThat(rules(review("--lens", "+testability")))
                .doesNotContain("LT01", "LT02", "LT04", "LT05", "LT07");
    }

    @Test
    void defaultsCountToo() throws Exception {
        tests(
                "main.test.yaml",
                """
                defaults:
                  models: { Intent: "x", Replier: "y" }
                  humans: [ "yes" ]
                cases:
                  - name: a
                    fail: { Replier: { times: 1, category: timeout } }
                """);
        assertThat(rules(review("--lens", "+testability"))).doesNotContain("LT02", "LT04", "LT05");
    }

    @Test
    void lt07FindsANameTheScriptNoLongerHasAndSuggestsTheNearestOne() throws Exception {
        tests(
                "main.test.yaml",
                """
                cases:
                  - name: a
                    models: { Intent: "x", Replyer: "y" }
                    tools: { Mailer: [ { returns: ok } ] }
                    expect: { calls: { Replier: 1 } }
                """);
        JsonNode r = review("--lens", "+testability");
        List<String> titles = new ArrayList<>();
        for (JsonNode f : r.get("findings"))
            if (f.get("rule").asText().equals("LT07"))
                titles.add(f.get("title").asText() + "|" + f.get("fixHint").asText());
        assertThat(titles).anyMatch(t -> t.contains("Replyer") && t.contains("did you mean"));
        assertThat(titles).anyMatch(t -> t.contains("Mailer"));
        assertThat(titles)
                .noneMatch(t -> t.startsWith("A test names the agent or classifier Intent"));
    }

    @Test
    void aTestFileForAnotherScriptDoesNotCount() throws Exception {
        tests(
                "other.test.yaml",
                """
                script: ../other.loom
                cases:
                  - name: a
                    models: { Intent: "x", Replier: "y" }
                """);
        assertThat(rules(review("--lens", "+testability"))).contains("LT01");
    }

    @Test
    void anUnreadableTestFileIsANoteNotACrash() throws Exception {
        tests("broken.test.yaml", "cases: [ : :\n");
        JsonNode r = review("--lens", "+testability");
        assertThat(r.toString()).contains("could not be read");
        assertThat(rules(r)).contains("LT01");
    }

    @Test
    void lt06GoesByTheGoldenFileNameSoItNeedsHeuristics() throws Exception {
        assertThat(rules(review("--lens", "+testability"))).doesNotContain("LT06");
        Files.writeString(dir.resolve("review.properties"), "heuristics = on\n");
        List<String> ids = rules(review("--lens", "+testability"));
        assertThat(ids.stream().filter(x -> x.equals("LT06")).count()).isEqualTo(2);
        Files.createDirectories(dir.resolve("eval").resolve("golden"));
        Files.writeString(
                dir.resolve("eval").resolve("golden").resolve("Intent.yaml"), "scenarios: []\n");
        assertThat(
                        rules(review("--lens", "+testability")).stream()
                                .filter(x -> x.equals("LT06"))
                                .count())
                .isEqualTo(1);
    }
}
