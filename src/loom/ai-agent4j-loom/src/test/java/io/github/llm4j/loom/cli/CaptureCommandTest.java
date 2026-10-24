package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.loom.travel.ScriptHash;
import io.github.llm4j.loom.wtest.CaseProbe;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * R6, R8, R10, R12, R13, R14, R15 of spec 14 through the command, over a hostile run: every surface
 * is searched for every seeded value.
 */
class CaptureCommandTest {

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
            }
            workflow Main(message) {
                intentclassify Intent on message -> intent
                alt (intent.kind == "order_status") {
                    delegate "Reply to: {message}" to Replier -> reply
                    human_prompt "Send this reply? (yes/no)" -> ok
                    return reply
                } else {
                    delegate "Summarise: {message}" to Escalator -> summary
                    return summary
                }
            }
            """;

    private Out capture(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        WeaveEnv env =
                new WeaveEnv(
                        m -> {
                            throw new IllegalStateException("capture calls no model");
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
        CaptureCommand c = new CaptureCommand();
        new CommandLine(c).parseArgs(args);
        int code = CaptureCommand.capture(c, env);
        return new Out(
                code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    /** A hostile run in a repository: a customer who typed everything a customer could. */
    private Path hostileRun() throws Exception {
        Path repo = Files.createDirectories(dir.resolve("repo"));
        Files.createDirectories(repo.resolve(".git"));
        Path script = repo.resolve("main.loom");
        Files.writeString(script, SCRIPT);
        var mapper = new ObjectMapper();
        var body = mapper.createObjectNode();
        String message = io.github.llm4j.loom.loop.Hostile.message();
        body.putObject("input").put("message", message);
        body.putArray("humans").add("yes " + io.github.llm4j.loom.loop.Hostile.PERSONAL.get(0));
        var m = body.putObject("models");
        m.putObject("Intent").put("kind", "order_status").put("urgent", "no");
        m.put(
                "Replier",
                "Dear Maria Gonzalez, token " + io.github.llm4j.loom.loop.Hostile.SECRETS.get(0));
        body.put("name", "x");
        var result = CaseProbe.run(script, "Main", body);
        assertThat(result.problems()).isEmpty();
        Path run = repo.resolve(".loom-triggers/runs/r-hostile");
        Files.createDirectories(run);
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("script", script.toString());
        spec.put("workflow", "Main");
        spec.put("inputs", Map.of("message", message));
        spec.put("scriptHash", ScriptHash.of(script));
        mapper.writeValue(run.resolve("run.json").toFile(), spec);
        mapper.writeValue(run.resolve("journal.json").toFile(), result.journal());
        return run;
    }

    @Test
    void firstUseNeedsTheAgreementAndItIsRecordedOnce() throws Exception {
        Path run = hostileRun();
        Out no = capture(run.toString());
        assertThat(no.code()).isEqualTo(2);
        assertThat(no.out()).contains("committed");
        assertThat(no.err()).contains("needs the agreement");
        assertThat(Files.exists(dir.resolve("repo/.weave/capture.consent"))).isFalse();
        Out yes = capture(run.toString(), "--yes");
        assertThat(yes.code()).as(yes.err() + yes.out()).isZero();
        assertThat(Files.exists(dir.resolve("repo/.weave/capture.consent"))).isTrue();
        // the second time there is nothing to agree to
        Out again = capture(run.toString());
        assertThat(again.code()).isZero();
        assertThat(again.out()).contains("Already captured").doesNotContain("committed");
    }

    @Test
    void noSeededValueReachesAFileTheOutputOrTheErrorStream() throws Exception {
        Path run = hostileRun();
        List<String> terms = io.github.llm4j.loom.loop.Hostile.TERMS;
        Out o =
                capture(
                        run.toString(),
                        "--yes",
                        "--mask-term",
                        terms.get(0),
                        "--mask-term",
                        terms.get(1),
                        "--note",
                        "a message from " + terms.get(0));
        assertThat(o.code()).as(o.err()).isZero();
        StringBuilder everything = new StringBuilder(o.out()).append(o.err());
        try (var files = Files.walk(dir.resolve("repo"))) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                // the run's own journal and spec are the store, which capture only reads
                if (f.toString().contains(".loom-triggers")) continue;
                everything.append(Files.readString(f));
            }
        }
        for (String v : io.github.llm4j.loom.loop.Hostile.mustNotSurvive())
            assertThat(everything.toString()).as("survivor: " + v).doesNotContain(v);
    }

    @Test
    void aDryRunPrintsTheCaseAndWritesNothingNotEvenTheAgreement() throws Exception {
        Path run = hostileRun();
        Out o = capture(run.toString(), "--dry-run");
        assertThat(o.code()).isZero();
        assertThat(o.out()).contains("captured:").contains("final: completed");
        assertThat(Files.exists(dir.resolve("repo/tests"))).isFalse();
        assertThat(Files.exists(dir.resolve("repo/.weave"))).isFalse();
    }

    @Test
    void theStoreIsListedAndASelectionCapturesTheNewestOfAClass() throws Exception {
        Path run = hostileRun();
        Out none = capture("--list", "--store", dir.resolve("repo/.loom-triggers").toString());
        assertThat(none.code()).isZero();
        assertThat(none.out()).contains("No run");
        Out slow =
                capture(
                        "--select",
                        "slow",
                        "--store",
                        dir.resolve("repo/.loom-triggers").toString());
        assertThat(slow.code()).isEqualTo(2);
        assertThat(slow.err()).contains("keeps what happened");
        assertThat(run).exists();
    }

    @Test
    void approvingAndRetiringGoThroughTheSameCommandWithWhoAndWhy() throws Exception {
        Path run = hostileRun();
        Out c = capture(run.toString(), "--yes");
        assertThat(c.code()).isZero();
        Path file;
        try (var files = Files.walk(dir.resolve("repo/tests"))) {
            file = files.filter(f -> f.toString().endsWith(".test.yaml")).findFirst().orElseThrow();
        }
        Out a =
                capture(
                        "--approve",
                        file.toString(),
                        "--by",
                        "ana@example.com",
                        "--why",
                        "checked");
        assertThat(a.code()).as(a.err()).isZero();
        assertThat(Files.readString(file)).contains("status: approved").contains("ana@example.com");
        Out r = capture("--retire", file.toString(), "--by", "ana@example.com");
        assertThat(r.code()).isZero();
        assertThat(Files.readString(file)).contains("status: retired");
    }

    @Test
    void usageErrorsExplain() {
        assertThat(capture().code()).isEqualTo(1);
        assertThat(capture("x", "--expect", "bogus").code()).isEqualTo(1);
        assertThat(capture("x", "--mask", "bogus").code()).isEqualTo(1);
    }

    @Test
    void aSelectionCapturesEachRunOfTheClassAsItsOwnCase() throws Exception {
        Path repo = Files.createDirectories(dir.resolve("repo2"));
        Files.createDirectories(repo.resolve(".git"));
        Path script = repo.resolve("main.loom");
        Files.writeString(script, SCRIPT);
        var mapper = new ObjectMapper();
        for (String id : List.of("u1", "u2")) {
            var body = mapper.createObjectNode();
            body.putObject("input").put("message", "x " + id);
            var m = body.putObject("models");
            m.putObject("Intent").put("kind", "refund").put("urgent", "no").put("confidence", 0.3);
            m.put("Escalator", "ok");
            body.put("name", "x");
            var result = CaseProbe.run(script, "Main", body);
            Path run = repo.resolve(".loom-triggers/runs/" + id);
            Files.createDirectories(run);
            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("script", script.toString());
            spec.put("workflow", "Main");
            spec.put("inputs", Map.of("message", "x " + id));
            spec.put("scriptHash", ScriptHash.of(script));
            mapper.writeValue(run.resolve("run.json").toFile(), spec);
            mapper.writeValue(run.resolve("journal.json").toFile(), result.journal());
        }
        Path store = repo.resolve(".loom-triggers");
        Out listed = capture("--list", "--store", store.toString());
        assertThat(listed.out()).contains("u1").contains("u2").contains("unsure");
        Out o = capture("--select", "unsure", "--limit", "2", "--yes", "--store", store.toString());
        assertThat(o.code()).as(o.err()).isZero();
        try (var files = Files.walk(repo.resolve("tests/captured"))) {
            assertThat(files.filter(f -> f.toString().endsWith(".test.yaml")).count()).isEqualTo(2);
        }
    }
}
