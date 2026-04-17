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

    public void setAuditConfig(AuditConfig auditConfig) { this.auditConfig = auditConfig; }
    public AuditConfig getAuditConfig() { return auditConfig; }
}
