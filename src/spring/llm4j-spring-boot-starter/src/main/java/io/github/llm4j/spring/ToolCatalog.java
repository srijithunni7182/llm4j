package io.github.llm4j.spring;

import io.github.llm4j.agent.Tool;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every tool the application offers, by name, with where each came from: {@code Tool} beans,
 * built-ins ({@code llm4j.tools.builtin}) and guarded tools ({@code llm4j.tools.custom.<name>}).
 * Two tools with one name are an error that names both, never a silent replacement.
 */
public final class ToolCatalog {

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final Map<String, String> origins = new LinkedHashMap<>();

    void add(Tool tool, String origin) {
        String name = tool.getName();
        String earlier = origins.get(name);
        if (earlier != null) {
            throw new Llm4jConfigurationException(
                    "two tools are named " + name + ": " + earlier + " and " + origin,
                    "Rename one of them (a guarded tool takes the name of its key under llm4j.tools.custom).");
        }
        tools.put(name, tool);
        origins.put(name, origin);
    }

    public List<Tool> tools() {
        return new ArrayList<>(tools.values());
    }

    public List<String> names() {
        return new ArrayList<>(tools.keySet());
    }

    public Tool get(String name) {
        return tools.get(name);
    }

    /** The tools called {@code names}, in that order. */
    public List<Tool> select(List<String> names, String property) {
        List<Tool> out = new ArrayList<>();
        for (String name : names) {
            Tool t = tools.get(name);
            if (t == null) {
                throw new Llm4jConfigurationException(
                        property + " names a tool that does not exist: " + name,
                        "The registered tools are: " + String.join(", ", tools.keySet()) + ".");
            }
            out.add(t);
        }
        return out;
    }
}
