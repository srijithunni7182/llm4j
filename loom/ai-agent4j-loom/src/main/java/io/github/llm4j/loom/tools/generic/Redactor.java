package io.github.llm4j.loom.tools.generic;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Removes secret values from text on its way out of a tool. Each secret is matched as written, URL-encoded,
 * and inside Base64 (standard and URL-safe, at each of the three byte alignments, so a Basic-auth header
 * that embeds it is caught too).
 */
public final class Redactor {

    /** Secrets shorter than this can't be scrubbed without mangling ordinary text; kinds refuse them at load. */
    public static final int MIN_SECRET_LENGTH = 4;

    public static final Redactor NONE = new Redactor(List.of());

    private final List<String> needles;

    public Redactor(Collection<String> secrets) {
        Set<String> all = new LinkedHashSet<>();
        for (String secret : secrets) {
            if (secret == null || secret.length() < MIN_SECRET_LENGTH) continue;
            all.add(secret);
            all.add(URLEncoder.encode(secret, StandardCharsets.UTF_8));
            all.add(URLEncoder.encode(secret, StandardCharsets.UTF_8).replace("+", "%20"));
            all.addAll(base64Fragments(secret.getBytes(StandardCharsets.UTF_8)));
        }
        List<String> sorted = new ArrayList<>(all);
        sorted.removeIf(String::isEmpty);
        sorted.sort(Comparator.comparingInt(String::length).reversed());
        this.needles = List.copyOf(sorted);
    }

    /** The text with every secret replaced by {@code ***}. */
    public String scrub(String text) {
        if (text == null || needles.isEmpty()) return text;
        String out = text;
        for (String n : needles) {
            if (out.contains(n)) out = out.replace(n, "***");
        }
        return out;
    }

    /** The part of a Base64 encoding of {@code secret} that doesn't depend on the bytes around it. */
    private static List<String> base64Fragments(byte[] secret) {
        List<String> out = new ArrayList<>();
        for (int lead = 0; lead < 3; lead++) {
            byte[] padded = new byte[lead + secret.length];
            System.arraycopy(secret, 0, padded, lead, secret.length);
            String enc = Base64.getEncoder().withoutPadding().encodeToString(padded);
            int drop = switch (padded.length % 3) {
                case 1 -> 2;
                case 2 -> 1;
                default -> 0;
            };
            int from = lead == 0 ? 0 : lead + 1;
            int to = enc.length() - drop;
            if (to - from < MIN_SECRET_LENGTH) continue;
            String fragment = enc.substring(from, to);
            out.add(fragment);
            out.add(fragment.replace('+', '-').replace('/', '_'));
        }
        return out;
    }
}
