package io.github.llm4j.loom.generate;

import java.util.List;

/**
 * What one gate found.
 *
 * @param gate G1 to G5
 * @param name what it checks, in a word ({@code check}, {@code review}, {@code tests}, {@code
 *     coverage}, {@code plan})
 * @param problems each with where and why; empty when it passed
 */
public record GateResult(String gate, String name, List<Problem> problems) {

    public GateResult {
        problems = problems == null ? List.of() : List.copyOf(problems);
    }

    /**
     * One thing that failed. {@code file} and {@code line} are empty/0 when it is about the
     * project.
     */
    public record Problem(String file, int line, String rule, String message) {
        public String text() {
            return (file == null || file.isEmpty()
                            ? ""
                            : file + (line > 0 ? ":" + line : "") + ": ")
                    + (rule == null || rule.isEmpty() ? "" : "[" + rule + "] ")
                    + message;
        }
    }

    public boolean passed() {
        return problems.isEmpty();
    }

    public static GateResult pass(String gate, String name) {
        return new GateResult(gate, name, List.of());
    }

    /** The failure as text to feed back to the model. */
    public String feedback() {
        StringBuilder sb = new StringBuilder("Gate " + gate + " (" + name + ") failed:\n");
        for (Problem p : problems) sb.append("- ").append(p.text()).append('\n');
        return sb.toString();
    }
}
