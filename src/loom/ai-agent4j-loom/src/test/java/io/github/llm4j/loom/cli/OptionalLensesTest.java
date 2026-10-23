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

/**
 * R33, R39, R40 of spec 09: the four optional lenses are off until asked, and the rules that need
 * the estimates (LH03, LB02, LB03) speak only when their lens is on.
 */
class OptionalLensesTest {

    @TempDir Path dir;

    ByteArrayOutputStream out = new ByteArrayOutputStream();

    static final String MAIL =
            "tool Mail { use: webhook  url: env.HOOK }\n"
                    + "agent N { model: \"m\"  system: \"s\"  tools: [Mail] }\n";

    int run(String script, String... args) throws Exception {
        Files.writeString(dir.resolve("main.loom"), script);
        return rerun(args);
    }

    int rerun(String... args) throws Exception {
        ReviewCommand c = new ReviewCommand();
        List<String> all = new ArrayList<>(List.of(dir.resolve("main.loom").toString()));
        all.addAll(List.of(args));
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
        return ReviewCommand.review(c, env);
    }

    List<String> titles(String rule, String script, String... args) throws Exception {
        List<String> all = new ArrayList<>(List.of("--format", "json", "--min-severity", "info"));
        all.addAll(List.of(args));
        assertThat(run(script, all.toArray(new String[0]))).isZero();
        List<String> out = new ArrayList<>();
        for (JsonNode f : new ObjectMapper().readTree(this.out.toString()).get("findings"))
            if (f.get("rule").asText().equals(rule)) out.add(f.get("title").asText());
        return out;
    }

    static String questions(int n) {
        StringBuilder b = new StringBuilder("workflow Main(q) {\n");
        for (int i = 0; i < n; i++)
            b.append("  human_prompt \"question ")
                    .append(i)
                    .append("?\" -> a")
                    .append(i)
                    .append('\n');
        return b.append("}\n").toString();
    }

    @Test
    void theOptionalLensesAreOffUntilAskedFor() throws Exception {
        String script = questions(12);
        assertThat(titles("LH03", script)).isEmpty();
        assertThat(titles("LH03", script, "--lens", "+oversight")).hasSize(1);
        assertThat(titles("LH03", script, "--lens", "all")).hasSize(1);
    }

    @Test
    void lh03CountsWorstCaseQuestionsAndHonoursTheLimit() throws Exception {
        assertThat(titles("LH03", questions(10), "--lens", "+oversight")).isEmpty();
        assertThat(titles("LH03", questions(11), "--lens", "+oversight"))
                .singleElement()
                .asString()
                .contains("up to 11");
        Files.writeString(dir.resolve("review.properties"), "oversight.max-questions = 20\n");
        assertThat(titles("LH03", questions(12), "--lens", "+oversight")).isEmpty();
    }

    @Test
    void lh03CountsQuestionsInALoopAndCallsAnUnboundedOneUnlimited() throws Exception {
        String script =
                "workflow Main(q) { loop until (a == \"y\") { human_prompt \"again?\" -> a } }\n";
        assertThat(titles("LH03", script, "--lens", "+oversight"))
                .singleElement()
                .asString()
                .contains("an unlimited number");
    }

    @Test
    void lb02AddsUpEffectsAndFiresPastTheLimit() throws Exception {
        String script =
                MAIL
                        + "workflow Main(q) { loop until (x == \"y\") max 3 { delegate \"{q}\" to N -> x } }\n";
        // 3 turns x 10 rounds = 30 sends at worst
        assertThat(titles("LB02", script, "--lens", "+blast-radius"))
                .singleElement()
                .asString()
                .contains("up to 30");
        Files.writeString(dir.resolve("review.properties"), "blast.max-effects = 100\n");
        assertThat(titles("LB02", script, "--lens", "+blast-radius")).isEmpty();
    }

    @Test
    void lb02CallsAnUnboundedLoopUnlimited() throws Exception {
        String script =
                MAIL
                        + "workflow Main(q) { loop until (x == \"y\") { delegate \"{q}\" to N -> x } }\n";
        assertThat(titles("LB02", script, "--lens", "+blast-radius"))
                .singleElement()
                .asString()
                .contains("an unlimited number");
    }

    static final String SCHEDULED =
            "agent A { model: \"m\"  system: \"s\" }\n"
                    + "workflow Daily(t) { delegate \"{t}\" to A -> r  note \"{r}\" }\n"
                    + "schedule Hourly { cron: \"0 * * * *\"  run: Daily(t = \"news\") }\n";

    @Test
    void lb03AsksForAPerDayBudgetOnAScheduledWorkflowAndCountsTheRuns() throws Exception {
        assertThat(titles("LB03", SCHEDULED, "--lens", "+blast-radius"))
                .singleElement()
                .asString()
                .contains("up to 24 times a day")
                .contains("no budget per day");
        assertThat(
                        titles(
                                "LB03",
                                "budget { tokens: 100000 per day }\n" + SCHEDULED,
                                "--lens",
                                "+blast-radius"))
                .isEmpty();
        assertThat(
                        titles(
                                "LB03",
                                "budget { tokens: 100000 }\n" + SCHEDULED,
                                "--lens",
                                "+blast-radius"))
                .hasSize(1);
    }

    @Test
    void lb03IsQuietForAWorkflowNothingSchedules() throws Exception {
        assertThat(
                        titles(
                                "LB03",
                                "agent A { model: \"m\"  system: \"s\" }\nworkflow Main(t) { delegate \"{t}\" to A -> r  note \"{r}\" }\n",
                                "--lens",
                                "+blast-radius"))
                .isEmpty();
    }

    @Test
    void lb03OffersABudgetTheDeveloperSizesAndTheScriptIsCheckedBeforeItIsWritten()
            throws Exception {
        List<String> all =
                List.of("--format", "json", "--lens", "+blast-radius", "--min-severity", "info");
        assertThat(run(SCHEDULED, all.toArray(new String[0]))).isZero();
        String fp = null;
        for (JsonNode f : new ObjectMapper().readTree(out.toString()).get("findings"))
            if (f.get("rule").asText().equals("LB03")) {
                fp = f.get("fingerprint").asText();
                assertThat(f.get("options").get(0).get("id").asText()).isEqualTo("budget-per-day");
            }
        assertThat(fp).isNotNull();
        assertThat(
                        rerun(
                                "--lens",
                                "+blast-radius",
                                "--fix-finding",
                                fp,
                                "--option",
                                "budget-per-day",
                                "--set",
                                "tokens=50000",
                                "--yes"))
                .isZero();
        assertThat(Files.readString(dir.resolve("main.loom")))
                .startsWith("budget { tokens: 50000 per day }\n");
        assertThat(
                        titles(
                                "LB03",
                                Files.readString(dir.resolve("main.loom")),
                                "--lens",
                                "+blast-radius"))
                .isEmpty();
    }

    @Test
    void lg02IsNotEvaluatedUntilTheProjectListsDeprecatedModels() throws Exception {
        String script =
                "agent A { model: \"old-model\"  system: \"s\" }\nworkflow Main(q) { delegate \"{q}\" to A -> r  note \"{r}\" }\n";
        assertThat(titles("LG02", script, "--lens", "+governance")).isEmpty();
        assertThat(out.toString()).contains("data.deprecated-models");
        Files.writeString(
                dir.resolve("review.properties"), "data.deprecated-models = old-model, other\n");
        assertThat(titles("LG02", script, "--lens", "+governance"))
                .singleElement()
                .asString()
                .contains("old-model");
    }

    @Test
    void rulesOfTheOptionalLensesAreInTheCatalogAndExplainable() throws Exception {
        for (String id : List.of("LH03", "LB02", "LB03", "LG01", "LM05", "LH01", "LB01")) {
            out.reset();
            assertThat(
                            run(
                                    "agent A { model: \"m\"  system: \"s\" }\nworkflow Main(q) { note \"x\" }\n",
                                    "--explain",
                                    id))
                    .as(id)
                    .isZero();
            assertThat(out.toString()).contains(id);
        }
    }
}
