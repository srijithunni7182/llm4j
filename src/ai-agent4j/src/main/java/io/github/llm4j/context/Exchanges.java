package io.github.llm4j.context;

import io.github.llm4j.model.Message;
import io.github.llm4j.model.ToolCall;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Splits a conversation into units that are kept or removed whole: an assistant message with tool calls together with the
 * tool messages answering it, or any other single message. Also finds what a compactor must not touch.
 */
final class Exchanges {

    /** Messages {@code [start, end)}. */
    record Unit(int start, int end) {
        int size() {
            return end - start;
        }
    }

    /** The protected prefix {@code [0, prefixEnd)}, the protected tail {@code [tailStart, n)} and the units between them. */
    record Region(int prefixEnd, int tailStart, List<Unit> middle) {}

    private Exchanges() {}

    static List<Unit> units(List<Message> messages) {
        List<Unit> units = new ArrayList<>();
        int n = messages.size();
        int i = 0;
        while (i < n) {
            Message m = messages.get(i);
            int j = i + 1;
            if (m.getRole() == Message.Role.ASSISTANT && !m.getToolCalls().isEmpty()) {
                Set<String> ids = new HashSet<>();
                for (ToolCall c : m.getToolCalls()) ids.add(c.id());
                while (j < n && messages.get(j).getRole() == Message.Role.TOOL && ids.contains(messages.get(j).getToolCallId())) j++;
            }
            units.add(new Unit(i, j));
            i = j;
        }
        return units;
    }

    /** The leading system messages and the first user message are the prefix; the last {@code keepRecent} messages, widened to whole units, the tail. */
    static Region region(List<Message> messages, int keepRecent) {
        int n = messages.size();
        int i = 0;
        while (i < n && messages.get(i).getRole() == Message.Role.SYSTEM) i++;
        int prefixEnd = i;
        for (int j = i; j < n; j++) {
            if (messages.get(j).getRole() == Message.Role.USER) {
                prefixEnd = j + 1;
                break;
            }
        }
        List<Unit> units = units(messages);
        int tailStart = n;
        if (keepRecent > 0) {
            int first = Math.max(0, n - keepRecent);
            tailStart = first;
            for (Unit u : units) {
                if (first >= u.start() && first < u.end()) {
                    tailStart = u.start();
                    break;
                }
            }
        }
        tailStart = Math.max(tailStart, prefixEnd);
        List<Unit> middle = new ArrayList<>();
        for (Unit u : units) if (u.start() >= prefixEnd && u.end() <= tailStart) middle.add(u);
        return new Region(prefixEnd, tailStart, middle);
    }
}
