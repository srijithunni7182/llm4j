package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

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

/** R26, R28 of spec 11: the command, its exit codes and what it prints. */
class TestCommandTest {

    @TempDir Path dir;

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();

    static final String TRIAGE =
            """
            agent Replier { model: "gemini-2.5-flash"  system: "Answer politely." }
            agent Escalator { model: "gemini-2.5-flash"  system: "Summarise." }
            intentclassifier Intent {
                model: "gemini-2.5-flash"
                choose kind: order_status | refund | other
                ask urgent: "Is the customer threatening to leave?"
            }
            workflow Main(message) {
                intentclassify Intent on message -> intent
                alt (intent.kind == "order_status") {
                    delegate "Reply to: {message}" to Replier -> reply
                    note "replied"
                    return reply
                } else {
                    delegate "Summarise: {message}" to Escalator -> summary
                    return summary
                }
            }
            """;

    static final String PASSING =
            """
            script: ../main.loom
            workflow: Main
            cases:
              - name: order status gets a reply
                input: { message: "where is my parcel?" }
                models:
                  Intent: { kind: order_status, urgent: "no" }
                  Replier: "Your parcel shipped yesterday."
                expect:
                  result: { contains: shipped }
                  route: [ classify Intent, delegate Replier ]
                  calls: { Escalator: 0 }
              - name: refunds go to a person
                input: { message: "money back" }
                models:
                  Intent: { kind: refund, urgent: "yes" }
                  Escalator: "A refund request."
                expect:
                  result: "A refund request."
            """;

    int run(String tests, String... args) throws Exception {
        Files.writeString(dir.resolve("main.loom"), TRIAGE);
        Path t = Files.createDirectories(dir.resolve("tests"));
        if (tests != null) Files.writeString(t.resolve("main.test.yaml"), tests);
        TestCommand c = new TestCommand();
        List<String> all = new ArrayList<>(List.of(dir.resolve("main.loom").toString()));
        all.addAll(List.of(args));
        new CommandLine(c).parseArgs(all.toArray(new String[0]));
        out.reset();
        err.reset();
        WeaveEnv env =
                new WeaveEnv(
                        m -> null,
                        q -> "",
                        new PrintStream(out, true),
                        new PrintStream(err, true),
                        Clock.systemUTC(),
                        d -> {},
                        cmd ->
                                new io.github.llm4j.loom.trigger.system.CommandRunner.Result(
                                        0, "", ""),
                        List.of("weave"),
                        k -> null);
        return TestCommand.run(c, env);
    }

    @Test
    void passingCasesExitZeroAndEndWithASummary() throws Exception {
        assertThat(run(PASSING)).isZero();
        assertThat(out.toString())
                .contains("order status gets a reply")
                .contains("refunds go to a person")
                .containsPattern("2 passed, 0 failed in \\d+ (ms|\\.\\d s)");
        assertThat(out.toString()).doesNotContain("FAIL");
    }

    @Test
    void aFailingCaseExitsOneAndShowsWhatDifferedWithoutOpeningTheYaml() throws Exception {
        String failing =
                PASSING.replace("result: { contains: shipped }", "result: { contains: refunded }");
        assertThat(run(failing)).isEqualTo(1);
        String text =
                out.toString()
                        .replaceAll("\\(\\d+ ms\\)", "(N ms)")
                        .replaceAll("in \\d+(\\.\\d)? (ms|s)", "in T");
        assertThat(text)
                .contains("FAIL  order status gets a reply  (N ms)")
                .contains(
                        "- result: expected text containing \"refunded\", got \"Your parcel shipped yesterday.\"")
                .contains("ok    refunds go to a person  (N ms)")
                .contains("1 passed, 1 failed in T");
    }

    @Test
    void aFileThatDoesNotFitTheScriptExitsTwoAndRunsNothing() throws Exception {
        assertThat(run(PASSING.replace("Replier:", "Replyer:"))).isEqualTo(2);
        assertThat(err.toString())
                .contains("'Replyer' is not an agent")
                .contains("nothing was run");
        assertThat(out.toString()).isEmpty();
    }

    @Test
    void noTestsAtAllIsAnErrorNotAPass() throws Exception {
        assertThat(run(null)).isEqualTo(2);
        assertThat(err.toString()).contains("no test cases found");
    }

    @Test
    void aScriptThatDoesNotCheckFailsBeforeAnyCase() throws Exception {
        Files.writeString(
                dir.resolve("main.loom"),
                "workflow Main(message) { delegate \"x\" to Nobody -> r }");
        Files.createDirectories(dir.resolve("tests"));
        Files.writeString(dir.resolve("tests/main.test.yaml"), PASSING);
        TestCommand c = new TestCommand();
        new CommandLine(c).parseArgs(new String[] {dir.resolve("main.loom").toString()});
        WeaveEnv env =
                new WeaveEnv(
                        m -> null,
                        q -> "",
                        new PrintStream(out, true),
                        new PrintStream(err, true),
                        Clock.systemUTC(),
                        d -> {},
                        cmd ->
                                new io.github.llm4j.loom.trigger.system.CommandRunner.Result(
                                        0, "", ""),
                        List.of("weave"),
                        k -> null);
        assertThat(TestCommand.run(c, env)).isEqualTo(2);
        assertThat(err.toString()).contains("Nobody");
    }

    @Test
    void checkValidatesOnlyAndRunsNothing() throws Exception {
        assertThat(run(PASSING, "--check")).isZero();
        assertThat(out.toString()).contains("2 case(s) in 1 file(s) are sound.");
        assertThat(run(PASSING.replace("Replier:", "Replyer:"), "--check")).isEqualTo(2);
    }

    @Test
    void stepsListsWhatATestCanNameWithIdsAndLines() throws Exception {
        assertThat(run(PASSING, "--steps")).isZero();
        assertThat(out.toString())
                .contains("Main:")
                .containsPattern("n1\\s+line 9\\s+classify Intent")
                .contains("delegate Replier")
                .contains("delegate Escalator");
    }

    @Test
    void showCasePrintsTheMergedCase() throws Exception {
        String withDefaults = PASSING.replace("cases:", "defaults:\n  tags: [ smoke ]\ncases:");
        assertThat(run(withDefaults, "--show-case", "refunds go to a person")).isZero();
        assertThat(out.toString())
                .contains("tags:")
                .contains("smoke")
                .contains("A refund request.");
        assertThat(run(withDefaults, "--show-case", "nope")).isEqualTo(2);
    }

    @Test
    void caseSelectsByNameOrGlobAndNoMatchIsAnError() throws Exception {
        assertThat(run(PASSING, "--case", "refunds*")).isZero();
        assertThat(out.toString()).contains("1 passed").doesNotContain("order status");
        assertThat(run(PASSING, "--case", "zzz")).isEqualTo(2);
        assertThat(err.toString()).contains("no case matches zzz");
    }

    @Test
    void failFastStopsAtTheFirstFailure() throws Exception {
        String failing = PASSING.replace("result: { contains: shipped }", "result: nope");
        assertThat(run(failing, "--fail-fast")).isEqualTo(1);
        assertThat(out.toString()).contains("1 failed").doesNotContain("refunds go to a person");
    }

    @Test
    void lenientRunsAnUnscriptedCaseAndSaysSo() throws Exception {
        String loose =
                "script: ../main.loom\nworkflow: Main\ncases:\n  - name: loose\n    input: { message: x }\n    models:\n      Intent: { kind: order_status, urgent: 'no' }\n";
        assertThat(run(loose)).isEqualTo(1);
        assertThat(run(loose, "--lenient")).isZero();
        assertThat(out.toString()).contains("~ lenient: agent Replier has no scripted answer");
    }

    @Test
    void theCommandIsRegisteredAndHasNoNeedOfKeys() {
        CommandLine cli = WeaveCLI.commandLine();
        assertThat(cli.getSubcommands()).containsKey("test");
    }
}
