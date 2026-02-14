package io.github.llm4j.dojo.model;

import java.util.List;

/**
 * Defines the procedurally generated software system.
 */
public record SoftwareSystem(
        String name,
        String description,
        Team userTeam,
        List<Team> dependencyTeams) {
    public record Team(String name, String responsibility, String techLeadName) {
    }
}
