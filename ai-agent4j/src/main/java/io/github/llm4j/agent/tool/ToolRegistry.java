package io.github.llm4j.agent.tool;

import io.github.llm4j.agent.Tool;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A central registry for managing available Agent Tools.
 * This allows an orchestrating agent to resolve specific tools by name and pass them
 * to dynamically spawned sub-agents.
 */
public class ToolRegistry {
    private static final Logger logger = LoggerFactory.getLogger(ToolRegistry.class);
    
    private final Map<String, Tool> registry = new HashMap<>();

    /**
     * Registers a tool. If a tool with the same name exists, it will be overwritten.
     * @param tool the tool to register
     */
    public void register(Tool tool) {
        Objects.requireNonNull(tool, "Tool cannot be null");
        Objects.requireNonNull(tool.getName(), "Tool name cannot be null");
        registry.put(tool.getName(), tool);
        logger.debug("Registered tool: {}", tool.getName());
    }

    /**
     * Retrieves a tool by its exact name.
     * @param toolName the name of the tool
     * @return the Tool, or null if not found
     */
    public Tool getTool(String toolName) {
        return registry.get(toolName);
    }
    
    /**
     * Retrieves a list of all currently registered tools.
     * @return an unmodifiable list of tools
     */
    public List<Tool> getAllTools() {
        return Collections.unmodifiableList(new ArrayList<>(registry.values()));
    }

    /**
     * Resolves a list of tools by their names. Ignores names that are not found in the registry.
     * @param toolNames list of tool names to resolve
     * @return list of resolved Tools
     */
    public List<Tool> resolveTools(List<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return Collections.emptyList();
        }
        
        List<Tool> resolved = new ArrayList<>();
        for (String name : toolNames) {
            Tool tool = getTool(name);
            if (tool != null) {
                resolved.add(tool);
            } else {
                logger.warn("Tool '{}' requested but not found in ToolRegistry.", name);
            }
        }
        return resolved;
    }
}
