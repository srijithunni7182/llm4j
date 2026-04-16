package io.github.llm4j.loom.ast;

import java.util.ArrayList;
import java.util.List;

public class AgentDef implements Node {
    private final String name;
    private String model;
    private String systemPrompt;
    private final List<String> tools = new ArrayList<>();

    public AgentDef(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public List<String> getTools() {
        return tools;
    }

    public void addTool(String toolName) {
        this.tools.add(toolName);
    }
}
