package io.github.llm4j.loom.graph;

import java.util.List;
import java.util.Map;

/** The settings of an agent that a node names, for the details card. Unset settings are {@code null} or empty; {@code prompt} is set only when the agent names a prompt file. */
public record AgentInfo(
        String name,
        String model,
        Double temperature,
        String persona,
        List<String> tools,
        List<String> mcp,
        List<String> skills,
        List<String> knowledge,
        List<String> approve,
        boolean approveAll,
        Map<String, Object> budget,
        Integer maxIterations,
        SourceRef source,
        PromptInfo prompt) {

    public AgentInfo {
        tools = List.copyOf(tools);
        mcp = List.copyOf(mcp);
        skills = List.copyOf(skills);
        knowledge = List.copyOf(knowledge);
        approve = List.copyOf(approve);
        budget = budget == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(budget));
    }

    /** As the 13-component form, for an agent with no prompt file. */
    public AgentInfo(String name, String model, Double temperature, String persona, List<String> tools, List<String> mcp,
            List<String> skills, List<String> knowledge, List<String> approve, boolean approveAll,
            Map<String, Object> budget, Integer maxIterations, SourceRef source) {
        this(name, model, temperature, persona, tools, mcp, skills, knowledge, approve, approveAll, budget, maxIterations, source, null);
    }

    public AgentInfo withPrompt(PromptInfo info) {
        return new AgentInfo(name, model, temperature, persona, tools, mcp, skills, knowledge, approve, approveAll, budget, maxIterations, source, info);
    }
}
