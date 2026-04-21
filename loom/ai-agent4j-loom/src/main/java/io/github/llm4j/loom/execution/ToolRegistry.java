package io.github.llm4j.loom.execution;

import io.github.llm4j.agent.Tool;
import java.util.HashMap;
import java.util.Map;

public class ToolRegistry {
    private final Map<String, Tool> tools = new HashMap<>();

    public void register(String name, Tool tool) {
        tools.put(name, tool);
    }

    public Tool getTool(String name) {
        return tools.get(name);
    }
}
