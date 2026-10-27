package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.demo.Scenarios;
import io.github.llm4j.loom.doctor.Doctor;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * R12 to R16 of spec 16: what is wrong, the one thing to do, no secret, and nothing without asking.
 */
class DoctorCommandTest {

    @TempDir Path dir;

    record Out(int code, String out, String err) {}

    private Out doctor(
            Map<String, String> vars, Doctor.Probe probe, String answer, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        WeaveEnv env =
                new WeaveEnv(
                        m -> {
                            throw new IllegalStateException("doctor calls no model");
                        },
                        q -> answer,
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(err, true, StandardCharsets.UTF_8),
                        Clock.systemUTC(),
                        d -> {},
                        c ->
                                new io.github.llm4j.loom.trigger.system.CommandRunner.Result(
                                        0, "", ""),
                        List.of("weave"),
                        k -> null);
        DoctorCommand c = new DoctorCommand();
        new CommandLine(c).parseArgs(args);
        int code = DoctorCommand.run(c, env, dir, vars::get, probe);
        return new Out(
                code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static final Doctor.Probe NEVER =
            (h, p, t) -> {
                throw new AssertionError("no connection without --network");
            };

    private Path project() throws Exception {
        Scenarios.write(Scenarios.find("triage"), dir);
        return dir.resolve("main.loom");
    }

    @Test
    void aReadyProjectSaysSoAndExitsZero() throws Exception {
        project();
        Out r = doctor(Map.of("GEMINI_API_KEY", "set"), NEVER, "no");
        assertThat(r.code()).as(r.out() + r.err()).isZero();
        assertThat(r.out())
                .contains("ok   Java")
                .contains("ok   Project")
                .contains("main.loom checks clean")
                .contains("GEMINI_API_KEY")
                .contains("Ready:");
    }

    @Test
    void aMissingKeyIsNamedWithTheOneActionAndNeverItsValue() throws Exception {
        project();
        String secret = "AIza" + "Sy" + "X".repeat(33);
        Out r = doctor(Map.of("OTHER_KEY", secret), NEVER, "no");
        assertThat(r.code()).isEqualTo(2);
        assertThat(r.out())
                .contains("Not set: GEMINI_API_KEY")
                .contains("→ Export GEMINI_API_KEY")
                .contains("Not ready: 1 failure");
        assertThat(r.out() + r.err()).doesNotContain(secret).doesNotContain("AIza");
        // a set key is reported by name only, whatever its value
        Out ok = doctor(Map.of("GEMINI_API_KEY", secret), NEVER, "no");
        assertThat(ok.out() + ok.err())
                .doesNotContain(secret)
                .doesNotContain("AIza")
                // a whole number only: the temp-dir name in the Store line is random digits
                .doesNotContainPattern("\\b" + secret.length() + "\\b");
    }

    @Test
    void anUnknownModelGetsASuggestion() throws Exception {
        Files.writeString(
                dir.resolve("main.loom"),
                "agent A { model: \"gemini-2.5-flas\"  system: \"s\" }\nagent B { model: \"claud-sonnet-4\"  system: \"s\" }\nworkflow Main(q) { delegate \"{q}\" to B -> r  note \"{r}\" }\n");
        Out r = doctor(Map.of(), NEVER, "no");
        assertThat(r.out())
                .contains("FAIL Models")
                .contains("does not know the model \"claud-sonnet-4\"")
                .contains("Did you mean `claude-sonnet-4-5`?");
    }

    @Test
    void noScriptIsSkippedNotAnError() {
        Out r = doctor(Map.of(), NEVER, "no");
        assertThat(r.code()).isZero();
        assertThat(r.out()).contains("skip Project").contains("weave try").contains("Ready:");
    }

    @Test
    void nothingConnectsUnlessAskedAndThenOnlyToTheScriptsHosts() throws Exception {
        project();
        AtomicInteger calls = new AtomicInteger();
        List<String> hosts = new ArrayList<>();
        Doctor.Probe fake =
                (h, p, t) -> {
                    calls.incrementAndGet();
                    hosts.add(h + ":" + p);
                    return "no connection";
                };
        Out off = doctor(Map.of("GEMINI_API_KEY", "x"), fake, "no");
        assertThat(calls.get()).isZero();
        assertThat(off.out()).contains("skip Network").contains("weave doctor --network");
        Out on =
                doctor(
                        Map.of("GEMINI_API_KEY", "x", "HTTPS_PROXY", "http://user:pw@proxy:3128"),
                        fake,
                        "no",
                        "--network");
        assertThat(hosts).containsExactly("generativelanguage.googleapis.com:443");
        assertThat(on.err())
                .contains("generativelanguage.googleapis.com")
                .contains("no request, no key");
        assertThat(on.code()).isEqualTo(2);
        assertThat(on.out())
                .contains("FAIL Network")
                .contains("proxy in HTTPS_PROXY")
                .doesNotContain("pw@")
                .doesNotContain("proxy:3128");
        assertThat(
                        doctor(Map.of("GEMINI_API_KEY", "x"), (h, p, t) -> null, "no", "--network")
                                .out())
                .contains("ok   Network");
    }

    @Test
    void jsonIsVersionedAndCarriesTheSameChecks() throws Exception {
        project();
        Out r = doctor(Map.of(), NEVER, "no", "--format", "json");
        assertThat(r.out())
                .contains("\"version\" : 1")
                .contains("\"status\" : \"fail\"")
                .contains("\"name\" : \"Keys\"")
                .contains("\"action\"");
    }

    @Test
    void everyProblemIsOneSentenceAndOneAction() throws Exception {
        project();
        Files.writeString(
                dir.resolve(".env.example"),
                "GEMINI_API_KEY=" + "AIza" + "Sy" + "Q".repeat(33) + "\n");
        List<Doctor.Check> all = new ArrayList<>();
        for (Map<String, String> vars :
                List.of(
                        Map.<String, String>of(),
                        Map.of("LOOM_FABRIC_URL", "http://fabric.example:9090"),
                        Map.of("LOOM_FABRIC_URL", "nonsense"))) {
            Out r = doctor(vars, (h, p, t) -> "no connection", "no", "--network");
            assertThat(r.out()).isNotBlank();
        }
        for (Doctor.Check k :
                Doctor.run(
                        new Doctor.Context(
                                dir.resolve("main.loom"),
                                new io.github.llm4j.loom.deploy.DeployPlan(
                                        "main.loom",
                                        List.of("KEY_A"),
                                        List.of(),
                                        false,
                                        List.of(),
                                        false,
                                        List.of(),
                                        List.of("claud")),
                                true,
                                "line 3: boom\nmore",
                                List.of("claud"),
                                v -> null,
                                dir,
                                11,
                                "/a",
                                "/b",
                                "1",
                                Instant.now(),
                                true,
                                (h, p, t) -> "x",
                                List.of("K"))))
            if (k.status() == Doctor.Status.FAIL || k.status() == Doctor.Status.WARN) all.add(k);
        assertThat(all).hasSizeGreaterThanOrEqualTo(3);
        for (Doctor.Check k : all) {
            assertThat(k.reason()).as(k.name()).hasSizeLessThan(200).doesNotContain("\n");
            assertThat(k.action()).as(k.name()).isNotBlank().hasSizeLessThan(200);
        }
    }

    @Test
    void anOldJavaAndAMismatchedJavaHomeAreFlagged() {
        Doctor.Context old =
                new Doctor.Context(
                        null,
                        null,
                        false,
                        null,
                        List.of(),
                        v -> null,
                        dir,
                        11,
                        "/j",
                        null,
                        "1",
                        Instant.now(),
                        false,
                        null,
                        List.of());
        assertThat(Doctor.java(old).status()).isEqualTo(Doctor.Status.FAIL);
        assertThat(Doctor.java(old).reason()).contains("Java 11 is too old");
        Doctor.Context mismatch =
                new Doctor.Context(
                        null,
                        null,
                        false,
                        null,
                        List.of(),
                        v -> null,
                        dir,
                        21,
                        "/usr/lib/jvm/a",
                        "/usr/lib/jvm/b",
                        "1",
                        Instant.now(),
                        false,
                        null,
                        List.of());
        assertThat(Doctor.java(mismatch).status()).isEqualTo(Doctor.Status.WARN);
        assertThat(Doctor.exitCode(List.of(Doctor.java(mismatch)))).isEqualTo(1);
    }

    @Test
    void fabricOverPlainHttpToAnotherMachineIsAWarning() {
        for (String url :
                new String[] {
                    "http://fabric.example:9090",
                    "nonsense",
                    "http://127.0.0.1:9090",
                    "https://fabric.example"
                }) {
            Doctor.Context c =
                    new Doctor.Context(
                            null,
                            null,
                            false,
                            null,
                            List.of(),
                            v -> v.equals("LOOM_FABRIC_URL") ? url : null,
                            dir,
                            21,
                            "/a",
                            null,
                            "1",
                            Instant.now(),
                            false,
                            null,
                            List.of());
            Doctor.Status s = Doctor.fabric(c).status();
            assertThat(s)
                    .as(url)
                    .isEqualTo(
                            url.startsWith("http://fabric")
                                    ? Doctor.Status.WARN
                                    : url.equals("nonsense")
                                            ? Doctor.Status.FAIL
                                            : Doctor.Status.OK);
        }
    }

    @Test
    void oldPricesAreAWarningAndRecentOnesAreFine() throws Exception {
        Files.writeString(dir.resolve("prices.properties"), "gemini-2.5-flash = 0.1,0.4\n");
        Doctor.Context c =
                new Doctor.Context(
                        null,
                        null,
                        false,
                        null,
                        List.of(),
                        v -> null,
                        dir,
                        21,
                        "/a",
                        null,
                        "1",
                        Instant.now(),
                        false,
                        null,
                        List.of());
        assertThat(Doctor.prices(c).status()).isEqualTo(Doctor.Status.OK);
        Doctor.Context later =
                new Doctor.Context(
                        null,
                        null,
                        false,
                        null,
                        List.of(),
                        v -> null,
                        dir,
                        21,
                        "/a",
                        null,
                        "1",
                        Instant.now().plus(Duration.ofDays(120)),
                        false,
                        null,
                        List.of());
        assertThat(Doctor.prices(later).status()).isEqualTo(Doctor.Status.WARN);
    }

    @Test
    void fixAsksFirstAndOnlyDoesTheSafeLocalThings() throws Exception {
        project();
        Files.createDirectories(dir.resolve(".git"));
        Files.writeString(dir.resolve(".env.example"), "GEMINI_API_KEY=<your key>\n");
        Out no = doctor(Map.of("GEMINI_API_KEY", "x"), NEVER, "no", "--fix");
        assertThat(no.out()).contains("Safe local fixes:").contains("Nothing was changed.");
        assertThat(dir.resolve(".loom-triggers")).doesNotExist();
        assertThat(dir.resolve(".env")).doesNotExist();
        Out yes = doctor(Map.of("GEMINI_API_KEY", "x"), NEVER, "no", "--fix", "--yes");
        assertThat(yes.out()).contains("Done.");
        assertThat(dir.resolve(".loom-triggers")).isDirectory();
        assertThat(Files.readString(dir.resolve(".gitignore"))).contains(".env");
        assertThat(Files.readString(dir.resolve(".env"))).isEqualTo("GEMINI_API_KEY=<your key>\n");
        assertThat(doctor(Map.of("GEMINI_API_KEY", "x"), NEVER, "no", "--fix").out())
                .contains("Nothing to fix here.");
    }
}
