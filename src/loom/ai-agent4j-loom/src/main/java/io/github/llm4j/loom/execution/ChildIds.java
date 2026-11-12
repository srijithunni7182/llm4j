package io.github.llm4j.loom.execution;

/**
 * The run id of a started workflow (decision D4): worked out from the parent's run id and the step
 * id of the {@code start} statement, so starting again after a crash finds the same child and does
 * not make a second. The step id already carries the loop round, the branch and the generation of a
 * rewind ({@code Main/s5~2}), so a resume re-attaches and a rewind starts a fresh child.
 */
final class ChildIds {

    private ChildIds() {}

    /**
     * The id for the child started at {@code stepId} of the run {@code parentRunId}. It is made of
     * letters, digits, dots, dashes and underscores only, so it is a safe folder or row name, and
     * two different step ids never give the same id.
     */
    static String derive(String parentRunId, String stepId) {
        StringBuilder id = new StringBuilder(parentRunId).append('.');
        for (char c : stepId.toCharArray()) {
            switch (c) {
                case '/' -> id.append('.');
                case '~' -> id.append("_g");
                case '>' -> id.append("_c");
                case '_' -> id.append("__");
                default -> {
                    if (Character.isLetterOrDigit(c) && c < 128 || c == '-') id.append(c);
                    else id.append("_x").append(Integer.toHexString(c)).append('_');
                }
            }
        }
        return id.length() <= MAX_LENGTH ? id.toString() : shortened(id.toString());
    }

    /** The longest run id a registry accepts (it becomes a folder name). */
    static final int MAX_LENGTH = 100;

    private static final int KEPT = 79;
    private static final int DIGEST_CHARS = 20;

    /**
     * A long id cut short and ended with a digest of the whole, so it stays deterministic and two
     * long ids that begin alike still differ.
     */
    private static String shortened(String full) {
        try {
            byte[] digest =
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(full.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return full.substring(0, KEPT)
                    + "-"
                    + java.util.HexFormat.of().formatHex(digest).substring(0, DIGEST_CHARS);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }
}
