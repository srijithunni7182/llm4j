package io.github.llm4j.evalreport.format;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The stable identifiers of the run format (spec 01 §3.1); the same rules eval4j uses to write
 * them.
 */
public final class Ids {

    private Ids() {}

    private static String hex16(String text) {
        try {
            byte[] d =
                    MessageDigest.getInstance("SHA-256")
                            .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(Character.forDigit((d[i] >> 4) & 0xF, 16))
                        .append(Character.forDigit(d[i] & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String caseId(String caseKey) {
        return "c_" + hex16("case\u0000" + caseKey);
    }

    public static String key(String caseKey, String metricId, int occurrence) {
        return "k_" + hex16(caseKey + "\u0000" + metricId + "\u0000" + occurrence);
    }

    /** Lower-case, runs of other characters become a dash. */
    public static String slug(String name) {
        if (name == null || name.isBlank()) {
            return "metric";
        }
        String s =
                name.toLowerCase(java.util.Locale.ROOT)
                        .replaceAll("[^a-z0-9]+", "-")
                        .replaceAll("^-+|-+$", "");
        if (s.isEmpty()) {
            return "metric";
        }
        if (!Character.isLetter(s.charAt(0))) {
            s = "m-" + s;
        }
        return s.length() > 64 ? s.substring(0, 64) : s;
    }
}
