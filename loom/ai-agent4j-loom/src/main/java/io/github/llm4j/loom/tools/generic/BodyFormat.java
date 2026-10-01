package io.github.llm4j.loom.tools.generic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The JSON a chat or ingest webhook expects for a message with an optional title. */
enum BodyFormat {

    SLACK("slack") {
        @Override Object body(String title, String text) {
            return Map.of("text", title == null ? text : "*" + title + "*\n" + text);
        }
    },
    DISCORD("discord") {
        @Override Object body(String title, String text) {
            String content = title == null ? text : "**" + title + "**\n" + text;
            String marker = "\n… [cut]";
            return Map.of("content", content.length() <= 2000 ? content : content.substring(0, 2000 - marker.length()) + marker);
        }
    },
    TEAMS("teams") {
        @Override Object body(String title, String text) {
            List<Object> blocks = new java.util.ArrayList<>();
            if (title != null) blocks.add(Map.of("type", "TextBlock", "text", title, "weight", "Bolder", "wrap", true));
            blocks.add(Map.of("type", "TextBlock", "text", text, "wrap", true));
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("type", "AdaptiveCard");
            card.put("version", "1.4");
            card.put("body", blocks);
            Map<String, Object> attachment = new LinkedHashMap<>();
            attachment.put("contentType", "application/vnd.microsoft.card.adaptive");
            attachment.put("content", card);
            return Map.of("type", "message", "attachments", List.of(attachment));
        }
    },
    JSON("json") {
        @Override Object body(String title, String text) {
            Map<String, Object> m = new LinkedHashMap<>();
            if (title != null) m.put("title", title);
            m.put("text", text);
            return m;
        }
    };

    private static final ObjectMapper MAPPER = new ObjectMapper();
    final String label;

    BodyFormat(String label) {
        this.label = label;
    }

    abstract Object body(String title, String text);

    String render(String title, String text) {
        try {
            return MAPPER.writeValueAsString(body(title, text));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static BodyFormat of(String label) {
        for (BodyFormat f : values()) if (f.label.equals(label)) return f;
        throw new OptionException("format: must be one of slack, discord, teams, json, not " + label);
    }
}
