package io.github.llm4j.provider;

import java.util.regex.Pattern;

/**
 * Some models write their reasoning into the answer as a leading {@code <think>…</think>} block. The
 * contract says content is the answer only, so providers for such models remove it — whole answers with
 * {@link #strip}, streams with a {@link StreamFilter}.
 */
public final class ThinkTags {

    private static final Pattern LEADING = Pattern.compile("^\\s*<think>.*?</think>\\s*", Pattern.DOTALL);
    private static final String OPEN = "<think>";
    private static final String CLOSE = "</think>";

    private ThinkTags() {}

    /** The text without a leading think block (unchanged if there is none, or it never closes). */
    public static String strip(String text) {
        if (text == null) return null;
        return LEADING.matcher(text).replaceFirst("");
    }

    /** Removes a leading think block from text arriving in pieces. Not thread-safe: one per stream. */
    public static final class StreamFilter {
        private final StringBuilder pending = new StringBuilder();
        private enum State { START, THINKING, ANSWER }
        private State state = State.START;
        private boolean trimming; // after a think block: drop whitespace until the answer starts

        /** The part of this piece that is answer text (possibly empty). */
        public String accept(String piece) {
            if (piece == null || piece.isEmpty()) return "";
            switch (state) {
                case ANSWER:
                    return trimmed(piece);
                case START: {
                    pending.append(piece);
                    String head = pending.toString().stripLeading();
                    if (head.length() < OPEN.length() && OPEN.startsWith(head)) return ""; // can't tell yet
                    if (head.startsWith(OPEN)) {
                        state = State.THINKING;
                        pending.setLength(0);
                        pending.append(head.substring(OPEN.length()));
                        return afterThinking();
                    }
                    state = State.ANSWER;
                    String out = pending.toString();
                    pending.setLength(0);
                    return out;
                }
                case THINKING:
                default:
                    pending.append(piece);
                    return afterThinking();
            }
        }

        /** Anything held back at the end of the stream (text that only looked like the start of a tag). */
        public String flush() {
            String out = state == State.START ? pending.toString() : "";
            pending.setLength(0);
            return out;
        }

        private String afterThinking() {
            int end = pending.indexOf(CLOSE);
            if (end < 0) {
                // keep only a tail that could still be the start of "</think>"
                if (pending.length() > CLOSE.length()) pending.delete(0, pending.length() - CLOSE.length());
                return "";
            }
            String rest = pending.substring(end + CLOSE.length());
            pending.setLength(0);
            state = State.ANSWER;
            trimming = true;
            return trimmed(rest);
        }

        private String trimmed(String piece) {
            if (!trimming) return piece;
            String rest = piece.stripLeading();
            if (!rest.isEmpty()) trimming = false;
            return rest;
        }
    }
}
