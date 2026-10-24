package io.github.llm4j.loom.serve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Test helper: a server on a pair of pipes and a client that speaks to it. */
public final class ServeClient implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PipedOutputStream toServer = new PipedOutputStream();
    private final PipedInputStream serverIn;
    private final PipedOutputStream serverOut = new PipedOutputStream();
    private final PipedInputStream fromServer;
    private final CompletableFuture<Integer> exit;
    private int nextId = 1;

    public ServeClient(Server server) throws IOException {
        serverIn = new PipedInputStream(toServer, 1 << 16);
        fromServer = new PipedInputStream(serverOut, 1 << 20);
        exit = new CompletableFuture<>();
        Thread t =
                new Thread(
                        () -> {
                            try {
                                int status = server.serve(serverIn, serverOut);
                                serverOut.close();
                                exit.complete(status);
                            } catch (IOException e) {
                                exit.complete(-1);
                            }
                        },
                        "test-server");
        t.setDaemon(true);
        t.start();
    }

    public ObjectNode request(String method, ObjectNode params) throws IOException {
        int id = send(method, params);
        return await(id);
    }

    public int send(String method, ObjectNode params) throws IOException {
        int id = nextId++;
        ObjectNode m = JSON.createObjectNode();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("method", method);
        m.set("params", params == null ? JSON.createObjectNode() : params);
        Framing.write(toServer, m.toString());
        return id;
    }

    public void notify(String method, ObjectNode params) throws IOException {
        ObjectNode m = JSON.createObjectNode();
        m.put("jsonrpc", "2.0");
        m.put("method", method);
        m.set("params", params);
        Framing.write(toServer, m.toString());
    }

    /** Writes bytes as they are: for a test of a broken or split frame. */
    public void raw(byte[] bytes) throws IOException {
        toServer.write(bytes);
        toServer.flush();
    }

    public void rawFrame(String body) throws IOException {
        Framing.write(toServer, body);
    }

    /** The next reply from the server, whichever request it answers. */
    public ObjectNode next() throws IOException {
        String frame = Framing.read(fromServer);
        if (frame == null) throw new IOException("the server closed its output");
        return (ObjectNode) JSON.readTree(frame);
    }

    /** The reply to request {@code id}; replies to other requests that come first are dropped. */
    public ObjectNode await(int id) throws IOException {
        while (true) {
            ObjectNode reply = next();
            if (reply.path("id").asInt(-1) == id) return reply;
        }
    }

    public static ObjectNode params() {
        return JSON.createObjectNode();
    }

    public int exitStatus() throws Exception {
        return exit.get(10, TimeUnit.SECONDS);
    }

    public void closeInput() throws IOException {
        toServer.close();
    }

    @Override
    public void close() throws IOException {
        try {
            toServer.close();
        } catch (IOException ignored) {
            // already closed
        }
    }
}
