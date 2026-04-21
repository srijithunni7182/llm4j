package io.github.llm4j.loom.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * AST node for an LLM Routing Policy.
 */
public class RoutingPolicyDef implements Node {
    private final String name;
    private String strategy;
    private String primaryModel;
    private final List<String> fallbackModels = new ArrayList<>();

    public RoutingPolicyDef(String name) {
        this.name = name;
    }

    public String getName() { return name; }
    public String getStrategy() { return strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy; }
    public String getPrimaryModel() { return primaryModel; }
    public void setPrimaryModel(String primaryModel) { this.primaryModel = primaryModel; }
    public List<String> getFallbackModels() { return fallbackModels; }
    public void addFallbackModel(String model) { this.fallbackModels.add(model); }
}
