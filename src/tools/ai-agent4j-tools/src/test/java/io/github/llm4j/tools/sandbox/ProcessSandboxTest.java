package io.github.llm4j.tools.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.agent.tool.Isolation;
import io.github.llm4j.tools.ProcessSandbox;
import io.github.llm4j.tools.SandboxBackend;
import io.github.llm4j.tools.SandboxRequest;
import io.github.llm4j.tools.SandboxRequest.SandboxLimits;
import io.github.llm4j.tools.SandboxRequest.SandboxNetwork;
import io.github.llm4j.tools.SandboxResult;
import io.github.llm4j.tools.ToolRefusal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 35, D5 and D6: the process backend runs a real program (the JDK's own {@code java}, with a
 * probe class from this module's tests) with an empty environment, a time limit and output kept
 * head and tail.
 */
class ProcessSandboxTest {

    static final Path JAVA = Path.of(System.getProperty("java.home"), "bin", "java");
    static final String CLASSPATH = System.getProperty("java.class.path");

    @TempDir Path workspace;
    final ProcessSandbox backend = new ProcessSandbox(name -> JAVA);

    SandboxResult run(Map<String, String> env, String stdin, SandboxLimits limits, String... probe) throws Exception {
        List<String> args = new ArrayList<>(List.of("-cp", CLASSPATH, SandboxProbe.class.getName()));
        args.addAll(List.of(probe));
        return backend.run(new SandboxRequest("java", args, workspace, "", List.of(), stdin, env, limits, SandboxNetwork.none(), "t"));
    }

    static SandboxLimits limits(Duration timeout, long maxOutput) {
        return new SandboxLimits(timeout, maxOutput, null, null, null);
    }

    SandboxResult run(String... probe) throws Exception {
        return run(Map.of(), null, limits(Duration.ofMinutes(1), 64 * 1024), probe);
    }

    @Test
    void itSaysWhatItIsAndWhatItCanEnforce() {
        assertThat(backend.isolation()).isEqualTo(Isolation.PROCESS);
        assertThat(backend.enforces()).containsExactlyInAnyOrder(SandboxBackend.Limit.TIME, SandboxBackend.Limit.OUTPUT);
        assertThat(backend.probe()).isNull();
        assertThat(backend.canServe()).isFalse();
    }

    @Test
    void theProgramSeesOnlyTheNamedVariablesAndAFewNeutralOnes() throws Exception {
        SandboxResult r = run(Map.of("TOKEN", "test-value"), null, limits(Duration.ofMinutes(1), 4096), "env");
        assertThat(r.stdout().strip()).isEqualTo("[HOME, LANG, PATH, TOKEN, TZ]");
        assertThat(r.isolation()).isEqualTo(Isolation.PROCESS);
        assertThat(run(Map.of("TOKEN", "test-value"), null, limits(Duration.ofMinutes(1), 4096), "var", "TOKEN").stdout().strip())
                .isEqualTo("test-value");
    }

    @Test
    void longOutputKeepsItsStartAndItsEndAndSaysHowMuchWasCut() throws Exception {
        SandboxResult r = run(Map.of(), null, limits(Duration.ofMinutes(1), 100), "big", "10000");
        assertThat(r.stdout()).startsWith("START").endsWith("END").contains("bytes cut");
        assertThat(r.truncated()).isTrue();
        assertThat(r.cutBytes()).isEqualTo(10_008 - 100);
        assertThat(r.stderr()).isEqualTo("err");
        SandboxResult whole = run("big", "10");
        assertThat(whole.truncated()).isFalse();
        assertThat(whole.stdout()).isEqualTo("START" + "x".repeat(10) + "END");
    }

    @Test
    void aProgramPastItsTimeIsKilledAndSaysSo() throws Exception {
        SandboxResult r = run(Map.of(), null, limits(Duration.ofMillis(1500), 4096), "sleep", "60000");
        assertThat(r.timedOut()).isTrue();
        assertThat(r.exit()).isEqualTo(-1);
        assertThat(r.durationMs()).isLessThan(30_000);
    }

    @Test
    void inputExitCodeAndFolder() throws Exception {
        assertThat(run(Map.of(), "hello", limits(Duration.ofMinutes(1), 4096), "stdin").stdout()).isEqualTo("HELLO");
        assertThat(run("exit", "3").exit()).isEqualTo(3);
        Files.createDirectories(workspace.resolve("sub"));
        List<String> args = List.of("-cp", CLASSPATH, SandboxProbe.class.getName(), "cwd");
        SandboxResult r = backend.run(new SandboxRequest("java", args, workspace, "sub", List.of(), null, Map.of(), limits(Duration.ofMinutes(1), 4096), SandboxNetwork.none(), "t"));
        assertThat(Path.of(r.stdout().strip()).toRealPath()).isEqualTo(workspace.resolve("sub").toRealPath());
    }

    @Test
    void aProgramNotOnThePathIsRefused() {
        assertThatThrownBy(() -> new ProcessSandbox().run(new SandboxRequest("no-such-program-xyz", List.of(), workspace, "", List.of(), null, Map.of(), limits(Duration.ofSeconds(5), 10), SandboxNetwork.none(), "t")))
                .isInstanceOf(ToolRefusal.class)
                .hasMessageContaining("not found on this machine's PATH");
    }
}
