package io.github.llm4j.loom.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The tools catalog ({@code docs/TOOLS_CATALOG.md}, shipped in the jar as {@code catalog/TOOLS_CATALOG.md}) read by the Guide:
 * the at-a-glance table, or one tool's own section. One text for people and for the Guide, so they never disagree.
 */
final class ToolCatalog {

    private static final String RESOURCE = "/catalog/TOOLS_CATALOG.md";
    /** A tool's heading: {@code ### <emoji> `name`}, optionally followed by {@code *(extra)*}. */
    private static final Pattern TOOL_HEADING = Pattern.compile("^### \\S+ `([a-z]+)`.*$");

    private ToolCatalog() {}

    /** The catalog's text. */
    static String text() {
        try (InputStream in = ToolCatalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException(RESOURCE + " is missing from the jar");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + RESOURCE, e);
        }
    }

    /** The names of the tools that have a section. */
    static List<String> names() {
        List<String> out = new ArrayList<>();
        for (String line : text().split("\n")) {
            Matcher m = TOOL_HEADING.matcher(line);
            if (m.matches()) out.add(m.group(1));
        }
        return List.copyOf(out);
    }

    /** The at-a-glance section: every tool in one line, with whether it needs approval. */
    static String glance() {
        String t = text();
        int start = t.indexOf("## 🗺️ At a glance");
        int end = start < 0 ? -1 : t.indexOf("\n## ", start + 1);
        if (start < 0 || end < 0) throw new IllegalStateException("The tools catalog has no At a glance section");
        return t.substring(start, end).strip();
    }

    /** One tool's section, from its heading to the next heading of the same or a higher level. */
    static Optional<String> section(String name) {
        String[] lines = text().split("\n");
        for (int i = 0; i < lines.length; i++) {
            Matcher m = TOOL_HEADING.matcher(lines[i]);
            if (!m.matches() || !m.group(1).equals(name)) continue;
            StringBuilder b = new StringBuilder(lines[i]).append('\n');
            for (int j = i + 1; j < lines.length && !lines[j].startsWith("### ") && !lines[j].startsWith("## "); j++)
                b.append(lines[j]).append('\n');
            return Optional.of(b.toString().strip());
        }
        return Optional.empty();
    }
}
