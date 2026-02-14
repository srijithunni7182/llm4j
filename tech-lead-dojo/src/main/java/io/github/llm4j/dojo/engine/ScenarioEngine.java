package io.github.llm4j.dojo.engine;

import io.github.llm4j.dojo.model.DojoEvent;
import io.github.llm4j.dojo.model.DojoOption;
import io.github.llm4j.dojo.model.ProjectState;
import io.github.llm4j.dojo.model.StakeholderProfile;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Generates events based on the current project state.
 */
public class ScenarioEngine {

        private final Random random = new Random();

        public DojoEvent generateEvent(ProjectState state, List<StakeholderProfile> stakeholders) {
                int entropy = random.nextInt(100);
                StakeholderProfile randomStakeholder = stakeholders.get(random.nextInt(stakeholders.size()));
                String systemName = state.systemDefinition().name();

                // 1. Critical Performance/Technical issues (Priority)
                if (state.metrics().getOrDefault("TechDebt", 0) > 60 && entropy > 70) {
                        return generateTechnicalCrisis(systemName);
                }

                // 2. Stakeholder requests / Scope creep
                if (entropy > 40) {
                        return generateStakeholderEvent(randomStakeholder, systemName);
                }

                // 3. Team / Morale events
                return generateTeamEvent(systemName);
        }

        private DojoEvent generateTechnicalCrisis(String systemName) {
                List<String> titles = List.of("System Degraded", "Technical Debt Maturity", "Infrastructure Collapse");
                List<String> descriptions = List.of(
                                "The " + systemName + " is struggling under legacy code. Latency is spiking.",
                                "A critical bug was found in the core module of " + systemName
                                                + ". Emergency patch needed.",
                                "Technical debt in " + systemName
                                                + " has reached a breaking point. Velocity has stalled.");

                int idx = random.nextInt(titles.size());
                return new DojoEvent(
                                UUID.randomUUID().toString(),
                                titles.get(idx),
                                descriptions.get(idx),
                                null,
                                List.of(
                                                new DojoOption("tech1", "Stop feature work to refactor.",
                                                                Map.of("TechDebt", -20, "StakeholderSatisfaction",
                                                                                -10)),
                                                new DojoOption("tech2", "Quick patch (Increases debt).",
                                                                Map.of("TechDebt", 15, "Quality", -10))));
        }

        private DojoEvent generateStakeholderEvent(StakeholderProfile stakeholder, String systemName) {
                String name = stakeholder.name();
                String role = stakeholder.role();

                return new DojoEvent(
                                UUID.randomUUID().toString(),
                                "Priority Shift: " + name,
                                name + " (" + role + ") is requesting an 'urgent' change for " + systemName + ".",
                                stakeholder,
                                List.of(
                                                new DojoOption("optA", "Accept the change.",
                                                                Map.of("StakeholderSatisfaction", 15, "Morale", -10,
                                                                                "TechDebt", 5)),
                                                new DojoOption("optB", "Push back.",
                                                                Map.of("StakeholderSatisfaction", -15, "Morale", 5)),
                                                new DojoOption("optC", "Negotiate scope.",
                                                                Map.of("StakeholderSatisfaction", 5, "Morale", -5))));
        }

        private DojoEvent generateTeamEvent(String systemName) {
                return new DojoEvent(
                                UUID.randomUUID().toString(),
                                "Team Morale Check",
                                "The team is feeling the heat from the " + systemName + " project pace.",
                                null,
                                List.of(
                                                new DojoOption("team1", "Organize a team lunch/break.",
                                                                Map.of("Morale", 20, "Budget", -10)),
                                                new DojoOption("team2", "Push through the sprint.",
                                                                Map.of("Morale", -15, "Quality", 10))));
        }
}
