package io.github.llm4j.loom.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** A local stand-in for the Telegram Bot API: records every request, delivers replies a test posts, and can fail. */
public final class FakeTelegram implements AutoCloseable {

    public static final String TOKEN = "123456:FAKE-SECRET-TOKEN-abcdef";

    public record Sent(long chat, String text, boolean hasParseMode, int messageId) { }

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpServer server;
    public final List<Sent> sent = Collections.synchronizedList(new ArrayList<>());
    public final List<String> paths = Collections.synchronizedList(new ArrayList<>());
    private final List<String> updates = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger messageIds = new AtomicInteger(900);
    private final AtomicInteger updateIds = new AtomicInteger(100);
    public volatile int failSends;
    public volatile int failPolls;
    public volatile boolean echoUrlInError;
    public volatile long lastOffset = -1;

    public FakeTelegram() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    public String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** A reply from {@code from} in {@code chat}, optionally to one of our messages. */
    public void reply(long chat, long from, String text, Integer replyTo) {
        String reply = replyTo == null ? "" : ",\"reply_to_message\":{\"message_id\":" + replyTo + "}";
        updates.add("{\"update_id\":" + updateIds.incrementAndGet() + ",\"message\":{\"message_id\":" + messageIds.incrementAndGet() + ",\"from\":{\"id\":" + from + "},\"chat\":{\"id\":" + chat
                + "},\"text\":" + quote(text) + reply + "}}");
    }

    public void reply(long chat, String text) {
        reply(chat, chat, text, null);
    }

    public List<Sent> sentTo(long chat) {
        synchronized (sent) {
            return sent.stream().filter(s -> s.chat() == chat).toList();
        }
    }

    public Sent last() {
        return sent.get(sent.size() - 1);
    }

    public int pendingUpdates() {
        return updates.size();
    }

    private static String quote(String s) {
        try {
            return JSON.writeValueAsString(s);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        paths.add(path);
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String method = path.substring(path.lastIndexOf('/') + 1);
        String out;
        int code = 200;
        if (!path.startsWith("/bot" + TOKEN + "/")) {
            code = 401;
            out = "{\"ok\":false,\"description\":\"Unauthorized\"}";
        } else if (method.equals("sendMessage")) {
            if (failSends > 0) {
                failSends--;
                code = 502;
                out = "{\"ok\":false,\"description\":\"Bad Gateway" + (echoUrlInError ? " for " + path : "") + "\"}";
            } else {
                JsonNode n = JSON.readTree(body);
                int id = messageIds.incrementAndGet();
                sent.add(new Sent(n.path("chat_id").asLong(), n.path("text").asText(), n.has("parse_mode"), id));
                out = "{\"ok\":true,\"result\":{\"message_id\":" + id + ",\"chat\":{\"id\":" + n.path("chat_id").asLong() + "}}}";
            }
        } else if (method.equals("getUpdates")) {
            if (failPolls > 0) {
                failPolls--;
                code = 500;
                out = "{\"ok\":false,\"description\":\"Internal Server Error" + (echoUrlInError ? " " + path : "") + "\"}";
            } else {
                JsonNode n = JSON.readTree(body);
                long offset = n.path("offset").asLong(0);
                lastOffset = offset;
                long wait = Math.min(n.path("timeout").asLong(0), 1) * 200;
                if (updates.isEmpty() && wait > 0) {
                    try {
                        Thread.sleep(wait);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                StringBuilder b = new StringBuilder("{\"ok\":true,\"result\":[");
                boolean first = true;
                synchronized (updates) {
                    for (String u : updates) {
                        long id = JSON.readTree(u).path("update_id").asLong();
                        if (id < offset) continue;
                        if (!first) b.append(',');
                        b.append(u);
                        first = false;
                    }
                }
                out = b.append("]}").toString();
            }
        } else {
            code = 404;
            out = "{\"ok\":false,\"description\":\"Not Found\"}";
        }
        byte[] bytes = out.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
