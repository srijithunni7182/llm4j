package io.github.llm4j.loom.generate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * G1: {@code weave check --no-env} finds no error. What checks is behind {@link Checker}, so the
 * gate can be tested alone.
 */
public final class CheckGate implements Gate {

    /**
     * The errors of the language's own checks for a script, with the line each is on (0 when none).
     */
    public interface Checker {
        List<GateResult.Problem> errors(Path script, Path loot) throws Exception;
    }

    private final Checker checker;

    public CheckGate(Checker checker) {
        this.checker = checker;
    }

    @Override
    public String id() {
        return "G1";
    }

    @Override
    public GateResult run(Workspace ws, Plan plan) {
        Path script = ws.script();
        if (!Files.isRegularFile(script))
            return new GateResult(
                    "G1",
                    "check",
                    List.of(
                            new GateResult.Problem(
                                    "main.loom", 0, "", "The project has no main.loom.")));
        try {
            List<GateResult.Problem> found = checker.errors(script, ws.loot());
            List<GateResult.Problem> out = new ArrayList<>();
            for (GateResult.Problem p : found)
                out.add(
                        new GateResult.Problem(
                                p.file() == null || p.file().isEmpty()
                                        ? script.getFileName().toString()
                                        : p.file(),
                                p.line(),
                                p.rule(),
                                p.message()));
            return new GateResult("G1", "check", out);
        } catch (Exception e) {
            return new GateResult(
                    "G1",
                    "check",
                    List.of(
                            new GateResult.Problem(
                                    "main.loom", 0, "", String.valueOf(e.getMessage()))));
        }
    }
}
