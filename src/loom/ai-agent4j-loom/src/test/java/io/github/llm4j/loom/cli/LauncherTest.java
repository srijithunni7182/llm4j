package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R18 to R22 of spec 16: the launcher, the JBang entry, the checksum and the version line. */
class LauncherTest {

    private static final Path ROOT = Path.of("../../../").toAbsolutePath().normalize();

    @TempDir Path dir;

    record Run(int code, String out, String err) {}

    /** A folder with the launcher and a jar beside it, and a bin folder with a fake java. */
    private Path setup(String javaVersionLine, boolean withJava) throws Exception {
        Path home = Files.createDirectories(dir.resolve("home"));
        Files.copy(ROOT.resolve("install/weave"), home.resolve("weave"));
        home.resolve("weave").toFile().setExecutable(true);
        Files.writeString(home.resolve("weave.jar"), "not a jar");
        Path bin = Files.createDirectories(dir.resolve("bin"));
        for (String tool : List.of("dirname", "sed", "head", "cat")) {
            Path real = Path.of("/usr/bin", tool);
            if (!Files.exists(real)) real = Path.of("/bin", tool);
            Files.createSymbolicLink(bin.resolve(tool), real);
        }
        if (withJava) {
            Path java = bin.resolve("java");
            Files.writeString(
                    java,
                    "#!/bin/sh\n"
                            + "case \"$*\" in *-version*) echo '"
                            + javaVersionLine
                            + "' >&2; exit 0;; esac\n"
                            + "for a in \"$@\"; do printf '%s\\n' \"$a\"; done > \""
                            + dir.resolve("args.txt")
                            + "\"\n");
            Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        return home;
    }

    private Run launch(Path home, Map<String, String> env, String... args) throws Exception {
        List<String> cmd =
                new java.util.ArrayList<>(List.of("/bin/sh", home.resolve("weave").toString()));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().clear();
        pb.environment().put("PATH", dir.resolve("bin").toString());
        pb.environment().putAll(env);
        File out = dir.resolve("o.txt").toFile();
        File err = dir.resolve("e.txt").toFile();
        pb.redirectOutput(out).redirectError(err);
        Process p = pb.start();
        assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
        return new Run(
                p.exitValue(), Files.readString(out.toPath()), Files.readString(err.toPath()));
    }

    private static void needSh() {
        assumeTrue(
                Files.isExecutable(Path.of("/bin/sh")) && Files.exists(Path.of("/usr/bin/sed"))
                        || Files.exists(Path.of("/bin/sed")));
    }

    @Test
    void argumentsReachTheJarUntouchedAndJavaOptsComeFirst() throws Exception {
        needSh();
        Path home = setup("openjdk version \"21.0.1\" 2023-10-17", true);
        Run r =
                launch(
                        home,
                        Map.of("JAVA_OPTS", "-Xmx64m -Dx=1"),
                        "try",
                        "--keep",
                        "a dir/with space",
                        "$HOME",
                        "*",
                        "it's");
        assertThat(r.code()).as(r.err()).isZero();
        assertThat(Files.readAllLines(dir.resolve("args.txt")))
                .containsExactly(
                        "-Xmx64m",
                        "-Dx=1",
                        "-jar",
                        home.resolve("weave.jar").toString(),
                        "try",
                        "--keep",
                        "a dir/with space",
                        "$HOME",
                        "*",
                        "it's");
    }

    @Test
    void anOldJavaIsRefusedWithTheWayOut() throws Exception {
        needSh();
        for (String line :
                new String[] {
                    "openjdk version \"11.0.2\" 2019-01-15", "java version \"1.8.0_392\""
                }) {
            Run r = launch(setup(line, true), Map.of(), "try");
            assertThat(r.code()).isEqualTo(2);
            assertThat(r.err())
                    .contains("too old")
                    .contains("17 or newer")
                    .contains("adoptium.net");
            Files.deleteIfExists(dir.resolve("args.txt"));
            deleteTree(dir.resolve("home"));
            deleteTree(dir.resolve("bin"));
        }
    }

    @Test
    void noJavaAndNoJarEachSayWhatToDo() throws Exception {
        needSh();
        Path noJava = setup("", false);
        Run r = launch(noJava, Map.of(), "try");
        assertThat(r.code()).isEqualTo(2);
        assertThat(r.err()).contains("Java is not installed").contains("17 or newer");
        Files.delete(noJava.resolve("weave.jar"));
        Run noJar = launch(noJava, Map.of(), "try");
        assertThat(noJar.err()).contains("cannot find weave.jar").contains("WEAVE_JAR");
        Path elsewhere = Files.writeString(dir.resolve("other.jar"), "x");
        Run viaVar = launch(noJava, Map.of("WEAVE_JAR", elsewhere.toString()), "try");
        assertThat(viaVar.err()).doesNotContain("cannot find weave.jar");
    }

    @Test
    void javaHomeWinsOverPath() throws Exception {
        needSh();
        Path home = setup("openjdk version \"21.0.1\"", true);
        Path jh = Files.createDirectories(dir.resolve("jh/bin"));
        Path java = jh.resolve("java");
        Files.writeString(
                java,
                "#!/bin/sh\ncase \"$*\" in *-version*) echo 'openjdk version \"17.0.9\"' >&2; exit 0;; esac\necho from-java-home > \""
                        + dir.resolve("which.txt")
                        + "\"\n");
        Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwxr-xr-x"));
        launch(home, Map.of("JAVA_HOME", dir.resolve("jh").toString()), "try");
        assertThat(Files.readString(dir.resolve("which.txt"))).contains("from-java-home");
    }

    @Test
    void theLauncherIsShellcheckCleanWhenShellcheckIsThere() throws Exception {
        Process which =
                new ProcessBuilder("sh", "-c", "command -v shellcheck")
                        .redirectErrorStream(true)
                        .start();
        which.waitFor();
        assumeTrue(which.exitValue() == 0, "shellcheck is not installed");
        Process p =
                new ProcessBuilder("shellcheck", ROOT.resolve("install/weave").toString())
                        .redirectErrorStream(true)
                        .start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor()).as(out).isZero();
    }

    @Test
    void theWindowsLauncherChecksJavaAndPassesEverythingOn() throws Exception {
        String cmd = Files.readString(ROOT.resolve("install/weave.cmd"));
        assertThat(cmd)
                .contains("%*")
                .contains("JAVA_OPTS")
                .contains("WEAVE_JAR")
                .contains("Java is not installed")
                .contains("exit /b %ERRORLEVEL%");
    }

    @Test
    void theJbangAliasPointsAtTheCliJarOfThisVersion() throws Exception {
        JsonNode catalog = new ObjectMapper().readTree(ROOT.resolve("jbang-catalog.json").toFile());
        JsonNode alias = catalog.path("aliases").path("weave");
        String pom = Files.readString(ROOT.resolve("src/loom/ai-agent4j-loom/pom.xml"));
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile(
                                "<parent>.*?<version>([^<]+)</version>",
                                java.util.regex.Pattern.DOTALL)
                        .matcher(pom);
        assertThat(m.find()).isTrue();
        assertThat(alias.path("script-ref").asText())
                .isEqualTo("io.github.srijithunni7182:ai-agent4j-loom:" + m.group(1) + ":cli");
        assertThat(alias.path("java").asText()).isEqualTo("17+");
        assertThat(alias.path("description").asText()).contains("try");
    }

    @Test
    void theChecksumFileVerifiesTheJar() throws Exception {
        assumeTrue(
                Files.exists(Path.of("/usr/bin/sha256sum"))
                        || Files.exists(Path.of("/bin/sha256sum")));
        try (var s = Files.list(ROOT.resolve("src/loom/ai-agent4j-loom/target"))) {
            assumeTrue(
                    s.anyMatch(p -> p.getFileName().toString().endsWith("-cli.jar")),
                    "the cli jar is not built");
        }
        Path out = dir.resolve("rel");
        Process p =
                new ProcessBuilder(
                                "bash",
                                ROOT.resolve("scripts/make-release-launchers.sh").toString(),
                                out.toString())
                        .redirectErrorStream(true)
                        .start();
        String text = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor()).as(text).isZero();
        assertThat(out.resolve("weave")).exists();
        assertThat(out.resolve("weave.cmd")).exists();
        Process check =
                new ProcessBuilder("sha256sum", "-c", "weave.jar.sha256")
                        .directory(out.toFile())
                        .redirectErrorStream(true)
                        .start();
        String c = new String(check.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(check.waitFor()).as(c).isZero();
        assertThat(c).contains("weave.jar: OK");
    }

    @Test
    void theVersionLineCarriesTheProtocolOfServe() throws Exception {
        String[] v = new WeaveCLI.BuildVersion().getVersion();
        assertThat(v).hasSize(1);
        assertThat(v[0])
                .matches(
                        "weave \\S+( \\(built [^)]+\\))?, serve protocol \\d+|weave \\(run from classes.*");
    }

    private static void deleteTree(Path p) throws Exception {
        if (!Files.exists(p)) return;
        try (var s = Files.walk(p)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(x -> x.toFile().delete());
        }
    }
}
