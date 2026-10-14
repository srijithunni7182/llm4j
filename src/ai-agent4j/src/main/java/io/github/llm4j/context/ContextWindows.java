package io.github.llm4j.context;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Context windows per model. The catalogue starts <b>empty</b>: nothing is assumed about a model. {@link #defaults()} is a short list of
 * models checked against their provider's documentation (each entry says when), and you load your own for the rest:
 *
 * <pre>
 * # windows.properties — model = window [/ tokens kept for the reply]
 * gemini/gemini-2.5-flash = 1000000 / 8192
 * my-local-model = 32000
 * </pre>
 *
 * A model is looked up by its id, then by the id without its provider prefix, ignoring case. An unknown model has no window;
 * nothing is guessed.
 */
public final class ContextWindows {

    private static final ContextWindows EMPTY = new ContextWindows(Map.of());

    private final Map<String, ContextWindow> windows;

    private ContextWindows(Map<String, ContextWindow> windows) {
        this.windows = Map.copyOf(windows);
    }

    public static ContextWindows empty() {
        return EMPTY;
    }

    /**
     * The short list that ships with the library: only models whose window was checked against the provider's own documentation, with the date
     * in {@code windows.properties}. It is never applied by itself; pass it to the agent. Extend or override it with {@link #with}, or load your own
     * file for the models it does not cover.
     */
    public static ContextWindows defaults() {
        try (java.io.InputStream in = ContextWindows.class.getResourceAsStream("windows.properties")) {
            if (in == null) return EMPTY;
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList(), "the seeded windows");
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("could not read the seeded context windows", e);
        }
    }

    /** Reads {@code model = window [/ reserve]} lines; {@code #} starts a comment. */
    public static ContextWindows load(Path file) throws IOException {
        return parse(Files.readAllLines(file, StandardCharsets.UTF_8), file.toString());
    }

    private static ContextWindows parse(java.util.List<String> lines, Object file) {
        Map<String, ContextWindow> windows = new LinkedHashMap<>();
        int n = 0;
        for (String raw : lines) {
            n++;
            String line = raw.replaceFirst("#.*$", "").strip();
            if (line.isEmpty()) continue;
            String where = file + ":" + n + ": ";
            int eq = line.indexOf('=');
            if (eq < 0) throw new IllegalArgumentException(where + "expected 'model = window [/ reserve]', got: " + raw);
            String model = line.substring(0, eq).strip().toLowerCase(Locale.ROOT);
            if (model.isEmpty()) throw new IllegalArgumentException(where + "the model name is missing");
            String[] parts = line.substring(eq + 1).split("/", -1);
            if (parts.length > 2) throw new IllegalArgumentException(where + "expected 'model = window [/ reserve]', got: " + raw);
            try {
                int tokens = Integer.parseInt(parts[0].strip());
                int reserve = parts.length == 2 ? Integer.parseInt(parts[1].strip()) : 0;
                if (windows.put(model, new ContextWindow(tokens, reserve)) != null) {
                    throw new IllegalArgumentException(where + "'" + model + "' is listed twice");
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(where + "the window and the reserve must be whole numbers, got: " + raw);
            } catch (IllegalArgumentException e) {
                if (e.getMessage().startsWith(where)) throw e;
                throw new IllegalArgumentException(where + e.getMessage());
            }
        }
        return new ContextWindows(windows);
    }

    /** A copy with this model added or replaced. */
    public ContextWindows with(String model, ContextWindow window) {
        Map<String, ContextWindow> copy = new LinkedHashMap<>(windows);
        copy.put(model.toLowerCase(Locale.ROOT), window);
        return new ContextWindows(copy);
    }

    /** The model's window: the exact id, then the id without its provider prefix; empty when unknown. */
    public Optional<ContextWindow> window(String model) {
        if (model == null) return Optional.empty();
        String key = model.toLowerCase(Locale.ROOT);
        ContextWindow w = windows.get(key);
        if (w == null && key.contains("/")) w = windows.get(key.substring(key.indexOf('/') + 1));
        return Optional.ofNullable(w);
    }
}
