package io.github.llm4j.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JsonRpcTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testRequestSerialization() throws Exception {
        JsonRpcRequest request = new JsonRpcRequest("testMethod", Map.of("key", "value"), 1);
        String json = mapper.writeValueAsString(request);

        assertThat(json).contains("\"jsonrpc\":\"2.0\"");
        assertThat(json).contains("\"method\":\"testMethod\"");
        assertThat(json).contains("\"id\":1");
    }

    @Test
    void testResponseDeserialization() throws Exception {
        String json = "{\"jsonrpc\": \"2.0\", \"result\": {\"foo\": \"bar\"}, \"id\": 1}";
        JsonRpcResponse response = mapper.readValue(json, JsonRpcResponse.class);

        assertThat(response.getId()).isEqualTo(1);
        assertThat(response.getResult()).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) response.getResult()).get("foo")).isEqualTo("bar");
    }

    @Test
    void testErrorDeserialization() throws Exception {
        String json = "{\"jsonrpc\": \"2.0\", \"error\": {\"code\": -32601, \"message\": \"Method not found\"}, \"id\": 1}";
        JsonRpcResponse response = mapper.readValue(json, JsonRpcResponse.class);

        assertThat(response.isError()).isTrue();
        assertThat(response.getError().getCode()).isEqualTo(-32601);
        assertThat(response.getError().getMessage()).isEqualTo("Method not found");
    }

    @Test
    void testNotificationSerialization() throws Exception {
        JsonRpcNotification notif = new JsonRpcNotification("update", null);
        String json = mapper.writeValueAsString(notif);

        assertThat(json).contains("\"method\":\"update\"");
        assertThat(json).doesNotContain("\"id\"");
    }
}
