package io.github.llm4j.secret;

import java.util.regex.Pattern;

/**
 * What a secret's name looks like: a letter, then letters, digits, {@code _} or {@code -}, at most 64 characters. It matches environment
 * variable names ({@code GEMINI_API_KEY}) and Loom identifiers ({@code secret.gemini-prod}); a dot is not allowed, so {@code secret.a.b}
 * is never ambiguous.
 */
public final class SecretNames {

    public static final Pattern PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");

    private SecretNames() { }

    public static boolean isValid(String name) {
        return name != null && PATTERN.matcher(name).matches();
    }

    /** The name, or {@link IllegalArgumentException} saying what a name looks like. */
    public static String require(String name) {
        if (!isValid(name)) {
            throw new IllegalArgumentException("secret name \"" + name + "\" must be a letter followed by up to 63 letters, digits, _ or -");
        }
        return name;
    }
}
