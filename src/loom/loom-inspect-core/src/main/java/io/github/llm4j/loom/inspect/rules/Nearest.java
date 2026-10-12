package io.github.llm4j.loom.inspect.rules;

import java.util.Collection;

/** (A copy of Loom's own suggester, so the server needs no Loom runtime.) The closest known name to a misspelt one, for messages that say "did you mean …?". */
public final class Nearest {

    private Nearest() { }

    /** The nearest candidate within two edits (or sharing a long prefix), or null. */
    public static String nearest(String word, Collection<String> candidates) {
        if (word.length() < 3) {
            for (String c : candidates) if (c.equalsIgnoreCase(word)) return c;
            return null;                                        // a one- or two-letter word is too short for a guess to mean anything
        }
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String c : candidates) {
            int d = distance(word.toLowerCase(), c.toLowerCase());
            boolean prefix = word.length() >= 3 && (c.startsWith(word) || word.startsWith(c));
            if (prefix) d = Math.min(d, 1);
            if (d < bestDistance && d <= Math.max(2, word.length() / 4)) {
                best = c;
                bestDistance = d;
            }
        }
        return best;
    }

    /** " (did you mean 'x'?)" or an empty string. */
    public static String hint(String word, Collection<String> candidates) {
        String n = nearest(word, candidates);
        return n == null ? "" : " (did you mean '" + n + "'?)";
    }

    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
