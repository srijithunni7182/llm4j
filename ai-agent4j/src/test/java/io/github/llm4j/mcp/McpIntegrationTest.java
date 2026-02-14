package io.github.llm4j.mcp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpIntegrationTest {

    private McpClient client;
    private StdioMcpTransport transport;

    @BeforeEach
    void setUp() throws Exception {
        // Locate the python script
        File scriptFile = new File("src/test/resources/mock_mcp_server.py");
        assertThat(scriptFile).exists();

        // Ensure python3 is available. If not, this test might fail or should be
        // disabled.
        // For this environment, we assume python3 is present.
        List<String> command = List.of("python3", scriptFile.getAbsolutePath());

        transport = new StdioMcpTransport(command, null);
        client = new McpClient(transport);
        client.initialize();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void testListTools() throws Exception {
        List<Map<String, Object>> tools = client.listTools();
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).get("name")).isEqualTo("echo");
    }

    @Test
    void testCallTool() throws Exception {
        Map<String, Object> args = Map.of("message", "Hello MCP");
        Object result = client.callTool("echo", args);

        assertThat(result).isInstanceOf(Map.class);
        Map<?, ?> resultMap = (Map<?, ?>) result;
        List<?> content = (List<?>) resultMap.get("content");
        assertThat(content).hasSize(1);
        Map<?, ?> textContent = (Map<?, ?>) content.get(0);
        assertThat(textContent.get("text")).isEqualTo("Echo: Hello MCP");
    }
}
