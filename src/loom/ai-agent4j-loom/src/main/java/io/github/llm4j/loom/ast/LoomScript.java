package io.github.llm4j.loom.ast;

import java.util.ArrayList;
import java.util.List;

public class LoomScript implements Node {
    private final List<AgentDef> agents = new ArrayList<>();
    private final List<WorkflowDef> workflows = new ArrayList<>();
    private final List<McpServerDef> mcpServers = new ArrayList<>();
    private final List<KnowledgeDef> knowledgeBases = new ArrayList<>();
    private final List<RoutingPolicyDef> routingPolicies = new ArrayList<>();
    private final List<ScheduleDef> schedules = new ArrayList<>();
    private final List<ToolDef> tools = new ArrayList<>();
    private final List<ProviderDef> providers = new ArrayList<>();
    private final List<PersonaDef> personas = new ArrayList<>();
    private final List<DecisionDef> decisions = new ArrayList<>();
    private final List<LadderDef> ladders = new ArrayList<>();
    private final List<MeasureDef> measures = new ArrayList<>();
    private final List<IntentClassifierDef> intentClassifiers = new ArrayList<>();
    private final List<String> imports = new ArrayList<>();
    private final List<Integer> importLines = new ArrayList<>();
    private String sharedPrompt;
    private String sharedPromptRef;
    private int sharedPromptLine;
    private String promptsDir;
    private int promptsDirLine;
    private AuditConfig auditConfig;
    private BudgetDef budget;
    private RateLimitDef rateLimits;
    private InterventionDef intervention;
    private final List<SuperviseDef> supervisions = new ArrayList<>();

    /** The {@code supervise Agent { }} blocks. */
    public List<SuperviseDef> getSupervisions() {
        return supervisions;
    }

    public void addSupervision(SuperviseDef def) {
        supervisions.add(def);
    }

    /** The script-level {@code intervention { }} defaults, or null. */
    public InterventionDef getIntervention() {
        return intervention;
    }

    public void setIntervention(InterventionDef intervention) {
        this.intervention = intervention;
    }

    /** The {@code intentclassifier} declarations. */
    public List<IntentClassifierDef> getIntentClassifiers() {
        return intentClassifiers;
    }

    public void addIntentClassifier(IntentClassifierDef def) {
        intentClassifiers.add(def);
    }

    /** The classifier called {@code name}, or null. */
    public IntentClassifierDef findIntentClassifier(String name) {
        return intentClassifiers.stream()
                .filter(c -> c.getName().equals(name))
                .findFirst()
                .orElse(null);
    }

    public void addAgent(AgentDef agent) {
        this.agents.add(agent);
    }

    public void addWorkflow(WorkflowDef workflow) {
        this.workflows.add(workflow);
    }

    public List<AgentDef> getAgents() {
        return agents;
    }

    public List<WorkflowDef> getWorkflows() {
        return workflows;
    }

    /**
     * The folder named by {@code prompts: "./prompts"}, relative to the script; null when the
     * script does not name one.
     */
    /**
     * {@code shared_prompt: "text"}: text put in front of every agent's system prompt, or null. It
     * is the same bytes in every agent's request, so a provider that reuses the start of a prompt
     * it has just seen can reuse it across the agents of the workflow.
     */
    public String getSharedPrompt() {
        return sharedPrompt;
    }

    /**
     * {@code shared_prompt: prompt "id"}: a prompt file or registry prompt used the same way, or
     * null.
     */
    public String getSharedPromptRef() {
        return sharedPromptRef;
    }

    public int getSharedPromptLine() {
        return sharedPromptLine;
    }

    public void setSharedPrompt(String text, String ref, int line) {
        this.sharedPrompt = text;
        this.sharedPromptRef = ref;
        this.sharedPromptLine = line;
    }

    public String getPromptsDir() {
        return promptsDir;
    }

    public int getPromptsDirLine() {
        return promptsDirLine;
    }

    public void setPromptsDir(String dir, int line) {
        this.promptsDir = dir;
        this.promptsDirLine = line;
    }

    public void addMcpServer(McpServerDef mcp) {
        this.mcpServers.add(mcp);
    }

    public List<McpServerDef> getMcpServers() {
        return mcpServers;
    }

    public void addKnowledgeBase(KnowledgeDef kb) {
        this.knowledgeBases.add(kb);
    }

    public List<KnowledgeDef> getKnowledgeBases() {
        return knowledgeBases;
    }

    public void addRoutingPolicy(RoutingPolicyDef rp) {
        this.routingPolicies.add(rp);
    }

    public List<RoutingPolicyDef> getRoutingPolicies() {
        return routingPolicies;
    }

    public void addSchedule(ScheduleDef schedule) {
        this.schedules.add(schedule);
    }

    public List<ScheduleDef> getSchedules() {
        return schedules;
    }

    public void addTool(ToolDef tool) {
        this.tools.add(tool);
    }

    /** Tools declared with {@code tool Name { use: … }}. */
    public List<ToolDef> getTools() {
        return tools;
    }

    public void addProvider(ProviderDef provider) {
        this.providers.add(provider);
    }

    /** Model providers declared with {@code provider Name { use: … }}. */
    public List<ProviderDef> getProviders() {
        return providers;
    }

    public void addPersona(PersonaDef persona) {
        this.personas.add(persona);
    }

    /** Personas declared with {@code persona Name { role: … }}. */
    public List<PersonaDef> getPersonas() {
        return personas;
    }

    public void addDecision(DecisionDef decision) {
        this.decisions.add(decision);
    }

    /** Decisions declared with {@code decision Name { … }}. */
    public List<DecisionDef> getDecisions() {
        return decisions;
    }

    public void addLadder(LadderDef ladder) {
        this.ladders.add(ladder);
    }

    /** Ladders declared with {@code ladder Name { rung … }}. */
    public List<LadderDef> getLadders() {
        return ladders;
    }

    public void addMeasure(MeasureDef measure) {
        this.measures.add(measure);
    }

    /** Measures declared with {@code measure name(c) = …}. */
    public List<MeasureDef> getMeasures() {
        return measures;
    }

    public void addImport(String path) {
        addImport(path, 0);
    }

    public void addImport(String path, int line) {
        this.imports.add(path);
        this.importLines.add(line);
    }

    /** The line of each import, in the order of {@link #getImports()}; 0 when unknown. */
    public List<Integer> getImportLines() {
        return importLines;
    }

    public List<String> getImports() {
        return imports;
    }

    public void setAuditConfig(AuditConfig auditConfig) {
        this.auditConfig = auditConfig;
    }

    public AuditConfig getAuditConfig() {
        return auditConfig;
    }

    /** The run's budget ({@code budget { }} at top level), or null. */
    public BudgetDef getBudget() {
        return budget;
    }

    public void setBudget(BudgetDef budget) {
        this.budget = budget;
    }

    /** The {@code rate_limits { }} block, or null. */
    public RateLimitDef getRateLimits() {
        return rateLimits;
    }

    public void setRateLimits(RateLimitDef rateLimits) {
        this.rateLimits = rateLimits;
    }

    /**
     * Gives every decision that has neither a {@code ladder:} nor a {@code trust { }} block the
     * default ladder of the agent that proposes for it. Safe to call again after more has been
     * merged in: a decision that already has a ladder is left as it is.
     */
    public void applyAgentLadders() {
        for (DecisionDef d : decisions) {
            // a ladder written from a decision that said nothing of trust is only a stand-in: an
            // agent merged in from another file replaces it
            boolean standIn = d.isLadderFromTrust() && !d.isTrustGiven();
            if ((d.getLadder() != null && !standIn) || d.isTrustGiven() || d.getAgent() == null)
                continue;
            for (AgentDef a : agents) {
                if (!a.getName().equals(d.getAgent()) || a.getLadder() == null) continue;
                if (standIn) {
                    d.setLadderFromTrust(false);
                    d.getTrustMeasures().clear();
                    d.getTrustLabels().clear();
                    ladders.removeIf(l -> l.getName().equals(d.getLadder()));
                    d.getLadderArgs().clear();
                }
                d.setLadder(a.getLadder(), a.getLadderLine());
                d.setLadderFromAgent(true);
                d.getLadderArgs().putAll(a.getLadderArgs());
                if (d.getCeilingRung() == null && a.getCeilingRung() != null)
                    d.setCeilingRung(a.getCeilingRung(), a.getCeilingRungLine());
                break;
            }
        }
    }

    /**
     * Writes the ladder of each decision that has none from its {@code trust { }} block, where the
     * language can say it; see {@code TrustDesugar}.
     */
    public void applyTrustLadders() {
        io.github.llm4j.loom.ladder.TrustDesugar.apply(this);
    }

    public void merge(LoomScript other) {
        this.agents.addAll(other.agents);
        this.workflows.addAll(other.workflows);
        this.mcpServers.addAll(other.mcpServers);
        this.knowledgeBases.addAll(other.knowledgeBases);
        this.routingPolicies.addAll(other.routingPolicies);
        this.schedules.addAll(other.schedules);
        this.tools.addAll(other.tools);
        this.providers.addAll(other.providers);
        this.personas.addAll(other.personas);
        this.decisions.addAll(other.decisions);
        this.ladders.addAll(other.ladders);
        this.measures.addAll(other.measures);
        this.intentClassifiers.addAll(other.intentClassifiers);
        // Supervision travels with the agents it watches. The script merged last (the importing
        // file) wins the intervention defaults.
        this.supervisions.addAll(other.supervisions);
        if (other.intervention != null) {
            this.intervention = other.intervention;
        }
        if (this.auditConfig == null) {
            this.auditConfig = other.auditConfig;
        }
        if (this.budget == null) {
            this.budget = other.budget;
        }
        if (this.rateLimits == null) {
            this.rateLimits = other.rateLimits;
        }
    }
}
