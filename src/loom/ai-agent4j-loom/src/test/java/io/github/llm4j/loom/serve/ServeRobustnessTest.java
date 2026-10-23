package io.github.llm4j.loom.serve;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * R13, R16 of spec 10: the server survives what a client can send, and answers each request once.
 */
class ServeRobustnessTest {

    static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** An engine of a few methods for protocol tests. */
    static Server.Engine engine(Map<String, Server.Engine.Method> methods) {
        return new Server.Engine() {
            @Override
            public Map<String, Method> methods() {
                return methods;
            }

            @Override
            public String version() {
                return "test";
            }
        };
    }

    static final Server.Engine.Method ECHO =
            r -> JSON.createObjectNode().put("echo", r.params().path("text").asText());

    ServeClient client(Map<String, Server.Engine.Method> methods, long timeout) throws Exception {
        return new ServeClient(new Server(engine(methods), timeout));
    }

    @Test
    void theHandshakeSaysWhichProtocolAndWhichWeave() throws Exception {
        try (ServeClient c = client(Map.of("x/echo", ECHO), 2000)) {
            JsonNode r = c.request("initialize", ServeClient.params()).path("result");
            assertThat(r.path("protocol").asInt()).isEqualTo(Server.PROTOCOL);
            assertThat(r.path("weave").asText()).isEqualTo("test");
            assertThat(r.path("methods").toString()).contains("x/echo");
        }
    }

    @Test
    void anUnknownMethodIsATypedErrorAndTheNextRequestIsServed() throws Exception {
        try (ServeClient c = client(Map.of("x/echo", ECHO), 2000)) {
            JsonNode e = c.request("no/such", null).path("error");
            assertThat(e.path("code").asInt()).isEqualTo(RpcError.UNKNOWN_METHOD);
            assertThat(e.path("data").path("kind").asText()).isEqualTo("unknown-method");
            assertThat(
                            c.request("x/echo", ServeClient.params().put("text", "hi"))
                                    .path("result")
                                    .path("echo")
                                    .asText())
                    .isEqualTo("hi");
        }
    }

    @Test
    void unknownFieldsAreIgnoredAndTheRequestVersionComesBack() throws Exception {
        try (ServeClient c = client(Map.of("x/echo", ECHO), 2000)) {
            ObjectNode p =
                    ServeClient.params().put("text", "a").put("version", 7).put("future", true);
            JsonNode r = c.request("x/echo", p).path("result");
            assertThat(r.path("echo").asText()).isEqualTo("a");
            assertThat(r.path("requestVersion").asInt()).isEqualTo(7);
        }
    }

    @Test
    void malformedJsonGetsAnErrorReplyAndTheServerCarriesOn() throws Exception {
        try (ServeClient c = client(Map.of("x/echo", ECHO), 2000)) {
            c.rawFrame("{ this is not json");
            JsonNode e = c.next().path("error");
            assertThat(e.path("code").asInt()).isEqualTo(RpcError.PARSE);
            assertThat(
                            c.request("x/echo", ServeClient.params().put("text", "still here"))
                                    .path("result")
                                    .path("echo")
                                    .asText())
                    .isEqualTo("still here");
        }
    }

    @Test
    void aRequestWithoutAMethodIsInvalid() throws Exception {
        try (ServeClient c = client(Map.of(), 2000)) {
            c.rawFrame("{\"jsonrpc\":\"2.0\",\"id\":9}");
            assertThat(c.next().path("error").path("code").asInt())
                    .isEqualTo(RpcError.INVALID_REQUEST);
        }
    }

    @Test
    void aHandlerThatThrowsBecomesAnErrorNotAnExit() throws Exception {
        Map<String, Server.Engine.Method> m =
                Map.of(
                        "x/boom",
                        r -> {
                            throw new IllegalStateException("a rule crashed");
                        },
                        "x/echo",
                        ECHO);
        try (ServeClient c = client(m, 2000)) {
            JsonNode e = c.request("x/boom", null).path("error");
            assertThat(e.path("message").asText()).contains("a rule crashed");
            assertThat(e.path("data").path("kind").asText()).isEqualTo("engine-failure");
            assertThat(c.request("x/echo", ServeClient.params().put("text", "ok")).has("result"))
                    .isTrue();
        }
    }

    @Test
    void aRequestOverTheTimeLimitIsAbandonedWithATimeoutError() throws Exception {
        Map<String, Server.Engine.Method> m =
                Map.of(
                        "x/slow",
                        r -> {
                            Thread.sleep(5000);
                            return JSON.createObjectNode();
                        },
                        "x/echo",
                        ECHO);
        try (ServeClient c = client(m, 150)) {
            JsonNode e = c.request("x/slow", null).path("error");
            assertThat(e.path("data").path("kind").asText()).isEqualTo("timeout");
            assertThat(c.request("x/echo", ServeClient.params().put("text", "after")).has("result"))
                    .isTrue();
        }
    }

    @Test
    void aClosedInputEndsTheServerCleanly() throws Exception {
        ServeClient c = client(Map.of("x/echo", ECHO), 2000);
        c.request("x/echo", ServeClient.params().put("text", "a"));
        c.closeInput();
        assertThat(c.exitStatus()).isZero();
    }

    @Test
    void shutdownAnswersAndEnds() throws Exception {
        ServeClient c = client(Map.of(), 2000);
        assertThat(c.request("loom/shutdown", null).has("result")).isTrue();
        assertThat(c.exitStatus()).isZero();
    }

    @Test
    void aFrameSplitIntoPiecesIsReadWhole() throws Exception {
        try (ServeClient c = client(Map.of("x/echo", ECHO), 2000)) {
            String body =
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"x/echo\",\"params\":{\"text\":\"split ✓\"}}";
            byte[] all =
                    ("Content-Length: "
                                    + body.getBytes(StandardCharsets.UTF_8).length
                                    + "\r\n\r\n"
                                    + body)
                            .getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < all.length; i += 7) {
                c.raw(java.util.Arrays.copyOfRange(all, i, Math.min(all.length, i + 7)));
                Thread.sleep(2);
            }
            assertThat(c.await(1).path("result").path("echo").asText()).isEqualTo("split ✓");
        }
    }

    @Test
    void aFrameOverTheLimitIsRefusedAndTheServerStops() throws Exception {
        ServeClient c = client(Map.of("x/echo", ECHO), 2000);
        c.raw(
                ("Content-Length: " + (Framing.MAX_BYTES + 1) + "\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
        JsonNode e = c.next().path("error");
        assertThat(e.path("data").path("kind").asText()).isEqualTo("bad-frame");
        assertThat(c.exitStatus()).isEqualTo(1);
    }

    @Test
    void aMessageWithoutContentLengthIsRefused() throws Exception {
        ServeClient c = client(Map.of(), 2000);
        c.raw("X-Other: 1\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        assertThat(c.next().path("error").path("data").path("kind").asText())
                .isEqualTo("bad-frame");
    }

    @Test
    void aRequestCancelledBeforeItStartsIsAnsweredCancelled() throws Exception {
        AtomicInteger started = new AtomicInteger();
        java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        Map<String, Server.Engine.Method> m =
                Map.of(
                        "x/block",
                        r -> {
                            started.incrementAndGet();
                            gate.await(3, java.util.concurrent.TimeUnit.SECONDS);
                            return JSON.createObjectNode().put("done", true);
                        });
        try (ServeClient c = client(m, 5000)) {
            int id = c.send("x/block", null);
            Thread.sleep(100);
            // cancel while it runs: its answer is dropped and the reply says so
            c.notify("loom/cancel", ServeClient.params().put("id", id));
            gate.countDown();
            JsonNode e = c.await(id).path("error");
            assertThat(e.path("data").path("kind").asText()).isEqualTo("cancelled");
        }
    }

    @Test
    void manyRequestsAreEachAnsweredOnce() throws Exception {
        try (ServeClient c = client(Map.of("x/echo", ECHO), 2000)) {
            int n = 50;
            int[] ids = new int[n];
            for (int i = 0; i < n; i++)
                ids[i] = c.send("x/echo", ServeClient.params().put("text", "m" + i));
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            for (int i = 0; i < n; i++) {
                JsonNode r = c.next();
                assertThat(seen.add(r.path("id").asInt())).isTrue();
                assertThat(r.path("result").path("echo").asText())
                        .isEqualTo("m" + (r.path("id").asInt() - 1));
            }
            assertThat(seen).hasSize(n);
        }
    }
}
