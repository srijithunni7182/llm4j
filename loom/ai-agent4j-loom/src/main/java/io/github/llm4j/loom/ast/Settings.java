package io.github.llm4j.loom.ast;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A block of {@code key: value} settings on an agent ({@code memory}, {@code voice}, {@code guard}).
 * The parser only reads the syntax; which keys and values are allowed is checked at load time, so every
 * problem is reported together, with its line.
 */
public class Settings {

    private int line;
    private final Map<String, ToolDef.OptionValue> values = new LinkedHashMap<>();
    private final Map<String, Integer> lines = new HashMap<>();

    public int getLine() { return line; }
    public void setLine(int line) { this.line = line; }

    public Map<String, ToolDef.OptionValue> getValues() { return values; }

    public void put(String key, ToolDef.OptionValue value, int line) {
        values.put(key, value);
        lines.put(key, line);
    }

    /** The line a key was written on (the block's line if unknown). */
    public int lineOf(String key) {
        return lines.getOrDefault(key, line);
    }

    public boolean has(String key) {
        return values.containsKey(key);
    }

    /** A literal value, or null (also null for {@code env.X}; the validator reports those). */
    public String get(String key) {
        ToolDef.OptionValue v = values.get(key);
        return v == null || v.fromEnv() ? null : v.value();
    }

    public String get(String key, String fallback) {
        String v = get(key);
        return v != null ? v : fallback;
    }

    /** A whole number, or the fallback when absent (the validator reports non-numbers). */
    public int getInt(String key, int fallback) {
        try {
            String v = get(key);
            return v == null ? fallback : (int) Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public double getDouble(String key, double fallback) {
        try {
            String v = get(key);
            return v == null ? fallback : Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
