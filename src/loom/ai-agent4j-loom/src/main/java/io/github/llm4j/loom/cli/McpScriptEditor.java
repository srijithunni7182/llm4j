package io.github.llm4j.loom.cli;

import io.github.llm4j.agent.task.TaskEffect;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Edits one {@code mcp} block of a script for {@code weave mcp list --write}: its {@code effects:}
 * and its {@code pin:}, and nothing else. A declared effect is a person's word, so a proposal only
 * fills in the tools nobody described. The rest of the script is left byte for byte as it was.
 */
final class McpScriptEditor {

    private static final Pattern EFFECTS = Pattern.compile("\\beffects\\s*:\\s*\\{([^}]*)}");
    private static final Pattern PIN = Pattern.compile("\\bpin\\s*:\\s*\"[^\"]*\"");
    private static final Pattern ENTRY = Pattern.compile("([A-Za-z0-9_.\\-\"]+)\\s*:\\s*(none|reads|changes)");

    private McpScriptEditor() {}

    /**
     * The script with {@code server}'s block carrying the effects it already declared, plus {@code
     * proposed} for tools it did not, and {@code pin}.
     *
     * @throws IllegalArgumentException when the script has no such block
     */
    static String apply(String source, String server, Map<String, TaskEffect> proposed, String pin) {
        Matcher open = Pattern.compile("\\bmcp\\s+" + Pattern.quote(server) + "\\s*\\{").matcher(source);
        int bodyStart = -1;
        while (open.find()) {
            if (!insideComment(source, open.start())) {
                bodyStart = open.end();
                break;
            }
        }
        if (bodyStart < 0)
            throw new IllegalArgumentException("the script has no mcp " + server + " block");
        int close = matchingBrace(source, bodyStart);
        String body = source.substring(bodyStart, close);

        Map<String, TaskEffect> merged = new LinkedHashMap<>();
        Matcher old = EFFECTS.matcher(body);
        if (old.find()) {
            Matcher entry = ENTRY.matcher(old.group(1));
            while (entry.find())
                merged.put(
                        entry.group(1).replace("\"", ""),
                        TaskEffect.valueOf(entry.group(2).toUpperCase(Locale.ROOT)));
        }
        proposed.forEach(merged::putIfAbsent);

        String kept = PIN.matcher(EFFECTS.matcher(body).replaceFirst("")).replaceFirst("");
        boolean multiLine = body.contains("\n");
        List<String> added = new ArrayList<>();
        if (!merged.isEmpty()) {
            List<String> parts = new ArrayList<>();
            merged.forEach(
                    (tool, effect) ->
                            parts.add(spell(tool) + ": " + effect.name().toLowerCase(Locale.ROOT)));
            added.add("effects: { " + String.join(", ", parts) + " }");
        }
        added.add("pin: \"" + pin + "\"");

        String rebuilt;
        if (multiLine) {
            String closingIndent = indentBefore(source, close);
            String trimmed = kept.replaceAll("\\s+$", "");
            StringBuilder sb = new StringBuilder(trimmed);
            for (String line : added) sb.append("\n").append(closingIndent).append("    ").append(line);
            sb.append("\n").append(closingIndent);
            rebuilt = sb.toString();
        } else {
            rebuilt = kept.replaceAll("\\s+$", "") + " " + String.join(" ", added) + " ";
        }
        return source.substring(0, bodyStart) + rebuilt + source.substring(close);
    }

    /** A tool name as the script writes it: bare when it is a word, quoted when it has other characters. */
    private static String spell(String tool) {
        return tool.matches("[A-Za-z_][A-Za-z0-9_]*") ? tool : "\"" + tool.replace("\"", "\\\"") + "\"";
    }

    /** Whether the text at {@code at} is inside a line comment. */
    private static boolean insideComment(String source, int at) {
        int lineStart = source.lastIndexOf('\n', at - 1) + 1;
        return source.substring(lineStart, at).contains("//");
    }

    /** The index of the brace that closes the block whose body starts at {@code from}. */
    private static int matchingBrace(String source, int from) {
        int depth = 1;
        boolean inString = false;
        for (int i = from; i < source.length(); i++) {
            char c = source.charAt(i);
            if (inString) {
                if (c == '\\') i++;
                else if (c == '"') inString = false;
            } else if (c == '"') {
                inString = true;
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                int newline = source.indexOf('\n', i);
                i = newline < 0 ? source.length() : newline;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        throw new IllegalArgumentException("the mcp block has no closing brace");
    }

    private static String indentBefore(String source, int at) {
        int lineStart = source.lastIndexOf('\n', at - 1) + 1;
        String before = source.substring(lineStart, at);
        return before.isBlank() ? before : "";
    }

    /** A unified-style listing of what changed between two texts: removed lines with {@code -}, added with {@code +}. */
    static String diff(String before, String after) {
        String[] a = before.split("\n", -1);
        String[] b = after.split("\n", -1);
        int[][] lcs = new int[a.length + 1][b.length + 1];
        for (int i = a.length - 1; i >= 0; i--)
            for (int j = b.length - 1; j >= 0; j--)
                lcs[i][j] = a[i].equals(b[j]) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
        StringBuilder out = new StringBuilder();
        int i = 0;
        int j = 0;
        while (i < a.length && j < b.length) {
            if (a[i].equals(b[j])) {
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                out.append("- ").append(a[i++]).append('\n');
            } else {
                out.append("+ ").append(b[j++]).append('\n');
            }
        }
        while (i < a.length) out.append("- ").append(a[i++]).append('\n');
        while (j < b.length) out.append("+ ").append(b[j++]).append('\n');
        return out.toString();
    }
}
