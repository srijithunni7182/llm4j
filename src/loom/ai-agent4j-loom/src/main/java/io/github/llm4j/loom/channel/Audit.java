package io.github.llm4j.loom.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/** {@code <store>/channel/audit.jsonl}: who answered what, when. Never holds a token. */
public final class Audit {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path file;
    private final Clock clock;

    public Audit(PendingStore store, Clock clock) {
        this.file = store.root().resolve("audit.jsonl");
        this.clock = clock;
    }

    public synchronized void record(String event, Map<String, Object> data) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("at", clock.instant().toString());
        line.put("event", event);
        line.putAll(data);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, JSON.writeValueAsString(line) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // the audit line is best effort; the pending record carries the answer too
        }
    }
}
