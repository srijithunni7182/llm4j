package io.github.llm4j.eval.judge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Content-addresses a judge call — a hash of everything that determines what the judge would be
 * asked, and how — so a {@link JudgeCache} hit only ever occurs when the exact same call would be
 * made again, with no separate cache-invalidation logic needed. The judge system prompt itself
 * ({@link JudgePrompt#SYSTEM_PROMPT}) is folded in as an automatic rubric version: editing the
 * rubric text changes the key for every condition, so a prompt change can't silently keep serving
 * verdicts graded under the old wording.
 */
final class JudgeCacheKey {

    private JudgeCacheKey() {}

    static String compute(
            String name,
            String criteria,
            String input,
            String expectedOutput,
            List<String> context,
            List<String> retrievalContext,
            String actualOutput,
            String trajectory,
            double temperature,
            String judgeIdentifier) {
        String joined =
                String.join(
                        "\u0001",
                        JudgePrompt.SYSTEM_PROMPT,
                        nullToEmpty(name),
                        nullToEmpty(criteria),
                        nullToEmpty(input),
                        nullToEmpty(expectedOutput),
                        String.valueOf(context),
                        String.valueOf(retrievalContext),
                        nullToEmpty(actualOutput),
                        nullToEmpty(trajectory),
                        String.valueOf(temperature),
                        nullToEmpty(judgeIdentifier));
        return sha256Hex(joined);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format(java.util.Locale.ROOT, "%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new JudgeEvaluationException("SHA-256 not available", e);
        }
    }
}
