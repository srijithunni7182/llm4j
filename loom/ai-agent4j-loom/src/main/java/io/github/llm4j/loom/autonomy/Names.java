package io.github.llm4j.loom.autonomy;

import java.util.regex.Pattern;

/** Decision names become directory names and table keys, so they are checked wherever they come from a person. */
public final class Names {

    private static final Pattern SAFE = Pattern.compile("[A-Za-z_][A-Za-z0-9_-]{0,127}");

    private Names() { }

    public static String check(String decision) {
        if (decision == null || !SAFE.matcher(decision).matches()) throw new IllegalArgumentException("\"" + decision + "\" is not a decision name (letters, digits, _ and - only)");
        return decision;
    }
}
