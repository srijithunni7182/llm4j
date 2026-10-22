package io.github.llm4j.context;

import io.github.llm4j.model.Message;
import io.github.llm4j.model.ToolCall;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds conversations for tests: a system prompt, a task, then exchanges of tool calls and
 * results.
 */
final class Convo {
    private final List<Message> messages = new ArrayList<>();
    private int calls;

    Convo system(String text) {
        messages.add(Message.system(text));
        return this;
    }

    Convo task(String text) {
        messages.add(Message.user(text));
        return this;
    }

    /** An assistant turn with one tool call and its result. */
    Convo step(String tool, int resultChars) {
        return parallel(1, resultChars, tool);
    }

    /** An assistant turn with {@code n} tool calls in one message, then the {@code n} results. */
    Convo parallel(int n, int resultChars, String tool) {
        List<ToolCall> made = new ArrayList<>();
        for (int i = 0; i < n; i++)
            made.add(new ToolCall("call_" + (++calls), tool, Map.of("q", "query " + calls)));
        messages.add(Message.assistantToolCalls("thinking before " + tool, made, Map.of()));
        for (ToolCall c : made)
            messages.add(Message.toolResult(c.id(), tool, filler(c.id(), resultChars)));
        return this;
    }

    Convo answer(String text) {
        messages.add(Message.assistant(text));
        return this;
    }

    Convo add(Message m) {
        messages.add(m);
        return this;
    }

    List<Message> build() {
        return List.copyOf(messages);
    }

    static String filler(String label, int chars) {
        StringBuilder sb = new StringBuilder(label).append(':');
        int i = 0;
        while (sb.length() < chars) sb.append(" w").append(i++);
        return sb.substring(0, Math.max(chars, 0));
    }
}
