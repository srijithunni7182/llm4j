package io.github.llm4j.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.tools.SandboxRequest.SandboxLimits;
import io.github.llm4j.tools.SandboxRequest.SandboxNetwork;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 35, G46: what the container backend asks the runtime for. Only the workspace is mounted,
 * protected paths are mounted read only over it, every capability is dropped, the root file system
 * is read only, and a secret's value never appears on the command line.
 */
class ContainerSandboxArgumentsTest {

    @TempDir Path workspace;
    final ContainerSandbox backend = new ContainerSandbox("docker", "python:3.12-slim", false);

    SandboxRequest request(List<String> protect, Map<String, String> env, SandboxLimits limits) {
        return new SandboxRequest("python3", List.of("-m", "pytest"), workspace, "src", protect, null, env, limits, SandboxNetwork.none(), "llm4j-run-abc");
    }

    static SandboxLimits limits() {
        return new SandboxLimits(Duration.ofMinutes(1), 1024, null, null, null);
    }

    static String after(List<String> a, String flag) {
        return a.get(a.indexOf(flag) + 1);
    }

    @Test
    void onlyTheWorkspaceIsMountedAndProtectedPathsAreReadOnlyOverIt() {
        List<String> a = backend.arguments(request(List.of("tests", "src/oracle.json"), Map.of(), limits()), "none", null, null);
        String root = workspace.toAbsolutePath().normalize().toString();
        List<String> volumes = new java.util.ArrayList<>();
        for (int i = 0; i < a.size(); i++) if (a.get(i).equals("--volume")) volumes.add(a.get(i + 1));
        assertThat(volumes)
                .containsExactly(
                        root + ":/workspace",
                        root + "/tests:/workspace/tests:ro",
                        root + "/src/oracle.json:/workspace/src/oracle.json:ro");
        assertThat(after(a, "--workdir")).isEqualTo("/workspace/src");
        assertThat(a).doesNotContain("--privileged").noneMatch(s -> s.contains("docker.sock"));
    }

    @Test
    void theContainerHasNoCapabilitiesAReadOnlyRootAndNoNetworkUnlessAsked() {
        List<String> a = backend.arguments(request(List.of(), Map.of(), limits()), "none", null, null);
        assertThat(after(a, "--cap-drop")).isEqualTo("ALL");
        assertThat(after(a, "--security-opt")).isEqualTo("no-new-privileges");
        assertThat(a).contains("--read-only", "--rm");
        assertThat(after(a, "--network")).isEqualTo("none");
        assertThat(after(a, "--tmpfs")).isEqualTo("/tmp:rw,size=64m");
        assertThat(a.subList(a.indexOf("python:3.12-slim"), a.size())).containsExactly("python:3.12-slim", "python3", "-m", "pytest");
        assertThat(a).noneMatch(s -> s.contains("PROXY"));
        assertThat(a).doesNotContain("--memory", "--pids-limit", "--publish");
    }

    @Test
    void limitsBecomeRuntimeFlags() {
        List<String> a =
                backend.arguments(request(List.of(), Map.of(), new SandboxLimits(Duration.ofMinutes(1), 1024, 512L << 20, 64, 100L << 20)), "none", null, null);
        assertThat(after(a, "--memory")).isEqualTo(String.valueOf(512L << 20));
        assertThat(after(a, "--pids-limit")).isEqualTo("64");
        assertThat(after(a, "--tmpfs")).isEqualTo("/tmp:rw,size=" + (100L << 20));
    }

    @Test
    void aSecretTravelsByNameNeverByValue() {
        List<String> a = backend.arguments(request(List.of(), Map.of("PYPI_TOKEN", "sk-test-canary000"), limits()), "none", null, null);
        assertThat(a).contains("PYPI_TOKEN").noneMatch(s -> s.contains("canary"));
    }

    @Test
    void anAllowedNetworkGoesThroughTheProxyAndAServedPortIsPublishedOnLoopbackOnly() {
        List<String> allow = backend.arguments(request(List.of(), Map.of(), limits()), "llm4j-x-net", "172.30.0.1:41000", null);
        assertThat(after(allow, "--network")).isEqualTo("llm4j-x-net");
        assertThat(allow).contains("HTTPS_PROXY=http://172.30.0.1:41000", "http_proxy=http://172.30.0.1:41000");
        List<String> served = backend.arguments(request(List.of(), Map.of(), limits()), "bridge", null, 8000);
        assertThat(served).contains("--detach");
        assertThat(after(served, "--publish")).isEqualTo("127.0.0.1::8000");
    }

    @Test
    void theProgramRunsAsTheWorkspacesOwner() {
        List<String> a = backend.arguments(request(List.of(), Map.of(), limits()), "none", null, null);
        assertThat(after(a, "--user")).matches("\\d+:\\d+");
        assertThat(Collections.frequency(a, "--user")).isEqualTo(1);
    }

    @Test
    void aMissingRuntimeSaysWhatToInstallAndThatNothingFallsBack() {
        ContainerSandbox missing = new ContainerSandbox("no-such-runtime-xyz", "alpine:3.20", false);
        assertThat(missing.probe()).contains("install Docker or Podman").contains("never falls back");
        assertThat(missing.isolation()).isEqualTo(io.github.llm4j.agent.tool.Isolation.CONTAINER);
        assertThat(missing.enforces()).containsAll(java.util.EnumSet.allOf(SandboxBackend.Limit.class));
        assertThat(missing.canServe()).isTrue();
    }
}
