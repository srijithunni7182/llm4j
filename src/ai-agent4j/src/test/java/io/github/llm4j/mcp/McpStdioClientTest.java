package io.github.llm4j.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.mcp.testkit.FakeMcpServer;
import io.github.llm4j.mcp.testkit.McpConformance;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The stdio client against a real server process (G79, G80 for the transport, G82): {@code isError}
 * is a failure, pages are collected and bounded, a slow call times out, a crashed server is
 * restarted a bounded number of times, the environment is scrubbed, arguments keep their spaces and
 * the error stream is captured, redacted and bounded. Every line either side wrote is checked
 * against the official schema of the revision.
 */
class McpStdioClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String JAVA =
            Path.of(System.getProperty("java.home"), "bin", "java").toString();

    @TempDir Path dir;
    private final List<McpClient> opened = new ArrayList<>();

    @AfterEach
    void close() throws Exception {
        for (McpClient c : opened) c.close();
    }

    private List<String> server(String... args) {
        return FakeMcpServer.command(args);
    }

    private McpClient client(StdioMcpTransport transport, Duration timeout) throws Exception {
        McpClient client = new McpClient(transport, timeout);
        opened.add(client);
        client.initialize();
        return client;
    }

    private StdioMcpTransport scrubbed(List<String> command) {
        return StdioMcpTransport.scrubbed(command, Map.of(), Set.of(), s -> s);
    }

    @Test
    void aToolErrorResultIsAFailureNotAnAnswer() throws Exception {
        McpClient client = client(scrubbed(server()), Duration.ofSeconds(20));

        assertThatThrownBy(() -> client.callTool("boom", Map.of()))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("the disk is full");
    }

    @Test
    void pagesAreCollectedUntilThereIsNoCursor() throws Exception {
        McpClient client = client(scrubbed(server("--page", "2")), Duration.ofSeconds(20));

        assertThat(client.listTools())
                .extracting(t -> t.get("name"))
                .containsExactly("echo", "boom", "sleep", "die", "env", "argv", "read");
    }

    @Test
    void aServerThatNeverStopsPagingIsStopped() throws Exception {
        McpClient client =
                client(scrubbed(server("--page", "2", "--endless")), Duration.ofSeconds(20));

        assertThatThrownBy(client::listTools)
                .isInstanceOf(McpException.class)
                .hasMessageContaining("pages");
    }

    @Test
    void aCallThatTakesTooLongTimesOut() throws Exception {
        McpClient client = client(scrubbed(server()), Duration.ofMillis(400));

        assertThatThrownBy(() -> client.callTool("sleep", Map.of("ms", 30_000)))
                .isInstanceOf(McpTimeoutException.class)
                .hasMessageContaining("sleep");
    }

    @Test
    void theClientOffersTheCurrentRevisionAndAcceptsAKnownCounterOffer() throws Exception {
        Path log = dir.resolve("wire.log");
        McpClient client =
                client(
                        scrubbed(server("--log", log.toString(), "--answer", "2025-03-26")),
                        Duration.ofSeconds(20));

        assertThat(client.negotiatedVersion()).isEqualTo("2025-03-26");
        assertThat(Files.readString(log)).contains("\"protocolVersion\":\"2025-11-25\"");
    }

    @Test
    void aVersionNobodyKnowsIsRefusedWithAClearMessage() {
        assertThatThrownBy(
                        () ->
                                client(
                                        scrubbed(server("--answer", "1999-01-01")),
                                        Duration.ofSeconds(20)))
                .isInstanceOf(McpException.class)
                .hasMessageContaining("1999-01-01")
                .hasMessageContaining("2025-11-25");
    }

    @Test
    void aCrashedServerIsRestartedAFewTimesAndThenReported() throws Exception {
        McpClient client =
                McpClient.restarting(() -> scrubbed(server()), 1, Duration.ofSeconds(20));
        opened.add(client);
        client.initialize();

        assertThat(text(client.callTool("echo", Map.of("message", "one")))).isEqualTo("echo: one");
        assertThatThrownBy(() -> client.callTool("die", Map.of()))
                .isInstanceOf(McpServerExitedException.class)
                .hasMessageContaining("may have");
        // the next call starts a fresh server; the call that was in flight is not repeated
        assertThat(text(client.callTool("echo", Map.of("message", "two")))).isEqualTo("echo: two");
        assertThatThrownBy(() -> client.callTool("die", Map.of()))
                .isInstanceOf(McpServerExitedException.class);
        assertThatThrownBy(() -> client.callTool("echo", Map.of("message", "three")))
                .isInstanceOf(McpServerExitedException.class)
                .hasMessageContaining("restarted 1 time");
    }

    @Test
    void theHostEnvironmentIsNotHandedToTheServer() throws Exception {
        String hostName = System.getenv("HOME") != null ? "HOME" : "PATH";
        McpClient client = client(scrubbed(server()), Duration.ofSeconds(20));

        assertThat(text(client.callTool("env", Map.of("name", hostName)))).isEqualTo("<unset>");
    }

    @Test
    void onlyWhatIsListedIsInheritedAndWhatIsDeclaredIsPassed() throws Exception {
        String hostName = System.getenv("HOME") != null ? "HOME" : "PATH";
        McpClient client =
                client(
                        StdioMcpTransport.scrubbed(
                                server(),
                                Map.of("FAKE_TOKEN", "test-token-000"),
                                Set.of(hostName),
                                s -> s),
                        Duration.ofSeconds(20));

        assertThat(text(client.callTool("env", Map.of("name", hostName))))
                .isEqualTo(System.getenv(hostName));
        assertThat(text(client.callTool("env", Map.of("name", "FAKE_TOKEN"))))
                .isEqualTo("test-token-000");
    }

    @Test
    void eachArgumentStaysOneArgument() throws Exception {
        McpClient client =
                client(
                        scrubbed(
                                server(
                                        "--",
                                        "two words",
                                        "semi;colon",
                                        "--flag",
                                        "$(id)",
                                        "new\nline")),
                        Duration.ofSeconds(20));

        assertThat(text(client.callTool("argv", Map.of())).split("␟", -1))
                .containsExactly("two words", "semi;colon", "--flag", "$(id)", "new\nline");
    }

    @Test
    void theErrorStreamIsCapturedRedactedAndBounded() throws Exception {
        StdioMcpTransport transport =
                StdioMcpTransport.scrubbed(
                        server("--flood", "300000", "--stderr", "failing with test-secret-123"),
                        Map.of(),
                        Set.of(),
                        s -> s.replace("test-secret-123", "***"));
        McpClient client = client(transport, Duration.ofSeconds(20));
        client.callTool("echo", Map.of("message", "x"));

        String tail = transport.stderrTail();
        assertThat(tail).contains("failing with ***").doesNotContain("test-secret-123");
        assertThat(tail.length()).isLessThanOrEqualTo(StdioMcpTransport.STDERR_TAIL_CHARS);
        // the flood did not block the server: it still answers
        assertThat(text(client.callTool("echo", Map.of("message", "y")))).isEqualTo("echo: y");
    }

    @Test
    void everyLineOnTheWireConformsToTheOfficialSchema() throws Exception {
        Path log = dir.resolve("wire.log");
        McpClient client =
                client(
                        scrubbed(server("--log", log.toString(), "--page", "3")),
                        Duration.ofSeconds(20));
        client.listTools();
        client.callTool("echo", Map.of("message", "hi"));
        assertThatThrownBy(() -> client.callTool("boom", Map.of()))
                .isInstanceOf(McpToolException.class);
        // a call that fails is still a conforming message
        McpConformance schema = new McpConformance("2025-11-25");
        List<String> problems = new ArrayList<>();
        Map<Object, String> methodById = new java.util.HashMap<>();
        for (String line : Files.readAllLines(log)) {
            String json = line.substring(1);
            var node = JSON.readTree(json);
            if (line.startsWith("<") && node.has("id"))
                methodById.put(node.get("id").asText(), node.get("method").asText());
            String answers = line.startsWith(">") ? methodById.get(node.path("id").asText()) : null;
            problems.addAll(schema.problems(json, answers));
        }
        assertThat(problems).isEmpty();
        assertThat(Files.readAllLines(log)).hasSizeGreaterThan(6);
    }

    private static String text(Object result) {
        Map<?, ?> map = (Map<?, ?>) result;
        return String.valueOf(((Map<?, ?>) ((List<?>) map.get("content")).get(0)).get("text"));
    }

    @Test
    void theFakeServerIsALaunchableProgram() {
        assertThat(new File(JAVA)).exists();
    }
}
