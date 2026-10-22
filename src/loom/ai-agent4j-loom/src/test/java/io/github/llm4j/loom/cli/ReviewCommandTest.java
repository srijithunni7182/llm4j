package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * R6, R9, R19, R32 of spec 09: {@code weave review}, its options, its exit codes, and that it is
 * advisory by default.
 */
class ReviewCommandTest {

    @TempDir Path dir;

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();

    WeaveEnv env() {
        return new WeaveEnv(
                m -> null,
                q -> "",
                new PrintStream(out, true),
                new PrintStream(err, true),
                Clock.systemUTC(),
                d -> {},
                c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""),
                List.of("weave"),
                k -> k.equals("HOOK") ? "https://example.com/hook" : null);
    }

    static final String RISKY =
            """
            tool Files { use: file  root: "data"  mode: read }
            tool Hook  { use: webhook  url: env.HOOK }
            agent A { model: "m"  system: "s"  tools: [web_search, Files, Hook] }
            workflow Main(q) { delegate "{q}" to A -> r  note "{r}" }
            """;

    static final String CALM =
            "budget { tokens: 1000 }\nagent A { model: \"m\"  system: \"s\" }\nworkflow Main(q) { delegate \"{q}\" to A -> r  note \"{r}\" }\n";

    ReviewCommand command(String source, String... args) throws Exception {
        Path f = dir.resolve("w.loom");
        Files.writeString(f, source);
        ReviewCommand c = new ReviewCommand();
        List<String> all = new java.util.ArrayList<>(List.of(f.toString()));
        all.addAll(List.of(args));
        new CommandLine(c).parseArgs(all.toArray(new String[0]));
        return c;
    }

    int run(String source, String... args) throws Exception {
        return ReviewCommand.review(command(source, args), env());
    }

    @Test
    void reviewIsAdvisoryByDefaultAndExitsZeroEvenWithHighFindings() throws Exception {
        assertThat(run(RISKY)).isZero();
        assertThat(out.toString())
                .contains("w.loom")
                .contains("high")
                .contains("LA01")
                .contains("Advisory");
    }

    @Test
    void failOnMakesItAGateAndNoneTurnsItBackIntoAdvice() throws Exception {
        assertThat(run(RISKY, "--fail-on", "high")).isEqualTo(1);
        assertThat(run(RISKY, "--fail-on", "none")).isZero();
        assertThat(run(CALM, "--fail-on", "info")).isZero();
    }

    @Test
    void theSettingsFileBesideTheScriptCanSetTheGateAndTheCommandLineWins() throws Exception {
        Files.writeString(dir.resolve("review.properties"), "fail-on = high\n");
        assertThat(run(RISKY)).isEqualTo(1);
        assertThat(run(RISKY, "--fail-on", "none")).isZero();
    }

    @Test
    void anExplicitRulesFileIsUsedInsteadOfTheOneBesideTheScript() throws Exception {
        Path rules = dir.resolve("other.properties");
        Files.writeString(rules, "fail-on = high\n");
        assertThat(run(RISKY, "--rules", rules.toString())).isEqualTo(1);
        assertThat(run(RISKY, "--rules", dir.resolve("missing.properties").toString()))
                .isEqualTo(2);
        assertThat(err.toString()).contains("does not exist");
    }

    @Test
    void aBadSettingsFileIsRefusedWithEveryProblemAndItsLine() throws Exception {
        Files.writeString(dir.resolve("review.properties"), "fial-on = high\nfail-on = loud\n");
        assertThat(run(RISKY)).isEqualTo(2);
        assertThat(err.toString())
                .contains("review.properties:1")
                .contains("did you mean 'fail-on'")
                .contains("review.properties:2");
    }

    @Test
    void badOptionsMissingFilesAndUnreadableScriptsAreExitTwoWithAMessageOnStderr()
            throws Exception {
        assertThat(run(RISKY, "--format", "pdf")).isEqualTo(2);
        assertThat(err.toString()).contains("--format takes text, md or json");
        err.reset();
        assertThat(run(RISKY, "--min-severity", "critical")).isEqualTo(2);
        assertThat(err.toString()).contains("--min-severity");
        err.reset();
        assertThat(run(RISKY, "--fail-on", "critical")).isEqualTo(2);
        assertThat(err.toString()).contains("--fail-on takes");
        err.reset();
        assertThat(run(RISKY, "--lens", "securty")).isEqualTo(2);
        assertThat(err.toString()).contains("did you mean 'security'");
        err.reset();
        ReviewCommand missing = command(RISKY);
        missing.script = new File(dir.toFile(), "nope.loom");
        assertThat(ReviewCommand.review(missing, env())).isEqualTo(2);
        assertThat(err.toString()).contains("could not be read");
        err.reset();
        assertThat(run("this is not loom {{{")).isEqualTo(2);
        err.reset();
        ReviewCommand none = new ReviewCommand();
        new CommandLine(none).parseArgs();
        assertThat(ReviewCommand.review(none, env())).isEqualTo(2);
        assertThat(err.toString()).contains("needs a script");
    }

    @Test
    void jsonGoesToTheTerminalAndToAFileAndIsTheDocumentedShape() throws Exception {
        File report = dir.resolve("report.json").toFile();
        assertThat(run(RISKY, "--format", "json", "--out", report.toString())).isZero();
        JsonNode j = new ObjectMapper().readTree(Files.readString(report.toPath()));
        assertThat(j.get("version").asInt()).isEqualTo(1);
        assertThat(j.get("summary").get("high").asInt()).isEqualTo(1);
        assertThat(j.get("findings").get(0).get("rule").asText()).isEqualTo("LA01");
        assertThat(out.toString()).contains("\"rule\" : \"LA01\"");
    }

    @Test
    void markdownIsAvailable() throws Exception {
        assertThat(run(RISKY, "--format", "md")).isZero();
        assertThat(out.toString()).contains("# Review of w.loom").contains("### `LA01`");
    }

    @Test
    void minSeverityHidesLessSeriousFindingsFromTheReportButNotFromTheGate() throws Exception {
        assertThat(run(RISKY, "--min-severity", "high", "--fail-on", "low")).isEqualTo(1);
        assertThat(out.toString()).contains("LA01").doesNotContain("LA03");
    }

    @Test
    void lensesCanBeChosenOnTheCommandLine() throws Exception {
        assertThat(run(RISKY, "--lens", "validity")).isZero();
        assertThat(out.toString()).doesNotContain("LA01").contains("no open findings");
        out.reset();
        assertThat(run(RISKY, "--lens", "all", "--format", "json")).isZero();
        assertThat(
                        new ObjectMapper()
                                .readTree(out.toString())
                                .get("summary")
                                .get("lenses")
                                .get("ran"))
                .hasSize(13);
    }

    @Test
    void whatWeaveCheckReportsAppearsAsValidityFindings() throws Exception {
        String broken = "workflow Main(q) { delegate \"{q}\" to Nobody -> r }\n";
        assertThat(run(broken, "--format", "json")).isZero();
        JsonNode findings = new ObjectMapper().readTree(out.toString()).get("findings");
        boolean sawValidity = false;
        for (JsonNode f : findings)
            if (f.get("rule").asText().equals("LV00")
                    && f.get("title").asText().contains("Nobody")) {
                sawValidity = true;
                assertThat(f.get("severity").asText()).isEqualTo("high");
                assertThat(f.get("lens").asText()).isEqualTo("validity");
            }
        assertThat(sawValidity).isTrue();
    }

    @Test
    void aTurnedOffRuleAndAChangedSeverityFromTheSettingsAreApplied() throws Exception {
        Files.writeString(dir.resolve("review.properties"), "LA01 = off\nLA02.severity = low\n");
        assertThat(run(RISKY, "--format", "json")).isZero();
        JsonNode findings = new ObjectMapper().readTree(out.toString()).get("findings");
        for (JsonNode f : findings) {
            assertThat(f.get("rule").asText()).isNotEqualTo("LA01");
            if (f.get("rule").asText().equals("LA02"))
                assertThat(f.get("severity").asText()).isEqualTo("low");
        }
    }

    @Test
    void explainTellsWhatARuleChecksAndAcceptsAnyCase() throws Exception {
        assertThat(run(RISKY, "--explain", "la01")).isZero();
        assertThat(out.toString())
                .contains("LA01")
                .contains("lethal trifecta")
                .contains("Lens: Security")
                .contains("From: audit");
        out.reset();
        assertThat(run(RISKY, "--explain", "LV00")).isZero();
        assertThat(out.toString()).contains("weave check");
    }

    @Test
    void explainingAnUnknownRuleNamesTheNearestOne() throws Exception {
        assertThat(run(RISKY, "--explain", "LA011")).isEqualTo(2);
        assertThat(err.toString()).contains("no rule LA011");
        err.reset();
        assertThat(run(RISKY, "--explain", "LA1")).isEqualTo(2);
        assertThat(err.toString()).contains("weave review --explain takes a rule id");
    }

    @Test
    void everyAuditRuleIsExplainable() throws Exception {
        for (int i = 1; i <= 20; i++) {
            out.reset();
            String id = String.format("LA%02d", i);
            assertThat(run(RISKY, "--explain", id)).as(id).isZero();
            assertThat(out.toString()).contains(id);
        }
    }

    static final String SLOPPY =
            """
            agent Researcher { model: "m"  system: "s"  tools: [web_search] }
            agent Idle { model: "gemini-flash-latest"  system: "never used" }
            workflow Main(q) {
                delegate "{q}" to Researcher -> r
                loop until (x == "yes") { delegate "{q}" to Researcher -> x }
                note "{r}"
            }
            """;

    private com.fasterxml.jackson.databind.JsonNode json(String source, String... args)
            throws Exception {
        List<String> all = new java.util.ArrayList<>(List.of("--format", "json"));
        all.addAll(List.of(args));
        out.reset();
        assertThat(run(source, all.toArray(new String[0]))).isZero();
        return new ObjectMapper().readTree(out.toString());
    }

    private static List<String> rules(com.fasterxml.jackson.databind.JsonNode report) {
        List<String> ids = new java.util.ArrayList<>();
        report.get("findings").forEach(f -> ids.add(f.get("rule").asText()));
        return ids;
    }

    @Test
    void rulesFromTheCorePackAppearBesideTheAuditAndTheyNameTheirPackAndNode() throws Exception {
        JsonNode report = json(SLOPPY);
        assertThat(rules(report)).contains("LR01", "LR03");
        for (JsonNode f : report.get("findings")) {
            if (f.get("rule").asText().equals("LR01")) {
                assertThat(f.get("source").asText()).isEqualTo("pack core@1.0.0");
                assertThat(f.get("lens").asText()).isEqualTo("reliability");
                assertThat(f.get("location").get("node").asText()).matches("n\\d+");
                assertThat(f.get("title").asText()).contains("delegate Researcher");
            }
        }
    }

    @Test
    void optionalLensRulesStayOffUntilAskedForOnTheCommandLineOrInTheSettingsFile()
            throws Exception {
        assertThat(rules(json(SLOPPY))).doesNotContain("LM01", "LG04");
        assertThat(rules(json(SLOPPY, "--lens", "+maintainability"))).contains("LM01");
        Files.writeString(dir.resolve("review.properties"), "lenses = +maintainability\n");
        assertThat(rules(json(SLOPPY))).contains("LM01");
        assertThat(json(SLOPPY).get("summary").get("lenses").get("off"))
                .extracting(JsonNode::asText)
                .doesNotContain("maintainability");
    }

    @Test
    void aRuleCanBeTurnedOffAndItsSeverityChangedFromTheSettingsFile() throws Exception {
        Files.writeString(dir.resolve("review.properties"), "LR01 = off\nLR03.severity = high\n");
        JsonNode report = json(SLOPPY);
        assertThat(rules(report)).doesNotContain("LR01").contains("LR03");
        for (JsonNode f : report.get("findings"))
            if (f.get("rule").asText().equals("LR03"))
                assertThat(f.get("severity").asText()).isEqualTo("high");
    }

    @Test
    void everyRuleOfTheCorePackCanBeExplainedWithItsPackAndOptionalLensNote() throws Exception {
        assertThat(run(SLOPPY, "--explain", "LR01")).isZero();
        assertThat(out.toString())
                .contains("LR01")
                .contains("Lens: Reliability")
                .contains("Severity: medium")
                .contains("From: pack core@1.0.0")
                .contains("Why it matters")
                .contains("What to do");
        out.reset();
        assertThat(run(SLOPPY, "--explain", "lm01")).isZero();
        assertThat(out.toString()).contains("optional lens").contains("--lens +maintainability");
        for (var rule : io.github.llm4j.loom.review.Reviews.corePack().rules()) {
            out.reset();
            assertThat(run(SLOPPY, "--explain", rule.id())).as(rule.id()).isZero();
        }
    }

    @Test
    void reviewingEveryExampleScriptNeverCrashesAndAlwaysProducesTheDocumentedJson()
            throws Exception {
        java.nio.file.Path examples = java.nio.file.Path.of("../../examples");
        int reviewed = 0;
        try (var walk = Files.walk(examples, 3)) {
            for (java.nio.file.Path p :
                    walk.filter(x -> x.toString().endsWith(".loom")).sorted().toList()) {
                ReviewCommand c = new ReviewCommand();
                new CommandLine(c).parseArgs(p.toString(), "--format", "json", "--no-env");
                out.reset();
                err.reset();
                int code = ReviewCommand.review(c, env());
                if (code == 2)
                    continue; // a script that cannot be read is exit 2, which is the documented
                // answer
                assertThat(code).as(p.toString()).isZero();
                assertThat(new ObjectMapper().readTree(out.toString()).get("version").asInt())
                        .isEqualTo(1);
                reviewed++;
            }
        }
        assertThat(reviewed).isGreaterThan(3);
    }

    @Test
    void aMissingOrBrokenLootFileIsExitTwoAndAValidOneIsUsedForReach() throws Exception {
        ReviewCommand missing = command(RISKY);
        missing.lootFile = new File(dir.toFile(), "nope.loot");
        assertThat(ReviewCommand.review(missing, env())).isEqualTo(2);
        assertThat(err.toString()).contains("does not exist");
        err.reset();
        Path loot = dir.resolve("tools.loot");
        Files.writeString(loot, "Mailer = com.acme.MailTool\nMailer.reach = nonsense\n");
        ReviewCommand broken = command(RISKY);
        broken.lootFile = loot.toFile();
        assertThat(ReviewCommand.review(broken, env())).isEqualTo(2);
        assertThat(err.toString()).contains("nonsense");
        err.reset();
        Files.writeString(loot, "Mailer = com.acme.MailTool\nMailer.reach = sends\n");
        ReviewCommand fine =
                command(
                        "agent A { model: \"m\"  system: \"s\"  tools: [Mailer] }\nworkflow Main(q) { delegate \"{q}\" to A -> r  note \"{r}\" }\n",
                        "--format",
                        "json");
        fine.lootFile = loot.toFile();
        assertThat(ReviewCommand.review(fine, env())).isZero();
        assertThat(rules(new ObjectMapper().readTree(out.toString()))).contains("LD02");
    }

    @Test
    void anUnwritableOutFileIsExitTwoAfterTheReportWasShown() throws Exception {
        assertThat(run(RISKY, "--out", dir.toString()))
                .isEqualTo(2); // a folder cannot be written as a file
        assertThat(err.toString()).contains("could not write");
        assertThat(out.toString()).contains("LA01");
    }

    @Test
    void theCommandRunsThroughPicocliWithTheRealEnvironment() throws Exception {
        java.io.PrintStream real = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true));
        try {
            int code = WeaveCLI.commandLine().execute("review", "--explain", "LA01");
            assertThat(code).isZero();
        } finally {
            System.setOut(real);
        }
        assertThat(captured.toString()).contains("lethal trifecta");
        Path f = dir.resolve("w.loom");
        Files.writeString(f, CALM);
        captured.reset();
        System.setOut(new PrintStream(captured, true));
        try {
            assertThat(
                            WeaveCLI.commandLine()
                                    .execute(
                                            "review", f.toString(), "--no-env", "--format", "json"))
                    .isZero();
        } finally {
            System.setOut(real);
        }
        assertThat(captured.toString()).contains("\"version\" : 1");
    }

    @Test
    void theCommandIsRegisteredInTheWeaveCli() {
        CommandLine weave = WeaveCLI.commandLine();
        assertThat(weave.getSubcommands()).containsKey("review");
    }
}
