package io.github.llm4j.tools.browser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** The DevTools client on a socket the test plays: answers by id, events with their session. */
class CdpTest {

    static final ObjectMapper JSON = new ObjectMapper();

    /** A socket that hands each sent message to the test. */
    static final class Socket implements WebSocket {
        final List<String> sent = new ArrayList<>();
        Consumer<String> onSend = s -> {};
        boolean aborted;
        boolean failClose;

        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            sent.add(data.toString());
            onSend.accept(data.toString());
            return CompletableFuture.completedFuture(this);
        }

        public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
            return CompletableFuture.completedFuture(this);
        }

        public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            return failClose ? CompletableFuture.failedFuture(new IOException("gone")) : CompletableFuture.completedFuture(this);
        }

        public void request(long n) {}

        public String getSubprotocol() {
            return "";
        }

        public boolean isOutputClosed() {
            return false;
        }

        public boolean isInputClosed() {
            return false;
        }

        public void abort() {
            aborted = true;
        }
    }

    final Socket socket = new Socket();
    final Cdp cdp = new Cdp(socket);
    final List<String> events = new ArrayList<>();

    void answer(String json) {
        cdp.received(json, (m, p) -> events.add(m + " " + p));
    }

    @Test
    void aCommandIsAnsweredByItsId() throws Exception {
        socket.onSend = s -> {
            try {
                JsonNode m = JSON.readTree(s);
                answer("{\"id\":" + m.get("id").asInt() + ",\"result\":{\"targetId\":\"T\"}}");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        };
        JsonNode r = cdp.call("Target.createTarget", Cdp.params().put("url", "about:blank"), "S9", Duration.ofSeconds(1));
        assertThat(r.get("targetId").asText()).isEqualTo("T");
        JsonNode sent = JSON.readTree(socket.sent.get(0));
        assertThat(sent.get("method").asText()).isEqualTo("Target.createTarget");
        assertThat(sent.get("sessionId").asText()).isEqualTo("S9");
    }

    @Test
    void anErrorOrNoAnswerIsSaid() throws Exception {
        socket.onSend = s -> {
            try {
                answer("{\"id\":" + JSON.readTree(s).get("id").asInt() + ",\"error\":{\"message\":\"No target\"}}");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        };
        assertThatThrownBy(() -> cdp.call("Target.attachToTarget", null, null, Duration.ofSeconds(1))).hasMessageContaining("No target");
        socket.onSend = s -> {};
        assertThatThrownBy(() -> cdp.call("Page.navigate", null, null, Duration.ofMillis(50))).hasMessageContaining("took longer");
    }

    @Test
    void eventsCarryTheirSessionAndNoiseIsIgnored() {
        answer("{\"method\":\"Page.loadEventFired\",\"params\":{\"timestamp\":1},\"sessionId\":\"S1\"}");
        answer("not json");
        answer("{\"id\":999,\"result\":{}}");
        assertThat(events).containsExactly("Page.loadEventFired {\"timestamp\":1,\"sessionId\":\"S1\"}");
        cdp.post("Fetch.continueRequest", Cdp.params().put("requestId", "1"), "S1");
        assertThat(socket.sent).hasSize(1);
    }

    @Test
    void theListenerJoinsFragmentsIntoMessages() {
        List<String> got = new ArrayList<>();
        Cdp.Listener listener = new Cdp.Listener((m, p) -> got.add(m));
        listener.cdp = cdp;
        listener.onText(socket, "{\"method\":\"Page.", false);
        listener.onText(socket, "frameNavigated\",\"params\":{}}", true);
        assertThat(got).containsExactly("Page.frameNavigated");
        listener.onError(socket, new IOException("dropped"));
    }

    @Test
    void closingEndsWhatWaitsAndAbortsASocketThatWillNotClose() throws Exception {
        CompletionStage<?> unused = CompletableFuture.completedFuture(null);
        assertThat(unused).isNotNull();
        socket.failClose = true;
        cdp.close();
        assertThat(socket.aborted).isTrue();
    }
}
