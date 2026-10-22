package io.github.llm4j.loom.review.report;

/**
 * Makes text from a script or a prompt safe to print: control characters (such as terminal escapes)
 * become spaces.
 */
final class Safe {

    private Safe() {}

    /** One line: every control character, including line breaks, becomes a space. */
    static String line(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(Character.isISOControl(c) ? ' ' : c);
        }
        return b.toString();
    }
}
