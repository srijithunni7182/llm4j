package io.github.llm4j.eval.optimize;

import io.github.llm4j.eval.report.AtomicFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The difference between the seed and the optimized candidate, ready for human review. Nothing is
 * written until you call {@link #applyTo(Path)}; the optimizer itself never touches your files.
 * Each parameter maps to a file named {@code <parameter>.txt} (unsafe characters become {@code _});
 * the diff assumes each file ends with a newline.
 */
public final class PromptPatch {

    private final Map<String, String> before;
    private final Map<String, String> after;

    PromptPatch(Map<String, String> before, Map<String, String> after) {
        this.before = new LinkedHashMap<>(before);
        this.after = new LinkedHashMap<>(after);
    }

    /** Names of parameters whose text changed. */
    public Set<String> changedParameters() {
        Set<String> changed = new java.util.LinkedHashSet<>();
        after.forEach(
                (name, text) -> {
                    if (!text.equals(before.get(name))) {
                        changed.add(name);
                    }
                });
        return changed;
    }

    public boolean isEmpty() {
        return changedParameters().isEmpty();
    }

    /** A unified diff of every changed parameter; empty when nothing changed. */
    public String unifiedDiff() {
        StringBuilder out = new StringBuilder();
        for (String name : changedParameters()) {
            out.append(
                    LineDiff.unified(
                            fileName(name), before.getOrDefault(name, ""), after.get(name)));
        }
        return out.toString();
    }

    /** The new text of a changed parameter. */
    public String newText(String parameter) {
        String text = after.get(parameter);
        if (text == null) {
            throw new IllegalArgumentException("unknown parameter: " + parameter);
        }
        return text;
    }

    /**
     * Writes each changed parameter's new text to {@code <directory>/<parameter>.txt}. This is the
     * only method that writes outside the report and checkpoint directories.
     */
    public void applyTo(Path directory) {
        List<String> names = new ArrayList<>();
        for (String name : changedParameters()) {
            String file = fileName(name);
            if (names.contains(file)) {
                throw new IllegalStateException(
                        "parameters map to the same file name \""
                                + file
                                + "\"; rename one of them");
            }
            names.add(file);
        }
        for (String name : changedParameters()) {
            String text = after.get(name);
            try {
                AtomicFiles.write(
                        directory.resolve(fileName(name)),
                        (text.endsWith("\n") ? text : text + "\n")
                                .getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalStateException("could not write " + fileName(name), e);
            }
        }
    }

    static String fileName(String parameter) {
        return parameter.replaceAll("[^A-Za-z0-9._-]", "_") + ".txt";
    }
}
