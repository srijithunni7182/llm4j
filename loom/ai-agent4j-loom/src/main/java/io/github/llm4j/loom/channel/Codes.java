package io.github.llm4j.loom.channel;

import java.security.SecureRandom;
import java.util.Set;
import java.util.function.Predicate;

/** The short code a reply carries: six characters from 31 letters and digits that are hard to mix up (about 29.7 bits), from a secure source. */
public final class Codes {

    static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"; // no I, L, O, 0, 1
    private static final SecureRandom RANDOM = new SecureRandom();
    public static final int LENGTH = 6;

    private Codes() { }

    /** A code that {@code taken} says is not in use; a clash draws again. */
    public static String fresh(Predicate<String> taken) {
        for (int i = 0; i < 1000; i++) {
            String c = draw(RANDOM);
            if (!taken.test(c)) return c;
        }
        throw new IllegalStateException("no free question code (too many open questions)");
    }

    static String draw(SecureRandom random) {
        StringBuilder b = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) b.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        return b.toString();
    }

    /** Bits of entropy in a code. */
    public static double bits() {
        return LENGTH * (Math.log(ALPHABET.length()) / Math.log(2));
    }

    public static boolean looksLikeOne(String token) {
        if (token == null || token.length() != LENGTH) return false;
        String up = token.toUpperCase(java.util.Locale.ROOT);
        for (int i = 0; i < up.length(); i++) if (ALPHABET.indexOf(up.charAt(i)) < 0) return false;
        return true;
    }

    static Set<Character> alphabet() {
        Set<Character> s = new java.util.TreeSet<>();
        for (char c : ALPHABET.toCharArray()) s.add(c);
        return s;
    }
}
