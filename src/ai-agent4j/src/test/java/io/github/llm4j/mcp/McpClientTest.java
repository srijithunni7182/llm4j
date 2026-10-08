package io.github.llm4j.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class McpClientTest {

    @Mock private McpTransport mockTransport;

    @Captor private ArgumentCaptor<Consumer<JsonRpcMessage>> handlerCaptor;

    private McpClient client;
    private Consumer<JsonRpcMessage> transportHandler;

    @BeforeEach
    void setUp() {
        client = new McpClient(mockTransport);
        verify(mockTransport).onMessage(handlerCaptor.capture());
        transportHandler = handlerCaptor.getValue();
    }

    @Test
    void initialize_shouldHandshakeSuccessfully() throws Exception {
        // Mock initialize response
        doAnswer(
                        invocation -> {
                            JsonRpcRequest req = invocation.getArgument(0);
                            if ("initialize".equals(req.getMethod())) {
                                JsonRpcResponse resp = new JsonRpcResponse();
                                resp.setId(req.getId());
                                Map<String, Object> result =
                                        Map.of(
                                                "serverInfo",
                                                        Map.of(
                                                                "name",
                                                                "test-server",
                                                                "version",
                                                                "1.0"),
                                                "capabilities", Collections.emptyMap());
                                resp.setResult(result);
                                // Simulate async response
                                Executors.newSingleThreadExecutor()
                                        .submit(() -> transportHandler.accept(resp));
                            }
                            return null;
                        })
                .when(mockTransport)
                .sendMessage(any(JsonRpcRequest.class));

        client.initialize();

        verify(mockTransport, times(2))
                .sendMessage(any()); // initialize + notifications/initialized
    }

    @Test
    void listTools_shouldReturnToolsList() throws Exception {
        // First initialize
        initializeClient();

        // Mock tools/list response
        doAnswer(
                        invocation -> {
                            JsonRpcRequest req = invocation.getArgument(0);
                            if ("tools/list".equals(req.getMethod())) {
                                JsonRpcResponse resp = new JsonRpcResponse();
                                resp.setId(req.getId());
                                Map<String, Object> tool =
                                        Map.of("name", "test-tool", "description", "test");
                                Map<String, Object> result = Map.of("tools", List.of(tool));
                                resp.setResult(result);
                                transportHandler.accept(resp);
                            }
                            return null;
                        })
                .when(mockTransport)
                .sendMessage(
                        argThat(
                                msg ->
                                        msg instanceof JsonRpcRequest
                                                && "tools/list"
                                                        .equals(
                                                                ((JsonRpcRequest) msg)
                                                                        .getMethod())));

        List<Map<String, Object>> tools = client.listTools();

        assertThat(tools).hasSize(1);
        assertThat(tools.get(0)).containsEntry("name", "test-tool");
    }

    @Test
    void callTool_shouldReturnResult() throws Exception {
        initializeClient();

        // Mock tools/call response
        doAnswer(
                        invocation -> {
                            JsonRpcRequest req = invocation.getArgument(0);
                            if ("tools/call".equals(req.getMethod())) {
                                JsonRpcResponse resp = new JsonRpcResponse();
                                resp.setId(req.getId());
                                Map<String, Object> result =
                                        Map.of("content", List.of(Map.of("text", "success")));
                                resp.setResult(result);
                                transportHandler.accept(resp);
                            }
                            return null;
                        })
                .when(mockTransport)
                .sendMessage(
                        argThat(
                                msg ->
                                        msg instanceof JsonRpcRequest
                                                && "tools/call"
                                                        .equals(
                                                                ((JsonRpcRequest) msg)
                                                                        .getMethod())));

        Object result = client.callTool("test-tool", Map.of("arg", "val"));

        assertThat(result).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> resultMap = (Map<String, Object>) result;
        assertThat(resultMap).containsKey("content");
    }

    @Test
    void callTool_shouldThrowException_whenTransportFails() throws Exception {
        initializeClient();

        doAnswer(
                        invocation -> {
                            JsonRpcRequest req = invocation.getArgument(0);
                            JsonRpcResponse resp = new JsonRpcResponse();
                            resp.setId(req.getId());
                            JsonRpcError error = new JsonRpcError(500, "Internal Error", null);
                            resp.setError(error);
                            transportHandler.accept(resp);
                            return null;
                        })
                .when(mockTransport)
                .sendMessage(any(JsonRpcRequest.class));

        assertThatThrownBy(() -> client.callTool("broken-tool", Collections.emptyMap()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Tool execution failed");
    }

    private void initializeClient() throws Exception {
        doAnswer(
                        invocation -> {
                            JsonRpcRequest req = invocation.getArgument(0);
                            if ("initialize".equals(req.getMethod())) {
                                JsonRpcResponse resp = new JsonRpcResponse();
                                resp.setId(req.getId());
                                Map<String, Object> result =
                                        Map.of(
                                                "serverInfo",
                                                        Map.of(
                                                                "name",
                                                                "test-server",
                                                                "version",
                                                                "1.0"),
                                                "capabilities", Collections.emptyMap());
                                resp.setResult(result);
                                transportHandler.accept(resp);
                            }
                            return null;
                        })
                .when(mockTransport)
                .sendMessage(any(JsonRpcRequest.class));

        client.initialize();
        reset(mockTransport); // Reset verify counts, but need to re-capture? No, handler is same.
        // Re-establish handler just in case reset cleared it? No, reset clears
        // interactions/stubbing.
        // We need to re-stub invalid calls or let them fall through if using lenient?
        // Actually, just standard stubbing is fine.
    }
}
