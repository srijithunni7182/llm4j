package io.github.llm4j.loom.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.loom.execution.TraceEvent;
import io.github.llm4j.loom.execution.TraceListener;
import java.io.PrintStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/** {@code weave run --trace}: one line per event on stderr, readable or as JSON lines. */
final class ConsoleTrace implements TraceListener {

    static final int MAX_TEXT = 300;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final Map<String, String> ICONS = Map.ofEntries(
            Map.entry(TraceEvent.DELEGATE_START, "▶"),
            Map.entry(TraceEvent.DELEGATE_END, "✔"),
            Map.entry(TraceEvent.DELEGATE_REPLAYED, "↺"),
            Map.entry(TraceEvent.THOUGHT, "💭"),
            Map.entry(TraceEvent.ACTION, "🔧"),
            Map.entry(TraceEvent.OBSERVATION, "👁"),
            Map.entry(TraceEvent.BUDGET, "💸"),
            Map.entry(TraceEvent.APPROVAL, "✋"),
            Map.entry(TraceEvent.MEMORY, "🧠"),
            Map.entry(TraceEvent.GUARD, "🛡"),
            Map.entry(TraceEvent.VOICE, "🔊"),
            Map.entry(TraceEvent.SUSPENDED, "⏸"),
            Map.entry(TraceEvent.NOTE, "📝"),
            Map.entry(TraceEvent.TOOL, "🧰"),
            Map.entry(TraceEvent.CHECKPOINT, "📍"),
            Map.entry(TraceEvent.REWIND, "⏪"),
            Map.entry(TraceEvent.TASK_START, "⚙"),
            Map.entry(TraceEvent.TASK_END, "✔"),
            Map.entry(TraceEvent.TASK_REPLAYED, "↺"));

    private final PrintStream out;
    private final boolean json;

    ConsoleTrace(PrintStream out, boolean json) {
        this.out = out;
        this.json = json;
    }

    @Override
    public void onEvent(TraceEvent e) {
        String line;
        if (json) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", e.at().toString());
            m.put("type", e.type());
            m.put("agent", e.agent());
            m.put("step", e.step());
            m.put("text", cut(e.text()));
            if (!e.data().isEmpty()) m.put("data", e.data());
            try {
                line = JSON.writeValueAsString(m);
            } catch (Exception ex) {
                line = "{\"type\":\"" + e.type() + "\"}";
            }
        } else {
            line = TIME.format(e.at()) + " [" + e.step() + "] " + (e.agent() == null ? "" : e.agent() + "  ")
                    + ICONS.getOrDefault(e.type(), "·") + " " + cut(e.text()).replace('\n', ' ');
        }
        synchronized (out) {
            out.println(line);
        }
    }

    static String cut(String text) {
        if (text == null) return "";
        return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT) + "…";
    }
}
