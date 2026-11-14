package io.github.llm4j.tools.browser;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Small helpers: cutting text, and a string as a JSON (and so JavaScript) literal. */
final class Limit {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Limit() {}

    static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    /** {@code value} as a literal a script can hold safely: quoted and escaped, or {@code null}. */
    static String json(String value) {
        try {
            return JSON.writeValueAsString(value).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029").replace("</", "<\\/");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
