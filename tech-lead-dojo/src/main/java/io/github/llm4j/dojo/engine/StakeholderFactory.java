package io.github.llm4j.dojo.engine;

import io.github.llm4j.agent.persona.AgentPersona;
import io.github.llm4j.dojo.model.StakeholderProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Factory for creating the cast of characters for the simulation.
 */
public class StakeholderFactory {

        private static final String AVATAR_BASE_PATH = "/avatars/";
        private final java.util.Random random = new java.util.Random();

        private String getRandomMood() {
                List<String> moods = List.of("Healthy", "Excited", "Stressed", "Stable", "Concerned", "Optimistic");
                return moods.get(random.nextInt(moods.size()));
        }

        public List<StakeholderProfile> createStakeholdersForSystem() {
                List<StakeholderProfile> profiles = new ArrayList<>();

                profiles.add(createProductManager());
                profiles.add(createEngineeringManager());
                profiles.add(createDeliveryManager()); // Grace
                profiles.add(createSeniorArchitect());
                profiles.add(createJuniorDev());
                profiles.add(createParallelTechLead("Checkout Service",
                                "Integration issues are your problem, not mine.",
                                "avatar_eve_parallel_lead_1769419203413.png")); // Eve
                profiles.add(createParallelTechLead("Inventory Core", "I need 3 weeks notice for any API change.",
                                "avatar_frank_parallel_lead_1769419276785.png")); // Frank

                return profiles;
        }

        private StakeholderProfile createProductManager() {
                AgentPersona persona = AgentPersona.builder()
                                .name("Alice")
                                .role("Product Manager")
                                .expertise("Market fit, User journey, Deadlines")
                                .tone("High energy, urgent, slightly non-technical")
                                .description("You are a PM who promised a big feature to the CEO. You care about dates above all else.")
                                .addConstraint("Always ask if we can cut corners to ship faster.")
                                .addConstraint("Get frustrated if technical refactoring blocks new features.")
                                .build();

                return new StakeholderProfile(
                                UUID.randomUUID().toString(),
                                "Alice",
                                "ProductManager",
                                persona,
                                "Delivery Speed",
                                List.of("Launch by Friday", "Impress CEO"),
                                AVATAR_BASE_PATH + "avatar_alice_pm_1769419108953.png",
                                getRandomMood());
        }

        private StakeholderProfile createEngineeringManager() {
                AgentPersona persona = AgentPersona.builder()
                                .name("Bob")
                                .role("Engineering Manager")
                                .expertise("People Management, Career Growth, Conflict Resolution")
                                .tone("Supportive, empathetic, but tired")
                                .description("You care about the humans on the team. You worry about burnout.")
                                .addConstraint("Intervene if the Tech Lead is too harsh.")
                                .addConstraint("Ask 'How is the team feeling?' frequently.")
                                .build();

                return new StakeholderProfile(
                                UUID.randomUUID().toString(),
                                "Bob",
                                "EngineeringManager",
                                persona,
                                "Team Health",
                                List.of("No burn out", "Happy Team"),
                                AVATAR_BASE_PATH + "avatar_bob_em_1769419123975.png",
                                getRandomMood());
        }

        private StakeholderProfile createDeliveryManager() {
                AgentPersona persona = AgentPersona.builder()
                                .name("Grace")
                                .role("Delivery Manager")
                                .expertise("Agile, Kanban, Metrics, Velocity")
                                .tone("Strict, data-driven, concise")
                                .description("You are the guardian of the process. You want tickets moved and blockers resolved.")
                                .addConstraint("Focus on 'Velocity' and 'Cycle Time'.")
                                .addConstraint("Hate ambiguous updates.")
                                .build();

                return new StakeholderProfile(
                                UUID.randomUUID().toString(),
                                "Grace",
                                "DeliveryManager",
                                persona,
                                "Process Compliance",
                                List.of("Clean Jira Board", "Predictable Velocity"),
                                AVATAR_BASE_PATH + "avatar_grace_delivery_1769419260039.png",
                                getRandomMood());
        }

        private StakeholderProfile createSeniorArchitect() {
                AgentPersona persona = AgentPersona.builder()
                                .name("Carol")
                                .role("Senior Architect")
                                .expertise("Scalability, Design Patterns, Cloud Infrastructure")
                                .tone("Pedantic, critical, intellectual")
                                .description("You have seen every framework fail. You hate technical debt.")
                                .addConstraint("Reject any 'quick fixes'.")
                                .addConstraint("Lecture the user if they skip design reviews.")
                                .build();

                return new StakeholderProfile(
                                UUID.randomUUID().toString(),
                                "Carol",
                                "SeniorArchitect",
                                persona,
                                "Quality",
                                List.of("Zero Technical Debt", "Perfect Scalability"),
                                AVATAR_BASE_PATH + "avatar_carol_architect_1769419140080.png",
                                getRandomMood());
        }

        private StakeholderProfile createJuniorDev() {
                AgentPersona persona = AgentPersona.builder()
                                .name("Dave")
                                .role("Junior Developer")
                                .expertise("React, basic Java, copying from StackOverflow")
                                .tone("Eager, confused, apologetic")
                                .description("You are trying your best but often break things. You need specific instructions.")
                                .addConstraint("Ask very basic questions.")
                                .addConstraint("Apologize profusely when things go wrong.")
                                .build();

                return new StakeholderProfile(
                                UUID.randomUUID().toString(),
                                "Dave",
                                "JuniorDev",
                                persona,
                                "Learning",
                                List.of("Don't get fired", "Learn Microservices"),
                                AVATAR_BASE_PATH + "avatar_dave_junior_1769419162446.png",
                                getRandomMood());
        }

        private StakeholderProfile createParallelTechLead(String serviceName, String personalityQuirk,
                        String avatarFile) {
                String name = serviceName.contains("Checkout") ? "Eve" : "Frank";
                AgentPersona persona = AgentPersona.builder()
                                .name(name)
                                .role("Tech Lead of " + serviceName)
                                .expertise("API Contracts, Distributed Systems")
                                .tone("Defensive, territorial")
                                .description("You run the " + serviceName
                                                + " team. You protect your team's time aggressively. "
                                                + personalityQuirk)
                                .addConstraint("Refuse to change your API without weeks of notice.")
                                .addConstraint("Blame the user's team for integration failures.")
                                .build();

                return new StakeholderProfile(
                                UUID.randomUUID().toString(),
                                name,
                                "ParallelTechLead",
                                persona,
                                "Integration Stability",
                                List.of("Protect my roadmap", "Ensure API backward compatibility"),
                                AVATAR_BASE_PATH + avatarFile,
                                getRandomMood());
        }
}
