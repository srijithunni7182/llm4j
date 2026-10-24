package io.github.llm4j.loom.debug.dap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.llm4j.loom.execution.LLMClientFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** An editor, for tests: sends requests to a {@link DapServer} and collects what comes back. */
final class DapClient implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PipedOutputStream toServer = new PipedOutputStream();
    private final PipedInputStream fromClient;
    private final PipedOutputStream serverOut = new PipedOutputStream();
    private final PipedInputStream clientIn;
    private final Thread thread;
    private int seq = 1;

    /** Everything that is not the response to the last request, in order. */
    final List<JsonNode> events = new ArrayList<>();

    DapClient(LLMClientFactory models) throws IOException {
        fromClient = new PipedInputStream(toServer, 1 << 16);
        clientIn = new PipedInputStream(serverOut, 1 << 16);
        DapServer server = new DapServer(fromClient, serverOut, models, k -> null);
        thread =
                new Thread(
                        () -> {
                            try {
                                server.serve();
                                serverOut.close();
                            } catch (IOException e) {
                                throw new java.io.UncheckedIOException(e);
                            }
                        },
                        "test-dap");
        thread.setDaemon(true);
        thread.start();
    }

    DapClient() throws IOException {
        this(
                m -> {
                    throw new IllegalStateException("a mock run calls no real model");
                });
    }

    /**
     * Sends a request and returns its response; events seen on the way are kept in {@link #events}.
     */
    JsonNode request(String command, ObjectNode arguments) throws IOException {
        ObjectNode m = JSON.createObjectNode();
        int mine = seq++;
        m.put("seq", mine).put("type", "request").put("command", command);
        if (arguments != null) m.set("arguments", arguments);
        raw(JSON.writeValueAsBytes(m));
        while (true) {
            JsonNode in = next();
            if (in == null) throw new IOException("the server closed before answering " + command);
            if ("response".equals(in.path("type").asText())
                    && in.path("request_seq").asInt() == mine) return in;
            events.add(in);
        }
    }

    JsonNode request(String command) throws IOException {
        return request(command, null);
    }

    void raw(byte[] body) throws IOException {
        toServer.write(
                ("Content-Length: " + body.length + "\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
        toServer.write(body);
        toServer.flush();
    }

    JsonNode next() throws IOException {
        int length = -1;
        while (true) {
            String line = headerLine(clientIn);
            if (line == null) return null;
            if (line.isEmpty()) break;
            if (line.toLowerCase().startsWith("content-length:"))
                length = Integer.parseInt(line.substring(15).trim());
        }
        return JSON.readTree(clientIn.readNBytes(length));
    }

    private static String headerLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        while (true) {
            int c = in.read();
            if (c < 0) return null;
            if (c == '\n') {
                String s = b.toString(StandardCharsets.UTF_8);
                return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
            }
            b.write(c);
        }
    }

    /** The events of this name seen so far. */
    List<JsonNode> events(String name) {
        return events.stream().filter(e -> name.equals(e.path("event").asText())).toList();
    }

    ObjectNode args() {
        return JSON.createObjectNode();
    }

    @Override
    public void close() throws IOException {
        try {
            toServer.close();
        } catch (IOException ignored) {
            // already closed
        }
        try {
            thread.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
