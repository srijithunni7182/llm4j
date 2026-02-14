package io.github.llm4j.dojo.engine;

import io.github.llm4j.dojo.model.SoftwareSystem;
import io.github.llm4j.dojo.model.SoftwareSystem.Team;

import java.util.List;
import java.util.Random;

/**
 * Uses LLM to generate unique software systems for the simulation.
 */
public class SystemGenerator {

    private final Random random = new Random();

    public SoftwareSystem generateSystem() {
        List<SoftwareSystem> templates = List.of(
                new SoftwareSystem(
                        "AI-Powered Logistics Engine",
                        "A high-throughput routing system for autonomous delivery drones.",
                        new Team("Core Routing", "Pathfinding Algorithms", "YOU"),
                        List.of(
                                new Team("Mobile App", "Driver Interface", "Sarah (Team Alpha)"),
                                new Team("Billing", "Invoicing Subsystem", "Mike (Team Beta)"))),
                new SoftwareSystem(
                        "Global FinTech Ledger",
                        "A real-time transactional database for cross-border payments.",
                        new Team("Ledger Core", "Transaction atomicity", "YOU"),
                        List.of(
                                new Team("Compliance", "AML/KYC Checks", "Legal Team"),
                                new Team("Mobile Banking", "iOS/Android frontend", "Frontend Guild"))),
                new SoftwareSystem(
                        "HealthTech Patient Portal",
                        "A secure infrastructure for managing sensitive medical data across hospitals.",
                        new Team("API Gateway", "HIPAA-compliant data exchange", "YOU"),
                        List.of(
                                new Team("Auth Service", "Identity Management", "Security Team"),
                                new Team("Legacy Bridge", "Old mainframe integration", "Enterprise Ops"))));

        return templates.get(random.nextInt(templates.size()));
    }
}
