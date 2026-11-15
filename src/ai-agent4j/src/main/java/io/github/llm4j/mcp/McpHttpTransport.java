package io.github.llm4j.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.secret.CredentialSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A remote MCP server over Streamable HTTP (phase 36b, D6): each message is one POST to a single
 * endpoint, answered by one JSON object or by a response stream whose events are the messages.
 * Request metadata (the protocol version, the method, a tool's name) is mirrored into headers as
 * the specification says. The bearer token, when there is one, is asked for from a {@link
 * CredentialSource} on every request and never kept: a 401 asks again once, and a second refusal,
 * or a 403 for a missing scope, is a {@link McpCredentialRejectedException}. It is never put in the
 * URL.
 *
 * <p>A transport belongs to one run: a token handed over for one person is never sent on behalf of
 * another, so a host builds one transport per run and never shares it.
 */
public final class McpHttpTransport implements McpTransport {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String VERSION_KEY = "io.modelcontextprotocol/protocolVersion";
    private static final int CHALLENGE_CHARS = 500;

    private final String url;
    private final McpHttpPoster poster;
    private final CredentialSource token;
    private Consumer<JsonRpcMessage> handler;
    private volatile String protocolVersion;
    private volatile String sessionId;

    /**
     * @param url the server's one endpoint
     * @param poster sends each POST under the host's address rules
     * @param token asked for on every request, or null for a server with no authorization
     */
    public McpHttpTransport(String url, McpHttpPoster poster, CredentialSource token) {
        this.url = Objects.requireNonNull(url, "url");
        this.poster = Objects.requireNonNull(poster, "poster");
        this.token = token;
    }

    @Override
    public boolean probesModernFirst() {
        return true;
    }

    @Override
    public void connect() {
        // nothing is open between requests
    }

    @Override
    public void onMessage(Consumer<JsonRpcMessage> handler) {
        this.handler = handler;
    }

    @Override
    public void sendMessage(JsonRpcMessage message) throws IOException {
        byte[] body = JSON.writeValueAsBytes(message);
        JsonNode sent = JSON.readTree(body);
        Map<String, String> headers = headersFor(sent);
        McpHttpPoster.Reply reply = post(headers, body);
        if (reply.status() == 401 && token != null) {
            token.rejected(); // the token may have expired: ask for a fresh one, once
            reply = post(headers, body);
        }
        if (token != null && refused(reply))
            throw new McpCredentialRejectedException(
                    "the server refused the credential (HTTP "
                            + reply.status()
                            + "); get a new or wider one and resume. "
                            + challenge(reply));
        if (reply.status() / 100 != 2)
            throw new McpHttpException(
                    reply.status(), new String(reply.body(), StandardCharsets.UTF_8));
        String session = reply.header("mcp-session-id");
        if (session != null) sessionId = session;
        if (reply.status() == 202 || reply.body().length == 0) return;
        deliver(reply);
    }

    /** What a later request is sent under once the era and version are known. */
    void useVersion(String version) {
        this.protocolVersion = version;
    }

    private Map<String, String> headersFor(JsonNode sent) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json");
        h.put("Accept", "application/json, text/event-stream");
        String version =
                sent.path("params").path("_meta").path(VERSION_KEY).asText(protocolVersion);
        if (version != null) h.put("MCP-Protocol-Version", version);
        if (sent.has("method")) {
            String method = sent.get("method").asText();
            if (sent.path("params").path("_meta").has(VERSION_KEY)) {
                h.put("Mcp-Method", method);
                String name = sent.path("params").path("name").asText(null);
                if (name != null && method.equals("tools/call")) h.put("Mcp-Name", name);
            }
        }
        if (sessionId != null) h.put("Mcp-Session-Id", sessionId);
        return h;
    }

    private McpHttpPoster.Reply post(Map<String, String> headers, byte[] body) throws IOException {
        Map<String, String> withAuth = new LinkedHashMap<>(headers);
        if (token != null) {
            String value;
            try {
                value = token.get();
            } catch (RuntimeException e) {
                // the host's own words may hold anything: say only what failed
                throw new McpException(
                        "the credential could not be obtained ("
                                + e.getClass().getSimpleName()
                                + ")");
            }
            if (value == null || value.isBlank())
                throw new McpException("there is no credential for this MCP server");
            withAuth.put(
                    "Authorization",
                    value.regionMatches(true, 0, "Bearer ", 0, 7) ? value : "Bearer " + value);
        }
        return poster.post(url, withAuth, body);
    }

    private static boolean refused(McpHttpPoster.Reply reply) {
        if (reply.status() == 401) return true;
        String c = reply.header("www-authenticate");
        return reply.status() == 403
                && c != null
                && c.toLowerCase(Locale.ROOT).contains("insufficient_scope");
    }

    private String challenge(McpHttpPoster.Reply reply) {
        String c = reply.header("www-authenticate");
        if (c == null) return "The server sent no WWW-Authenticate challenge.";
        String safe = c;
        try {
            String secret = token.get();
            if (secret != null && secret.length() >= 4) {
                safe = safe.replace(secret, "***");
                int space = secret.indexOf(' ');
                if (space > 0 && secret.length() - space - 1 >= 4)
                    safe = safe.replace(secret.substring(space + 1), "***");
            }
        } catch (RuntimeException ignored) {
            // nothing to scrub with
        }
        return "The server's challenge: "
                + (safe.length() > CHALLENGE_CHARS
                        ? safe.substring(0, CHALLENGE_CHARS) + "…"
                        : safe);
    }

    private void deliver(McpHttpPoster.Reply reply) throws IOException {
        String type =
                reply.header("content-type") == null
                        ? ""
                        : reply.header("content-type").toLowerCase(Locale.ROOT);
        String text = new String(reply.body(), StandardCharsets.UTF_8);
        if (type.startsWith("text/event-stream")) {
            for (String data : events(text)) accept(data);
        } else {
            accept(text);
        }
    }

    /**
     * The {@code data} of each event of a stream: comments, keep-alives and other fields are
     * skipped.
     */
    static java.util.List<String> events(String stream) {
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder data = new StringBuilder();
        for (String raw : stream.split("\r\n|\n|\r", -1)) {
            if (raw.isEmpty()) {
                if (data.length() > 0) out.add(data.toString());
                data.setLength(0);
            } else if (raw.startsWith("data:")) {
                if (data.length() > 0) data.append('\n');
                String v = raw.substring(5);
                data.append(v.startsWith(" ") ? v.substring(1) : v);
            }
        }
        if (data.length() > 0) out.add(data.toString());
        return out;
    }

    private void accept(String json) throws IOException {
        if (json.isBlank() || handler == null) return;
        handler.accept(JSON.readValue(json, JsonRpcMessage.class));
    }

    @Override
    public void close() {
        // nothing is open between requests
    }
}
