package io.github.llm4j.loom.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What happened in a project that the Guide has not talked about yet, kept in {@code
 * .loom/guide/events.jsonl}. The editor and the builder's checkpoints append; the Guide reads what
 * it has not discussed. An event holds a kind, a stage, a subject and small identifiers: never a
 * file's content, a prompt, a model's output or a secret. The file keeps the newest {@link #MAX}.
 */
final class GuideEvents {

    static final int MAX = 500;
    /** At most this many events are handed to the Guide in one turn. */
    static final int MAX_PER_READ = 50;
    private static final int MAX_FIELD = 200;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path folder;
    private final Clock clock;

    GuideEvents(Path projectRoot, Clock clock) {
        this.folder = Objects.requireNonNull(projectRoot, "projectRoot").resolve(".loom").resolve("guide");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    Path file() {
        return folder.resolve("events.jsonl");
    }

    private Path discussedFile() {
        return folder.resolve("discussed.json");
    }

    /** Appends one event and returns its sequence number. Long fields are cut. */
    synchronized long append(String kind, String stage, String subject, Map<String, String> data) throws IOException {
        Files.createDirectories(folder);
        try (FileChannel ch = FileChannel.open(folder.resolve("events.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock ignored = ch.lock()) {
            List<String> lines = lines();
            long seq = lines.isEmpty() ? 1 : JSON.readTree(lines.get(lines.size() - 1)).path("seq").asLong() + 1;
            ObjectNode e = JSON.createObjectNode();
            e.put("seq", seq);
            e.put("at", clock.instant().toString());
            e.put("kind", cut(kind));
            if (stage != null && !stage.isBlank()) e.put("stage", cut(stage));
            if (subject != null && !subject.isBlank()) e.put("subject", cut(subject));
            if (data != null && !data.isEmpty()) {
                ObjectNode d = e.putObject("data");
                data.forEach((k, v) -> d.put(cut(k), cut(v)));
            }
            lines.add(JSON.writeValueAsString(e));
            if (lines.size() > MAX) lines = new ArrayList<>(lines.subList(lines.size() - MAX, lines.size()));
            Files.write(file(), lines, StandardCharsets.UTF_8);
            return seq;
        }
    }

    /** The events not yet discussed, oldest first, at most {@link #MAX_PER_READ}; marks them discussed. */
    synchronized List<JsonNode> undiscussed(boolean mark) throws IOException {
        long seen = discussedUpTo();
        List<JsonNode> out = new ArrayList<>();
        for (String l : lines()) {
            JsonNode e;
            try {
                e = JSON.readTree(l);
            } catch (IOException bad) {
                continue;
            }
            if (e.path("seq").asLong() > seen) out.add(e);
        }
        if (out.size() > MAX_PER_READ) out = out.subList(out.size() - MAX_PER_READ, out.size());
        if (mark && !out.isEmpty()) {
            Files.createDirectories(folder);
            Files.writeString(discussedFile(), "{\"seq\":" + out.get(out.size() - 1).path("seq").asLong() + "}\n");
        }
        return List.copyOf(out);
    }

    /** How many events have not been discussed (what the editor's status bar shows). */
    long undiscussedCount() throws IOException {
        long seen = discussedUpTo();
        return lines().stream().filter(l -> {
            try {
                return JSON.readTree(l).path("seq").asLong() > seen;
            } catch (IOException bad) {
                return false;
            }
        }).count();
    }

    private long discussedUpTo() {
        try {
            return Files.isRegularFile(discussedFile()) ? JSON.readTree(discussedFile().toFile()).path("seq").asLong(0) : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    private List<String> lines() throws IOException {
        if (!Files.isRegularFile(file())) return new ArrayList<>();
        List<String> out = new ArrayList<>();
        for (String l : Files.readAllLines(file(), StandardCharsets.UTF_8)) if (!l.isBlank()) out.add(l);
        return out;
    }

    private static String cut(String s) {
        if (s == null) return "";
        return s.length() <= MAX_FIELD ? s : s.substring(0, MAX_FIELD) + "…";
    }
}
