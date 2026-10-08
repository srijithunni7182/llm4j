package io.github.llm4j.loom.execution;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A script asks for something the runtime can't honour — an unknown tool, a missing secret, a feature
 * that isn't supported yet. Thrown before any model is called, listing every problem with its line.
 */
public class LoomLoadException extends RuntimeException {

    private final List<ScriptValidator.Problem> problems;

    public LoomLoadException(List<ScriptValidator.Problem> problems) {
        super(message(problems));
        this.problems = List.copyOf(problems);
    }

    private static String message(List<ScriptValidator.Problem> problems) {
        return "The script can't run (" + problems.size() + " problem" + (problems.size() == 1 ? "" : "s") + "):\n"
                + problems.stream().map(p -> "  " + p).collect(Collectors.joining("\n"));
    }

    public List<ScriptValidator.Problem> problems() {
        return problems;
    }
}
