package io.github.llm4j.nirmaan.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.loom.execution.TraceEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns what the workflow does (trace events) into what the screen shows: lines of the form {@code Agent: message} for the chat, in the
 * words of the crew. Anything that is bookkeeping (a file saved, a gate checked, a context size) is left out; anything a person would
 * want to know (an answer cut off at the output limit, a command that failed, a budget warning) is kept.
 */
final class UiEvents {

    /** One thing to send to the screen: an SSE event name and its data. */
    record Out(String event, String data) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    private UiEvents() {}

    static List<Out> convert(TraceEvent e) {
        List<Out> out = new ArrayList<>();
        String type = e.type();
        Map<String, Object> data = e.data() == null ? Map.of() : e.data();
        switch (type) {
            case TraceEvent.DELEGATE_START -> out.add(log(e.agent(), working(e.text())));
            case TraceEvent.DELEGATE_END -> out.add(log(e.agent(), summary(e.text(), data)));
            case "model_call" -> {
                if (Boolean.TRUE.equals(data.get("truncated")) || "MAX_TOKENS".equalsIgnoreCase(String.valueOf(data.get("finish_reason")))) {
                    out.add(log("System", "⚠ " + e.agent() + "'s answer was cut off at the output limit, so it may be incomplete."));
                }
            }
            case TraceEvent.ACTION -> out.add(log(e.agent(), "🔧 " + clip(e.text(), 120)));
            case TraceEvent.TASK_START -> {
                if ("RunCommand".equals(data.get("task"))) {
                    String command = commandOf(data.get("args"));
                    if (command != null) out.add(log("Dhruv", "Running: " + clip(command, 160)));
                }
            }
            case TraceEvent.TASK_END -> {
                String task = String.valueOf(data.get("task") != null ? data.get("task") : firstWord(e.text()));
                String outcome = String.valueOf(data.get("outcome"));
                if (task.equals("RunCommand")) out.add(log("Dhruv", ("passed".equals(outcome) ? "✔ " : "✘ ") + outcome));
                if (task.equals("PackageProject")) out.add(log("System", "Packaged the project: " + outcome));
            }
            case TraceEvent.NOTE -> out.add(log("System", e.text()));
            case TraceEvent.BUDGET -> out.add(log("System", "Budget: " + clip(e.text(), 200)));
            default -> { }
        }
        return out;
    }

    static Out log(String agent, String message) {
        String who = agent == null || agent.isBlank() ? "System" : agent;
        return new Out("log", who.equals("System") ? message : who + ": " + message);
    }

    /** "Working: ..." for the first line of a task: its MODE, its STAGE, or what it starts with. */
    static String working(String task) {
        String first = firstLine(task);
        if (first.startsWith("MODE: ")) return "Working on " + first.substring(6).toLowerCase().replace('_', ' ') + "…";
        if (first.startsWith("STAGE: ")) return "Reviewing the project (" + first.substring(7) + ")…";
        if (first.startsWith("PRD:")) return "Designing the technical spec…";
        if (first.startsWith("Write the PRD")) return "Writing the product requirements…";
        return "Working: " + clip(first, 100);
    }

    /** What an agent's answer amounts to, in a sentence: the files it wrote, its verdict, its choice of stack. */
    static String summary(String answer, Map<String, Object> data) {
        String tokens = data.get("tokens") == null ? "" : " (" + data.get("tokens") + " tokens)";
        String text = answer == null ? "" : answer.strip();
        if (text.startsWith("{")) {
            try {
                JsonNode json = JSON.readTree(text);
                if (json.has("files") && json.get("files").isArray()) {
                    List<String> paths = new ArrayList<>();
                    json.get("files").forEach(f -> paths.add(f.path("path").asText("?")));
                    String shown = String.join(", ", paths.subList(0, Math.min(5, paths.size()))) + (paths.size() > 5 ? ", …" : "");
                    return "Wrote " + paths.size() + (paths.size() == 1 ? " file: " : " files: ") + shown + tokens;
                }
                if (json.has("verdict")) return "Verdict: " + json.get("verdict").asText() + ". " + clip(json.path("detail").asText(""), 200) + tokens;
                if (json.has("stack")) {
                    return "Chose " + json.get("stack").asText() + ". Tests: " + json.path("test_command").asText("?") + ". Build: " + json.path("build_command").asText("?") + tokens;
                }
            } catch (Exception notJson) {
                // fall through: show it as text
            }
        }
        return clip(text.replace("\n", " "), 220) + tokens;
    }

    static String firstLine(String text) {
        if (text == null) return "";
        String t = text.strip();
        int real = t.indexOf('\n');
        int literal = t.indexOf("\\n");
        int cut = real < 0 ? literal : literal < 0 ? real : Math.min(real, literal);
        return (cut < 0 ? t : t.substring(0, cut)).strip();
    }

    /** The command of a RunCommand step: its arguments arrive as a map or, from the trace, as sorted-key JSON text. */
    private static String commandOf(Object args) {
        try {
            if (args instanceof Map<?, ?> map) return map.get("command") == null ? null : String.valueOf(map.get("command"));
            if (args instanceof String text && text.startsWith("{")) {
                JsonNode json = JSON.readTree(text);
                return json.hasNonNull("command") ? json.get("command").asText() : null;
            }
        } catch (Exception unreadable) {
            // no command to show
        }
        return null;
    }

    private static String firstWord(String text) {
        return text == null ? "" : text.strip().split("\\s+")[0];
    }

    static String clip(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
