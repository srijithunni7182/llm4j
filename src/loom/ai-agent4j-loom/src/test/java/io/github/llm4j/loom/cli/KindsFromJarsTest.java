package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.security.SecurityAudit;
import io.github.llm4j.loom.tools.KindDiscoveryTest;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 35, D3 rules 1, 3 and 4 and G37 through {@code weave check} and {@code weave audit}: a kind
 * a jar brings is used, listed with its jar and audited by its own profile; one that does not say
 * what it reaches is assumed to reach everything; one that fails to load is an error.
 */
class KindsFromJarsTest {

    @TempDir Path dir;

    private final ClassLoader before = Thread.currentThread().getContextClassLoader();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    @AfterEach
    void restoreTheClassPath() {
        Thread.currentThread().setContextClassLoader(before);
    }

    /** Puts a folder that lists these kinds on the project's class path, as --classes does. */
    private void onClassPath(String folder, Class<?>... kinds) throws Exception {
        Path root = Files.createDirectories(dir.resolve(folder));
        Path services = Files.createDirectories(root.resolve("META-INF/services"));
        StringBuilder list = new StringBuilder();
        for (Class<?> k : kinds) list.append(k.getName()).append('\n');
        Files.writeString(
                services.resolve(io.github.llm4j.agent.tool.ToolKind.class.getName()), list);
        Thread.currentThread()
                .setContextClassLoader(
                        new URLClassLoader(new URL[] {root.toUri().toURL()}, before));
    }

    private Path script(String text) throws Exception {
        return Files.writeString(dir.resolve("main.loom"), text);
    }

    private WeaveEnv env() {
        return new WeaveEnv(
                m -> null,
                q -> "",
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                Clock.systemUTC(),
                d -> {},
                c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""),
                List.of("weave"),
                k -> null);
    }

    private static final String USES_ECHO =
            """
            budget { tokens: 1000  calls: 5 }
            tool Pages { use: echo_extra }
            agent A { model: "m"  system: "s"  tools: [Pages] }
            workflow Main(q) { delegate "{q}" to A -> r  note "{r}" }
            """;

    @Test
    void checkUsesAndListsAKindAJarBrings() throws Exception {
        onClassPath("echo-module", KindDiscoveryTest.Echo.class);
        int code = WeaveCLI.check(script(USES_ECHO).toFile(), null, false, env());
        String text = out.toString(StandardCharsets.UTF_8);
        assertThat(code).as(text).isZero();
        assertThat(text).contains("ℹ tool kinds from jars: echo_extra (").contains("echo-module");
    }

    @Test
    void theAuditReadsAJarKindsOwnProfile() throws Exception {
        onClassPath("echo-module", KindDiscoveryTest.Echo.class);
        var report = SecurityAudit.audit(new LoomLoader().load(script(USES_ECHO).toString()), "m");
        assertThat(report.json()).contains("\"untrusted\" : [ \"Pages\" ]").doesNotContain("LA14");
    }

    @Test
    void theAuditAssumesTheWorstOfAJarKindThatDoesNotSay() throws Exception {
        onClassPath("silent-module", KindDiscoveryTest.Silent.class);
        var report =
                SecurityAudit.audit(
                        new LoomLoader()
                                .load(
                                        script(USES_ECHO.replace("echo_extra", "silent_extra"))
                                                .toString()),
                        "m");
        assertThat(report.json())
                .contains("LA14")
                .contains("silent_extra\\\" does not say what it reaches")
                .contains("\"outward\" : [ \"Pages\" ]");
    }

    @Test
    void aKindThatFailsToLoadFailsTheCheckNamingItsJar() throws Exception {
        onClassPath("broken-module", KindDiscoveryTest.Broken.class);
        int code =
                WeaveCLI.check(
                        script(
                                        USES_ECHO
                                                .replace("tool Pages { use: echo_extra }\n", "")
                                                .replace("tools: [Pages]", ""))
                                .toFile(),
                        null,
                        false,
                        env());
        String text = out.toString(StandardCharsets.UTF_8);
        assertThat(code).isEqualTo(2);
        assertThat(text)
                .contains("✗")
                .contains("tool kinds")
                .contains("broken-module")
                .contains("needs a library that is not there");
    }
}
