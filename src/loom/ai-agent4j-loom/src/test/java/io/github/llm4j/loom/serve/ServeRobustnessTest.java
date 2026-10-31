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

    /** An output that is slow to take each write, as a busy pipe or a slow runner is. */
    static final class SlowOutput extends java.io.OutputStream {
        final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();

        @Override
        public void write(int b) throws java.io.IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws java.io.IOException {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                throw new java.io.IOException("interrupted before the write finished", e);
            }
            bytes.write(b, off, len);
        }
    }

    @Test
    void theShutdownReplyIsWrittenWholeEvenWhenTheOutputIsSlow() throws Exception {
        java.io.ByteArrayOutputStream request = new java.io.ByteArrayOutputStream();
        Framing.write(
                request,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"loom/shutdown\",\"params\":{}}");
        SlowOutput out = new SlowOutput();

        int status =
                new Server(engine(Map.of()), 2000)
                        .serve(new java.io.ByteArrayInputStream(request.toByteArray()), out);

        assertThat(status).isZero();
        String reply = Framing.read(new java.io.ByteArrayInputStream(out.bytes.toByteArray()));
        assertThat(JSON.readTree(reply).has("result")).isTrue();
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
            // the reader takes frames in order, so a reply to a later request means the cancel was
            // seen
            c.request("loom/ping", null);
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

    // ---- stalls: nothing a request does may make the editor wait on the server

    @Test
    void aPingIsAnsweredWhileEveryWorkerIsBusy() throws Exception {
        java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        Map<String, Server.Engine.Method> m =
                Map.of(
                        "x/block",
                        r -> {
                            gate.await(5, java.util.concurrent.TimeUnit.SECONDS);
                            return JSON.createObjectNode();
                        });
        try (ServeClient c = client(m, 10000)) {
            for (int i = 0; i < Server.WORKERS + 2; i++)
                c.send("x/block", ServeClient.params().put("file", "f" + i));
            long t0 = System.nanoTime();
            JsonNode pong = c.request("loom/ping", null).path("result");
            assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(1000);
            assertThat(pong.path("busy").asInt()).isPositive();
            gate.countDown();
        }
    }

    @Test
    void aHungHandlerIsInterruptedAtTheTimeoutSoItsWorkerIsFreed() throws Exception {
        AtomicInteger interrupted = new AtomicInteger();
        Map<String, Server.Engine.Method> m =
                Map.of(
                        "x/hang",
                        r -> {
                            try {
                                Thread.sleep(60000);
                            } catch (InterruptedException e) {
                                interrupted.incrementAndGet();
                                throw e;
                            }
                            return JSON.createObjectNode();
                        },
                        "x/echo",
                        ECHO);
        try (ServeClient c = client(m, 100)) {
            // more hung requests than workers: only possible if each timeout frees its worker
            for (int i = 0; i < Server.WORKERS * 3; i++) {
                JsonNode e = c.request("x/hang", null).path("error");
                assertThat(e.path("data").path("kind").asText()).isEqualTo("timeout");
            }
            assertThat(c.request("x/echo", ServeClient.params().put("text", "ok")).has("result"))
                    .isTrue();
            assertThat(interrupted.get()).isPositive();
        }
    }

    @Test
    void aNewerRequestForTheSameFileReplacesTheOlderOne() throws Exception {
        Map<String, Server.Engine.Method> m =
                Map.of(
                        "x/review",
                        r -> {
                            if (r.params().path("version").asInt() == 1) Thread.sleep(5000);
                            return JSON.createObjectNode()
                                    .put("v", r.params().path("version").asInt());
                        });
        try (ServeClient c = client(m, 10000)) {
            int first =
                    c.send(
                            "x/review",
                            ServeClient.params().put("file", "a.loom").put("version", 1));
            Thread.sleep(100);
            int second =
                    c.send(
                            "x/review",
                            ServeClient.params().put("file", "a.loom").put("version", 2));
            assertThat(c.await(first).path("error").path("data").path("kind").asText())
                    .isEqualTo("cancelled");
            assertThat(c.await(second).path("result").path("v").asInt()).isEqualTo(2);
        }
    }

    @Test
    void aFullQueueAnswersBusyAtOnceInsteadOfWaiting() throws Exception {
        java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        Map<String, Server.Engine.Method> m =
                Map.of(
                        "x/block",
                        r -> {
                            gate.await(5, java.util.concurrent.TimeUnit.SECONDS);
                            return JSON.createObjectNode();
                        });
        try (ServeClient c = client(m, 20000)) {
            int n = Server.WORKERS + Server.QUEUE + 5;
            for (int i = 0; i < n; i++)
                c.send("x/block", ServeClient.params().put("file", "f" + i));
            JsonNode first = c.next();
            assertThat(first.path("error").path("data").path("kind").asText()).isEqualTo("busy");
            gate.countDown();
        }
    }

    @Test
    void aClientThatStopsReadingDoesNotBlockTheServer() throws Exception {
        // the pipe holds 1 MB; replies beyond that would block a naive writer in the worker
        Map<String, Server.Engine.Method> m =
                Map.of("x/big", r -> JSON.createObjectNode().put("pad", "x".repeat(200_000)));
        try (ServeClient c = client(m, 5000)) {
            long t0 = System.nanoTime();
            for (int i = 0; i < 20; i++) c.send("x/big", ServeClient.params().put("file", "f" + i));
            JsonNode pong = c.request("loom/ping", null).path("result");
            assertThat(pong.isMissingNode()).isFalse();
            assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(5000);
        }
    }
}
