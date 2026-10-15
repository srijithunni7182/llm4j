package io.github.llm4j.loom.inspect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R41, R43: the deployer skill's scripts, run for real: a Fabric started in the background, a workflow reporting to it with its client key stored and never
 * printed, an unsafe bind refused, everything idempotent. Skipped (with the reason) where bash, curl or the built jars are missing.
 */
class DeployerScriptsTest {

    private static final Path REPO = Path.of("../../..");
    private static final Path SCRIPTS = REPO.resolve(".claude/skills/llm4j-loom-deployer/scripts");
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path work;
    private Map<String, String> jars;
    private int port;

    private static Path jar(String module, String glob) throws IOException {
        Path dir = REPO.resolve("src/loom").resolve(module).resolve("target");
        if (!Files.isDirectory(dir)) return null;
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().matches(glob) && !p.getFileName().toString().matches(".*(sources|javadoc|tests).*")).sorted().findFirst().orElse(null);
        }
    }

    private static boolean on(String tool) {
        try {
            return new ProcessBuilder(tool, "--version").redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(!System.getProperty("os.name").toLowerCase().contains("win"), "the .sh scripts run on macOS and Linux");
        assumeTrue(on("bash") && on("curl"), "bash and curl are needed");
        Path fabric = jar("loom-fabric", "loom-fabric-\\d.*-all\\.jar");
        Path weave = jar("ai-agent4j-loom", "ai-agent4j-loom-.*-cli\\.jar");
        Path inspect = jar("loom-inspect", "loom-inspect-\\d.*\\.jar");
        Path core = jar("loom-inspect-core", "loom-inspect-core-\\d.*\\.jar");
        assumeTrue(fabric != null && weave != null && inspect != null && core != null, "the jars are not built (mvn -DskipTests package)");
        jars = Map.of("FABRIC_JAR", fabric.toAbsolutePath().toString(), "WEAVE_JAR", weave.toAbsolutePath().toString(), "INSPECT_JAR", inspect.toAbsolutePath().toString(),
                "INSPECT_CORE_JAR", core.toAbsolutePath().toString());
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
    }

    @AfterEach
    void stopFabric() throws Exception {
        if (jars != null && Files.exists(work.resolve("fd/fabric.pid"))) run(Map.of(), "fabric-down.sh", "--data", work.resolve("fd").toString());
    }

    private record Result(int code, String out) { }

    private Result run(Map<String, String> extraEnv, String script, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("bash", SCRIPTS.resolve(script).toAbsolutePath().toString()));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(work.toFile()).redirectErrorStream(true);
        pb.environment().putAll(jars);
        pb.environment().remove("LOOM_FABRIC_ADMIN_PASSWORD");
        pb.environment().putAll(extraEnv);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(180, TimeUnit.SECONDS)).as(script + " finished").isTrue();
        return new Result(p.exitValue(), out);
    }

    private String url() {
        return "http://127.0.0.1:" + port;
    }

    private Result up() throws Exception {
        return run(Map.of(), "fabric-up.sh", "--data", work.resolve("fd").toString(), "--port", String.valueOf(port), "--generate-password");
    }

    private String password() throws Exception {
        return Files.readString(work.resolve("fd/admin-password.txt")).strip();
    }

    private JsonNode api(String path) throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<String> login = http.send(HttpRequest.newBuilder(URI.create(url() + "/api/login")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"password\":\"" + password() + "\"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(200);
        String cookie = login.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
        return JSON.readTree(http.send(HttpRequest.newBuilder(URI.create(url() + path)).header("Cookie", cookie).build(), HttpResponse.BodyHandlers.ofString()).body());
    }

    @Test
    void fabricComesUpOnce_theAdminPasswordIsPrivateAndNeverPrinted_andASecondStartChangesNothing() throws Exception {
        Result first = up();
        assertThat(first.code()).as(first.out()).isZero();
        assertThat(first.out()).contains("Fabric is up: " + url()).contains("admin-password.txt");
        assertThat(first.out()).doesNotContain(password());
        assertThat(Files.getPosixFilePermissions(work.resolve("fd/admin-password.txt"))).containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        assertThat(password()).hasSizeGreaterThanOrEqualTo(12);

        Result second = up();
        assertThat(second.code()).isZero();
        assertThat(second.out()).contains("already running").contains("Nothing changed");

        Result status = run(Map.of(), "fabric-status.sh", "--data", work.resolve("fd").toString(), "--port", String.valueOf(port));
        assertThat(status.out()).contains("running: pid").contains(String.valueOf(port));
    }

    @Test
    void theFirstStartNeedsAPasswordFromTheEnvironmentOrAGeneratedOne_neverFromTheCommandLine() throws Exception {
        Result none = run(Map.of(), "fabric-up.sh", "--data", work.resolve("fd").toString(), "--port", String.valueOf(port));
        assertThat(none.code()).isEqualTo(2);
        assertThat(none.out()).contains("LOOM_FABRIC_ADMIN_PASSWORD").contains("never taken from a command line");
        assertThat(Files.exists(work.resolve("fd/fabric.pid"))).isFalse();

        Result opt = run(Map.of(), "fabric-up.sh", "--data", work.resolve("fd").toString(), "--password", "hunter2hunter2");
        assertThat(opt.code()).as("there is no password option").isEqualTo(2);

        Result env = run(Map.of("LOOM_FABRIC_ADMIN_PASSWORD", "a-long-enough-password"), "fabric-up.sh", "--data", work.resolve("fd").toString(), "--port", String.valueOf(port));
        assertThat(env.code()).as(env.out()).isZero();
        assertThat(env.out()).doesNotContain("a-long-enough-password");
    }

    @Test
    void listeningBeyondThisComputerNeedsTlsOrAProxy_andIsRefusedBeforeAnythingStarts() throws Exception {
        Result r = run(Map.of(), "fabric-up.sh", "--data", work.resolve("fd").toString(), "--port", String.valueOf(port), "--bind", "0.0.0.0", "--generate-password");
        assertThat(r.code()).isEqualTo(3);
        assertThat(r.out()).contains("is not this computer").contains("--trust-proxy").contains("--tls-keystore");
        assertThat(Files.exists(work.resolve("fd/vault.json"))).as("nothing was set up").isFalse();
    }

    @Test
    void aWorkflowReportsToFabricWithItsKeyStoredAndNeverPrinted() throws Exception {
        assertThat(up().code()).isZero();
        Path flow = work.resolve("flow.loom");
        Files.writeString(flow, "workflow Main() {\n    note \"hello from the deployer test\"\n}\n");

        Result first = run(Map.of(), "run-observed.sh", "--script", flow.toString(), "--fabric", url(), "--name", "deploytest", "--data", work.resolve("fd").toString());
        assertThat(first.code()).as(first.out()).isZero();
        assertThat(first.out()).contains("Created the Fabric client 'deploytest'").contains("not printed").doesNotContain("lfk_");
        assertThat(Files.getPosixFilePermissions(work.resolve(".loom/secrets.key"))).containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

        Result second = run(Map.of(), "run-observed.sh", "--script", flow.toString(), "--fabric", url(), "--name", "deploytest", "--data", work.resolve("fd").toString());
        assertThat(second.code()).as(second.out()).isZero();
        assertThat(second.out()).doesNotContain("Created the Fabric client").doesNotContain("lfk_");

        Thread.sleep(1500);
        assertThat(api("/api/processes").get("processes").findValuesAsText("name")).contains("deploytest");
        assertThat(api("/api/runs").get("runs").findValuesAsText("client")).contains("deploytest");

        // the key is written nowhere readable: not in a log, not in the files the scripts made
        try (Stream<Path> files = Files.walk(work)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) assertThat(Files.readString(f, StandardCharsets.ISO_8859_1)).as(work.relativize(f).toString()).doesNotContain("lfk_");
        }
    }

    @Test
    void aProcessReportsOverHttpsToARemoteServerOnly_andNeedsAKey() throws Exception {
        Path flow = work.resolve("flow.loom");
        Files.writeString(flow, "workflow Main() {\n    note \"x\"\n}\n");
        Result plain = run(Map.of(), "run-observed.sh", "--script", flow.toString(), "--fabric", "http://fabric.example.com", "--name", "x");
        assertThat(plain.code()).isEqualTo(3);
        assertThat(plain.out()).contains("over https");
        Result nokey = run(Map.of(), "run-observed.sh", "--script", flow.toString(), "--fabric", "https://fabric.example.com", "--name", "x");
        assertThat(nokey.code()).isEqualTo(4);
        assertThat(nokey.out()).contains("no Fabric client key").contains("--key-stdin");
        assertThat(run(Map.of(), "run-observed.sh").code()).isEqualTo(2);
    }

    @Test
    void fabricGoesDownCleanly_andStoppingTwiceIsFine() throws Exception {
        assertThat(up().code()).isZero();
        Result down = run(Map.of(), "fabric-down.sh", "--data", work.resolve("fd").toString());
        assertThat(down.out()).contains("stopped");
        Result again = run(Map.of(), "fabric-down.sh", "--data", work.resolve("fd").toString());
        assertThat(again.code()).isZero();
        assertThat(again.out()).contains("not running");
        Result status = run(Map.of(), "fabric-status.sh", "--data", work.resolve("fd").toString(), "--port", String.valueOf(port));
        assertThat(status.out()).contains("not running");
    }
}
