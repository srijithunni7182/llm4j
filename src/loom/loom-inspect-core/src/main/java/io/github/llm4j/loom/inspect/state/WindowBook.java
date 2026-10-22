package io.github.llm4j.loom.inspect.state;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Usable context windows per model, as a server keeps them: {@code model = tokens}, one per line
 * ({@code #} starts a comment). It lets a server show how full an agent's context is when the
 * process that runs it was never told the model's window. A window a process reports always wins
 * over this one.
 */
public final class WindowBook {

    /**
     * The largest window a line may give: far beyond any model, so that a typo is refused and not
     * believed.
     */
    public static final long MAX_TOKENS = 10_000_000_000L;

    /** One line that could not be read. */
    public record Problem(int line, String message) {
        @Override
        public String toString() {
            return "line " + line + ": " + message;
        }
    }

    /**
     * The result of reading a file: the book, and what was wrong with it (the book is only usable
     * when there is nothing wrong).
     */
    public record Parsed(WindowBook book, List<Problem> problems) {
        public boolean ok() {
            return problems.isEmpty();
        }
    }

    /** One model's usable window, in tokens. */
    public record Entry(String model, long usable) {}

    public static final WindowBook EMPTY = new WindowBook(Map.of());

    private final Map<String, Long> windows;

    private WindowBook(Map<String, Long> windows) {
        this.windows = windows;
    }

    public static Parsed parse(String text) {
        Map<String, Long> out = new LinkedHashMap<>();
        List<Problem> problems = new ArrayList<>();
        String[] lines = (text == null ? "" : text).split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].replaceFirst("#.*$", "").strip();
            if (line.isEmpty()) continue;
            int eq = line.indexOf('=');
            String model = eq < 0 ? "" : line.substring(0, eq).strip();
            if (eq < 0 || model.isEmpty()) {
                problems.add(
                        new Problem(i + 1, "expected 'model = tokens', got: " + lines[i].strip()));
                continue;
            }
            long tokens;
            try {
                tokens = Long.parseLong(line.substring(eq + 1).strip());
            } catch (NumberFormatException e) {
                problems.add(
                        new Problem(
                                i + 1,
                                "the window of "
                                        + model
                                        + " must be a whole number of tokens, got: "
                                        + line.substring(eq + 1).strip()));
                continue;
            }
            if (tokens < 1 || tokens > MAX_TOKENS) {
                problems.add(
                        new Problem(
                                i + 1,
                                "the window of "
                                        + model
                                        + " must be between 1 and "
                                        + MAX_TOKENS
                                        + " tokens"));
                continue;
            }
            if (out.put(model, tokens) != null)
                problems.add(new Problem(i + 1, "'" + model + "' has a window twice"));
        }
        return new Parsed(new WindowBook(out), problems);
    }

    public List<Entry> entries() {
        List<Entry> out = new ArrayList<>();
        windows.forEach((m, t) -> out.add(new Entry(m, t)));
        out.sort(Comparator.comparing(Entry::model));
        return out;
    }

    /** The file for these entries, as {@link #parse} reads it back. */
    public static String write(List<Entry> entries) {
        StringBuilder out = new StringBuilder("# model = usable context window, in tokens\n");
        for (Entry e : entries.stream().sorted(Comparator.comparing(Entry::model)).toList())
            out.append(e.model()).append(" = ").append(e.usable()).append('\n');
        return out.toString();
    }

    public int size() {
        return windows.size();
    }

    public boolean isEmpty() {
        return windows.isEmpty();
    }

    /**
     * The model's usable window, or null when the book has none: the exact id first, then without
     * its provider prefix.
     */
    public Long usable(String model) {
        if (model == null) return null;
        Long w = windows.get(model);
        if (w == null && model.contains("/"))
            w = windows.get(model.substring(model.indexOf('/') + 1));
        return w;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof WindowBook b && windows.equals(b.windows);
    }

    @Override
    public int hashCode() {
        return Objects.hash(windows);
    }
}
