package io.github.llm4j.loom.ast;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A tool declared in the script: {@code tool Search { use: serpapi  api_key: env.SERPAPI_KEY }}.
 * Options keep whether they were written literally or as an environment reference.
 */
public class ToolDef implements Node {

    /** An option's value: a literal, or {@code env.NAME} (resolved at load time, never logged). */
    public record OptionValue(String value, boolean fromEnv) {
        public static OptionValue literal(String value) {
            return new OptionValue(value, false);
        }

        public static OptionValue env(String name) {
            return new OptionValue(name, true);
        }

        @Override
        public String toString() {
            return fromEnv ? "env." + value : value;
        }
    }

    private final String name;
    private String kind;
    private final Map<String, OptionValue> options = new LinkedHashMap<>();
    private int line;

    public ToolDef(String name) {
        this.name = name;
    }

    public String getName() { return name; }
    /** The {@code use:} value, e.g. {@code serpapi}. */
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public Map<String, OptionValue> getOptions() { return options; }
    public int getLine() { return line; }
    public void setLine(int line) { this.line = line; }
}
