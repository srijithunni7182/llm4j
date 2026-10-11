package io.github.llm4j.loom.inspect.map;

import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.InspectDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.inspect.state.InspectRules;
import java.util.LinkedHashMap;
import java.util.Map;

/** The {@code inspect { }} blocks of a script as plain rules by agent name, the form that travels to a console or a server. */
public final class InspectRulesView {

    private InspectRulesView() { }

    public static Map<String, InspectRules> of(LoomScript script) {
        Map<String, InspectRules> out = new LinkedHashMap<>();
        if (script == null) return out;
        for (AgentDef a : script.getAgents()) {
            InspectDef d = a.getInspect();
            if (d == null || d.isEmpty()) continue;
            out.put(a.getName(), new InspectRules(d.getSameAction(), d.getSameOutput(), d.getToolFailures(), d.getUngroundedWarn(), d.getUngroundedHigh(),
                    d.getPausedLongerThan() == null ? null : d.getPausedLongerThan().toSeconds(), d.getNoProgressRounds(), d.getNoProgressVariable()));
        }
        return out;
    }
}
