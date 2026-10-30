package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.loom.loop.StoreReader;
import io.github.llm4j.loom.loop.Workflows;
import io.github.llm4j.loom.travel.ScriptHash;
import io.github.llm4j.loom.wtest.CaseProbe;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** R16, R31 to R35 of spec 14: the gate's stages, skips, git refs, formats and templates. */
class GateCommandTest {

    @TempDir Path dir;

    record Out(int code, String out, String err) {}

    static final String SCRIPT =
            """
            agent Replier { model: "gemini-2.5-flash"  system: "Answer the customer." }
            agent Escalator { model: "gemini-2.5-flash"  system: "Summarise the case." }
            intentclassifier Intent {
                model: "gemini-2.5-flash"
                choose kind: order_status | refund | other
                ask urgent: "Is the customer threatening to leave?"
                samples: 3
            }
            workflow Main(message) {
                intentclassify Intent on message -> intent
                alt (intent.kind == "order_status") {
                    delegate "Draft an answer for: {message}" to Replier -> draft_text
                    human_prompt "Send this answer? (yes/no)" -> approval
                    alt (approval == "yes") {
                        return draft_text
                    } else {
                        return "not sent"
                    }
                } else {
                    delegate "Summarise: {message}" to Escalator -> summary
                    return summary
                }
            }
            """;

    /** The same, with nobody asked. */
    static final String WITHOUT_PERSON =
            SCRIPT.replace(
                    "human_prompt \"Send this answer? (yes/no)\" -> approval\n"
                            + "        alt (approval == \"yes\") {\n"
                            + "            return draft_text\n"
                            + "        } else {\n"
                            + "            return \"not sent\"\n"
                            + "        }\n",
                    "return draft_text\n");

    static final String TESTS =
            """
            script: ../main.loom
            workflow: Main
            cases:
              - name: refunds go to a person
                input: { message: "money back" }
                models:
                  Intent: { kind: refund, urgent: "yes" }
                  Escalator: "A refund request."
                expect:
                  result: "A refund request."
            """;

    private Out gate(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        WeaveEnv env =
                new WeaveEnv(
                        m -> {
                            throw new IllegalStateException("the gate calls no model");
                        },
                        q -> {
                            throw new IllegalStateException("nobody to ask");
                        },
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(err, true, StandardCharsets.UTF_8),
                        Clock.systemUTC(),
                        d -> {},
                        c ->
                                new io.github.llm4j.loom.trigger.system.CommandRunner.Result(
                                        0, "", ""),
                        List.of("weave"),
                        k -> null);
        GateCommand c = new GateCommand();
        new CommandLine(c).parseArgs(args);
        int code = GateCommand.gate(c, env);
        return new Out(
                code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private Path project() throws Exception {
        Path p = Files.createDirectories(dir.resolve("repo"));
        Files.writeString(p.resolve("main.loom"), SCRIPT);
        Files.createDirectories(p.resolve("tests"));
        Files.writeString(p.resolve("tests/main.test.yaml"), TESTS);
        return p.resolve("main.loom");
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> cmd =
                new ArrayList<>(
                        List.of("git", "-c", "user.email=t@example.com", "-c", "user.name=t"));
        cmd.addAll(List.of(args));
        Process p =
                new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertThat(p.waitFor()).as(String.join(" ", cmd)).isZero();
    }

    private Path runInto(Path store, Path script, String id, String kind) throws Exception {
        var mapper = new ObjectMapper();
        var body = mapper.createObjectNode();
        body.putObject("input").put("message", "m " + id);
        if (kind.equals("order_status")) body.putArray("humans").add("yes");
        var m = body.putObject("models");
        m.putObject("Intent").put("kind", kind).put("urgent", "no");
        m.put("Replier", "On its way.");
        m.put("Escalator", "A person should look.");
        body.put("name", "x");
        var result = CaseProbe.run(script, "Main", body);
        Path run = store.resolve("runs").resolve(id);
        Files.createDirectories(run);
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("script", script.toString());
        spec.put("workflow", "Main");
        spec.put("inputs", Map.of("message", "m " + id));
        spec.put("scriptHash", ScriptHash.of(script));
        mapper.writeValue(run.resolve("run.json").toFile(), spec);
        mapper.writeValue(run.resolve("journal.json").toFile(), result.journal());
        return run;
    }

    @Test
    void eachStageSaysPassFailOrSkippedAndASkipIsVisibleNotSilent() throws Exception {
        Path script = project();
        Out o = gate(script.toString());
        assertThat(o.code()).as(o.err() + o.out()).isZero();
        assertThat(o.out())
                .contains("check")
                .contains("PASS")
                .contains("regress")
                .contains("SKIPPED")
                .contains("no run store is configured")
                .contains("eval")
                .contains("PASSED");
        // --require turns the skip into a failure, and says how to reproduce
        Out strict = gate(script.toString(), "--require", "regress");
        assertThat(strict.code()).isEqualTo(1);
        assertThat(strict.out())
                .contains("SKIPPED (required)")
                .contains("reproduce: weave regress")
                .contains("FAILED: regress");
    }

    @Test
    void aFailingStageIsNamedWithTheOneCommandThatReproducesIt() throws Exception {
        Path script = project();
        Files.writeString(script, SCRIPT.replace("to Escalator", "to Nobody"));
        Out o = gate(script.toString());
        assertThat(o.code()).isEqualTo(1);
        assertThat(o.out())
                .contains("FAILED")
                .contains("reproduce: weave check main.loom --no-env --strict");
    }

    @Test
    void onlyApprovedCapturedCasesCanFailTheGate() throws Exception {
        Path script = project();
        String captured =
                """
                captured:
                  from: run r1
                  status: %s
                script: ../../main.loom
                workflow: Main
                cases:
                  - name: captured r1
                    input: { message: "x" }
                    models:
                      Intent: { kind: refund, urgent: "no" }
                      Escalator: "A person."
                    expect:
                      result: "something the script does not say"
                """;
        Files.createDirectories(script.getParent().resolve("tests/captured"));
        Path file = script.getParent().resolve("tests/captured/c.test.yaml");
        Files.writeString(file, captured.formatted("observed"));
        Out observed = gate(script.toString());
        assertThat(observed.code()).as(observed.out()).isZero();
        assertThat(gate(script.toString(), "--include-observed").code()).isEqualTo(1);
        Files.writeString(file, captured.formatted("approved"));
        Out approved = gate(script.toString());
        assertThat(approved.code()).isEqualTo(1);
    }

    @Test
    void pullRequestRefsMakeTheBaseTheBaselineListChangedFilesAndFailAnUnsafeChange()
            throws Exception {
        Path script = project();
        Path repo = script.getParent();
        git(repo, "init", "-q");
        git(repo, "add", ".");
        git(repo, "commit", "-q", "-m", "base");
        Path store = dir.resolve("store");
        runInto(store, script, "a1", "order_status");
        runInto(store, script, "a2", "order_status");
        // the change: nobody is asked any more
        Files.writeString(script, WITHOUT_PERSON);
        git(repo, "commit", "-q", "-am", "drop the person");
        Files.writeString(
                repo.resolve("gate.properties"), "regress.store = " + store + "\nreview = off\n");
        Out o = gate(script.toString(), "--base-ref", "HEAD~1", "--candidate-ref", "HEAD");
        assertThat(o.out()).contains("Changed files:").contains("main.loom");
        assertThat(o.code()).as(o.out()).isEqualTo(1);
        assertThat(o.out()).contains("regress").contains("FAIL").contains("UNSAFE FLIPS");
        // read-only: the repository and the store are as they were
        String before = StoreReader.fingerprint(store);
        gate(script.toString(), "--base-ref", "HEAD~1", "--candidate-ref", "HEAD");
        assertThat(StoreReader.fingerprint(store)).isEqualTo(before);
        assertThat(Files.readString(script)).doesNotContain("human_prompt");
    }

    @Test
    void formatsAreForPeopleForPullRequestCommentsForCiAndForCodeScanning() throws Exception {
        Path script = project();
        assertThat(gate(script.toString(), "--format", "md").out())
                .contains("### Gate")
                .contains("| stage |");
        Out junit = gate(script.toString(), "--format", "junit");
        assertThat(junit.out()).contains("<testsuite").contains("<skipped");
        Out sarif = gate(script.toString(), "--format", "sarif");
        assertThat(new ObjectMapper().readTree(sarif.out()).get("version").asText())
                .isEqualTo("2.1.0");
    }

    @Test
    void theWorkflowTemplatesParseAndNameRealCommandsAndOptions() throws Exception {
        for (String kind : List.of("github", "jenkins")) {
            Out o = gate("--print-workflow", kind);
            assertThat(o.code()).isZero();
            assertThat(o.out()).contains("weave").contains("gate");
        }
        var yaml =
                new com.fasterxml.jackson.dataformat.yaml.YAMLMapper().readTree(Workflows.github());
        assertThat(yaml.get("jobs").get("gate").get("steps")).isNotEmpty();
        // every option the templates use exists on the command
        String all = Workflows.github() + Workflows.jenkins();
        for (String option : List.of("--base-ref", "--format"))
            if (all.contains(option))
                assertThat(new CommandLine(new GateCommand()).getCommandSpec().findOption(option))
                        .isNotNull();
        assertThat(gate("--print-workflow", "bogus").code()).isEqualTo(1);
    }

    @Test
    void aMistypedConfigurationKeyIsRefusedBeforeAnythingRuns() throws Exception {
        Path script = project();
        Files.writeString(script.getParent().resolve("gate.properties"), "chek = true\n");
        Out o = gate(script.toString());
        assertThat(o.code()).isEqualTo(2);
        assertThat(o.err()).contains("unknown key \"chek\"");
        assertThat(gate(script.toString(), "--require", "bogus").code()).isEqualTo(1);
        assertThat(gate(dir.resolve("none.loom").toString()).code()).isEqualTo(2);
    }

    @Test
    void theEvalStageRunsAProtocolAndNamesItInTheReproduceLine() throws Exception {
        Path script = project();
        Files.writeString(script.getParent().resolve("gate.properties"), "eval = true\nreview = off\n");

        Out o = gate(script.toString(), "--protocol", "release");

        assertThat(o.code()).isEqualTo(1);
        assertThat(o.out()).contains("no protocol named release").contains("reproduce: weave eval").contains("--protocol release");
    }
}
