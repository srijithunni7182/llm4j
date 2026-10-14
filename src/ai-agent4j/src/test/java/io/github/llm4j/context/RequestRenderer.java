package io.github.llm4j.context;

import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.Message;
import io.github.llm4j.model.ToolCall;
import io.github.llm4j.model.ToolSpec;
import java.util.List;

/** Writes a request out in full, in a stable form, so two runs can be compared byte for byte. */
final class RequestRenderer {
    private RequestRenderer() {}

    static String render(List<LLMRequest> requests) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < requests.size(); i++) {
            sb.append("=== request ").append(i + 1).append(" ===\n").append(render(requests.get(i)));
        }
        return sb.toString();
    }

    static String render(LLMRequest r) {
        StringBuilder sb = new StringBuilder();
        sb.append("temperature=").append(r.getTemperature()).append(" maxTokens=").append(r.getMaxTokens()).append('\n');
        for (ToolSpec t : r.getTools()) sb.append("tool ").append(t.name()).append(": ").append(t.description()).append(" ").append(canon(t.parameters())).append('\n');
        for (Message m : r.getMessages()) {
            sb.append("--- ").append(m.getRole());
            if (m.getName() != null) sb.append(" name=").append(m.getName());
            if (m.getToolCallId() != null) sb.append(" toolCallId=").append(m.getToolCallId());
            sb.append('\n');
            for (ToolCall c : m.getToolCalls()) sb.append("call ").append(c.id()).append(' ').append(c.name()).append(' ').append(canon(c.arguments())).append('\n');
            sb.append(m.getContent()).append('\n');
        }
        return sb.toString();
    }

    /** Maps print in key order, so the text does not depend on the iteration order of an immutable map. */
    static Object canon(Object o) {
        if (o instanceof java.util.Map<?, ?> m) {
            java.util.TreeMap<String, Object> sorted = new java.util.TreeMap<>();
            m.forEach((k, v) -> sorted.put(String.valueOf(k), canon(v)));
            return sorted;
        }
        if (o instanceof List<?> l) return l.stream().map(RequestRenderer::canon).toList();
        return o;
    }
}
