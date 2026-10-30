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
 * {@code .env.example} is a template that is committed (git is told not to ignore it), so a real
 * key written there is shared with everyone who sees the repository. {@code weave check} and {@code
 * weave next} say so, by name only: the value is never shown.
 */
final class EnvExampleCheck {

    private static final Pattern LINE =
            Pattern.compile("(?:export\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.*)");
    private static final List<String> PLACEHOLDERS =
            List.of(
                    "your",
                    "xxx",
                    "changeme",
                    "change-me",
                    "replace",
                    "example",
                    "<",
                    "...",
                    "todo",
                    "none",
                    "placeholder");

    /** A name that holds a secret: ends in one of these words, or says it is an API key. */
    private static final Pattern SECRET_NAME =
            Pattern.compile(
                    "(?i).*(KEY|TOKEN|SECRET|PASSWORD|PASSWD|PASSPHRASE|CREDENTIALS?)|.*API_?KEY.*|.*PRIVATE.*");

    /**
     * A URL with a user and a password in it ({@code scheme://user:pass@host}): a secret whatever the
     * name. A password that starts with {@code $} is a reference to a variable, the safe way to write
     * a template.
     */
    private static final Pattern URL_WITH_PASSWORD =
            Pattern.compile("(?i)^[a-z][a-z0-9+.-]*://[^/\\s@:]*:(?!\\$)[^/\\s@]+@.*");

    /** One value found in a template: which file, and the name it is set for (never the value). */
    record Finding(String file, String name) {}

    private EnvExampleCheck() {}

    /** The template files of a project: files that are meant to be committed as examples. */
    private static List<Path> templates(Path root) {
        List<Path> out = new ArrayList<>();
        try (var files = Files.list(root)) {
            files.filter(Files::isRegularFile)
                    .filter(
                            f -> {
                                String n = f.getFileName().toString();
                                return n.equals(".env.example")
                                        || n.endsWith(".example")
                                        || n.endsWith(".sample")
                                        || n.endsWith(".template");
                            })
                    .sorted()
                    .forEach(out::add);
        } catch (IOException e) {
            // an unreadable folder has no templates to check
        }
        return out;
    }

    /**
     * Every template file of the project that has a value for a name that holds a secret and that
     * does not look like a placeholder. Names and files only: the value is never kept.
     */
    static List<Finding> findings(Path script) {
        List<Finding> out = new ArrayList<>();
        for (Path file : templates(ProjectLayout.root(script)))
            for (String name : namesWithValuesIn(file))
                out.add(new Finding(file.getFileName().toString(), name));
        return out;
    }

    /** The names (without the files) of {@link #findings}, each once. */
    static List<String> namesWithValues(Path script) {
        return findings(script).stream().map(Finding::name).distinct().toList();
    }

    /**
     * The entries of one file that have a value which is not a placeholder and are secrets: named
     * like one, or holding a URL with a password in it.
     */
    static List<String> namesWithValuesIn(Path file) {
        List<String> out = new ArrayList<>();
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
            if (value.length() >= 2
                    && (value.startsWith("\"") && value.endsWith("\"")
                            || value.startsWith("'") && value.endsWith("'")))
                value = value.substring(1, value.length() - 1);
            int hash = value.indexOf(" #");
            if (hash >= 0) value = value.substring(0, hash).strip();
            if (value.isEmpty()) continue;
            if (!SECRET_NAME.matcher(m.group(1)).matches() && !URL_WITH_PASSWORD.matcher(value).matches())
                continue;
            String lower = value.toLowerCase(Locale.ROOT);
            if (PLACEHOLDERS.stream().anyMatch(lower::contains)) continue;
            out.add(m.group(1));
        }
        return out;
    }

    /** One sentence per file: what it holds (by name), why that is wrong, and the way out. */
    static String warning(List<Finding> findings) {
        java.util.Map<String, List<String>> byFile = new java.util.LinkedHashMap<>();
        for (Finding f : findings)
            byFile.computeIfAbsent(f.file(), k -> new ArrayList<>()).add(f.name());
        StringBuilder out = new StringBuilder();
        byFile.forEach(
                (file, names) -> {
                    if (out.length() > 0) out.append(" ");
                    out.append(file)
                            .append(" has a value for ")
                            .append(String.join(", ", names))
                            .append(". It is meant to be committed, so that value is shared with everyone"
                                    + " who can see the project. Move it into the secret store with `weave secrets"
                                    + " import-env --from ")
                            .append(file)
                            .append(" --secrets <store file>` (it offers to blank the file).");
                });
        return out.toString();
    }
}
