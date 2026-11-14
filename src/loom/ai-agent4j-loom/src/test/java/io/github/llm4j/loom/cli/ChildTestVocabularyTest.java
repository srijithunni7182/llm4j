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

/** Phase 33, D17: {@code weave test} runs real children under scripted models and a virtual clock. */
class ChildTestVocabularyTest {

    @TempDir Path dir;

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();

    static final String SCRIPT =
            """
            evaluator TopicJudge { model: "gemini-2.5-flash" }
            agent Researcher { model: "gemini-2.5-flash" system: "Research." }
            workflow Research(topic) {
                delegate "Find {topic}" to Researcher -> notes
                return notes
            }
            workflow Main(q) {
                start Research(topic = q) budget { tokens: 100000 } -> work
                supervise work { when elapsed > 5m { cancel use partial } }
                sleep 6m
                wait work timeout 1m -> findings
                set seen = work.status
                return seen
            }
            workflow Quick(q) {
                start Research(topic = q) budget { tokens: 100000 } -> work
                wait work timeout 1m -> findings
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
                        m -> null,
                        q -> "",
                        new PrintStream(out, true),
                        new PrintStream(err, true),
                        Clock.systemUTC(),
                        d -> {},
                        cmd -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""),
                        List.of("weave"),
                        k -> null);
        return TestCommand.run(c, env);
    }

    private static final String QUICK =
            """
            script: ../main.loom
            workflow: Quick
            cases:
              - name: a child runs under a scripted model and its result comes back
                input: { q: "peru" }
                models:
                  Researcher: "Lima."
                expect:
                  result: { contains: "Lima" }
                  starts: [ Research ]
                  children: { Research: { status: completed } }
                  fired: []
            """;

    @Test
    void aRealChildRunsUnderScriptedModelsAndTheCaseSaysWhatWasStartedAndHowItEnded() throws Exception {
        assertThat(run(QUICK)).as(out + "\n" + err).isZero();
        assertThat(out.toString()).contains("1 passed, 0 failed");
    }

    @Test
    void aRuleOnRunningTimeFiresOnTheVirtualClockAndTheCaseSeesItsReason() throws Exception {
        String tests =
                """
                script: ../main.loom
                workflow: Main
                cases:
                  - name: a slow child is cancelled by its supervisor
                    input: { q: "peru" }
                    models:
                      Researcher: "Lima."
                    expect:
                      result: cancelled
                      starts: 1
                      children: { Research: { status: cancelled, reason: "supervise rule 0" } }
                      fired: [ { rule: 0, action: cancel } ]
                      steers: { delivered: 0, not_delivered: 0 }
                      elapsed_virtual: 6m
                """;

        assertThat(run(tests)).as(out + "\n" + err).isZero();
    }

    @Test
    void aWrongExpectationIsSaidInTheCasesOwnWords() throws Exception {
        assertThat(run(QUICK.replace("fired: []", "fired: [ { rule: 0, action: cancel } ]"))).isEqualTo(1);
        assertThat(out.toString()).contains("fired: expected [0:cancel]");
    }

    @Test
    void aNameThatIsNotInTheScriptIsRefusedBeforeAnythingRuns() throws Exception {
        assertThat(run(QUICK.replace("children: { Research:", "children: { Researchh:"))).isEqualTo(2);
        assertThat(err.toString()).contains("'Researchh' is not a workflow of the script");
        assertThat(run(QUICK.replace("    models:", "    judges: { Nobody: [pass] }\n    models:"))).isEqualTo(2);
        assertThat(err.toString()).contains("'Nobody' is not an evaluator of the script");
    }
}
