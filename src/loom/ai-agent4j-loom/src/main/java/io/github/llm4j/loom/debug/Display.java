package io.github.llm4j.loom.debug;

/** Text from a run, made safe to show: what a run held is data, not instructions to a screen. */
public final class Display {

    private Display() {}

    /** The text with control characters, bidirectional overrides and the like shown as escapes. */
    public static String safe(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else if (c < 0x20
                    || c == 0x7f
                    || (c >= 0x80 && c < 0xa0)
                    || c == 0x202e
                    || (c >= 0x2066 && c <= 0x2069)) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.toString();
    }
}
