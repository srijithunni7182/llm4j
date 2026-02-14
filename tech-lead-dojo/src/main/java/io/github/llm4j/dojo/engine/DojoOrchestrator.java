package io.github.llm4j.dojo.engine;

import io.github.llm4j.dojo.model.ProjectState;
import io.github.llm4j.dojo.model.SoftwareSystem;
import io.github.llm4j.dojo.model.StakeholderProfile;
import io.github.llm4j.dojo.model.DojoEvent;
import io.github.llm4j.dojo.model.DojoOption;
import io.github.llm4j.agent.ReActAgent;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages the simulation lifecycle.
 */
public class DojoOrchestrator {

    private final AtomicReference<ProjectState> currentState = new AtomicReference<>();
    private final List<StakeholderProfile> stakeholders;
    private final SystemGenerator systemGenerator;

    public DojoOrchestrator(SystemGenerator systemGenerator, List<StakeholderProfile> stakeholders) {
        this.systemGenerator = systemGenerator;
        this.stakeholders = stakeholders;
    }

    public ProjectState startNewSimulation() {
        SoftwareSystem system = systemGenerator.generateSystem();
        ProjectState initialState = ProjectState.initial(system, 15, stakeholders); // 15 day sprint
        currentState.set(initialState);
        return initialState;
    }

    public ProjectState getCurrentState() {
        return currentState.get();
    }

    /**
     * Advances the simulation by applying the user's choice and generating the next
     * event.
     */
    public TurnResult advanceTurn(DojoOption selectedOption) {
        ProjectState state = currentState.get();
        if (state.isGameOver()) {
            throw new IllegalStateException("Game is over");
        }

        // 1. Apply metrics impact
        Map<String, Integer> currentMetrics = new ConcurrentHashMap<>(state.metrics());
        if (selectedOption != null) {
            selectedOption.hiddenImpact().forEach((key, value) -> currentMetrics.merge(key, value, Integer::sum));
        }

        // 2. Advance time
        int nextIteration = state.currentIteration() + 1;
        boolean isGameOver = nextIteration > state.maxIterations();

        // 3. Generate next event (if not game over)
        DojoEvent nextEvent = null;
        if (!isGameOver) {
            ScenarioEngine scenarioEngine = new ScenarioEngine(); // In real app, inject this
            nextEvent = scenarioEngine.generateEvent(state, stakeholders);
        }

        // 4. Update state
        ProjectState newState = new ProjectState(
                nextIteration,
                state.maxIterations(),
                state.systemDefinition(),
                currentMetrics,
                state.stakeholderStatuses(),
                stakeholders,
                isGameOver);
        currentState.set(newState);

        return new TurnResult(newState, nextEvent,
                selectedOption != null ? "You chose: " + selectedOption.description() : "Simulation Started");
    }

    public record TurnResult(ProjectState state, DojoEvent event, String feedback) {
    }

    // TODO: Implement turn advancement logic
}
