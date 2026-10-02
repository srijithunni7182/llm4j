package io.github.llm4j.loom.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Telegram's Bot API over HTTPS: {@code sendMessage} and long-polled {@code getUpdates}. Text goes as plain text (no parse mode), so nothing in a case
 * can be turned into formatting or a link. The token is held in memory only and scrubbed from every error. This is the one class that knows Telegram.
 */
public final class TelegramChannel implements Channel {

    static final int LIMIT = 4096;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String apiBase;
    private final String token;
    private final Path offsetFile;
    private final HttpClient http;

    /**
     * @param apiBase    {@code https://api.telegram.org}, or a test server
     * @param offsetFile where the next update number is kept, so a restart neither repeats nor skips
     */
    public TelegramChannel(String apiBase, String token, Path offsetFile) {
        this.apiBase = apiBase.endsWith("/") ? apiBase.substring(0, apiBase.length() - 1) : apiBase;
        this.token = token;
        this.offsetFile = offsetFile;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public String name() {
        return "telegram";
    }

    @Override
    public Sent send(String chat, Outgoing q) throws IOException {
        JsonNode result = call("sendMessage", Map.of("chat_id", Long.parseLong(chat), "text", Text.cut(q.text(), LIMIT - 20), "disable_web_page_preview", true), Duration.ofSeconds(20));
        String ref = result.path("message_id").asText();
        return new Sent(chat, ref, Instant.now());
    }

    @Override
    public Batch poll(Duration wait) throws IOException {
        long offset = readOffset();
        Map<String, Object> body = new LinkedHashMap<>();
        if (offset > 0) body.put("offset", offset);
        body.put("timeout", wait.toSeconds());
        body.put("allowed_updates", List.of("message"));
        JsonNode updates = call("getUpdates", body, wait.plusSeconds(15));
        List<Reply> replies = new ArrayList<>();
        long max = -1;
        for (JsonNode u : updates) {
            max = Math.max(max, u.path("update_id").asLong(-1));
            JsonNode m = u.path("message");
            if (!m.hasNonNull("text")) continue;
            JsonNode reply = m.path("reply_to_message");
            replies.add(new Reply(m.path("chat").path("id").asText(), m.path("from").path("id").asText(), m.path("text").asText(),
                    reply.hasNonNull("message_id") ? reply.path("message_id").asText() : null));
        }
        return new Batch(replies, max < 0 ? null : String.valueOf(max + 1));
    }

    @Override
    public void acknowledge(String cursor) throws IOException {
        if (cursor == null) return;
        Files.createDirectories(offsetFile.getParent());
        Path tmp = Files.createTempFile(offsetFile.getParent(), ".offset", ".tmp");
        Files.writeString(tmp, "{\"offset\":" + Long.parseLong(cursor) + "}");
        Files.move(tmp, offsetFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    @Override
    public void tell(String chat, String text) throws IOException {
        call("sendMessage", Map.of("chat_id", Long.parseLong(chat), "text", Text.cut(Text.safe(text, true), LIMIT - 20)), Duration.ofSeconds(20));
    }

    private long readOffset() {
        try {
            if (!Files.isRegularFile(offsetFile)) return 0;
            return JSON.readTree(offsetFile.toFile()).path("offset").asLong(0);
        } catch (IOException e) {
            return 0;
        }
    }

    private JsonNode call(String method, Map<String, Object> body, Duration timeout) throws IOException {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(apiBase + "/bot" + token + "/" + method)).timeout(timeout)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode root;
            try {
                root = JSON.readTree(response.body());
            } catch (IOException e) {
                throw new IOException("Telegram answered with something that is not JSON (HTTP " + response.statusCode() + ")");
            }
            if (!root.path("ok").asBoolean(false)) {
                throw new IOException("Telegram refused " + method + " (HTTP " + response.statusCode() + "): " + scrub(root.path("description").asText("no reason given")));
            }
            return root.path("result");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while talking to Telegram");
        } catch (IOException e) {
            throw new IOException(scrub(e.getMessage() == null ? "could not reach Telegram (" + e.getClass().getSimpleName() + ")" : e.getMessage()));
        } catch (IllegalArgumentException e) {
            throw new IOException("the Telegram address is not valid");
        }
    }

    /** Nothing that goes out of this class may contain the token. */
    String scrub(String text) {
        String t = text == null ? "" : text;
        if (!token.isEmpty()) t = t.replace(token, "<token>");
        return Text.safe(t, false);
    }
}
