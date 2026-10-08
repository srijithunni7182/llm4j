package io.github.llm4j.loom.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A bridge to anything: each question (and each note) is piped to a configured program as one line of JSON. Replies come back as {@code weave answer}
 * calls the program makes, so there is nothing to poll.
 */
public final class CommandChannel implements Channel {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final List<String> command;

    public CommandChannel(List<String> command) {
        this.command = List.copyOf(command);
    }

    @Override
    public String name() {
        return "command";
    }

    @Override
    public Sent send(String chat, Outgoing q) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "question");
        m.put("code", q.code());
        m.put("text", q.text());
        m.put("choices", q.choices());
        m.put("to", q.to());
        m.put("chat", chat);
        run(m);
        return new Sent(chat, "cmd:" + q.code(), Instant.now());
    }

    @Override
    public Batch poll(Duration wait) {
        return Batch.empty();
    }

    @Override
    public void acknowledge(String cursor) { }

    @Override
    public void tell(String chat, String text) throws IOException {
        run(Map.of("type", "note", "chat", chat, "text", text));
    }

    private void run(Map<String, Object> message) throws IOException {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            p.getOutputStream().write((JSON.writeValueAsString(message) + "\n").getBytes(StandardCharsets.UTF_8));
            p.getOutputStream().close();
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("the channel command did not finish in 30 seconds");
            }
            if (p.exitValue() != 0) throw new IOException("the channel command failed (exit " + p.exitValue() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while running the channel command");
        }
    }
}
