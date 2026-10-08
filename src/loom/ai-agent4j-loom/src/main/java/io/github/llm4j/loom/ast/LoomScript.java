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
    private final List<String> imports = new ArrayList<>();
    private final List<Integer> importLines = new ArrayList<>();
    private String promptsDir;
    private int promptsDirLine;
    private AuditConfig auditConfig;
    private BudgetDef budget;
    private RateLimitDef rateLimits;

    public void addAgent(AgentDef agent) {
        this.agents.add(agent);
    }

    public void addWorkflow(WorkflowDef workflow) {
        this.workflows.add(workflow);
    }

    public List<AgentDef> getAgents() {
        return agents;
    }

    public List<WorkflowDef> getWorkflows() { return workflows; }

    /** The folder named by {@code prompts: "./prompts"}, relative to the script; null when the script does not name one. */
    public String getPromptsDir() { return promptsDir; }
    public int getPromptsDirLine() { return promptsDirLine; }
    public void setPromptsDir(String dir, int line) { this.promptsDir = dir; this.promptsDirLine = line; }

    public void addMcpServer(McpServerDef mcp) { this.mcpServers.add(mcp); }
    public List<McpServerDef> getMcpServers() { return mcpServers; }

    public void addKnowledgeBase(KnowledgeDef kb) { this.knowledgeBases.add(kb); }
    public List<KnowledgeDef> getKnowledgeBases() { return knowledgeBases; }

    public void addRoutingPolicy(RoutingPolicyDef rp) { this.routingPolicies.add(rp); }
    public List<RoutingPolicyDef> getRoutingPolicies() { return routingPolicies; }

    public void addSchedule(ScheduleDef schedule) { this.schedules.add(schedule); }
    public List<ScheduleDef> getSchedules() { return schedules; }

    public void addTool(ToolDef tool) { this.tools.add(tool); }
    /** Tools declared with {@code tool Name { use: … }}. */
    public List<ToolDef> getTools() { return tools; }

    public void addProvider(ProviderDef provider) { this.providers.add(provider); }
    /** Model providers declared with {@code provider Name { use: … }}. */
    public List<ProviderDef> getProviders() { return providers; }

    public void addPersona(PersonaDef persona) { this.personas.add(persona); }
    /** Personas declared with {@code persona Name { role: … }}. */
    public List<PersonaDef> getPersonas() { return personas; }

    public void addDecision(DecisionDef decision) { this.decisions.add(decision); }
    /** Decisions declared with {@code decision Name { … }}. */
    public List<DecisionDef> getDecisions() { return decisions; }

    public void addImport(String path) { addImport(path, 0); }
    public void addImport(String path, int line) {
        this.imports.add(path);
        this.importLines.add(line);
    }
    /** The line of each import, in the order of {@link #getImports()}; 0 when unknown. */
    public List<Integer> getImportLines() { return importLines; }
    public List<String> getImports() { return imports; }

    public void setAuditConfig(AuditConfig auditConfig) { this.auditConfig = auditConfig; }
    public AuditConfig getAuditConfig() { return auditConfig; }

    /** The run's budget ({@code budget { }} at top level), or null. */
    public BudgetDef getBudget() { return budget; }
    public void setBudget(BudgetDef budget) { this.budget = budget; }

    /** The {@code rate_limits { }} block, or null. */
    public RateLimitDef getRateLimits() { return rateLimits; }
    public void setRateLimits(RateLimitDef rateLimits) { this.rateLimits = rateLimits; }

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
