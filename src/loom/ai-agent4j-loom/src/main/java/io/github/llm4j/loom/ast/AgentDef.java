package io.github.llm4j.loom.ast;

import java.util.ArrayList;
import java.util.List;

public class AgentDef implements Node {
    private int line;
    /** The line it was declared on, for error messages. */
    public int getLine() { return line; }
    public void setLine(int line) { this.line = line; }

    private final String name;
    private String model;
    private String systemPrompt;
    /** Optional: name of a pre-defined persona from PersonaLibrary (e.g. "technicalAnalyst"). */
    private String persona;
    /** Optional: ID of a PromptTemplate in PromptRegistry; overrides inline system prompt. */
    private String systemTemplate;
    /** Optional: a prompt file reference from {@code prompt: "id"} or {@code prompt: "id@v2"}. */
    private String prompt;
    private final List<String> tools = new ArrayList<>();
    /** Names of mcp { } blocks whose tools are auto-bound to this agent. */
    private final List<String> mcpServers = new ArrayList<>();

    // Tier 2 additions
    private final List<String> skills = new ArrayList<>();
    private MemoryConfig memory;
    private String routingPolicy;
    private final List<String> knowledgeBases = new ArrayList<>();
    private SchemaDef outputSchema;
    /** Optional sampling temperature (0.0–2.0) for this agent's LLM calls; null = the runtime default. */
    private Double temperature;
    private BudgetDef budget;
    private final List<String> approve = new ArrayList<>();
    private boolean approveAll;
    private Integer maxIterations;

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

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }

    /** The prompt reference this agent uses: {@code prompt:} if given, else the older {@code system_template:}; null for neither. */
    public String getPromptRef() { return prompt != null ? prompt : systemTemplate; }

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

    public Double getTemperature() { return temperature; }
    /** This agent's budget ({@code budget { }} inside the agent), or null. */
    /** Tools whose calls need a person's approval ({@code approve: [..]}). */
    public List<String> getApprove() { return approve; }
    /** {@code approve: all}. */
    public boolean isApproveAll() { return approveAll; }
    public void setApproveAll(boolean approveAll) { this.approveAll = approveAll; }
    public Integer getMaxIterations() { return maxIterations; }
    public void setMaxIterations(Integer maxIterations) { this.maxIterations = maxIterations; }
    public BudgetDef getBudget() { return budget; }
    public void setBudget(BudgetDef budget) { this.budget = budget; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }

    public SchemaDef getOutputSchema() { return outputSchema; }
    public void setOutputSchema(SchemaDef outputSchema) { this.outputSchema = outputSchema; }

    /**
     * {@code memory { conversation: "chats/"  limit: 20  session: "{user}"  facts: "facts.json"  embedding: "…" }}:
     * the agent's own conversations and long-term facts. (Loom's memory engine, which passes workflow
     * context between steps, is separate.)
     */
    public static class MemoryConfig extends Settings {
        public String getConversation() { return get("conversation"); }
        public int getLimit() { return getInt("limit", 20); }
        public String getSession() { return get("session"); }
        public String getFacts() { return get("facts"); }
        public String getEmbedding() { return get("embedding"); }
        public int getRecall() { return getInt("recall", 5); }
        public double getMinSimilarity() { return getDouble("min_similarity", 0.7); }
    }

    /** {@code voice { listen: "sarvam/…"  speak: "sarvam/…"  language: "hi-IN"  voice: "…"  out: "audio" }}. */
    public static class VoiceConfig extends Settings {
        public String getListen() { return get("listen"); }
        public String getSpeak() { return get("speak"); }
        public String getLanguage() { return get("language"); }
        public String getVoice() { return get("voice"); }
        public String getOut() { return get("out", "audio"); }
    }

    /** {@code guard { pii: mask|block|warn  bias: warn|block  bias_model: "…" }}. */
    public static class GuardConfig extends Settings {
        public String getPii() { return get("pii"); }
        public String getBias() { return get("bias"); }
        public String getBiasModel() { return get("bias_model"); }
    }

    private VoiceConfig voice;
    private GuardConfig guard;

    public VoiceConfig getVoice() { return voice; }
    public void setVoice(VoiceConfig voice) { this.voice = voice; }
    public GuardConfig getGuard() { return guard; }
    public void setGuard(GuardConfig guard) { this.guard = guard; }
}
