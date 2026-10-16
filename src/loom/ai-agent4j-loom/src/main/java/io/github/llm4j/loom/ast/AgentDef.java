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
    private boolean streaming;
    private boolean reasoning;
    private boolean steerable;

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
    /** {@code streaming: true}: the reply is shown as it is written, and a steer or cancel ends a model call in progress. */
    public boolean isStreaming() { return streaming; }
    public void setStreaming(boolean streaming) { this.streaming = streaming; }
    /** {@code reasoning: true}: ask the model to show its reasoning (a summary) where it can. */
    public boolean isReasoning() { return reasoning; }
    public void setReasoning(boolean reasoning) { this.reasoning = reasoning; }
    /** {@code steerable: true}: the person this run serves may steer the agent while it works. Off by default. */
    public boolean isSteerable() { return steerable; }
    public void setSteerable(boolean steerable) { this.steerable = steerable; }
    public Integer getMaxIterations() { return maxIterations; }
    public void setMaxIterations(Integer maxIterations) { this.maxIterations = maxIterations; }
    public BudgetDef getBudget() { return budget; }
    public void setBudget(BudgetDef budget) { this.budget = budget; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }

    private String ladder;
    private int ladderLine;
    private final java.util.Map<String, io.github.llm4j.loom.autonomy.expr.Expr> ladderArgs = new java.util.LinkedHashMap<>();
    private String ceilingRung;
    private int ceilingRungLine;

    /**
     * The ladder every decision this agent proposes for binds unless it names its own ({@code ladder: Name} in the agent), or null. A decision with
     * its own {@code ladder:} or {@code trust { }} keeps it.
     */
    public String getLadder() { return ladder; }
    public int getLadderLine() { return ladderLine; }
    public java.util.Map<String, io.github.llm4j.loom.autonomy.expr.Expr> getLadderArgs() { return ladderArgs; }
    public void setLadder(String ladder, int line) { this.ladder = ladder; this.ladderLine = line; }
    /** The highest rung this agent may reach on any decision that does not state its own ceiling, or null for the top. */
    public String getCeilingRung() { return ceilingRung; }
    public int getCeilingRungLine() { return ceilingRungLine; }
    public void setCeilingRung(String rung, int line) { this.ceilingRung = rung; this.ceilingRungLine = line; }

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
        /** The most Loom's shared transcript may hold, in tokens, before it is compacted by this agent's {@link #getCompact} strategy; null for no bound. */
        public Integer getMaxTokens() { return has("max_tokens") ? getInt("max_tokens", 0) : null; }
        /** The strategy that bounds the transcript (default drop_oldest). */
        public String getCompact() { return get("compact", "drop_oldest"); }
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

    /**
     * {@code context { compact: chain(trim_tool_results, summarize, drop_oldest)  summarizer: "…"  trigger: 80%  target: 55%  keep_recent: 6  max_compactions: 20 }}.
     * How this agent keeps its prompt inside the model's window; the size of the window itself is never written here (it is configured per model, outside the script).
     * Keys other than these are options of the strategies named in {@code compact}.
     */
    public static class ContextConfig extends Settings {
        /** The keys that belong to the block itself. */
        public static final java.util.Set<String> OWN_KEYS = java.util.Set.of("compact", "summarizer", "trigger", "target", "keep_recent", "max_compactions");

        private final java.util.List<String> compact = new java.util.ArrayList<>();

        /** The strategies named by {@code compact:}, in order ({@code chain(a, b)} and {@code [a, b]} give several). */
        public java.util.List<String> compactNames() { return compact; }
        public String getSummarizer() { return get("summarizer"); }
        /** The trigger as a fraction (0.8 for 80%), or null when absent or not written as a percentage. */
        public Double getTrigger() { return fraction("trigger"); }
        public Double getTarget() { return fraction("target"); }

        private Double fraction(String key) {
            String v = get(key);
            if (v == null || !v.endsWith("%")) return null;
            try {
                return Double.parseDouble(v.substring(0, v.length() - 1).trim()) / 100.0;
            } catch (NumberFormatException e) {
                return null;
            }
        }

        /** Keys that are not the block's own: options for the strategies. */
        public java.util.Map<String, String> extraOptions() {
            java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
            for (var e : getValues().entrySet()) {
                if (!OWN_KEYS.contains(e.getKey()) && !e.getValue().isReference()) out.put(e.getKey(), e.getValue().value());
            }
            return out;
        }
    }

    private VoiceConfig voice;
    private GuardConfig guard;
    private ContextConfig context;

    public VoiceConfig getVoice() { return voice; }
    public void setVoice(VoiceConfig voice) { this.voice = voice; }
    public ContextConfig getContext() { return context; }
    public void setContext(ContextConfig context) { this.context = context; }
    public GuardConfig getGuard() { return guard; }
    public void setGuard(GuardConfig guard) { this.guard = guard; }
}
