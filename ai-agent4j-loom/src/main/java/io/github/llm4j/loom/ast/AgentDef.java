package io.github.llm4j.loom.ast;

import java.util.ArrayList;
import java.util.List;

public class AgentDef implements Node {
    private final String name;
    private String model;
    private String systemPrompt;
    /** Optional: name of a pre-defined persona from PersonaLibrary (e.g. "technicalAnalyst"). */
    private String persona;
    /** Optional: ID of a PromptTemplate in PromptRegistry; overrides inline system prompt. */
    private String systemTemplate;
    private final List<String> tools = new ArrayList<>();
    /** Names of mcp { } blocks whose tools are auto-bound to this agent. */
    private final List<String> mcpServers = new ArrayList<>();

    // Tier 2 additions
    private final List<String> skills = new ArrayList<>();
    private MemoryConfig memory;
    private String routingPolicy;
    private final List<String> knowledgeBases = new ArrayList<>();
    private SchemaDef outputSchema;

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

    public List<String> getTools() { return tools; }
    public void addTool(String toolName) { this.tools.add(toolName); }

    public String getPersona() { return persona; }
    public void setPersona(String persona) { this.persona = persona; }

    public String getSystemTemplate() { return systemTemplate; }
    public void setSystemTemplate(String systemTemplate) { this.systemTemplate = systemTemplate; }

    public List<String> getMcpServers() { return mcpServers; }
    public void addMcpServer(String serverName) { this.mcpServers.add(serverName); }

    public List<String> getSkills() { return skills; }
    public void addSkill(String skillUri) { this.skills.add(skillUri); }

    public MemoryConfig getMemory() { return memory; }
    public void setMemory(MemoryConfig memory) { this.memory = memory; }

    public String getRoutingPolicy() { return routingPolicy; }
    public void setRoutingPolicy(String routingPolicy) { this.routingPolicy = routingPolicy; }

    public List<String> getKnowledgeBases() { return knowledgeBases; }
    public void addKnowledgeBase(String kbName) { this.knowledgeBases.add(kbName); }

    public SchemaDef getOutputSchema() { return outputSchema; }
    public void setOutputSchema(SchemaDef outputSchema) { this.outputSchema = outputSchema; }

    /** Inner class for agent memory configuration. */
    public static class MemoryConfig {
        private String type;
        private String path;
        private int limit = 10;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public int getLimit() { return limit; }
        public void setLimit(int limit) { this.limit = limit; }
    }
}
