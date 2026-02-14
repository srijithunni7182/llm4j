package io.github.llm4j.dojo.server.controller;

import io.github.llm4j.dojo.engine.DojoOrchestrator;
import io.github.llm4j.dojo.engine.DojoOrchestrator.TurnResult;
import io.github.llm4j.dojo.model.DojoOption;
import io.github.llm4j.dojo.model.ProjectState;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/simulation")
@CrossOrigin(origins = "*") // Allow React Frontend
public class SimulationController {

    private final DojoOrchestrator orchestrator;

    public SimulationController(DojoOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping("/start")
    public ProjectState startSimulation() {
        return orchestrator.startNewSimulation();
    }

    @GetMapping("/state")
    public ProjectState getState() {
        return orchestrator.getCurrentState();
    }

    @PostMapping("/advance")
    public TurnResult advanceTurn(@RequestBody(required = false) DojoOption selectedOption) {
        // If option is null, it might be the initial "Start Game" kick (though
        // startSimulation handles init state)
        // Or user selected "Do Nothing".
        return orchestrator.advanceTurn(selectedOption);
    }
}
