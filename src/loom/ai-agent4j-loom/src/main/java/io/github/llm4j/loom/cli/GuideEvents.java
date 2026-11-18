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
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What happened in a project that the Guide has not talked about yet. There are two files in {@code .loom/guide/}, each with
 * one writer, so no lock is shared across languages: the editor appends to {@code events-editor.jsonl}, {@code weave
 * guide-mcp} (the builder's checkpoints) to {@code events-server.jsonl}. Both only append, and trim through a temporary
 * file and a rename; a reader merges them by time and skips a line it cannot read (a torn last line, for example). {@code
 * discussed.json} keeps how far the Guide has read in each. An event holds a kind, a stage, a subject and small identifiers:
 * never a file's content, a prompt, a model's output or a secret. Each file keeps its newest {@link #MAX}.
 */
final class GuideEvents {

    static final int MAX = 500;
    /** At most this many events are handed to the Guide in one turn, oldest first. */
    static final int MAX_PER_READ = 50;
    static final String SERVER = "server";
    static final String EDITOR = "editor";
    private static final int MAX_FIELD = 200;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** One read: the oldest events not yet discussed, and how many more are waiting after them. */
    record Batch(List<JsonNode> events, int more) {
        Batch {
            events = List.copyOf(events);
        }
    }

    private final Path folder;
    private final Clock clock;

    GuideEvents(Path projectRoot, Clock clock) {
        this.folder = Objects.requireNonNull(projectRoot, "projectRoot").resolve(".loom").resolve("guide");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The file this process writes. */
    Path file() {
        return source(SERVER);
    }

    Path source(String name) {
        return folder.resolve("events-" + name + ".jsonl");
    }

    private Path discussedFile() {
        return folder.resolve("discussed.json");
    }

    /** Appends one event to the server's file and returns its sequence number. Long fields are cut. */
    synchronized long append(String kind, String stage, String subject, Map<String, String> data) throws IOException {
        Files.createDirectories(folder);
        // several guide-mcp processes (one per assistant) may write the same file: they share this lock
        try (FileChannel ch = FileChannel.open(folder.resolve("events-server.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock ignored = ch.lock()) {
            List<String> lines = lines(file());
            long seq = lines.stream().mapToLong(GuideEvents::seqOf).max().orElse(0) + 1;
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
            String line = JSON.writeValueAsString(e);
            if (lines.size() + 1 > MAX) {
                List<String> kept = new ArrayList<>(lines.subList(lines.size() - MAX + 1, lines.size()));
                kept.add(line);
                Path tmp = folder.resolve("events-server.jsonl.tmp");
                Files.write(tmp, kept, StandardCharsets.UTF_8);
                Files.move(tmp, file(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                // a torn last line has no newline: start a fresh line rather than glue this event onto it
                boolean torn = Files.isRegularFile(file()) && Files.size(file()) > 0 && !endsWithNewline(file());
                Files.writeString(file(), (torn ? "\n" : "") + line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            return seq;
        }
    }

    /** The oldest events not yet discussed, from both files, at most {@link #MAX_PER_READ}; with mark, those become discussed. */
    synchronized Batch undiscussed(boolean mark) throws IOException {
        Map<String, Long> seen = discussed();
        List<JsonNode> out = new ArrayList<>();
        for (String name : List.of(EDITOR, SERVER)) {
            long upTo = seen.getOrDefault(name, 0L);
            for (String l : lines(source(name))) {
                JsonNode e = parse(l);
                if (e != null && e.path("seq").asLong() > upTo) {
                    ((ObjectNode) e).put("source", name);
                    out.add(e);
                }
            }
        }
        out.sort(Comparator.comparing((JsonNode e) -> e.path("at").asText()).thenComparing(e -> e.path("source").asText()).thenComparingLong(e -> e.path("seq").asLong()));
        List<JsonNode> batch = out.subList(0, Math.min(MAX_PER_READ, out.size()));
        if (mark && !batch.isEmpty()) {
            Map<String, Long> next = new java.util.LinkedHashMap<>(seen);
            for (JsonNode e : batch) next.merge(e.path("source").asText(), e.path("seq").asLong(), Math::max);
            Files.createDirectories(folder);
            Path tmp = folder.resolve("discussed.json.tmp");
            Files.writeString(tmp, JSON.writeValueAsString(next) + "\n");
            Files.move(tmp, discussedFile(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        return new Batch(new ArrayList<>(batch), out.size() - batch.size());
    }

    /** How many events have not been discussed (what the editor's status bar shows). */
    long undiscussedCount() throws IOException {
        Map<String, Long> seen = discussed();
        long n = 0;
        for (String name : List.of(EDITOR, SERVER)) {
            long upTo = seen.getOrDefault(name, 0L);
            n += lines(source(name)).stream().filter(l -> seqOf(l) > upTo).count();
        }
        return n;
    }

    private Map<String, Long> discussed() {
        Map<String, Long> out = new java.util.HashMap<>();
        try {
            if (Files.isRegularFile(discussedFile())) {
                JsonNode d = JSON.readTree(discussedFile().toFile());
                d.fields().forEachRemaining(f -> out.put(f.getKey(), f.getValue().asLong(0)));
            }
        } catch (IOException e) {
            // an unreadable mark means nothing was discussed: the Guide sees the events again rather than miss one
        }
        return out;
    }

    private static List<String> lines(Path f) throws IOException {
        if (!Files.isRegularFile(f)) return new ArrayList<>();
        List<String> out = new ArrayList<>();
        for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) if (!l.isBlank()) out.add(l);
        return out;
    }

    private static boolean endsWithNewline(Path f) throws IOException {
        try (FileChannel ch = FileChannel.open(f, StandardOpenOption.READ)) {
            java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(1);
            ch.read(b, ch.size() - 1);
            return b.get(0) == '\n';
        }
    }

    private static JsonNode parse(String line) {
        try {
            JsonNode e = JSON.readTree(line);
            return e != null && e.isObject() ? e : null;
        } catch (IOException torn) {
            return null;
        }
    }

    private static long seqOf(String line) {
        JsonNode e = parse(line);
        return e == null ? 0 : e.path("seq").asLong(0);
    }

    private static String cut(String s) {
        if (s == null) return "";
        return s.length() <= MAX_FIELD ? s : s.substring(0, MAX_FIELD) + "…";
    }
}
