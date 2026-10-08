package io.github.llm4j.agent.prompt;

import java.util.ArrayList;
import java.util.List;

/**
 * The front matter of a prompt file: {@code description:} (one line) and {@code variables:} (a list, written {@code [a, b]} or as {@code - a}
 * lines). Other keys are ignored. It is read by hand, not with a YAML library, so a prompt file means the same on every classpath.
 */
final class PromptFrontMatter {

    record Parsed(String description, List<String> variables) {}

    /** A front matter block that cannot be read, with what to fix. */
    static final class Invalid extends RuntimeException {
        Invalid(String message) {
            super(message);
        }
    }

    private PromptFrontMatter() {}

    static Parsed parse(String block) {
        String description = null;
        List<String> variables = new ArrayList<>();
        boolean inVariables = false;
        for (String raw : block.split("\\r?\\n", -1)) {
            String line = raw.stripTrailing();
            if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
            if (inVariables && line.stripLeading().startsWith("- ")) {
                variables.add(unquote(line.stripLeading().substring(2).strip()));
                continue;
            }
            inVariables = false;
            int colon = line.indexOf(':');
            if (colon <= 0 || Character.isWhitespace(line.charAt(0))) {
                throw new Invalid("the line \"" + line.strip() + "\" is not a key: value pair");
            }
            String key = line.substring(0, colon).strip();
            String value = line.substring(colon + 1).strip();
            switch (key) {
                case "description" -> {
                    if (value.startsWith("|") || value.startsWith(">")) {
                        throw new Invalid("description must be on one line");
                    }
                    description = unquote(value);
                }
                case "variables" -> {
                    if (value.isEmpty()) {
                        inVariables = true;
                    } else if (value.startsWith("[")) {
                        if (!value.endsWith("]")) throw new Invalid("variables: the list is not closed with ]");
                        String inner = value.substring(1, value.length() - 1).strip();
                        if (!inner.isEmpty()) for (String part : inner.split(",")) variables.add(unquote(part.strip()));
                    } else {
                        throw new Invalid("variables must be a list such as [topic, region]");
                    }
                }
                default -> { /* another key: not ours */ }
            }
        }
        variables.removeIf(String::isEmpty);
        return new Parsed(description == null || description.isEmpty() ? null : description, List.copyOf(variables));
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
