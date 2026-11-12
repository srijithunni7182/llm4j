package io.github.llm4j.loom.ast;

/**
 * Where a handle is held: a variable ({@code work}) or one key of a keyed variable ({@code
 * work[t.name]}, {@code work["a"]}). A handle is opaque (decision D2): it is usable only in {@code
 * start}, {@code wait}, {@code cancel}, {@code steer} and {@code supervise}, and in reading its
 * facts.
 *
 * @param name the variable that holds the handle, or the keyed variable
 * @param key the key, or null for a plain variable
 * @param keyIsLiteral true when the key was written in quotes
 */
public record HandleRef(String name, String key, boolean keyIsLiteral) {

    /** A handle held in a plain variable. */
    public static HandleRef variable(String name) {
        return new HandleRef(name, null, false);
    }

    /** True when the handle lives under a key of a keyed variable. */
    public boolean isKeyed() {
        return key != null;
    }

    /** As written in a script, for messages and graph labels. */
    public String describe() {
        if (key == null) return name;
        return keyIsLiteral ? name + "[\"" + key + "\"]" : name + "[" + key + "]";
    }
}
