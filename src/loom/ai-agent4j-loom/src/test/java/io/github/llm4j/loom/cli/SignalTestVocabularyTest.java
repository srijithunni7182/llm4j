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

/**
 * Phase 34c, D15 (principle 22): {@code signals:} scripts a signal's arrival on the virtual clock, a
 * scripted child can send a signal, and {@code expect: signals:} says what was sent, read and timed
 * out. A workflow that talks to its children is tested with no model, no real clock and no racing.
 */
class SignalTestVocabularyTest {

    @TempDir Path dir;

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();

    static final String SCRIPT =
            """
            substitution: braces
            agent Researcher { model: "gemini-2.5-flash" system: "Research." }
            workflow Research(topic) {
                delegate "Find {topic}" to Researcher -> notes
                return notes
            }
            workflow Collect(q) {
                start Research(topic = q) budget { tokens: 100000 } -> work
                wait work signal "stuck" timeout 3m -> why
                alt (why is ok) { set seen = why } else { set seen = "no word" }
                wait work timeout 20m -> findings
                return seen
            }
            workflow Tell(q) {
                start Research(topic = q) budget { tokens: 100000 } -> work
                notify work "go" "use the 2025 data"
                notify work "approved" "yes" sticky
                wait work timeout 20m -> findings
                return findings
            }
            """;

    int run(String tests, String... args) throws Exception {
        Files.writeString(dir.resolve("main.loom"), SCRIPT);
        Path t = Files.createDirectories(dir.resolve("tests"));
        Files.writeString(t.resolve("main.test.yaml"), tests);
        TestCommand c = new TestCommand();
        List<String> all = new ArrayList<>(List.of(dir.resolve("main.loom").toString()));
        all.addAll(List.of(args));
        new CommandLine(c).parseArgs(all.toArray(new String[0]));
        out.reset();
        err.reset();
        WeaveEnv env =
                new WeaveEnv(
                        m -> null, q -> "", new PrintStream(out, true), new PrintStream(err, true), Clock.systemUTC(), d -> {},
                        cmd -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""), List.of("weave"), k -> null);
        return TestCommand.run(c, env);
    }

    private static String cases(String workflow, String body) {
        return "script: ../main.loom\nworkflow: " + workflow + "\ncases:\n" + body;
    }

    @Test
    void aScriptedChildSendsASignalAndTheParentReadsItAndTheCaseSaysSo() throws Exception {
        int code =
                run(
                        cases(
                                "Collect",
                                """
                                  - name: a scripted child says it is stuck
                                    input: { q: "peru" }
                                    children:
                                      Research:
                                        - { signal: stuck, text: "no reliable sources", after: 1m }
                                        - { state: completed, result: "x", after: 1m }
                                    expect:
                                      result: { contains: "no reliable sources" }
                                      signals: { received: [ stuck ], sent: [] }
                                      children: { Research: { status: completed } }
                                """));

        assertThat(code).as(out + "\n" + err).isZero();
    }

    @Test
    void aSignalScriptedToArriveOnTheVirtualClockIsReadWhenItsTimeComes() throws Exception {
        int code =
                run(
                        cases(
                                "Collect",
                                """
                                  - name: it arrives at two minutes, before the three minute timeout
                                    input: { q: "peru" }
                                    children:
                                      Research:
                                        - { state: working, after: 5m }
                                        - { state: completed, result: "x", after: 1m }
                                    signals:
                                      work: [ { at: 2m, name: stuck, text: "late news" } ]
                                    expect:
                                      result: { contains: "late news" }
                                      signals: { received: [ stuck ] }
                                  - name: it arrives at four minutes, after the timeout, so the parent heard nothing
                                    input: { q: "peru" }
                                    children:
                                      Research:
                                        - { state: working, after: 5m }
                                        - { state: completed, result: "x", after: 1m }
                                    signals:
                                      work: [ { at: 4m, name: stuck, text: "too late" } ]
                                    expect:
                                      result: { contains: "no word" }
                                      signals: { received: [], timed_out: [ stuck ] }
                                """));

        assertThat(code).as(out + "\n" + err).isZero();
    }

    @Test
    void whatTheParentSentIsInTheCase() throws Exception {
        int code =
                run(
                        cases(
                                "Tell",
                                """
                                  - name: the parent signals a child
                                    input: { q: "peru" }
                                    children:
                                      Research:
                                        - { state: completed, result: "done", after: 1m }
                                    expect:
                                      signals: { sent: [ go, approved ] }
                                  - name: a wrong expectation fails the case
                                    input: { q: "peru" }
                                    children:
                                      Research:
                                        - { state: completed, result: "done", after: 1m }
                                    expect:
                                      signals: { sent: [ other ] }
                                """));

        assertThat(code).as("the second case must fail").isNotZero();
        assertThat(out + "\n" + err).contains("signals.sent");
    }

    private String check(String extra, String expect) throws Exception {
        int code =
                run(cases("Collect", "  - name: c\n    input: { q: \"x\" }\n" + extra + "    expect:\n" + expect), "--check");
        assertThat(code).as("a bad file is refused before anything runs").isNotZero();
        return out + "\n" + err;
    }

    @Test
    void theCheckerRefusesABadSignalsEntry() throws Exception {
        assertThat(check("    signals:\n      work: [ { at: soon, name: stuck } ]\n", "      result: { contains: \"x\" }\n")).contains("at").contains("duration");
        assertThat(check("    signals:\n      work: [ { at: 1m, name: \"bad name\" } ]\n", "      result: { contains: \"x\" }\n")).contains("plain word");
        assertThat(check("    signals:\n      work: [ { at: 1m } ]\n", "      result: { contains: \"x\" }\n")).contains("name");
        assertThat(check("    signals:\n      work: { at: 1m }\n", "      result: { contains: \"x\" }\n")).contains("list");
    }

    @Test
    void theCheckerRefusesABadSignalStepAndABadSignalsExpectation() throws Exception {
        assertThat(check("    children:\n      Research:\n        - { signal: \"bad name\", after: 1m }\n        - { state: completed, result: x }\n", "      result: { contains: \"x\" }\n"))
                .contains("plain word");
        assertThat(check("", "      signals: { sentt: [ go ] }\n")).contains("sentt").contains("sent");
        assertThat(check("", "      signals: [ go ]\n")).contains("signals");
    }
}
