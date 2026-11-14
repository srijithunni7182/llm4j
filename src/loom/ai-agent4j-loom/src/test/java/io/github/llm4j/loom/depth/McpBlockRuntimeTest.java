package io.github.llm4j.loom.depth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LoomLoadException;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.mcp.testkit.FakeMcpServer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code mcp} block of a script against a real server process (phase 36a, D6 and D7; guards
 * G79 and G80): its options, a scrubbed environment, approval for the tools it brings, and a failed
 * tool list as a load error.
 */
class McpBlockRuntimeTest {

    @TempDir Path dir;

    /** The block for a fake server: {@code cmd} is the JDK, {@code args} the rest. */
    private String block(String options, String... serverArgs) {
        List<String> command = FakeMcpServer.command(serverArgs);
        List<String> quoted = new ArrayList<>();
        for (String a : command.subList(1, command.size())) quoted.add("\"" + a + "\"");
        return "mcp Fake { transport: \"stdio\" cmd: \""
                + command.get(0)
                + "\" args: ["
                + String.join(", ", quoted)
                + "] "
                + options
                + " }\n";
    }

    private static final String AGENT =
            "agent A { model: \"m\" system: \"s\" mcp_servers: [Fake] %s }\n"
                    + "workflow Main() { delegate \"go\" to A -> r }\n";

    private String script(String options, String agentOptions, String... serverArgs) {
        return block(options, serverArgs) + AGENT.formatted(agentOptions);
    }

    @Test
    void theBlockTakesArgsEnvIsolationAndATimeout() {
        var parsed =
                new LoomParser(
                                new Lexer(
                                                "mcp Files { transport: \"stdio\" cmd: \"npx\" args: [\"-y\", \"a b\"]"
                                                        + " isolation: process env_inherit: [\"PATH\"]"
                                                        + " env: { TOKEN: env.FILES_TOKEN, MODE: \"read\" } timeout: 20s"
                                                        + " reach: reads pin: \"abc\" }\nworkflow M() { note \"x\" }")
                                        .tokenize())
                        .parseScript()
                        .getMcpServers()
                        .get(0);

        assertThat(parsed.getArgs()).containsExactly("-y", "a b");
        assertThat(parsed.getIsolation()).isEqualTo("process");
        assertThat(parsed.getEnvInherit()).containsExactly("PATH");
        assertThat(parsed.getEnvValues()).containsKeys("TOKEN", "MODE");
        assertThat(parsed.getEnvValues().get("TOKEN").isReference()).isTrue();
        assertThat(parsed.getTimeout()).hasSeconds(20);
        assertThat(parsed.getReach()).isEqualTo("reads");
        assertThat(parsed.getPin()).isEqualTo("abc");
    }

    @Test
    void anIsolatedServerSeesOnlyItsDeclaredEnvironment() {
        Harness h = new Harness(dir);
        h.env.put("FAKE_TOKEN_SOURCE", "test-token-000");
        String hostName = System.getenv("HOME") != null ? "HOME" : "PATH";
        h.answers(
                Harness.call("env", "{\"name\": \"" + hostName + "\"}"),
                Harness.call("env", "{\"name\": \"FAKE_TOKEN\"}"),
                Harness.done("finished"));
        HarnessExecutor e =
                h.executor(
                        script(
                                "isolation: process env: { FAKE_TOKEN: env.FAKE_TOKEN_SOURCE }",
                                ""),
                        null);
        e.initialize();
        e.executeWorkflow("Main", java.util.Map.of());

        assertThat(h.seen()).contains("<unset>").contains("test-token-000");
        e.shutdown();
    }

    @Test
    void approveAllReachesTheToolsAnMcpServerBrings() throws Exception {
        Path log = dir.resolve("wire.log");
        Harness h = new Harness(dir);
        List<String> asked = new ArrayList<>();
        h.answers(Harness.call("echo", "{\"message\": \"hi\"}"), Harness.done("finished"));
        HarnessExecutor e =
                h.executor(
                        script("isolation: process", "approve: all", "--log", log.toString()),
                        x ->
                                x.setHumanInterface(
                                        message -> {
                                            asked.add(message);
                                            return "no";
                                        }));
        e.initialize();
        e.executeWorkflow("Main", java.util.Map.of());

        assertThat(asked).isNotEmpty();
        assertThat(Files.readString(log)).doesNotContain("tools/call");
        e.shutdown();
    }

    @Test
    void aToolTheAgentMayCallWithoutApprovalStillRuns() throws Exception {
        Path log = dir.resolve("wire.log");
        Harness h = new Harness(dir);
        h.answers(Harness.call("echo", "{\"message\": \"hi\"}"), Harness.done("finished"));
        HarnessExecutor e =
                h.executor(script("isolation: process", "", "--log", log.toString()), null);
        e.initialize();
        e.executeWorkflow("Main", java.util.Map.of());

        assertThat(Files.readString(log)).contains("tools/call");
        assertThat(h.seen()).contains("echo: hi");
        e.shutdown();
    }

    @Test
    void aFailedToolListIsALoadErrorNotALogLine() {
        Harness h = new Harness(dir);
        HarnessExecutor e =
                h.executor(script("isolation: process", "", "--fail-list"), null);

        assertThatThrownBy(e::initialize)
                .isInstanceOf(LoomLoadException.class)
                .hasMessageContaining("mcp Fake")
                .hasMessageContaining("tool list");
    }

    @Test
    void aFailingToolShowsTheServersErrorStreamRedacted() {
        Harness h = new Harness(dir);
        h.env.put("FAKE_TOKEN_SOURCE", "test-token-000");
        h.answers(Harness.call("boom", "{}"), Harness.done("finished"));
        HarnessExecutor e =
                h.executor(
                        script(
                                "isolation: process env: { FAKE_TOKEN: env.FAKE_TOKEN_SOURCE }",
                                "",
                                "--stderr",
                                "crashed holding test-token-000"),
                        null);
        e.initialize();
        e.executeWorkflow("Main", java.util.Map.of());

        assertThat(h.seen()).contains("the disk is full").contains("crashed holding").doesNotContain("test-token-000");
        e.shutdown();
    }

    @Test
    void anExplicitLevelOtherThanNoneOrProcessIsRefusedForNow() {
        Harness h = new Harness(dir);
        assertThatThrownBy(() -> h.ready(script("isolation: container", "")))
                .isInstanceOf(LoomLoadException.class)
                .hasMessageContaining("isolation")
                .hasMessageContaining("none or process");
    }

    @Test
    void aCredentialLookingVariableMustBeAReferenceNotALiteral() {
        Harness h = new Harness(dir);
        assertThatThrownBy(
                        () ->
                                h.ready(
                                        script(
                                                "isolation: process env: { API_TOKEN: \"sk-test-000\" }",
                                                "")))
                .isInstanceOf(LoomLoadException.class)
                .hasMessageContaining("API_TOKEN")
                .hasMessageContaining("secret.");
    }

    @Test
    void noIsolationLineStillWorksAsBeforeButWarns() {
        Harness h = new Harness(dir);
        HarnessExecutor e = h.executor(script("", ""), null);
        e.initialize();
        var warnings =
                new io.github.llm4j.loom.execution.ScriptValidator()
                        .validate(
                                new LoomParser(new Lexer(script("", "")).tokenize()).parseScript(),
                                new io.github.llm4j.loom.execution.ScriptValidator.Context()
                                        .registeredTools(java.util.Set.of()));
        assertThat(warnings)
                .anySatisfy(
                        p ->
                                assertThat(p.toString())
                                        .contains("mcp Fake")
                                        .contains("isolation:"));
        e.shutdown();
    }
}
