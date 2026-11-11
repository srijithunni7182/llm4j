package io.github.llm4j.loom.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code expose Name { workflow: W description: "…" agent_channel: Desk … }}: a workflow that others
 * may call over A2A. Which keys and values are allowed is checked at load time.
 */
public final class ExposeDef extends NamedSettings {

    /** One skill the card advertises. */
    public record Skill(String id, String name, String description, int line) {}

    private final List<Skill> skills = new ArrayList<>();
    private BudgetDef budget;

    public ExposeDef(String name) {
        super(name);
    }

    /** The skills written in {@code skills: [ … ]}; empty means one is derived from the workflow. */
    public List<Skill> getSkills() {
        return skills;
    }

    /** The {@code budget { … }} block, or null. */
    public BudgetDef getBudget() {
        return budget;
    }

    public void setBudget(BudgetDef budget) {
        this.budget = budget;
    }
}
