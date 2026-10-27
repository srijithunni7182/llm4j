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

    /**
     * How to fix the errors a model keeps making, in the terms of this language, added to what the
     * check says.
     */
    static String recipe(String message) {
        if (message == null) return "";
        if (message.contains("unsure is reserved"))
            return " Recipe: delete unsure from the choose list; keep `confidence at least 0.8 else escalate`, and the answer unsure then appears by itself, so branch on == \"unsure\".";
        if (message.contains("use: class needs class:"))
            return " Recipe: there are no Java tools in a generated project; use one of the built-in kinds (webhook, email, http, file, duckduckgo, ...) or ask a person.";
        if (message.contains("Expected statement"))
            return " Recipe: write one statement per line, with its settings (timeout, retry, expecting { ... }, on_failure { ... }) on following lines; a very long line is the usual cause.";
        return "";
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
                                p.message() + recipe(p.message())));
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
