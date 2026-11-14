package io.github.llm4j.tools.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.llm4j.agent.tool.Isolation;
import io.github.llm4j.tools.ContainerSandbox;
import io.github.llm4j.tools.EgressProxy;
import io.github.llm4j.tools.SandboxBackend;
import io.github.llm4j.tools.SandboxRequest;
import io.github.llm4j.tools.SandboxRequest.SandboxLimits;
import io.github.llm4j.tools.SandboxRequest.SandboxNetwork;
import io.github.llm4j.tools.SandboxResult;
import io.github.llm4j.tools.ToolRefusal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 35, D5 to D7 against a real Docker daemon, with {@code alpine:3.20}: the container sees
 * only the workspace, protected paths are read only, no network means none, limits hold and a
 * served program is published on loopback and stopped. Runs only where a daemon answers.
 */
@Tag("integration")
class ContainerSandboxDockerTest {

    static final String IMAGE = "alpine:3.20";
    @TempDir Path workspace;
    final ContainerSandbox backend = new ContainerSandbox("docker", IMAGE, true);
    int runs;

    @BeforeEach
    void daemon() throws Exception {
        assumeTrue(backend.probe() == null, "no Docker daemon answers here");
        Files.writeString(workspace.resolve("input.txt"), "from the workspace");
        Files.createDirectories(workspace.resolve("tests"));
        Files.writeString(workspace.resolve("tests/oracle.txt"), "expected");
    }

    SandboxResult sh(String script, SandboxNetwork network, List<String> protect, SandboxLimits limits, Map<String, String> env) throws Exception {
        return backend.run(new SandboxRequest("sh", List.of("-c", script), workspace, "", protect, null, env, limits, network, "llm4j-it-" + ProcessHandle.current().pid() + "-" + (++runs)));
    }

    static SandboxLimits limits() {
        return new SandboxLimits(Duration.ofMinutes(2), 64 * 1024, null, null, null);
    }

    SandboxResult sh(String script) throws Exception {
        return sh(script, SandboxNetwork.none(), List.of(), limits(), Map.of());
    }

    @Test
    void theContainerSeesTheWorkspaceAndWritesBackToIt() throws Exception {
        SandboxResult r = sh("cat input.txt && echo made > out.txt && ls /");
        assertThat(r.exit()).isZero();
        assertThat(r.stdout()).contains("from the workspace").contains("workspace");
        assertThat(r.isolation()).isEqualTo(Isolation.CONTAINER);
        assertThat(Files.readString(workspace.resolve("out.txt")).strip()).isEqualTo("made");
    }

    @Test
    void protectedPathsAreReadOnlyAndTheRootFileSystemIsToo() throws Exception {
        SandboxResult r = sh("echo x > tests/oracle.txt; echo $?; echo y > /etc/z; echo $?", SandboxNetwork.none(), List.of("tests"), limits(), Map.of());
        assertThat(r.stdout().lines().toList()).containsExactly("1", "1");
        assertThat(Files.readString(workspace.resolve("tests/oracle.txt"))).isEqualTo("expected");
    }

    @Test
    void noNetworkMeansNoRouteAtAll() throws Exception {
        SandboxResult r = sh("ip route; nc -w 3 1.1.1.1 443 </dev/null && echo reached || echo blocked");
        assertThat(r.stdout()).doesNotContain("default").contains("blocked");
    }

    @Test
    void anAllowedNetworkHasOnlyTheProxy() throws Exception {
        SandboxResult r = sh("ip route; echo $HTTPS_PROXY; nc -w 3 1.1.1.1 443 </dev/null && echo reached || echo blocked",
                new SandboxNetwork(SandboxNetwork.Mode.ALLOW, EgressProxy.Hosts.parse("allowed.example")), List.of(), limits(), Map.of());
        assertThat(r.stdout()).doesNotContain("default").contains("blocked").contains("http://");
    }

    @Test
    void aNamedVariableReachesTheProgramAndNothingElseDoes() throws Exception {
        SandboxResult r = sh("echo $TOKEN; env | cut -d= -f1 | sort | tr '\\n' ' '", SandboxNetwork.none(), List.of(), limits(), Map.of("TOKEN", "test-value"));
        assertThat(r.stdout()).contains("test-value").doesNotContain("JAVA_TOOL_OPTIONS").doesNotContain("PATH=");
    }

    @Test
    void limitsHold() throws Exception {
        SandboxResult slow = sh("sleep 60", SandboxNetwork.none(), List.of(), new SandboxLimits(Duration.ofSeconds(3), 1024, null, null, null), Map.of());
        assertThat(slow.timedOut()).isTrue();
        SandboxResult forks = sh("for i in 1 2 3 4 5 6 7 8 9 10 11 12; do sleep 5 & done; wait; echo done", SandboxNetwork.none(), List.of(), new SandboxLimits(Duration.ofMinutes(1), 4096, 64L << 20, 5, null), Map.of());
        assertThat(forks.stderr()).containsIgnoringCase("resource");
        SandboxResult big = sh("head -c 100000 /dev/zero | tr '\\0' x", SandboxNetwork.none(), List.of(), new SandboxLimits(Duration.ofMinutes(1), 100, null, null, null), Map.of());
        assertThat(big.truncated()).isTrue();
    }

    @Test
    void aServedProgramIsOnLoopbackAndStops() throws Exception {
        SandboxRequest serve = new SandboxRequest("sh", List.of("-c", "while true; do echo -e 'HTTP/1.0 200 OK\\r\\n\\r\\nhi' | nc -l -p 8000; done"), workspace, "", List.of(), null, Map.of(), limits(),
                new SandboxNetwork(SandboxNetwork.Mode.OPEN, null), "llm4j-it-serve-" + ProcessHandle.current().pid());
        SandboxBackend.Served s = backend.serve(serve, 8000);
        try {
            assertThat(s.hostPort()).isPositive();
            assertThat(backend.running(s.handle())).isTrue();
        } finally {
            backend.stop(s.handle());
        }
        assertThat(backend.running(s.handle())).isFalse();
        assertThatThrownBy(() -> backend.serve(new SandboxRequest("sh", List.of(), workspace, "", List.of(), null, Map.of(), limits(), SandboxNetwork.none(), "x"), 8000))
                .isInstanceOf(ToolRefusal.class);
    }
}
