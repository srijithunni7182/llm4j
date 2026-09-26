package io.github.llm4j.getviral.engine;

import io.github.llm4j.getviral.studio.StudioRun;
import io.github.llm4j.loom.runtime.HumanInterface;
import io.github.llm4j.loom.runtime.VariableContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Serves Loom's {@code human_prompt} statements from the studio (or its autopilot). */
public class StudioHumanInterface implements HumanInterface {

    private final StudioRun run;
    private final Supplier<VariableContext> context;

    public StudioHumanInterface(StudioRun run, Supplier<VariableContext> context) {
        this.run = run;
        this.context = context;
    }

    @Override
    public String promptHuman(String message) {
        if (message.startsWith("HOOK_PICK:")) {
            List<String> hooks = hooks();
            String fallback = hooks.isEmpty() ? "" : hooks.get(0);
            String answer = run.ask("hook", message.substring("HOOK_PICK:".length()).strip(), hooks, fallback);
            return answer == null || answer.isBlank() ? fallback : answer.strip();
        }
        if (message.startsWith("PUBLISH:")) {
            String answer = run.ask("publish", message.substring("PUBLISH:".length()).strip(), List.of("skip"), "skip");
            String url = answer == null ? "" : answer.strip();
            return url.startsWith("https://") ? url : "skip";
        }
        return run.ask("question", message, List.of(), "");
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
