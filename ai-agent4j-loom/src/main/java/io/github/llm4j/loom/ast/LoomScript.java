package io.github.llm4j.loom.ast;

import java.util.ArrayList;
import java.util.List;

public class LoomScript implements Node {
    private final List<AgentDef> agents = new ArrayList<>();
    private final List<WorkflowDef> workflows = new ArrayList<>();

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
}
