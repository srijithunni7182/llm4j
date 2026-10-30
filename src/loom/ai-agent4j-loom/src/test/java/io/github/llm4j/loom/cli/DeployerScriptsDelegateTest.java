package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 22 R10.3: the deployer skill's Fabric scripts hand over to {@code weave fabric} when the
 * weave in use has it, with the same exit code, and keep their own logic only as the fallback. A
 * stub {@code weave} on the path stands in for the command, so no jar is needed.
 */
class DeployerScriptsDelegateTest {

    private static final Path SCRIPTS =
            Path.of("../../../.claude/skills/llm4j-loom-deployer/scripts").toAbsolutePath().normalize();

    @TempDir Path work;
    private Path stubLog;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(
                !System.getProperty("os.name").toLowerCase().contains("win"),
                "the .sh scripts run on macOS and Linux; the .ps1 twins are not run by this suite");
        assumeTrue(Files.isDirectory(SCRIPTS), "the skill's scripts are in the repository");
        Path bin = Files.createDirectories(work.resolve("bin"));
        stubLog = work.resolve("stub.log");
        Path weave = bin.resolve("weave");
        Files.writeString(
                weave,
                "#!/usr/bin/env bash\n"
                        + "if [ \"$1\" = fabric ] && [ \"$2\" = --help ]; then exit \"${HELP_EXIT:-0}\"; fi\n"
                        + "echo \"$@\" >> \"$STUB_LOG\"\n"
                        + "exit \"${STUB_EXIT:-0}\"\n");
        assertThat(weave.toFile().setExecutable(true)).isTrue();
    }

    private record Result(int code, String out) {}

    private Result run(Map<String, String> env, String script, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("bash", SCRIPTS.resolve(script).toString()));
        command.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command).directory(work.toFile()).redirectErrorStream(true);
        pb.environment().put("PATH", work.resolve("bin") + ":" + System.getenv("PATH"));
        pb.environment().put("HOME", work.toString());
        pb.environment().put("STUB_LOG", stubLog.toString());
        pb.environment().putAll(env);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).as(script + " finished").isTrue();
        return new Result(p.exitValue(), out);
    }

    private String called() throws Exception {
        return Files.exists(stubLog) ? Files.readString(stubLog) : "";
    }

    @Test
    void fabricUpHandsOverWithItsOptionsAndTheServeOptionsAfterTheDoubleDash() throws Exception {
        Result r =
                run(Map.of(), "fabric-up.sh", "--data", "d", "--port", "9191", "--", "--trust-proxy");

        assertThat(r.code()).isZero();
        assertThat(called()).contains("fabric up --data d --port 9191 --bind 127.0.0.1 -- --trust-proxy");
    }

    @Test
    void theExitCodeOfWeaveFabricIsTheScriptsExitCode() throws Exception {
        assertThat(run(Map.of("STUB_EXIT", "3"), "fabric-up.sh", "--data", "d").code()).isEqualTo(3);
    }

    @Test
    void fabricDownAndStatusHandOverToo() throws Exception {
        run(Map.of(), "fabric-down.sh", "--data", "d");
        run(Map.of(), "fabric-status.sh", "--data", "d", "--port", "9191");

        assertThat(called()).contains("fabric down --data d").contains("fabric status --data d --port 9191");
    }

    @Test
    void withoutAWeaveThatHasTheCommandTheScriptKeepsItsOwnLogic() throws Exception {
        Map<String, String> noFabric =
                Map.of(
                        "HELP_EXIT", "1",
                        "WEAVE_JAR", work.resolve("no-such-weave.jar").toString(),
                        "FABRIC_JAR", work.resolve("no-such-fabric.jar").toString());

        Result r = run(noFabric, "fabric-up.sh", "--data", "d");

        assertThat(called()).doesNotContain("fabric up");
        assertThat(r.code()).isEqualTo(2);
        assertThat(r.out()).contains("FABRIC_JAR points at");
    }
}
