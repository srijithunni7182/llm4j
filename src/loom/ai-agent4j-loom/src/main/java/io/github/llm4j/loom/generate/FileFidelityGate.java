package io.github.llm4j.loom.generate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * G5 for a project that keeps its plan in a file: the script is compared with the plan as the trial
 * copy has it. Passes when there is no plan.
 */
public final class FileFidelityGate implements Gate {

    private final String planRel;

    public FileFidelityGate(String planRel) {
        this.planRel = planRel;
    }

    @Override
    public String id() {
        return "G5";
    }

    @Override
    public GateResult run(Workspace ws, Plan ignored) {
        Path file = ws.dir().resolve(planRel);
        if (!Files.isRegularFile(file)) return GateResult.pass("G5", "plan");
        try {
            Plan plan = PlanJson.read(Files.readString(file, StandardCharsets.UTF_8));
            return new FidelityGate().run(ws, plan);
        } catch (Exception e) {
            return new GateResult(
                    "G5",
                    "plan",
                    List.of(
                            new GateResult.Problem(
                                    "plan.json",
                                    0,
                                    "",
                                    "plan.json cannot be read: " + e.getMessage())));
        }
    }
}
