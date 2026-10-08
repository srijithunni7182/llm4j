package io.github.llm4j.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class StdioMcpTransportTest {

    @Test
    void testTransportCommunication() throws Exception {
        // Use 'cat' to echo back input on linux
        List<String> command = List.of("cat");

        StdioMcpTransport transport = new StdioMcpTransport(command, null);
        BlockingQueue<JsonRpcMessage> received = new LinkedBlockingQueue<>();

        transport.onMessage(received::add);
        transport.connect();

        // Send a request
        JsonRpcRequest request = new JsonRpcRequest("ping", Map.of("a", 1), 123);
        transport.sendMessage(request);

        // Should receive it back because cat echoes stdin to stdout
        // However, 'cat' echoes the raw bytes.
        // Our transport expects NEWLINE delimited JSON.
        // If we write "json\n", cat writes "json\n".
        // Transport reads "json" line.
        // It tries to parse "json" as JsonRpcMessage.
        // Since we sent a valid JsonRpcRequest, it should parse back as one.

        JsonRpcMessage message = received.poll(5, TimeUnit.SECONDS);
        assertThat(message).isNotNull();
        assertThat(message).isInstanceOf(JsonRpcRequest.class);
        assertThat(((JsonRpcRequest) message).getMethod()).isEqualTo("ping");

        transport.close();
    }
}
