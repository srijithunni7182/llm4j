package io.github.llm4j.loom.graph;

import java.util.regex.Pattern;

/**
 * Masks text that looks like a credential before it goes into a graph. A graph is meant to be shared (pasted in an
 * issue, saved in a report), and a prompt in a script can hold a key someone pasted by mistake.
 */
final class Redactor {

    static final String MASK = "••••";

    /** Well-known key shapes: provider keys, tokens, cloud access keys, bearer values and private key blocks. */
    private static final Pattern KEY_LIKE = Pattern.compile(
            "(?i)\\b(?:sk|pk|rk)[-_][A-Za-z0-9_-]{16,}"
                    + "|\\bxox[abprs]-[A-Za-z0-9-]{10,}"
                    + "|\\bgh[pousr]_[A-Za-z0-9]{20,}"
                    + "|\\bglpat-[A-Za-z0-9_-]{16,}"
                    + "|\\bAKIA[0-9A-Z]{16}\\b"
                    + "|\\bAIza[0-9A-Za-z_-]{30,}"
                    + "|\\bBearer\\s+[A-Za-z0-9._~+/=-]{16,}"
                    + "|-----BEGIN [A-Z ]*PRIVATE KEY-----"
                    + "|\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}");

    private Redactor() {
    }

    static String mask(String text) {
        return text == null ? null : KEY_LIKE.matcher(text).replaceAll(MASK);
    }
}
