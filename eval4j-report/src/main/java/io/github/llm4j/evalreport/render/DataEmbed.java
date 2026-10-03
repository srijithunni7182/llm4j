package io.github.llm4j.evalreport.render;

/**
 * Makes JSON safe to place inside a {@code <script type="application/json">} element. A model
 * contains arbitrary text from LLM output and test names; the characters that could end the script
 * element or be read as HTML comment openers are escaped as JSON unicode escapes, which JSON.parse
 * decodes back to the identical text.
 */
public final class DataEmbed {

    private DataEmbed() {}

    public static String escape(String json) {
        StringBuilder sb = new StringBuilder(json.length() + 64);
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            switch (c) {
                case '<' -> sb.append("\\u003c");
                case '>' -> sb.append("\\u003e");
                case '&' -> sb.append("\\u0026");
                case '\u2028' -> sb.append("\\u2028");
                case '\u2029' -> sb.append("\\u2029");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
