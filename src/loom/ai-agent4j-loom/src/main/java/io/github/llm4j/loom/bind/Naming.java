package io.github.llm4j.loom.bind;

import java.util.Locale;
import java.util.Set;

/** Java names from script names: stable, valid, and never a keyword. */
final class Naming {

    private Naming() {}

    private static final Set<String> KEYWORDS =
            Set.of(
                    "abstract",
                    "assert",
                    "boolean",
                    "break",
                    "byte",
                    "case",
                    "catch",
                    "char",
                    "class",
                    "const",
                    "continue",
                    "default",
                    "do",
                    "double",
                    "else",
                    "enum",
                    "extends",
                    "final",
                    "finally",
                    "float",
                    "for",
                    "goto",
                    "if",
                    "implements",
                    "import",
                    "instanceof",
                    "int",
                    "interface",
                    "long",
                    "native",
                    "new",
                    "package",
                    "private",
                    "protected",
                    "public",
                    "return",
                    "short",
                    "static",
                    "strictfp",
                    "super",
                    "switch",
                    "synchronized",
                    "this",
                    "throw",
                    "throws",
                    "transient",
                    "try",
                    "void",
                    "volatile",
                    "while",
                    "record",
                    "var",
                    "yield",
                    "true",
                    "false",
                    "null");

    private static String[] words(String s) {
        String spaced = s.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replaceAll("[^A-Za-z0-9]+", " ");
        return spaced.trim().isEmpty() ? new String[] {"x"} : spaced.trim().split(" ");
    }

    /** {@code ticket_body} to {@code ticketBody}; never a keyword; never starts with a digit. */
    static String member(String s) {
        StringBuilder sb = new StringBuilder();
        String[] w = words(s);
        for (int i = 0; i < w.length; i++) {
            String x = w[i].toLowerCase(Locale.ROOT);
            sb.append(i == 0 ? x : Character.toUpperCase(x.charAt(0)) + x.substring(1));
        }
        String out = sb.toString();
        if (Character.isDigit(out.charAt(0))) out = "_" + out;
        return KEYWORDS.contains(out) ? out + "_" : out;
    }

    /** {@code order_status} to {@code OrderStatus}. */
    static String type(String s) {
        String m = member(s);
        if (m.endsWith("_") && KEYWORDS.contains(m.substring(0, m.length() - 1)))
            m = m.substring(0, m.length() - 1);
        return Character.toUpperCase(m.charAt(0)) + m.substring(1);
    }

    /** {@code order-status} to {@code ORDER_STATUS}. */
    static String constant(String label) {
        String c = String.join("_", words(label)).toUpperCase(Locale.ROOT);
        return Character.isDigit(c.charAt(0)) ? "_" + c : c;
    }
}
