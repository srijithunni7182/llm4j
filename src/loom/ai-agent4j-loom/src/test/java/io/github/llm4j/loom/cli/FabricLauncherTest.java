package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 22 R10.1: {@code weave fabric up | down | status} starts the Fabric jar as a separate
 * process. A fake host stands in for the processes, the network and the clock, so what is checked
 * is what the launcher decides: which jar, which arguments, which environment, what it prints and
 * which files it writes.
 */
class FabricLauncherTest {

    static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");

    @TempDir Path dir;

    private Path data;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private FakeHost host;
    private FabricLauncher launcher;

    @BeforeEach
    void setUp() {
        data = dir.resolve("fabric-data");
        host = new FakeHost();
        launcher =
                new FabricLauncher(
                        host,
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private String said() {
        return out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8);
    }

    private FabricLauncher.UpOptions up(String bind, String... extra) {
        return new FabricLauncher.UpOptions(data, 9090, bind, List.of(extra));
    }

    /** What the processes and the network would do. */
    static final class FakeHost implements FabricHost {
        Path jar = Path.of("/tools/loom-fabric-6.2.0-all.jar");
        final List<List<String>> runs = new ArrayList<>();
        final List<Map<String, String>> runEnvironments = new ArrayList<>();
        final List<List<String>> started = new ArrayList<>();
        final List<Long> stopped = new ArrayList<>();
        final List<Long> killed = new ArrayList<>();
        int initExit = 0;
        String clientsList = "";
        boolean answers = true;
        final List<String> asked = new ArrayList<>();
        boolean aliveAfterStop = false;
        Set<Long> alive = new java.util.HashSet<>();
        Map<String, String> environment = Map.of();
        long nextPid = 4242;
        Path dataForInit;

        @Override
        public Optional<Path> jar() {
            return Optional.ofNullable(jar);
        }

        @Override
        public String java() {
            return "java";
        }

        @Override
        public Result run(List<String> command, Map<String, String> extraEnvironment) {
            runs.add(List.copyOf(command));
            runEnvironments.add(Map.copyOf(extraEnvironment));
            if (command.contains("init")) {
                if (initExit == 0)
                    try {
                        Files.writeString(
                                Path.of(command.get(command.indexOf("--data") + 1))
                                        .resolve("vault.json"),
                                "{}");
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                return new Result(initExit, initExit == 0 ? "" : "password too short");
            }
            return new Result(0, clientsList);
        }

        @Override
        public long startBackground(List<String> command, Path log) {
            started.add(List.copyOf(command));
            try {
                Files.writeString(log, "Fabric listening\nsecond line\n");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            alive.add(nextPid);
            return nextPid;
        }

        @Override
        public boolean answers(String baseUrl, int seconds) {
            asked.add(baseUrl);
            return answers;
        }

        @Override
        public boolean alive(long pid) {
            return alive.contains(pid);
        }

        boolean fabric = true;

        @Override
        public boolean looksLikeFabric(long pid) {
            return fabric;
        }

        @Override
        public void stop(long pid) {
            stopped.add(pid);
            if (!aliveAfterStop) alive.remove(pid);
        }

        @Override
        public void kill(long pid) {
            killed.add(pid);
            alive.remove(pid);
        }

        @Override
        public Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Override
        public String environment(String name) {
            return environment.get(name);
        }
    }

    // ---- up

    @Test
    void upInitialisesThenStartsTheJarAsASeparateProcessAndSaysWhere() throws Exception {
        int exit = launcher.up(up("127.0.0.1"));

        assertThat(exit).isZero();
        assertThat(host.runs).singleElement().satisfies(c -> assertThat(c).containsSubsequence("-jar", host.jar.toString(), "init", "--data", data.toString()));
        assertThat(host.started)
                .singleElement()
                .satisfies(
                        c ->
                                assertThat(c)
                                        .containsSubsequence(
                                                "java", "-jar", host.jar.toString(), "serve")
                                        .containsSubsequence("--port", "9090", "--bind", "127.0.0.1"));
        assertThat(Files.readString(data.resolve("fabric.pid")).trim()).isEqualTo("4242");
        assertThat(said()).contains("http://127.0.0.1:9090").contains(data.resolve("admin-password.txt").toString());
    }

    @Test
    void upWritesAnOwnerOnlyPasswordFileAndTheSecretIsInNoOutputAndNoArgument() throws Exception {
        assumeTrue(
                data.getFileSystem().supportedFileAttributeViews().contains("posix"),
                "owner-only file modes are POSIX; Windows is covered by the same code path's ACL note");
        launcher.up(up("127.0.0.1"));

        Path file = data.resolve("admin-password.txt");
        String password = Files.readString(file).strip();
        assertThat(password).hasSizeGreaterThanOrEqualTo(12);
        assertThat(Files.getPosixFilePermissions(file))
                .containsExactlyInAnyOrder(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        assertThat(said()).doesNotContain(password);
        assertThat(host.runs).noneMatch(c -> c.stream().anyMatch(a -> a.contains(password)));
        assertThat(host.started).noneMatch(c -> c.stream().anyMatch(a -> a.contains(password)));
        assertThat(host.runEnvironments.get(0)).containsEntry("LOOM_FABRIC_ADMIN_PASSWORD", password);
    }

    @Test
    void upLeavesTheAdminPasswordOutOfTheServerStartAndOutOfTheLog() throws Exception {
        launcher.up(up("127.0.0.1"));

        assertThat(host.started.get(0)).noneMatch(a -> a.contains("PASSWORD"));
        assertThat(Files.readString(data.resolve("admin-password.txt")).strip())
                .isNotEqualTo(Files.readString(data.resolve("fabric.log")).strip());
    }

    @Test
    void anAdminPasswordFromTheEnvironmentIsUsedAndNoFileIsWritten() {
        host.environment = Map.of("LOOM_FABRIC_ADMIN_PASSWORD", "from-the-environment-1");

        launcher.up(up("127.0.0.1"));

        assertThat(data.resolve("admin-password.txt")).doesNotExist();
        assertThat(host.runEnvironments.get(0))
                .containsEntry("LOOM_FABRIC_ADMIN_PASSWORD", "from-the-environment-1");
        assertThat(said()).doesNotContain("from-the-environment-1");
    }

    @Test
    void aSecondUpChangesNothing() throws Exception {
        launcher.up(up("127.0.0.1"));
        String password = Files.readString(data.resolve("admin-password.txt"));
        host.started.clear();

        int exit = launcher.up(up("127.0.0.1"));

        assertThat(exit).isZero();
        assertThat(host.started).isEmpty();
        assertThat(Files.readString(data.resolve("admin-password.txt"))).isEqualTo(password);
        assertThat(said()).contains("already running").contains("Nothing changed");
    }

    @Test
    void upRefusesABindBeyondThisComputerWithoutTlsOrAProxy() {
        int exit = launcher.up(up("0.0.0.0"));

        assertThat(exit).isEqualTo(3);
        assertThat(said()).contains("'0.0.0.0' is not this computer").contains("--trust-proxy");
        assertThat(host.started).isEmpty();
        assertThat(data.resolve("vault.json")).doesNotExist();
    }

    @Test
    void upAcceptsABindBeyondThisComputerBehindAProxy() {
        assertThat(launcher.up(up("0.0.0.0", "--trust-proxy"))).isZero();
        assertThat(host.started.get(0)).contains("--trust-proxy", "0.0.0.0");
    }

    @Test
    void upUsesHttpsForTheAddressWhenTlsIsOn() {
        launcher.up(up("127.0.0.1", "--tls-keystore", "k.p12"));

        assertThat(said()).contains("https://127.0.0.1:9090");
    }

    @Test
    void upProbesTheAddressItWasToldToListenOn() {
        launcher.up(up("192.168.1.5", "--tls-keystore", "k.p12"));

        assertThat(host.asked).isNotEmpty().allMatch(url -> url.equals("https://192.168.1.5:9090"));
    }

    @Test
    void upProbesTheLoopbackWhenItListensOnEveryAddress() {
        launcher.up(up("0.0.0.0", "--trust-proxy"));

        assertThat(host.asked).isNotEmpty().allMatch(url -> url.equals("http://127.0.0.1:9090"));
    }

    @Test
    void upBracketsAnIpv6Address() {
        launcher.up(up("fd00::5", "--tls-keystore", "k.p12"));

        assertThat(host.asked).isNotEmpty().allMatch(url -> url.equals("https://[fd00::5]:9090"));
    }

    @Test
    void upSaysWhatToDoWhenTheJarIsNotFound() {
        host.jar = null;

        int exit = launcher.up(up("127.0.0.1"));

        assertThat(exit).isEqualTo(2);
        assertThat(said()).contains("weave tools fetch fabric").contains("FABRIC_JAR");
        assertThat(host.runs).isEmpty();
    }

    @Test
    void aFailedInitStartsNothingAndLeavesNoServer() {
        host.initExit = 1;

        int exit = launcher.up(up("127.0.0.1"));

        assertThat(exit).isEqualTo(4);
        assertThat(said()).contains("fabric init failed").contains("12 or more characters");
        assertThat(host.started).isEmpty();
    }

    @Test
    void aPasswordFileThatCannotBeCreatedIsSaidClearlyAndLeavesNothingHalfDone() throws Exception {
        Files.createDirectories(data.resolve("admin-password.txt"));

        int exit = launcher.up(up("127.0.0.1"));

        assertThat(exit).isEqualTo(4);
        assertThat(said()).contains("could not write the admin password file");
        assertThat(host.runs).isEmpty();
        assertThat(host.started).isEmpty();
        assertThat(data.resolve("vault.json")).doesNotExist();
    }

    @Test
    void aServerThatDoesNotAnswerIsStoppedAndItsLogShown() {
        host.answers = false;

        int exit = launcher.up(up("127.0.0.1"));

        assertThat(exit).isEqualTo(5);
        assertThat(said()).contains("did not answer").contains("Fabric listening");
        assertThat(host.stopped).containsExactly(4242L);
        assertThat(data.resolve("fabric.pid")).doesNotExist();
    }

    // ---- down

    @Test
    void downStopsTheServerAndRemovesThePidFile() throws Exception {
        launcher.up(up("127.0.0.1"));

        assertThat(launcher.down(data)).isZero();

        assertThat(host.stopped).containsExactly(4242L);
        assertThat(data.resolve("fabric.pid")).doesNotExist();
        assertThat(said()).contains("stopped");
    }

    @Test
    void downKillsAServerThatDoesNotStop() throws Exception {
        launcher.up(up("127.0.0.1"));
        host.aliveAfterStop = true;

        launcher.down(data);

        assertThat(host.killed).containsExactly(4242L);
        assertThat(said()).contains("was killed");
    }

    @Test
    void downWhenNothingRunsIsNotAnError() throws Exception {
        assertThat(launcher.down(data)).isZero();
        assertThat(said()).contains("not running");

        Files.createDirectories(data);
        Files.writeString(data.resolve("fabric.pid"), "999");
        assertThat(launcher.down(data)).isZero();
        assertThat(said()).contains("stale");
        assertThat(data.resolve("fabric.pid")).doesNotExist();
    }

    @Test
    void downNeverStopsAProcessThatIsNotFabricEvenIfThePidFileNamesIt() throws Exception {
        launcher.up(up("127.0.0.1"));
        host.fabric = false;

        assertThat(launcher.down(data)).isZero();

        assertThat(host.stopped).isEmpty();
        assertThat(host.killed).isEmpty();
        assertThat(said()).contains("another program").contains("not stopped");
        assertThat(data.resolve("fabric.pid")).doesNotExist();
    }

    // ---- status

    private void report(String client, String at) throws IOException {
        Path day = data.resolve("history").resolve(client);
        Files.createDirectories(day);
        Files.writeString(
                day.resolve("2026-10-30.jsonl"),
                "{\"k\":\"hello\",\"t\":\"2026-10-30T08:00:00Z\",\"b\":\"{}\"}\n"
                        + "{\"k\":\"heartbeat\",\"t\":\""
                        + at
                        + "\",\"b\":\"{}\"}\n"
                        + "{\"k\":\"heartb");
    }

    @Test
    void statusShowsEachClientWithWhenItLastReported() throws Exception {
        launcher.up(up("127.0.0.1"));
        host.clientsList = "billing\nreports\n";
        report("billing", "2026-10-30T09:56:00Z");
        out.reset();

        int exit = launcher.status(data, 9090);

        assertThat(exit).isZero();
        String text = said();
        assertThat(text).contains("running").contains("pid 4242").contains("9090");
        assertThat(text).contains("billing").contains("last reported 2026-10-30T09:56:00Z (4 minutes ago)");
        assertThat(text).contains("reports").contains("never reported");
    }

    @Test
    void statusSaysWhenTheProcessIsAliveButSilent() throws Exception {
        launcher.up(up("127.0.0.1"));
        host.answers = false;
        out.reset();

        launcher.status(data, 9090);

        assertThat(said()).contains("alive").contains("does not answer").contains("fabric.log");
    }

    @Test
    void statusOfAFolderThatWasNeverSetUpSaysSoAndFails() {
        int exit = launcher.status(data, 9090);

        assertThat(exit).isEqualTo(1);
        assertThat(said()).contains("has not been set up here").contains("weave fabric up");
    }

    @Test
    void statusWhenStoppedSaysHowToStartIt() throws Exception {
        launcher.up(up("127.0.0.1"));
        launcher.down(data);
        out.reset();

        launcher.status(data, 9090);

        assertThat(said()).contains("not running").contains("weave fabric up");
    }
}
