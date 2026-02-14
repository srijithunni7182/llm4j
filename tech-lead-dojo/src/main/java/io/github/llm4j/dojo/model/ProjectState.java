package io.github.llm4j.dojo.model;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents the immutable state of the tech lead simulation.
 */
public record ProjectState(
        int currentIteration,
        int maxIterations,
        SoftwareSystem systemDefinition,
        Map<String, Integer> metrics, // Morale, TechDebt, Quality, Satisfaction
        Map<String, String> stakeholderStatuses, // StakeholderID -> Mood/Status
        List<StakeholderProfile> stakeholders,
        boolean isGameOver) {
    public ProjectState {
        if (metrics == null)
            metrics = new ConcurrentHashMap<>();
        if (stakeholderStatuses == null)
            stakeholderStatuses = new ConcurrentHashMap<>();
        if (stakeholders == null)
            stakeholders = List.of();
    }

    public static ProjectState initial(SoftwareSystem system, int maxIterations,
            List<StakeholderProfile> stakeholders) {
        return new ProjectState(
                1,
                maxIterations,
                system,
                Map.of(
                        "Morale", 80,
                        "TechDebt", 10,
                        "Quality", 90,
                        "StakeholderSatisfaction", 75),
                new ConcurrentHashMap<>(),
                stakeholders,
                false);
    }
}
