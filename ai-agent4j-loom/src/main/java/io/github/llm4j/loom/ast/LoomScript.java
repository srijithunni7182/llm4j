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
    private final List<String> imports = new ArrayList<>();
    private AuditConfig auditConfig;

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

    public void addMcpServer(McpServerDef mcp) { this.mcpServers.add(mcp); }
    public List<McpServerDef> getMcpServers() { return mcpServers; }

    public void addKnowledgeBase(KnowledgeDef kb) { this.knowledgeBases.add(kb); }
    public List<KnowledgeDef> getKnowledgeBases() { return knowledgeBases; }

    public void addRoutingPolicy(RoutingPolicyDef rp) { this.routingPolicies.add(rp); }
    public List<RoutingPolicyDef> getRoutingPolicies() { return routingPolicies; }

    public void addSchedule(ScheduleDef schedule) { this.schedules.add(schedule); }
    public List<ScheduleDef> getSchedules() { return schedules; }

    public void addImport(String path) { this.imports.add(path); }
    public List<String> getImports() { return imports; }

    public void setAuditConfig(AuditConfig auditConfig) { this.auditConfig = auditConfig; }
    public AuditConfig getAuditConfig() { return auditConfig; }

    public void merge(LoomScript other) {
        this.agents.addAll(other.agents);
        this.workflows.addAll(other.workflows);
        this.mcpServers.addAll(other.mcpServers);
        this.knowledgeBases.addAll(other.knowledgeBases);
        this.routingPolicies.addAll(other.routingPolicies);
        this.schedules.addAll(other.schedules);
        if (this.auditConfig == null) {
            this.auditConfig = other.auditConfig;
        }
    }
}
