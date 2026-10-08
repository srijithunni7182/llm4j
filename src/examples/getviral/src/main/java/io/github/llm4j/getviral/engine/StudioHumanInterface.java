package io.github.llm4j.getviral.engine;

import io.github.llm4j.getviral.studio.StudioRun;
import io.github.llm4j.loom.runtime.HumanInterface;
import io.github.llm4j.loom.runtime.VariableContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Serves Loom's {@code human_prompt} statements from the studio. The question is asked under Loom's
 * step id, so the answer can arrive later (the run suspends meanwhile) and be replayed on resume.
 */
public class StudioHumanInterface implements HumanInterface {

    private final StudioRun run;
    private final Supplier<VariableContext> context;

    public StudioHumanInterface(StudioRun run, Supplier<VariableContext> context) {
        this.run = run;
        this.context = context;
    }

    @Override
    public String promptHuman(String message) {
        return promptHuman("question", message);
    }

    @Override
    public String promptHuman(String stepId, String message) {
        String kind = kind(message);
        List<String> options = kind.equals("hook") ? hooks() : kind.equals("publish") ? List.of("skip") : List.of();
        return normalize(kind, run.ask(kind, stepId, text(message), options), options);
    }

    /** The question kind from its tag in the .loom ({@code HOOK_PICK:}, {@code PUBLISH:}). */
    static String kind(String message) {
        if (message.startsWith("HOOK_PICK:")) return "hook";
        if (message.startsWith("PUBLISH:")) return "publish";
        return "question";
    }

    static String text(String message) {
        return message.replaceFirst("^(HOOK_PICK|PUBLISH):", "").strip();
    }

    /**
     * Turns a raw answer into what the workflow expects: a blank hook pick means the first hook, and a
     * publish answer that isn't an https URL means skip. Applied before an answer is recorded.
     */
    public static String normalize(String kind, String answer, List<String> options) {
        String a = answer == null ? "" : answer.strip();
        return switch (kind) {
            case "hook" -> a.isEmpty() && !options.isEmpty() ? options.get(0) : a;
            case "publish" -> a.startsWith("https://") ? a : "skip";
            case "approval" -> "approve".equalsIgnoreCase(a) ? "approve" : "reject";
            default -> a;
        };
    }

    private List<String> hooks() {
        Object plan = context.get().getVariable("gamePlan");
        List<String> hooks = new ArrayList<>();
        if (plan instanceof Map<?, ?> map && map.get("hooks") instanceof List<?> list) {
            for (Object hook : list) {
                if (hook != null && !hook.toString().isBlank()) hooks.add(hook.toString().strip());
            }
        }
        return hooks;
    }
}
