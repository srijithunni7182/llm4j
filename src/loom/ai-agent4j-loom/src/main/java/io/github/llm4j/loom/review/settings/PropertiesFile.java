package io.github.llm4j.loom.review.settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the {@code key = value} lines of a {@code review.properties} file. Unlike {@link
 * java.util.Properties} it understands a trailing {@code # comment} (outside double quotes), keeps
 * the line each key came from for error messages, and keeps a quoted value as written.
 */
final class PropertiesFile {

    /** One setting with the line it was written on. */
    record Entry(String key, String value, int line) {}

    private final List<Entry> entries = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();

    static PropertiesFile parse(String file, String text) {
        PropertiesFile f = new PropertiesFile();
        String[] lines = text.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = stripComment(lines[i]).trim();
            if (line.isEmpty()) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) {
                f.errors.add(file + ":" + (i + 1) + ": expected key = value, found '" + line + "'");
                continue;
            }
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            f.entries.add(new Entry(key, value, i + 1));
        }
        return f;
    }

    List<Entry> entries() {
        return entries;
    }

    List<String> errors() {
        return errors;
    }

    /** Last value of each key, in file order. */
    Map<String, String> values() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Entry e : entries) out.put(e.key(), e.value());
        return out;
    }

    /** Removes a {@code #} comment that is not inside double quotes. */
    static String stripComment(String line) {
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') quoted = !quoted;
            else if (c == '#' && !quoted && (i == 0 || Character.isWhitespace(line.charAt(i - 1))))
                return line.substring(0, i);
        }
        return line;
    }
}
