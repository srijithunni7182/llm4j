package io.github.llm4j.dojo;

import io.github.llm4j.dojo.engine.DojoOrchestrator;
import io.github.llm4j.dojo.engine.StakeholderFactory;
import io.github.llm4j.dojo.engine.SystemGenerator;
import io.github.llm4j.dojo.model.DojoEvent;
import io.github.llm4j.dojo.model.DojoOption;
import io.github.llm4j.dojo.model.ProjectState;
import io.github.llm4j.dojo.model.StakeholderProfile;

import java.util.List;
import java.util.Scanner;

/**
 * A simple CLI runner to verify the simulation logic.
 */
public class ConsoleDojoApp {

    public static void main(String[] args) {
        System.out.println("=== TECH LEAD DOJO ===");

        // Setup
        StakeholderFactory stakeholderFactory = new StakeholderFactory();
        List<StakeholderProfile> stakeholders = stakeholderFactory.createStakeholdersForSystem();

        // Mock SystemGenerator
        SystemGenerator systemGenerator = new SystemGenerator();

        DojoOrchestrator orchestrator = new DojoOrchestrator(systemGenerator, stakeholders);

        // Start
        ProjectState state = orchestrator.startNewSimulation();
        System.out.println("Mission: " + state.systemDefinition().name());
        System.out.println("Description: " + state.systemDefinition().description());
        System.out.println("Your Role: Tech Lead of " + state.systemDefinition().userTeam().name());

        DojoOrchestrator.TurnResult turn = orchestrator.advanceTurn(null); // Initial kick-off

        Scanner scanner = new Scanner(System.in);

        while (!state.isGameOver()) {
            DojoEvent event = turn.event();
            if (event == null)
                break;

            System.out.println("\n------------------------------------------------");
            System.out.println("DAY " + state.currentIteration());
            System.out.println("METRICS: " + state.metrics());
            System.out.println("EVENT: " + event.title());
            System.out.println(event.description());

            if (event.source() != null) {
                System.out.println("SOURCE: " + event.source().name() + " (" + event.source().role() + ")");
            }

            System.out.println("\nOPTIONS:");
            for (int i = 0; i < event.options().size(); i++) {
                DojoOption opt = event.options().get(i);
                // HIDDEN IMPACT: We do NOT show the map here.
                System.out.println((i + 1) + ". " + opt.description());
            }

            System.out.print("\nChoose (1-3): ");
            int choice = 0;
            try {
                String input = scanner.nextLine();
                choice = Integer.parseInt(input) - 1;
            } catch (Exception e) {
                choice = 0;
            }

            if (choice < 0 || choice >= event.options().size())
                choice = 0;
            DojoOption selected = event.options().get(choice);

            turn = orchestrator.advanceTurn(selected);
            state = turn.state();

            System.out.println("=> " + turn.feedback());
        }

        System.out.println("\n=== GAME OVER ===");
        System.out.println("Final Metrics: " + state.metrics());
    }
}
