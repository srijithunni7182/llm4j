package io.github.llm4j.loom.channel;

/** Text from a case or a person, made safe to put in a message or a record. */
final class Text {

    private Text() { }

    /** Control characters become a visible mark; a line break is kept only when {@code keepLines} (the question's own layout). */
    static String safe(String text, boolean keepLines) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(c -> {
            boolean control = c < 0x20 || c == 0x7f || (c >= 0x80 && c < 0xa0) || c == 0x2028 || c == 0x2029
                    || (c >= 0x200b && c <= 0x200f) || (c >= 0x202a && c <= 0x202e) || (c >= 0x2066 && c <= 0x2069); // line separators, zero-width and direction-changing marks
            if (c == '\n' && keepLines) out.append('\n');
            else out.appendCodePoint(control ? '?' : c);
        });
        return out.toString();
    }

    static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, Math.max(0, max - 7)) + "…(cut)";
    }
}
