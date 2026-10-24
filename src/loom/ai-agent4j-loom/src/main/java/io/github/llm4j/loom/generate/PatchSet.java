package io.github.llm4j.loom.generate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.llm4j.loom.review.fix.UnifiedDiff;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A change to a project as the model proposes it: edits to existing files (text to find and the
 * text that replaces it, so the lines it does not name are never touched) and new files. It is
 * applied to a copy; the diff it makes is what the developer reads.
 */
public record PatchSet(
        Map<String, List<Edit>> edits, Map<String, String> newFiles, String question) {

    /** Replaces {@code search} (which occurs exactly once) with {@code replace}. */
    public record Edit(String search, String replace) {}

    public PatchSet {
        edits = edits == null ? Map.of() : edits;
        newFiles = newFiles == null ? Map.of() : newFiles;
    }

    public boolean isEmpty() {
        return edits.isEmpty() && newFiles.isEmpty();
    }

    private static final Pattern BLOCK =
            Pattern.compile(
                    "(?ms)^=== (edit|file|question)(?:: ?(.*?))? ===[ \\t]*\\R(.*?)^=== end ===[ \\t]*$");
    private static final Pattern PAIR =
            Pattern.compile(
                    "(?ms)^<<<<<<< SEARCH[ \\t]*\\R(.*?)^=======[ \\t]*\\R(.*?)^>>>>>>> REPLACE[ \\t]*$");

    /** Reads the blocks of a reply. A reply with a question block is only a question. */
    public static PatchSet parse(String reply) {
        Map<String, List<Edit>> edits = new LinkedHashMap<>();
        Map<String, String> files = new LinkedHashMap<>();
        String question = null;
        if (reply == null) return new PatchSet(edits, files, null);
        Matcher m = BLOCK.matcher(reply);
        while (m.find()) {
            String kind = m.group(1);
            String name = m.group(2) == null ? "" : m.group(2).strip();
            String body = m.group(3);
            switch (kind) {
                case "question" -> question = body.strip();
                case "file" -> files.put(name, body.endsWith("\n") ? body : body + "\n");
                default -> {
                    Matcher p = PAIR.matcher(body);
                    List<Edit> list = edits.computeIfAbsent(name, k -> new ArrayList<>());
                    while (p.find())
                        list.add(
                                new Edit(
                                        withoutLastNewline(p.group(1)),
                                        withoutLastNewline(p.group(2))));
                }
            }
        }
        edits.values().removeIf(List::isEmpty);
        return new PatchSet(edits, files, question);
    }

    /** What applying a patch to a project gave: the changed files, and what could not be done. */
    public record Applied(Map<String, String> changed, List<String> problems) {}

    /** Applies the patch to the files; nothing is written. */
    public Applied apply(Map<String, String> base) {
        Map<String, String> changed = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (var e : edits.entrySet()) {
            String path = e.getKey();
            String bad = Writer.problemWith(path);
            if (bad != null) {
                problems.add("An edit names " + bad + ".");
                continue;
            }
            String text = base.get(path);
            if (text == null) {
                problems.add(
                        "An edit names "
                                + path
                                + ", which the project does not have (use a file block for a new file).");
                continue;
            }
            for (Edit edit : e.getValue()) {
                if (edit.search().isBlank()) {
                    problems.add("An edit of " + path + " has nothing to search for.");
                    continue;
                }
                int at = text.indexOf(edit.search());
                if (at < 0) {
                    problems.add(
                            "In "
                                    + path
                                    + " the text to replace is not there, word for word: "
                                    + shorten(edit.search()));
                    continue;
                }
                if (text.indexOf(edit.search(), at + 1) >= 0) {
                    problems.add(
                            "In "
                                    + path
                                    + " the text to replace occurs more than once; include more lines around it: "
                                    + shorten(edit.search()));
                    continue;
                }
                text =
                        text.substring(0, at)
                                + edit.replace()
                                + text.substring(at + edit.search().length());
            }
            changed.put(path, text);
        }
        for (var e : newFiles.entrySet()) {
            String bad = Writer.problemWith(e.getKey());
            if (bad != null) problems.add("A new file: " + bad + ".");
            else if (base.containsKey(e.getKey()))
                problems.add(e.getKey() + " already exists; change it with an edit block.");
            else changed.put(e.getKey(), e.getValue());
        }
        problems.addAll(Writer.problems(changed));
        return new Applied(changed, problems);
    }

    /**
     * The block's text ends at the line before the marker, so its last line break belongs to the
     * marker.
     */
    private static String withoutLastNewline(String s) {
        if (s.endsWith("\r\n")) return s.substring(0, s.length() - 2);
        return s.endsWith("\n") ? s.substring(0, s.length() - 1) : s;
    }

    private static String shorten(String s) {
        String one = s.replaceAll("\\s+", " ").strip();
        return "\"" + (one.length() > 70 ? one.substring(0, 70) + "…" : one) + "\"";
    }

    /** The patch as a unified diff of every file it changes. */
    public static String diff(Map<String, String> before, Map<String, String> after) {
        StringBuilder sb = new StringBuilder();
        for (var e : after.entrySet()) {
            String old = before.get(e.getKey());
            if (e.getValue().equals(old)) continue;
            sb.append(UnifiedDiff.of(e.getKey(), old == null ? "" : old, e.getValue()));
        }
        return sb.toString();
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A saved patch: the changed files in full, so it can be applied later without the model. */
    public static String save(
            String request, Map<String, String> before, Map<String, String> after) {
        ObjectNode n = JSON.createObjectNode();
        n.put("version", 1).put("request", request);
        ObjectNode files = n.putObject("files");
        ObjectNode base = n.putObject("was");
        for (var e : after.entrySet())
            if (!e.getValue().equals(before.get(e.getKey()))) {
                files.put(e.getKey(), e.getValue());
                if (before.containsKey(e.getKey()))
                    base.put(e.getKey(), sha(before.get(e.getKey())));
            }
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(n) + "\n";
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A saved patch read back: the files it writes and the fingerprints of the files it was made
     * against.
     */
    public record Saved(String request, Map<String, String> files, Map<String, String> was) {}

    public static Saved read(String json) {
        try {
            var n = JSON.readTree(json);
            Map<String, String> files = new LinkedHashMap<>();
            n.path("files")
                    .fields()
                    .forEachRemaining(e -> files.put(e.getKey(), e.getValue().asText()));
            Map<String, String> was = new LinkedHashMap<>();
            n.path("was")
                    .fields()
                    .forEachRemaining(e -> was.put(e.getKey(), e.getValue().asText()));
            if (files.isEmpty())
                throw new IllegalArgumentException("The saved patch changes no file.");
            return new Saved(n.path("request").asText(""), files, was);
        } catch (IOException e) {
            throw new IllegalArgumentException("The saved patch is not valid JSON.");
        }
    }

    public static String sha(String text) {
        try {
            byte[] d =
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(d).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
