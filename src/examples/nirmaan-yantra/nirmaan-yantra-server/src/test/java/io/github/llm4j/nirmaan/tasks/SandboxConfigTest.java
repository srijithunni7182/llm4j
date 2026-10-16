package io.github.llm4j.nirmaan.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SandboxConfigTest {

    @Test
    void readsItsSettingsFromTheEnvironmentWithSafeDefaults() {
        SandboxConfig defaults = SandboxConfig.from(name -> null);
        assertThat(defaults.mode()).isEqualTo("local");
        assertThat(defaults.container()).isFalse();
        assertThat(defaults.network()).isEqualTo("bridge");
        assertThat(defaults.requireContainer()).isFalse();

        SandboxConfig strict = SandboxConfig.from(Map.of("NIRMAAN_SANDBOX", "podman", "NIRMAAN_SANDBOX_IMAGE", "my/img:1", "NIRMAAN_SANDBOX_NETWORK", "none",
                "NIRMAAN_REQUIRE_CONTAINER", "TRUE")::get);
        assertThat(strict.container()).isTrue();
        assertThat(strict.image()).isEqualTo("my/img:1");
        assertThat(strict.network()).isEqualTo("none");
        assertThat(strict.requireContainer()).isTrue();
    }

    @Test
    void anUnknownRuntimeIsAConfigurationError() {
        assertThatThrownBy(() -> SandboxConfig.from(Map.of("NIRMAAN_SANDBOX", "chroot")::get)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("chroot");
    }

    @Test
    void theContainerRunsAsAnUnprivilegedUserWithLimitsAndOnlyTheProjectMounted() {
        SandboxConfig config = new SandboxConfig("docker", "nirmaan-sandbox:latest", "none", "2g", "1", "", false);

        List<String> command = config.containerCommand("nirmaan-p1-abc", List.of("mvn", "test"), Path.of("/ws/p1/code"), Path.of("/ws/.toolhome"), "1000", "1001");

        assertThat(command).startsWith("docker", "run", "--rm", "--name", "nirmaan-p1-abc");
        assertThat(command).containsSubsequence("--network", "none").containsSubsequence("--memory", "2g").containsSubsequence("--cpus", "1")
                .containsSubsequence("--cap-drop", "ALL").containsSubsequence("--security-opt", "no-new-privileges").containsSubsequence("--user", "1000:1001")
                .containsSubsequence("-v", "/ws/p1/code:/work").containsSubsequence("-v", "/ws/.toolhome:/home/nirmaan").containsSubsequence("-w", "/work");
        assertThat(command).contains("HOME=/home/nirmaan", "JAVA_TOOL_OPTIONS=-Duser.home=/home/nirmaan");
        assertThat(command.subList(command.size() - 3, command.size())).containsExactly("nirmaan-sandbox:latest", "mvn", "test");
        assertThat(String.join(" ", command)).as("nothing of the host is mounted but the two folders").doesNotContain("/var/run/docker.sock").doesNotContain("--privileged");
        assertThat(command.stream().filter(w -> w.equals("-v")).count()).isEqualTo(2);
    }

    @Test
    void theToolHomeIsSharedPerProjectOrChosen(@org.junit.jupiter.api.io.TempDir Path ws) throws Exception {
        assertThat(SandboxConfig.local().toolHome(ws, "p1")).isEqualTo(ws.resolve(".toolhome").toRealPath());
        SandboxConfig perProject = new SandboxConfig("local", "i", "bridge", "4g", "2", "per-project", false);
        assertThat(perProject.toolHome(ws, "p1")).isEqualTo(ws.resolve("p1").resolve("home").toRealPath());
    }
}
