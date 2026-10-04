package io.github.llm4j.loom.ast;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A tool declared in the script: {@code tool Search { use: serpapi  api_key: env.SERPAPI_KEY }}.
 * Options keep whether they were written literally or as an environment reference.
 */
public class ToolDef implements Node {

    /**
     * An option's value: a literal, {@code env.NAME}, or {@code secret.NAME} (a name in the secret store). References are resolved when needed
     * and never logged.
     */
    public record OptionValue(String value, Source source) {

        /** Where a value comes from. */
        public enum Source { LITERAL, ENV, SECRET }

        public OptionValue {
            java.util.Objects.requireNonNull(value, "value");
            java.util.Objects.requireNonNull(source, "source");
        }

        public static OptionValue literal(String value) {
            return new OptionValue(value, Source.LITERAL);
        }

        public static OptionValue env(String name) {
            return new OptionValue(name, Source.ENV);
        }

        public static OptionValue secret(String name) {
            return new OptionValue(name, Source.SECRET);
        }

        /** True for {@code env.NAME}. */
        public boolean fromEnv() {
            return source == Source.ENV;
        }

        /** True for {@code secret.NAME}. */
        public boolean fromSecret() {
            return source == Source.SECRET;
        }

        /** True for either kind of reference: the value is a name, not the thing itself. */
        public boolean isReference() {
            return source != Source.LITERAL;
        }

        /**
         * What a credential lookup is asked for: {@code NAME} for an environment reference (the store is tried first, then the environment), and
         * {@code secret:NAME} for a secret (the store only).
         */
        public String lookupKey() {
            return source == Source.SECRET ? "secret:" + value : value;
        }

        /** For messages: "environment variable NAME" or "secret NAME". */
        public String describe() {
            return source == Source.SECRET ? "secret " + value : "environment variable " + value;
        }

        @Override
        public String toString() {
            return switch (source) {
                case ENV -> "env." + value;
                case SECRET -> "secret." + value;
                case LITERAL -> value;
            };
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
