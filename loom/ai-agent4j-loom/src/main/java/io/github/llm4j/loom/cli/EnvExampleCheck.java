package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.init.ProjectLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code .env.example} is a template that is committed (git is told not to ignore it), so a real key written there is shared with everyone who sees the
 * repository. {@code weave check} and {@code weave next} say so, by name only: the value is never shown.
 */
final class EnvExampleCheck {

    private static final Pattern LINE = Pattern.compile("(?:export\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.*)");
    private static final List<String> PLACEHOLDERS = List.of("your", "xxx", "changeme", "change-me", "replace", "example", "<", "...", "todo", "none", "placeholder");

    private EnvExampleCheck() {}

    /** The names in the project's {@code .env.example} that have a value which does not look like a placeholder. */
    static List<String> namesWithValues(Path script) {
        Path file = ProjectLayout.root(script).resolve(".env.example");
        List<String> out = new ArrayList<>();
        if (!Files.isRegularFile(file)) return out;
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            return out;
        }
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            Matcher m = LINE.matcher(line);
            if (!m.matches()) continue;
            String value = m.group(2).strip();
            if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"") || value.startsWith("'") && value.endsWith("'"))) value = value.substring(1, value.length() - 1);
            int hash = value.indexOf(" #");
            if (hash >= 0) value = value.substring(0, hash).strip();
            if (value.isEmpty()) continue;
            String lower = value.toLowerCase(Locale.ROOT);
            if (PLACEHOLDERS.stream().anyMatch(lower::contains)) continue;
            out.add(m.group(1));
        }
        return out;
    }

    static String warning(List<String> names) {
        return ".env.example holds a value for " + String.join(", ", names) + ". That file is a template that gets committed, so a key there is shared with everyone who can see the project. "
                + "Put the key in .env (cp .env.example .env), which git ignores, and leave the value in .env.example empty";
    }
}
