package io.github.llm4j.loom.prompt;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A reference to a prompt, as written in a script: {@code "researcher"} (the latest version) or {@code "researcher@v2"}. */
public record PromptRef(String id, String version) {

    private static final Pattern SHAPE = Pattern.compile("([a-z0-9][a-z0-9_-]*)(?:@(v[0-9]+))?");

    /** The reference in {@code text}, or throws {@link IllegalArgumentException} saying what a reference looks like. */
    public static PromptRef parse(String text) {
        Matcher m = text == null ? null : SHAPE.matcher(text);
        if (m == null || !m.matches()) {
            throw new IllegalArgumentException("a prompt reference looks like \"researcher\" or \"researcher@v2\" (lower-case letters, digits, - and _; a version is v and a number), not \"" + text + "\"");
        }
        return new PromptRef(m.group(1), m.group(2));
    }

    /** True when the version is pinned in the reference itself. */
    public boolean pinned() {
        return version != null;
    }

    @Override
    public String toString() {
        return version == null ? id : id + "@" + version;
    }
}
